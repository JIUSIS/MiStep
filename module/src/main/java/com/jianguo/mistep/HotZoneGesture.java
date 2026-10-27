package com.jianguo.mistep;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XposedHelpers;

/** Spy channel: taps retain their original target. Only an accepted swipe pilfers. */
public final class HotZoneGesture {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static HotZoneGesture instance;
    private final Context context;
    private final Runnable trigger;
    private final CornerSwipe swipe = new CornerSwipe();
    private Object monitor, receiver;
    private boolean enabled;
    private long generation;

    private HotZoneGesture(Context context, Runnable trigger) {
        this.context = context;
        this.trigger = trigger;
    }
    public static void install(Context context, Runnable trigger) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> install(context, trigger));
            return;
        }
        if (instance != null) return;
        HotZoneGesture g = new HotZoneGesture(context, trigger);
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> compat = XposedHelpers.findClass(
                    "com.android.systemui.shared.system.InputMonitorCompat", cl);
            Class<?> listener = XposedHelpers.findClass(
                    "com.android.systemui.shared.system.InputChannelCompat$InputEventListener", cl);
            g.monitor = XposedHelpers.newInstance(compat, "MiStepGesture", 0);
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                    (self, method, args) -> {
                        // Target receiver ACKs after returning. Never throw out of this callback.
                        try {
                            if ("onInputEvent".equals(method.getName()) && args != null
                                    && args.length == 1 && args[0] instanceof MotionEvent) {
                                g.onMotion((MotionEvent) args[0]);
                            } else if ("toString".equals(method.getName())) return "MiStepGestureListener";
                            else if ("hashCode".equals(method.getName())) return System.identityHashCode(self);
                            else if ("equals".equals(method.getName())) return self == args[0];
                        } catch (Throwable error) {
                            g.reset();
                            log("gesture callback stopped: " + error);
                        }
                        return null;
                    });
            g.receiver = XposedHelpers.callMethod(g.monitor, "getInputReceiver",
                    Looper.getMainLooper(), Choreographer.getInstance(), proxy);
            if (g.receiver == null) throw new IllegalStateException("no receiver");
            g.enabled = true;
            instance = g;
            log("gesture spy installed; no touch overlay; tap pass-through enabled");
        } catch (Throwable error) {
            g.dispose();
            log("gesture spy unavailable: " + error);
        }
    }
    private void onMotion(MotionEvent e) {
        if (!enabled || !Integer.valueOf(0).equals(XposedHelpers.callMethod(e, "getDisplayId"))
                || !e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        int rotation = wm.getDefaultDisplay().getRotation();
        int action = e.getActionMasked();
        if (MiStepHost.hostSceneActive()) {
            swipe.reset();
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            if (!MiStepEntryGuard.canOpen(context)) return;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            float density = context.getResources().getDisplayMetrics().density;
            swipe.down(e.getPointerId(0), e.getPointerCount(), e.getRawX(), e.getRawY(),
                    e.getEventTime(), size.x, size.y, rotation,
                    Math.round(72f * density), Math.round(58f * density),
                    Math.round(24f * density));
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (swipe.move(e.getPointerId(0), e.getPointerCount(), e.getRawX(), e.getRawY(),
                    e.getEventTime(), size.x, size.y, rotation)) {
                if (!MiStepEntryGuard.canOpen(context)) { reset(); return; }
                try {
                    Object platform = XposedHelpers.getObjectField(monitor, "mInputMonitor");
                    XposedHelpers.callMethod(platform, "pilferPointers");
                } catch (Throwable error) {
                    reset();
                    log("pilfer failed; no trigger: " + error);
                    return;
                }
                long accepted = generation;
                MAIN.postDelayed(() -> {
                    if (enabled && generation == accepted) {
                        if (!MiStepEntryGuard.canOpen(context)) { reset(); return; }
                        Point current = new Point();
                        wm.getDefaultDisplay().getRealSize(current);
                        if (current.x != size.x || current.y != size.y
                                || wm.getDefaultDisplay().getRotation() != rotation) { reset(); return; }
                        generation++;
                        // 主区在滑动的相反一侧：左角滑出左侧栏 → 主区在右；右角滑出右侧栏 → 主区在左。
                        boolean mainRight = swipe.sign() > 0;
                        MiStepHost.setPendingMainSide(mainRight);
                        log("gesture edge=" + (mainRight ? "right" : "left") + " -> open");
                        trigger.run();
                    }
                }, 65L);
                log("corner swipe accepted; pilfered once");
            }
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            reset();
        } else if (action == MotionEvent.ACTION_UP) {
            // A tap never schedules a trigger. An accepted swipe may finish within 65ms.
            swipe.reset();
        }
    }
    private boolean pilferPointers() {
        try {
            Object platform = XposedHelpers.getObjectField(monitor, "mInputMonitor");
            XposedHelpers.callMethod(platform, "pilferPointers");
            return true;
        } catch (Throwable error) {
            log("pilfer failed; no trigger: " + error);
            return false;
        }
    }

    private void reset() {
        generation++;
        swipe.reset();
    }
    public static boolean isAttached() {
        return instance != null && instance.enabled && instance.receiver != null;
    }
    public static void raiseAll(Context context) {
        if (instance != null) { instance.reset(); instance.enabled = true; }
    }
    public static void suspend() {
        if (instance != null) { instance.enabled = false; instance.reset(); }
    }
    private void dispose() {
        enabled = false;
        reset();
        if (receiver != null) {
            try { XposedHelpers.callMethod(receiver, "dispose"); }
            catch (Throwable e) { log("receiver dispose: " + e); }
            finally { receiver = null; }
        }
        if (monitor != null) {
            try { XposedHelpers.callMethod(monitor, "dispose"); }
            catch (Throwable e) { log("monitor dispose: " + e); }
            finally { monitor = null; }
        }
    }
    private static void log(String message) { android.util.Log.i("MiStepProbe", message); }
}
