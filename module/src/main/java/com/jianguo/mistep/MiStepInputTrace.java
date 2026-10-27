package com.jianguo.mistep;

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
    static final String ACTION = "com.jianguo.mistep.TRACE_INPUT";
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
    private static boolean unavailableRecorded;
    private static final List<XC_MethodHook.Unhook> HOOKS = new ArrayList<>();

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
        unavailableRecorded = false;
        File dir = inServer ? new File("/data/system/mistep-input-traces")
                : new File(ctx.getFilesDir(), "mistep-input-traces");
        output = new File(dir, (inServer ? "wm-" : "ui-") + now + ".txt");
        line("ARM uptime=" + now + " duration=6000 maxLines=128 numeric-only=true");
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
        if (!GATE.take(SystemClock.uptimeMillis())) return;
        LINES.append(SystemClock.uptimeMillis()).append(' ').append(text).append('\n');
        if (!GATE.active(SystemClock.uptimeMillis())) finish(GATE.generation());
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
                        if (now - lastFrame < 32) return;
                        lastFrame = now;
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
            retain(XposedBridge.hookAllMethods(XposedHelpers.findClass(cls, cl), method, new TraceHook() {
                @Override protected void before(MethodHookParam p) {
                    if (!active() || p.args.length == 0 || !(p.args[0] instanceof MotionEvent)) return;
                    String source = p.args.length > 1 && p.args[1] instanceof String
                            ? (String) p.args[1] : "monitor";
                    // Sources are labels from the ROM, never arbitrary contents.
                    if (source.length() > 48 || !source.matches("[A-Za-z0-9_ -]+")) source = "other";
                    event(label + " source=" + source, (MotionEvent) p.args[0]);
                }
            }));
        } catch (Throwable ignored) { }
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
        line(label + " eventTime=" + event.getEventTime() + " action=" + event.getActionMasked()
                + " display=" + XposedHelpers.callMethod(event, "getDisplayId") + " local=" + event.getX() + "," + event.getY()
                + " raw=" + event.getRawX() + "," + event.getRawY() + " pointers=" + event.getPointerCount());
        float[] transform = MiStepHost.activeMainTransform();
        if (transform != null && event.getActionMasked() == MotionEvent.ACTION_DOWN)
            line("hostExpectedZoom=" + transform[0] + "," + transform[1] + "," + transform[2]);
        } catch (Throwable error) { unavailable(error); }
    }

    private static void hookSystemServer(ClassLoader cl) {
        try {
            Class<?> transition = XposedHelpers.findClass("com.android.server.wm.Transition", cl);
            retain(XposedBridge.hookAllMethods(transition, "calculateTransitionInfo", new TraceHook() {
                @Override protected void after(MethodHookParam p) {
                    if (!active() || p.hasThrowable() || p.args.length != 6 || !(p.args[2] instanceof List<?>)) return;
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
