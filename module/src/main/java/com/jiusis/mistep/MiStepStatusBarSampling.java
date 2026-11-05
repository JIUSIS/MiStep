package com.jiusis.mistep;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Keep the ROM's luminance sampler over the real, magnified status bar. */
final class MiStepStatusBarSampling {
    private static final String CALLBACK =
            "com.miui.systemui.statusbar.core.StatusBarRegionSamplingInteractor$regionSamplingHelper$1";
    private static final WeakHashMap<Object, Boolean> helpers = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> statusListeners = new WeakHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void install(ClassLoader loader) {
        try {
            Class<?> callback = XposedHelpers.findClass(CALLBACK, loader);
            XposedHelpers.findAndHookMethod(callback, "getSampledRegion", android.view.View.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getResult() instanceof Rect)
                        p.setResult(physical((Rect) p.getResult(), MiStepHost.activeStatusBarTransform()));
                }
            });
            Class<?> helper = XposedHelpers.findClass(
                    "com.android.wm.shell.shared.handles.RegionSamplingHelper", loader);
            XposedBridge.hookAllConstructors(helper, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    track(p.thisObject);
                }
            });
            Class<?> composition = XposedHelpers.findClass("android.view.CompositionSamplingListener", loader);
            XposedBridge.hookAllMethods(composition, "register", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 4 || !Integer.valueOf(0).equals(p.args[1])
                            || !usesMainComposition(p.args[0], MiStepHost.activeStatusBarTransform())) return;
                    // The OEM's default bar can remain below the main app in the Home task.
                    // Its stop layer would omit that app even with the correct physical region.
                    // Sample the displayed main region; native median/contrast policy stays intact.
                    p.args[2] = null;
                    XposedBridge.log("MiStep: main status sampling region=" + p.args[3]
                            + " stop=display composition");
                }
            });
            XposedHelpers.findAndHookMethod(callback, "onRegionDarknessChanged", boolean.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (MiStepHost.activeStatusBarTransform() != null)
                        XposedBridge.log("MiStep: native main status sampling dark=" + p.args[0]);
                }
            });
            XposedHelpers.findAndHookMethod(helper, "start", Rect.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!isStatusHelper(p.thisObject) || !(p.args[0] instanceof Rect)) return;
                    Object cb = XposedHelpers.getObjectField(p.thisObject, "mCallback");
                    Object owner = XposedHelpers.getObjectField(cb, "this$0");
                    // The native resample runnable passes this logical rectangle directly.
                    // Keep samplingBarBounds logical for DarkIconDispatcher's View tests.
                    if (p.args[0].equals(XposedHelpers.getObjectField(owner, "samplingBounds")))
                        p.args[0] = physical((Rect) p.args[0], MiStepHost.activeStatusBarTransform());
                }
            });
            XposedBridge.log("MiStep: native status-bar sampling follows main geometry");
        } catch (Throwable unavailable) {
            XposedBridge.log("MiStep: native status-bar sampling unavailable: " + unavailable);
        }
    }

    private static boolean isStatusHelper(Object helper) {
        Object cb = XposedHelpers.getObjectField(helper, "mCallback");
        return cb != null && CALLBACK.equals(cb.getClass().getName());
    }

    static void track(Object helper) {
        if (!isStatusHelper(helper)) return;
        synchronized (helpers) {
            helpers.put(helper, Boolean.TRUE);
            Object listener = XposedHelpers.getObjectField(helper, "mSamplingListener");
            if (listener != null) statusListeners.put(listener, Boolean.TRUE);
        }
    }

    static boolean usesMainComposition(Object listener, float[] transform) {
        if (transform == null || transform.length < 3 || !Float.isFinite(transform[0])
                || transform[0] <= 0 || !Float.isFinite(transform[1]) || !Float.isFinite(transform[2]))
            return false;
        synchronized (helpers) { return statusListeners.containsKey(listener); }
    }

    static Rect physical(Rect logical, float[] transform) {
        if (logical == null || logical.isEmpty() || transform == null || transform.length < 3)
            return logical;
        // Host geometry is [scale, offsetY, offsetX].
        float scale = transform[0], x = transform[2], y = transform[1];
        if (!Float.isFinite(scale) || scale <= 0f || !Float.isFinite(x) || !Float.isFinite(y))
            return logical;
        return new Rect((int) Math.floor(logical.left * scale + x),
                (int) Math.floor(logical.top * scale + y),
                (int) Math.ceil(logical.right * scale + x),
                (int) Math.ceil(logical.bottom * scale + y));
    }

    static void geometryChanged() {
        main.post(() -> {
            Object[] current;
            synchronized (helpers) { current = helpers.keySet().toArray(); }
            for (Object helper : current) {
                try {
                    if (ShellCompat.hasMethod(helper.getClass(), "updateSamplingRect", 0)) {
                        XposedHelpers.callMethod(helper, "updateSamplingRect");
                    } else {
                        // OS3's layout callback resamples through the same native callback.
                        Object view = XposedHelpers.getObjectField(helper, "mSampledView");
                        XposedHelpers.callMethod(helper, "onLayoutChange", view, 0, 0, 0, 0, 0, 0, 0, 0);
                    }
                }
                catch (Throwable unavailable) {
                    XposedBridge.log("MiStep: status sampling refresh skipped: " + unavailable);
                }
            }
        });
    }

    private MiStepStatusBarSampling() {}
}
