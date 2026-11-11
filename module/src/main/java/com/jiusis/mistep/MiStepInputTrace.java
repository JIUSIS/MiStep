package com.jiusis.mistep;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.KeyguardManager;
import android.os.PowerManager;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Explicit six-second numeric trace. It neither changes matrices nor dispatches an event. */
final class MiStepInputTrace {
    static final String ACTION = "com.jiusis.mistep.TRACE_INPUT";
    private static final InputTraceGate GATE = new InputTraceGate();
    private static final StringBuilder LINES = new StringBuilder();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "mistep-input-trace-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private static File output;
    private static Context context;
    private static KeyguardManager keyguard;
    private static PowerManager power;
    private static boolean screenReceiverInstalled;
    private static boolean windowsRecorded;
    private static long lastFrame;
    private static long lastTopFrame;
    private static boolean unavailableRecorded;
    private static final List<XC_MethodHook.Unhook> HOOKS = new ArrayList<>();
    private static Object globalReceiver;
    private static MiStepPassiveInputChannel globalChannel;
    private static final ThreadLocal<Boolean> readingFocus = new ThreadLocal<>();
    private static int focusSamples;
    private static int moveSamples, nativeFrames, topFrames;

    private MiStepInputTrace() {}

    static synchronized void arm(Context ctx, boolean inServer, ClassLoader loader) {
        if (!MiStepEntryGuard.canOpen(ctx)) return;
        finish(GATE.generation());
        long now = SystemClock.uptimeMillis();
        long ticket = GATE.arm(now, true);
        context = ctx;
        keyguard = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        power = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (!screenReceiverInstalled) {
            try {
                ctx.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent intent) { stop(); }
                }, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
                screenReceiverInstalled = true;
            } catch (Throwable error) {
                finish(ticket);
                return;
            }
        }
        windowsRecorded = false;
        lastFrame = 0;
        lastTopFrame = 0;
        unavailableRecorded = false;
        focusSamples = 0;
        moveSamples = nativeFrames = topFrames = 0;
        File dir = inServer ? new File("/data/system/mistep-input-traces")
                : new File(ctx.getFilesDir(), "mistep-input-traces");
        output = new File(dir, (inServer ? "wm-" : "ui-") + now + ".txt");
        line("ARM uptime=" + now + " bootId=" + bootId() + " pid=" + android.os.Process.myPid()
                + " epochMillis=" + System.currentTimeMillis() + " elapsedRealtime=" + SystemClock.elapsedRealtime()
                + " generation=" + ticket + " duration=6000 maxLines=128 numeric-only=true");
        if (loader != null) {
            if (inServer) hookSystemServer(loader);
            else hookSystemUi(loader);
        }
        TIMER.schedule(() -> finish(ticket), InputTraceGate.DURATION_MS, TimeUnit.MILLISECONDS);
    }

    private static synchronized boolean active() {
        if (!GATE.active(SystemClock.uptimeMillis())) return false;
        // The full user/device-unlock check ran before arming, outside the WMS lock.
        // Do not query TrustManager repeatedly from a transition's locked traversal.
        boolean allowed = false;
        try { allowed = keyguard != null && power != null
                && !keyguard.isKeyguardLocked() && power.isInteractive(); }
        catch (Throwable ignored) { }
        if (!allowed) {
            finish(GATE.generation());
            return false;
        }
        return true;
    }

    static synchronized void stop() { finish(GATE.generation()); }

    private static synchronized void line(String text) {
        long now = SystemClock.uptimeMillis();
        if (!GATE.take(now)) return;
        // Numeric/geometry breadcrumbs are bounded even if an OEM toString grows.
        String prefix = now + " ";
        String suffix = GATE.active(now) ? "" : " TRACE_BUDGET_REACHED";
        int limit = 1024 - prefix.length() - suffix.length();
        String bounded = text.substring(0, Math.min(text.length(), limit)).replace('\n', ' ').replace('\r', ' ');
        LINES.append(prefix).append(bounded).append(suffix).append('\n');
        if (!GATE.active(SystemClock.uptimeMillis())) finish(GATE.generation());
    }

    private static String bootId() {
        try (java.io.FileInputStream stream = new java.io.FileInputStream("/proc/sys/kernel/random/boot_id")) {
            String id = new String(stream.readNBytes(64), StandardCharsets.US_ASCII).trim();
            if (id.matches("[a-fA-F0-9-]{36}")) return id;
        } catch (Throwable ignored) { }
        return "unavailable";
    }

    private static synchronized void finish(long ticket) {
        if (!GATE.stop(ticket)) return;
        File destination = output;
        output = null;
        context = null;
        keyguard = null;
        power = null;
        windowsRecorded = false;
        lastFrame = 0;
        for (XC_MethodHook.Unhook hook : HOOKS) {
            try { hook.unhook(); } catch (Throwable ignored) { }
        }
        HOOKS.clear();
        if (globalReceiver != null) {
            try { XposedHelpers.callMethod(globalReceiver, "dispose"); } catch (Throwable ignored) { }
            globalReceiver = null;
        }
        if (globalChannel != null) {
            globalChannel.close();
            globalChannel = null;
        }
        if (destination != null && LINES.length() > 0) {
            try {
                File dir = destination.getParentFile();
                if (dir.isDirectory() || dir.mkdirs()) {
                    try (FileOutputStream stream = new FileOutputStream(destination)) {
                        stream.write(LINES.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    XposedBridge.log("MiStepInputTrace saved " + destination);
                }
            } catch (Throwable error) {
                XposedBridge.log("MiStepInputTrace save failed: " + error.getClass().getSimpleName());
            }
        }
        LINES.setLength(0);
    }

    private static void retain(Set<XC_MethodHook.Unhook> hooks) { HOOKS.addAll(hooks); }

    private abstract static class TraceHook extends XC_MethodHook {
        @Override protected final void beforeHookedMethod(MethodHookParam p) {
            try { before(p); } catch (Throwable error) { unavailable(error); }
        }
        @Override protected final void afterHookedMethod(MethodHookParam p) {
            try { after(p); } catch (Throwable error) { unavailable(error); }
        }
        protected void before(MethodHookParam p) throws Throwable { }
        protected void after(MethodHookParam p) throws Throwable { }
    }

    private static synchronized void unavailable(Throwable error) {
        if (unavailableRecorded || !GATE.active(SystemClock.uptimeMillis())) return;
        unavailableRecorded = true;
        line("unavailable=" + error.getClass().getSimpleName());
    }

    private static void hookSystemUi(ClassLoader cl) {
        hookTopPhases();
        hook(cl, "com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl$inputEventReceiver$1",
                "onInputEvent", "native-display-back-receiver");
        hook(cl, "com.android.systemui.navigationbar.gestural.BackPanelController",
                "onMotionEvent", "native-back-panel");
        hook(cl, "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
                "onMotionEvent", "native-edge-back-motion");
        hook(cl, "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
                "handleMotionEvent", "native-edge-back-handle");
        try {
            Class<?> util = XposedHelpers.findClass("com.android.wm.shell.shared.TransitionUtil", cl);
            retain(XposedBridge.hookAllMethods(util, "newTarget", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    int offset = MiStepRecentsWallpaper.argumentOffset(p.args);
                    if (!active() || p.hasThrowable() || offset < 0) return;
                    Object info = p.args[2 + offset];
                    line("nativeTarget args=" + p.args.length + " type="
                            + XposedHelpers.callMethod(info, "getType") + " roots="
                            + XposedHelpers.callMethod(info, "getRootCount")
                            + " main=" + (MiStepHost.activeMainTransform() != null));
                    int count = 0;
                    for (Object change : (List<?>) XposedHelpers.callMethod(info, "getChanges")) {
                        if (++count > 6) break;
                        Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                        line("nativeChange mode=" + XposedHelpers.callMethod(change, "getMode")
                                + " flags=" + XposedHelpers.callMethod(change, "getFlags")
                                + " parent=" + (XposedHelpers.callMethod(change, "getParent") != null)
                                + " task=" + (task == null ? -1 : XposedHelpers.getIntField(task, "taskId"))
                                + " activity=" + (task == null ? -1 : XposedHelpers.callMethod(task, "getActivityType"))
                                + " windowing=" + (task == null ? -1 : XposedHelpers.callMethod(task, "getWindowingMode"))
                                + " taskParent=" + (task != null && Boolean.TRUE.equals(XposedHelpers.callMethod(task, "hasParentTask")))
                                + " rotation=" + XposedHelpers.callMethod(change, "getStartRotation")
                                + "," + XposedHelpers.callMethod(change, "getEndRotation")
                                + " bounds=" + XposedHelpers.callMethod(change, "getStartAbsBounds"));
                    }
                }
            }));
        } catch (Throwable error) { unavailable(error); }
        retain(XposedBridge.hookAllMethods(HotZoneGesture.class, "onMotion", new TraceHook() {
            @Override protected void before(MethodHookParam p) {
                if (!active() || p.args.length != 1 || !(p.args[0] instanceof MotionEvent e)) return;
                event("mistep-corner-spy", e);
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
                    line("corner-admission enabled="+XposedHelpers.getBooleanField(p.thisObject,"enabled")
                            +" "+MiStepStatusBar.cornerStateForTrace());
            }
        }));
        hook(cl, "com.android.systemui.statusbar.notification.island.DynamicIslandGestureMonitor$listener$1",
                "onPointerEvent", "island-spy");
        hook(cl, "com.miui.systemui.notification.island.DynamicIslandTouchHandlerImpl",
                "onIntercept", "island-external-intercept");
        hook(cl, "com.miui.systemui.notification.island.DynamicIslandTouchHandlerImpl",
                "onTouch", "island-external-touch");
        try {
            Class<?> receiver = XposedHelpers.findClass("android.view.ViewRootImpl$WindowInputEventReceiver", cl);
            retain(XposedBridge.hookAllMethods(receiver, "onInputEvent", new TraceHook() {
                @Override protected void before(MethodHookParam p) {
                    if (!active() || p.args.length != 1 || !(p.args[0] instanceof MotionEvent)) return;
                    try {
                        Object root = XposedHelpers.getObjectField(p.thisObject, "this$0");
                        View view = (View) XposedHelpers.callMethod(root, "getView");
                        if (view == null) return;
                        WindowManager.LayoutParams lp = (WindowManager.LayoutParams)
                                XposedHelpers.getObjectField(root, "mWindowAttributes");
                        if (lp.type != 2000 && lp.type != 2040
                                && !(lp.type == 2009 && "DynamicIslandWindow".contentEquals(lp.getTitle()))) return;
                        event("window-type=" + lp.type, (MotionEvent) p.args[0]);
                        if (((MotionEvent) p.args[0]).getActionMasked() == MotionEvent.ACTION_DOWN) {
                            int[] location = new int[2];
                            view.getLocationOnScreen(location);
                            Rect visible = new Rect();
                            view.getGlobalVisibleRect(visible);
                            line("viewRoot type=" + lp.type + " frameworkLocation=" + location[0] + "," + location[1]
                                    + " visibleLogical=" + visible + " size=" + view.getWidth() + "," + view.getHeight());
                        }
                    } catch (Throwable error) { unavailable(error); }
                }
            }));
        } catch (Throwable ignored) { }
        try {
            Class<?> container = XposedHelpers.findClass(
                    "com.android.wm.shell.common.transition.animation.MiuiAnimationContainer", cl);
            retain(XposedBridge.hookAllMethods(container, "getMatrix", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active()) return;
                    long now = SystemClock.uptimeMillis();
                    synchronized (MiStepInputTrace.class) {
                        if (now - lastFrame < 32 || nativeFrames >= 12) return;
                        lastFrame = now;
                        nativeFrames++;
                    }
                    try {
                        Object leash = XposedHelpers.callMethod(p.thisObject, "getLeash");
                        line("nativeAnimation layer=" + layer(leash) + " matrix=" + p.getResult());
                    } catch (Throwable error) { unavailable(error); }
                }
            }));
        } catch (Throwable ignored) { }
    }

    private static void hook(ClassLoader cl, String cls, String method, String label) {
        try {
            Set<XC_MethodHook.Unhook> installed = XposedBridge.hookAllMethods(XposedHelpers.findClass(cls, cl), method, new TraceHook() {
                @Override protected void before(MethodHookParam p) {
                    if (!active() || p.args.length == 0 || !(p.args[0] instanceof MotionEvent)) return;
                    String source = p.args.length > 1 && p.args[1] instanceof String
                            ? (String) p.args[1] : "monitor";
                    // Sources are labels from the ROM, never arbitrary contents.
                    if (source.length() > 48 || !source.matches("[A-Za-z0-9_ -]+")) source = "other";
                    event(label + " source=" + source, (MotionEvent) p.args[0]);
                }
            });
            retain(installed);
            line("observer=" + label + " installed=" + !installed.isEmpty());
        } catch (Throwable ignored) { line("observer=" + label + " installed=false"); }
    }

    /** Observe existing decisions and live-Surface phases; never consumes or injects input. */
    private static void hookTopPhases() {
        retain(XposedBridge.hookAllMethods(TopTaskSwipe.class, "event", new TraceHook() {
            @Override protected void after(MethodHookParam p) {
                if (!active() || p.hasThrowable() || p.args.length != 14) return;
                int action = ((Number)p.args[0]).intValue();
                int decision = ((Number)p.getResult()).intValue();
                if (action == MotionEvent.ACTION_MOVE && decision != TopTaskSwipe.ACCEPT
                        && decision != TopTaskSwipe.CANCEL) return;
                line("top-two-finger action=" + action + " count=" + p.args[1] + " decision=" + decision
                        + " displacement=" + XposedHelpers.getFloatField(p.thisObject, "displacement")
                        + " horizontal=" + p.args[12] + " outward=" + p.args[13]);
            }
        }));
        retain(XposedBridge.hookAllMethods(TopEdgeBackSwipe.class, "event", new TraceHook() {
            @Override protected void before(MethodHookParam p) {
                if (!active() || p.args.length != 11) return;
                int action = ((Number)p.args[0]).intValue();
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP
                        && action != MotionEvent.ACTION_CANCEL) return;
                Object owner = XposedHelpers.getObjectField(p.thisObject, "token");
                line("top-edge-before action=" + action + " eventTime=" + p.args[5]
                        + " bounds=" + p.args[6] + "," + p.args[7] + " density=" + p.args[8]
                        + " claimed=" + XposedHelpers.getBooleanField(p.thisObject, "claimed")
                        + " currentOwner=" + (owner != null && owner.equals(p.args[10])));
            }
            @Override protected void after(MethodHookParam p) {
                if (!active() || p.hasThrowable() || p.args.length != 11) return;
                int action = ((Number)p.args[0]).intValue();
                int decision = ((Number)p.getResult()).intValue();
                if (action == MotionEvent.ACTION_MOVE && decision == TopEdgeBackSwipe.NONE) return;
                line("top-edge-back action=" + action + " count=" + p.args[1] + " decision=" + decision
                        + " local=" + p.args[3] + "," + p.args[4] + " outward=" + p.args[9]
                        + " owner=" + (p.args[10] != null));
            }
        }));
        retain(XposedBridge.hookAllMethods(MiStepTopBack.class, "request", new TraceHook() {
            @Override protected void before(MethodHookParam p) {
                if (active()) line("top-back-request owner=" + (p.args.length > 1 && p.args[1] != null));
            }
        }));
        for (String method : new String[]{"startTopTaskTransition", "revealTopTaskTransition",
                "cancelTopTaskTransition", "releaseTopTaskNow", "returnToSystem", "handoffPromotedTask",
                "checkPromotion", "finishPromotionFocus"}) {
            retain(XposedBridge.hookAllMethods(MiStepHost.class, method, new TraceHook() {
                @Override protected void before(MethodHookParam p) {
                    if (active()) hostPhase(p, method + ":enter");
                }
                @Override protected void after(MethodHookParam p) {
                    if (active()) hostPhase(p, method + (p.hasThrowable() ? ":throw" : ":exit"));
                }
            }));
        }
        for (String method : new String[]{"start", "collect", "collectionReady", "handoff", "reveal", "animate", "close",
                "applyLiveScale", "applyCollectionScale"}) {
            retain(XposedBridge.hookAllMethods(MiStepTopTaskTransition.class, method, new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active()) return;
                    if (method.startsWith("apply")) synchronized (MiStepInputTrace.class) {
                        long now = SystemClock.uptimeMillis();
                        if (topFrames >= 12 || now - lastTopFrame < 32) return;
                        topFrames++; lastTopFrame = now;
                    }
                    String detail = "";
                    if (method.equals("animate") && p.args.length >= 3)
                        detail = " from=" + p.args[0] + " to=" + p.args[1] + " duration=" + p.args[2];
                    else if (method.startsWith("apply") && p.args.length > 0) detail = " scale=" + p.args[0];
                    line("top-live-phase=" + method + " instance=" + System.identityHashCode(p.thisObject)
                            + " closed=" + XposedHelpers.getBooleanField(p.thisObject, "closed")
                            + " collapsed=" + XposedHelpers.getBooleanField(p.thisObject, "collapsed")
                            + " revealing=" + XposedHelpers.getBooleanField(p.thisObject, "revealing")
                            + " result=" + (p.getResult() instanceof Boolean ? p.getResult() : "void")
                            + " error=" + p.hasThrowable() + detail);
                }
            }));
        }
    }

    private static void hostPhase(XC_MethodHook.MethodHookParam p, String phase) {
        Object[] slots = (Object[])XposedHelpers.getObjectField(p.thisObject, "slots");
        int top = XposedHelpers.getStaticIntField(MiStepHost.class, "SLOT_TOP");
        Object slot = slots != null && top >= 0 && top < slots.length ? slots[top] : null;
        StringBuilder decisions = new StringBuilder();
        for (int i = 0; i < p.args.length && i < 6; i++)
            if (p.args[i] instanceof Boolean || p.args[i] instanceof Number)
                decisions.append(" arg").append(i).append('=').append(p.args[i]);
        line("top-host-phase=" + phase + " epoch=" + XposedHelpers.getLongField(p.thisObject, "sceneEpoch")
                + " scene=" + XposedHelpers.getIntField(p.thisObject, "scene")
                + " task=" + (slot == null ? -1 : XposedHelpers.getIntField(slot, "taskId"))
                + " transition=" + (XposedHelpers.getObjectField(p.thisObject, "topTransition") != null)
                + " result=" + (p.getResult() instanceof Boolean ? p.getResult() : "void")
                + " error=" + p.hasThrowable() + decisions);
    }

    static void panelInputGate(int type, int display, int rotation, boolean systemUiPackage,
            Throwable error) {
        if (!active()) return;
        line("panelInputGate type=" + type + " display=" + display + " windowRotation=" + rotation
                + " systemUiPackage=" + systemUiPackage
                + (error == null ? "" : " error=" + error.getClass().getSimpleName()));
    }

    private static void event(String label, MotionEvent event) {
        if (!active()) return;
        try {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) synchronized (MiStepInputTrace.class) {
            if (moveSamples++ >= 24) return;
        }
        line(label + " eventTime=" + event.getEventTime() + " action=" + event.getActionMasked()
                + " display=" + XposedHelpers.callMethod(event, "getDisplayId") + " local=" + event.getX() + "," + event.getY()
                + " raw=" + event.getRawX() + "," + event.getRawY() + " pointers=" + event.getPointerCount());
        float[] transform = MiStepHost.activeMainTransform();
        if (transform != null && event.getActionMasked() == MotionEvent.ACTION_DOWN)
            line("hostExpectedZoom=" + transform[0] + "," + transform[1] + "," + transform[2]);
        } catch (Throwable error) { unavailable(error); }
    }

    private static void hookSystemServer(ClassLoader cl) {
        retain(XposedBridge.hookAllMethods(MiStepTopBack.class, "prepare", new TraceHook() {
            @Override protected void after(MethodHookParam p) {
                if (active()) line("top-back-admission prepared=" + (p.getResult() != null)
                        + " error=" + p.hasThrowable());
            }
        }));
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(XposedHelpers.findClass(
                    "android.hardware.input.InputManager", cl), "injectInputEvent", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active() || p.args.length < 3 || !(p.args[0] instanceof android.view.KeyEvent e)
                            || e.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) return;
                    line("back-dispatch action=" + e.getAction() + " eventTime=" + e.getEventTime()
                            + " targetUid=" + p.args[2] + " result=" + Boolean.TRUE.equals(p.getResult())
                            + " error=" + p.hasThrowable());
                }
            });
            retain(hooks);
            line("observer=back-dispatch installed=" + !hooks.isEmpty());
        } catch (Throwable ignored) { line("observer=back-dispatch installed=false"); }
        try {
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            retain(XposedBridge.hookAllMethods(window, "canReceiveKeys", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active() || Boolean.TRUE.equals(readingFocus.get())
                            || p.hasThrowable() || p.args.length != 0) return;
                    Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                    if (task == null || !Integer.valueOf(6).equals(
                            XposedHelpers.callMethod(task, "getWindowingMode"))) return;
                    synchronized (MiStepInputTrace.class) {
                        if (focusSamples++ >= 12) return;
                    }
                    readingFocus.set(Boolean.TRUE);
                    try {
                        Object display = XposedHelpers.callMethod(p.thisObject, "getDisplayContent");
                        line("focusCandidate task=" + XposedHelpers.getIntField(task, "mTaskId")
                                + " nativeKeys=" + p.getResult()
                                + " topLease=" + MiStepDesktopLaunchBridge.ownsTopTask(task)
                                + " passiveLease=" + (MiStepDesktopLaunchBridge.recentsLease(task) != null)
                                + " appFocused=" + MiStepImePolicy.isTopAppFocused(task, display)
                                + " reason=" + XposedHelpers.callMethod(
                                        p.thisObject, "canReceiveKeysReason", false));
                    } finally { readingFocus.remove(); }
                }
            }));
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            retain(XposedBridge.hookAllMethods(display, "findFocusedWindow", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active()) return;
                    Object focused = p.getResult();
                    Object task = focused == null ? null : XposedHelpers.callMethod(focused, "getTask");
                    synchronized (MiStepInputTrace.class) { if (focusSamples++ >= 12) return; }
                    line("focusScan resultTask=" + (task == null ? -1
                            : XposedHelpers.getIntField(task, "mTaskId")));
                }
            }));
        } catch (Throwable error) { unavailable(error); }
        try {
            Class<?> dispatcher = XposedHelpers.findClass("com.android.server.wm.PointerEventDispatcher", cl);
            retain(XposedBridge.hookAllMethods(dispatcher, "onInputEvent", new TraceHook() {
                @Override protected void before(MethodHookParam p) {
                    if (p.thisObject == globalReceiver && active() && p.args.length == 1
                            && p.args[0] instanceof MotionEvent e) event("legacy-global-monitor", e);
                }
            }));
            Object service = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.ServiceManager", cl), "getService", "input");
            globalChannel = MiStepPassiveInputChannel.open(service, "MiStepSixSecondTrace", cl);
            globalReceiver = XposedHelpers.newInstance(dispatcher, globalChannel.input);
            line("legacy-global-monitor installed=true");
        } catch (Throwable error) { unavailable(error); }
        hook(cl, "com.android.server.input.MiuiInputManagerService",
                "beforeNotifyMotion", "miui-before-notify");
        hook(cl, "com.android.server.input.MiuiInputManagerService",
                "notifyTouchMotionEvent", "miui-touch-notify");
        hook(cl, "com.miui.server.input.gesture.MiuiGestureEventDispatcher",
                "onInputEvent", "miui-gesture-spy");
        try {
            Class<?> transition = XposedHelpers.findClass("com.android.server.wm.Transition", cl);
            retain(XposedBridge.hookAllMethods(transition, "calculateTransitionInfo", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active() || p.hasThrowable() || !TransitionMagnificationCompat.isStart(p.args)) return;
                    line("transition start type=" + p.args[0]);
                    line("transition flags=" + p.args[1] + " source=calculateTransitionInfo");
                    int count = 0;
                    for (Object change : (List<?>) p.args[2]) {
                        if (++count > 3) break;
                        try {
                            Object container = XposedHelpers.getObjectField(change, "mContainer");
                            chain(container);
                            if (count == 1) activeRecents(XposedHelpers.callMethod(container, "getDisplayContent"), "infoBuilt");
                        }
                        catch (Throwable error) { unavailable(error); }
                    }
                    Object info = p.getResult();
                    if (info != null) try {
                        int roots = (Integer) XposedHelpers.callMethod(info, "getRootCount");
                        for (int i = 0; i < roots && i < 4; i++) {
                            Object root = XposedHelpers.callMethod(info, "getRoot", i);
                            line("transitionRoot display=" + XposedHelpers.callMethod(root, "getDisplayId")
                                    + " layer=" + layer(XposedHelpers.callMethod(root, "getLeash"))
                                    + " offset=" + XposedHelpers.callMethod(root, "getOffset"));
                        }
                    } catch (Throwable error) { unavailable(error); }
                }
            }));
            retain(XposedBridge.hookAllMethods(transition, "handleLegacyRecentsStartBehavior", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active() || p.hasThrowable() || p.args.length < 1) return;
                    activeRecents(p.args[0], "legacyRecentsAfter");
                }
            }));
            retain(XposedBridge.hookAllMethods(transition, "buildFinishTransaction", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active()) return;
                    line("transition finishTransactionPrepared (animation may not have started)");
                    try {
                        int count = 0;
                        for (Object change : (List<?>) XposedHelpers.getObjectField(p.thisObject, "mTargets")) {
                            if (++count > 3) break;
                            chain(XposedHelpers.getObjectField(change, "mContainer"));
                        }
                    } catch (Throwable error) { unavailable(error); }
                }
            }));
            retain(XposedBridge.hookAllMethods(transition, "finishTransition", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active() || p.hasThrowable()) return;
                    line("transition actualFinished");
                    int count = 0;
                    for (Object change : (List<?>) XposedHelpers.getObjectField(p.thisObject, "mTargets")) {
                        if (++count > 3) break;
                        Object container = XposedHelpers.getObjectField(change, "mContainer");
                        chain(container);
                        if (count == 1) {
                            windowsRecorded = false;
                            captureDisplay(XposedHelpers.callMethod(container, "getDisplayContent"));
                        }
                    }
                }
            }));
        } catch (Throwable ignored) { }
    }

    private static void activeRecents(Object display, String phase) {
        if (display == null || !active()) return;
        try {
            Object monitor = XposedHelpers.callMethod(display, "getInputMonitor");
            Object task = dereference(XposedHelpers.getObjectField(monitor, "mActiveRecentsTask"));
            Object relative = dereference(XposedHelpers.getObjectField(monitor, "mActiveRecentsLayerRef"));
            Object consumer = XposedHelpers.callMethod(monitor, "getInputConsumer", "recents_animation_input_consumer");
            Object surface = consumer == null ? null : XposedHelpers.getObjectField(consumer, "mInputSurface");
            line("activeRecents=" + taskId(task) + " phase=" + phase
                    + " display=" + XposedHelpers.getIntField(display, "mDisplayId")
                    + " taskLayer=" + (task == null ? "none" : layer(XposedHelpers.callMethod(task, "getSurfaceControl")))
                    + " relativeLayer=" + (relative == null ? "none" : layer(XposedHelpers.callMethod(relative, "getSurfaceControl")))
                    + " consumerLayer=" + layer(surface)
                    + " remoteToRecents=" + XposedHelpers.getObjectField(monitor, "mIsRemoteToRecent"));
        } catch (Throwable error) { unavailable(error); }
    }

    private static Object dereference(Object reference) {
        return reference instanceof java.lang.ref.Reference<?> ? ((java.lang.ref.Reference<?>) reference).get() : null;
    }

    private static int taskId(Object task) {
        return task == null ? -1 : XposedHelpers.getIntField(task, "mTaskId");
    }

    static void captureDisplay(Object display) {
        if (!active() || windowsRecorded || display == null) return;
        try {
            java.util.function.Consumer<Object> inspect = window -> {
                try {
                    WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                            XposedHelpers.getObjectField(window, "mAttrs");
                    if (attrs.type != 2009 || !"DynamicIslandWindow".contentEquals(attrs.getTitle())) return;
                    windowsRecorded = true;
                    Region touch = new Region();
                    XposedHelpers.callMethod(window, "getEffectiveTouchableRegion", touch);
                    line("islandWindow type=" + attrs.type + " privateFlags=" + XposedHelpers.getIntField(attrs, "privateFlags")
                            + " frameLogical=" + XposedHelpers.callMethod(window, "getFrame")
                            + " touchLogical=" + touch);
                    chain(window);
                } catch (Throwable error) { unavailable(error); }
            };
            XposedHelpers.callMethod(display, "forAllWindows", inspect, false);
        } catch (Throwable error) { unavailable(error); }
    }

    private static void chain(Object container) {
        for (int depth = 0; container != null && depth < 8 && active(); depth++) {
            Object spec = null;
            try { spec = XposedHelpers.getObjectField(container, "mLastMagnificationSpec"); }
            catch (Throwable ignored) { }
            String matrix = "none";
            if (spec != null) matrix = XposedHelpers.getFloatField(spec, "scale") + ","
                    + XposedHelpers.getFloatField(spec, "offsetX") + "," + XposedHelpers.getFloatField(spec, "offsetY");
            Object parent = XposedHelpers.callMethod(container, "getParent");
            int taskId = -1;
            try { taskId = XposedHelpers.getIntField(container, "mTaskId"); } catch (Throwable ignored) { }
            line("chain depth=" + depth + " class=" + container.getClass().getSimpleName() + " task=" + taskId
                    + " layer=" + layer(XposedHelpers.callMethod(container, "getSurfaceControl"))
                    + " parentLayer=" + (parent == null ? "none" : layer(XposedHelpers.callMethod(parent, "getSurfaceControl")))
                    + " magnification=" + matrix);
            container = parent;
        }
    }

    private static String layer(Object surface) {
        if (surface == null) return "none";
        try { return String.valueOf(XposedHelpers.callMethod(surface, "getLayerId")); }
        catch (Throwable ignored) { return "unavailable"; }
    }
}
