package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Mi Step宿主模块。
 *
 * v0.1 能力探针（已完成）：核对类/方法签名，确认能拿到 TaskViewTransitions / TaskViewFactory。
 * v0.2 加窗口（已完成）：在 SystemUI 进程内用常规 WindowManager 加一个自己的窗口。
 * v0.3-v0.5（已完成）：单个 TaskView 内渲染真实应用，含 PendingIntent 与自锁修正。
 * v0.6（inc4）：4 个 TaskView（主区+3 格）+ MiStepLayout 几何 + 点击副格交换。
 * v0.7：角部内滑热区（R2 参数）+ 广播触发；首次触发再启动宿主，之后切换显示。
 * v0.8：修 inset 偏移、onTaskInfoChanged 状态、格子密度自适应、亮屏重断言。
 * v0.8.1（本版）：cutout 模式 ALWAYS（去掉 122px 状态栏偏移）、工作区不透明底、
 *                任务边界直发 WCT、热区提到宿主窗口之上。
 * 规格：research/rea分析/10-最少实现规格冻结.md、11-体验移植路线图.md；仍只启动我们自己的新任务。
 */
public class HostEntry implements IXposedHookLoadPackage {

    private static final String TAG = "MiStepProbe";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String DESKTOP_ICON_LAUNCH_ACTION =
            "com.jiusis.mistep.DESKTOP_ICON_LAUNCH";
    private static final long LAUNCH_DELAY_MS = 12_000L;   // 等开机稳定
    private static final long AFTER_ADD_MS = 2_500L;       // 等 TaskView 表面就绪再启动

    /** 试验方框的位置与尺寸（后面要换成自适应布局）。 */
    private static final int BOX_W = 600;
    private static final int BOX_H = 900;
    private static final int BOX_X = 40;
    private static final int BOX_Y = 520;

    private static final String TARGET_PKG = "com.android.settings";

    private static volatile Context sContext;
    private static volatile Object sTaskViewFactory;
    private static volatile Object sTaskViewTransitions;
    private static volatile Executor sShellExecutor;
    private static volatile boolean sStarted;

