package com.jiusis.mistep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** OS3's native penetration replays into global monitors after cancelling spy windows. */
final class MiStepBottomReplayInput {
    static final String ACTION = "com.jiusis.mistep.BOTTOM_INPUT_OPTIONS";
    private static MiStepBottomReplayInput instance;
    private final Context context;
    private final ClassLoader loader;
    private final Handler handler;
    private final BottomCenterSwipe swipe = new BottomCenterSwipe();
    private int selected;
    private volatile Object receiver;
    private MiStepPassiveInputChannel channel;
    private long downTime;

    private MiStepBottomReplayInput(Context context, ClassLoader loader, Handler handler) {
        this.context = context;
        this.loader = loader;
        this.handler = handler;
    }

    static void install(Context context, ClassLoader loader, Handler handler) {
        if (instance != null) return;
        MiStepBottomReplayInput bridge = new MiStepBottomReplayInput(context, loader, handler);
        instance = bridge;
        handler.post(bridge::start);
    }

    static void setMode(int mode) {
        MiStepBottomReplayInput bridge = instance;
        if (bridge != null) bridge.handler.post(() -> {
            bridge.selected = mode;
            XposedBridge.log("MiStepProbe: bottom replay option=" + mode);
            bridge.reconcile();
        });
    }

    private void start() {
        try {
            XposedBridge.log("MiStepProbe: bottom replay initializing build=" + Build.VERSION.INCREMENTAL);
            Class<?> dispatcher = XposedHelpers.findClass("com.android.server.wm.PointerEventDispatcher", loader);
            XposedBridge.hookAllMethods(dispatcher, "onInputEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.thisObject != receiver || p.args.length != 1
                            || !(p.args[0] instanceof MotionEvent e)
                            || !e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return;
                    // Copy only numbers; the receiver retains ownership of the MotionEvent.
                    Sample sample = new Sample(e.getActionMasked(), e.getPointerId(0), e.getPointerCount(),
                            e.getRawX(), e.getRawY(), e.getEventTime(),
                            (Integer) XposedHelpers.callMethod(e, "getDisplayId"));
                    Object source = p.thisObject;
                    handler.post(() -> { if (source == receiver) motion(sample); });
                }
            });
            context.getContentResolver().registerContentObserver(Settings.Global.getUriFor("hide_gesture_line"),
                    false, new ContentObserver(handler) {
                        @Override public void onChange(boolean changed) { reconcile(); }
                    });
            context.getContentResolver().registerContentObserver(Settings.Global.getUriFor("mistep_zoom"),
                    false, new ContentObserver(handler) {
                        @Override public void onChange(boolean changed) { reconcile(); }
                    });
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            context.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) { reconcile(); }
            }, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
            reconcile();
        } catch (Throwable error) {
            close();
            XposedBridge.log("MiStepProbe: bottom replay unavailable " + error);
        }
    }

    private boolean allowed() {
        String zoom = Settings.Global.getString(context.getContentResolver(), "mistep_zoom");
        return selected == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                && EntryGestureSettings.lineHidden(context) && MiStepEntryGuard.canOpen(context)
                && zoom != null && zoom.matches(".*\\|1(?:\\.0+)? 0 0");
    }

    private void reconcile() {
        try {
            if (!allowed()) { close(); return; }
            if (receiver != null) return;
            Object service = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.ServiceManager", loader), "getService", "input");
            channel = MiStepPassiveInputChannel.open(service, "MiStepBottomReplay", loader);
            receiver = XposedHelpers.newInstance(
                    XposedHelpers.findClass("com.android.server.wm.PointerEventDispatcher", loader), channel.input);
            XposedBridge.log("MiStepProbe: bottom replay monitor ready");
        } catch (Throwable error) {
            close();
            XposedBridge.log("MiStepProbe: bottom replay monitor failed " + error);
        }
    }

    private void close() {
        Object old = receiver;
        receiver = null;
        swipe.reset();
        if (old != null) try { XposedHelpers.callMethod(old, "dispose"); } catch (Throwable ignored) { }
        if (channel != null) channel.close();
        channel = null;
    }

    private void motion(Sample e) {
        try {
            if (e.display != 0) return;
            if (e.action == MotionEvent.ACTION_CANCEL || e.count != 1) { swipe.reset(); return; }
            if (e.action != MotionEvent.ACTION_DOWN && !swipe.active()) return;
            if (!allowed()) { swipe.reset(); return; }
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Point size = new Point();
            wm.getDefaultDisplay().getRealSize(size);
            int rotation = wm.getDefaultDisplay().getRotation();
            float density = context.getResources().getDisplayMetrics().density;
            if (e.action == MotionEvent.ACTION_DOWN) {
                downTime = e.time;
                swipe.down(e.pointer, e.count, e.x, e.y, e.time, size.x, size.y, rotation,
                        Math.round(8f * density));
            } else if (e.action == MotionEvent.ACTION_MOVE || e.action == MotionEvent.ACTION_UP) {
                if (swipe.move(e.pointer, e.count, e.x, e.y, e.time, size.x, size.y, rotation,
                        Math.round(58f * density), Math.round(24f * density))) {
                    context.sendBroadcast(new Intent("com.jiusis.mistep.DEBUG")
                            .setPackage("com.android.systemui")
                            .putExtra("legacyBottomSign", swipe.sign())
                            .putExtra("legacyBottomDown", downTime)
                            .putExtra("legacyBottomTime", e.time));
                    XposedBridge.log("MiStepProbe: bottom replay accepted sign=" + swipe.sign());
                }
                if (e.action == MotionEvent.ACTION_UP) swipe.reset();
            } else {
                swipe.reset();
            }
        } catch (Throwable error) {
            swipe.reset();
            XposedBridge.log("MiStepProbe: bottom replay stopped " + error);
        }
    }

    private record Sample(int action, int pointer, int count, float x, float y, long time, int display) { }
}
