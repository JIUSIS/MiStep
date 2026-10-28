package com.jiusis.mistep;

import android.graphics.Point;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** The native return-to-Home input consumer must use the same coordinates as Home. */
final class MiStepRecentsInput {
    private static final WeakHashMap<Object, Boolean> consumers = new WeakHashMap<>();

    static void install(ClassLoader loader, Supplier<float[]> transform) {
        try {
            Class<?> consumer = XposedHelpers.findClass("com.android.server.wm.InputConsumerImpl", loader);
            XC_MethodHook geometry = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length == 0 || !exact(p.thisObject)) return;
                    try { apply(p.thisObject, p.args[0], transform.get()); }
                    catch (Throwable unavailable) {
                        XposedBridge.log("MiStep: recents input geometry skipped: " + unavailable);
                    }
                }
            };
            XposedBridge.hookAllMethods(consumer, "layout", geometry);
            XposedBridge.hookAllMethods(consumer, "show", geometry);
            XposedBridge.log("MiStep: native recents input consumer follows main coordinates");
        } catch (Throwable unavailable) {
            XposedBridge.log("MiStep: recents input hook unavailable: " + unavailable);
        }
    }

    private static boolean exact(Object consumer) {
        if (!"recents_animation_input_consumer".equals(XposedHelpers.getObjectField(consumer, "mName")))
            return false;
        Object handle = XposedHelpers.getObjectField(consumer, "mWindowHandle");
        return handle != null && XposedHelpers.getIntField(handle, "displayId") == 0;
    }

    private static void apply(Object consumer, Object transaction, float[] transform) {
        synchronized (consumers) {
            boolean wasModified = Boolean.TRUE.equals(consumers.get(consumer));
            consumers.putIfAbsent(consumer, Boolean.FALSE);
            if (transform == null && !wasModified) return;
            Object surface = XposedHelpers.getObjectField(consumer, "mInputSurface");
            if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) {
                consumers.remove(consumer); return;
            }
            Point nativePosition = (Point) XposedHelpers.getObjectField(consumer, "mOldPosition");
            float scale = transform == null ? 1f : transform[0];
            float y = transform == null ? 0f : transform[1];
            float x = transform == null ? 0f : transform[2];
            // Append to the very transaction that publishes/shows this input surface.
            // SurfaceFlinger then publishes its inverse matrix to InputDispatcher.
            XposedHelpers.callMethod(transaction, "setMatrix", surface, scale, 0f, 0f, scale);
            XposedHelpers.callMethod(transaction, "setPosition", surface,
                    nativePosition.x * scale + x, nativePosition.y * scale + y);
            consumers.put(consumer, transform != null);
        }
    }

    /** Called under the WM lock, in the display's own magnification transaction. */
    static void geometryChanged(Object transaction, float scale, float y, float x) {
        Object[] current;
        synchronized (consumers) { current = consumers.keySet().toArray(); }
        float[] transform = scale == 1f && y == 0f && x == 0f ? null : new float[]{scale, y, x};
        for (Object consumer : current) {
            try { if (exact(consumer)) apply(consumer, transaction, transform); }
            catch (Throwable unavailable) {
                XposedBridge.log("MiStep: recents input refresh skipped: " + unavailable);
            }
        }
    }

    private MiStepRecentsInput() {}
}
