package com.jianguo.mistep;

import android.app.Application;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Point;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.hardware.display.DisplayManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Applies the main-area raw-coordinate inverse only while an app dispatches a pointer event. */
final class AppRawInputHook {
    private static final String SETTING = "mistep_zoom";
    private static final String EXTRA = "com.jianguo.mistep.previousInputFrame";
    private static final ThreadLocal<InputFrame> ACTIVE_FRAME = new ThreadLocal<>();
    private static volatile RawInputMagnificationPolicy.Transform sTransform;
    private static boolean sObserverRegistered;

    private static final class InputFrame {
        final MotionEvent event;
        final RawInputMagnificationPolicy.Transform transform;

        InputFrame(MotionEvent event, RawInputMagnificationPolicy.Transform transform) {
            this.event = event;
            this.transform = transform;
        }
    }

    private AppRawInputHook() {}

    static void install(XC_LoadPackage.LoadPackageParam packageParam) {
        String packageName = packageParam.packageName;
        if (packageName == null || packageName.isEmpty()
                || "com.android.systemui".equals(packageName)
                || "com.miui.home".equals(packageName)
                || "com.jianguo.mistep".equals(packageName)) return;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    registerSettingsObserver((Context) param.args[0]);
                }
            });

            Class<?> view = XposedHelpers.findClass("android.view.View", packageParam.classLoader);
            XposedHelpers.findAndHookMethod(view, "dispatchPointerEvent", MotionEvent.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    MotionEvent event = (MotionEvent) param.args[0];
                    InputFrame previous = ACTIVE_FRAME.get();
                    param.setObjectExtra(EXTRA, previous);
                    RawInputMagnificationPolicy.Transform transform = sTransform;
                    if (!RawInputMagnificationPolicy.isActive(transform, System.currentTimeMillis())) {
                        sTransform = null;
                        return;
                    }
                    if (event == null
                            || (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0) return;
                    try {
                        Point size = displaySize((View) param.thisObject);
                        if (!RawInputMagnificationPolicy.mapsToDisplay(event.getRawX(),
                                event.getRawY(), transform, size.x, size.y)) return;
                        ACTIVE_FRAME.set(new InputFrame(event, transform));
                    } catch (Throwable ignored) {
                        ACTIVE_FRAME.set(previous);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    InputFrame previous = (InputFrame) param.getObjectExtra(EXTRA);
                    if (previous == null) ACTIVE_FRAME.remove();
                    else ACTIVE_FRAME.set(previous);
                }
            });

            XposedHelpers.findAndHookMethod(MotionEvent.class, "getRawX", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    remapRawCoordinate(param, true);
                }
            });
            XposedHelpers.findAndHookMethod(MotionEvent.class, "getRawY", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    remapRawCoordinate(param, false);
                }
            });
            XposedBridge.log("MiStepProbe: app raw-input hooks installed package=" + packageName);
        } catch (Throwable error) {
            XposedBridge.log("MiStepProbe: app raw-input hooks unavailable package="
                    + packageName + " error=" + error);
        }
    }

    private static void registerSettingsObserver(Context context) {
        try {
            synchronized (AppRawInputHook.class) {
                if (sObserverRegistered) return;
                sObserverRegistered = true;
            }
            Uri uri = Settings.Global.getUriFor(SETTING);
            ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override public void onChange(boolean selfChange, Uri changed) {
                    refresh(context);
                }

                @Override public void onChange(boolean selfChange) {
                    refresh(context);
                }
            };
            context.getContentResolver().registerContentObserver(uri, false, observer);
            refresh(context);
        } catch (Throwable error) {
            synchronized (AppRawInputHook.class) { sObserverRegistered = false; }
            sTransform = null;
            XposedBridge.log("MiStepProbe: app raw-input state unavailable " + error);
        }
    }

    private static void refresh(Context context) {
        try {
            String request = Settings.Global.getString(context.getContentResolver(), SETTING);
            sTransform = RawInputMagnificationPolicy.parseLease(request, System.currentTimeMillis());
        } catch (Throwable ignored) {
            sTransform = null;
        }
    }

    private static Point displaySize(View view) {
        DisplayManager manager = (DisplayManager) view.getContext()
                .getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        Point size = new Point();
        if (display != null) display.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) {
            size.x = view.getResources().getDisplayMetrics().widthPixels;
            size.y = view.getResources().getDisplayMetrics().heightPixels;
        }
        return size;
    }

    private static void remapRawCoordinate(XC_MethodHook.MethodHookParam param,
            boolean horizontal) {
        InputFrame frame = ACTIVE_FRAME.get();
        if (frame == null || frame.event != param.thisObject
                || !(param.getResult() instanceof Float)) return;
        float raw = (Float) param.getResult();
        float corrected = horizontal
                ? RawInputMagnificationPolicy.mapRawX(raw, frame.transform)
                : RawInputMagnificationPolicy.mapRawY(raw, frame.transform);
        param.setResult(corrected);
    }
}
