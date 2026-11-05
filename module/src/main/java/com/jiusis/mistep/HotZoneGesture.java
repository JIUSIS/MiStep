package com.jiusis.mistep;

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
    private final TopTaskSwipe topSwipe = new TopTaskSwipe();
    private boolean topHorizontal;
    private Object topTaskAtDown;
    private Object monitor, receiver;
    private boolean enabled;
    private long generation;
    private long entryContext;

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
            generation++;
            swipe.reset();
            onTopMotion(e,size);
            return;
        }
        if(topSwipe.accepted())endTopMotion(true);
        topSwipe.reset();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            if (!MiStepEntryGuard.canOpen(context)) return;
            if (!MiStepStatusBar.allowsCornerGesture(size.x, size.y)) {
                log("corner gesture ignored in landscape or native fullscreen");
                return;
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            entryContext = MiStepStatusBar.cornerGestureContext();
            float density = context.getResources().getDisplayMetrics().density;
            swipe.down(e.getPointerId(0), e.getPointerCount(), e.getRawX(), e.getRawY(),
                    e.getEventTime(), size.x, size.y, rotation,
                    Math.round(72f * density), Math.round(58f * density),
                    Math.round(24f * density));
        } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            if (!MiStepStatusBar.keepsCornerGesture(entryContext)) { reset(); return; }
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
                        if (MiStepHost.hostSceneActive() || !MiStepEntryGuard.canOpen(context)) { reset(); return; }
                        Point current = new Point();
                        wm.getDefaultDisplay().getRealSize(current);
                        if (current.x != size.x || current.y != size.y
                                || wm.getDefaultDisplay().getRotation() != rotation
                                || !MiStepStatusBar.keepsCornerGesture(entryContext)) { reset(); return; }
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
            // A batched stream may deliver its final threshold-crossing position only on UP.
            // Feed that endpoint through the same recognizer; a tap still cannot schedule entry.
            if (action == MotionEvent.ACTION_UP) swipe.reset();
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            reset();
        }
    }
    private void onTopMotion(MotionEvent e,Point size) {
        int height=MiStepHost.topGestureHeight();
        if(height<=0) {
            if(topSwipe.accepted())endTopMotion(true);
            topSwipe.reset();return;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN)topTaskAtDown=MiStepHost.topDismissToken();
        int count=e.getPointerCount();
        int result=topSwipe.event(e.getActionMasked(),count,e.getPointerId(0),count>1?e.getPointerId(1):-1,
                e.getRawX(),e.getRawY(),count>1?e.getRawX(1):0,count>1?e.getRawY(1):0,
                e.getEventTime(),size.x,height,context.getResources().getDisplayMetrics().density,topTaskAtDown!=null);
        if(result==TopTaskSwipe.ACCEPT) {
            topHorizontal=topSwipe.horizontal();
            if(topHorizontal && !java.util.Objects.equals(topTaskAtDown,MiStepHost.topDismissToken())) {
                topSwipe.reset();return;
            }
            if(!pilferPointers() || !(topHorizontal
                    ?MiStepHost.beginTopDismiss(topSwipe.displacement,topTaskAtDown):MiStepHost.beginTopGesture())) {
                topSwipe.reset();return;
            }
            moveTopMotion(topSwipe.displacement,e.getEventTime());
            log(topHorizontal?"upper two-finger horizontal dismiss accepted":"upper two-finger drag accepted");
        } else if(result==TopTaskSwipe.DRAG) {
            moveTopMotion(topSwipe.displacement,e.getEventTime());
        } else if(result==TopTaskSwipe.FINISH || result==TopTaskSwipe.CANCEL) {
            endTopMotion(result==TopTaskSwipe.CANCEL);
        }
    }

    private void moveTopMotion(float displacement,long time) {
        if(topHorizontal)MiStepHost.moveTopDismiss(displacement);
        else MiStepHost.moveTopGesture(displacement,time);
    }

    private void endTopMotion(boolean cancelled) {
        if(topHorizontal)MiStepHost.endTopDismiss(cancelled);
        else MiStepHost.endTopGesture(cancelled);
        topHorizontal=false;topTaskAtDown=null;
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
        if(topSwipe.accepted())endTopMotion(true);
        topSwipe.reset();
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