    private static View sWindowRoot;
    private static Object sTaskView;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            NativeMultiWindowUnlock.install(lpparam.classLoader, true);
            hookSystemServer(lpparam.classLoader);
            return;
        }
        if ("com.xiaomi.type".equals(lpparam.packageName)) {
            hookMiTypeAutoFloating(lpparam.classLoader);
            return;
        }
        if (!SYSTEMUI.equals(lpparam.packageName)) {
            // Raw correction is owned by the system publisher; never invert it twice in apps.
            return;
        }
        final ClassLoader cl = lpparam.classLoader;
        log("=== attached to " + SYSTEMUI + " build=1.0.0 release ===");
        try {
            NativeMultiWindowUnlock.install(cl, false);
            MiStepSystemActions.install(cl);
            MiStepNativeMedia.install(cl);
            MiStepIslandMonitorInput.install(cl, () -> sContext);
            MiStepStatusBarSampling.install(cl);
            MiStepStatusBar.install(cl);
            MiStepPanelInput.install(cl);
            MiStepMainUiConfiguration.install(cl);
            MiStepNotificationLaunch.install(cl, () -> sContext);
            NativeFreeformCoordinates.install(cl);
            NativeFreeformPreview.install(cl);
            hookApplication(cl);
            hookShellProviders(cl);
            hookRecentsWallpaper(cl);
            hookBackGestureDiagnostics(cl);
            hookTransitionBounds(cl);
        } catch (Throwable t) {
            log("hooks failed: " + t);
        }
    }

    /**
     * MiType independently enters a compact PopupWindow when a landscape
     * keyboard cannot fit the current task width.  That is the wrong policy
     * inside Mi Step: the main task owns the native landscape transform, so
     * the IME should keep its normal full landscape layout.  Skip only the
     * keyboard's auto-floating decision; manual floating mode remains owned
     * by the keyboard itself.
     */
    private static void hookMiTypeAutoFloating(ClassLoader cl) {
        log("=== attached to com.xiaomi.type build=1.0.0 release ===");
        try {
            Class<?> service = XposedHelpers.findClass("com.mi.ime.MiInputMethodService", cl);
            XposedHelpers.findAndHookMethod(service, "handleSpacePressureAutoFloating",
                    android.view.inputmethod.EditorInfo.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Context)) return;
                    Context context = (Context) param.thisObject;
                    try {
                        long now = System.currentTimeMillis();
                        String encoded = android.provider.Settings.Global.getString(
                                context.getContentResolver(), "mistep_zoom");
                        if (!MiStepImePolicy.suppressAutomaticFloating(encoded,
                                now - android.os.SystemClock.elapsedRealtime(), now)) return;
                    } catch (Throwable unavailable) {
                        // Missing/denied/stale host state must leave the keyboard alone.
                        return;
                    }
                    // The original method only replaces the normal input view
                    // with MiType's floating placeholder and PopupWindow.  A
                    // no-op keeps the IME attached to the native landscape
                    // window configuration already supplied by WM.
                    param.setResult(null);
                    log("MiType automatic landscape floating suppressed");
                }
            });
            log("MiType auto-floating decision hook installed");
        } catch (Throwable error) {
            log("MiType auto-floating hook unavailable: " + error);
        }
    }

    // ---------------------------------------------------------------- v1.1.0：系统框架整屏缩放（R2 同款）

    private static volatile Object sDisplayContent;
    private static volatile Object sSystemContext;
    private static volatile boolean sZoomed;
    private static volatile ZoomTarget sZoomTarget;
    /** 缩放动画只在 WM handler 上创建/取消；帧内矩阵仍在 mGlobalLock 内应用。 */
    private static volatile ValueAnimator sZoomAnimator;
    private static volatile String sZoomAnimKey;
    private static volatile boolean sZoomFrameApply;
    private static volatile PathInterpolator zoomAnimInterpolator;
    private static volatile float sZoomScale = 1f, sZoomOffset, sZoomOffsetX;
    private static final long sZoomWatcherStarted = System.currentTimeMillis();
    private static final ZoomChannelState sZoomChannels = new ZoomChannelState(sZoomWatcherStarted);
    private static volatile android.os.FileObserver sZoomFileObserver;
    private static volatile Handler sZoomBridgeHandler;
    private static volatile boolean sHostZoomed;
    private static volatile String sZoomReadError;
    private static volatile String sZoomLastSeen;
    private static volatile String sZoomLastDispatched;
    private static volatile String sZoomLastTargetNull;
    private static ZoomCommit sLiveZoomCommit;
    private static long sLiveZoomIssued;
    private static volatile boolean sFullscreenRotationActive;
    private static volatile int sHeldDisplayRotation;
    private static volatile Object sMiStepFixedRotationApp;
    private static volatile int sNativeLandscapeState = NativeLandscapePolicy.INACTIVE;
    /** Accessed only under the WMS global lock; these are per-token, not display launches. */
    private static final java.util.Map<Object, Integer> sTaskRotations =
            new java.util.WeakHashMap<>();
    /** Only surfaces actually written by our WM magnification, under its global lock. */
    private static final java.util.Map<Object, Object> sMagnifiedSurfaces =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<Object, Object> sMagnificationFinishReplays =
            new java.util.WeakHashMap<>();
    private static final java.util.Map<Object, Object> sHomeTransitionInfos =
            new java.util.WeakHashMap<>();
    private static final ThreadLocal<Boolean> sFinalMagnificationSnapshot = new ThreadLocal<>();
    private static final ThreadLocal<Object> sMagnificationFinishInfo = new ThreadLocal<>();
    private static final NativeFreeformStartGate sNativeFreeformStart = new NativeFreeformStartGate();
    private static final NativeFreeformStartGate sLiveSwapZoomSurfaces = new NativeFreeformStartGate();
    /** Per-task state survives top-activity changes and main/side ownership swaps. */
    private static final java.util.Map<Object, TaskWindowState> sTaskWindowStates =
            new java.util.WeakHashMap<>();
    private static java.lang.reflect.Method sTaskRotationResolver;
    private static boolean sUpdatingTaskRotation;
    private static final java.util.Map<Object, String> sGestureSurfaces =
            new java.util.WeakHashMap<>();

    static void setHostZoomed(boolean zoomed) {
        sHostZoomed = zoomed;
    }

    private static final class ZoomTarget {
        final HostEntry owner;
        final Object display;
        final Object globalLock;
        final Handler handler;
        final ZoomDispatch dispatch;

        ZoomTarget(HostEntry owner, Object display, Object globalLock, Handler handler) {
            this.owner = owner;
            this.display = display;
            this.globalLock = java.util.Objects.requireNonNull(globalLock);
            this.handler = handler;
            this.dispatch = new ZoomDispatch(handler::post, globalLock,
                    (identity, scale, offsetY, offsetX) -> {
                        if (sZoomTarget == identity) {
                            // 静态状态请求优先于动画：先取消动画，避免结束回调把旧目标写回去。
                            owner.cancelZoomAnimation(this);
                            owner.applyDisplayZoomLocked(this, scale, offsetY, offsetX);
                        }
                    }, error -> {
                        log("WM zoom request stopped; no same-state retry: " + error);
                        XposedBridge.log(error);
                    });
        }

        /** 动画命令必须回到 WM handler 上创建，与普通请求共用同一串行化路径。 */
        void submitAnim(ZoomAnimCommand.Plan plan, String key) {
            handler.post(() -> {
                if (sZoomTarget != this) return;
                owner.startZoomAnimation(this, plan, key);
            });
        }
    }

    /**
     * 系统框架作用域（LSPosed 里要勾"系统框架"）：
     * 捕获默认显示器的 DisplayContent，收到 com.jiusis.mistep.ZOOM 广播时调用
     * DisplayContent.applyMagnificationSpec(scale/offset) —— 目标机自带的整屏缩放原语，
     * 与锤子Mi Step的 MagnificationSpecSmt / applyZoomedMatrixSmt 是同一机制的官方版本。
     */
    private void hookSystemServer(ClassLoader cl) {
        log("=== attached to system_server (android) build=1.0.0 release ===");
        MiStepRecents.install(cl);
        NativeRawInputBridge.install();
        try {
            Class<?> dc = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(dc, "configureSurfaces", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    try {
                        Object type = XposedHelpers.callMethod(self, "getDisplayId");
                        if (type instanceof Integer && ((Integer) type).intValue() != 0) {
                            return;
                        }
                        sDisplayContent = self;
                        Object service = XposedHelpers.getObjectField(self, "mWmService");
                        Object lock = XposedHelpers.getObjectField(service, "mGlobalLock");
                        Object handler = XposedHelpers.getObjectField(service, "mH");
                        if (!(handler instanceof Handler)) {
                            throw new IllegalStateException("WMS.mH is not a Handler");
                        }
                        // configureSurfaces creates a new display surface even for the same DC.
                        // New identity invalidates deduplication after a real surface rebuild.
                        cancelZoomAnimation(null);
                        sZoomAnimKey = null;
                        sZoomTarget = new ZoomTarget(HostEntry.this, self, lock, (Handler) handler);
                        armZoomBridge(service);
                        dispatchCurrentZoom();
                        log("captured DisplayContent(display 0) with WM handler/global lock -> " + self);
                    } catch (Throwable t) {
                        sZoomTarget = null;
                        log("capture DisplayContent failed: " + t);
                    }
                }
            });
            log("DisplayContent#configureSurfaces hooked");
        } catch (Throwable t) {
            log("hookSystemServer hook failed: " + t);
            return;
        }
        MiStepRecentsInput.install(cl, () -> sZoomed
                ? new float[]{sZoomScale, sZoomOffset, sZoomOffsetX} : null);
        MiStepWallpaperScale.install(cl, () -> {
            ZoomTarget target = sZoomTarget;
            return sZoomed && target != null && target.display == sDisplayContent
                    ? target.display : null;
        });
        installMagnificationSurfaceTracking(cl);
        hookMagnificationReplayAfterTransition(cl);
        installNativeFreeformCoexistence(cl);
        installFixedMainRotationHooks(cl);
        installMainSystemUiRotation(cl);
        installFixedMainTouchableCropHook(cl);
        installSideSystemBarPolicy(cl);
        installWorkspaceFocus(cl);
        installWorkspaceRunningTasks(cl);
        // Use WM's own visual AND input-window magnification path for system input UI.
        // The target normally excludes IME and conditionally excludes the navigation bar.
        // Do not change accessibility settings or pretend that a mouse is connected.
        try {
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "shouldMagnify", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!sZoomed || sDisplayContent == null) return;
                    try {
                        if (XposedHelpers.callMethod(p.thisObject, "getDisplayContent") != sDisplayContent) return;
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                                XposedHelpers.getObjectField(p.thisObject, "mAttrs");
                        if (task == null && (attrs.type == 2011 || attrs.type == 2012)) {
                            Object target = XposedHelpers.callMethod(sDisplayContent, "getImeInputTarget");
                            Object window = target == null ? null
                                    : XposedHelpers.callMethod(target, "getWindowState");
                            task = window == null ? null : XposedHelpers.callMethod(window, "getTask");
                        }
                        // Native freeforms inherit the same WM parent transform as main apps.
                        // Their canonical leash retains the OEM's own mini/freeform scale.
                        int flags = XposedHelpers.getIntField(attrs, "privateFlags");
                        // Native gesture windows keep the ROM's own magnification policy.
                        if (InputMagnificationPolicy.shouldForceMagnification(attrs.type, flags,
                                attrs.packageName, attrs.getTitle()))
                            p.setResult(Boolean.TRUE);
                    } catch (Throwable error) { log("input UI magnification skipped: " + error); }
                }
            });
            log("scoped IME/navigation magnification admitted on default display");
        } catch (Throwable error) { log("input UI magnification unavailable: " + error); }
        try {
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "canBeImeLayeringTarget",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!sZoomed || !Boolean.TRUE.equals(p.getResult())) return;
                    try {
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        if (task != null
                                && Integer.valueOf(6).equals(XposedHelpers.callMethod(
                                        task, "getWindowingMode"))
                                && Boolean.TRUE.equals(XposedHelpers.callMethod(
                                        task, "isForceTranslucent"))) {
                            p.setResult(Boolean.FALSE);
                        }
                    } catch (Throwable error) {
                        log("side IME exclusion skipped: " + error);
                    }
                }
            });
            log("side TaskViews excluded from IME layering while zoomed");
        } catch (Throwable error) { log("side IME exclusion unavailable: " + error); }
        try {
            Class<?> spy = XposedHelpers.findClass("com.android.server.input.GestureMonitorSpyWindow", cl);
            XposedBridge.hookAllConstructors(spy, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length != 7 || !Integer.valueOf(0).equals(p.args[2])) return;
                    String name = String.valueOf(p.args[1]);
                    try {
                        Context context = (Context) sSystemContext;
                        String[] packages = context == null ? null
                                : context.getPackageManager().getPackagesForUid((Integer) p.args[4]);
                        String kind = NativeFreeformPolicy.gestureKind((Integer) p.args[2],
                                name, (Integer) p.args[4], packages);
                        if (kind == null) return;
                        Object surface = XposedHelpers.getObjectField(p.thisObject, "mInputSurface");
                        synchronized (sGestureSurfaces) {
                            sGestureSurfaces.put(surface, kind);
                        }
                        ZoomTarget target = sZoomTarget;
                        if (target != null) target.handler.post(() -> {
                            synchronized (target.globalLock) {
                                if (sZoomTarget == target)
                                    applyGestureSurfacesLocked(target, sZoomScale,
                                            sZoomOffset, sZoomOffsetX);
                            }
                        });
                        log("captured gesture monitor name=" + name + " pid=" + p.args[3]
                                + " uid=" + p.args[4] + " packages="
                                + (packages == null ? "unknown" : java.util.Arrays.toString(packages)));
                        if ("return".equals(kind)) {
                            StackTraceElement[] frames = Thread.currentThread().getStackTrace();
                            StringBuilder callers = new StringBuilder();
                            for (int i = 1; i < frames.length && i < 9; i++) {
                                if (callers.length() > 0) callers.append(" <- ");
                                callers.append(frames[i].getClassName()).append('.')
                                        .append(frames[i].getMethodName());
                            }
                            log("return gesture monitor creator " + callers);
                        }
                    } catch (Throwable error) { log("navigation surface admission skipped: " + error); }
                }
            });
        } catch (Throwable error) { log("navigation input geometry unavailable: " + error); }
        // Keep the non-resizable override inside Mi Step-owned side tasks. A process-wide
        // override also changes Home and unrelated windowing decisions.
        try {
            Class<?> taskCls = XposedHelpers.findClass("com.android.server.wm.Task", cl);
            XposedBridge.hookAllMethods(taskCls, "isResizeable", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!sFullscreenRotationActive) return;
                    try {
                        if (isMiStepSideTask(param.thisObject)) param.setResult(Boolean.TRUE);
                    } catch (Throwable error) {
                        log("scoped resizeability check skipped: " + error);
                    }
                }
            });
            log("Task#isResizeable override scoped to active Mi Step side tasks");
        } catch (Throwable t) {
            log("Task#isResizeable hook failed: " + t);
        }
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", cl);
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            Object ctx = thread == null ? null
                    : XposedHelpers.callMethod(thread, "getSystemContext");
            sSystemContext = ctx;
            log("system context -> " + ctx);
        } catch (Throwable t) {
            log("system context failed: " + t);
        }
        installDesktopIconLaunchBridge(cl);
        // 文件只作备用；所有通道共享有时限的最新请求，读取失败不能撤销有效广播。
        //   mistep-zoom 内容 "scale offsetY offsetX"（如 "0.7472 622 273"），"1 0 0" = 还原；
        //   动画行为 "anim <scale> <offsetY> <offsetX> <durationMs>"，从当前 spec 起步。
        // SystemUI 私有文件用 FileObserver 即时触发（宿主自己写，用于呼出动画与后续交换动画）；
        // 250ms 轮询只作兜底，并保留 adb/root 写 /data/system/mistep-zoom 的调试通道。
        try {
            Thread watcher = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            java.io.File f1 = new java.io.File("/data/system/mistep-zoom");
                            java.io.File f2 = new java.io.File(
                                    "/data/user_de/0/com.android.systemui/files/mistep-zoom");
                            java.io.File f = f1;
                            if (f2.exists() && (!f1.exists()
                                    || f2.lastModified() > f1.lastModified())) {
                                f = f2;
                            }
                            readAndDispatchZoom(f);
                            Thread.sleep(250L);
                        } catch (InterruptedException stopped) {
                            Thread.currentThread().interrupt();
                            return;
                        } catch (Throwable t) {
                            try {
                                Thread.sleep(2000L);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    }
                }
            }, "mistep-zoom-watch");
            watcher.setDaemon(true);
            watcher.start();
            log("ZOOM lease watcher armed; transforms only on locked WMS handler");
        } catch (Throwable t) {
            log("ZOOM watcher failed: " + t);
        }
        installZoomFileObserver();
    }

    private static volatile boolean sZoomBridgeArmed;
    private static volatile android.content.Context sWmsContext;
    private static volatile String sLastSettingsZoom;
    private static boolean sZoomSettingsWatcherArmed;

    /** Settings.Global 通道：SystemUI 有 WRITE_SECURE_SETTINGS，system_server 读取不受文件权限影响。 */
    private static synchronized void startZoomSettingsWatcher() {
        if (sZoomSettingsWatcherArmed) return;
        try {
            Thread thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        android.content.Context context = sWmsContext;
                        if (context != null) {
                            String value = android.provider.Settings.Global.getString(
                                    context.getContentResolver(), "mistep_zoom");
                            synchronized (sZoomChannels) {
                                long now = System.currentTimeMillis();
                                boolean accepted = sZoomChannels.offerEncoded(value, now);
                                if (accepted && !java.util.Objects.equals(value, sLastSettingsZoom)) {
                                    log("zoom settings received: " + value);
                                }
                                sLastSettingsZoom = value;
                                dispatchCurrentZoom();
                            }
                        }
                        Thread.sleep(200L);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Throwable ignored) {
                        try { Thread.sleep(1000L); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }, "mistep-zoom-settings");
            thread.setDaemon(true);
            thread.start();
            sZoomSettingsWatcherArmed = true;
            log("zoom settings watcher armed");
        } catch (Throwable error) {
            log("zoom settings watcher failed: " + error);
        }
    }
    /** 文件通道在部分机型被 SELinux/权限挡住；再开一条 SystemUI -> system_server 的广播直投。 */
    private static synchronized void armZoomBridge(Object service) {
        try {
            final Context context = (Context) XposedHelpers.getObjectField(service, "mContext");
            sWmsContext = context;
            startZoomSettingsWatcher();
            if (sZoomBridgeArmed) return;
            android.os.HandlerThread thread = new android.os.HandlerThread("mistep-zoom-bridge");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            sZoomBridgeHandler = handler;
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent == null) return;
                    if (MiStepCodes.ACTION.equals(intent.getAction())) {
                        setResultCode(MiStepCodes.receive(context, service.getClass().getClassLoader(), intent));
                        return;
                    }
                    if (LiveSwapZoomProtocol.APPLY.equals(intent.getAction())) {
                        receiveLiveZoom(context, intent);
                        return;
                    }
                    if (MiStepInputTrace.ACTION.equals(intent.getAction())) {
                        if (intent.getBooleanExtra("stopInputTrace", false)) {
                            MiStepInputTrace.stop();
                            return;
                        }
                        ZoomTarget target = sZoomTarget;
                        if (target != null) target.handler.post(() -> {
                            if (target != sZoomTarget) return;
                            MiStepInputTrace.arm(context, true, target.display.getClass().getClassLoader());
                            synchronized (target.globalLock) {
                                MiStepInputTrace.captureDisplay(target.display);
                            }
                        });
                        return;
                    }
                    if (MiStepDesktopLaunchBridge.ACTION.equals(intent.getAction())) {
                        MiStepDesktopLaunchBridge.update(intent);
                        return;
                    }
                    if ("com.jiusis.mistep.TRANSFER_TOUCH".equals(intent.getAction())) {
                        transferSideTouch(intent);
                        return;
                    }
                    try {
                        synchronized (sZoomChannels) {
                            long now = System.currentTimeMillis();
                            String encoded = intent.getStringExtra("request");
                            if (encoded != null) {
                                sZoomChannels.offerEncoded(encoded, now);
                            } else {
                                // Preserve explicit adb diagnostics and older host senders.
                                float scale = intent.getFloatExtra("scale", 1f);
                                float y = intent.getFloatExtra("offsetY", 0f);
                                float x = intent.getFloatExtra("offsetX", 0f);
                                sZoomChannels.offer(scale + " " + y + " " + x, now, now);
                            }
                            dispatchCurrentZoom();
                        }
                    } catch (Throwable error) {
                        log("zoom broadcast submit failed: " + error);
                    }
                }
            };
            IntentFilter filter = new IntentFilter("com.jiusis.mistep.ZOOM");
            filter.addAction(LiveSwapZoomProtocol.APPLY);
            filter.addAction("com.jiusis.mistep.TRANSFER_TOUCH");
            filter.addAction(MiStepInputTrace.ACTION);
            filter.addAction(MiStepDesktopLaunchBridge.ACTION);
            filter.addAction(MiStepCodes.ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                // SystemUI / shell can send; ordinary apps cannot change display magnification.
                context.registerReceiver(receiver, filter, android.Manifest.permission.WRITE_SECURE_SETTINGS,
                        handler, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, android.Manifest.permission.WRITE_SECURE_SETTINGS, handler);
            }
            sZoomBridgeArmed = true;
            log("zoom broadcast bridge armed");
        } catch (Throwable error) {
            log("zoom broadcast bridge failed: " + error);
        }
    }
    /** The captured transaction is the one DisplayContent applies, never a separate crop transaction. */
    private static final class ZoomCommit {
        final Context context;
        final ZoomTarget target;
        final LiveSwapZoom.Zoom zoom;
        long transactionId;
        float actualScale = Float.NaN, actualX = Float.NaN, actualY = Float.NaN;
        boolean complete;
        boolean applied;

        ZoomCommit(Context context, ZoomTarget target, LiveSwapZoom.Zoom zoom) {
            this.context = context; this.target = target; this.zoom = zoom;
        }

        void finish(boolean committed, String error) {
            if (complete) return;
            Object replay = null;
            if (committed) {
                try {
                    synchronized (target.globalLock) {
                        if (target != sZoomTarget || !zoom.sameTuple(sZoomScale, sZoomOffsetX, sZoomOffset))
                            throw new IllegalStateException("zoom changed before finish snapshot");
                        replay = createMagnificationFinishReplay(target.display);
                    }
                } catch (Throwable failure) {
                    LiveSwapZoom.closeReplay(replay); replay = null;
                    committed = false; error = "WMS finish snapshot failed: " + failure.getClass().getSimpleName();
                }
            }
            complete = true;
            if (sLiveZoomCommit == this) sLiveZoomCommit = null;
            Intent ack = new Intent(LiveSwapZoomProtocol.ACK).setPackage(SYSTEMUI);
            ack.putExtra(LiveSwapZoomProtocol.GENERATION, zoom.generation);
            ack.putExtra(LiveSwapZoomProtocol.TRANSACTION, transactionId);
            ack.putExtra(LiveSwapZoomProtocol.SCALE, Float.floatToIntBits(actualScale));
            ack.putExtra(LiveSwapZoomProtocol.X, Float.floatToIntBits(actualX));
            ack.putExtra(LiveSwapZoomProtocol.Y, Float.floatToIntBits(actualY));
            ack.putExtra(LiveSwapZoomProtocol.COMMITTED, committed);
            if (replay != null) ack.putExtra(LiveSwapZoomProtocol.FINISH_REPLAY, (android.os.Parcelable) replay);
            if (error != null) ack.putExtra(LiveSwapZoomProtocol.ERROR, error);
            final Object sentReplay = replay;
            try {
                // We run inside system_server: AMS can retain the Intent locally and parcel
                // it later. Keep native operations alive until actual receiver dispatch ends.
                context.sendOrderedBroadcast(ack, LiveSwapZoomProtocol.PERMISSION,
                        new BroadcastReceiver() {
                            @Override public void onReceive(Context c, Intent result) {
                                LiveSwapZoom.closeReplay(sentReplay);
                            }
                        }, target.handler, 0, null, null);
            } catch (Throwable failure) {
                LiveSwapZoom.closeReplay(sentReplay);
                log("live zoom acknowledgement failed: " + failure);
            }
        }
    }

    /** Snapshot operations only. Shell merges and applies them with the task handoff. */
    private static Object createMagnificationFinishReplay(Object display) throws Exception {
        Object replay = Class.forName("android.view.SurfaceControl$Transaction")
                .getDeclaredConstructor().newInstance();
        try {
            reapplyFinalMagnification(display, replay);
            return replay;
        } catch (Throwable failure) {
            LiveSwapZoom.closeReplay(replay);
            throw new IllegalStateException("current magnification snapshot failed", failure);
        }
    }

    private static void reapplyFinalMagnification(Object display, Object transaction) {
        reapplyFinalMagnification(display, transaction, null);
    }

    private static void reapplyFinalMagnification(Object display, Object transaction, Object info) {
        Boolean previous = sFinalMagnificationSnapshot.get();
        Object previousInfo = sMagnificationFinishInfo.get();
        sFinalMagnificationSnapshot.set(Boolean.TRUE);
        if (info == null) sMagnificationFinishInfo.remove();
        else sMagnificationFinishInfo.set(info);
        try { XposedHelpers.callMethod(display, "reapplyMagnificationSpec", transaction); }
        finally {
            if (previous == null) sFinalMagnificationSnapshot.remove();
            else sFinalMagnificationSnapshot.set(previous);
            if (previousInfo == null) sMagnificationFinishInfo.remove();
            else sMagnificationFinishInfo.set(previousInfo);
        }
    }

    private static void receiveLiveZoom(Context context, Intent intent) {
        long generation = intent.getLongExtra(LiveSwapZoomProtocol.GENERATION, 0L);
        long issued = intent.getLongExtra(LiveSwapZoomProtocol.ISSUED, 0L);
        float scale = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE, 0));
        float x = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X, 0));
        float y = Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y, 0));
        ZoomTarget target = sZoomTarget;
        if (target == null || !LiveSwapZoomProtocol.valid(generation, scale, x, y,
                issued, System.currentTimeMillis())) return;
        LiveSwapZoom.Zoom zoom = new LiveSwapZoom.Zoom(generation, scale, x, y);
        target.handler.post(() -> {
            ZoomCommit commit = new ZoomCommit(context, target, zoom);
            try {
                synchronized (target.globalLock) {
                    if (target != sZoomTarget || issued < sLiveZoomIssued) {
                        commit.finish(false, "stale zoom target or lease"); return;
                    }
                    synchronized (sZoomChannels) {
                        long now = System.currentTimeMillis();
                        if (!LiveSwapZoomProtocol.valid(generation, scale, x, y, issued, now)) {
                            commit.finish(false, "expired zoom lease"); return;
                        }
                        String command = scale + " " + y + " " + x;
                        sZoomChannels.offer(command, issued, now);
                        ZoomChannelState.Request current = sZoomChannels.current(now);
                        if (current.modified > issued && !command.equals(current.command)) {
                            commit.finish(false, "superseded zoom lease"); return;
                        }
                    }
                    if (sLiveZoomCommit != null) sLiveZoomCommit.finish(false, "superseded generation");
                    sLiveZoomIssued = issued;
                    sLiveZoomCommit = commit;
                    target.dispatch.takeOver(target, scale, y, x);
                    target.owner.cancelZoomAnimation(target);
                    // Logical WM parents already have their final roles, while the real
                    // leashes still animate under Host. Applying this traversal's clear
                    // operations now exposes an identity/fullscreen task for one frame.
                    // Commit the WM spec/input state; the exact visual traversal is already
                    // regenerated by ZoomCommit.finish and merged with Shell's reparent.
                    Object staged = Class.forName("android.view.SurfaceControl$Transaction")
                            .getDeclaredConstructor().newInstance();
                    if (!sLiveSwapZoomSurfaces.begin(target.display, staged)) {
                        LiveSwapZoom.closeReplay(staged);
                        throw new IllegalStateException("live zoom surface scope already active");
                    }
                    try { target.owner.applyDisplayZoomLocked(target, scale, y, x, commit); }
                    finally {
                        sLiveSwapZoomSurfaces.finish(target.display, staged);
                        LiveSwapZoom.closeReplay(staged);
                    }
                }
                target.handler.postDelayed(() -> commit.finish(false, "WMS transaction commit timeout"),
                        LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS - 100L);
            } catch (Throwable failure) {
                target.dispatch.invalidateLastAttempt();
                commit.finish(false, "WMS zoom apply failed: " + failure.getClass().getSimpleName());
            }
        });
    }
    /** Transfer the existing physical gesture; InputDispatcher maps task coordinates once. */
    private static void transferSideTouch(Intent intent) {
        ZoomTarget target = sZoomTarget;
        android.os.Bundle tokens = intent.getBundleExtra("tokens");
        if (target == null || tokens == null) return;
        android.os.IBinder hostToken = tokens.getBinder("host");
        android.os.IBinder taskToken = tokens.getBinder("task");
        int taskId = intent.getIntExtra("taskId", -1);
        long eventTime = intent.getLongExtra("eventTime", 0L);
        target.handler.post(() -> {
            long age = android.os.SystemClock.uptimeMillis() - eventTime;
            if (sZoomTarget != target || sZoomScale >= 1f || age < 0 || age > 500
                    || hostToken == null || taskToken == null || taskId < 0) return;
            try {
                Object from;
                Object to;
                Object input;
                synchronized (target.globalLock) {
                    Object service = XposedHelpers.getObjectField(target.display, "mWmService");
                    java.util.Map<?, ?> windows = (java.util.Map<?, ?>)
                            XposedHelpers.getObjectField(service, "mWindowMap");
                    Object host = windows.get(hostToken);
                    if (host == null) return;
                    WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                            XposedHelpers.getObjectField(host, "mAttrs");
                    if (!"MiStepHost".contentEquals(attrs.getTitle())
                            || !SYSTEMUI.equals(attrs.packageName)
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(host, "isVisible"))) return;
                    Class<?> container = XposedHelpers.findClass(
                            "com.android.server.wm.WindowContainer", target.display.getClass().getClassLoader());
                    Object task = XposedHelpers.callStaticMethod(container, "fromBinder", taskToken);
                    if (task == null || XposedHelpers.getIntField(task, "mTaskId") != taskId
                            || !Integer.valueOf(6).equals(XposedHelpers.callMethod(task, "getWindowingMode"))
                            || XposedHelpers.callMethod(task, "getDisplayContent") != target.display) return;
                    Object[] app = {null};
                    Consumer<Object> pick = window -> {
                        if (app[0] != null) return;
                        WindowManager.LayoutParams wa = (WindowManager.LayoutParams)
                                XposedHelpers.getObjectField(window, "mAttrs");
                        if ((wa.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                                || wa.type == WindowManager.LayoutParams.TYPE_APPLICATION)
                                && Boolean.TRUE.equals(XposedHelpers.callMethod(window, "isVisible"))
                                && XposedHelpers.getObjectField(window, "mInputChannelToken") != null) {
                            app[0] = window;
                        }
                    };
                    XposedHelpers.callMethod(task, "forAllWindows", pick, true);
                    if (app[0] == null) return;
                    from = XposedHelpers.getObjectField(host, "mInputChannelToken");
                    to = XposedHelpers.getObjectField(app[0], "mInputChannelToken");
                    input = XposedHelpers.getObjectField(service, "mInputManager");
                }
                if (from == null || to == null) return;
                Object result;
                try {
                    result = XposedHelpers.callMethod(input, "transferTouchGesture", from, to, true);
                } catch (NoSuchMethodError olderApi) {
                    result = XposedHelpers.callMethod(input, "transferTouchGesture", from, to);
                }
                log("side touch transferred task=" + taskId + " result=" + result + " age=" + age);
            } catch (Throwable error) {
                log("side touch transfer failed task=" + taskId + ": " + error);
            }
        });
    }
    /** watcher 与 FileObserver 共用：读文件、按租约解析、投递到 WM handler。 */
    private static void readAndDispatchZoom(java.io.File f) {
        String cur = "";
        long modified = 0L;
        Throwable readError = null;
        try {
            if (f != null) {
                modified = f.lastModified();
                if (f.exists()) {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.FileReader(f));
                    cur = br.readLine();
                    br.close();
                }
            }
        } catch (Throwable error) {
            readError = error;
            if (sZoomReadError == null || !sZoomReadError.equals(String.valueOf(error))) {
                sZoomReadError = String.valueOf(error);
                log("zoom read error: " + error);
            }
            cur = "";
        }
        if (cur == null) cur = "";
        if (!cur.equals(sZoomLastSeen)) {
            sZoomLastSeen = cur;
            log("zoom cmd seen: '" + cur + "' modified=" + modified
                    + " target=" + (sZoomTarget != null) + " readError=" + readError
                    + " canRead=" + (f != null && f.canRead())
                    + " perms=" + (f != null && f.exists() ? Long.toOctalString(f.length()) : "-"));
        }
        long now = System.currentTimeMillis();
        synchronized (sZoomChannels) {
            sZoomChannels.offer(cur, modified, now);
            dispatchCurrentZoom();
        }
    }

    /** Replay on target creation and restore only when the shared host lease expires. */
    private static void dispatchCurrentZoom() {
        synchronized (sZoomChannels) {
            long now = System.currentTimeMillis();
            ZoomChannelState.Request selected = sZoomChannels.current(now);
            ZoomTarget target = sZoomTarget;
            if (target == null) {
                if (!selected.command.equals(sZoomLastTargetNull)) {
                    sZoomLastTargetNull = selected.command;
                    log("zoom request retained until display ready: " + selected.command);
                }
                return;
            }
            ZoomAnimCommand.Plan anim = ZoomAnimCommand.parse(selected.command,
                    selected.modified, sZoomWatcherStarted, now);
            if (anim != null) {
                target.submitAnim(anim, selected.animationKey);
            } else {
                float[] request = ZoomCommand.read(selected.command,
                        selected.modified, sZoomWatcherStarted, now);
                if (!selected.command.equals(sZoomLastDispatched)) {
                    sZoomLastDispatched = selected.command;
                    log("zoom dispatch " + java.util.Arrays.toString(request)
                            + " from leased request '" + selected.command + "'");
                }
                target.dispatch.submit(target, request[0], request[1], request[2]);
            }
        }
    }

    /** Preserve active WM magnification after Shell resets its transition targets. */
    private void hookMagnificationReplayAfterTransition(ClassLoader cl) {
        try {
            Class<?> transition = XposedHelpers.findClass(
                    "com.android.server.wm.Transition", cl);
            XposedBridge.hookAllMethods(transition, "calculateTransitionInfo", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ZoomTarget target = sZoomTarget;
                    if (p.hasThrowable() || !sZoomed || target == null
                            || target.display != sDisplayContent || !Thread.holdsLock(target.globalLock)
                            || p.args.length != 6 || !(p.args[2] instanceof java.util.List<?>)) return;
                    try {
                        if (NativeFreeformReturn.change(p.getResult()) != null) {
                            Object nativeTask = null;
                            for (Object change : (java.util.List<?>) p.args[2]) {
                                Object container = XposedHelpers.getObjectField(change, "mContainer");
                                Object task = XposedHelpers.callMethod(container, "asTask");
                                if (task != null && XposedHelpers.getIntField(change, "mWindowingMode") == 5
                                        && Integer.valueOf(1).equals(XposedHelpers.callMethod(task, "getWindowingMode"))
                                        && XposedHelpers.callMethod(task, "getDisplayContent") == target.display)
                                    nativeTask = task;
                            }
                            Object start = p.args[3];
                            if (nativeTask == null || !sNativeFreeformStart.begin(target.display, start)) return;
                            try {
                                ((android.view.SurfaceControl.Transaction) start).addTransactionCommittedListener(
                                        command -> target.handler.post(command), () -> finishNativeFreeformStart(target, start));
                                if (!NativeFreeformHandoff.markNativePrepared(nativeTask))
                                    throw new IllegalStateException("native return source identity changed");
                                reapplyFinalMagnification(target.display, start);
                                if (!HomeTransitionMagnification.prepare(p.getResult(), (java.util.List<?>) p.args[2],
                                        target.display, start, sZoomScale, sZoomOffsetX, sZoomOffset))
                                    throw new IllegalStateException("native return Root scene changed");
                                log("native return Root/start magnification transferred atomically");
                            } catch (Throwable error) {
                                sNativeFreeformStart.finish(target.display, start); throw error;
                            }
                            return;
                        }
                        for (Object change : (java.util.List<?>) p.args[2]) {
                            if (XposedHelpers.getIntField(change, "mWindowingMode") != 1
                                    || !Boolean.TRUE.equals(XposedHelpers.getObjectField(change, "mVisible"))) continue;
                            Object container = XposedHelpers.getObjectField(change, "mContainer");
                            Object task = XposedHelpers.callMethod(container, "asTask");
                            if (task == null || XposedHelpers.callMethod(task, "getDisplayContent") != target.display
                                    || ((Number) XposedHelpers.callMethod(task, "getWindowingMode")).intValue() != 5)
                                continue;
                            Object start = p.args[3];
                            if (!sNativeFreeformStart.begin(target.display, start)) return;
                            try {
                                android.view.SurfaceControl.Transaction tx =
                                        (android.view.SurfaceControl.Transaction) start;
                                tx.addTransactionCommittedListener(command -> target.handler.post(command),
                                        () -> finishNativeFreeformStart(target, start));
                                if (!NativeFreeformHandoff.markPrepared(task))
                                    throw new IllegalStateException("native fullscreen source identity changed");
                                reapplyFinalMagnification(target.display, start);
                                log("native freeform main transform transferred in start transaction");
                            } catch (Throwable error) {
                                sNativeFreeformStart.finish(target.display, start);
                                throw error;
                            }
                            return;
                        }
                        for (Object change : (java.util.List<?>) p.args[2]) {
                            Object container = XposedHelpers.getObjectField(change, "mContainer");
                            Object task = XposedHelpers.callMethod(container, "asTask");
                            if (task == null) continue;
                            int transitMode = (Integer) XposedHelpers.callMethod(
                                    change, "getTransitMode", container);
                            if (!MainLaunchMagnification.shouldApply(sZoomed, target.display,
                                    XposedHelpers.callMethod(task, "getDisplayContent"),
                                    XposedHelpers.callMethod(task, "getRootTask") == task,
                                    Boolean.TRUE.equals(XposedHelpers.callMethod(
                                            task, "isVisibleRequested")),
                                    (Integer) XposedHelpers.callMethod(task, "getWindowingMode"),
                                    transitMode)) continue;
                            reapplyFinalMagnification(target.display, p.args[3]);
                            if (HomeTransitionMagnification.prepare(p.getResult(),
                                    (java.util.List<?>) p.args[2], target.display, p.args[3],
                                    sZoomScale, sZoomOffsetX, sZoomOffset))
                                log("Home transition root retains workspace transform");
                            log("main launch magnification included in transition start task="
                                    + XposedHelpers.getIntField(task, "mTaskId")
                                    + " transit=" + transitMode);
                            return;
                        }
                    } catch (Throwable error) { log("transition start magnification skipped: " + error); }
                }
            });
            XposedBridge.hookAllMethods(transition, "buildFinishTransaction", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args == null || p.args.length != 3
                            || !(p.args[2] instanceof Object[])) return;
                    ZoomTarget target = sZoomTarget;
                        if (target == null) return;
                    try {
                        if (HomeTransitionMagnification.hasInfo(p.args[1]))
                            sHomeTransitionInfos.put(p.thisObject, p.args[1]);
                        boolean replayed = TransitionMagnificationReplay.reapplyIfActive(
                                sZoomed, target.display, Thread.holdsLock(target.globalLock),
                                (Object[]) p.args[2], p.args[0],
                                (display, transaction) -> reapplyFinalMagnification(
                                        display, transaction, p.args[1]));
                        if (replayed) {
                            // A closing native task can stop normal child-layer assignment.
                            // Detach our windows before its leash becomes hidden, and include
                            // the same restoration in the transition's finish transaction.
                            placeHostBelowNativeFreeforms(target.display, p.args[0]);
                            sMagnificationFinishReplays.put(p.thisObject, target.display);
                            log("display magnification reasserted in transition finish");
                        }
                    } catch (Throwable error) {
                        log("transition magnification replay unavailable: " + error);
                    }
                }
            });
            XposedBridge.hookAllMethods(transition, "finishTransition", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ZoomTarget target = sZoomTarget;
                    if (target == null || !Thread.holdsLock(target.globalLock)) return;
                    HomeTransitionMagnification.finish(sHomeTransitionInfos.remove(p.thisObject));
                    try {
                        Object start = XposedHelpers.getObjectField(p.thisObject, "mStartTransaction");
                        sNativeFreeformStart.finish(target.display, start);
                    } catch (Throwable ignored) { }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    ZoomTarget target = sZoomTarget;
                    if (target == null || !Thread.holdsLock(target.globalLock)) return;
                    Object display = sMagnificationFinishReplays.remove(p.thisObject);
                    if (p.hasThrowable() || display == null || target == null
                            || display != target.display) return;
                    target.handler.post(() -> {
                        synchronized (target.globalLock) {
                            if (target != sZoomTarget || target.display != sDisplayContent) return;
                            try {
                                if (sZoomed) {
                                    // A new session may have started since this finish was built.
                                    Object transaction = XposedHelpers.callMethod(display,
                                            "getPendingTransaction");
                                    XposedHelpers.callMethod(display, "reapplyMagnificationSpec",
                                            transaction);
                                    placeHostBelowNativeFreeforms(display, transaction);
                                    XposedHelpers.callMethod(transaction, "apply");
                                } else {
                                    restoreExitedMagnificationLocked(target);
                                }
                            } catch (Throwable error) {
                                log("transition zoom reconciliation skipped: " + error);
                            }
                        }
                    });
                }
            });
            log("Transition#buildFinishTransaction magnification replay hooked");
        } catch (Throwable error) {
            log("transition magnification replay hook unavailable: " + error);
        }
    }

    private static void finishNativeFreeformStart(ZoomTarget target, Object start) {
        synchronized (target.globalLock) {
            if (!sNativeFreeformStart.finish(target.display, start) || target != sZoomTarget) return;
            try {
                if (sZoomed) {
                    Object transaction = XposedHelpers.callMethod(target.display, "getPendingTransaction");
                    XposedHelpers.callMethod(target.display, "reapplyMagnificationSpec", transaction);
                    XposedHelpers.callMethod(transaction, "apply");
                } else restoreExitedMagnificationLocked(target);
                log("native freeform start committed; normal magnification writes resumed");
            } catch (Throwable error) { log("native freeform committed handoff skipped: " + error); }
        }
    }

    private static final MiStepSystemUiRotation sMainSystemUiRotation = new MiStepSystemUiRotation();
    private static final MiStepStatusBarClip sMainStatusBarClip = new MiStepStatusBarClip();
    private static final MiStepImeProcessConfiguration sMainImeProcessConfiguration =
            new MiStepImeProcessConfiguration();
    private static Object sPublishedMainUiState;
    private boolean systemUiRotationFailureLogged;

    private void updateMainSystemUiRotation(Object window) {
        if (!sFullscreenRotationActive || !sZoomed) return;
        try {
            Object app = sMiStepFixedRotationApp;
            if (app != null && !sTaskRotations.containsKey(app)) app = null;
            publishMainUiConfiguration(app);
            sMainSystemUiRotation.update(window, sDisplayContent, app, HostEntry::log);
            sMainStatusBarClip.update(window, sDisplayContent, HostEntry::log);
            sMainImeProcessConfiguration.update(window, sDisplayContent, app, HostEntry::log);
        } catch (Throwable error) {
            if (!systemUiRotationFailureLogged) {
                systemUiRotationFailureLogged = true;
                log("main system UI rotation unavailable: " + error);
            }
        }
    }

    private static void publishMainUiConfiguration(Object app) {
        try {
            Object state = app == null ? null : XposedHelpers.getObjectField(app, "mFixedRotationTransformState");
            if (state == sPublishedMainUiState) return;
            sPublishedMainUiState = state;
            final android.content.res.Configuration config = state == null ? null
                    : new android.content.res.Configuration((android.content.res.Configuration)
                            XposedHelpers.getObjectField(state, "mRotatedOverrideConfiguration"));
            Context context = (Context) (sSystemContext != null ? sSystemContext : sWmsContext);
            Handler bridge = sZoomBridgeHandler;
            if (context == null || bridge == null) return;
            // Never broadcast synchronously while holding WMS's global lock.
            bridge.post(() -> context.sendBroadcast(new Intent(MiStepMainUiConfiguration.ACTION)
                    .setPackage(SYSTEMUI).putExtra("configuration", config),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS));
        } catch (Throwable error) { log("main UI configuration publication unavailable: " + error); }
    }

    private void installMainSystemUiRotation(ClassLoader cl) {
        try {
            sMainImeProcessConfiguration.install(cl);
            Class<?> organizer = XposedHelpers.findClass("com.android.server.wm.WindowOrganizerController", cl);
            XposedBridge.hookAllMethods(organizer, "applyTaskChanges", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sFullscreenRotationActive || p.args.length < 2) return;
                    try { p.setObjectExtra("mistep.sideRole", MiStepSideRoleChange.enter(
                            p.args[0], p.args[1], sDisplayContent)); }
                    catch (Throwable error) { log("side role change observation unavailable: " + error); }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    MiStepSideRoleChange.exit(p.getObjectExtra("mistep.sideRole"));
                }
            });
            Class<?> policy = XposedHelpers.findClass("com.android.server.wm.DisplayPolicy", cl);
            XposedBridge.hookAllMethods(policy, "layoutWindowLw", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0) updateMainSystemUiRotation(p.args[0]);
                }
            });
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedBridge.hookAllMethods(window, "prepareSurfaces", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    updateMainSystemUiRotation(p.thisObject);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !sZoomed || sDisplayContent == null) return;
                    try {
                        Object spec = XposedHelpers.getObjectField(sDisplayContent, "mMagnificationSpec");
                        if (spec != null) MiStepSystemUiRotation.magnifyPrivacy(p.thisObject,
                                sDisplayContent, spec,
                                XposedHelpers.callMethod(sDisplayContent, "getPendingTransaction"));
                    } catch (Throwable error) {
                        if (!systemUiRotationFailureLogged) {
                            systemUiRotationFailureLogged = true;
                            log("permission window zoom unavailable: " + error);
                        }
                    }
                }
            });
            log("main system UI native token rotation and late permission-window zoom hooked");
        } catch (Throwable error) { log("main system UI rotation hooks unavailable: " + error); }
    }

    private void installMagnificationSurfaceTracking(ClassLoader cl) {
        try {
            Class<?> container = XposedHelpers.findClass("com.android.server.wm.WindowContainer", cl);
            Class<?> transaction = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
            Class<?> spec = XposedHelpers.findClass("android.view.MagnificationSpec", cl);
            XposedHelpers.findAndHookMethod(container, "applyMagnificationSpec", transaction,
                    spec, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ZoomTarget target = sZoomTarget;
                    if (!sZoomed || target == null || target.display != sDisplayContent
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        boolean finalSnapshot = Boolean.TRUE.equals(sFinalMagnificationSnapshot.get());
                        // Even an empty concurrent transition builds a display-wide finish.
                        // Only Home's own delayed finish may restore children below its Root.
                        boolean homeFinish = finalSnapshot && HomeTransitionMagnification.hasInfo(
                                sMagnificationFinishInfo.get());
                        if (!homeFinish && HomeTransitionMagnification.owns(
                                p.thisObject, target.display)) {
                            p.setObjectExtra("mistep.sideMagnification", Boolean.TRUE);
                            p.setResult(null);
                            return;
                        }
                        if (MiStepSideMagnification.ownsGeometry(p.thisObject, target.display)) {
                            p.setObjectExtra("mistep.sideMagnification", Boolean.TRUE);
                            p.setResult(null);
                            return;
                        }
                        p.args[0] = sNativeFreeformStart.route(p.thisObject, target.display,
                                p.args[0], Boolean.TRUE.equals(sFinalMagnificationSnapshot.get()));
                        p.args[0] = sLiveSwapZoomSurfaces.route(p.thisObject, target.display,
                                p.args[0], Boolean.TRUE.equals(sFinalMagnificationSnapshot.get()));
                        java.util.Map<Object, Object> tracked = sMagnifiedSurfaces;
                        if (!homeFinish && HomeTransitionMagnification.activeOn(target.display)) {
                            tracked = new java.util.IdentityHashMap<>(tracked);
                            tracked.entrySet().removeIf(entry ->
                                    HomeTransitionMagnification.owns(entry.getKey(), target.display));
                        }
                        MiStepMagnificationTree.prepare(p.thisObject, target.display,
                                p.args[0], p.args[1], tracked);
                    } catch (Throwable error) {
                        log("magnification replay surface cleanup skipped: " + error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()
                            && !Boolean.TRUE.equals(p.getObjectExtra("mistep.sideMagnification")))
                        rememberMagnifiedSurface(p.thisObject, p.args[1]);
                }
            });
            XposedHelpers.findAndHookMethod(container, "clearMagnificationSpec", transaction,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    ZoomTarget target = sZoomTarget;
                    if (!sZoomed || target == null || target.display != sDisplayContent
                            || !Thread.holdsLock(target.globalLock)) return;
                    try {
                        // Native clear recurses before apply. Skipping apply alone lets
                        // an older WM spec reset the TaskView leash to identity first.
                        boolean homeFinish = Boolean.TRUE.equals(sFinalMagnificationSnapshot.get())
                                && HomeTransitionMagnification.hasInfo(sMagnificationFinishInfo.get());
                        if (!homeFinish
                                && HomeTransitionMagnification.owns(p.thisObject, target.display)) {
                            p.setResult(null);
                            return;
                        }
                        if (MiStepSideMagnification.ownsGeometry(p.thisObject, target.display))
                            p.setResult(null);
                    } catch (Throwable error) {
                        log("side magnification clear guard unavailable: " + error);
                    }
                }
            });
            log("WM magnification surface identities tracked for exit reconciliation");
        } catch (Throwable error) {
            log("WM magnification surface tracking unavailable: " + error);
        }
    }

    private static void rememberMagnifiedSurface(Object container, Object spec) {
        ZoomTarget target = sZoomTarget;
        if (!sZoomed || target == null || spec == null
                || target.display != sDisplayContent || !Thread.holdsLock(target.globalLock)) return;
        try {
            if (XposedHelpers.callMethod(container, "getDisplayContent") != target.display
                    || XposedHelpers.getObjectField(target.display, "mMagnificationSpec") != spec
                    || XposedHelpers.getObjectField(container, "mLastMagnificationSpec") != spec) return;
            Object surface = XposedHelpers.getObjectField(container, "mSurfaceControl");
            if (surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
                sMagnifiedSurfaces.put(container, surface);
        } catch (Throwable error) {
            log("magnified surface identity skipped: " + error);
        }
    }

    private static int restoreExitedMagnificationLocked(ZoomTarget target) {
        if (target == null || target != sZoomTarget || target.display != sDisplayContent
                || sZoomed || !Thread.holdsLock(target.globalLock)) return 0;
        int restored = 0;
        try {
            if (XposedHelpers.getObjectField(target.display, "mMagnificationSpec") != null) return 0;
            Object transaction = XposedHelpers.callMethod(target.display, "getPendingTransaction");
            java.util.Iterator<java.util.Map.Entry<Object, Object>> entries =
                    sMagnifiedSurfaces.entrySet().iterator();
            while (entries.hasNext()) {
                java.util.Map.Entry<Object, Object> entry = entries.next();
                Object container = entry.getKey(), surface = entry.getValue();
                try {
                    if (XposedHelpers.callMethod(container, "getDisplayContent") != target.display
                            || XposedHelpers.getObjectField(container, "mSurfaceControl") != surface
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) {
                        entries.remove();
                        continue;
                    }
                    if (XposedHelpers.getObjectField(container, "mLastMagnificationSpec") != null)
                        continue;
                    if (Integer.valueOf(5).equals(XposedHelpers.callMethod(container,
                                    "getWindowingMode"))
                            && XposedHelpers.callMethod(container, "asTask") != null) {
                        // WM has already released this canonical task leash. An OEM
                        // mini/freeform animation now owns its scale and position;
                        // an old main-region record must not reset it on entry/exit.
                        entries.remove();
                        continue;
                    }
                    Object position = XposedHelpers.getObjectField(container, "mLastSurfacePosition");
                    XposedHelpers.callMethod(transaction, "setMatrix", surface, 1f, 0f, 0f, 1f);
                    XposedHelpers.callMethod(transaction, "setPosition", surface,
                            (float) XposedHelpers.getIntField(position, "x"),
                            (float) XposedHelpers.getIntField(position, "y"));
                    restored++;
                } catch (Throwable error) {
                    entries.remove();
                    log("exit surface restoration skipped: " + error);
                }
            }
            if (restored > 0) {
                XposedHelpers.callMethod(transaction, "apply");
                log("exit magnification surfaces restored=" + restored);
            }
        } catch (Throwable error) {
            log("exit magnification reconciliation unavailable: " + error);
        }
        return restored;
    }

    /** 宿主写 SystemUI 私有文件时立即处理，避免 250ms 轮询造成的桥/缩放不同帧。 */
    private static void installZoomFileObserver() {
        try {
            final java.io.File dir =
                    new java.io.File("/data/user_de/0/com.android.systemui/files");
            if (!dir.isDirectory()) {
                log("zoom FileObserver skipped: no SystemUI files dir");
                return;
            }
            android.os.FileObserver observer = new android.os.FileObserver(dir,
                    android.os.FileObserver.CLOSE_WRITE
                            | android.os.FileObserver.MOVED_TO
                            | android.os.FileObserver.CREATE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path == null || !path.endsWith("mistep-zoom")) return;
                    readAndDispatchZoom(new java.io.File(dir, "mistep-zoom"));
                }
            };
            observer.startWatching();
            sZoomFileObserver = observer;
            log("zoom FileObserver armed on SystemUI files dir");
        } catch (Throwable t) {
            log("zoom FileObserver failed: " + t);
        }
    }

    /** Hold the workspace portrait; app transforms must never announce display rotation. */
    private void installFixedMainRotationHooks(ClassLoader cl) {
        try {
            Class<?> displayContent = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(displayContent,
                    "handleTopActivityLaunchingInDifferentOrientation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length < 1 || p.args[0] == null) return;
                    Boolean decision = applyMiStepFixedRotationLocked(
                            p.thisObject, p.args[0], "activity-orientation");
                    if (decision != null) p.setResult(decision);
                }
            });
            log("task rotation: activity path intercepted before display launch");
        } catch (Throwable error) {
            log("fixed rotation top-activity hook unavailable: " + error);
        }
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", cl);
            XposedBridge.hookAllMethods(activityRecord, "setRequestedOrientation",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length > 0 && p.args[0] instanceof Integer) {
                        beforeApplicationOrientationRequest(p.thisObject, (Integer) p.args[0]);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!sFullscreenRotationActive || p.thisObject == null) return;
                    try {
                        Object display = XposedHelpers.getObjectField(
                                p.thisObject, "mDisplayContent");
                        // This path also covers non-top Mi Step side tasks. The stock
                        // DisplayContent hook only sees the current top activity.
                        applyMiStepFixedRotationLocked(display, p.thisObject,
                                "activity-requested-orientation");
                    } catch (Throwable error) {
                        log("task rotation requested-orientation refresh failed: " + error);
                    }
                }
            });
            log("task rotation: ActivityRecord requests hooked for side tasks");
        } catch (Throwable error) {
            log("fixed rotation ActivityRecord hook unavailable: " + error);
        }
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", cl);
            XposedBridge.hookAllMethods(activityRecord, "onConfigurationChanged",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!sFullscreenRotationActive || p.thisObject == null
                            || sUpdatingTaskRotation) return;
                    try {
                        Object display = XposedHelpers.getObjectField(
                                p.thisObject, "mDisplayContent");
                        applyMiStepFixedRotationLocked(display, p.thisObject,
                                "activity-configuration-changed");
                    } catch (Throwable error) {
                        log("task rotation configuration refresh failed: " + error);
                    }
                }
            });
            log("task rotation: configuration changes re-evaluate token orientation");
        } catch (Throwable error) {
            log("fixed rotation configuration hook unavailable: " + error);
        }
        try {
            Class<?> displayContent = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayContent", cl);
            for (String name : new String[]{"setFixedRotationLaunchingApp",
                    "setFixedRotationLaunchingAppUnchecked"}) {
                XposedBridge.hookAllMethods(displayContent, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (sFullscreenRotationActive && p.thisObject == sDisplayContent
                                && p.args.length > 0 && p.args[0] != null
                                && sTaskRotations.containsKey(p.args[0])) {
                            // OEM promotion paths also try to register an already transformed
                            // token as a display launch. Keep our transform local to that token.
                            p.setResult(null);
                        }
                    }
                });
            }
            log("task rotation: owned tokens excluded from display launch notifications");
        } catch (Throwable error) {
            log("task rotation display-launch exclusion unavailable: " + error);
        }
        try {
            Class<?> displayRotation = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayRotation", cl);
            sTaskRotationResolver = XposedHelpers.findMethodExact(displayRotation,
                    "rotationForOrientation", int.class, int.class);
            XposedBridge.hookMethod(sTaskRotationResolver, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        Object display = XposedHelpers.getObjectField(p.thisObject,
                                "mDisplayContent");
                        int displayId = (Integer) XposedHelpers.callMethod(display, "getDisplayId");
                        if (FullscreenRotationPolicy.shouldHoldDisplay(
                                sFullscreenRotationActive, displayId)
                                && display == sDisplayContent) {
                            p.setResult(sHeldDisplayRotation);
                        }
                    } catch (Throwable error) {
                        log("fixed rotation display hold skipped: " + error);
                    }
                }
            });
            log("fixed rotation: display 0 hold hooked");
            XposedBridge.hookAllMethods(displayRotation, "updateRotationUnchecked",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (sUpdatingTaskRotation) return;
                    try {
                        Object display = XposedHelpers.getObjectField(p.thisObject,
                                "mDisplayContent");
                        if (display != sDisplayContent) return;
                        evaluateNativeLandscapeLocked(display,
                                "display rotation updated");
                        if (!sFullscreenRotationActive) return;
                        refreshMiStepTaskRotationLocked(display);
                    } catch (Throwable error) {
                        log("task rotation policy refresh failed: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            log("fixed rotation display hold unavailable: " + error);
        }
    }

    /** Root-task bounds remain in display orientation while our main token is rotated. */
    private void installFixedMainTouchableCropHook(ClassLoader cl) {
        try {
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "cropRegionToRootTaskBoundsIfNeeded",
                    android.graphics.Region.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 1 && p.args[0] instanceof android.graphics.Region
                            && cropFixedMainTouchableRegion(p.thisObject,
                                    (android.graphics.Region) p.args[0])) p.setResult(null);
                }
            });
            log("owned fixed main: root touch crop uses rotated display coordinates");
        } catch (Throwable error) {
            log("fixed main touch crop hook unavailable: " + error);
        }
    }

    private static boolean cropFixedMainTouchableRegion(Object window,
            android.graphics.Region region) {
        ZoomTarget target = sZoomTarget;
        if (!sZoomed || !sFullscreenRotationActive || window == null || region == null
                || target == null || target.display != sDisplayContent
                || !Thread.holdsLock(target.globalLock)) return false;
        try {
            if (XposedHelpers.callMethod(window, "getDisplayContent") != sDisplayContent)
                return false;
            Object activity = XposedHelpers.getObjectField(window, "mActivityRecord");
            if (activity == null || activity != sMiStepFixedRotationApp) return false;
            Integer rotation = sTaskRotations.get(activity);
            if (rotation == null || ((rotation - sHeldDisplayRotation) & 1) != 1) return false;
            Object task = XposedHelpers.callMethod(window, "getTask");
            if (task == null || !Integer.valueOf(1).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))) return false;
            Object root = XposedHelpers.callMethod(task, "getRootTask");
            if (root == null || !Integer.valueOf(1).equals(
                    XposedHelpers.callMethod(root, "getWindowingMode"))) return false;
            Rect displayBounds = (Rect) XposedHelpers.callMethod(sDisplayContent, "getBounds");
            Rect rootBounds = (Rect) XposedHelpers.callMethod(root, "getBounds");
            Rect rotatedBounds = (Rect) XposedHelpers.callMethod(activity,
                    "getFixedRotationTransformDisplayBounds");
            if (displayBounds == null || !displayBounds.equals(rootBounds)
                    || rotatedBounds == null || rotatedBounds.isEmpty()
                    || rotatedBounds.width() != displayBounds.height()
                    || rotatedBounds.height() != displayBounds.width()) return false;
            // Keep letterbox exclusions and nonrectangular regions. Only change the
            // coordinate system of this full-display root intersection.
            region.op(rotatedBounds, android.graphics.Region.Op.INTERSECT);
            return true;
        } catch (Throwable error) {
            log("fixed main touch crop skipped: " + error);
            return false;
        }
    }

    private void beforeApplicationOrientationRequest(Object activity, int request) {
        if (!sFullscreenRotationActive || sUpdatingTaskRotation || activity == null
                || FullscreenRotationPolicy.requestsLandscape(request) || request == 14) return;
        try {
            if (XposedHelpers.getObjectField(activity, "mDisplayContent") != sDisplayContent) return;
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (task == null || Integer.valueOf(5).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))) return;
            if (XposedHelpers.callMethod(task, "topRunningActivity") != activity) return;
            TaskWindowState state = sTaskWindowStates.get(task);
            if (state != null) state.forgetRememberedRotation();
        } catch (Throwable error) {
            log("application orientation history reset unavailable: " + error);
        }
    }

    private Boolean applyMiStepFixedRotationLocked(Object display, Object activity, String source) {
        if (!sFullscreenRotationActive || display == null || display != sDisplayContent
                || activity == null) return null;
        if (sUpdatingTaskRotation) return Boolean.FALSE;
        sUpdatingTaskRotation = true;
        try {
            boolean home = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    activity, "isActivityTypeHome"));
            boolean multiWindow = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    activity, "inMultiWindowMode"));
            boolean sideTask = isSideFixedTask(activity);
            Object task = XposedHelpers.callMethod(activity, "getTask");
            int windowingMode = task == null ? 1
                    : (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            if (windowingMode == FullscreenRotationPolicy.WINDOWING_MODE_FREEFORM) {
                // HyperOS owns native freeform configuration, surfaces and rotation.
                // Never turn its orientation callback into our fullscreen/TaskView path.
                captureTaskWindowState(display, activity, source + " native-freeform");
                releaseTaskRotationLocked(activity, "returned to native freeform owner");
                return null;
            }
            // Role changes reconfigure the whole back stack. Only its running top
            // activity can define this task's direction or replace the main owner.
            // A portrait page underneath a landscape video must not cancel that
            // video's token transform while mode1/mode6 is being exchanged.
            if (task != null && XposedHelpers.callMethod(task, "topRunningActivity") != activity) {
                return Boolean.FALSE;
            }
            if (home || (multiWindow && !sideTask)) {
                // A promoted token keeps its local transform in the side slot. Do not
                // let WM promote that transform to mFixedRotationLaunchingApp.
                Object previous = sMiStepFixedRotationApp;
                if (previous != null) {
                    if (!isSideFixedTask(previous)) {
                        releaseTaskRotationLocked(previous,
                                home ? "main activity left for home" : "main activity left fullscreen");
                    }
                    if (sMiStepFixedRotationApp == previous) sMiStepFixedRotationApp = null;
                }
                return Boolean.FALSE;
            }
            if (NativeLandscapePolicy.suppressesLocalRotation(
                    sNativeLandscapeState, sideTask, home)) {
                // The workspace is temporarily suspended while the native display owns
                // the full landscape app. Side tasks were returned to system ownership.
                return Boolean.FALSE;
            }
            Object previous = sMiStepFixedRotationApp;
            if (!sideTask && previous != null && previous != activity) {
                if (!isSideFixedTask(previous)) {
                    releaseTaskRotationLocked(previous, "main activity changed");
                }
                sMiStepFixedRotationApp = null;
            }
            RotationResolution resolution = resolveTaskRotationLocked(
                    display, activity, task, windowingMode, source);
            int overrideOrientation = resolution.overrideOrientation;
            int resolvedRotation = resolution.resolvedRotation;
            int rotation = resolution.taskRotation;
            if (resolution.lands() && NativeLandscapePolicy.shouldUseNativeFullscreen(
                    sZoomed, sideTask, home)) {
                recordTaskWindowState(activity, overrideOrientation, resolvedRotation,
                        rotation, 1, source + " native-fullscreen");
                requestNativeLandscapeLocked(activity, source);
                return Boolean.FALSE;
            }
            recordTaskWindowState(activity, overrideOrientation, resolvedRotation,
                    rotation, sideTask ? 6 : 1, source);
            if (rotation == sHeldDisplayRotation) {
                releaseTaskRotationLocked(activity, source + " -> portrait/locked");
                return Boolean.FALSE;
            }
            boolean transformed = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    activity, "hasFixedRotationTransform"));
            Integer existingRotation = sTaskRotations.get(activity);
            if (transformed && existingRotation == null) {
                // Leave a native, in-flight rotation under its original owner's lifecycle.
                return Boolean.FALSE;
            }
            if (!transformed || existingRotation == null || existingRotation != rotation) {
                releaseTaskRotationLocked(activity, "task direction changed");
                if (!sideTask) sMiStepFixedRotationApp = activity;
                sTaskRotations.put(activity, rotation);
                try {
                    XposedHelpers.callMethod(display, "startFixedRotationTransform",
                            activity, rotation);
                } catch (Throwable error) {
                    releaseTaskRotationLocked(activity, "failed transform cleanup");
                    throw error;
                }
                log("task rotation: task=" + XposedHelpers.callMethod(activity, "getRootTaskId")
                        + " request=" + overrideOrientation + " resolved=" + resolvedRotation
                        + " rotationLocked=" + resolution.rotationLocked
                        + " held=" + sHeldDisplayRotation
                        + " existing=" + existingRotation + " taskRotation=" + rotation
                        + " localToken=true source=" + source);
            } else {
                if (!sideTask) sMiStepFixedRotationApp = activity;
            }
            return Boolean.TRUE;
        } catch (Throwable error) {
            log("fixed rotation apply failed source=" + source + ": " + error);
            XposedBridge.log(error);
            return Boolean.FALSE;
        } finally {
            sUpdatingTaskRotation = false;
        }
    }

    private static final class RotationResolution {
        final int overrideOrientation, resolvedRotation, taskRotation;
        final boolean rotationLocked;

        RotationResolution(int overrideOrientation, int resolvedRotation, int taskRotation,
                boolean rotationLocked) {
            this.overrideOrientation = overrideOrientation;
            this.resolvedRotation = resolvedRotation;
            this.taskRotation = taskRotation;
            this.rotationLocked = rotationLocked;
        }

        boolean lands() {
            return taskRotation == 1 || taskRotation == 3;
        }
    }

    private RotationResolution resolveTaskRotationLocked(Object display, Object activity,
            Object task, int windowingMode, String source) throws Throwable {
        int overrideOrientation = (Integer) XposedHelpers.callMethod(
                activity, "getOverrideOrientation");
        TaskWindowState state = sTaskWindowStates.get(task);
        if (state == null) {
            captureTaskWindowState(display, activity, source + " initial");
            state = sTaskWindowStates.get(task);
        }
        int rememberedRotation = state == null ? TaskWindowState.UNKNOWN_ROTATION
                : state.preferredRememberedRotation();
        boolean rotationLocked = isSystemRotationLocked(display);
        int lastRotation = sTaskRotations.containsKey(activity)
                ? sTaskRotations.get(activity)
                : rememberedRotation >= 0 ? rememberedRotation : sHeldDisplayRotation;
        int resolvedRotation = sHeldDisplayRotation;
        if (!rotationLocked
                || !FullscreenRotationPolicy.usesSensorOrUserRotation(overrideOrientation)
                || FullscreenRotationPolicy.requestsLandscape(overrideOrientation)) {
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            // Invoke WM's original resolver, bypassing only our display-hold hook.
            // This retains OEM policy, current-user settings and sensor filtering.
            resolvedRotation = (Integer) XposedBridge.invokeOriginalMethod(
                    sTaskRotationResolver, displayRotation,
                    new Object[]{overrideOrientation, lastRotation});
        }
        int rotation = FullscreenRotationPolicy.taskRotation(
                overrideOrientation, resolvedRotation, rotationLocked,
                windowingMode, rememberedRotation);
        return new RotationResolution(
                overrideOrientation, resolvedRotation, rotation, rotationLocked);
    }

    private void requestNativeLandscapeLocked(Object activity, String source) {
        int next = NativeLandscapePolicy.onLandscapeRequested(sNativeLandscapeState);
        if (next == sNativeLandscapeState) return;
        sNativeLandscapeState = next;
        sendNativeLandscapeMode(true);
        log("native landscape requested: task="
                + XposedHelpers.callMethod(activity, "getRootTaskId") + " source=" + source);
    }

    private void evaluateNativeLandscapeLocked(Object display, String source) {
        if (sNativeLandscapeState != NativeLandscapePolicy.FULLSCREEN
                || display == null || display != sDisplayContent) return;
        try {
            Object top = XposedHelpers.callMethod(display, "topRunningActivity");
            if (top == null) return;
            boolean home = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    top, "isActivityTypeHome"));
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            int rotation = (Integer) XposedHelpers.callMethod(displayRotation, "getRotation");
            boolean landscape = rotation == 1 || rotation == 3;
            if (!NativeLandscapePolicy.needsResume(
                    sNativeLandscapeState, landscape, home)) return;
            sNativeLandscapeState = NativeLandscapePolicy.onResumeRequested(
                    sNativeLandscapeState);
            sendNativeLandscapeMode(false);
            log("restore workspace after native landscape: rotation=" + rotation
                    + " home=" + home + " source=" + source);
        } catch (Throwable error) {
            log("native landscape restore check failed: " + error);
        }
    }

    private void sendNativeLandscapeMode(boolean fullscreen) {
        try {
            Object rawContext = sSystemContext != null ? sSystemContext : sWmsContext;
            Context context = (Context) rawContext;
            if (context == null) throw new IllegalStateException("system context unavailable");
            final Intent intent = new Intent(MiStepHost.LANDSCAPE_MODE_ACTION)
                    .setPackage(SYSTEMUI).putExtra("fullscreen", fullscreen);
            final Handler bridge = sZoomBridgeHandler;
            final Runnable send = () -> {
                try {
                    context.sendBroadcast(intent,
                            android.Manifest.permission.WRITE_SECURE_SETTINGS);
                } catch (Throwable error) {
                    log("native landscape bridge send failed: " + error);
                }
            };
            // requestNativeLandscapeLocked runs under WindowManagerGlobalLock.
            // A synchronous broadcast can invert with ActivityManagerService and
            // deadlock system_server, so hand it to the existing private bridge thread.
            if (bridge != null) bridge.post(send);
            else new Thread(send, "mistep-landscape-bridge").start();
        } catch (Throwable error) {
            sNativeLandscapeState = NativeLandscapePolicy.INACTIVE;
            log("native landscape bridge failed: " + error);
        }
    }

    private boolean releaseTaskRotationLocked(Object activity, String reason) {
        if (!sTaskRotations.containsKey(activity)) return false;
        try {
            XposedHelpers.callMethod(activity, "finishFixedRotationTransform");
            sTaskRotations.remove(activity);
            if (sMiStepFixedRotationApp == activity) sMiStepFixedRotationApp = null;
            log("task rotation released: " + reason);
            return true;
        } catch (Throwable error) {
            log("fixed rotation release failed: " + reason + ": " + error);
            return false;
        }
    }

    /** Keep orientation/windowing facts with the task, rather than the current top activity. */
    private void recordTaskWindowState(Object activity, int requestedOrientation,
            int resolvedRotation, int taskRotation, int fallbackWindowingMode, String source) {
        try {
            Object task = XposedHelpers.callMethod(activity, "getTask");
            if (task == null) return;
            int taskId = (Integer) XposedHelpers.callMethod(activity, "getRootTaskId");
            int windowingMode = fallbackWindowingMode;
            try {
                windowingMode = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            } catch (Throwable ignored) { }
            int configurationOrientation = 0;
            try {
                Object config = XposedHelpers.callMethod(activity, "getConfiguration");
                configurationOrientation = XposedHelpers.getIntField(config, "orientation");
            } catch (Throwable ignored) { }
            TaskWindowState state = sTaskWindowStates.get(task);
            if (state == null) {
                state = new TaskWindowState(taskId);
                sTaskWindowStates.put(task, state);
            }
            state.update(-1, configurationOrientation, requestedOrientation,
                    resolvedRotation == -1 ? taskRotation : resolvedRotation,
                    windowingMode, windowingMode == 1, true, taskRotation);
            log("task window state task=" + taskId + " mode=" + windowingMode
                    + " configOrientation=" + configurationOrientation
                    + " request=" + requestedOrientation + " resolved=" + resolvedRotation
                    + " localRotation=" + taskRotation + " source=" + source);
        } catch (Throwable error) {
            log("task window state unavailable: " + error);
        }
    }

    private void captureTaskWindowState(Object display, Object activity, String source) {
        if (activity == null) return;
        try {
            int request = (Integer) XposedHelpers.callMethod(activity, "getOverrideOrientation");
            Object configuration = XposedHelpers.callMethod(activity, "getConfiguration");
            int orientation = XposedHelpers.getIntField(configuration, "orientation");
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            int physicalRotation = (Integer) XposedHelpers.callMethod(displayRotation, "getRotation");
            int rotation = FullscreenRotationPolicy.observedTaskRotation(
                    orientation, request, physicalRotation);
            recordTaskWindowState(activity, request, rotation, rotation, 1, source);
        } catch (Throwable error) {
            log("pre-hold task state unavailable: " + error);
        }
    }

    private void releaseAllTaskRotationsLocked(String reason) {
        boolean wasUpdating = sUpdatingTaskRotation;
        sUpdatingTaskRotation = true;
        try {
            for (Object activity : new java.util.ArrayList<>(sTaskRotations.keySet())) {
                releaseTaskRotationLocked(activity, reason);
            }
        } finally {
            sUpdatingTaskRotation = wasUpdating;
        }
    }

    private void refreshMiStepTaskRotationLocked(Object display) {
        Object top = XposedHelpers.callMethod(display, "topRunningActivity");
        for (Object activity : new java.util.ArrayList<>(sTaskRotations.keySet())) {
            if (activity != top) {
                applyMiStepFixedRotationLocked(display, activity, "side-rotation-policy");
            }
        }
        applyMiStepFixedRotationLocked(display, top, "system-rotation-policy");
    }

    private boolean isSideFixedTask(Object activity) {
        try {
            Object task = XposedHelpers.callMethod(activity, "getTask");
            return MiStepSideRoleChange.isSide(task);
        } catch (Throwable error) {
            log("fixed rotation side-state lookup failed: " + error);
            return false;
        }
    }

    private static boolean isMiStepSideTask(Object task) {
        try {
            return Integer.valueOf(6).equals(XposedHelpers.callMethod(
                            task, "getWindowingMode"))
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(
                            task, "isForceTranslucent"));
        } catch (Throwable error) {
            return false;
        }
    }

    private static final ThreadLocal<Boolean> sReadingWorkspaceFocus = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> sWorkspaceLauncherTaskQuery = new ThreadLocal<>();

    private void installWorkspaceRunningTasks(ClassLoader cl) {
        try {
            Class<?> running = XposedHelpers.findClass("com.android.server.wm.RunningTasks", cl);
            Class<?> taskClass = XposedHelpers.findClass("com.android.server.wm.Task", cl);
            // The ROM passes the original caller at argument 5, before clearing Binder identity.
            for (java.lang.reflect.Method method : running.getDeclaredMethods()) {
                if (!method.getName().equals("getTasks") || method.getParameterCount() != 7
                        || method.getParameterTypes()[5] != int.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        p.setObjectExtra("mistepPreviousTaskQuery", sWorkspaceLauncherTaskQuery.get());
                        boolean launcher = false;
                        try {
                            Context context = (Context) sSystemContext;
                            String[] packages = context == null ? null : context.getPackageManager()
                                    .getPackagesForUid((Integer) p.args[5]);
                            launcher = packages != null
                                    && java.util.Arrays.asList(packages).contains("com.miui.home");
                        } catch (Throwable unavailable) { /* Preserve native queries on lookup failure. */ }
                        sWorkspaceLauncherTaskQuery.set(sZoomed && launcher);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Boolean previous = (Boolean) p.getObjectExtra("mistepPreviousTaskQuery");
                        if (previous == null) sWorkspaceLauncherTaskQuery.remove();
                        else sWorkspaceLauncherTaskQuery.set(previous);
                    }
                });
            }
            XposedHelpers.findAndHookMethod(running, "accept", taskClass, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!sZoomed || !Boolean.TRUE.equals(sWorkspaceLauncherTaskQuery.get())) return;
                    try {
                        Object task = p.args[0];
                        if (XposedHelpers.callMethod(task, "getDisplayContent") != sDisplayContent) return;
                        Object lease = MiStepDesktopLaunchBridge.recentsLease(task);
                        if (lease != null && MiStepDesktopLaunchBridge.recentsLeaseAlive(lease))
                            p.setResult(null);
                    } catch (Throwable unavailable) { /* An uncertain task remains a native candidate. */ }
                }
            });
            log("workspace Launcher running-task scan skips exact side leases before maxNum");
        } catch (Throwable unavailable) { log("workspace running-task hook unavailable: " + unavailable); }
    }

    private void installWorkspaceFocus(ClassLoader cl) {
        try {
            Class<?> area = XposedHelpers.findClass("com.android.server.wm.TaskDisplayArea", cl);
            XC_MethodHook selection = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!sZoomed || p.hasThrowable() || p.getResult() == null
                            || Boolean.TRUE.equals(sReadingWorkspaceFocus.get())) return;
                    sReadingWorkspaceFocus.set(Boolean.TRUE);
                    try {
                        Object original = p.getResult();
                        Object lease = MiStepDesktopLaunchBridge.recentsLease(original);
                        if (lease == null || !MiStepDesktopLaunchBridge.recentsLeaseAlive(lease)
                                || XposedHelpers.callMethod(original, "getDisplayContent") != sDisplayContent) return;
                        Object ignored = p.args.length >= 2 && Boolean.TRUE.equals(p.args[1]) ? p.args[0] : null;
                        boolean excludeMode = p.args.length == 4 && Boolean.TRUE.equals(p.args[2]);
                        int mode = excludeMode ? (Integer) p.args[3] : 0;
                        Object target = WorkspaceFocus.next(p.thisObject, ignored, excludeMode, mode, task -> {
                            Object binding = MiStepDesktopLaunchBridge.recentsLease(task);
                            return binding != null && MiStepDesktopLaunchBridge.recentsLeaseAlive(binding);
                        });
                        // No replacement means WM keeps its native fallback; never return a hidden task.
                        if (target != null) p.setResult(target);
                    } catch (Throwable unavailable) {
                        log("workspace focus selection unavailable: " + unavailable);
                    } finally { sReadingWorkspaceFocus.remove(); }
                }
            };
            XposedBridge.hookAllMethods(area, "getFocusedRootTask", selection);
            XposedBridge.hookAllMethods(area, "getNextFocusableRootTask", selection);
            log("workspace native focus selection skips exact side-task leases");
        } catch (Throwable unavailable) { log("workspace focus hook unavailable: " + unavailable); }
    }

    private void installNativeFreeformCoexistence(ClassLoader cl) {
        installNativeFreeformTouchExclusion(cl);
        installNativeFreeformPin(cl);
        try {
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedBridge.hookAllMethods(display, "assignChildLayers", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject != sDisplayContent || p.args.length != 1
                            || (!sZoomed && sNativeFreeformHostLayers.isEmpty())) return;
                    try { placeHostBelowNativeFreeforms(p.thisObject, p.args[0]); }
                    catch (Throwable error) { log("native freeform layering skipped: " + error); }
                }
            });
            log("native freeform inherits main zoom and layers above MiStepHost");
        } catch (Throwable error) { log("native freeform coexistence hook unavailable: " + error); }
    }

    private static Object sNativeFreeformLayerAnchor;
    private static Object sNativeFreeformPinManager;
    private static final java.util.Map<Object, NativeHostLayer> sNativeFreeformHostLayers =
            new java.util.IdentityHashMap<>();
    private static final ThreadLocal<Object> sNativeTouchExclusionDisplay = new ThreadLocal<>();
    private static Object sNativeTouchExclusionManager;

    private static void installNativeFreeformPin(ClassLoader cl) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.android.server.wm.MiuiFreeFormManagerService", cl);
            try {
                sNativeFreeformPinManager = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                        "com.android.server.wm.MiuiFreeFormManagerServiceStub", cl), "getInstance");
            } catch (Throwable notCreatedYet) { /* The first pin decision supplies the live instance. */ }
            XposedHelpers.findAndHookMethod(manager, "shouldForegroundPin", int.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    sNativeFreeformPinManager = p.thisObject;
                    if (Boolean.TRUE.equals(p.getResult()))
                        releaseNativeForegroundPinLayers(p.thisObject, (Integer) p.args[0]);
                }
            });
            log("native foreground pin releases host layers before Shell hides task");
        } catch (Throwable error) { log("native foreground pin hook unavailable: " + error); }
    }

    private static boolean isNativeForegroundPin(Object task) {
        Object manager = sNativeFreeformPinManager != null
                ? sNativeFreeformPinManager : sNativeTouchExclusionManager;
        return manager != null && Boolean.TRUE.equals(XposedHelpers.callMethod(manager,
                "getMiuiFreeformIsForegroundPin", XposedHelpers.getIntField(task, "mTaskId")));
    }

    private static void releaseNativeForegroundPinLayers(Object manager, int taskId) {
        sNativeFreeformPinManager = manager;
        ZoomTarget target = sZoomTarget;
        if (!sZoomed || target == null) return;
        synchronized (target.globalLock) {
            if (!sZoomed || target != sZoomTarget || sNativeFreeformHostLayers.isEmpty()) return;
            try {
                // The ROM has just stored mIsForegroundPin, but pin2/3 need not
                // assign child layers. Clear BOTH live and pending dependencies
                // synchronously, before the Binder reply permits Shell hideTask.
                Object transaction = Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor().newInstance();
                try {
                    placeHostBelowNativeFreeforms(target.display, transaction);
                    XposedHelpers.callMethod(transaction, "apply");
                } finally { XposedHelpers.callMethod(transaction, "close"); }
                log("native foreground pin layers reconciled task=" + taskId);
            } catch (Throwable error) { log("native foreground pin layer release failed: " + error); }
        }
    }

    private static void installNativeFreeformTouchExclusion(ClassLoader cl) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.android.server.wm.MiuiFreeFormManagerService", cl);
            Class<?> display = XposedHelpers.findClass("com.android.server.wm.DisplayContent", cl);
            XposedHelpers.findAndHookMethod(manager, "updateMiuiFreeformTouchExcludeRegion", display,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    sNativeTouchExclusionManager = p.thisObject;
                    p.setObjectExtra("mistepPreviousExcludeDisplay", sNativeTouchExclusionDisplay.get());
                    sNativeTouchExclusionDisplay.set(p.args[0]);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object previous = p.getObjectExtra("mistepPreviousExcludeDisplay");
                    if (previous == null) sNativeTouchExclusionDisplay.remove();
                    else sNativeTouchExclusionDisplay.set(previous);
                }
            });
            Class<?> window = XposedHelpers.findClass("com.android.server.wm.WindowState", cl);
            XposedHelpers.findAndHookMethod(window, "getTouchableRegion", android.graphics.Region.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object currentDisplay = sNativeTouchExclusionDisplay.get();
                    if (currentDisplay == null || p.hasThrowable()) return;
                    ZoomTarget target = sZoomTarget;
                    if (target == null || !Thread.holdsLock(target.globalLock)) return;
                    NativeHostLayer state = sNativeFreeformHostLayers.get(p.thisObject);
                    try {
                        boolean sameSurfaceAndAnchor = state != null
                                && state.nativeAnchor != null
                                && state.nativeAnchor == sNativeFreeformLayerAnchor
                                && state.surface == XposedHelpers.callMethod(p.thisObject, "getSurfaceControl");
                        if (NativeFreeformPolicy.omitFromTouchExclusion(sZoomed,
                                currentDisplay == target.display
                                && currentDisplay == XposedHelpers.callMethod(p.thisObject, "getDisplayContent"),
                                state != null, sameSurfaceAndAnchor)) {
                            // Only OEM's exclusion-union scratch Region changes. Its ordinary
                            // InputDispatcher region and all other windows retain native behavior.
                            ((android.graphics.Region) p.args[0]).setEmpty();
                        }
                    } catch (Throwable error) { log("native touch exclusion skipped: " + error); }
                }
            });
            log("native touch exclusion follows scoped MiStep relative layers");
        } catch (Throwable error) { log("native touch exclusion hook unavailable: " + error); }
    }

    private static final class NativeHostLayer {
        final Object surface;
        int normalLayer;
        Object normalRelativeTo;
        Object nativeAnchor;
        int nativeLayer;

        NativeHostLayer(Object surface) { this.surface = surface; }
    }

    /** Amend WM's own layer transaction; never reparent or transform an OEM task. */
    private static void placeHostBelowNativeFreeforms(Object display, Object transaction) {
        boolean touchExclusionChanged = false;
        java.util.List<Object> hosts = new java.util.ArrayList<>();
        Object[] lowestFreeform = {null};
        Consumer<Object> pickHost = window -> {
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                    XposedHelpers.getObjectField(window, "mAttrs");
            String title = String.valueOf(attrs.getTitle());
            boolean hostOverlay = ("MiStepHost".equals(title)
                    || "MiStepTransition".equals(title) || "MiStepMirrorLayer".equals(title)
                    || "MiStepClipboard".equals(title))
                    && attrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            // This status bar has been magnified into the main region. Keep it
            // with main chrome, not above an OEM freeform crossing that region.
            boolean mainStatusBar = sZoomed && attrs.type == 2000 && "StatusBar".equals(title);
            if (SYSTEMUI.equals(attrs.packageName) && (hostOverlay || mainStatusBar)
                    && Boolean.TRUE.equals(XposedHelpers.callMethod(window, "isVisible"))) hosts.add(window);
        };
        XposedHelpers.callMethod(display, "forAllWindows", pickHost, false);
        Consumer<Object> pickFreeform = task -> {
            if (!sZoomed || lowestFreeform[0] != null || (!Integer.valueOf(5).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))
                    && !NativeFreeformHandoff.keepNativeTransform(task, false))
                    || XposedHelpers.callMethod(task, "getRootTask") != task
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisible"))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisibleRequested"))) return;
            // Foreground pin retains WM visibility while Shell hides its canonical
            // surface. Relative children would otherwise disappear with that task.
            if (isNativeForegroundPin(task)) return;
            Object surface = XposedHelpers.callMethod(task, "getSurfaceControl");
            if (surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
                lowestFreeform[0] = surface;
        };
        XposedHelpers.callMethod(display, "forAllTasks", pickFreeform, false);
        if (sNativeFreeformLayerAnchor != lowestFreeform[0]) {
            // A newly excluded leaf does not undo a spec already on its shared ancestor.
            // Re-split that ancestor in this same WM transaction before showing freeform.
            if (sZoomed) XposedHelpers.callMethod(display, "reapplyMagnificationSpec", transaction);
            sNativeFreeformLayerAnchor = lowestFreeform[0];
            log("native freeform anchor changed present=" + (lowestFreeform[0] != null)
                    + " hostWindows=" + hosts.size());
            ZoomTarget target = sZoomTarget;
            if (target != null && target.display == display) target.handler.post(() -> {
                synchronized (target.globalLock) {
                    if (target == sZoomTarget)
                        applyGestureSurfacesLocked(target, sZoomScale, sZoomOffset, sZoomOffsetX);
                }
            });
        }

        java.util.Iterator<java.util.Map.Entry<Object, NativeHostLayer>> entries =
                sNativeFreeformHostLayers.entrySet().iterator();
        while (entries.hasNext()) {
            java.util.Map.Entry<Object, NativeHostLayer> entry = entries.next();
            Object host = entry.getKey();
            NativeHostLayer state = entry.getValue();
            if (XposedHelpers.callMethod(host, "getSurfaceControl") != state.surface
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(state.surface, "isValid"))) {
                entries.remove();
                touchExclusionChanged = true;
                continue;
            }
            if (lowestFreeform[0] != null && hosts.contains(host)) continue;
            rememberNativeHostNormalLayer(host, state);
            restoreNativeHostLayer(host, state, transaction);
            Object pending = XposedHelpers.callMethod(display, "getPendingTransaction");
            if (pending != transaction) restoreNativeHostLayer(host, state, pending);
            entries.remove();
            touchExclusionChanged = true;
        }
        if (lowestFreeform[0] != null) {
            for (Object host : hosts) {
                Object surface = XposedHelpers.callMethod(host, "getSurfaceControl");
                if (surface == null || !Boolean.TRUE.equals(
                        XposedHelpers.callMethod(surface, "isValid"))) continue;
                NativeHostLayer state = sNativeFreeformHostLayers.get(host);
                if (state == null || state.surface != surface) {
                    state = new NativeHostLayer(surface);
                    sNativeFreeformHostLayers.put(host, state);
                }
                rememberNativeHostNormalLayer(host, state);
                WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                        XposedHelpers.getObjectField(host, "mAttrs");
                int layer = attrs.type == 2000 ? -5
                        : "MiStepClipboard".contentEquals(attrs.getTitle()) ? -1
                        : "MiStepTransition".contentEquals(attrs.getTitle()) ? -2
                        : "MiStepMirrorLayer".contentEquals(attrs.getTitle()) ? -3 : -4;
                if (state.nativeAnchor != lowestFreeform[0]) touchExclusionChanged = true;
                XposedHelpers.callMethod(host, "assignRelativeLayer", transaction,
                        lowestFreeform[0], layer, true);
                state.nativeAnchor = lowestFreeform[0];
                state.nativeLayer = layer;
            }
        }
        if (touchExclusionChanged && sNativeTouchExclusionManager != null) {
            try {
                XposedHelpers.callMethod(sNativeTouchExclusionManager,
                        "updateMiuiFreeformTouchExcludeRegion", display);
            } catch (Throwable error) { log("native touch exclusion refresh skipped: " + error); }
        }
    }

    private static void rememberNativeHostNormalLayer(Object host, NativeHostLayer state) {
        int currentLayer = XposedHelpers.getIntField(host, "mLastLayer");
        Object currentRelative = XposedHelpers.getObjectField(host, "mLastRelativeToLayer");
        if (state.nativeAnchor == null || currentRelative != state.nativeAnchor
                || currentLayer != state.nativeLayer) {
            state.normalLayer = currentLayer;
            state.normalRelativeTo = currentRelative;
        }
    }

    private static void restoreNativeHostLayer(Object host, NativeHostLayer state, Object transaction) {
        // assignLayer may return early while WM builds transition layers. Use its
        // SurfaceAnimator setter for our own window, preserving any animation leash.
        // Never change the OEM task's parent, layer, visibility or transform.
        if (state.normalRelativeTo == null) {
            XposedHelpers.callMethod(host, "setLayer", transaction, state.normalLayer);
        } else {
            XposedHelpers.callMethod(host, "setRelativeLayer", transaction,
                    state.normalRelativeTo, state.normalLayer);
        }
        XposedHelpers.setIntField(host, "mLastLayer", state.normalLayer);
        XposedHelpers.setObjectField(host, "mLastRelativeToLayer", state.normalRelativeTo);
    }

    private void installSideSystemBarPolicy(ClassLoader cl) {
        try {
            Class<?> windowState = XposedHelpers.findClass(
                    "com.android.server.wm.WindowState", cl);
            XposedBridge.hookAllMethods(windowState, "getSystemUiVisibility",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!sFullscreenRotationActive || !(p.getResult() instanceof Integer)) return;
                    try {
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        if (!isMiStepSideTask(task)) return;
                        int original = (Integer) p.getResult();
                        int visible = SystemBarVisibilityPolicy.forSideTask(original);
                        if (visible != original) p.setResult(visible);
                    } catch (Throwable error) {
                        log("side task system-bar policy skipped: " + error);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(windowState, "canAffectSystemUiFlags", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!sZoomed || !Boolean.TRUE.equals(p.getResult())) return;
                    try {
                        if (XposedHelpers.callMethod(p.thisObject, "getDisplayContent") != sDisplayContent) return;
                        Object task = XposedHelpers.callMethod(p.thisObject, "getTask");
                        Object lease = MiStepDesktopLaunchBridge.recentsLease(task);
                        if (lease != null && MiStepDesktopLaunchBridge.recentsLeaseAlive(lease))
                            p.setResult(Boolean.FALSE);
                    } catch (Throwable stale) { /* Preserve the native decision for unrelated windows. */ }
                }
            });
            log("side task fullscreen system-ui flags isolated from display bars");
        } catch (Throwable error) {
            log("side task system-bar policy unavailable: " + error);
        }
    }

    private void installDesktopIconLaunchBridge(ClassLoader cl) {
        try {
            Class<?> atms = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerService", cl);
            int hooked = 0;
            for (java.lang.reflect.Method method : atms.getDeclaredMethods()) {
                String name = method.getName();
                final boolean fromRecents = name.equals("startActivityFromRecents");
                if (!(fromRecents || name.equals("startActivity") || name.equals("startActivityAsUser")
                        || name.equals("startActivityAsUserWithFeature"))) continue;
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        boolean handled = fromRecents
                                ? MiStepDesktopLaunchBridge.beforeRecents((Context) sSystemContext, p.thisObject, p.args)
                                : MiStepDesktopLaunchBridge.before((Context) sSystemContext, p.thisObject, p.args);
                        if (handled) {
                            p.setObjectExtra("mistepHostLaunch", true);
                            p.setResult(2); // ActivityManager.START_TASK_TO_FRONT
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (fromRecents || Boolean.TRUE.equals(p.getObjectExtra("mistepHostLaunch"))) return;
                        if (p.getThrowable() != null) return;
                        Object result = p.getResult();
                        if (result instanceof Integer && (Integer) result < 0) return;
                        forwardDesktopIconLaunch(p.args);
                    }
                });
                hooked++;
            }
            log("desktop icon launch bridge hooked ATMS methods=" + hooked);
        } catch (Throwable error) {
            log("desktop icon launch bridge unavailable: " + error);
        }
    }

    private static volatile String sLastDesktopLaunchPackage;
    private static volatile long sLastDesktopLaunchAt;

    private static void forwardDesktopIconLaunch(Object[] args) {
        if (args == null) return;
        String callerPackage = null;
        android.content.Intent launch = null;
        for (Object arg : args) {
            if (arg instanceof String && "com.miui.home".equals(arg)) {
                callerPackage = (String) arg;
            } else if (arg instanceof android.content.Intent) {
                launch = (android.content.Intent) arg;
            }
        }
        String pkg = DesktopIconLaunchPolicy.packageForLauncherEntry(
                callerPackage,
                launch == null ? null : launch.getAction(),
                launch != null && launch.hasCategory(android.content.Intent.CATEGORY_LAUNCHER),
                launch == null || launch.getComponent() == null
                        ? null : launch.getComponent().getPackageName());
        if (pkg == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (HostEntry.class) {
            if (pkg.equals(sLastDesktopLaunchPackage) && now - sLastDesktopLaunchAt < 350L) return;
            sLastDesktopLaunchPackage = pkg;
            sLastDesktopLaunchAt = now;
        }
        try {
            Context context = (Context) sSystemContext;
            if (context == null) return;
            android.content.Intent signal = new android.content.Intent(DESKTOP_ICON_LAUNCH_ACTION)
                    .setPackage(SYSTEMUI)
                    .putExtra("package", pkg);
            context.sendBroadcast(signal);
            log("desktop icon launch forwarded package=" + pkg);
        } catch (Throwable error) {
            log("desktop icon launch forward failed: " + error);
        }
    }

    private boolean isSystemRotationLocked(Object display) {
        try {
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            try {
                // The target's native getter reads ACCELEROMETER_ROTATION for USER_CURRENT,
                // including the active HyperOS profile/display policy.
                return Boolean.TRUE.equals(XposedHelpers.callMethod(
                        displayRotation, "isRotationFrozen"));
            } catch (Throwable ignored) { }
            try {
                return Integer.valueOf(1).equals(XposedHelpers.callMethod(
                        displayRotation, "getUserRotationMode"));
            } catch (Throwable ignored) { }
        } catch (Throwable error) {
            log("fixed rotation mode lookup unavailable: " + error);
        }
        try {
            Context context = (Context) sSystemContext;
            if (context != null) {
                return Integer.valueOf(0).equals(XposedHelpers.callStaticMethod(
                        android.provider.Settings.System.class, "getIntForUser",
                        context.getContentResolver(),
                        android.provider.Settings.System.ACCELEROMETER_ROTATION, 0, -2));
            }
        } catch (Throwable error) {
            log("fixed rotation setting lookup unavailable: " + error);
        }
        // Do not turn an unavailable policy query into permission to use a sensor.
        return true;
    }

    private void beginFixedMainRotationLocked(Object display) {
        if (sFullscreenRotationActive || display == null) return;
        sTaskWindowStates.clear();
        // Capture the application's real direction before the workspace's portrait
        // configuration can overwrite it, including manual fullscreen video under lock.
        captureTaskWindowState(display, XposedHelpers.callMethod(display, "topRunningActivity"),
                "before-workspace-portrait");
        releaseAllTaskRotationsLocked("new Mi Step session");
        sHeldDisplayRotation = FullscreenRotationPolicy.portraitDisplayRotation();
        sFullscreenRotationActive = true;
        log("fixed rotation active; Mi Step display held at portrait rotation "
                + sHeldDisplayRotation);
        recomputeDisplayOrientationLocked(display, "enter Mi Step");
    }

    private void endFixedMainRotationLocked(Object display) {
        sMainImeProcessConfiguration.clear(HostEntry::log);
        sMainSystemUiRotation.clear(HostEntry::log);
        publishMainUiConfiguration(null);
        if (!sFullscreenRotationActive) {
            sMainStatusBarClip.clear(HostEntry::log);
            releaseAllTaskRotationsLocked("inactive cleanup");
            return;
        }
        releaseAllTaskRotationsLocked("host exit");
        sFullscreenRotationActive = false;
        sMainStatusBarClip.clear(HostEntry::log);
        recomputeDisplayOrientationLocked(display, "exit Mi Step");
    }

    private void recomputeDisplayOrientationLocked(Object display, String reason) {
        try {
            boolean changed = Boolean.TRUE.equals(XposedHelpers.callMethod(
                    display, "updateOrientation"));
            Object displayRotation = XposedHelpers.getObjectField(display, "mDisplayRotation");
            // updateOrientation alone can early-return when the top app has not changed.
            // Re-run WM's regular rotation pipeline, including when entering from landscape.
            changed |= Boolean.TRUE.equals(XposedHelpers.callMethod(
                    displayRotation, "updateRotationUnchecked", true));
            if (changed) {
                XposedHelpers.callMethod(display, "sendNewConfiguration");
            }
        } catch (Throwable error) {
            log("fixed rotation: orientation refresh unavailable on " + reason + ": " + error);
        }
    }

    private void applyDisplayZoomLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX) throws Throwable {
        applyDisplayZoomLocked(target, scale, offsetY, offsetX, null);
    }

    private void applyDisplayZoomLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX, ZoomCommit commit) throws Throwable {
        if (Looper.myLooper() != target.handler.getLooper() || !Thread.holdsLock(target.globalLock)) {
            throw new IllegalStateException("zoom requires the WMS handler and its global lock");
        }
        if (sZoomTarget != target || sDisplayContent != target.display) return;
        Object dc = target.display;
        Class<?> specCls = XposedHelpers.findClass("android.view.MagnificationSpec",
                dc.getClass().getClassLoader());
        // The target's no-arg constructor is the already verified path; obtain() is absent.
        java.lang.reflect.Constructor<?> ctor = specCls.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object spec = ctor.newInstance();
        XposedHelpers.setFloatField(spec, "scale", scale);
        XposedHelpers.setFloatField(spec, "offsetX", offsetX);
        XposedHelpers.setFloatField(spec, "offsetY", offsetY);
        boolean previous = sZoomed;
        boolean rotationWasActive = sFullscreenRotationActive;
        int previousLandscapeState = sNativeLandscapeState;
        sNativeLandscapeState = NativeLandscapePolicy.onZoom(
                sNativeLandscapeState, scale);
        if (scale != 1f) beginFixedMainRotationLocked(dc);
        else endFixedMainRotationLocked(dc);
        // shouldMagnify is consulted synchronously while the new spec traverses children.
        sZoomed = scale != 1f;
        try {
            if (commit != null) {
                Object pending = XposedHelpers.callMethod(dc, "getPendingTransaction");
                commit.transactionId = ((Number) XposedHelpers.callMethod(pending, "getId")).longValue();
                if (commit.transactionId == 0L) throw new IllegalStateException("missing WMS transaction id");
                Class<?> callbackType = XposedHelpers.findClass(
                        "android.view.SurfaceControl$TransactionCommittedListener", dc.getClass().getClassLoader());
                Object callback = java.lang.reflect.Proxy.newProxyInstance(callbackType.getClassLoader(),
                        new Class<?>[]{callbackType}, (proxy, method, args) -> {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                            if ("toString".equals(method.getName())) return "MiStepZoomCommit";
                            if ("onTransactionCommitted".equals(method.getName())) {
                                boolean exact;
                                try {
                                    synchronized (target.globalLock) {
                                        Object actual = XposedHelpers.getObjectField(dc, "mMagnificationSpec");
                                        commit.actualScale = actual == null ? 1f : XposedHelpers.getFloatField(actual, "scale");
                                        commit.actualX = actual == null ? 0f : XposedHelpers.getFloatField(actual, "offsetX");
                                        commit.actualY = actual == null ? 0f : XposedHelpers.getFloatField(actual, "offsetY");
                                        exact = commit.applied && target == sZoomTarget
                                                && sLiveZoomCommit == commit
                                                && commit.zoom.sameTuple(commit.actualScale, commit.actualX, commit.actualY)
                                                && commit.zoom.sameTuple(sZoomScale, sZoomOffsetX, sZoomOffset);
                                    }
                                    commit.finish(exact, exact ? null : "zoom changed before commit acknowledgement");
                                } catch (Throwable failure) {
                                    commit.finish(false, "WMS applied-spec read failed");
                                }
                            }
                            return null;
                        });
                java.util.concurrent.Executor executor = runnable -> target.handler.post(runnable);
                XposedHelpers.callMethod(pending, "addTransactionCommittedListener", executor, callback);
            }
            XposedHelpers.callMethod(dc, "applyMagnificationSpec", spec);
        } catch (Throwable error) {
            sZoomed = previous;
            sNativeLandscapeState = previousLandscapeState;
            if (!rotationWasActive) endFixedMainRotationLocked(dc);
            throw error;
        }
        sZoomScale = scale;
        sZoomOffset = offsetY;
        sZoomOffsetX = offsetX;
        if (previous != sZoomed) MiStepWallpaperScale.refresh(dc);
        if (commit != null) commit.applied = true;
        // An already visible OEM freeform need not trigger assignChildLayers
        // when only magnification changes. Reconcile at the zoom boundary too,
        // in the same WM transaction, so host input/chrome cannot cover it.
        if (previous != sZoomed || scale == 1f) {
            placeHostBelowNativeFreeforms(dc,
                    XposedHelpers.callMethod(dc, "getPendingTransaction"));
        }
        if (scale == 1f) {
            restoreExitedMagnificationLocked(target);
        }
        MiStepRecentsInput.geometryChanged(XposedHelpers.callMethod(dc, "getPendingTransaction"),
                scale, offsetY, offsetX);
        Rect rawDisplayBounds = (Rect) XposedHelpers.callMethod(dc, "getBounds");
        int rawDisplayRotation = (Integer) XposedHelpers.callMethod(dc, "getRotation");
        NativeRawInputBridge.update(scale, offsetY, offsetX,
                rawDisplayBounds.width(), rawDisplayBounds.height(), rawDisplayRotation);
        applyGestureSurfacesLocked(target, scale, offsetY, offsetX);

        // 清掉历史版本遗留的固定 1080x2460 显示裁剪（R2 时代的调试代码）：在 17PM 上会把
        // 窗口层裁成 1080x2460，右侧 120px、底部 148px 变成黑边。每次应用 spec 都显式清空，
        // 能自愈旧版本残留；不再主动设置任何 crop。
        Object crop = null;
        try {
            Object layer = XposedHelpers.callMethod(dc, "getWindowingLayer");
            if (layer != null && Boolean.TRUE.equals(XposedHelpers.callMethod(layer, "isValid"))) {
                crop = Class.forName("android.view.SurfaceControl$Transaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(crop, "setWindowCrop", layer, (Object) null);
                XposedHelpers.callMethod(crop, "apply");
            }
        } catch (Throwable failure) {
            log("display crop clear unavailable: " + failure);
        } finally {
            if (crop != null) {
                try { XposedHelpers.callMethod(crop, "close"); } catch (Throwable ignored) {}
            }
        }
        // supplies the same matrix and inverse input mapping as the main app windows.
        if (!sZoomFrameApply) {
            log("WM zoom applied scale=" + scale + " offsetY=" + offsetY
                    + " offsetX=" + offsetX
                    + " thread=" + Thread.currentThread().getName() + " globalLock=true");
        }
    }

    /**
     * 整屏缩放动画：从当前生效 spec 插值到目标 spec，每帧仍在 WMS.mH 上、
     * 持真实 mGlobalLock 应用（与静态缩放同一路径）。R2 的 ZoomAnimController
     * 也用 ValueAnimator + PathInterpolator(0.34,0.69,0.1,1.0) 在 WMS 动画线程上
     * 逐帧写 applyZoomedMatrixSmt；这里沿用同一曲线与时长语义。
     *
     * POC 边界：本批只验证“逐帧整屏缩放”在目标机是否平滑、无崩溃；尚未接入
     * 主副交换动画，也不与宿主桥接动画同步。
     */
    private void startZoomAnimation(ZoomTarget target, ZoomAnimCommand.Plan plan, String key) {
        if (target == null || plan == null || key == null) return;
        if (sZoomTarget != target) return;
        if (Looper.myLooper() != target.handler.getLooper()) {
            log("zoom animation rejected off the WM handler");
            return;
        }
        if (key.equals(sZoomAnimKey)) return; // 同一命令内容只播放一次
        cancelZoomAnimation(target);
        sZoomAnimKey = key;
        final float fromScale = sZoomScale;
        final float fromY = sZoomOffset;
        final float fromX = sZoomOffsetX;
        if (Math.abs(fromScale - plan.scale) < 0.0005f
                && Math.abs(fromY - plan.offsetY) < 0.5f
                && Math.abs(fromX - plan.offsetX) < 0.5f) {
            log("zoom animation skipped; already at target " + plan.describe());
            return;
        }
        // 动画会改变真实 state；让调度器丢弃旧的 lastAttempt，避免之后“恢复原值”被当成重复请求吞掉。
        target.dispatch.invalidateLastAttempt();
        if (zoomAnimInterpolator == null) {
            zoomAnimInterpolator = new PathInterpolator(0.34f, 0.69f, 0.1f, 1.0f);
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(plan.durationMs);
        animator.setInterpolator(zoomAnimInterpolator);
        animator.addUpdateListener(animation -> {
            if (sZoomAnimator != animator || sZoomTarget != target) return;
            float fraction = (Float) animation.getAnimatedValue();
            float[] state = ZoomAnimCommand.stateAt(fromScale, fromY, fromX, plan, fraction);
            sZoomFrameApply = true;
            try {
                synchronized (target.globalLock) {
                    if (sZoomTarget != target) return;
                    applyDisplayZoomLocked(target, state[0], state[1], state[2]);
                }
            } catch (Throwable error) {
                log("zoom animation frame stopped: " + error);
                cancelZoomAnimation(target);
            } finally {
                sZoomFrameApply = false;
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (sZoomAnimator != animator) return; // 已被更新的普通请求取消
                sZoomAnimator = null;
                sZoomFrameApply = false;
                try {
                    synchronized (target.globalLock) {
                        if (sZoomTarget == target) {
                            applyDisplayZoomLocked(target, plan.scale, plan.offsetY, plan.offsetX);
                        }
                    }
                } catch (Throwable error) {
                    log("zoom animation final apply failed: " + error);
                }
                log("zoom animation finished " + plan.describe());
            }
        });
        sZoomAnimator = animator;
        animator.start();
        log("zoom animation started from scale=" + fromScale + " y=" + fromY + " x=" + fromX
                + " -> " + plan.describe());
    }

    /** 只能在 WM handler 上取消；其它线程转发过去，不做 inline 调用。 */
    void cancelZoomAnimation(ZoomTarget target) {
        ValueAnimator animator = sZoomAnimator;
        if (animator == null) return;
        if (target != null && Looper.myLooper() != target.handler.getLooper()) {
            target.handler.post(() -> cancelZoomAnimation(target));
            return;
        }
        sZoomAnimator = null;
        sZoomFrameApply = false;
        try { animator.cancel(); } catch (Throwable ignored) {}
        log("zoom animation cancelled");
    }

    private static void applyGestureSurfacesLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX) {
        if (Looper.myLooper() != target.handler.getLooper() || !Thread.holdsLock(target.globalLock)) return;
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            int count = 0;
            synchronized (sGestureSurfaces) {
                java.util.Iterator<Object> it = sGestureSurfaces.keySet().iterator();
                while (it.hasNext()) {
                    Object surface = it.next();
                    if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) {
                        it.remove();
                        continue;
                    }
                    // Its InputWindowHandle uses replaceTouchableRegionWithCrop(null).
                    // Navigation and native freeform spies use the task's logical display space.
                    Rect bounds = (Rect) XposedHelpers.callMethod(target.display, "getBounds");
                    float monitorScale = NativeFreeformPolicy.gestureScale(
                            sNativeFreeformLayerAnchor != null, sGestureSurfaces.get(surface), scale);
                    float x = monitorScale == 1f ? 0f : offsetX;
                    float y = monitorScale == 1f ? 0f : offsetY;
                    Rect crop = monitorScale == 1f ? null : new Rect(0, 0, bounds.width(), bounds.height());
                    XposedHelpers.callMethod(tx, "setCrop", surface, crop);
                    XposedHelpers.callMethod(tx, "setMatrix", surface, monitorScale, 0f, 0f, monitorScale);
                    XposedHelpers.callMethod(tx, "setPosition", surface, x, y);
                    count++;
                }
            }
            if (count > 0) {
                XposedHelpers.callMethod(tx, "apply");
                if (!sZoomFrameApply) {
                    log("gesture geometry scale=" + scale + " offset=" + offsetX + "," + offsetY
                            + " monitors=" + count);
                }
            }
        } catch (Throwable error) { log("navigation input transform stopped: " + error); }
        finally { if (tx != null) try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {} }
    }

    private void hookApplication(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.android.systemui.SystemUIService", cl);
            XposedBridge.hookAllMethods(c, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;
                    if (self instanceof Context) {
                        sContext = (Context) self;
                        MiStepClipboard.start(sContext);
                        MiStepSystemActions.start(sContext, cl);
                        MiStepWarmup.start(sContext, cl);
                        log("captured Context -> " + sContext);
                        scheduleStart();
                    }
                }
            });
            log("hooked SystemUIService#onCreate");
        } catch (Throwable t) {
            log("could not hook SystemUIService: " + t);
        }
    }

    private void hookBackGestureDiagnostics(ClassLoader cl) {
        try {
            Class<?> receiver = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl$inputEventReceiver$1", cl);
            XposedBridge.hookAllMethods(receiver, "onInputEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 1 && p.args[0] instanceof android.view.MotionEvent)
                        logBackEvent("DisplayBackGestureHandler", (android.view.MotionEvent) p.args[0]);
                }
            });
            log("DisplayBackGestureHandler input diagnostics installed");
        } catch (Throwable error) {
            log("DisplayBackGestureHandler input diagnostics unavailable: " + error);
        }
        try {
            Class<?> panel = XposedHelpers.findClass(
                    "com.android.systemui.navigationbar.gestural.BackPanelController", cl);
            XposedBridge.hookAllMethods(panel, "onMotionEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 1 && p.args[0] instanceof android.view.MotionEvent)
                        logBackEvent("BackPanelController", (android.view.MotionEvent) p.args[0]);
                }
            });
            log("BackPanelController input diagnostics installed");
        } catch (Throwable error) {
            log("BackPanelController input diagnostics unavailable: " + error);
        }
    }

    /**
     * MIUI computes the app open/close animation rectangle before SurfaceControl playback.
     * Keep that temporary visual rectangle inside Mi Step's main pane; task bounds and the
     * normal full-screen launcher animation are left untouched.
     */
    private void hookTransitionBounds(ClassLoader cl) {
        // Native windows now share the main parent coordinate system.
        // Physical/main conversion and pre-return position suppression are unnecessary.
        try {
            Class<?> util = XposedHelpers.findClass(
                    "com.android.wm.shell.common.transition.MiuiTransitionHandlerUtil", cl);
            XposedBridge.hookAllMethods(util, "getAnimBounds", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    Rect main = MiStepHost.activeMainBounds();
                    if (!(result instanceof Rect) || main == null || param.args == null
                            || param.args.length < 2
                            || !MiStepHost.shouldClampAnimationBounds(
                                    param.args[0], param.args[1])) return;
                    Rect original = (Rect) result;
                    Rect clipped = TransitionBounds.clamp(original, main);
                    if (clipped == original || clipped.equals(original)) return;
                    param.setResult(clipped);
                    log("transition animation clipped " + original + " -> " + clipped
                            + " main=" + main);
                }
            });
            log("MIUI transition animation bounds clamped to active main pane");
        } catch (Throwable error) {
            log("transition animation clamp unavailable: " + error);
        }

    }


    private static void logBackEvent(String source, android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action != android.view.MotionEvent.ACTION_DOWN
                && action != android.view.MotionEvent.ACTION_UP
                && action != android.view.MotionEvent.ACTION_CANCEL) return;
        int display = -1;
        try { display = (Integer) XposedHelpers.callMethod(event, "getDisplayId"); }
        catch (Throwable ignored) {}
        log("back event source=" + source + " action=" + action + " display=" + display
                + " local=" + event.getX() + "," + event.getY()
                + " raw=" + event.getRawX() + "," + event.getRawY()
                + " hostZoom=" + sHostZoomed);
    }

    private void hookRecentsWallpaper(ClassLoader cl) {
        try {
            Class<?> util = XposedHelpers.findClass("com.android.wm.shell.shared.TransitionUtil", cl);
            XposedBridge.hookAllMethods(util, "newTarget", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length != 5 || p.getResult() == null
                            || !(p.args[4] instanceof java.util.Map)) return;
                    try {
                        if (MiStepRecentsWallpaper.stage(p.args[2], p.args[0],
                                XposedHelpers.getObjectField(p.getResult(), "leash"), p.args[3],
                                MiStepHost.activeMainTransform(), MiStepHost.activeMainBounds(),
                                (java.util.Map) p.args[4]))
                            log("Recents wallpaper capture fill staged behind workspace wallpaper");
                    } catch (Throwable error) {
                        log("Recents wallpaper capture unchanged: " + error);
                    }
                }
            });
        } catch (Throwable error) { log("Recents wallpaper hook unavailable: " + error); }
    }

    private void hookShellProviders(ClassLoader cl) {
        Class<?> module;
        try {
            module = XposedHelpers.findClass("com.android.wm.shell.dagger.WMShellBaseModule", cl);
        } catch (Throwable t) {
            log("WMShellBaseModule not found");
            return;
        }
        int hooked = 0;
        for (final Method m : module.getDeclaredMethods()) {
            if (!m.getReturnType().getName().contains("TaskView")
                    && !m.getName().startsWith("provideTaskView")) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        String n = m.getName();
                        if ("provideTaskViewTransitions".equals(n)) {
                            sTaskViewTransitions = r;
                        } else if ("provideTaskViewFactoryController".equals(n)) {
                            Object[] args = param.args;
                            if (args != null && args.length > 1 && args[1] instanceof Executor) {
                                sShellExecutor = (Executor) args[1];
                                log("captured ShellExecutor -> " + sShellExecutor);
                            }
                        } else if ("provideTaskViewFactory".equals(n) && r instanceof Optional) {
                            sTaskViewFactory = ((Optional<?>) r).orElse(null);
                            log("captured TaskViewFactory -> " + sTaskViewFactory);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                log("hook failed for " + m.getName() + ": " + t);
            }
        }
        log("provider hooks installed: " + hooked);
    }

    private void scheduleStart() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // 紧急停止阀：只要这个文件存在就什么都不做。
                if (new java.io.File("/data/system/mistep-halt").exists()) {
                    log("halt marker present -> skipping test");
                    return;
                }
                // 一次性自锁：本次安装只允许尝试启动一次。
                // 死在启动这Mi Step会让 SystemUI 崩溃重启，而重启后模块会再次运行；
                // 没有这道锁就会形成崩溃循环（LSPosed 安全模式是外部兜底，不该依赖）。
                // 锁文件放在 SystemUI 自己的数据目录，跨进程重启依然有效。
                log("capabilities: factory=" + sTaskViewFactory
                        + " executor=" + sShellExecutor + " transitions=" + sTaskViewTransitions);
                if (sTaskViewFactory == null || sShellExecutor == null || sTaskViewTransitions == null) {
                    log("missing capability, abort");
                    return;
                }
                // v1.2.1：不再用一次性自锁挡触发器（重启后"呼不出/退不出"的根源）。
                // 触发器本身是热区+广播，安全；真正危险的是早期自动启动应用，已删除。
                installTriggers();
            }
        }, LAUNCH_DELAY_MS);
    }

    /** v0.7.0：角部热区 + 广播触发；首次触发再启动宿主，之后切换显示。 */
    private void installTriggers() {
        final Runnable toggle = new Runnable() {
            @Override
            public void run() {
                if (!MiStepEntryGuard.canOpen(sContext)) return;
                try {
                    MiStepHost.toggleOrStart(sContext, sShellExecutor, sTaskViewFactory, sTaskViewTransitions);
                } catch (Throwable t) {
                    log("toggle FAILED: " + t);
                    XposedBridge.log(t);
                }
            }
        };
        HotZoneGesture.install(sContext, toggle);
        try {
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    String action = intent.getAction();
                    if ("com.jiusis.mistep.DEBUG".equals(action)) {
                        handleDebugBroadcast(intent);
                        return;
                    }
                    if (MiStepHost.LANDSCAPE_MODE_ACTION.equals(action)) {
                        MiStepHost.onLandscapeMode(intent.getBooleanExtra("fullscreen", false));
                        return;
                    }
                    log("broadcast " + action + " -> toggle");
                    toggle.run();
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction("com.jiusis.mistep.TOGGLE");
            filter.addAction("com.jiusis.mistep.DEBUG");
            filter.addAction(MiStepHost.LANDSCAPE_MODE_ACTION);
            sContext.registerReceiver(receiver, filter, android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    null, android.content.Context.RECEIVER_EXPORTED);
            log("broadcast trigger armed: TOGGLE / DEBUG");
        } catch (Throwable t) {
            log("broadcast trigger failed: " + t);
        }
        try {
            android.content.BroadcastReceiver screenReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context context, android.content.Intent intent) {
                    if (android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                        String reason = intent.getStringExtra("reason");
                        if (MiStepNavigationPolicy.shouldSuspend(reason))
                            MiStepHost.onSystemNavigation(reason);
                        else if ("gestureNav".equals(reason))
                            log("native gesture navigation leaves Mi Step active");
                        return;
                    }
                    if (android.content.Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                        log("user present -> clear keyguard privacy");
                        MiStepHost.onUserPresent();
                        return;
                    }
                    boolean on = android.content.Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                    if (!on || !MiStepEntryGuard.canOpen(sContext)) MiStepInputTrace.stop();
                    log("screen " + (on ? "ON" : "OFF"));
                    MiStepHost.onScreenState(on);
                }
            };
            android.content.IntentFilter screenFilter = new android.content.IntentFilter();
            screenFilter.addAction(android.content.Intent.ACTION_SCREEN_ON);
            screenFilter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            screenFilter.addAction(android.content.Intent.ACTION_USER_PRESENT);
            screenFilter.addAction(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            sContext.registerReceiver(screenReceiver, screenFilter,
                    android.content.Context.RECEIVER_EXPORTED);
            log("screen trigger armed");
        } catch (Throwable t) {
            log("screen trigger failed: " + t);
        }
    }

    /**
     * 无人值守调试广播（v0.8.0）：
     *   am broadcast -a com.jiusis.mistep.DEBUG --ei slot 1 --es pkg com.android.settings
     *   am broadcast -a com.jiusis.mistep.DEBUG --ei slot 1 --ez swap true
     * 先确保宿主已启动，再转给 MiStepHost。
     */
    private void handleDebugBroadcast(android.content.Intent intent) {
        if (intent.getBooleanExtra("stopInputTrace", false)) {
            MiStepInputTrace.stop();
            if (sContext != null) sContext.sendBroadcast(new Intent(MiStepInputTrace.ACTION)
                    .setPackage("android").putExtra("stopInputTrace", true));
            return;
        }
        if (!MiStepEntryGuard.canOpen(sContext)) return;
        if (intent.getBooleanExtra("traceInput", false)) {
            MiStepInputTrace.arm(sContext, false, sContext.getClassLoader());
            sContext.sendBroadcast(new Intent(MiStepInputTrace.ACTION).setPackage("android"));
            return;
        }
        if (intent.getBooleanExtra("traceNextSwap", false)) {
            MiStepHost.debugTraceNextSwap();
            return;
        }
        int slot = intent.getIntExtra("slot", -1);
        String pkg = intent.getStringExtra("pkg");
        boolean swap = intent.getBooleanExtra("swap", false);
        if (intent.getBooleanExtra("dumpViews", false)) {
            log("broadcast DEBUG dumpViews");
            MiStepHost.debugDumpViews();
            return;
        }
        if (intent.getBooleanExtra("roots", false)) {
            log("broadcast DEBUG roots");
            MiStepHost.debugRoots();
            return;
        }
        if (intent.getBooleanExtra("leashAnim", false)) {
            final int animSlot = intent.getIntExtra("slot", -1);
            final int duration = intent.getIntExtra("durationMs", 400);
            final String animPkg = intent.getStringExtra("pkg");
            log("broadcast DEBUG leashAnim slot=" + animSlot + " duration=" + duration
                    + " pkg=" + animPkg);
            if (sTaskViewFactory == null || sShellExecutor == null || sTaskViewTransitions == null) {
                log("broadcast DEBUG leashAnim: capabilities not ready, ignored");
                return;
            }
            MiStepHost.toggleOrStartIfNeeded(sContext, sShellExecutor, sTaskViewFactory,
                    sTaskViewTransitions);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> MiStepHost.debugLeashAnim(animSlot, duration, animPkg), 1500L);
            return;
        }
        if (intent.hasExtra("mirrorTask") || intent.getBooleanExtra("mirror", false)) {
            final int task = intent.getIntExtra("mirrorTask", -1);
            final int x = intent.getIntExtra("mirrorX", 0);
            final int y = intent.getIntExtra("mirrorY", 622);
            final int w = intent.getIntExtra("mirrorW", 400);
            final int h = intent.getIntExtra("mirrorH", 912);
            final int hold = intent.getIntExtra("mirrorHoldMs", 6000);
            final boolean hideSource = intent.getBooleanExtra("mirrorHideSource", false);
            final int hideModeExtra = intent.getIntExtra("mirrorHideMode", -1);
            final int hideMode = hideModeExtra >= 0 ? hideModeExtra : (hideSource ? 1 : 0);
            log("broadcast DEBUG mirror task=" + task + " rect=" + x + "," + y + "," + w + "x" + h
                    + " hold=" + hold + " hideMode=" + hideMode);
            if (sTaskViewFactory == null || sShellExecutor == null || sTaskViewTransitions == null) {
                log("broadcast DEBUG mirror: capabilities not ready, ignored");
                return;
            }
            MiStepHost.toggleOrStartIfNeeded(sContext, sShellExecutor, sTaskViewFactory,
                    sTaskViewTransitions);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> MiStepHost.debugMirror(task, x, y, w, h, hold, hideMode), 1500L);
            return;
        }
        log("broadcast DEBUG slot=" + slot + " pkg=" + pkg + " swap=" + swap);
        if (slot < 0) {
            return;
        }
        if (sTaskViewFactory == null || sShellExecutor == null || sTaskViewTransitions == null) {
            log("broadcast DEBUG: capabilities not ready, ignored");
            return;
        }
        MiStepHost.toggleOrStartIfNeeded(sContext, sShellExecutor, sTaskViewFactory, sTaskViewTransitions);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (swap) {
                    MiStepHost.debugSwap(slot);
                } else if (pkg != null) {
                    MiStepHost.debugLaunch(slot, pkg);
                }
            }
        }, 1500L);
    }

    /** 通过工厂创建一个 TaskView；回调在 Dagger/Shell 线程上，转交主线程做视图操作。 */
    private void createTaskView() throws Exception {
        Consumer<Object> onCreated = new Consumer<Object>() {
            @Override
            public void accept(Object view) {
                log("TaskView created -> " + view);
                sTaskView = view;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        attachAndLaunch();
                    }
                });
            }
        };
        XposedHelpers.callMethod(sTaskViewFactory, "create", sContext, sShellExecutor, onCreated);
        log("factory.create invoked");
    }

    /** 把 TaskView 放进我们自己的窗口，然后启动目标应用。 */
    private void attachAndLaunch() {
        try {
            if (sContext == null || sTaskView == null) {
                log("attachAndLaunch: missing context/taskview");
                return;
            }
            final View taskView = (View) sTaskView;

            // 监听 TaskView 生命周期：这些回调决定后续「格子绑定/交换」的状态机
            Object listener = makeListener(sShellExecutor, "com.android.wm.shell.taskview.TaskView$Listener");
            if (listener != null) {
                XposedHelpers.callMethod(taskView, "setListener", sShellExecutor, listener);
                log("setListener OK");
            }

            LinearLayout root = new LinearLayout(sContext);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(0xE6000000);
            root.setPadding(16, 16, 16, 16);

            TextView label = new TextView(sContext);
            label.setText("Mi Step宿主 v0.3 — 真实应用承载测试");
            label.setTextColor(Color.WHITE);
            label.setTextSize(15f);
            root.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            if (taskView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) taskView.getParent()).removeView(taskView);
            }
            root.addView(taskView, new LinearLayout.LayoutParams(BOX_W, BOX_H));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = BOX_X;
            lp.y = BOX_Y;
            lp.setTitle("MiStepHostBox");

            WindowManager wm = (WindowManager) sContext.getSystemService(Context.WINDOW_SERVICE);
            wm.addView(root, lp);
            sWindowRoot = root;
            log("window with TaskView added: " + root + " box=" + BOX_W + "x" + BOX_H + "@" + BOX_X + "," + BOX_Y);

            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    launchTargetApp(taskView);
                }
            }, AFTER_ADD_MS);
        } catch (Throwable t) {
            log("attachAndLaunch FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    /** 用公开的 TaskView#startActivity 启动目标应用；ActivityOptions 的隐藏 setter 走反射。 */
    /** 一次性自锁文件：写在 SystemUI 自己的数据目录，重启进程后仍存在。 */
    private java.io.File latchFile() {
        return new java.io.File(sContext.getFilesDir(), "mistep-launched");
    }

    private void armLatch() {
        try {
            java.io.File f = latchFile();
            if (!f.exists() && f.createNewFile()) {
                log("latch armed at " + f);
            }
        } catch (Throwable t) {
            log("could not arm latch: " + t);
        }
    }

    /** 为目标 Intent 造 PendingIntent 并调用 TaskView#startActivity。 */
    private void launchTargetApp(View taskView) {
        try {
            Intent launch = sContext.getPackageManager().getLaunchIntentForPackage(TARGET_PKG);
            if (launch == null) {
                log("no launch intent for " + TARGET_PKG);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Rect bounds = new Rect(0, 0, BOX_W, BOX_H);
            ActivityOptions options = ActivityOptions.makeBasic();
            callHidden(options, "setLaunchBounds", bounds);
            callHidden(options, "setLaunchCookie", new Binder());
            callHidden(options, "setLaunchWindowingMode", 6);          // WINDOWING_MODE_MULTI_WINDOW
            callHidden(options, "setRemoveWithTaskOrganizer", Boolean.TRUE);

            // pendingIntent 必须非空：TaskViewTransitions.startActivity 第一行就
            // pendingIntent.getIntent()，且真正的启动走
            // WindowContainerTransaction.sendPendingIntent(pendingIntent, intent, options)。
            // 传 null 会让 SystemUI 在 wmshell.main 上 NPE 崩溃（已实测）。
            PendingIntent pi = PendingIntent.getActivity(sContext, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE);

            // 同理不能用 XposedHelpers.callMethod：它靠实参类型匹配，遇到 null 实参会抛
            // "Attempt to get length of null array"。改用显式 Method.invoke。
            Method start = null;
            for (Class<?> c = taskView.getClass(); c != null && start == null; c = c.getSuperclass()) {
                try {
                    start = c.getDeclaredMethod("startActivity",
                            PendingIntent.class, Intent.class, ActivityOptions.class, Rect.class);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
            if (start == null) {
                log("startActivity(PendingIntent, Intent, ActivityOptions, Rect) not found");
                return;
            }
            // 先上锁再调用：万一这次调用崩掉 SystemUI，重启后的新实例会因锁存在而不再重试
            armLatch();
            start.setAccessible(true);
            start.invoke(taskView, pi, launch, options, bounds);
            log("startActivity invoked for " + TARGET_PKG + " pi=" + pi + " bounds=" + bounds);
        } catch (Throwable t) {
            log("launchTargetApp FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    /** 隐藏 API 的调用一律走反射，失败要能看见具体是哪一个。 */
    private static void callHidden(Object target, String name, Object... args) {
        try {
            Object r = XposedHelpers.callMethod(target, name, args);
            log("   hidden OK " + name + " -> " + r);
        } catch (Throwable t) {
            log("   hidden FAILED " + name + ": " + t);
        }
    }

    /** 用动态代理实现隐藏接口 TaskView.Listener，记录每个回调。 */
    private Object makeListener(Executor executor, String ifaceName) {
        try {
            Class<?> iface = XposedHelpers.findClass(ifaceName, sContext.getClassLoader());
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            StringBuilder sb = new StringBuilder("listener#").append(method.getName()).append('(');
                            if (args != null) {
                                for (int i = 0; i < args.length; i++) {
                                    sb.append(i > 0 ? ", " : "").append(args[i]);
                                }
                            }
                            log(sb.append(')').toString());
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) {
                                return Boolean.FALSE;
                            }
                            if (rt == int.class) {
                                return 0;
                            }
                            if (rt == long.class) {
                                return 0L;
                            }
                            return null;
                        }
                    });
        } catch (Throwable t) {
            log("makeListener failed: " + t);
            return null;
        }
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            // 至少保留 logcat
        }
    }

    // 保留：字段探查工具，后续看状态用
    @SuppressWarnings("unused")
    private static Object readField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return "unreadable: " + t;
        }
    }

    @SuppressWarnings("unused")
    private static String sig(Method m) {
        return Modifier.toString(m.getModifiers()) + ' ' + m.getReturnType().getSimpleName() + ' ' + m.getName();
    }

    @SuppressWarnings("unused")
    private static final Map<String, String> NOTES = new LinkedHashMap<>();
}




