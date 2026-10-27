package com.jianguo.mistep;

import android.content.Context;
import android.provider.Settings;
import android.view.MotionEvent;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Normalize just the ROM's outside-collapse monitor, keeping the shared event intact. */
final class MiStepIslandMonitorInput {
    private static final String ORIGINAL = "mistep.island.monitor.original";
    private static final String COPY = "mistep.island.monitor.copy";

    private MiStepIslandMonitorInput() {}

    static void install(ClassLoader loader, Supplier<Context> context) {
        try {
            Class<?> listener = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.island.DynamicIslandGestureMonitor$listener$1",
                    loader);
            XposedBridge.hookAllMethods(listener, "onPointerEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MotionEvent copy = null;
                    try {
                        if (p.args.length != 1 || !(p.args[0] instanceof MotionEvent)) return;
                        MotionEvent original = (MotionEvent) p.args[0];
                        if (original.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                        float[] zoom = MiStepHost.activeIslandMonitorTransform();
                        if (zoom == null) return;
                        Context ctx = context.get();
                        if (ctx == null || Settings.Secure.getInt(ctx.getContentResolver(),
                                "one_handed_mode_activated", 0) != 0) return;
                        int display = (Integer) XposedHelpers.callMethod(original, "getDisplayId");
                        float[] point = IslandMonitorInputPolicy.logicalDown(true,
                                original.getActionMasked(), display, original.getX(), original.getY(),
                                original.getRawX(), original.getRawY(), zoom);
                        if (point == null) return;
                        copy = MotionEvent.obtain(original);
                        copy.setLocation(point[0], point[1]);
                        p.setObjectExtra(ORIGINAL, original);
                        p.setObjectExtra(COPY, copy);
                        p.args[0] = copy;
                    } catch (Throwable unavailable) {
                        if (copy != null) {
                            try {
                                Object original = p.getObjectExtra(ORIGINAL);
                                if (original != null) p.args[0] = original;
                                p.setObjectExtra(COPY, null);
                                copy.recycle();
                            } catch (Throwable ignored) { }
                        }
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object original = p.getObjectExtra(ORIGINAL);
                        Object copy = p.getObjectExtra(COPY);
                        if (original != null) p.args[0] = original;
                        if (copy instanceof MotionEvent) ((MotionEvent) copy).recycle();
                    } catch (Throwable ignored) { }
                }
            });
            XposedBridge.log("MiStep: Island outside-collapse monitor uses workspace coordinates");
        } catch (Throwable unavailable) {
            XposedBridge.log("MiStep: Island monitor input unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }
}
