package com.jiusis.mistep;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XposedHelpers;

/** Spy channel: taps pass through; bottom horizontal intent retains the stream until acceptance. */
public final class HotZoneGesture {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static HotZoneGesture instance;
    private static int selectedEntryGesture = WorkspaceOptions.STATUS_BAR_INWARD;
    private final Context context;
    private final Runnable trigger;
    private final CornerSwipe swipe = new CornerSwipe();
    private final BottomCenterSwipe bottomSwipe = new BottomCenterSwipe();
    private final TopTaskSwipe topSwipe = new TopTaskSwipe();
    private final TopEdgeBackSwipe topBack = new TopEdgeBackSwipe();
    private boolean topHorizontal;
    private Object topTaskAtDown;
    private Object monitor, receiver;
    private WorkspaceSettingsClient entrySettings;
    private boolean enabled;
    private long generation;
    private long entryContext;
    private int admittedEntryGesture;
    private boolean bottomPilfered;
    private boolean bottomUnbatched;
    private Object batchingReceiver;
    private int bottomIntentPx, bottomVerticalPx;
    private long bottomDownTime, lastBottomOpened = -1;

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
            g.entrySettings = new WorkspaceSettingsClient(context, MAIN,
                    options -> setEntryGesture(options.entryGesture));
            log("gesture spy installed; no touch overlay; tap pass-through enabled");
        } catch (Throwable error) {
            g.dispose();
            log("gesture spy unavailable: " + error);
        }
    }
    private void onMotion(MotionEvent e) {
        if (!enabled || !Integer.valueOf(0).equals(XposedHelpers.callMethod(e, "getDisplayId"))
                || !e.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return;
        // DOWN already admitted this stream. Retain horizontal intent before binder-backed
        // geometry/settings/unlock queries let the native navigation monitor win the race.
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !bottomPilfered
                && admittedEntryGesture == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                && selectedEntryGesture == admittedEntryGesture && !MiStepHost.hostSceneActive()
                && bottomSwipe.canRetain(e.getPointerId(0), e.getPointerCount(), e.getRawX(), e.getRawY(),
                        e.getEventTime(), bottomIntentPx, bottomVerticalPx)) {
            if (!pilferPointers()) { reset(); return; }
            bottomPilfered = true;
            log("bottom entry retained before state queries; " + bottomSwipe.progress(e.getRawX(), e.getRawY(), e.getEventTime()));
        }
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        int rotation = wm.getDefaultDisplay().getRotation();
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) bottomDownTime = e.getEventTime();
        if (MiStepHost.hostSceneActive()) {
            generation++;
            swipe.reset();
            bottomSwipe.reset();
            bottomPilfered = false;
            restoreBatching();
            onTopMotion(e,size);
            return;
        }
        if(topSwipe.accepted())endTopMotion(true);
        topSwipe.reset();
        topBack.reset();
        if (action == MotionEvent.ACTION_DOWN) {
            reset();
            if (!MiStepEntryGuard.canOpen(context)) return;
            admittedEntryGesture = effectiveEntryGesture();
            if (!allowsEntry(size, admittedEntryGesture)) {
                log("corner gesture ignored in landscape or native fullscreen");
                return;
            }
        }
        if (action == MotionEvent.ACTION_DOWN) {
            entryContext = MiStepStatusBar.cornerGestureContext();
            float density = context.getResources().getDisplayMetrics().density;
            if (admittedEntryGesture == WorkspaceOptions.BOTTOM_CENTER_OUTWARD) {
                bottomIntentPx = Math.round(2f * density);
                bottomVerticalPx = Math.round(24f * density);
                bottomSwipe.down(e.getPointerId(0), e.getPointerCount(), e.getRawX(), e.getRawY(),
                        e.getEventTime(), size.x, size.y, rotation,
                        Math.round(8f * density));
                if (bottomSwipe.active()) {
                    try {
                        setInputBatching(false);
                        bottomUnbatched = true;
                    } catch (Throwable error) {
                        log("bottom immediate input unavailable: " + error);
                        reset(); return;
                    }
                    log("bottom entry candidate; immediate input; edge=" + Math.round(8f * density));
                }
            } else {
                swipe.down(e.getPointerId(0), e.getPointerCount(), e.getRawX(), e.getRawY(),
                        e.getEventTime(), size.x, size.y, rotation,
                        Math.round(72f * density), Math.round(58f * density),
                        Math.round(24f * density));
            }
        } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            if (!keepsEntry(size)) { reset(); return; }
            float density = context.getResources().getDisplayMetrics().density;
            boolean bottomCandidate = bottomSwipe.active();
            boolean acceptedSwipe = admittedEntryGesture == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                    ? bottomSwipe.move(e.getPointerId(0), e.getPointerCount(), e.getRawX(), e.getRawY(),
                            e.getEventTime(), size.x, size.y, rotation,
                            Math.round(58f * density), Math.round(24f * density))
                    : swipe.move(e.getPointerId(0), e.getPointerCount(), e.getRawX(), e.getRawY(),
                            e.getEventTime(), size.x, size.y, rotation);
            // The native bottom monitor can cancel us after about 5dp. Claim clear
            // horizontal intent first; retain the full 58dp threshold for opening.
            if (admittedEntryGesture == WorkspaceOptions.BOTTOM_CENTER_OUTWARD && !bottomPilfered
                    && (acceptedSwipe || (action == MotionEvent.ACTION_MOVE
                    && bottomSwipe.horizontalIntent(e.getRawX(), e.getRawY(), Math.round(2f * density))))) {
                if (!MiStepEntryGuard.canOpen(context) || !pilferPointers()) { reset(); return; }
                bottomPilfered = true;
                log("bottom entry retained; " + bottomSwipe.progress(e.getRawX(), e.getRawY(), e.getEventTime()));
            }
            if (bottomCandidate && !acceptedSwipe && !bottomSwipe.active()) {
                log("bottom entry rejected (pointer/geometry/time/vertical); claimed=" + bottomPilfered
                        + " " + bottomSwipe.progress(e.getRawX(), e.getRawY(), e.getEventTime()));
            }
            if (acceptedSwipe) {
                if (!MiStepEntryGuard.canOpen(context)) { reset(); return; }
                if (!bottomPilfered && !pilferPointers()) { reset(); return; }
                long accepted = generation;
                long acceptedDown = bottomDownTime;
                MAIN.postDelayed(() -> {
                    if (enabled && generation == accepted) {
                        if (admittedEntryGesture == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                                && lastBottomOpened == acceptedDown) return;
                        if (MiStepHost.hostSceneActive() || !MiStepEntryGuard.canOpen(context)) { reset(); return; }
                        Point current = new Point();
                        wm.getDefaultDisplay().getRealSize(current);
                        if (current.x != size.x || current.y != size.y
                                || wm.getDefaultDisplay().getRotation() != rotation
                                || !keepsEntry(current)) { reset(); return; }
                        generation++;
                        // 主区域跟随水平滑动方向：向右在右，向左在左。
                        boolean mainRight = admittedEntryGesture == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                                ? bottomSwipe.sign() > 0 : swipe.sign() > 0;
                        MiStepHost.setPendingMainSide(mainRight);
                        if (admittedEntryGesture == WorkspaceOptions.BOTTOM_CENTER_OUTWARD)
                            lastBottomOpened = acceptedDown;
                        log("gesture mode=" + admittedEntryGesture
                                + " -> main=" + (mainRight ? "right" : "left") + " -> open");
                        trigger.run();
                    }
                }, 65L);
                log("entry swipe mode=" + admittedEntryGesture + " accepted; pilfered once");
            }
            // A batched stream may deliver its final threshold-crossing position only on UP.
            // Feed that endpoint through the same recognizer; a tap still cannot schedule entry.
            if (action == MotionEvent.ACTION_UP) {
                if (bottomSwipe.active()) log("bottom entry released below threshold; claimed=" + bottomPilfered
                        + " " + bottomSwipe.progress(e.getRawX(), e.getRawY(), e.getEventTime()));
                swipe.reset();
                bottomSwipe.reset();
                bottomPilfered = false;
                restoreBatching();
            }
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            if (bottomSwipe.active()) log("bottom entry cancelled; action=" + action + " claimed=" + bottomPilfered
                    + " " + bottomSwipe.progress(e.getRawX(), e.getRawY(), e.getEventTime()));
            reset();
        }
    }

    public static void setEntryGesture(int mode) {
        int valid = mode == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                ? mode : WorkspaceOptions.STATUS_BAR_INWARD;
        if (instance != null) instance.context.sendBroadcast(
                new android.content.Intent("com.jiusis.mistep.BOTTOM_INPUT_OPTIONS")
                        .setPackage("android").putExtra("mode", valid));
        if (selectedEntryGesture == valid) return;
        selectedEntryGesture = valid;
        if (instance != null) instance.reset();
    }

    static void completeBottomReplay(int sign, long downTime, long eventTime) {
        HotZoneGesture g = instance;
        if (g == null || (sign != -1 && sign != 1) || downTime < 0 || eventTime < downTime
                || eventTime - downTime > 600) return;
        MAIN.post(() -> {
            long now = SystemClock.uptimeMillis();
            if (!g.enabled || now < eventTime || now - eventTime > 600
                    || g.lastBottomOpened == downTime || MiStepHost.hostSceneActive()
                    || g.effectiveEntryGesture() != WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                    || !MiStepEntryGuard.canOpen(g.context)) return;
            WindowManager wm = (WindowManager) g.context.getSystemService(Context.WINDOW_SERVICE);
            Point size = new Point();
            wm.getDefaultDisplay().getRealSize(size);
            if (!g.allowsEntry(size, WorkspaceOptions.BOTTOM_CENTER_OUTWARD)) return;
            g.reset();
            g.lastBottomOpened = downTime;
            MiStepHost.setPendingMainSide(sign > 0);
            log("OS3 bottom replay -> main=" + (sign > 0 ? "right" : "left") + " -> open");
            g.trigger.run();
        });
    }

    private int effectiveEntryGesture() {
        return EntryGesturePolicy.effectiveMode(selectedEntryGesture,
                EntryGestureSettings.lineHidden(context));
    }

    private boolean allowsEntry(Point size, int mode) {
        return mode == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                ? MiStepStatusBar.allowsBottomGesture(size.x, size.y, DeviceProfile.isTablet(context))
                : MiStepStatusBar.allowsCornerGesture(size.x, size.y, DeviceProfile.isTablet(context));
    }

    private boolean keepsEntry(Point size) {
        if (effectiveEntryGesture() != admittedEntryGesture) return false;
        return admittedEntryGesture == WorkspaceOptions.BOTTOM_CENTER_OUTWARD
                ? allowsEntry(size, admittedEntryGesture)
                : MiStepStatusBar.keepsCornerGesture(entryContext);
    }
    private void onTopMotion(MotionEvent e,Point size) {
        int[] bounds=MiStepHost.topGestureBounds();
        if(bounds==null) {
            if(topSwipe.accepted())endTopMotion(true);
            topSwipe.reset();topBack.reset();return;
        }
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN)topTaskAtDown=MiStepHost.topDismissToken();
        int count=e.getPointerCount();
        Object owner = MiStepHost.topDismissToken();
        int back = topBack.event(e.getActionMasked(), count, e.getPointerId(0),
                e.getRawX()-bounds[0], e.getRawY()-bounds[1], e.getEventTime(),
                bounds[2]-bounds[0], bounds[3]-bounds[1],
                context.getResources().getDisplayMetrics().density,
                MiStepHost.topGestureDirection(), owner);
        if (back == TopEdgeBackSwipe.CLAIM && !pilferPointers()) topBack.reset();
        if (back == TopEdgeBackSwipe.BACK && topTaskAtDown!=null
                && java.util.Objects.equals(topTaskAtDown,owner)) MiStepHost.requestTopBack(topTaskAtDown);
        if (topBack.claimed() || back == TopEdgeBackSwipe.BACK) { topSwipe.reset(); return; }
        int result=topSwipe.event(e.getActionMasked(),count,e.getPointerId(0),count>1?e.getPointerId(1):-1,
                e.getRawX()-bounds[0],e.getRawY()-bounds[1],
                count>1?e.getRawX(1)-bounds[0]:0,count>1?e.getRawY(1)-bounds[1]:0,
                e.getEventTime(),bounds[2]-bounds[0],bounds[3]-bounds[1],
                context.getResources().getDisplayMetrics().density,topTaskAtDown!=null,
                MiStepHost.topGestureDirection());
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
            log(topHorizontal?"upper two-finger horizontal handoff accepted":"upper two-finger drag accepted");
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
        bottomSwipe.reset();
        bottomPilfered = false;
        restoreBatching();
        if(topSwipe.accepted())endTopMotion(true);
        topSwipe.reset();
        topBack.reset();
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
    private void restoreBatching() {
        if (!bottomUnbatched) return;
        bottomUnbatched = false;
        try { setInputBatching(true); }
        catch (Throwable error) { log("restore input batching unavailable: " + error); }
    }
    private void setInputBatching(boolean enabled) {
        if (batchingReceiver == null) {
            try {
                receiver.getClass().getMethod("setBatchingEnabled", boolean.class);
                batchingReceiver = receiver;
            } catch (NoSuchMethodException oldWrapper) {
                batchingReceiver = XposedHelpers.getObjectField(receiver, "mReceiver");
            }
        }
        XposedHelpers.callMethod(batchingReceiver, "setBatchingEnabled", enabled);
    }
    private static void log(String message) {
        android.util.Log.i("MiStepProbe", message);
        try { de.robv.android.xposed.XposedBridge.log("MiStepProbe: " + message); }
        catch (Throwable ignored) {}
    }
}
