package com.jianguo.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * v1.6.1：核对原生任务/表面绑定，确认脱离后清槽，退出恢复遗留副任务。
 * 宿主不参与显示缩放，保持正常物理坐标；主区画面与触摸均让给下层系统。
 *
 * 调试广播：com.jianguo.mistep.TOGGLE / com.jianguo.mistep.DEBUG(slot,pkg,swap)。
 */
public final class MiStepHost {
    static final String LANDSCAPE_MODE_ACTION = "com.jianguo.mistep.LANDSCAPE_MODE";

    private static final String DESKTOP_ICON_LAUNCH_ACTION =
            "com.jianguo.mistep.DESKTOP_ICON_LAUNCH";

    private static final String TAG = "MiStepProbe";
    private static final int SLOT_MAIN = 0;
    private static final int SLOT_COUNT = 1 + MiStepLayout.CELL_COUNT;
    private static final long LAUNCH_TIMEOUT_MS = 4000L;
    private static final long SIDE_PROMOTION_TIMEOUT_MS = 3500L;

    private static volatile MiStepHost sInstance;

    private final Context ctx;
    private final Executor executor;
    private final Object factory;
    private final Object transitions;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Slot[] slots = new Slot[SLOT_COUNT];

    private volatile MiStepLayout layout;
    private FrameLayout root;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private volatile int displayRotation = -1;
    private boolean geometryRefreshQueued;
    private int geometryRefreshDeferrals;
    private boolean geometryRefreshAfterLive;
    private int lpY = 0;
    private boolean zoomed;
    private String lastBroadcastZoom;
    private volatile int screenW = 1080;
    private volatile int screenH = 2460;
    private final Object taskUsersLock = new Object();
    private final Object taskWindowStatesLock = new Object();
    private final java.util.Map<Integer, TaskWindowState> taskWindowStates =
            new java.util.HashMap<>();
    private volatile java.util.Set<Integer> taskUserIds;
    private volatile long taskUserIdsReadAt;
    private volatile String taskUserIdsReadError;
    private volatile boolean screenOn = true;
    private Slot launchCurrentSlot;
    private Promotion promotion;
    private SwapPreview swapPreview;
    private Object swapPreviewStatusOwner;
    private final MiStepStatusBar statusBar;
    private volatile HomeTaskReturn coveredHome;
    private CellMovePreview cellMovePreview;
    private Bitmap mainBackdrop;
    private View topStripBacking;
    private Bitmap wallpaperBackdrop;
    private boolean wallpaperBackdropRequested;
    private BroadcastReceiver wallpaperReceiver;
    private BroadcastReceiver desktopIconReceiver;
    private BroadcastReceiver liveZoomReceiver;
    private LiveZoomRequest pendingLiveZoom;
    private boolean mainBackdropRequested;
    /** mirror 承载时主区显示的桌面/上一主区快照（避免隐藏任务后主区全黑）。 */
    private View carrierBackdrop;
    private int carrierSlotIndex = -1;
    private MiStepTopBar topBar;
    private View sideBacking;
    /** 侧栏按格分段的不透明底板；承载格需要把它藏起来让下面的真实任务画面透出来。 */
    private final View[] sideBackingViews = new View[SLOT_COUNT];
    private boolean mainOnRight;
    private View dragGhost;
    private boolean dragMoved;
    private float dragStartRawX;
    private float dragStartRawY;
    private Slot dragTarget;
    private View keyguardMask;
    private boolean keyguardLocked;
    private static final int ACTIVE = 0, SUSPENDING = 1, SUSPENDED = 2, RESUMING = 3;
    private volatile int scene = ACTIVE;
    private long sceneEpoch;
    private boolean suspendRequested;
    private boolean landscapeSuspended;
    private boolean transferBusy;
    private boolean overlayRefreshScheduled;
    private static boolean boundsHooksReady;
    private static boolean surfaceHooksReady;
    private Object surfaceObserver;
    private volatile boolean traceNextSwap;
    private volatile MiStepSwapTrace swapTrace;
    private boolean compactPending;
    private MiStepCellMotion cellMotion;
    private MiStepSideDismiss sideDismiss;
    private Slot sideDismissSlot;
    private static final class CellMovePlan {
        final Slot slot;
        final Rect from;
        CellMovePlan(Slot slot, Rect from) { this.slot = slot; this.from = from; }
    }
    private volatile boolean liveSwapInputBlocked;
    private MiStepTransitionInput transitionInput;
    private View transitionInputWindow;
    private final android.os.IBinder desktopSceneOwner = new android.os.Binder();
    private volatile long desktopRegistrationSequence;
    private final FinishTransactions<RenderBinding> finishTransactions = new FinishTransactions<>(16);
    private final FinishTransactions<Long> workspaceFinishes = new FinishTransactions<>(16);

    private final java.util.Map<Object, OwnedTask> ownedTasks = new java.util.HashMap<>();

    private static final class Slot implements SlotPositions.Entry {
        int index;
        View view;
        View hint;
        Object controller;
        int taskId = -1;
        Object taskToken;
        int[] rect;
        int windowMode = -1;
        TaskWindowState windowState;
        boolean occupied;
        boolean taskVisible;
        boolean reassertPending;
        long modeGuardUntil;
        int modeRepairAttempts;
        boolean modeAdmissionPending;
        final VisibilityRepair visibilityRepair = new VisibilityRepair();
        // v0.9 缩放渲染：任务按满屏排版，靠矩阵缩到格子里
        Object scaleSurface;
        Object cellBackground;
        Object cellBackgroundParent;
        int cellBackgroundWidth;
        int cellBackgroundHeight;
        boolean scaleOnLeash;
        int scaleTaskId = Integer.MIN_VALUE;
        float scaleApplied = -1f;
        int scaleAttempts;
        int taskUid;
        String pkg;
        // A tap swaps; an outward swipe releases this exact task to the background.
        MotionEvent bufferedDown;
        long touchTaskEpoch, touchSceneEpoch;
        boolean injecting;
        volatile boolean promoting;
        boolean launching;
        int retiredTaskId = -1;
        long taskEpoch;
        int removalCheckTask = -1;
        volatile boolean parked;
        volatile boolean frozen;
        volatile boolean closing;
        volatile boolean nativeReleasePending;
        // fullscreen-only 应用的 mirror 承载：任务仍是满屏前台，画面被移出屏幕，
        // 副本镜像显示在格子里；点击格子把原任务移回主区。
        int carrierTaskId = -1;
        Object carrierLeash;
        Object carrierMirror;
        View carrierTapView;
        boolean carrier;
        PendingSidePromotion pendingSidePromotion;

        Slot(int index) {
            this.index = index;
        }

        public int position() { return index; }
        public void position(int value) { index = value; }
    }

    private static final class PendingSidePromotion {
        final Slot slot;
        final int taskId;
        final Object token;
        final long sceneEpoch;
        final long bindingEpoch;
        final long deadline;
        final String pkg;
        boolean desktopLaunch;
        int attempts;

        PendingSidePromotion(Slot slot, long sceneEpoch, boolean desktopLaunch, long deadline) {
            this.slot = slot;
            taskId = slot.taskId;
            token = slot.taskToken;
            this.sceneEpoch = sceneEpoch;
            bindingEpoch = slot.taskEpoch;
            pkg = slot.pkg;
            this.desktopLaunch = desktopLaunch;
            this.deadline = deadline;
        }
    }

    private static final class OwnedTask {
        final int id;
        final Object token;
        Object surface;
        Object recoveryController;
        int userId = -1;
        boolean recoveryCandidate;
        OwnedTask(int id, Object token) { this.id = id; this.token = token; }
    }

    private final FinishTransactions<NativeObservation> nativeObservations = new FinishTransactions<>(16);
    private volatile NativeObservation pendingNativeObservation;

    /** One commit signal or the bounded watchdog starts the same state verification. */
    private final class NativeObservation implements Runnable {
        final Object token;
        final Runnable check;
        final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
        NativeObservation(Object token, Runnable check) { this.token = token; this.check = check; }
        public void run() {
            if (!consumed.compareAndSet(false, true)) return;
            main.removeCallbacks(this);
            if (pendingNativeObservation == this) pendingNativeObservation = null;
            check.run();
        }
    }

    private NativeObservation observeNativeCommit(Object token, Runnable check) {
        NativeObservation observation = new NativeObservation(token, check);
        pendingNativeObservation = observation;
        main.postDelayed(observation, 350L); // Fallback if the target omits the transition/commit callback.
        return observation;
    }

    private void cancelNativeObservation(NativeObservation observation) {
        if (observation == null) return;
        observation.consumed.set(true);
        main.removeCallbacks(observation);
        if (pendingNativeObservation == observation) pendingNativeObservation = null;
    }

    private static final class Promotion {
        final Slot slot;
        final int taskId;
        final Object token;
        final java.util.List<Object> otherSideTokens = new java.util.ArrayList<>();
        volatile boolean cancelled;
        volatile boolean handoffAttempted;
        volatile boolean handoffComplete;
        boolean background;
        boolean externalLaunch;
        boolean nativeSwap;
        boolean nativeFlip;
        boolean nativeHome;
        boolean nativeCollect;
        Runnable notificationLaunch;
        long liveZoomGuardUntil;
        MiStepLayout previousLayout;
        MiStepLayout targetLayout;
        boolean candidateRecovery;
        volatile boolean nativeSubmitted;
        volatile boolean cancelRequested;
        volatile boolean nativeCompleting;
        boolean liveMainFocusPrepared;
        PromotionFocusTransfer.Result liveMainFocusResult;
        LiveSwapVisualLease liveVisual;
        MiStepLiveBackdrop liveBackdrop;
        final java.util.List<LiveSwapVisualLease> peerVisuals = new java.util.ArrayList<>();
        final java.util.List<LivePeerBinding> livePeers = new java.util.ArrayList<>();
        OwnedTask previousMainCandidate;
        LiveTask previousMain;
        LiveTask homeBeforeCover;
        LiveTask collectionHome;
        boolean previousMainOnRight;
        boolean targetMainOnRight;
        LiveLiftPreview liveLift;
        /** 交换时主任务（previousMain）的真实 leash：桥上给它挖洞做 view 级缩小动画。 */
        Object mainLeash;
        Bitmap compactSnapshot;
        Rect compactSnapshotRect;
        long sceneEpoch;
        long bindingEpoch;

        Promotion(Slot slot, Slot[] slots) {
            this.slot = slot;
            this.taskId = slot.taskId;
            this.token = slot.taskToken;
            for (Slot other : slots) {
                // A parked/frozen or already hidden cell is not a live peer that can
                // block promotion. Requiring it visible caused spurious rollbacks
                // after the user returned from Home with a stale side binding.
                if (other != slot && other.occupied && !other.parked && !other.frozen
                        && other.taskVisible && other.taskToken != null) {
                    otherSideTokens.add(other.taskToken);
                }
            }
        }
    }

    private static final class LivePeerBinding {
        final Slot slot;
        final long epoch;
        final int id;
        final Object token, controller;
        LivePeerBinding(Slot slot) {
            this.slot = slot; epoch = slot.taskEpoch; id = slot.taskId;
            token = slot.taskToken; controller = slot.controller;
        }
    }

    /** Hide only the selected cell's Host drawings; its SurfaceView stays attached. */
    private static final class LiveSwapVisualLease {
        private static final class AlphaHold {
            int count;
            float alpha;
            AlphaHold(float alpha) { this.alpha = alpha; }
        }
        private static final java.util.IdentityHashMap<View, AlphaHold> held = new java.util.IdentityHashMap<>();
        final View backing, hint;
        final float backingAlpha, hintAlpha;
        final int backingVisibility, hintVisibility;
        boolean restored;
        LiveSwapVisualLease(View backing, View hint) {
            this.backing = backing; this.hint = hint;
            backingAlpha = backing == null ? 1f : backing.getAlpha();
            hintAlpha = hint == null ? 1f : hint.getAlpha();
            backingVisibility = backing == null ? View.GONE : backing.getVisibility();
            hintVisibility = hint == null ? View.GONE : hint.getVisibility();
            acquire(backing);
            acquire(hint);
        }
        private static void acquire(View view) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold == null) { hold = new AlphaHold(view.getAlpha()); held.put(view, hold); }
            hold.count++; view.setAlpha(0f);
        }
        static void writeAlpha(View view, float alpha) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold != null) hold.alpha = alpha;
            else view.setAlpha(alpha);
        }
        private static void release(View view) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold != null && --hold.count == 0) {
                held.remove(view); view.setAlpha(hold.alpha);
            }
        }
        void restore() {
            if (restored) return;
            restored = true;
            // Nested handoffs and late fade callbacks cannot resurrect shared chrome.
            // Restore the latest requested alpha only when the last owner releases it.
            release(backing);
            release(hint);
        }
    }

    private static final class LiveTask {
        final int id;
        final Object token;
        final String pkg;
        LiveTask(Object info) {
            id = XposedHelpers.getIntField(info, "taskId");
            token = XposedHelpers.getObjectField(info, "token");
            ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            pkg = base == null ? null : base.getPackageName();
        }
        LiveTask(Slot slot) {
            this.id = slot.taskId;
            this.token = slot.taskToken;
            this.pkg = slot.pkg;
        }
        LiveTask(int id, Object token, String pkg) {
            this.id = id;
            this.token = token;
            this.pkg = pkg;
        }
        boolean matches(Object info) {
            return info != null && id == XposedHelpers.getIntField(info, "taskId")
                    && token != null && token.equals(XposedHelpers.getObjectField(info, "token"));
        }
    }

    private MiStepHost(Context ctx, Executor executor, Object factory, Object transitions) {
        // Keep the workspace on the physical portrait window configuration while
        // native SystemUI resources follow the main application's environment.
        Display display = ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        this.ctx = ctx.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        this.executor = executor;
        this.factory = factory;
        this.transitions = transitions;
        statusBar = new MiStepStatusBar(main, MiStepHost::log);
        installLogicalBoundsHooks(ctx.getClassLoader());
        installRetainedSurfaces(ctx.getClassLoader());
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = new Slot(i);
        }
    }

    public static void start(Context ctx, Executor executor, Object factory, Object transitions) {
        if (!MiStepEntryGuard.canOpen(ctx)) { log("host start rejected while locked"); return; }
        if (sInstance != null) {
            log("host already started");
            return;
        }
        sInstance = new MiStepHost(ctx, executor, factory, transitions);
        sInstance.startInternal();
    }

    /** 首次触发启动宿主，之后在显示／隐藏间切换。 */
    public static void toggleOrStart(Context ctx, Executor executor, Object factory, Object transitions) {
        if (!MiStepEntryGuard.canOpen(ctx)) { log("host gesture rejected while locked"); return; }
        if (sInstance == null) {
            start(ctx, executor, factory, transitions);
            return;
        }
        sInstance.toggleVisibility();
    }

    /** 调试用：确保宿主已启动，但不切换显示状态。 */
    public static void toggleOrStartIfNeeded(Context ctx, Executor executor, Object factory,
            Object transitions) {
        if (!MiStepEntryGuard.canOpen(ctx)) return;
        if (sInstance == null) {
            start(ctx, executor, factory, transitions);
        }
    }

    /** 亮屏／熄屏通知（HostEntry 的屏幕广播）。 */
    public static void onScreenState(boolean on) {
        MiStepHost h = sInstance;
        if (h == null) {
            return;
        }
        h.main.post(() -> {
            h.screenOn = on;
            h.publishDesktopSideTasks();
            if (!on) {
                h.traceNextSwap = false;
                MiStepSwapTrace trace = h.swapTrace;
                if (trace != null) trace.close();
                h.closeSwapPreview();
                h.statusBar.restore();
                h.closeCellMovePreview();
                // Locking the device ends Mi Step instead of retaining a magnified lock screen.
                h.suspendSession("screen-lock");
            } else {
                h.updateTopBarState();
            }
        });
    }

    public static void onUserPresent() {
        MiStepHost h = sInstance;
        if (h != null) h.main.post(h::leaveKeyguardPrivacy);
    }

    public static void onSystemNavigation(String reason) {
        MiStepHost h = sInstance;
        if (h != null) h.main.post(() -> {
            if (NativeLandscapePolicy.shouldRestoreFromHome(
                    h.landscapeSuspended, h.scene == SUSPENDED, reason)) {
                h.resumeSession();
            } else {
                h.suspendSession(reason);
            }
        });
    }

    /** Native fullscreen uses the existing retained-task suspend/resume lifecycle. */
    public static void onLandscapeMode(boolean fullscreen) {
        MiStepHost h = sInstance;
        if (h == null) return;
        h.main.post(() -> {
            if (fullscreen) {
                h.landscapeSuspended = true;
                if (h.scene == ACTIVE) h.suspendSession("native-landscape");
            } else {
                h.landscapeSuspended = false;
                h.resumeSession();
            }
        });
    }

    /** 调试广播：向指定格启动一个包（无人值守验证用）。 */
    public static void debugLaunch(int slot, String pkg) {
        MiStepHost h = sInstance;
        if (h == null) {
            log("debugLaunch: host not started");
            return;
        }
        h.main.post(() -> h.launchWhenReady(slot, pkg, 0));
    }

    /** 调试广播：把指定副格与主区交换。 */
    public static void debugSwap(int slot) {
        MiStepHost h = sInstance;
        if (h == null) {
            log("debugSwap: host not started");
            return;
        }
        h.main.post(() -> h.swapWithMain(slot));
    }

    /** Keep the original notification dispatch until this exact side owner is released. */
    static boolean promoteForNotification(String pkg, int userId, Runnable dispatch) {
        MiStepHost h = sInstance;
        if (h == null || dispatch == null || pkg == null || Looper.myLooper() != h.main.getLooper()
                || h.scene != ACTIVE || !h.screenOn || h.keyguardLocked
                || userId != h.currentUserId() || h.promotion != null || h.transferBusy) return false;
        Slot match = null;
        for (Slot slot : h.slots) {
            if (slot.index <= SLOT_MAIN || !slot.occupied || slot.parked || slot.frozen
                    || slot.carrier || slot.windowMode != 6 || !pkg.equals(slot.pkg)
                    || !h.mayWriteSideSurface(slot)
                    || !h.controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            try {
                Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
                if (XposedHelpers.getIntField(info, "taskId") != slot.taskId
                        || XposedHelpers.getIntField(info, "userId") != userId) continue;
            } catch (Throwable stale) { continue; }
            if (match != null) return false;
            match = slot;
        }
        if (match == null) return false;
        h.swapWithMain(match.index);
        Promotion ticket = h.promotion;
        if (ticket == null || ticket.cancelled || ticket.slot != match) return false;
        ticket.notificationLaunch = dispatch;
        log("notification waits for same-task promotion task=" + ticket.taskId + " package=" + pkg);
        return true;
    }

    private void resumeNotificationLaunch(Promotion ticket) {
        Runnable dispatch = ticket.notificationLaunch;
        ticket.notificationLaunch = null;
        if (dispatch != null) {
            log("notification resumes original dispatch task=" + ticket.taskId);
            main.post(dispatch);
        }
    }

    /** Arm passive instrumentation only; it never starts a swap or turns on a POC. */
    public static void debugTraceNextSwap() {
        MiStepHost h = sInstance;
        if (h == null) { log("passive trace: host not started"); return; }
        h.main.post(() -> {
            if (h.scene != ACTIVE || !h.screenOn || h.keyguardLocked
                    || h.promotion != null || h.transferBusy) return;
            h.traceNextSwap = true;
            log("passive trace armed for next one swap; 6s/384 events; no transaction mutations");
        });
    }

    private void traceSwap(Promotion ticket) {
        if (!traceNextSwap) return;
        traceNextSwap = false;
        MiStepSwapTrace old = swapTrace;
        if (old != null) old.close();
        swapTrace = null;
        try {
            LiveTask previous = ticket.previousMain;
            swapTrace = new MiStepSwapTrace(main, new java.io.File(ctx.getFilesDir(), "mistep-swap-traces"),
                    MiStepHost::log, previous == null ? -1 : previous.id,
                    previous == null ? null : previous.token, ticket.taskId, ticket.token,
                    ticket.slot.controller, shellOrganizer(), layout.describe(),
                    closed -> { if (swapTrace == closed) swapTrace = null; });
        } catch (Throwable error) { log("passive trace unavailable: " + error); }
    }

    private void traceSwapEvent(String event) {
        MiStepSwapTrace trace = swapTrace;
        if (trace != null) trace.snapshot(event);
    }

    /** 调试广播：把主区任务 mirror 成宿主窗口里的实时画面（验证 live-surface 承载）。 */
    public static void debugMirror(int taskId, int x, int y, int w, int h, int holdMs,
            int hideMode) {
        MiStepHost host = sInstance;
        if (host == null) {
            log("debugMirror: host not started");
            return;
        }
        if (w <= 0 || h <= 0) {
            log("debugMirror: bad rect " + w + "x" + h);
            return;
        }
        host.main.post(() -> host.startMirrorPoc(taskId,
                new Rect(x, y, x + w, y + h), Math.max(1000, holdMs), hideMode));
    }

    private Object mirrorPocSurface;
    private Object mirrorPocLeash;
    private int mirrorPocHideMode;

    /** 镜像专用透明 overlay 窗口：位于宿主之上、不接收触摸，mirror 作为它的子层。 */
    private FrameLayout mirrorLayerRoot;
    private WindowManager mirrorLayerWm;
    private WindowManager.LayoutParams mirrorLayerParams;

    private void createMirrorLayer() {
        if (mirrorLayerRoot != null) return;
        try {
            mirrorLayerRoot = new FrameLayout(ctx);
            mirrorLayerRoot.setBackgroundColor(0x00000000);
            mirrorLayerWm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            mirrorLayerParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            mirrorLayerParams.gravity = Gravity.TOP | Gravity.START;
            mirrorLayerParams.setTitle("MiStepMirrorLayer");
            HostWindowPolicy.configure(mirrorLayerParams);
            try { mirrorLayerParams.setFitInsetsTypes(0); } catch (Throwable ignored) {}
            try {
                mirrorLayerParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } catch (Throwable ignored) {}
            mirrorLayerWm.addView(mirrorLayerRoot, mirrorLayerParams);
            log("mirror layer window added");
        } catch (Throwable error) {
            log("mirror layer window failed: " + error);
            mirrorLayerRoot = null;
        }
    }

    private void setMirrorLayerVisible(boolean visible) {
        if (mirrorLayerRoot == null) return;
        try {
            if (mirrorLayerRoot.getVisibility() != (visible ? View.VISIBLE : View.GONE)) {
                mirrorLayerRoot.setVisibility(visible ? View.VISIBLE : View.GONE);
            }
        } catch (Throwable ignored) {}
    }

    /** mirror 层窗口的 SurfaceControl；失败时返回 null。 */
    private Object mirrorLayerSurface() {
        try {
            if (mirrorLayerRoot == null) return null;
            Object viewRoot = XposedHelpers.callMethod(mirrorLayerRoot, "getViewRootImpl");
            return viewRoot == null ? null
                    : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
        } catch (Throwable error) {
            log("mirror layer surface unavailable: " + error);
            return null;
        }
    }

    /**
     * POC：从 ShellTaskOrganizer.getTaskLeash(taskId) 取真实任务 leash，
     * SurfaceControl.mirrorSurface 生成实时镜像，父挂到宿主窗口层并按目标矩形缩放。
     * hideMode 1/2/3/4 分别试验 alpha/移出屏幕/空 crop/hide 隐藏原任务，
     * 目标是镜像仍然实时、而主区不再显示原任务；结束时恢复。
     */
    private void startMirrorPoc(int requestedTask, Rect dest, int holdMs, int hideMode) {
        try {
            stopMirrorPoc();
            if (requestedTask < 0) {
                LiveTask main = findMainTask();
                if (main == null) {
                    log("mirror POC: no main task");
                    android.widget.Toast.makeText(ctx, "mirror: 主区无应用",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                requestedTask = main.id;
            }
            Object organizer = null;
            for (Slot slot : slots) {
                if (slot.controller != null) {
                    organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                    break;
                }
            }
            if (organizer == null) {
                log("mirror POC: no ShellTaskOrganizer on controllers");
                return;
            }
            Object leash = XposedHelpers.callMethod(organizer, "getTaskLeash",
                    new Class<?>[]{int.class}, requestedTask);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                log("mirror POC: leash unavailable task=" + requestedTask + " leash=" + leash);
                return;
            }
            if (hideMode == 5) {
                // 免 mirror 承载：把原任务 leash 直接变换进格子（原任务窗口仍是满屏，
                // 只是显示变换成格子大小/位置），验证画面与触摸能否同时成立。
                if (!applyDirectCarrier(leash, dest, true)) return;
                mirrorPocLeash = leash;
                mirrorPocHideMode = 5;
                log("direct carrier shown task=" + requestedTask + " dest=" + dest);
                main.postDelayed(this::stopMirrorPoc, holdMs);
                return;
            }
            Object parent = mirrorLayerSurface();
            if (parent == null) {
                Object viewRoot = XposedHelpers.callMethod(root, "getViewRootImpl");
                parent = viewRoot == null ? null
                        : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
            }
            if (parent == null) {
                log("mirror POC: host window SurfaceControl unavailable");
                return;
            }
            Class<?> scClass = Class.forName("android.view.SurfaceControl");
            Object mirror = XposedHelpers.callStaticMethod(scClass, "mirrorSurface", leash);
            float sx = dest.width() / (float) screenW;
            float sy = dest.height() / (float) screenH;
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                hideMirrorSource(tx, leash, hideMode);
                XposedHelpers.callMethod(tx, "reparent", mirror, parent);
                XposedHelpers.callMethod(tx, "setLayer", mirror, 1000000);
                XposedHelpers.callMethod(tx, "setPosition", mirror,
                        (float) dest.left, (float) dest.top);
                XposedHelpers.callMethod(tx, "setMatrix", mirror, sx, 0f, 0f, sy);
                XposedHelpers.callMethod(tx, "setAlpha", mirror, 1f);
                XposedHelpers.callMethod(tx, "show", mirror);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            mirrorPocSurface = mirror;
            mirrorPocLeash = leash;
            mirrorPocHideMode = hideMode;
            log("mirror POC shown task=" + requestedTask + " dest=" + dest
                    + " scale=" + sx + "x" + sy + " hideMode=" + hideMode
                    + " leash=" + leash + " parent=" + parent);
            main.postDelayed(this::stopMirrorPoc, holdMs);
        } catch (Throwable error) {
            log("mirror POC failed: " + error);
            XposedBridge.log(error);
            stopMirrorPoc();
        }
    }

    /**
     * 试不同的“原任务隐藏”方式，目标是镜像仍然实时、而主区不再显示原任务：
     * 0=不隐藏；1=alpha 0（已证会让镜像一起变空）；2=移出屏幕；3=空 crop；4=hide。
     */
    private void hideMirrorSource(Object tx, Object leash, int mode) {
        try {
            switch (mode) {
                case 1:
                    XposedHelpers.callMethod(tx, "setAlpha", leash, 0f);
                    break;
                case 2:
                    XposedHelpers.callMethod(tx, "setPosition", leash, -4000f, -4000f);
                    break;
                case 3:
                    XposedHelpers.callMethod(tx, "setCrop", leash, new Rect(0, 0, 0, 0));
                    break;
                case 4:
                    XposedHelpers.callMethod(tx, "hide", leash);
                    break;
                default:
                    break;
            }
        } catch (Throwable error) {
            log("mirror hide mode " + mode + " failed: " + error);
        }
    }

    private void restoreMirrorSource(Object tx, Object leash, int mode) {
        try {
            switch (mode) {
                case 1:
                    XposedHelpers.callMethod(tx, "setAlpha", leash, 1f);
                    break;
                case 2:
                    XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
                    break;
                case 3:
                    XposedHelpers.callMethod(tx, "setCrop",
                            new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                    android.graphics.Rect.class}, leash, null);
                    break;
                case 4:
                    XposedHelpers.callMethod(tx, "show", leash);
                    break;
                default:
                    break;
            }
        } catch (Throwable error) {
            log("mirror restore mode " + mode + " failed: " + error);
        }
    }

    /**
     * 免 mirror 承载：屏幕坐标 = 放大倍率 × 显示内容坐标 + 主区偏移，因此把格子的
     * 屏幕矩形反算成任务 leash 的位置/缩放。原任务窗口仍占满屏、输入通道不变。
     */
    private boolean applyDirectCarrier(Object leash, Rect dest, boolean logSuccess) {
        try {
            final float ratio = layout.mainScale;
            final float sx = dest.width() / (ratio * screenW);
            final float sy = dest.height() / (ratio * screenH);
            final float px = (dest.left - layout.mainOffsetX) / ratio;
            final float py = (dest.top - layout.mainOffsetY) / ratio;
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition", leash, px, py);
                XposedHelpers.callMethod(tx, "setMatrix", leash, sx, 0f, 0f, sy);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            if (logSuccess) {
                log("direct carrier transform ratio=" + ratio + " scale=" + sx + "x" + sy
                        + " pos=" + px + "," + py);
            }
            return true;
        } catch (Throwable error) {
            log("direct carrier failed: " + error);
            return false;
        }
    }

    private void restoreDirectCarrier(Object leash) {
        try {
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            log("direct carrier restored task to main");
        } catch (Throwable error) {
            log("direct carrier restore failed: " + error);
        }
    }

    private void stopMirrorPoc() {
        Object mirror = mirrorPocSurface;
        Object leash = mirrorPocLeash;
        int hideMode = mirrorPocHideMode;
        mirrorPocSurface = null;
        mirrorPocLeash = null;
        mirrorPocHideMode = 0;
        if (hideMode == 5) {
            if (leash != null && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                restoreDirectCarrier(leash);
            }
            return;
        }
        if (mirror == null) return;
        try {
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                if (hideMode != 0 && leash != null
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    restoreMirrorSource(tx, leash, hideMode);
                }
                XposedHelpers.callMethod(tx, "remove", mirror);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            XposedHelpers.callMethod(mirror, "release");
            log("mirror POC removed; restoredHideMode=" + hideMode);
        } catch (Throwable error) {
            log("mirror POC cleanup failed: " + error);
        }
    }

    // ---------------------------------------------------------------- mirror 承载（fullscreen-only 应用进副格）

    private Object shellOrganizer() {
        for (Slot slot : slots) {
            if (slot.controller != null) {
                return XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
            }
        }
        return null;
    }

    /**
     * 把 fullscreen-only 应用（相机等）放进副格：
     * 任务保持满屏前台（预览继续刷新），直接把任务 leash 变换成格子的大小/位置；
     * 主区显示桌面快照。此形态真机验证宿主仍能收到触摸（mirror 形态会被 CLONE 输入窗口挡住）。
     */
    private boolean attachMirrorCarrier(Slot slot, LiveTask task) {
        if (slot == null || task == null || scene != ACTIVE || carrierSlotIndex >= 0) return false;
        if (slot.rect == null) return false;
        try {
            Object organizer = shellOrganizer();
            Object leash = organizer == null ? null
                    : XposedHelpers.callMethod(organizer, "getTaskLeash",
                            new Class<?>[]{int.class}, task.id);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                log("carrier: no task leash for task=" + task.id);
                return false;
            }
            final Rect dest = new Rect(slot.rect[0], slot.rect[1], slot.rect[2], slot.rect[3]);
            // 不用 mirror：直接把原任务 leash 变换进格子。真机验证这种形态下宿主仍能收到
            // 触摸（mirror 形态会被 MIUI 的 CLONE 输入窗口挡住），且任务仍是前台、预览继续。
            if (!applyDirectCarrier(leash, dest, true)) {
                log("carrier: direct transform failed task=" + task.id);
                return false;
            }
            slot.carrier = true;
            slot.carrierTaskId = task.id;
            slot.carrierLeash = leash;
            slot.carrierMirror = null;
            slot.taskId = task.id;
            slot.taskToken = null;
            slot.pkg = task.pkg;
            slot.occupied = true;
            slot.parked = false;
            slot.frozen = false;
            slot.taskVisible = true;
            slot.launching = false;
            // 底板/hint 是不透明的宿主 View，会盖住下面的真实任务画面：承载格必须让位。
            View backing = sideBackingViews[slot.index];
            if (backing != null) backing.setVisibility(View.GONE);
            if (slot.hint != null) slot.hint.setVisibility(View.GONE);
            // 格子的 SF 纯色底也是宿主层的子 surface，同样会挡住真实任务画面。
            removeCellBackground(slot);
            carrierSlotIndex = slot.index;
            showCarrierBackdrop(task.pkg, slot.index);
            // 承载格没有 TaskView 可点：在格子上放一个透明点击层，点它把任务放回主区。
            try {
                final View tap = new View(ctx);
                tap.setBackgroundColor(0x01000000);
                tap.setOnClickListener(v -> {
                    log("carrier tap layer clicked slot=" + slot.index);
                    restoreCarrierToMain();
                });
                root.setOnTouchListener((v, event) -> {
                    if (carrierSlotIndex >= 0 && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        log("carrier root touch down " + event.getRawX() + "," + event.getRawY());
                    }
                    return false;
                });
                FrameLayout.LayoutParams tapParams =
                        new FrameLayout.LayoutParams(dest.width(), dest.height());
                tapParams.leftMargin = dest.left;
                tapParams.topMargin = dest.top;
                root.addView(tap, tapParams);
                slot.carrierTapView = tap;
            } catch (Throwable error) {
                log("carrier tap layer failed: " + error);
            }
            root.requestLayout();
            root.invalidate();
            log("carrier attached (direct) task=" + task.id + " slot=" + slot.index
                    + " dest=" + dest + " leash=" + leash);
            android.widget.Toast.makeText(ctx, "已放入副格",
                    android.widget.Toast.LENGTH_SHORT).show();
            return true;
        } catch (Throwable error) {
            log("carrier attach failed: " + error);
            XposedBridge.log(error);
            restoreCarrierToMain();
            return false;
        }
    }

    /** 主区在承载期间没有真实任务可看，用 Home 快照占位并提示返回方式。 */
    private void showCarrierBackdrop(String pkg, int slotIndex) {
        if (carrierBackdrop != null || root == null || layout == null) return;
        try {
            FrameLayout box = new FrameLayout(ctx);
            if (mainBackdrop != null && !mainBackdrop.isRecycled()) {
                android.widget.ImageView image = new android.widget.ImageView(ctx);
                image.setImageBitmap(mainBackdrop);
                image.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                box.addView(image, new FrameLayout.LayoutParams(-1, -1));
                View shade = new View(ctx);
                shade.setBackgroundColor(HyperOsStyle.dark(ctx) ? 0x33000000 : 0x22000000);
                box.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            } else {
                box.setBackgroundColor(HyperOsStyle.page(ctx));
            }
            TextView hint = new TextView(ctx);
            hint.setText(pkg + " 在 " + slotIndex + " 号窗口\n点按返回主区");
            hint.setTextColor(HyperOsStyle.textPrimary(ctx));
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            hint.setTypeface(android.graphics.Typeface.create(
                    "sans-serif-medium", android.graphics.Typeface.NORMAL));
            hint.setGravity(Gravity.CENTER);
            hint.setBackground(HyperOsStyle.glassSheet(ctx, 22f));
            hint.setPadding(dp(18), dp(12), dp(18), dp(12));
            FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-1, dp(82));
            hintParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            hintParams.leftMargin = dp(16);
            hintParams.rightMargin = dp(16);
            hintParams.bottomMargin = dp(16);
            box.addView(hint, hintParams);
            box.setOnClickListener(v -> restoreCarrierToMain());
            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(layout.mainDispWidth, layout.mainDispHeight);
            params.leftMargin = layout.mainOffsetX;
            params.topMargin = layout.mainOffsetY;
            root.addView(box, params);
            carrierBackdrop = box;
            log("carrier backdrop added for " + pkg + " slot=" + slotIndex);
        } catch (Throwable error) {
            log("carrier backdrop failed: " + error);
        }
    }

    private void removeCarrierBackdrop() {
        View box = carrierBackdrop;
        carrierBackdrop = null;
        if (box != null && root != null) {
            try { root.removeView(box); } catch (Throwable ignored) {}
            root.requestLayout();
            root.invalidate();
        }
    }

    /** 心跳/系统重排后重新声明“原任务 leash 在格子里的位置/缩放”。 */
    private void reassertCarrier() {
        for (Slot slot : slots) {
            if (!slot.carrier) continue;
            Object leash = slot.carrierLeash;
            if (leash == null || slot.rect == null) continue;
            try {
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    log("carrier leash invalid; restoring task to main");
                    restoreCarrierToMain();
                    return;
                }
                Rect dest = new Rect(slot.rect[0], slot.rect[1], slot.rect[2], slot.rect[3]);
                applyDirectCarrier(leash, dest, false);
            } catch (Throwable error) {
                log("carrier reassert failed: " + error);
            }
        }
    }

    /** 把承载中的任务移回主区（点击格子/主区、退出一步、系统返回都走这里）。 */
    private void restoreCarrierToMain() {
        boolean restored = false;
        for (Slot slot : slots) {
            if (!slot.carrier) continue;
            Object leash = slot.carrierLeash;
            try {
                if (leash != null
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    restoreDirectCarrier(leash);
                }
            } catch (Throwable error) {
                log("carrier restore failed: " + error);
            }
            slot.carrier = false;
            slot.carrierTaskId = -1;
            slot.carrierLeash = null;
            slot.carrierMirror = null;
            View tap = slot.carrierTapView;
            slot.carrierTapView = null;
            if (tap != null && root != null) {
                try { root.removeView(tap); } catch (Throwable ignored) {}
            }
            slot.occupied = false;
            slot.taskId = -1;
            slot.taskToken = null;
            slot.pkg = null;
            slot.taskVisible = false;
            View backing = sideBackingViews[slot.index];
            if (backing != null) backing.setVisibility(View.VISIBLE);
            if (slot.hint != null) slot.hint.setVisibility(View.VISIBLE);
            log("carrier restored slot=" + slot.index + " task to main");
            restored = true;
        }
        carrierSlotIndex = -1;
        removeCarrierBackdrop();
        if (root != null) {
            root.setOnTouchListener(null);
        }
        if (restored && root != null) {
            root.requestLayout();
            root.invalidate();
        }
    }

    /**
     * 清掉主任务 leash 上可能残留的过渡裁切。旧版 clampOpeningTransition 把动画期的
     * crop 写进了 finish 事务，任务回到常驻后仍被裁到主区矩形（顶栏启动的应用只显示
     * 一部分）；这里在进入一步、快捷启动后、主任务变化时主动清一次。
     */
    private void clearMainTaskLeashCrop(int taskId) {
        if (taskId < 0) return;
        try {
            Object organizer = shellOrganizer();
            if (organizer == null) return;
            Object leash = XposedHelpers.callMethod(organizer, "getTaskLeash",
                    new Class<?>[]{int.class}, taskId);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                return;
            }
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setCrop",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                android.graphics.Rect.class}, leash, null);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            log("main task leash crop cleared task=" + taskId);
        } catch (Throwable error) {
            log("clear main task crop failed task=" + taskId + ": " + error);
        }
    }

    /** 延时清一次主任务 leash 裁切（进入一步/恢复/快捷启动后调用）。 */
    private void scheduleMainCropClear(long delayMs) {
        main.postDelayed(() -> {
            if (root == null || scene == SUSPENDED) return;
            if (!executeOnShell(() -> {
                try {
                    LiveTask mainTask = findMainTask();
                    if (mainTask != null) clearMainTaskLeashCrop(mainTask.id);
                } catch (Throwable error) {
                    log("main task crop clear failed: " + error);
                }
            })) {
                log("main task crop clear skipped: no shell queue");
            }
        }, delayMs);
    }

    /** 打开一步时按滑动起始角决定主区在哪边：从左角滑 → 主区在左；从右角滑 → 主区在右。 */
    private static volatile int pendingMainSide = -1;

    public static void setPendingMainSide(boolean mainOnRight) {
        MiStepHost host = sInstance;
        // 已经打开时的滑动是“关闭”，不改变下一次打开的方向。
        if (host != null && host.scene == ACTIVE) return;
        pendingMainSide = mainOnRight ? 1 : 0;
    }

    private void consumePendingMainSide() {
        int pending = pendingMainSide;
        if (pending < 0) return;
        pendingMainSide = -1;
        boolean right = pending == 1;
        if (right != mainOnRight) {
            log("pending main side applied: " + (right ? "right" : "left"));
            setMainSide(right);
        }
    }

    /** 调试广播：把主区任务 leash 从主区尺寸动画到目标槽（view 级交换动画的 POC）。 */
    public static void debugLeashAnim(int slotIndex, int durationMs, String pkg) {
        MiStepHost host = sInstance;
        if (host == null) {
            log("debugLeashAnim: host not started");
            return;
        }
        host.main.post(() -> host.runLeashAnimPoc(slotIndex, Math.max(150, durationMs), pkg));
    }

    private volatile int leashAnimFrames;
    /** POC 期间该格的 SF 底板必须停止心跳重建，否则会盖住动画中的真实任务画面。 */
    private volatile int leashPocSlot = -1;

    private void runLeashAnimPoc(int slotIndex, long durationMs, String pkg) {
        if (scene != ACTIVE || layout == null || slotIndex <= SLOT_MAIN
                || slotIndex >= SLOT_COUNT || slots[slotIndex].rect == null) {
            log("leash anim POC skipped slot=" + slotIndex + " scene=" + scene);
            return;
        }
        final int index = slotIndex;
        if (!executeOnShell(() -> {
            try {
                LiveTask task = null;
                try {
                    task = findMainTask();
                } catch (Throwable error) {
                    log("leash anim: findMainTask failed: " + error);
                }
                if (task == null && pkg != null) {
                    try {
                        task = findPackageTask(pkg);
                        log("leash anim: package fallback task="
                                + (task == null ? -1 : task.id) + " pkg=" + pkg);
                    } catch (Throwable error) {
                        log("leash anim: findPackageTask failed: " + error);
                    }
                }
                if (task == null) {
                    log("leash anim POC: no main task pkg=" + pkg);
                    return;
                }
                final LiveTask resolved = task;
                Object organizer = shellOrganizer();
                Object leash = organizer == null ? null
                        : XposedHelpers.callMethod(organizer, "getTaskLeash",
                                new Class<?>[]{int.class}, resolved.id);
                if (leash == null
                        || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                    log("leash anim POC: no leash task=" + resolved.id);
                    return;
                }
                main.post(() -> animateLeashPoc(leash, slots[index].rect, index, resolved.id, durationMs));
            } catch (Throwable error) {
                log("leash anim POC setup failed: " + error);
            }
        })) {
            log("leash anim POC skipped: no shell queue");
        }
    }

    private void animateLeashPoc(Object leash, int[] rect, int slotIndex, int taskId,
            long durationMs) {
        final Rect dest = new Rect(rect[0], rect[1], rect[2], rect[3]);
        final float ratio = layout.mainScale;
        final float endScaleX = dest.width() / (ratio * screenW);
        final float endScaleY = dest.height() / (ratio * screenH);
        final float endX = (dest.left - layout.mainOffsetX) / ratio;
        final float endY = (dest.top - layout.mainOffsetY) / ratio;
        leashAnimFrames = 0;
        hideSlotChromeForPoc(slotIndex, true);
        try {
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(durationMs);
            anim.setInterpolator(new PathInterpolator(0.34f, 0.69f, 0.1f, 1f));
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                applyLeashTransform(leash, endX * f, endY * f,
                        1f + (endScaleX - 1f) * f, 1f + (endScaleY - 1f) * f);
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    applyLeashTransform(leash, endX, endY, endScaleX, endScaleY);
                    log("leash anim POC finished task=" + taskId + " dest=" + dest
                            + " frames=" + leashAnimFrames);
                    main.postDelayed(() -> reverseLeashPoc(leash, taskId, slotIndex), 1200L);
                }
            });
            anim.start();
            log("leash anim POC start task=" + taskId + " dest=" + dest
                    + " duration=" + durationMs + " endScale=" + endScaleX + "x" + endScaleY);
        } catch (Throwable error) {
            log("leash anim POC failed: " + error);
        }
    }

    /** POC 期间隐藏目标格的宿主底板，让真实任务画面能在格子里可见（正式交换也会这么做）。 */
    private void hideSlotChromeForPoc(int slotIndex, boolean hide) {
        try {
            leashPocSlot = hide ? slotIndex : -1;
            View backing = sideBackingViews[slotIndex];
            if (backing != null) {
                backing.setVisibility(hide ? View.GONE : View.VISIBLE);
            }
            Slot slot = slots[slotIndex];
            if (slot != null) {
                if (hide) {
                    if (slot.hint != null) slot.hint.setVisibility(View.GONE);
                    removeCellBackground(slot);
                } else if (slot.hint != null) {
                    slot.hint.setVisibility(View.VISIBLE);
                }
            }
            if (root != null) {
                root.requestLayout();
                root.invalidate();
            }
            log("leash anim POC slot chrome hidden=" + hide + " slot=" + slotIndex);
        } catch (Throwable error) {
            log("leash anim chrome failed: " + error);
        }
    }

    private void reverseLeashPoc(Object leash, int taskId, int slotIndex) {
        // POC 只验证“主窗 live 缩小”；hold 后直接回位，避免再引入一套反向动画。
        applyLeashTransform(leash, 0f, 0f, 1f, 1f);
        hideSlotChromeForPoc(slotIndex, false);
        log("leash anim POC restored task=" + taskId);
    }

    /**
     * view 级主窗动画：把真实主任务 leash 在桥的洞内从主区矩形缩进目标格。
     * 与 SwapPreview 同时长、同曲线，桥只画侧栏/背景，主窗画面是真实任务表面。
     */
    private void startLiveMainShrink(Promotion ticket) {
        Object leash = ticket.mainLeash;
        if (leash == null || ticket.slot == null || ticket.slot.rect == null) return;
        int[] rect = ticket.slot.rect;
        final Rect dest = new Rect(rect[0], rect[1], rect[2], rect[3]);
        final float ratio = layout.mainScale;
        final float endScaleX = dest.width() / (ratio * screenW);
        final float endScaleY = dest.height() / (ratio * screenH);
        final float endX = (dest.left - layout.mainOffsetX) / ratio;
        final float endY = (dest.top - layout.mainOffsetY) / ratio;
        try {
            ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(SwapPreview.DURATION_MS);
            anim.setInterpolator(new PathInterpolator(0.34f, 0.69f, 0.1f, 1f));
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                applyLeashTransform(leash, endX * f, endY * f,
                        1f + (endScaleX - 1f) * f, 1f + (endScaleY - 1f) * f);
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) {
                    applyLeashTransform(leash, endX, endY, endScaleX, endScaleY);
                    log("live main shrink finished task=" + ticket.taskId
                            + " dest=" + dest + " frames=" + leashAnimFrames);
                }
            });
            leashAnimFrames = 0;
            anim.start();
            log("live main shrink start task=" + ticket.taskId + " dest=" + dest
                    + " duration=" + SwapPreview.DURATION_MS);
        } catch (Throwable error) {
            log("live main shrink failed: " + error);
        }
    }

    private void applyLeashTransform(Object leash, float x, float y, float sx, float sy) {
        try {
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition", leash, x, y);
                XposedHelpers.callMethod(tx, "setMatrix", leash, sx, 0f, 0f, sy);
                XposedHelpers.callMethod(tx, "apply");
                leashAnimFrames++;
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
        } catch (Throwable error) {
            log("leash anim frame failed: " + error);
        }
    }

    /** Bounds of the visible main pane while the host owns the display zoom. */
    static Rect activeMainBounds() {
        MiStepHost h = sInstance;
        if (h == null || h.scene != ACTIVE || !h.screenOn || h.layout == null) return null;
        MiStepLayout active = h.layout;
        return new Rect(active.mainX, active.mainY,
                active.mainX + active.mainWidth, active.mainY + active.mainHeight);
    }

    /** Workspace matrix for a registered native main-task animation, never for a swap. */
    static float[] activeMainTransform() {
        MiStepHost h = sInstance;
        if (h == null || !hostSceneActive() || h.layout == null
                || h.promotion != null || h.transferBusy || h.keyguardLocked) return null;
        MiStepLayout current = h.layout;
        return new float[]{current.mainScale, current.mainOffsetY, current.mainOffsetX};
    }

    static Rect nativeFreeformAnimationStart(Object change) {
        MiStepHost h = sInstance;
        float[] transform = activeMainTransform();
        if (h == null || transform == null || change == null) return null;
        try {
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null || windowModeOf(task) != 5
                    || XposedHelpers.getIntField(task, "displayId") != 0
                    || h.isSideToken(XposedHelpers.getObjectField(task, "token"))) return null;
            int startRotation = (Integer) XposedHelpers.callMethod(change, "getStartRotation");
            int endRotation = (Integer) XposedHelpers.callMethod(change, "getEndRotation");
            if (startRotation < 0 || startRotation != endRotation) return null;
            return NativeFreeformAnimation.startBounds(
                    (Rect) XposedHelpers.callMethod(change, "getStartAbsBounds"),
                    h.layout.width, h.layout.height, transform);
        } catch (Throwable error) { return null; }
    }

    static float[] activeStatusBarTransform() {
        MiStepHost h = sInstance;
        if (h == null || !h.zoomed || !h.screenOn || h.keyguardLocked || h.layout == null
                || h.root == null || h.root.getVisibility() != View.VISIBLE) return null;
        MiStepLayout current = h.layout;
        return new float[]{current.mainScale, current.mainOffsetY, current.mainOffsetX};
    }

    static float[] activeIslandMonitorTransform() {
        MiStepHost h = sInstance;
        if (h == null || SystemClock.uptimeMillis() < h.zoomAnimUntil) return null;
        return activeMainTransform();
    }

    static boolean hostSceneActive() {
        MiStepHost h = sInstance;
        return h != null && h.zoomed && h.scene == ACTIVE && h.screenOn
                && h.root != null && h.root.getVisibility() == View.VISIBLE;
    }

    static boolean shouldClampAnimationBounds(Object info, Object change) {
        MiStepHost h = sInstance;
        if (h == null || info == null || change == null) return false;
        try {
            int transitionType = (Integer) XposedHelpers.callMethod(info, "getType");
            int changeMode = (Integer) XposedHelpers.callMethod(change, "getMode");
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null) return false;
            Object token = XposedHelpers.getObjectField(task, "token");
            boolean sideTask = token == null || h.isSideToken(token) || windowModeOf(task) == 6;
            int startRotation = -1;
            int endRotation = -1;
            try {
                startRotation = (Integer) XposedHelpers.callMethod(change, "getStartRotation");
                endRotation = (Integer) XposedHelpers.callMethod(change, "getEndRotation");
            } catch (Throwable ignored) { }
            boolean rotating = startRotation >= 0 && endRotation >= 0
                    && startRotation != endRotation;
            return TransitionAnimationPolicy.shouldClampOpenBounds(
                    hostSceneActive(), h.promotion != null || h.transferBusy,
                    transitionType, changeMode, sideTask, windowModeOf(task), rotating);
        } catch (Throwable error) {
            return false;
        }
    }

    /** 等 TaskView 就绪再启动；宿主刚建时窗口可能还没 attach。 */
    private void launchWhenReady(int slot, String pkg, int attempt) {
        if (slot <= SLOT_MAIN || slot >= SLOT_COUNT) {
            return;
        }
        if (slots[slot].view == null && attempt < 8) {
            main.postDelayed(() -> launchWhenReady(slot, pkg, attempt + 1), 400L);
            return;
        }
        launchPackage(slot, pkg);
    }

    private void toggleVisibility() {
        main.post(() -> {
            if (scene == SUSPENDED) resumeSession();
            else if (scene == ACTIVE) suspendSession("exit");
        });
    }

    /** Transfer live tasks first. Removing a SurfaceView while still bound hides its task. */
    private void suspendSession(String reason) {
        if (!"native-landscape".equals(reason)) landscapeSuspended = false;
        statusBar.restore(); // Includes a deferred exit while native ownership is changing.
        MiStepClipboard.get(ctx).close();
        if (root == null) return;
        if (scene == RESUMING) {
            suspendRequested = true;
            if (!workspaceEntering) return; // A native readoption still owns its callback.
            workspaceEntering = false;
            workspaceGeneration++; // Cancel a pending first-frame entry before it can shrink again.
            scene = ACTIVE;
        }
        if (scene != ACTIVE) return;
        if (promotion != null || transferBusy) {
            suspendRequested = true;
            if (cellMotion != null) cellMotion.finish();
            if (sideDismiss != null && sideDismiss.isTracking()) sideDismiss.settle(false);
            log("suspend queued after task transfer: " + reason);
            if (promotion != null && promotion.nativeSwap)
                failPromotion(promotion, "suspend requested: " + reason);
            return;
        }
        for (Slot slot : slots) {
            if (slot.launching) {
                suspendRequested = true;
                log("suspend queued after launch: " + reason);
                return;
            }
        }
        scene = SUSPENDING;
        sceneEpoch++;
        publishDesktopSideTasks();
        suspendRequested = false;
        closeSwapPreview();
        closeCellMovePreview();
        // fullscreen-only 的镜像承载任务仍在主区：退出前先移回，避免下次进入是一块黑屏。
        restoreCarrierToMain();
        if (mainBackdrop != null) {
            mainBackdrop.recycle();
            mainBackdrop = null;
        }
        hideIme();
        workspaceEntering = false;
        final long exitAnimation = ++workspaceGeneration;
        if (screenOn) writeZoomAnimated(false); else writeZoom(false);
        setZoomState(false);
        log("suspend begin; retain live task identities: " + reason);
        final long exitEpoch = sceneEpoch;
        Runnable returnTasks = () -> {
            if (scene == SUSPENDING && sceneEpoch == exitEpoch)
                returnUnboundOwnedCandidates(exitEpoch, () -> parkNext(1));
        };
        if (screenOn) playWorkspaceFade(false, exitAnimation, returnTasks);
        else {
            android.animation.ValueAnimator previous = workspaceAnimator;
            workspaceAnimator = null;
            if (previous != null) previous.cancel();
            setWorkspaceAlpha(0f);
            returnTasks.run();
        }
    }

    /** A pending swap can leave A outside the saved slot; it remains in the recovery ledger. */
    private void returnUnboundOwnedCandidates(long epoch, Runnable complete) {
        OwnedTask next = null;
        for (OwnedTask candidate : ownedTasks.values()) {
            if (!candidate.recoveryCandidate) continue;
            boolean inSlot = false;
            for (Slot slot : slots) if (slot.index > 0 && slot.occupied
                    && candidate.token.equals(slot.taskToken)) { inSlot = true; break; }
            if (!inSlot) { next = candidate; break; }
        }
        if (next == null) { complete.run(); return; }
        returnUnboundOwnedCandidate(next, epoch, 0, complete);
    }

    private void returnUnboundOwnedCandidate(OwnedTask candidate, long epoch, int attempt, Runnable complete) {
        if (scene != SUSPENDING || sceneEpoch != epoch || ownedTasks.get(candidate.token) != candidate) return;
        if (!executeOnShell(() -> {
            if (scene != SUSPENDING || sceneEpoch != epoch || ownedTasks.get(candidate.token) != candidate) return;
            boolean ready = false;
            String error = "owned candidate not yet returned";
            try {
                Object controller = candidate.recoveryController;
                if (controller == null || candidate.userId < 0) throw new IllegalStateException("candidate owner/user absent");
                LiveTask identity = new LiveTask(candidate.id, candidate.token, null);
                Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
                Object core = rootInfo(identity);
                Object appeared;
                synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                    appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                            organizer, "mTasks")).get(candidate.id);
                    if (appeared != null && !identity.matches(XposedHelpers.callMethod(appeared, "getTaskInfo")))
                        throw new IllegalStateException("candidate canonical token changed");
                }
                if (core == null) {
                    if (appeared != null) throw new IllegalStateException("candidate still in organizer outside root list");
                    Object pending = XposedHelpers.getObjectField(controller, "mPendingInfo");
                    if (candidate.token.equals(XposedHelpers.getObjectField(controller, "mTaskToken"))
                            || (pending != null && identity.matches(pending)))
                        throw new IllegalStateException("candidate still referenced by its controller");
                    ready = true; // Exact task is gone from both sources, never infer death from roots alone.
                } else {
                    if (!identity.matches(core) || XposedHelpers.getIntField(core, "userId") != candidate.userId
                            || (!ordinaryRoot(core, 1) && !ordinaryRoot(core, 6)))
                        throw new IllegalStateException("candidate identity/user/display/role changed");
                    Slot recovery = new Slot(-1);
                    recovery.controller = controller; recovery.taskId = candidate.id;
                    recovery.taskToken = candidate.token;
                    Promotion handback = new Promotion(recovery, new Slot[0]);
                    handback.background = true; handback.candidateRecovery = true;
                    if (attempt == 0 || ordinaryRoot(core, 6)) {
                        if (!applyWct("return-unbound-owned-candidate", fullscreenExitTransaction(handback)))
                            throw new IllegalStateException("candidate fullscreen transaction refused");
                    } else {
                        handoffPromotedTask(handback);
                        ready = handback.handoffComplete;
                    }
                }
            } catch (Throwable failure) { error = failure.toString(); }
            final boolean returned = ready;
            final String detail = error;
            main.post(() -> {
                if (scene != SUSPENDING || sceneEpoch != epoch || ownedTasks.get(candidate.token) != candidate) return;
                if (returned) {
                    ownedTasks.remove(candidate.token);
                    log("unbound owned candidate returned alive task=" + candidate.id);
                    returnUnboundOwnedCandidates(epoch, complete);
                } else if (attempt < 3) {
                    main.postDelayed(() -> returnUnboundOwnedCandidate(candidate, epoch, attempt + 1, complete), 350L);
                } else {
                    // Preserve the exact A/B ledger and attached surfaces for a later explicit exit.
                    scene = ACTIVE;
                    if (screenOn && !keyguardLocked) { setWorkspaceAlpha(1f); writeZoom(true); setZoomState(true); }
                    log("suspend stopped; unresolved owned candidate retained task=" + candidate.id + ": " + detail);
                }
            });
        })) {
            scene = ACTIVE;
            if (screenOn && !keyguardLocked) { setWorkspaceAlpha(1f); writeZoom(true); setZoomState(true); }
            log("suspend stopped; owned candidate Shell executor unavailable task=" + candidate.id);
        }
    }

    private void parkNext(int index) {
        if (index >= SLOT_COUNT) {
            root.setVisibility(View.GONE);
            setMirrorLayerVisible(false);
            scene = SUSPENDED;
            publishDesktopSideTasks();
            log("host suspended; all side tasks returned alive: " + describeTasks());
            return;
        }
        Slot slot = slots[index];
        if (!slot.occupied || slot.parked || slot.taskToken == null) {
            parkNext(index + 1);
            return;
        }
        returnToSystem(slot, false, () -> parkNext(index + 1), error -> {
            // Some peers may already be parked. Restore them before exposing ACTIVE again.
            scene = RESUMING;
            setWorkspaceAlpha(1f);
            writeZoom(true);
            setZoomState(true);
            log("suspend incomplete; restore parked peers before resuming: " + error);
            resumeNext(1, sceneEpoch);
        });
    }

    private void resumeSession() {
        if (scene != SUSPENDED || !screenOn || root == null || !MiStepEntryGuard.canOpen(ctx)) return;
        landscapeSuspended = false;
        scene = RESUMING;
        final long epoch = ++sceneEpoch;
        // Read the foreground before showing our overlay or reclaiming any saved task.
        // A desktop launch while suspended is the user's new main-task choice.
        if (!executeOnShell(() -> {
            try {
                final java.util.Set<Object> foreground = new java.util.HashSet<>();
                for (Object info : rootInfos()) {
                    if (RetainedTaskRestorePolicy.preserveAsMain(ordinaryRoot(info, 1),
                            XposedHelpers.getBooleanField(info, "isVisible"),
                            XposedHelpers.getBooleanField(info, "isFocused"))) {
                        foreground.add(XposedHelpers.getObjectField(info, "token"));
                    }
                }
                main.post(() -> {
                    if (scene != RESUMING || sceneEpoch != epoch) return;
                    for (Slot saved : slots) {
                        if (saved.parked && saved.occupied && saved.taskToken != null
                                && !RetainedTaskRestorePolicy.reclaim(
                                        saved.occupied, saved.parked, saved.taskToken, foreground)) {
                            int id = saved.taskId;
                            TaskWindowState state = saved.windowState;
                            saved.windowState = null;
                            clearSlot(saved); // Forget the slot; never remove or move the live task.
                            if (state != null) { state.slotIndex = SLOT_MAIN; state.active = true; }
                            log("resume kept desktop foreground task=" + id + " in main; old cell released");
                        }
                    }
                    showResumingSession(epoch);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    if (scene != RESUMING || sceneEpoch != epoch) return;
                    scene = SUSPENDED;
                    log("resume stopped before reclaiming tasks: " + error);
                });
            }
        })) { scene = SUSPENDED; log("resume stopped: no Shell queue"); }
    }

    private void showResumingSession(long epoch) {
        if (scene != RESUMING || sceneEpoch != epoch) return;
        if (!MiStepEntryGuard.canOpen(ctx)) { scene = SUSPENDED; return; }
        consumePendingMainSide();
        setWorkspaceAlpha(0f);
        root.setVisibility(View.VISIBLE);
        setMirrorLayerVisible(true);
        main.postDelayed(this::collapseStatusBarPanels, 220L);
        beginWorkspaceEntry(() -> {
            if (scene == RESUMING && sceneEpoch == epoch) resumeNext(1, epoch);
        });
    }

    private void resumeNext(int index, long epoch) {
        if (scene != RESUMING || epoch != sceneEpoch) return;
        if (suspendRequested || !screenOn) {
            scene = ACTIVE;
            suspendSession("navigation-during-resume");
            return;
        }
        if (index >= SLOT_COUNT) {
            scene = ACTIVE;
            publishDesktopSideTasks();
            requestCompaction();
            refreshQuickApps();
            reassertSideTaskViews("resume complete");
            log("host resumed from retained task identities: " + describeTasks());
            finishPendingSuspend();
            return;
        }
        Slot slot = slots[index];
        if (!slot.occupied || !slot.parked) { resumeNext(index + 1, epoch); return; }
        LiveTask saved = new LiveTask(slot);
        adoptExisting(slot, saved, () -> resumeNext(index + 1, epoch), error -> {
            // External task removal is a real empty cell; a failed admission never launches it.
            updateHint(slot);
            log("resume task unavailable; keep existing system task, no relaunch: " + error);
            resumeNext(index + 1, epoch);
        });
    }

    private void finishPendingSuspend() {
        if (suspendRequested && scene == ACTIVE && promotion == null && !transferBusy) {
            suspendRequested = false;
            suspendSession("deferred");
        }
    }

    /** Process-wide hooks, restricted to these exact three view/controller identities. */
    private static final ThreadLocal<java.util.List<MiStepSideClose>> nativeCloses = new ThreadLocal<>();
    private static final ThreadLocal<Object> closeFinishTransaction = new ThreadLocal<>();
    private static void installLogicalBoundsHooks(ClassLoader cl) {
        if (boundsHooksReady) return;
        java.util.List<XC_MethodHook.Unhook> installed = new java.util.ArrayList<>();
        try {
            Class<?> view = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskView", cl);
            Class<?> trans = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskViewTransitions", cl);
            Class<?> control = XposedHelpers.findClass("com.android.wm.shell.taskview.TaskViewTaskController", cl);
            installed.addAll(XposedBridge.hookAllMethods(trans, "startAnimation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    MiStepHost h = sInstance;
                    if (p.args.length != 6 || h == null || h.transitions != p.thisObject) return;
                    java.util.List<MiStepSideClose> closes = h.closingTasks(p.args[1]);
                    if (closes.isEmpty()) return;
                    p.setObjectExtra("mistep.closes", closes);
                    nativeCloses.set(closes);
                    closeFinishTransaction.set(p.args[4]);
                    Object original = p.args[5];
                    Class<?> callback = Class.forName("com.android.wm.shell.transition.Transitions$TransitionFinishCallback", false, cl);
                    java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
                    p.args[5] = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{callback}, (proxy, method, args) -> {
                        if (!"onTransitionFinished".equals(method.getName())) return method.invoke(original, args);
                        if (!finished.compareAndSet(false, true)) return null;
                        java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(closes.size());
                        Runnable finish = () -> {
                            if (remaining.decrementAndGet() == 0)
                                XposedHelpers.callMethod(original, "onTransitionFinished", args[0]);
                        };
                        for (MiStepSideClose close : closes) close.animate(finish);
                        return null;
                    });
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    @SuppressWarnings("unchecked") java.util.List<MiStepSideClose> closes =
                            (java.util.List<MiStepSideClose>) p.getObjectExtra("mistep.closes");
                    if (closes == null) return;
                    nativeCloses.remove();
                    closeFinishTransaction.remove();
                    if (p.hasThrowable() || !Boolean.TRUE.equals(p.getResult()))
                        for (MiStepSideClose close : closes) close.abandon();
                }
            }));
            installed.addAll(XposedBridge.hookAllMethods(control, "prepareCloseAnimation", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    java.util.List<MiStepSideClose> closes = nativeCloses.get();
                    if (closes == null || p.hasThrowable() || p.args.length != 2) return;
                    for (MiStepSideClose close : closes)
                        if (close.controller == p.thisObject && close.surface == p.args[1]) {
                            try { close.prepare(p.args[0], closeFinishTransaction.get()); }
                            catch (Throwable error) { log("side close prepare: " + error); }
                        }
                }
            }));
            installed.add(XposedHelpers.findAndHookMethod(view, "onComputeInternalInsets",
                    XposedHelpers.findClass("android.view.ViewTreeObserver$InternalInsetsInfo", cl),
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost h = sInstance;
                    // TaskView normally punches its app hole out of the Host. Mi Step
                    // owns the entire side strip for tap-to-promote. A moved/retired
                    // TaskView must not subtract another cell after our Host listener.
                    if (h != null && h.ownsViewOrController(p.thisObject)) p.setResult(null);
                }
            }));
            installed.add(XposedHelpers.findAndHookMethod(view, "getCurrentBoundsOnScreen", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost h = sInstance;
                    if (h != null && h.ownsViewOrController(p.thisObject)) p.setResult(h.logicalBounds());
                }
            }));
            XC_MethodHook rectGuard = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost h = sInstance;
                    if (h != null && h.transitions == p.thisObject && h.ownsViewOrController(p.args[0]))
                        p.args[1] = h.logicalBounds();
                }
            };
            installed.add(XposedHelpers.findAndHookMethod(trans, "setTaskBounds", control, Rect.class, rectGuard));
            installed.add(XposedHelpers.findAndHookMethod(trans, "lambda$setTaskBounds$6", control, Rect.class, rectGuard));
            java.util.Set<XC_MethodHook.Unhook> external = XposedBridge.hookAllMethods(trans,
                    "isValidTaskView", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost h = sInstance;
                    if (h != null && h.transitions == p.thisObject && p.args.length == 2
                            && p.args[1] == null && h.leaveExternalChangeToNative(p.args[0]))
                        p.setResult(false);
                }
            });
            if (external.isEmpty()) throw new IllegalStateException("no external task change guard");
            installed.addAll(external);
            java.util.Set<XC_MethodHook.Unhook> unfold = XposedBridge.hookAllMethods(trans, "updateBoundsForUnfold", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost h = sInstance;
                    if (h == null || h.transitions != p.thisObject || p.args.length < 2
                            || !(p.args[0] instanceof Rect)) return;
                    for (Object arg : p.args) {
                        if (arg == null || !arg.getClass().getName().contains("RunningTaskInfo")) continue;
                        Object controller = XposedHelpers.callMethod(p.thisObject, "findTaskView", arg);
                        if (h.ownsViewOrController(controller)) {
                            for (Slot slot : h.slots) {
                                if (slot.controller == controller && !slot.parked
                                        && h.controllerOwns(slot, XposedHelpers.getIntField(arg, "taskId"),
                                        XposedHelpers.getObjectField(arg, "token"))) p.args[0] = h.logicalBounds();
                            }
                        }
                    }
                }
            });
            if (unfold.isEmpty()) throw new IllegalStateException("no unfold bounds guard");
            installed.addAll(unfold);
            java.util.Set<XC_MethodHook.Unhook> opening = XposedBridge.hookAllMethods(trans, "prepareOpenAnimation", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    MiStepHost h = sInstance;
                    if (h != null && h.transitions == p.thisObject && p.args.length == 7
                            && h.ownsViewOrController(p.args[0])) {
                        Object info = p.args[4];
                        Object token = XposedHelpers.getObjectField(info, "token");
                        Object wct = p.args[6];
                        XposedHelpers.callMethod(wct, "setForceTranslucent", token, true);
                        XposedHelpers.callMethod(wct, "setAlwaysOnTop", token, true);
                        XposedHelpers.callMethod(wct, "setFocusable", token, true);
                        XposedHelpers.callMethod(wct, "setBounds", token, h.logicalBounds());
                    }
                }
            });
            if (opening.isEmpty()) throw new IllegalStateException("no native opening guard");
            installed.addAll(opening);
            boundsHooksReady = true;
            log("logical bounds guards installed: getter/queue/consumer/unfold");
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : installed) hook.unhook();
            log("logical bounds guards unavailable; no task admission: " + error);
        }
    }

    private Rect logicalBounds() { return new Rect(0, 0, screenW, screenH); }

    private java.util.Set<Integer> supportedTaskUsers() {
        long now = SystemClock.uptimeMillis();
        java.util.Set<Integer> cached = taskUserIds;
        if (cached != null && now - taskUserIdsReadAt < 5000L) return cached;
        synchronized (taskUsersLock) {
            cached = taskUserIds;
            now = SystemClock.uptimeMillis();
            if (cached != null && now - taskUserIdsReadAt < 5000L) return cached;
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            int currentUser = currentUserId();
            ids.add(currentUser);
            try {
                UserManager users = ctx.getSystemService(UserManager.class);
                if (users != null) {
                    java.util.List<UserHandle> profiles = users.getUserProfiles();
                    if (profiles != null) {
                        for (UserHandle profile : profiles) {
                            if (profile != null) {
                                ids.add((Integer) XposedHelpers.callMethod(profile, "getIdentifier"));
                            }
                        }
                    }
                }
                taskUserIdsReadError = null;
            } catch (Throwable error) {
                String message = String.valueOf(error);
                if (!message.equals(taskUserIdsReadError)) {
                    log("associated task profiles unavailable; owner only: " + message);
                    taskUserIdsReadError = message;
                }
            }
            taskUserIds = java.util.Collections.unmodifiableSet(ids);
            taskUserIdsReadAt = now;
            log("task profile scope current=" + currentUser + " associated=" + ids);
            return taskUserIds;
        }
    }

    private boolean supportsTaskUser(int userId) {
        java.util.Set<Integer> ids = supportedTaskUsers();
        return TaskUserPolicy.allows(userId, currentUserId(), ids);
    }

    private int currentUserId() {
        try {
            return (Integer) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityManager"), "getCurrentUser");
        } catch (Throwable ignored) {
            return (Integer) XposedHelpers.callStaticMethod(UserHandle.class, "getUserId",
                    android.os.Process.myUid());
        }
    }

    private boolean ownsViewOrController(Object object) {
        if (object == null) return false;
        for (Slot slot : slots)
            if (slot.index > 0 && !slot.parked && (slot.view == object || slot.controller == object)) return true;
        return false;
    }

    /** External mode1 CHANGE belongs to Launcher, not TaskView's immediate side reparent. */
    private boolean leaveExternalChangeToNative(Object change) {
        if (change == null || scene != ACTIVE || !screenOn || keyguardLocked || transferBusy) return false;
        try {
            int mode = (Integer) XposedHelpers.callMethod(change, "getMode");
            if (mode != 6 && mode != 3) return false;
            Object info = XposedHelpers.callMethod(change, "getTaskInfo");
            if (!ordinaryRoot(info, 1)) return false;
            int taskId = XposedHelpers.getIntField(info, "taskId");
            Object token = XposedHelpers.getObjectField(info, "token");
            for (Slot slot : slots) {
                if (slot.index <= 0 || !isActive(slot) || !slot.occupied || slot.parked
                        || slot.frozen || slot.launching || slot.modeAdmissionPending
                        || slot.taskId != taskId || token == null || !token.equals(slot.taskToken)
                        || !controllerOwns(slot, taskId, token)) continue;
                Promotion current = promotion;
                if (current != null && (!current.externalLaunch || current.slot != slot
                        || current.sceneEpoch != sceneEpoch || current.bindingEpoch != slot.taskEpoch))
                    return false;
                Object queue = XposedHelpers.getObjectField(transitions, "mPending");
                if (!(queue instanceof Iterable<?>)) return false;
                for (Object pending : (Iterable<?>) queue)
                    if (XposedHelpers.getObjectField(pending, "mTaskView") == slot.controller) return false;
                Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                    Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                            organizer, "mTasks")).get(taskId);
                    Object canonical = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                    if (!ordinaryRoot(canonical, 1) || !token.equals(XposedHelpers.getObjectField(canonical, "token"))
                            || XposedHelpers.getIntField(canonical, "taskId") != taskId
                            || XposedHelpers.getIntField(canonical, "userId") != XposedHelpers.getIntField(info, "userId"))
                        return false;
                }
                log("external fullscreen change kept native task=" + taskId + " slot=" + slot.index);
                return true;
            }
        } catch (Throwable unavailable) { log("external native change guard unavailable: " + unavailable); }
        return false;
    }

    private java.util.List<?> rootInfos() throws Exception {
        Object service = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityTaskManager"), "getService");
        return (java.util.List<?>) XposedHelpers.callMethod(service, "getAllRootTaskInfos");
    }

    private Object rootInfo(LiveTask task) throws Exception {
        for (Object info : rootInfos()) if (task.matches(info)) return info;
        return null;
    }

    private boolean ordinaryRoot(Object info, int mode) {
        if (info == null || XposedHelpers.getIntField(info, "displayId") != 0
                || !supportsTaskUser(XposedHelpers.getIntField(info, "userId"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                || !Integer.valueOf(mode).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getActivityType"))) return false;
        ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
        return base != null && !"com.miui.home".equals(base.getPackageName());
    }

    private boolean isSideToken(Object token) {
        for (Slot slot : slots) if (!slot.parked && token != null && token.equals(slot.taskToken)) return true;
        return false;
    }

    /** 诊断：打印当前所有 root task 的判定字段（findMainTask 漏判用）。 */
    private void dumpRoots(String reason) {
        try {
            java.util.List<?> roots = rootInfos();
            LiveTask main = null;
            try { main = findMainTask(); } catch (Throwable error) {
                log("roots[" + reason + "] findMainTask threw: " + error);
            }
            log("roots[" + reason + "] count=" + roots.size()
                    + " main=" + (main == null ? -1 : main.id));
            for (Object info : roots) {
                int taskId = XposedHelpers.getIntField(info, "taskId");
                int userId = XposedHelpers.getIntField(info, "userId");
                int type = (Integer) XposedHelpers.callMethod(info, "getActivityType");
                boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
                boolean focused = XposedHelpers.getBooleanField(info, "isFocused");
                boolean hasParent = Boolean.TRUE.equals(
                        XposedHelpers.callMethod(info, "hasParentTask"));
                int mode = -1;
                try {
                    Object config = XposedHelpers.getObjectField(info, "configuration");
                    Object wc = XposedHelpers.getObjectField(config, "windowConfiguration");
                    mode = (Integer) XposedHelpers.callMethod(wc, "getWindowingMode");
                } catch (Throwable ignored) {}
                Object token = XposedHelpers.getObjectField(info, "token");
                boolean side = isSideToken(token);
                boolean ordinary = ordinaryRoot(info, 1);
                ComponentName base = (ComponentName) XposedHelpers.getObjectField(
                        info, "baseActivity");
                log("roots[" + reason + "] task=" + taskId + " type=" + type
                        + " user=" + userId
                        + " vis=" + visible + " focused=" + focused + " mode=" + mode
                        + " parent=" + hasParent + " side=" + side
                        + " ordinary=" + ordinary
                        + " base=" + (base == null ? "?" : base.getPackageName()));
            }
        } catch (Throwable error) {
            log("roots dump[" + reason + "] failed: " + error);
        }
    }

    /** 调试广播入口：打印 root 诊断。 */
    public static void debugRoots() {
        MiStepHost host = sInstance;
        if (host == null) {
            log("debugRoots: host not started");
            return;
        }
        if (!host.executeOnShell(() -> host.dumpRoots("debug"))) {
            log("debugRoots skipped: no shell queue");
        }
    }

    private LiveTask findMainTask() throws Exception {
        // A closing fullscreen app may remain visible while Home has already taken
        // focus. Visibility alone cannot make that departing app a swap participant.
        // If both claim focus the scene is still changing: retain both task bindings.
        LiveTask candidate = null;
        LiveTask focusedCandidate = null;
        boolean focusedHome = false;
        int visibleCandidates = 0;
        java.util.List<?> roots = rootInfos();
        for (Object info : roots) {
            if (isHomeRoot(info) && XposedHelpers.getBooleanField(info, "isVisible")
                    && XposedHelpers.getBooleanField(info, "isFocused")) focusedHome = true;
            if (!ordinaryRoot(info, 1)) continue;
            int userId = XposedHelpers.getIntField(info, "userId");
            boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
            boolean focused = XposedHelpers.getBooleanField(info, "isFocused");
            boolean sideTask = isSideToken(XposedHelpers.getObjectField(info, "token"));
            if (!TaskUserPolicy.mayBeMainTask(userId, currentUserId(),
                    supportedTaskUsers(), visible, focused, sideTask)) continue;
            if (visible) visibleCandidates++;
            LiveTask task = new LiveTask(info);
            if (focused) {
                if (focusedCandidate != null)
                    throw new IllegalStateException("ambiguous focused main roots");
                focusedCandidate = task;
            }
            candidate = task;
            updateTaskWindowState(0, info, task.id, true);
        }
        if (focusedHome && focusedCandidate != null)
            throw new IllegalStateException("Home/app focus is changing; retain task bindings");
        if (focusedHome) return null;
        if (focusedCandidate != null) return focusedCandidate;
        if (visibleCandidates > 1) throw new IllegalStateException("ambiguous visible main roots");
        return candidate; // Home（type=2）不会被当主任务。
    }

    private LiveTask findPackageTask(String pkg) throws Exception {
        LiveTask candidate = null;
        for (Object info : rootInfos()) {
            if (!TaskUserPolicy.isCurrentUser(
                    XposedHelpers.getIntField(info, "userId"), currentUserId())) continue;
            ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            if (base == null || !pkg.equals(base.getPackageName())) continue;
            if (!ordinaryRoot(info, 1) || isSideToken(XposedHelpers.getObjectField(info, "token")))
                throw new IllegalStateException("existing package task has unsupported owner/mode");
            if (candidate != null) throw new IllegalStateException("multiple package tasks; no implicit relaunch");
            candidate = new LiveTask(info);
        }
        return candidate;
    }

    private LiveTask existingHomeTask() throws Exception {
        LiveTask home = null;
        for (Object info : rootInfos()) {
            if (!isHomeRoot(info)) continue;
            if (home != null) throw new IllegalStateException("ambiguous existing Home roots");
            home = new LiveTask(info);
        }
        if (home == null) throw new IllegalStateException("existing Home root unavailable");
        return home;
    }

    private boolean isHomeRoot(Object info) {
        return info != null && Integer.valueOf(2).equals(
                    XposedHelpers.callMethod(info, "getActivityType"))
                && XposedHelpers.getIntField(info, "displayId") == 0
                && XposedHelpers.getIntField(info, "userId") == currentUserId()
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"));
    }

    /** Reveal only the same Home that this app covered; never send a HOME launch intent. */
    private LiveTask existingHomeForCollection(LiveTask task) throws Exception {
        LiveTask home = existingHomeTask();
        HomeTaskReturn saved = coveredHome;
        if (saved != null && saved.covers(task.id, task.token)
                && !saved.restores(home.id, home.token))
            throw new IllegalStateException("covered Home identity changed; keep app in main");
        return home;
    }

    private Object homeForCollection(LiveTask task) throws Exception {
        return existingHomeForCollection(task).token;
    }

    private void adoptExisting(Slot slot, LiveTask task, Runnable success, Consumer<String> failure) {
        adoptExisting(slot, task, false, success, failure);
    }

    private void adoptExisting(Slot slot, LiveTask task, boolean collectFromMain,
            Runnable success, Consumer<String> failure) {
        if (!isActive(slot) || !boundsHooksReady || transferBusy || slot.frozen || slot.controller == null) {
            failure.accept("adoption busy/unavailable");
            return;
        }
        transferBusy = true;
        slot.promoting = true;
        slot.modeGuardUntil = SystemClock.uptimeMillis() + 2500L;
        slot.modeRepairAttempts = 0;
        slot.parked = false;
        slot.retiredTaskId = -1;
        prepareCellSurface(slot);
        final long epoch = sceneEpoch;
        if (!executeOnShell(() -> {
            boolean claimed = false;
            NativeObservation observation = null;
            try {
                Object core = rootInfo(task);
                if (core == null && absentFromShellAndController(slot, task)) {
                    main.post(() -> {
                        transferBusy = false;
                        slot.promoting = false;
                        if (slot.taskId == task.id && task.token.equals(slot.taskToken)) clearSlot(slot);
                        failure.accept("task confirmed gone from Core/Shell/controller");
                    });
                    return;
                }
                if (!ordinaryRoot(core, 1) || (collectFromMain && !XposedHelpers.getBooleanField(core, "isVisible")))
                    throw new IllegalStateException("required fullscreen source no longer available");
                Object controller = slot.controller;
                resetIdleTaskView(slot);
                if (XposedHelpers.getObjectField(controller, "mTaskToken") != null)
                    throw new IllegalStateException("destination controller is occupied");
                if (!Boolean.TRUE.equals(XposedHelpers.getObjectField(controller, "mSurfaceCreated"))
                        || getSurfaceField(controller, "mSurfaceControl") == null)
                    throw new IllegalStateException("destination surface unavailable");
                Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
                Object lock = XposedHelpers.getObjectField(organizer, "mLock");
                synchronized (lock) {
                    Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTasks")).get(task.id);
                    if (appeared == null) throw new IllegalStateException("canonical task not in existing organizer");
                    Object info = XposedHelpers.callMethod(appeared, "getTaskInfo");
                    Object canonical = XposedHelpers.callMethod(appeared, "getLeash");
                    Object fallback = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTaskListeners")).get(-2);
                    Object explicit = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTaskListeners")).get(task.id);
                    if (!task.matches(info) || !ordinaryRoot(info, 1) || explicit != null
                            || fallback == null || XposedHelpers.callMethod(organizer, "getTaskListener", info, false) != fallback
                            || canonical == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(canonical, "isValid")))
                        throw new IllegalStateException("canonical identity/fullscreen ownership gate rejected");
                    Object wct = Class.forName("android.window.WindowContainerTransaction").getDeclaredConstructor().newInstance();
                    XposedHelpers.callMethod(wct, "setWindowingMode", task.token, 6);
                    XposedHelpers.callMethod(wct, "setBounds", task.token, logicalBounds());
                    XposedHelpers.callMethod(wct, "setForceTranslucent", task.token, true);
                    XposedHelpers.callMethod(wct, "setAlwaysOnTop", task.token, true);
                    XposedHelpers.callMethod(wct, "setFocusable", task.token, true);
                    XposedHelpers.callMethod(wct, "setHidden", task.token, false);
                    if (collectFromMain) XposedHelpers.callMethod(wct, "reorder", homeForCollection(task), true);
                    XposedHelpers.callMethod(wct, "reorder", task.token, true);
                    // Existing listener is retired by the organizer, never globally replaced.
                    claimed = true;
                    XposedHelpers.callMethod(organizer, "addListenerForTaskId", controller, task.id);
                    observation = observeNativeCommit(task.token,
                            () -> checkAdoption(slot, task, epoch, 0, success, failure));
                    XposedHelpers.callMethod(transitions, "startRootTask", controller, info, canonical, wct);
                    log("adopt existing task=" + task.id + " into slot=" + slot.index + " logical=" + logicalBounds());
                }
            } catch (Throwable error) {
                cancelNativeObservation(observation);
                final boolean changed = claimed;
                main.post(() -> adoptionFailed(slot, task, changed, "adopt: " + error, failure));
            }
        })) adoptionFailed(slot, task, false, "no Shell queue", failure);
    }

    private void checkAdoption(Slot slot, LiveTask task, long epoch, int attempt,
            Runnable success, Consumer<String> failure) {
        if (!isActive(slot) || epoch != sceneEpoch) {
            adoptionFailed(slot, task, true, "scene changed during adoption", failure);
            return;
        }
        if (!executeOnShell(() -> {
            boolean ready = false;
            String detail;
            try {
                Object core = rootInfo(task);
                ready = ordinaryRoot(core, 6) && XposedHelpers.getBooleanField(core, "isVisible")
                        && controllerOwns(slot, task.id, task.token)
                        && getSurfaceField(slot.controller, "mTaskLeash") != null;
                detail = "core/controller/leash=" + ready;
            } catch (Throwable error) { detail = error.toString(); }
            final boolean confirmed = ready;
            final String result = detail;
            main.post(() -> {
                if (epoch != sceneEpoch) {
                    adoptionFailed(slot, task, true, "stale adoption completion", failure);
                } else if (confirmed) {
                    bindTask(slot, task.id, task.token);
                    slot.pkg = task.pkg;
                    slot.occupied = true;
                    slot.taskVisible = true;
                    slot.launching = false;
                    slot.windowMode = 6; // Confirmed from Core and this controller above.
                    slot.promoting = false;
                    slot.parked = false;
                    transferBusy = false;
                    updateHint(slot);
                    updateTouchRegion(slot);
                    reapplyScale(slot);
                    publishDesktopSideTasks();
                    log("adoption confirmed same task=" + task.id + " slot=" + slot.index);
                    success.run();
                    main.postDelayed(() -> {
                        compactCells();
                        // TaskView insertion can briefly hide sibling surfaces while the
                        // native transition settles. Re-show every already-owned cell
                        // without moving tasks or changing ownership.
                        main.postDelayed(() -> reassertSideTaskViews("adoption-settled"), 420L);
                    }, 120L);
                } else if (attempt < 2) {
                    main.postDelayed(() -> checkAdoption(slot, task, epoch, attempt + 1, success, failure), 500L);
                } else adoptionFailed(slot, task, true, result, failure);
            });
        })) adoptionFailed(slot, task, true, "no Shell queue for confirmation", failure);
    }

    /** A failed claim is handed back to the same native owner, never removed/restarted. */
    private void adoptionFailed(Slot slot, LiveTask task, boolean claimed, String error, Consumer<String> failure) {
        transferBusy = false;
        slot.launching = false;
        if (!claimed) {
            slot.promoting = slot.frozen;
            if (slot.occupied && slot.taskId == task.id && task.token.equals(slot.taskToken))
                slot.parked = true;
            updateHint(slot);
            failure.accept(error);
            return;
        }
        bindTask(slot, task.id, task.token);
        slot.pkg = task.pkg;
        slot.occupied = true;
        returnToSystem(slot, false, () -> {
            clearSlot(slot);
            failure.accept(error + "; handed back alive");
        }, rollback -> {
            // Do not clear a still-owned controller or let a later selection overwrite it.
            slot.frozen = true;
            slot.promoting = true;
            log("adoption rollback incomplete; frozen live task=" + task.id + ": " + rollback);
            failure.accept(error + "; binding frozen");
        });
    }

    private void returnToSystem(Slot slot, boolean front, Runnable success, Consumer<String> failure) {
        Promotion ticket = new Promotion(slot, new Slot[0]);
        ticket.background = !front;
        ticket.sceneEpoch = sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        slot.promoting = true;
        transferBusy = true;
        recycleBuffered(slot);
        if (!executeOnShell(() -> {
            NativeObservation observation = null;
            try {
                if (!returnTicketCurrent(ticket)) throw new IllegalStateException("stale return ticket");
                LiveTask identity = new LiveTask(ticket.taskId, ticket.token, slot.pkg);
                Object core = rootInfo(identity);
                if (core == null) {
                    if (!absentFromShellAndController(slot, identity))
                        throw new IllegalStateException("task missing from roots but still alive in Shell/controller");
                    main.post(() -> {
                        if (!returnTicketCurrent(ticket)) return;
                        transferBusy = false; clearSlot(slot); success.run();
                    });
                    return;
                }
                Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
                if (current == null && ordinaryRoot(core, 1)) {
                    main.post(() -> checkReturned(slot, ticket, 0, success, failure));
                    return;
                }
                if (!ticket.token.equals(current))
                    throw new IllegalStateException("return controller identity changed");
                observation = observeNativeCommit(ticket.token, () -> checkReturned(slot, ticket, 0, success, failure));
                if (!applyWct("return-live-task", fullscreenExitTransaction(ticket)))
                    throw new IllegalStateException("return transaction failed");
            } catch (Throwable error) {
                cancelNativeObservation(observation);
                main.post(() -> {
                    transferBusy = false;
                    slot.promoting = slot.frozen;
                    failure.accept(error.toString());
                });
            }
         })) { transferBusy = false; slot.promoting = slot.frozen; failure.accept("no Shell queue"); }
    }

    private boolean returnTicketCurrent(Promotion ticket) {
        Slot slot = ticket.slot;
        return isActive(slot) && sceneEpoch == ticket.sceneEpoch && slot.taskEpoch == ticket.bindingEpoch
                && slot.taskId == ticket.taskId && ticket.token.equals(slot.taskToken);
    }

    /** Root enumeration alone does not prove death: child/moved tasks remain in Shell. */
    private boolean absentFromShellAndController(Slot slot, LiveTask task) {
        Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
        synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
            Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(organizer, "mTasks")).get(task.id);
            if (appeared != null && task.matches(XposedHelpers.callMethod(appeared, "getTaskInfo"))) return false;
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            Object pending = XposedHelpers.getObjectField(slot.controller, "mPendingInfo");
            return !task.token.equals(current) && (pending == null || !task.matches(pending));
        }
    }

    private void checkReturned(Slot slot, Promotion ticket, int attempt, Runnable success, Consumer<String> failure) {
        if (!returnTicketCurrent(ticket)) {
            transferBusy = false;
            failure.accept("stale return ticket; no new binding touched");
            return;
        }
        if (!executeOnShell(() -> {
            boolean ready = false, gone = false;
            String detail;
            try {
                if (!returnTicketCurrent(ticket)) throw new IllegalStateException("stale return on Shell queue");
                LiveTask identity = new LiveTask(ticket.taskId, ticket.token, slot.pkg);
                Object core = rootInfo(identity);
                if (core == null) {
                    gone = absentFromShellAndController(slot, identity);
                    ready = gone;
                } else if (ordinaryRoot(core, 1)) {
                    handoffPromotedTask(ticket);
                    ready = ticket.handoffComplete
                            && XposedHelpers.getObjectField(slot.controller, "mTaskToken") == null;
                }
                detail = "native handback=" + ready + " gone=" + gone;
            } catch (Throwable error) { detail = error.toString(); }
            final boolean done = ready, removed = gone;
            final String result = detail;
            main.post(() -> {
                if (!returnTicketCurrent(ticket)) {
                    transferBusy = false;
                    failure.accept("stale return completion; no new binding touched");
                    return;
                }
                if (done) {
                    ownedTasks.remove(ticket.token);
                    if (removed) clearSlot(slot);
                    else {
                        slot.promoting = false;
                        slot.frozen = false;
                        slot.parked = true;
                        slot.taskVisible = false;
                        slot.scaleSurface = null;
                        slot.scaleTaskId = Integer.MIN_VALUE;
                        slot.scaleApplied = -1f;
                        updateHint(slot);
                    }
                    transferBusy = false;
                    removeCellBackground(slot);
                    log("returned task id=" + ticket.taskId + " gone=" + removed + " front=" + !ticket.background);
                    success.run();
                } else if (attempt < 2 && !ticket.handoffAttempted) {
                    main.postDelayed(() -> checkReturned(slot, ticket, attempt + 1, success, failure), 500L);
                } else {
                    transferBusy = false;
                    slot.frozen |= ticket.handoffAttempted;
                    slot.promoting = slot.frozen;
                    failure.accept(result);
                }
            });
        })) { transferBusy = false; failure.accept("no Shell queue for handback"); }
    }

    private static final class RenderBinding {
        final Slot slot;
        final int id;
        final Object token, leash, parent;
        final long scene, binding;
        RenderBinding(Slot slot, long scene, Object leash, Object parent) {
            this.slot = slot; id = slot.taskId; token = slot.taskToken;
            this.scene = scene; binding = slot.taskEpoch; this.leash = leash; this.parent = parent;
        }
    }

    private void installRetainedSurfaces(ClassLoader cl) {
        if (!surfaceHooksReady) {
            java.util.List<XC_MethodHook.Unhook> installed = new java.util.ArrayList<>();
            try {
                Class<?> tx = Class.forName("android.view.SurfaceControl$Transaction");
                installed.addAll(XposedBridge.hookAllMethods(tx, "merge", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        MiStepHost h = sInstance;
                        if (h != null && !p.hasThrowable() && p.args.length == 1) {
                            h.finishTransactions.merge(p.thisObject, p.args[0]);
                            h.workspaceFinishes.merge(p.thisObject, p.args[0]);
                            h.nativeObservations.merge(p.thisObject, p.args[0]);
                        }
                    }
                }));
                installed.addAll(XposedBridge.hookAllMethods(tx, "apply", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        MiStepHost h = sInstance;
                        if (h == null) return;
                        java.util.List<RenderBinding> frames = h.finishTransactions.take(p.thisObject);
                        if (!frames.isEmpty()) h.appendRetainedSurfaces(p.thisObject, frames);
                        h.appendWorkspaceSurfaces(p.thisObject, h.workspaceFinishes.take(p.thisObject));
                        java.util.List<NativeObservation> checks = h.nativeObservations.take(p.thisObject);
                        if (!checks.isEmpty()) {
                            try {
                                Executor uiExecutor = action -> h.main.post(action);
                                XposedHelpers.callMethod(p.thisObject, "addTransactionCommittedListener",
                                        uiExecutor, (Runnable) () -> { for (NativeObservation check : checks) check.run(); });
                            } catch (Throwable unavailable) {
                                log("commit signal unavailable; retain bounded observation watchdog");
                            }
                        }
                    }
                }));
                installed.addAll(XposedBridge.hookAllMethods(tx, "close", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        MiStepHost h = sInstance;
                        if (h != null) {
                            h.finishTransactions.forget(p.thisObject);
                            h.workspaceFinishes.forget(p.thisObject);
                            h.nativeObservations.forget(p.thisObject);
                        }
                    }
                }));
                if (installed.size() < 3) throw new IllegalStateException("missing finish transaction hooks");
                surfaceHooksReady = true;
            } catch (Throwable error) {
                for (XC_MethodHook.Unhook hook : installed) hook.unhook();
                log("retained surface hooks unavailable: " + error);
                return;
            }
        }
        executeOnShell(() -> {
            try {
                Class<?> iface = XposedHelpers.findClass(
                        "com.android.wm.shell.transition.Transitions$TransitionObserver", cl);
                surfaceObserver = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                        (self, method, args) -> {
                            try {
                                String name = method.getName();
                                if ("onTransitionReady".equals(name) && args != null && args.length == 4) {
                                    MiStepSwapTrace trace = swapTrace;
                                    if (trace != null) trace.transition(args[1], args[2], args[3]);
                                    trackRetainedSurfaces(args[1], args[3]);
                                    scheduleOverlayRefresh();
                                }
                                else if ("onTransitionMerged".equals(name)
                                        || "onTransitionFinished".equals(name)) traceSwapEvent(name);
                                else if ("hashCode".equals(name)) return System.identityHashCode(self);
                                else if ("equals".equals(name)) return self == args[0];
                                else if ("toString".equals(name)) return "MiStepRetainedSurfaceObserver";
                                // Target onTransitionFinished precedes finishT.apply: never restore here.
                            } catch (Throwable error) { log("surface observer stopped: " + error); }
                            return null;
                        });
                Object shellTransitions = XposedHelpers.getObjectField(transitions, "mTransitions");
                XposedHelpers.callMethod(shellTransitions, "registerObserver", surfaceObserver);
                log("retained surface observer registered; restore only in marked final apply");
            } catch (Throwable error) { log("surface observer unavailable: " + error); }
        });
    }

    private void trackRetainedSurfaces(Object info, Object finish) {
        NativeObservation observation = pendingNativeObservation;
        if (observation != null && !observation.consumed.get()) {
            java.util.List<?> observedChanges = (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges");
            for (Object change : observedChanges) {
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (task != null && observation.token.equals(XposedHelpers.getObjectField(task, "token"))) {
                    nativeObservations.mark(finish, java.util.Collections.singletonList(observation));
                    break;
                }
            }
        }
        if (!screenOn || (scene != ACTIVE && scene != RESUMING) || root == null) return;
        // A main activity's rotation/cleanup can reparent a previously adopted side
        // task even when that side token is absent from TransitionInfo. Resolve its
        // current canonical binding at the final apply, after native merges.
        workspaceFinishes.mark(finish, java.util.Collections.singletonList(sceneEpoch));
        boolean relevant = promotion != null || transferBusy;
        for (Slot slot : slots) if (slot.launching) relevant = true;
        java.util.List<?> changes = (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges");
        for (Object change : changes) {
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null) continue;
            Object token = XposedHelpers.getObjectField(task, "token");
            if (isSideToken(token)) relevant = true;
        }
        if (!relevant) return;
        java.util.List<RenderBinding> frames = new java.util.ArrayList<>();
        for (Slot slot : slots) {
            if (!canRender(slot) || !slot.occupied || slot.promoting || slot.launching || slot.frozen
                    || !mayWriteSideSurface(slot)
                    || !controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            Object leash = getSurfaceField(slot.controller, "mTaskLeash");
            Object parent = getSurfaceField(slot.controller, "mSurfaceControl");
            if (leash != null && parent != null && leash != parent)
                frames.add(new RenderBinding(slot, sceneEpoch, leash, parent));
        }
        if (!frames.isEmpty() && !finishTransactions.mark(finish, frames))
            log("retained finish transaction cap; do not retry or touch unrelated transactions");
    }

    /**
     * WindowManager reports this overlay visible throughout fixed-rotation launches,
     * but MIUI can leave its last buffer under the transition until the next redraw.
     * Refresh only around a transition; this does not resize or recreate the host.
     */
    private void scheduleOverlayRefresh() {
        if (overlayRefreshScheduled) return;
        overlayRefreshScheduled = true;
        main.postDelayed(() -> {
            overlayRefreshScheduled = false;
            refreshOverlayAfterTransition();
        }, 60L);
        main.postDelayed(this::refreshOverlayAfterTransition, 380L);
    }

    private void refreshOverlayAfterTransition() {
        if (scene != ACTIVE || root == null) return;
        for (Slot slot : slots) if (slot.closing) {
            main.postDelayed(this::refreshOverlayAfterTransition, 120L);
            return;
        }
        if (swapPreview != null || cellMovePreview != null || promotion != null
                || transferBusy) {
            // Relayouting the host window during the visible bridge brings its
            // SurfaceView children forward for one composition. Retry after handoff.
            main.postDelayed(this::refreshOverlayAfterTransition, 120L);
            return;
        }
        try {
            root.setVisibility(View.VISIBLE);
            root.setAlpha(1f);
            root.requestLayout();
            root.invalidate();
            if (root.getWindowToken() != null && wm != null && lp != null)
                wm.updateViewLayout(root, lp);
            log("overlay refreshed for transition");
        } catch (Throwable error) {
            log("overlay refresh failed: " + error);
        }
    }

    /** Append after native finish operations have merged, before the very same apply. */
    private void appendWorkspaceSurfaces(Object tx, java.util.List<Long> epochs) {
        if (!screenOn || (scene != ACTIVE && scene != RESUMING) || root == null
                || !epochs.contains(sceneEpoch)) return;
        java.util.List<RenderBinding> frames = new java.util.ArrayList<>();
        for (Slot slot : slots) {
            if (slot.index <= SLOT_MAIN || !canRender(slot) || !slot.occupied
                    || slot.parked || slot.promoting || slot.launching || slot.frozen
                    || !mayWriteSideSurface(slot)
                    || !controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
            Object leash = getSurfaceField(slot.controller, "mTaskLeash");
            Object parent = getSurfaceField(slot.controller, "mSurfaceControl");
            if (leash != null && parent != null && leash != parent)
                frames.add(new RenderBinding(slot, sceneEpoch, leash, parent));
        }
        appendRetainedSurfaces(tx, frames);
    }

    private void appendRetainedSurfaces(Object tx, java.util.List<RenderBinding> frames) {
        java.util.Set<Slot> restored = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int count = 0;
        for (RenderBinding frame : frames) {
            Slot slot = frame.slot;
            if (restored.contains(slot) || !canRender(slot) || slot.promoting || slot.launching || slot.frozen
                    || !mayWriteSideSurface(slot)
                    || frame.scene != sceneEpoch || frame.binding != slot.taskEpoch
                    || frame.id != slot.taskId || !frame.token.equals(slot.taskToken)
                    || !controllerOwns(slot, frame.id, frame.token)
                    || frame.leash != getSurfaceField(slot.controller, "mTaskLeash")
                    || frame.parent != getSurfaceField(slot.controller, "mSurfaceControl")) continue;
            try {
                // Same parent/crop/position/show contract as target TaskViewTransitions.updateSurface.
                XposedHelpers.callMethod(tx, "reparent", frame.leash, frame.parent);
                XposedHelpers.callMethod(tx, "setLayer", frame.leash, 0);
                XposedHelpers.callMethod(tx, "setPosition", frame.leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setWindowCrop", frame.leash, logicalBounds());
                float scale = scaleFor(slot);
                XposedHelpers.callMethod(tx, "setMatrix", frame.leash, scale, 0f, 0f, scale);
                XposedHelpers.callMethod(tx, "setAlpha", frame.leash, 1f);
                XposedHelpers.callMethod(tx, "show", frame.leash);
                restored.add(slot);
                count++;
            } catch (Throwable error) { log("retained surface skipped task=" + frame.id + ": " + error); }
        }
        if (count > 0) log("final transaction retained own side surfaces=" + count);
    }


    private void setZoomState(boolean on) {
        zoomed = on;
        HostEntry.setHostZoomed(on);
        MiStepStatusBarSampling.geometryChanged();
        HotZoneGesture.raiseAll(ctx);
    }

    /**
     * 写缩放触发文件（SystemUI 私有目录，system_server 侧轮询）。
     * 呼出/退出为 300ms 整屏缩放动画（system_server 侧 2.2.4+ 的 anim 命令）；
     * 动画结束后再写一次稳态续租，避免 4 秒租约把进行中的动画取消。
     */
    private static final long ZOOM_ANIM_MS = 300L;
    private android.animation.ValueAnimator workspaceAnimator;
    private long workspaceGeneration;
    private float workspaceAlpha = 1f;
    private boolean workspaceEntering;

    /** Wait for the real Host buffer/wallpaper before shrinking the existing main surface. */
    private void beginWorkspaceEntry(Runnable ready) {
        final long generation = ++workspaceGeneration;
        workspaceEntering = true;
        zoomAnimUntil = SystemClock.uptimeMillis() + 1000L;
        setWorkspaceAlpha(0f);
        awaitWorkspaceFrame(generation, 0, ready);
    }

    private void awaitWorkspaceFrame(long generation, int attempt, Runnable ready) {
        if (generation != workspaceGeneration || root == null || !screenOn
                || scene != ACTIVE && scene != RESUMING) return;
        if (wallpaperBackdrop == null && attempt < 24) {
            main.postDelayed(() -> awaitWorkspaceFrame(generation, attempt + 1, ready), 16L);
            return;
        }
        FrameLayout enteringRoot = root;
        Runnable start = () -> main.post(() -> {
            if (generation != workspaceGeneration || root != enteringRoot || !screenOn
                    || scene != ACTIVE && scene != RESUMING) return;
            writeZoomAnimated(true);
            setZoomState(true);
            scheduleMainCropClear(900L);
            playWorkspaceFade(true, generation, ready);
        });
        if (root.isAttachedToWindow() && root.isHardwareAccelerated()) {
            root.getViewTreeObserver().registerFrameCommitCallback(start);
            root.invalidate();
        } else start.run();
    }

    /** Fade Host controls and real TaskView surface parents; task buffers are never copied. */
    private void setWorkspaceAlpha(float value) {
        workspaceAlpha = Math.max(0f, Math.min(1f, value));
        if (topBar != null) topBar.setAlpha(workspaceAlpha);
        if (topStripBacking != null) topStripBacking.setAlpha(workspaceAlpha);
        Object tx = null;
        try {
            for (int i = 1; i < SLOT_COUNT; i++) {
                LiveSwapVisualLease.writeAlpha(sideBackingViews[i], workspaceAlpha);
                Slot slot = slots[i];
                if (slot == null) continue;
                LiveSwapVisualLease.writeAlpha(slot.hint, workspaceAlpha);
                Object surface = getSurfaceField(slot.controller, "mSurfaceControl");
                if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) continue;
                if (tx == null) tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(tx, "setAlpha", surface, workspaceAlpha);
            }
            if (tx != null) XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { log("workspace surface fade: " + error); }
        finally { closeSurfaceObject(tx, "close"); }
    }

    private void playWorkspaceFade(boolean entering, long generation, Runnable complete) {
        android.animation.ValueAnimator previous = workspaceAnimator;
        workspaceAnimator = null;
        if (previous != null) previous.cancel();
        final float from = entering ? 0f : workspaceAlpha;
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(from, entering ? 1f : 0f);
        animator.setDuration(ZOOM_ANIM_MS);
        animator.setInterpolator(new android.view.animation.PathInterpolator(.34f, .69f, .1f, 1f));
        animator.addUpdateListener(value -> {
            if (workspaceAnimator == animator && generation == workspaceGeneration)
                setWorkspaceAlpha((Float) value.getAnimatedValue());
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator value) {
                if (workspaceAnimator != animator || generation != workspaceGeneration) return;
                workspaceAnimator = null;
                if (entering) workspaceEntering = false;
                setWorkspaceAlpha(entering ? 1f : 0f);
                if (complete != null) complete.run();
            }
        });
        workspaceAnimator = animator;
        animator.start();
    }
    /** 动画期间宿主不得写普通稳态（会取消动画）；心跳按此时间窗跳过。 */
    private volatile long zoomAnimUntil;
    private long zoomCommandGeneration;

    private String zoomOnCommand() {
        return layout.mainScale + " " + layout.mainOffsetY + " " + layout.mainOffsetX;
    }

    private void writeZoomAnimated(boolean on) {
        final long generation = ++zoomCommandGeneration;
        final String steady = on ? zoomOnCommand() : "1 0 0";
        zoomAnimUntil = android.os.SystemClock.uptimeMillis() + ZOOM_ANIM_MS + 250L;
        writeZoomLine("anim " + steady + " " + ZOOM_ANIM_MS);
        main.postDelayed(() -> {
            if (!ZoomLifecyclePolicy.shouldApplyDelayedWrite(generation,
                    zoomCommandGeneration, on, zoomed)) {
                log("stale zoom animation completion ignored generation=" + generation);
                return;
            }
            writeZoomLine(steady);
        }, ZOOM_ANIM_MS + 150L);
    }

    private void writeZoom(boolean on) {
        zoomCommandGeneration++;
        writeZoomLine(on ? zoomOnCommand() : "1 0 0");
    }

    private void writeZoomLine(String command) {
        writeZoomLine(command, System.currentTimeMillis());
    }

    private void writeZoomLine(String command, long issuedAt) {
        // Independent transport: a failed private-file write must not suppress zoom/restore.
        broadcastZoom(command, issuedAt);
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), "mistep-zoom");
            android.util.AtomicFile file = new android.util.AtomicFile(f);
            java.io.FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                stream.write(command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                file.finishWrite(stream);
                // system_server 需要读这个文件来应用单手模式缩放；AtomicFile 默认 0600，必须放开读权限。
                if (!f.setReadable(true, false)) {
                    log("writeZoom: setReadable(false) returned false");
                }
            } catch (java.io.IOException failure) {
                if (stream != null) file.failWrite(stream);
                throw failure;
            }
            log("zoom trigger written: " + command);
        } catch (Throwable t) {
            log("writeZoom failed: " + t);
        }
    }


    /** 广播直投：绕过 system_server 读 SystemUI 私有文件的权限/SELinux 限制。 */
    private void broadcastZoom(String command, long issuedAt) {
        try {
            String encoded = ZoomChannelState.encode(command, issuedAt);
            String steady = command.trim();
            if (steady.startsWith("anim")) {
                String[] parts = steady.split("\\s+");
                if (parts.length < 4) return;
                steady = parts[1] + " " + parts[2] + " " + parts[3];
            }
            boolean changed = !command.equals(lastBroadcastZoom);
            try {
                // A heartbeat renews the timestamp too; rereading Settings must not renew it.
                if (!android.provider.Settings.Global.putString(ctx.getContentResolver(), "mistep_zoom", encoded)) {
                    log("zoom settings write returned false");
                }
            } catch (Throwable settingsError) {
                log("zoom settings write failed: " + settingsError);
            }
            String[] parts = steady.split("\\s+");
            float scale = Float.parseFloat(parts[0]);
            float offsetY = Float.parseFloat(parts[1]);
            float offsetX = Float.parseFloat(parts[2]);
            android.content.Intent intent = new android.content.Intent("com.jianguo.mistep.ZOOM");
            intent.putExtra("request", encoded);
            intent.putExtra("scale", scale);
            intent.putExtra("offsetY", offsetY);
            intent.putExtra("offsetX", offsetX);
            intent.addFlags(android.content.Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            ctx.sendBroadcast(intent);
            if (changed) log("zoom broadcast sent: " + command);
            lastBroadcastZoom = command;
        } catch (Throwable error) {
            log("zoom broadcast failed: " + error);
        }
    }
    private void startInternal() {
        main.post(() -> {
            if (!MiStepEntryGuard.canOpen(ctx)) {
                if (sInstance == this && root == null) sInstance = null;
                log("queued host start rejected while locked");
                return;
            }
            try {
                registerDesktopIconReceiver();
                buildLayout();
                createSubViews();
                registerWallpaperReceiver();
                main.postDelayed(this::collapseStatusBarPanels, 220L);
        main.postDelayed(this::loadWallpaperBackdrop, 250L);
            } catch (Throwable t) {
                log("startInternal FAILED: " + t);
                XposedBridge.log(t);
            }
        });
    }

    private void buildLayout() {
        readDisplayGeometry();
        int w = screenW;
        int h = screenH;
        int pendingSide = pendingMainSide;
        if (pendingSide >= 0) {
            pendingMainSide = -1;
            mainOnRight = pendingSide == 1;
            log("layout mainOnRight from gesture edge=" + mainOnRight);
        }
        log("device: " + DeviceProfile.describe(ctx));
        float layoutDensity = ctx.getResources().getDisplayMetrics().density;
        layout = MiStepLayout.of(w, h, mainOnRight, layoutDensity);
        log("layout: " + layout.describe()
                + " mainScale=" + mainScale() + " cellScale=" + cellScale());

        root = new FrameLayout(ctx) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                if (transitionInput != null && transitionInput.consume(event.getActionMasked())) return true;
                return super.dispatchTouchEvent(event);
            }
        };
        transitionInput = new MiStepTransitionInput(root, this::acquireTransitionInputWindow, this::releaseTransitionInputWindow, blocked -> {
            liveSwapInputBlocked = blocked;
            for (Slot slot : slots) if (slot != null && slot.index > 0) updateTouchRegion(slot);
            if (root != null) { root.requestLayout(); root.invalidate(); }
        }, MiStepHost::log);
        root.setClipChildren(false);
        root.setBackgroundColor(0x00000000);
        installHostTouchRegion();

        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("MiStepHost");
        HostWindowPolicy.configure(lp);
        // APPLICATION_OVERLAY 默认被状态栏/cutout 顶下去（真机 122px）。两者都关掉后
        // 窗口坐标系才等于屏幕坐标系（[0,0]-[1080,2460]）。
        try {
            lp.setFitInsetsTypes(0);
            log("fitInsetsTypes=0 applied");
        } catch (Throwable t) {
            log("setFitInsetsTypes failed: " + t);
        }
        try {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            log("cutoutMode=ALWAYS applied");
        } catch (Throwable t) {
            log("cutout mode failed: " + t);
        }
        wm.addView(root, lp);
        registerDefaultDisplayListener();
        createMirrorLayer();
        log("host 2.9.41 HyperOS glass added " + w + "x" + h
                + " privateFlags=0x" + Integer.toHexString(
                        XposedHelpers.getIntField(lp, "privateFlags")));
        // v1.3.3：呼出Mi Step = 整块屏缩进主区（写缩放触发文件，system_server 轮询执行）。
        main.postDelayed(this::collapseStatusBarPanels, 220L);
        // Host-owned toolbar and app strip, matching the Mi Step 3.0 interaction surface.
        try {
            java.util.List<QuickApp> quickApps;
            try {
                quickApps = MiStepWarmup.quickApps(ctx);
            } catch (Throwable error) {
                quickApps = java.util.Collections.emptyList();
                log("top app strip unavailable: " + error);
            }
            topBar = new MiStepTopBar(ctx, layout, quickApps, new MiStepTopBar.Listener() {
                @Override public void onOpenSettings() { openSettingsInMain(); }
                @Override public void onOpenClipboard() {
                    if (scene == ACTIVE && promotion == null && !transferBusy)
                        topBar.toggleClipboard(wallpaperBackdrop, screenW, screenH);
                }
                @Override public void onToggleTorch() {
                    if (scene == ACTIVE && promotion == null && !transferBusy)
                        MiStepSystemActions.toggleTorch(ctx);
                }
                @Override public void onScreenshot() {
                    if (scene == ACTIVE && promotion == null && !transferBusy)
                        MiStepSystemActions.takeScreenshot(ctx);
                }
                @Override public void onScreenRecord() {
                    if (scene == ACTIVE && promotion == null && !transferBusy)
                        MiStepSystemActions.toggleScreenRecord(ctx);
                }
                @Override public void onExit() {
                    log("top bar exit tapped");
                    toggleVisibility();
                }
                @Override public void onLaunch(QuickApp entry) { launchQuick(entry); }
                @Override public void onDragStart(MotionEvent event) { beginWindowDrag(event); }
                @Override public void onDragMove(MotionEvent event) { updateWindowDrag(event); }
                @Override public void onDragEnd(MotionEvent event) { finishWindowDrag(event); }
            });
            refreshQuickApps();
            topStripBacking = new View(ctx);
            topStripBacking.setBackgroundColor(0xFF1E2420);
            root.addView(topStripBacking, new FrameLayout.LayoutParams(-1, layout.topStrip));
            root.addView(topBar, new FrameLayout.LayoutParams(-1, layout.topStrip));
            topBar.setStatusBarInset(DeviceProfile.statusBarHeightPx(ctx));
            main.post(this::applyWallpaperBackdrops);
            for (int i = 1; i < SLOT_COUNT; i++) {
                View backing = new View(ctx);
                backing.setBackgroundColor(HyperOsStyle.page(ctx));
                FrameLayout.LayoutParams rlp =
                        new FrameLayout.LayoutParams(layout.sideWidth, sideSegmentHeight(i));
                rlp.leftMargin = layout.mainOnRight ? 0 : layout.mainWidth;
                rlp.topMargin = sideSegmentTop(i);
                backing.setLayoutParams(rlp);
                root.addView(backing);
                sideBackingViews[i] = backing;
            }
            log("Mi Step top bar ready shortcuts=" + quickApps.size());
        } catch (Throwable t) {
            log("top bar add failed: " + t);
        }
        root.post(this::updateTopBarState);
        loadWallpaperBackdrop();
        beginWorkspaceEntry(null);
        // Keep corner activation disabled while active; HyperOS GestureStub owns back gestures.
        HotZoneGesture.raiseAll(ctx);
        // v0.9.9：心跳保证热区始终在位上（面板关闭路径异常时也能自动恢复，否则会卡在"撤下"）。
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (root == null || sInstance != MiStepHost.this) {
                    return;
                }
                if (scene != ACTIVE || !screenOn || root.getVisibility() != View.VISIBLE) {
                    main.postDelayed(this, 4000L);
                    return;
                }
                if (layout != null && !holdsLiveFlipZoom()
                        && android.os.SystemClock.uptimeMillis() >= zoomAnimUntil) {
                    writeZoom(true);
                }
                if (carrierSlotIndex >= 0) {
                    reassertCarrier();
                }
                if (!zoomed && !HotZoneGesture.isAttached()) {
                    log("hotzone heartbeat: re-raise");
                    HotZoneGesture.raiseAll(ctx);
                }
                // v1.1.3：转场可能覆盖 leash 的位置/矩阵（"只显示一部分"），心跳里周期性重发。
                for (Slot s : slots) {
                    if (holdsLiveFlipZoom()) continue;
                    if (s.carrier || s.index == leashPocSlot) {
                        continue;
                    }
                    ensureCellBackground(s);
                    if (!s.occupied || s.promoting) {
                        continue;
                    }
                    reapplyScale(s);
                    if (!s.taskVisible) scheduleSideVisibilityRepair(s);
                }
                main.postDelayed(this, 4000L);
            }
        }, 4000L);
    }

    private void createSubViews() {
        createNext(1);
    }

    private void createNext(final int index) {
        if (index >= SLOT_COUNT) {
            log("three side TaskViews ready; system main region has no TaskView");
            return;
        }
        Consumer<Object> onCreated = new Consumer<Object>() {
            @Override
            public void accept(Object taskViewObj) {
                log("TaskView created slot=" + index + " -> " + taskViewObj);
                main.post(() -> onTaskViewCreated(index, taskViewObj));
            }
        };
        XposedHelpers.callMethod(factory, "create", ctx, executor, onCreated);
    }

    private void onTaskViewCreated(int index, Object taskViewObj) {
        try {
            Slot slot = slots[index];
            View view = (View) taskViewObj;
            slot.view = view;
            slot.controller = XposedHelpers.callMethod(view, "getController");
            slot.rect = layout.cellBounds(index - 1);

            Object listener = makeListener(index);
            XposedHelpers.callMethod(view, "setListener", executor, listener);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
            if (!boundsHooksReady) throw new IllegalStateException("logical bounds hooks unavailable");
            root.addView(view, lp);
            applyRect(slot);
            ensureCellBackground(slot);

            slot.hint = makeEmptyHint();
            slot.hint.setOnClickListener(v -> onSlotTapped(slot.index));
            root.addView(slot.hint, hintParams(slot));
            // 修：hint 在 applyRect 之后才创建，之前一直停在 (0,0)（屏幕上左上角那个多余的加号）。
            applyRect(slot);
            updateHint(slot);

            if (workspaceAlpha < 1f) setWorkspaceAlpha(workspaceAlpha);

            view.setOnClickListener(v -> onSlotTapped(slotIndexOfView(v)));
            view.setOnTouchListener((v, e) -> canRender(slot) && scene == ACTIVE && onSlotTouch(slot.index, e));
            updateTouchRegion(slot);
            log("slot " + index + " attached rect=" + java.util.Arrays.toString(slot.rect));
        } catch (Throwable t) {
            log("onTaskViewCreated slot=" + index + " FAILED: " + t);
            XposedBridge.log(t);
        }
        createNext(index + 1);
    }

    private TextView makeEmptyHint() {
        TextView tv = new TextView(ctx) {
            private final android.graphics.Paint plus = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                if (length() != 0) return;
                drawEmptyCellPlus(canvas, getWidth(), getHeight(), getCurrentTextColor(), plus);
            }
        };
        // SurfaceView clears a hole through the host. Paint above that hole while empty.
        tv.setBackground(HyperOsStyle.emptyCell(ctx));
        tv.setText("");
        tv.setTextColor(HyperOsStyle.textSecondary(ctx));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 38f);
        tv.setTypeface(android.graphics.Typeface.create(
                "sans-serif-light", android.graphics.Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setContentDescription("空白窗口，点按添加应用");
        return tv;
    }

    private void drawEmptyCellPlus(android.graphics.Canvas canvas, int width, int height,
            int color, android.graphics.Paint plus) {
        plus.setColor(color);
        plus.setStrokeWidth(HyperOsStyle.dp(ctx, 1.25f));
        plus.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        float x = width * .5f, y = height * .5f;
        float radius = HyperOsStyle.dp(ctx, 10f);
        canvas.drawLine(x - radius, y, x + radius, y, plus);
        canvas.drawLine(x, y - radius, x, y + radius, plus);
    }

    private FrameLayout.LayoutParams hintParams(Slot slot) {
        return new FrameLayout.LayoutParams(
                slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
    }

    /** 空格子显示"+"提示；有任务时隐藏。 */
    private void updateHint(Slot slot) {
        ensureCellBackground(slot);
        updateCellSurfaceVisibility(slot);
        if (slot.hint == null) {
            return;
        }
        if (slot.hint instanceof TextView) {
            TextView label = (TextView) slot.hint;
            label.setText(slot.parked ? "恢复" : "");
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    slot.parked ? 15f : 38f);
            label.setTextColor(slot.parked
                    ? HyperOsStyle.accent(ctx) : HyperOsStyle.textSecondary(ctx));
            label.setContentDescription(slot.parked
                    ? "已保留的窗口，点按恢复" : "空白窗口，点按添加应用");
        }
        slot.hint.setVisibility(slot.occupied && !slot.parked ? View.GONE : View.VISIBLE);
    }

    /** An empty TaskView keeps its last resize background; hide that container, not a screenshot. */
    private void updateCellSurfaceVisibility(Slot slot) {
        // Shell owns the borrowed task parents until the whole handoff finishes.
        // clearSlot/bindTask is only a logical replacement during a native swap;
        // an independent hide here would blank the just-committed real task.
        if (slot.promoting || slot.launching || cellMotion != null
                || (promotion != null && promotion.nativeSwap && !promotion.cancelled)) return;
        boolean visible = slot.occupied && !slot.parked && slot.taskToken != null
                && controllerOwns(slot, slot.taskId, slot.taskToken);
        // An empty native container can be re-shown by SurfaceView's next position frame.
        // Its RenderNode must also be transparent; keep its surface alive for the next launch.
        if (slot.view != null) slot.view.setAlpha(visible ? 1f : 0f);
        Object surface = getSurfaceField(slot.controller, "mSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(tx, "setAlpha", surface, visible ? workspaceAlpha : 0f);
            XposedHelpers.callMethod(tx, visible ? "show" : "hide", surface);
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { log("cell container visibility: " + error); }
        finally { closeSurfaceObject(tx, "close"); }
    }

    /** Open the destination hole while its opaque empty hint still covers it.
     * Native preparation owns show/hide; only make its eventual parent nontransparent.
     */
    private void prepareCellSurface(Slot slot) {
        if (slot.view != null) slot.view.setAlpha(1f);
        Object surface = getSurfaceField(slot.controller, "mSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(tx, "setAlpha", surface, workspaceAlpha);
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { log("cell destination preparation: " + error); }
        finally { closeSurfaceObject(tx, "close"); }
    }

    /** A host-owned backing stays inside the cell, below the real task, even for translucent apps. */
    private void ensureCellBackground(Slot slot) {
        if (wallpaperBackdrop != null) applyWallpaperBackdrops();
        if (!slot.occupied || slot.parked) {
            removeCellBackground(slot);
            return;
        }
        if (root == null || slot.index <= SLOT_MAIN || slot.controller == null || slot.rect == null) {
            return;
        }
        Object parent = getSurfaceField(slot.controller, "mSurfaceControl");
        if (parent == null) return;
        int width = slot.rect[2] - slot.rect[0];
        int height = slot.rect[3] - slot.rect[1];
        if (parent == slot.cellBackgroundParent && width == slot.cellBackgroundWidth
                && height == slot.cellBackgroundHeight) return;
        removeCellBackground(slot);
        // Remember a failed attempt too: no allocation/log loop for the same surface generation.
        slot.cellBackgroundParent = parent;
        slot.cellBackgroundWidth = width;
        slot.cellBackgroundHeight = height;
        Object background = null;
        Object transaction = null;
        try {
            Object builder = Class.forName("android.view.SurfaceControl$Builder")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(builder, "setName", "MiStepCellBacking" + slot.index);
            XposedHelpers.callMethod(builder, "setColorLayer");
            XposedHelpers.callMethod(builder, "setParent", parent);
            background = XposedHelpers.callMethod(builder, "build");
            transaction = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            int page = HyperOsStyle.page(ctx);
            XposedHelpers.callMethod(transaction, "setColor", background,
                    new float[]{android.graphics.Color.red(page) / 255f,
                            android.graphics.Color.green(page) / 255f,
                            android.graphics.Color.blue(page) / 255f});
            XposedHelpers.callMethod(transaction, "setWindowCrop", background,
                    new Rect(0, 0, width, height));
            XposedHelpers.callMethod(transaction, "setLayer", background, -1);
            XposedHelpers.callMethod(transaction, "show", background);
            XposedHelpers.callMethod(transaction, "apply");
            slot.cellBackground = background;
            background = null; // The slot owns only the newly created backing, never the parent.
            log("cell backing ready slot=" + slot.index);
        } catch (Throwable failure) {
            log("cell backing failed slot=" + slot.index + ": " + failure);
        } finally {
            closeSurfaceObject(transaction, "close");
            if (background != null) destroyCellSurface(background);
        }
    }

    private void registerDefaultDisplayListener() {
        if (displayListener != null) return;
        try {
            displayManager = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) throw new IllegalStateException("DisplayManager unavailable");
            displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) scheduleDisplayGeometryRefresh();
                }
            };
            displayManager.registerDisplayListener(displayListener, main);
            log("default display geometry listener registered");
        } catch (Throwable error) {
            displayListener = null;
            log("default display geometry listener unavailable: " + error);
        }
    }

    private void readDisplayGeometry() {
        android.util.DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        try {
            if (displayManager == null) {
                displayManager = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            }
            Display display = displayManager == null
                    ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                Point realSize = new Point();
                display.getRealSize(realSize);
                if (realSize.x > 0 && realSize.y > 0) {
                    width = realSize.x;
                    height = realSize.y;
                }
                displayRotation = display.getRotation();
            }
        } catch (Throwable error) {
            log("display geometry read fell back to resources: " + error);
        }
        if (width > 0 && height > 0) {
            // Mi Step always has one portrait workspace. A task's fixed rotation or
            // a pending display transition must not rebuild it as a landscape scene.
            screenW = FullscreenRotationPolicy.workspaceWidth(width, height);
            screenH = FullscreenRotationPolicy.workspaceHeight(width, height);
        }
    }

    private void scheduleDisplayGeometryRefresh() {
        if (geometryRefreshQueued) return;
        geometryRefreshQueued = true;
        main.postDelayed(() -> {
            geometryRefreshQueued = false;
            refreshDisplayGeometry();
        }, 120L);
    }

    private void refreshDisplayGeometry() {
        if (root == null || displayManager == null) return;
        if (cellMotion != null || promotion != null && promotion.nativeSwap) {
            int oldWidth = screenW;
            int oldHeight = screenH;
            int oldRotation = displayRotation;
            readDisplayGeometry();
            int newWidth = screenW;
            int newHeight = screenH;
            int newRotation = displayRotation;
            // Display notifications also report non-geometric changes. Keep the
            // in-flight coordinate space intact until its finish or rollback.
            screenW = oldWidth;
            screenH = oldHeight;
            displayRotation = oldRotation;
            if (oldWidth == newWidth && oldHeight == newHeight && oldRotation == newRotation) {
                if (cellMotion != null) log("unchanged display geometry retains real cell motion");
                return;
            }
            if (cellMotion != null) {
                cellMotion.finish();
                scheduleDisplayGeometryRefresh();
                return;
            }
            geometryRefreshAfterLive = true;
            failPromotion(promotion, "display geometry changed during live swap "
                    + oldWidth + "x" + oldHeight + " r" + oldRotation
                    + " -> " + newWidth + "x" + newHeight + " r" + newRotation);
            return;
        }
        if (transferBusy || swapPreview != null || cellMovePreview != null) {
            if (geometryRefreshDeferrals++ < 8) {
                scheduleDisplayGeometryRefresh();
                return;
            }
        }
        geometryRefreshDeferrals = 0;
        int oldWidth = screenW;
        int oldHeight = screenH;
        int oldRotation = displayRotation;
        readDisplayGeometry();
        if (NativeLandscapePolicy.shouldRestoreFromDisplay(
                landscapeSuspended, scene == SUSPENDED, displayRotation)) {
            log("restore workspace after native landscape rotation=" + displayRotation);
            resumeSession();
            return;
        }
        if (displayRotation != FullscreenRotationPolicy.portraitDisplayRotation()) {
            if (oldRotation != displayRotation) {
                log("workspace retains portrait geometry during display rotation "
                        + displayRotation + "; waiting for WM portrait configuration");
            }
            return;
        }
        if ((oldWidth == screenW && oldHeight == screenH && oldRotation == displayRotation)
                || screenW <= 0 || screenH <= 0) return;

        layout = makeAdaptiveLayout(mainOnRight);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, layout.topStrip);
        if (topStripBacking != null) topStripBacking.setLayoutParams(topParams);
        if (topBar != null) {
            topBar.setLayoutParams(new FrameLayout.LayoutParams(-1, layout.topStrip));
            topBar.setStatusBarInset(DeviceProfile.statusBarHeightPx(ctx));
        }
        for (int i = 1; i < SLOT_COUNT; i++) {
            Slot slot = slots[i];
            slot.rect = layout.cellBounds(i - 1);
            applyRect(slot);
            updateTouchRegion(slot);
            updateHint(slot);
            View backing = sideBackingViews[i];
            if (backing != null) {
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        layout.sideWidth, sideSegmentHeight(i));
                params.leftMargin = layout.mainOnRight ? 0 : layout.mainWidth;
                params.topMargin = sideSegmentTop(i);
                backing.setLayoutParams(params);
            }
            if (slot.carrierTapView != null) {
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
                params.leftMargin = slot.rect[0];
                params.topMargin = slot.rect[1];
                slot.carrierTapView.setLayoutParams(params);
            }
        }
        if (carrierBackdrop != null) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    layout.mainDispWidth, layout.mainDispHeight);
            params.leftMargin = layout.mainOffsetX;
            params.topMargin = layout.mainOffsetY;
            carrierBackdrop.setLayoutParams(params);
        }
        applyWallpaperBackdrops();
        if (carrierSlotIndex >= 0) reassertCarrier();
        if (wm != null && lp != null) {
            try { wm.updateViewLayout(root, lp); }
            catch (Throwable error) { log("host window resize refresh failed: " + error); }
        }
        root.requestLayout();
        root.invalidate();
        boolean activeRoot = ZoomLifecyclePolicy.shouldReassertGeometry(
                scene == ACTIVE, root.getVisibility() == View.VISIBLE, screenOn);
        if (activeRoot) writeZoom(true);
        else log("display geometry changed while host inactive; skip zoom/task reassert");
        boolean queued = activeRoot && executeOnShell(() -> {
            if (scene != ACTIVE) return;
            for (Slot slot : slots) {
                if (slot.index > SLOT_MAIN && slot.occupied && !slot.carrier
                        && !slot.promoting && !slot.launching) applyFullscreenBounds(slot);
            }
        });
        main.postDelayed(() -> {
            if (!ZoomLifecyclePolicy.shouldReassertGeometry(scene == ACTIVE,
                    root != null && root.getVisibility() == View.VISIBLE, screenOn)) return;
            for (Slot slot : slots) {
                if (slot.index > SLOT_MAIN && slot.occupied && !slot.carrier) reapplyScale(slot);
            }
        }, queued ? 180L : 0L);
        log("display geometry changed " + oldWidth + "x" + oldHeight + " r" + oldRotation
                + " -> " + screenW + "x" + screenH + " r" + displayRotation
                + " queuedTaskBounds=" + queued + " " + layout.describe());
    }

    private void refreshQuickApps() {
        if (topBar == null || root == null) return;
        final MiStepTopBar readyBar = topBar;
        final FrameLayout readyRoot = root;
        final long readyEpoch = sceneEpoch;
        MiStepWarmup.whenQuickAppsReady(ctx, apps -> {
            if (sInstance == this && topBar == readyBar && root == readyRoot
                && sceneEpoch == readyEpoch && screenOn && !keyguardLocked)
                readyBar.setQuickApps(apps);
        });
    }

    private void updateTopBarState() {
        if (workspaceAnimator != null || workspaceAlpha < 1f) return;
        boolean available = scene == ACTIVE && screenOn && !keyguardLocked
                && promotion == null && !transferBusy;
        if (topBar != null) topBar.setAlpha(available ? 1f : 0.92f);
    }

    private void enterKeyguardPrivacy() {
        keyguardLocked = true;
        publishDesktopSideTasks();
        statusBar.restore();
        hideIme();
        updateTopBarState();
        if (root == null) return;
        if (keyguardMask == null) {
            View mask = new View(ctx);
            mask.setBackgroundColor(0xFF000000);
            mask.setElevation(2200f);
            root.addView(mask, new FrameLayout.LayoutParams(-1, -1));
            keyguardMask = mask;
        }
        root.requestLayout();
        log("keyguard privacy shown; retain Mi Step display zoom");
    }

    private void refreshKeyguardPrivacy() {
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                enterKeyguardPrivacy();
                return;
            }
        } catch (Throwable error) {
            log("keyguard state read failed: " + error);
        }
        leaveKeyguardPrivacy();
    }

    private void leaveKeyguardPrivacy() {
        keyguardLocked = false;
        publishDesktopSideTasks();
        if (keyguardMask != null && root != null) {
            root.removeView(keyguardMask);
            keyguardMask = null;
            root.requestLayout();
        }
        updateTopBarState();
        if (scene == ACTIVE && screenOn) {
            for (Slot slot : slots) {
                if (slot.occupied && !slot.promoting) {
                    reapplyScale(slot);
                    scheduleSideVisibilityRepair(slot);
                }
            }
        }
        log("keyguard privacy cleared");
    }


    /** 只取桌面壁纸做高斯模糊底（不使用应用内容），供顶栏/侧栏格子的液态玻璃使用。 */
    private void loadWallpaperBackdrop() {
        if (wallpaperBackdropRequested) return;
        if (wallpaperBackdrop != null && !wallpaperBackdrop.isRecycled()) {
            applyWallpaperBackdrops();
            return;
        }
        if (screenW <= 0 || screenH <= 0) return;
        wallpaperBackdropRequested = true;
        Thread loader = new Thread(() -> {
            Bitmap ready = null;
            try {
                ready = MiStepWarmup.wallpaper(ctx, screenW, screenH);
            } catch (Throwable error) {
                log("wallpaper blur failed: " + error);
            }
            final Bitmap bitmap = ready;
            main.post(() -> {
                wallpaperBackdropRequested = false;
                if (bitmap == null || bitmap.isRecycled()) {
                    log("wallpaper blur unavailable");
                    return;
                }
                if (wallpaperBackdrop != null) {
                    MiStepClipboard.get(ctx).close();
                    wallpaperBackdrop.recycle();
                }
                wallpaperBackdrop = bitmap;
                log("wallpaper gaussian backdrop ready " + bitmap.getWidth() + "x" + bitmap.getHeight());
                applyWallpaperBackdrops();
            });
        }, "MiStepWallpaperBlur");
        loader.start();
    }

    private void applyWallpaperBackdrops() {
        if (wallpaperBackdrop == null || wallpaperBackdrop.isRecycled() || layout == null) return;
        if (topBar != null) topBar.setWallpaperBackdrop(wallpaperBackdrop, screenW, screenH);
        if (topStripBacking != null) {
            topStripBacking.setBackground(new WallpaperCropDrawable(wallpaperBackdrop, screenW, screenH,
                    0, 0, HyperOsStyle.topWallpaperTint(ctx)));
        }
        int x = layout.mainOnRight ? 0 : layout.mainWidth;
        for (int i = 1; i < SLOT_COUNT; i++) {
            View backing = sideBackingViews[i];
            if (backing == null) continue;
            backing.setBackground(new WallpaperCropDrawable(wallpaperBackdrop, screenW, screenH,
                    x, sideSegmentTop(i), HyperOsStyle.topWallpaperTint(ctx)));
            Slot slot = slots[i];
            if (slot == null || slot.hint == null || slot.occupied || slot.rect == null) continue;
            slot.hint.setBackground(new WallpaperCropDrawable(wallpaperBackdrop, screenW, screenH,
                    slot.rect[0], slot.rect[1], HyperOsStyle.topWallpaperTint(ctx)));
        }
    }

    /** 角部滑动起手在状态栏区域时 MIUI 通知栏/控制中心会被顺带拉出并盖住主区；打开一步后主动收起。 */
    private void collapseStatusBarPanels() {
        try {
            Object sbm = ctx.getSystemService(Context.STATUS_BAR_SERVICE);
            if (sbm == null) return;
            java.lang.reflect.Method method = sbm.getClass().getMethod("collapsePanels");
            method.invoke(sbm);
            log("status bar panels collapsed");
        } catch (Throwable error) {
            log("collapse status bar panels failed: " + error);
        }
    }

    /** 换壁纸后自动重算模糊底，避免一步里还是旧壁纸。 */
    private void registerWallpaperReceiver() {
        if (wallpaperReceiver != null) return;
        try {
            wallpaperReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    log("wallpaper changed; reloading blur backdrop");
                    MiStepClipboard.get(ctx).close();
                    if (wallpaperBackdrop != null) {
                        wallpaperBackdrop.recycle();
                        wallpaperBackdrop = null;
                    }
                    wallpaperBackdropRequested = false;
                    loadWallpaperBackdrop();
                }
            };
            ctx.registerReceiver(wallpaperReceiver, new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED));
            log("wallpaper change receiver registered");
        } catch (Throwable error) {
            log("wallpaper receiver failed: " + error);
        }
    }

    private void registerDesktopIconReceiver() {
        if (desktopIconReceiver != null) return;
        try {
            desktopIconReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent != null && intent.getBooleanExtra("before", false)) {
                        preannounceDesktopLaunch(intent.getBundleExtra("binding"));
                        return;
                    }
                    String pkg = intent == null ? null : intent.getStringExtra("package");
                    promoteSideTaskForDesktopLaunch(pkg);
                }
            };
            IntentFilter filter = new IntentFilter(DESKTOP_ICON_LAUNCH_ACTION);
            ctx.registerReceiver(desktopIconReceiver, filter,
                    "android.permission.MANAGE_ACTIVITY_TASKS", main,
                    Context.RECEIVER_EXPORTED);
            log("desktop icon launch receiver registered");
        } catch (Throwable error) {
            desktopIconReceiver = null;
            log("desktop icon launch receiver failed: " + error);
        }
    }

    private void preannounceDesktopLaunch(android.os.Bundle binding) {
        if (binding == null || scene != ACTIVE || !screenOn || keyguardLocked
                || binding.getLong("scene", -1L) != sceneEpoch) return;
        int taskId = binding.getInt("taskId", -1);
        android.os.IBinder token = binding.getBinder("task");
        for (Slot slot : slots) {
            if (slot.index <= 0 || !slot.occupied || slot.parked || slot.frozen
                    || slot.taskId != taskId || slot.taskToken == null
                    || slot.taskEpoch != binding.getLong("binding", -1L)
                    || !java.util.Objects.equals(slot.pkg, binding.getString("package"))) continue;
            try {
                if (!java.util.Objects.equals(token, XposedHelpers.callMethod(slot.taskToken, "asBinder"))) return;
                log("desktop launch preannounced exact task=" + taskId + " slot=" + slot.index);
                if (binding.getBoolean("hostRoute", false)) {
                    if (SystemClock.uptimeMillis() > binding.getLong("deadline", 0L)) {
                        log("desktop host route expired without changing task=" + taskId); return;
                    }
                    if (slot.windowMode != 6 || !mayWriteSideSurface(slot)) return;
                    if (transferBusy || promotion != null || slot.promoting || slot.launching) {
                        main.postDelayed(() -> preannounceDesktopLaunch(binding), 32L);
                    } else {
                        log("desktop host route uses real promotion task=" + taskId);
                        onSlotTapped(slot.index);
                    }
                } else queueSidePromotion(slot, true);
            } catch (Throwable unavailable) { log("desktop preannouncement rejected: " + unavailable); }
            return;
        }
    }

    /** Publish only confirmed bindings; system_server rechecks each native task before use. */
    private void publishDesktopSideTasks() {
        final long sequence = ++desktopRegistrationSequence;
        final long epoch = sceneEpoch;
        final java.util.List<Slot> owners = new java.util.ArrayList<>();
        for (Slot slot : slots) if (slot != null && slot.index > 0) owners.add(slot);
        final boolean active = scene == ACTIVE && screenOn && !keyguardLocked && root != null;
        final boolean keepRecents = root != null && scene != SUSPENDED;
        Runnable send = () -> {
            if (sequence != desktopRegistrationSequence || epoch != sceneEpoch) return;
            android.os.Bundle state = new android.os.Bundle();
            java.util.ArrayList<android.os.Bundle> rows = new java.util.ArrayList<>();
            java.util.ArrayList<android.os.Bundle> recents = new java.util.ArrayList<>();
            if (active || keepRecents) for (Slot slot : owners) {
                try {
                    if (!isActive(slot) || !slot.occupied || slot.parked || slot.taskId < 0
                            || slot.taskToken == null || slot.pkg == null
                            || !controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
                    Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                    synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                        Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                                organizer, "mTasks")).get(slot.taskId);
                        Object info = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                        if (info == null || !slot.taskToken.equals(XposedHelpers.getObjectField(info, "token"))) continue;
                        android.os.Bundle row = new android.os.Bundle();
                        row.putBinder("task", (android.os.IBinder) XposedHelpers.callMethod(slot.taskToken, "asBinder"));
                        row.putInt("taskId", slot.taskId);
                        row.putInt("userId", XposedHelpers.getIntField(info, "userId"));
                        row.putInt("displayId", XposedHelpers.getIntField(info, "displayId"));
                        row.putString("package", slot.pkg);
                        row.putLong("scene", epoch);
                        row.putLong("binding", slot.taskEpoch);
                        if (keepRecents && scene != SUSPENDED && windowModeOf(info) == 6) recents.add(row);
                        if (active && scene == ACTIVE && screenOn && !keyguardLocked
                                && !slot.frozen && !slot.promoting && !slot.launching
                                && mayWriteSideSurface(slot)) rows.add(row);
                    }
                } catch (Throwable unavailable) { log("desktop binding omitted: " + unavailable); }
            }
            if (sequence != desktopRegistrationSequence || epoch != sceneEpoch) return;
            state.putBinder("owner", desktopSceneOwner);
            state.putLong("sequence", sequence);
            state.putParcelableArrayList("rows", rows);
            state.putParcelableArrayList("recents", recents);
            ctx.sendBroadcast(new Intent(MiStepDesktopLaunchBridge.ACTION).setPackage("android")
                    .putExtra("state", state), android.Manifest.permission.WRITE_SECURE_SETTINGS);
        };
        if (active || keepRecents) executeOnShell(send); else send.run();
    }

    private void promoteSideTaskForDesktopLaunch(String pkg) {
        if (pkg == null || scene != ACTIVE) return;
        String[] packages = new String[SLOT_COUNT - 1];
        boolean[] occupied = new boolean[SLOT_COUNT - 1];
        for (int i = 1; i < SLOT_COUNT; i++) {
            packages[i - 1] = slots[i].pkg;
            occupied[i - 1] = slots[i].occupied;
        }
        int sideIndex = DesktopIconLaunchPolicy.uniqueSideSlot(pkg, packages, occupied);
        if (sideIndex < 0) {
            log("desktop launch has no unique side task package=" + pkg);
            return;
        }
        Slot match = slots[sideIndex + 1];
        log("desktop icon selected side task package=" + pkg + " slot=" + match.index
                + " task=" + match.taskId + "; promote existing task");
        queueSidePromotion(match, true);
    }

    private void queueSidePromotion(Slot slot, boolean desktopLaunch) {
        if (slot == null || slot.index <= SLOT_MAIN || !isActive(slot)
                || !slot.occupied || slot.taskId < 0 || slot.taskToken == null
                || slot.parked || slot.frozen || slot.launching) return;
        PendingSidePromotion pending = slot.pendingSidePromotion;
        if (pending != null && pending.taskId == slot.taskId
                && pending.token.equals(slot.taskToken)
                && pending.bindingEpoch == slot.taskEpoch
                && pending.sceneEpoch == sceneEpoch) {
            pending.desktopLaunch |= desktopLaunch;
            return;
        }
        pending = new PendingSidePromotion(slot, sceneEpoch, desktopLaunch,
                SystemClock.uptimeMillis() + SIDE_PROMOTION_TIMEOUT_MS);
        slot.pendingSidePromotion = pending;
        retrySidePromotion(pending);
    }

    private void retrySidePromotion(PendingSidePromotion request) {
        Slot slot = request.slot;
        if (slot.pendingSidePromotion != request) return;
        boolean sameTask = scene == ACTIVE && isActive(slot) && slot.occupied
                && !slot.parked && !slot.frozen && !slot.launching
                && SidePromotionPolicy.sameBinding(request.taskId, request.token,
                        request.sceneEpoch, request.bindingEpoch, request.pkg,
                        slot.taskId, slot.taskToken, sceneEpoch, slot.taskEpoch, slot.pkg);
        if (!sameTask) {
            slot.pendingSidePromotion = null;
            log("pending side promotion cancelled: task binding changed slot=" + slot.index);
            return;
        }
        if (promotion != null && promotion.slot == slot
                && promotion.taskId == request.taskId && request.token.equals(promotion.token)) {
            slot.pendingSidePromotion = null;
            return;
        }
        if (slot.modeAdmissionPending && !request.desktopLaunch) {
            slot.pendingSidePromotion = null;
            return;
        }
        long now = SystemClock.uptimeMillis();
        long wait = SidePromotionPolicy.retryDelay(now, request.deadline,
                slot.modeGuardUntil, promotion != null || transferBusy || slot.promoting
                        || slot.windowMode != 1);
        if (wait < 0L || request.attempts >= 40) {
            slot.pendingSidePromotion = null;
            log("pending side promotion expired slot=" + slot.index + " task=" + request.taskId
                    + " mode=" + slot.windowMode + " attempts=" + request.attempts);
            if (slot.windowMode == 6) reapplyScale(slot);
            return;
        }
        if (wait == 0L) {
            slot.pendingSidePromotion = null;
            log("pending side promotion execute slot=" + slot.index + " task=" + request.taskId
                    + " desktop=" + request.desktopLaunch + " mode=" + slot.windowMode);
            // The system has already made this task fullscreen. Submitting another
            // fullscreen TaskView pending here repeats prepareOpen/updateBounds and
            // suppresses the original Launcher transition. Observe that transition.
            beginExternalPromotion(request);
            return;
        }
        request.attempts++;
        main.postDelayed(() -> retrySidePromotion(request), wait);
    }

    private void beginExternalPromotion(PendingSidePromotion request) {
        Slot slot = request.slot;
        if (promotion != null || transferBusy || !isActive(slot)
                || !SidePromotionPolicy.sameBinding(request.taskId, request.token,
                        request.sceneEpoch, request.bindingEpoch, request.pkg,
                        slot.taskId, slot.taskToken, sceneEpoch, slot.taskEpoch, slot.pkg)
                || slot.windowMode != 1) return;
        Promotion ticket = new Promotion(slot, slots);
        ticket.sceneEpoch = sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        ticket.externalLaunch = true;
        ticket.previousMainOnRight = mainOnRight;
        ticket.targetMainOnRight = mainOnRight;
        promotion = ticket;
        slot.promoting = true;
        recycleBuffered(slot);
        MiStepClipboard.get(ctx).close();
        statusBar.hideFor(ticket, () -> {
            if (promotion == ticket && !ticket.cancelled)
                failPromotion(ticket, "external promotion status-bar lease expired");
        });
        if (!executeOnShell(() -> {
            try {
                if (!externalPromotionCurrent(ticket)) return;
                ticket.homeBeforeCover = existingHomeTask();
                traceSwap(ticket);
                observeNativeCommit(ticket.token, () -> afterExternalTransitionIdle(ticket));
                log("observe external fullscreen task=" + ticket.taskId
                        + "; retain original launch, no second fullscreen pending");
            } catch (Throwable error) {
                main.post(() -> failPromotion(ticket, "external prepare: " + error));
            }
        })) failPromotion(ticket, "external promotion Shell executor unavailable");
    }

    private boolean externalPromotionCurrent(Promotion ticket) {
        return promotion == ticket && !ticket.cancelled && !ticket.cancelRequested && scene == ACTIVE
                && screenOn && !keyguardLocked
                && sceneEpoch == ticket.sceneEpoch && isActive(ticket.slot)
                && ticket.slot.taskEpoch == ticket.bindingEpoch
                && ticket.slot.taskId == ticket.taskId
                && ticket.token.equals(ticket.slot.taskToken);
    }

    private void afterExternalTransitionIdle(Promotion ticket) {
        if (!externalPromotionCurrent(ticket)) return;
        if (!executeOnShell(() -> {
            try {
                if (!externalPromotionCurrent(ticket)) return;
                Object shellTransitions = XposedHelpers.getObjectField(transitions, "mTransitions");
                // The exact target ROM queues this until pending transitions and all
                // tracks are idle. Its finish observer runs before finishT.apply.
                XposedHelpers.callMethod(shellTransitions, "runOnIdle", (Runnable) () ->
                        main.post(() -> {
                            if (externalPromotionCurrent(ticket)) checkPromotion(ticket, 0);
                        }));
            } catch (Throwable error) {
                main.post(() -> failPromotion(ticket, "external transition idle unavailable: " + error));
            }
        })) failPromotion(ticket, "external transition Shell queue unavailable");
    }

    private void scheduleSideSurfaceRestore(Slot slot, int taskId, Object token,
            long bindingEpoch, long epoch) {
        for (long delay : new long[]{90L, 320L, 800L}) {
            main.postDelayed(() -> {
                if (scene != ACTIVE || sceneEpoch != epoch || !isActive(slot)
                        || slot.taskId != taskId || slot.taskEpoch != bindingEpoch
                        || !java.util.Objects.equals(slot.taskToken, token)
                        || !slot.occupied || slot.promoting || slot.parked
                        || slot.windowMode != 6) return;
                reapplyScale(slot);
            }, delay);
        }
    }


    private MiStepLayout makeAdaptiveLayout(boolean right) {
        android.util.DisplayMetrics metrics = ctx.getResources().getDisplayMetrics();
        return MiStepLayout.of(screenW, screenH, right, metrics.density);
    }


    /** 调试广播入口：打印宿主 View 树（定位残带/残留视图）。 */
    public static void debugDumpViews() {
        MiStepHost host = sInstance;
        if (host == null) { log("debugDumpViews: host not started"); return; }
        host.main.post(() -> host.dumpViewTree());
    }

    private void dumpViewTree() {
        if (root == null) { log("view tree: root null"); return; }
        log("view tree root children=" + root.getChildCount());
        dumpViewRecursive(root, 0);
    }

    private void dumpViewRecursive(View view, int depth) {
        if (view == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("| ");
        sb.append(view.getClass().getSimpleName())
                .append(" vis=").append(view.getVisibility())
                .append(" l=").append(view.getLeft()).append(" t=").append(view.getTop())
                .append(" w=").append(view.getWidth()).append(" h=").append(view.getHeight())
                .append(" a=").append(view.getAlpha());
        if (view.getBackground() != null) {
            sb.append(" bg=").append(view.getBackground().getClass().getSimpleName());
        }
        log("view: " + sb);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpViewRecursive(group.getChildAt(i), depth + 1);
            }
        }
    }


    /** 交换确认后把新主任务 leash 完全复位：position/matrix/alpha/crop，避免副格残留变换把画面顶到主区上方。 */
    private void resetMainTaskLeash(int taskId) {
        if (taskId < 0) return;
        try {
            Object organizer = shellOrganizer();
            if (organizer == null) return;
            Object leash = XposedHelpers.callMethod(organizer, "getTaskLeash",
                    new Class<?>[]{int.class}, taskId);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                return;
            }
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class, float.class}, leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setMatrix",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class, float.class, float.class, float.class},
                        leash, 1f, 0f, 0f, 1f);
                XposedHelpers.callMethod(tx, "setAlpha",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class}, leash, 1f);
                XposedHelpers.callMethod(tx, "setCrop",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                android.graphics.Rect.class}, leash, null);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            log("main task leash reset task=" + taskId);
        } catch (Throwable error) {
            log("reset main task leash failed task=" + taskId + ": " + error);
        }
    }

    private void launchQuick(QuickApp entry) {
        if (entry == null || entry.component == null || scene != ACTIVE
                || promotion != null || transferBusy) return;
        for (Slot slot : slots) {
            if (slot.index > SLOT_MAIN && slot.occupied && entry.component.getPackageName()
                    .equals(slot.pkg)) {
                log("top shortcut promotes existing side task slot=" + slot.index);
                onSlotTapped(slot.index);
                return;
            }
        }
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(launch);
            log("top shortcut launch main " + entry.component.flattenToShortString());
            scheduleMainCropClear(1400L);
        } catch (Throwable error) {
            log("top shortcut launch failed: " + error);
            android.widget.Toast.makeText(ctx, "无法打开 " + entry.label,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettingsInMain() {
        if (scene != ACTIVE || promotion != null || transferBusy) return;
        for (Slot slot : slots) {
            if (slot.index > SLOT_MAIN && slot.occupied
                    && "com.android.settings".equals(slot.pkg)) {
                onSlotTapped(slot.index);
                return;
            }
        }
        try {
            Intent settings = new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(settings);
            log("system settings launched in main");
        } catch (Throwable error) {
            log("system settings launch failed: " + error);
            android.widget.Toast.makeText(ctx, "无法打开系统设置",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void beginWindowDrag(MotionEvent event) {
        dragMoved = false;
        dragStartRawX = event.getRawX();
        dragStartRawY = event.getRawY();
        dragTarget = null;
    }

    private void updateWindowDrag(MotionEvent event) {
        float dx = event.getRawX() - dragStartRawX;
        float dy = event.getRawY() - dragStartRawY;
        if (!dragMoved && dx * dx + dy * dy < dp(10) * dp(10)) return;
        dragMoved = true;
        if (dragGhost == null) createDragGhost();
        if (dragGhost != null) {
            dragGhost.setX(event.getRawX() - dragGhost.getWidth() * 0.5f);
            dragGhost.setY(event.getRawY() - dragGhost.getHeight() * 0.5f);
        }
        Slot target = slotAtPoint(event.getRawX(), event.getRawY());
        if (target != dragTarget) {
            setDropHighlight(dragTarget, false);
            dragTarget = target;
            setDropHighlight(dragTarget, true);
        }
    }

    private void finishWindowDrag(MotionEvent event) {
        boolean cancelled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
        boolean moved = dragMoved;
        Slot target = slotAtPoint(event.getRawX(), event.getRawY());
        setDropHighlight(dragTarget, false);
        if (dragGhost != null) {
            root.removeView(dragGhost);
            dragGhost = null;
        }
        dragTarget = null;
        if (!moved || cancelled) return;
        if (target == null) {
            log("window drag ended outside side cells");
            return;
        }
        if (target.occupied) {
            log("window drag target occupied slot=" + target.index);
            android.widget.Toast.makeText(ctx, "请拖到空白副槽",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        log("window drag collect main into slot=" + target.index);
        captureMainIntoEmptySlot(target);
    }

    private void createDragGhost() {
        TextView ghost = new TextView(ctx);
        ghost.setText("当前应用");
        ghost.setTextColor(HyperOsStyle.textPrimary(ctx));
        ghost.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        ghost.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        ghost.setGravity(Gravity.CENTER);
        ghost.setAlpha(0.92f);
        ghost.setElevation(1600f);
        ghost.setBackground(HyperOsStyle.glassPill(ctx, 18f, true));
        int width = dp(120), height = dp(56);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = Math.round(dragStartRawX - width * 0.5f);
        params.topMargin = Math.round(dragStartRawY - height * 0.5f);
        root.addView(ghost, params);
        dragGhost = ghost;
        root.requestLayout();
    }

    private void setDropHighlight(Slot slot, boolean active) {
        if (slot == null || slot.hint == null) return;
        slot.hint.setScaleX(1f);
        slot.hint.setScaleY(1f);
        LiveSwapVisualLease.writeAlpha(slot.hint, 1f);
    }

    /** 侧栏第 slotIndex 格的分段底板 top（与相邻格共享边界，保证列无缝）。 */
    private int sideSegmentTop(int slotIndex) {
        if (slotIndex <= 1 || layout == null) return layout == null ? 0 : layout.topStrip;
        int[] previous = layout.cellBounds(slotIndex - 2);
        return previous[3];
    }

    /** 侧栏第 slotIndex 格的分段底板高度。 */
    private int sideSegmentHeight(int slotIndex) {
        if (layout == null) return 1;
        int top = sideSegmentTop(slotIndex);
        int bottom = slotIndex >= SLOT_COUNT - 1 ? screenH : layout.cellBounds(slotIndex - 1)[3];
        return Math.max(1, bottom - top);
    }

    private Slot slotAtPoint(float x, float y) {
        if (layout == null || y < layout.topStrip) return null;
        for (Slot slot : slots) {
            if (slot.index <= SLOT_MAIN || slot.rect == null) continue;
            if (x >= slot.rect[0] && x < slot.rect[2]
                    && y >= slot.rect[1] && y < slot.rect[3]) return slot;
        }
        return null;
    }

    private int dp(float value) {
        return Math.round(ctx.getResources().getDisplayMetrics().density * value);
    }

    private void removeCellBackground(Slot slot) {
        Object background = slot.cellBackground;
        slot.cellBackground = null;
        slot.cellBackgroundParent = null;
        if (background == null) return;
        destroyCellSurface(background);
    }

    private void destroyCellSurface(Object background) {
        Object transaction = null;
        try {
            transaction = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(transaction, "remove", background);
            XposedHelpers.callMethod(transaction, "apply");
        } catch (Throwable failure) {
            log("cell backing cleanup failed: " + failure);
        } finally {
            closeSurfaceObject(transaction, "close");
            closeSurfaceObject(background, "release");
        }
    }

    private void closeSurfaceObject(Object object, String method) {
        if (object == null) return;
        try {
            XposedHelpers.callMethod(object, method);
        } catch (Throwable failure) {
            log("cell backing " + method + " failed: " + failure);
        }
    }

    private int slotIndexOfView(View view) {
        for (Slot s : slots) {
            if (s.view == view) {
                return s.index;
            }
        }
        return -1;
    }

    private void applyRect(Slot slot) {
        if (slot.rect == null) {
            return;
        }
        if (slot.view != null) {
            slot.view.setX(slot.rect[0]);
            slot.view.setY(slot.rect[1]);
            if (slot.view.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.view.getLayoutParams();
                lp.width = slot.rect[2] - slot.rect[0];
                lp.height = slot.rect[3] - slot.rect[1];
                slot.view.setLayoutParams(lp);
            }
        }
        if (slot.hint != null) {
            slot.hint.setX(slot.rect[0]);
            slot.hint.setY(slot.rect[1]);
            if (slot.hint.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slot.hint.getLayoutParams();
                lp.width = slot.rect[2] - slot.rect[0];
                lp.height = slot.rect[3] - slot.rect[1];
                slot.hint.setLayoutParams(lp);
            }
        }
        ensureCellBackground(slot);
    }

    /**
     * Exchange the whole main/side orientation. The official layout keeps the two
     * windows anchored to opposite edges, so the shrinking and growing panes share a
     * moving divider instead of crossing through each other's centre.
     */
    private void setMainSide(boolean right) {
        if (layout != null && mainOnRight == right) return;
        try {
            applyMainLayout(makeAdaptiveLayout(right));
            if (root != null) writeZoom(true);
        } catch (Throwable error) {
            log("main side switch failed: " + error);
        }
    }

    /** Host geometry only. A live flip sends its correlated WMS zoom separately. */
    private void applyMainLayout(MiStepLayout target) {
        if (target == null || target.width != screenW || target.height != screenH)
            throw new IllegalArgumentException("stale Host layout");
        mainOnRight = target.mainOnRight;
        layout = target;
        MiStepStatusBarSampling.geometryChanged();
        if (topBar != null) topBar.setMainOnRight(mainOnRight);
        log("main side layout: " + (mainOnRight ? "right" : "left") + " " + layout.describe());
        applyWallpaperBackdrops();
        if (root == null) return;
        for (int i = 1; i < SLOT_COUNT; i++) {
            Slot slot = slots[i];
            slot.rect = target.cellBounds(i - 1);
            applyRect(slot);
            updateTouchRegion(slot);
        }
        for (int i = 1; i < SLOT_COUNT; i++) {
            View backing = sideBackingViews[i];
            if (backing == null
                    || !(backing.getLayoutParams() instanceof FrameLayout.LayoutParams)) continue;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) backing.getLayoutParams();
            params.leftMargin = target.mainOnRight ? 0 : target.mainWidth;
            params.topMargin = sideSegmentTop(i);
            params.width = target.sideWidth;
            params.height = sideSegmentHeight(i);
            backing.setLayoutParams(params);
        }
        root.requestLayout();
        root.invalidate();
    }

    /** Only the top strip / side column receive host input; transitions may cover the display. */
    private void installHostTouchRegion() {
        Class<?> listenerClass = XposedHelpers.findClass(
                "android.view.ViewTreeObserver$OnComputeInternalInsetsListener", ctx.getClassLoader());
        Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return "MiStepHostTouchRegion";
                    }
                    if (!"onComputeInternalInsets".equals(method.getName()) || root == null) {
                        return null;
                    }
                    Object info = args[0];
                    XposedHelpers.callMethod(info, "setTouchableInsets", 3); // TOUCHABLE_INSETS_REGION
                    Region touchable = (Region) XposedHelpers.getObjectField(info, "touchableRegion");
                    touchable.setEmpty();
                    if (swapPreview != null || cellMovePreview != null) {
                        touchable.set(0, 0, screenW, screenH);
                    } else if (liveSwapInputBlocked) {
                        touchable.set(0, 0, screenW, screenH);
                    } else {
                        touchable.set(0, 0, screenW, layout.topStrip);
                        touchable.op(new Rect(layout.cellX, layout.topStrip,
                                        layout.cellX + layout.cellWidth, screenH),
                                Region.Op.UNION);
                    }
                    return null;
                });
        XposedHelpers.callMethod(root.getViewTreeObserver(),
                "addOnComputeInternalInsetsListener", listener);
        log("host input: top/side only; system main passes through");
    }

    /** Side TaskViews return their touch areas to the host for tap/drag arbitration. */
    private void updateTouchRegion(Slot slot) {
        try {
            if (slot.view == null) {
                return;
            }
            Method m = slot.view.getClass().getMethod("setObscuredTouchRegion", Region.class);
            // Only side cells exist. Their own scaled contents still use targeted injection.
            Object arg = liveSwapInputBlocked ? new Region(0, 0, screenW, screenH) : slot.rect == null ? null
                    : new Region(slot.rect[0], slot.rect[1], slot.rect[2], slot.rect[3]);
            m.invoke(slot.view, arg);
        } catch (Throwable t) {
            log("updateTouchRegion slot=" + slot.index + " failed: " + t);
        }
    }

    // ---------------------------------------------------------------- v0.9 触摸仲裁与定向注入

    /**
     * 触摸规则（官方Mi Step语义 + 演示需要）：
     *   主区由系统处理，宿主不接收其触摸；
     *   副格有任务 → 轻点=与主区交换；滑动取消轻点，始终不操作副任务；
     *   空槽 → 不消费，交给宿主点击处理。
     */
    private boolean onSlotTouch(int slotIndex, MotionEvent e) {
        if (slotIndex <= SLOT_MAIN || slotIndex >= SLOT_COUNT) return false;
        Slot slot = slots[slotIndex];
        // Our MOVE session owns this pointer even while the task-transfer gate is held.
        if (sideDismiss != null && sideDismissSlot == slot) return followSideDismiss(slot, e);
        if (slot.promoting || slot.launching || slot.closing || slot.frozen || slot.parked
                || transferBusy || promotion != null || cellMotion != null) {
            recycleBuffered(slot);
            slot.injecting = false;
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            log("slotTouch down slot=" + slotIndex + " occupied=" + slot.occupied
                    + " x=" + e.getRawX() + " y=" + e.getRawY());
        }
        if (!slot.occupied || slot.view == null) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                recycleBuffered(slot);
                slot.bufferedDown = MotionEvent.obtain(e);
                slot.touchTaskEpoch = slot.taskEpoch;
                slot.touchSceneEpoch = sceneEpoch;
                slot.injecting = false;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (slot.bufferedDown != null && e.getPointerCount() == 1
                        && slot.touchTaskEpoch == slot.taskEpoch && slot.touchSceneEpoch == sceneEpoch) {
                    float dx = e.getRawX() - slot.bufferedDown.getRawX();
                    float dy = e.getRawY() - slot.bufferedDown.getRawY();
                    int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
                    if (Math.abs(dx) > slop || Math.abs(dy) > slop) slot.injecting = true;
                    if (MiStepSideDismissGesture.starts(mainOnRight, dx, dy, slop)) {
                        dismissSideToBackground(slot);
                        if (sideDismiss != null && sideDismissSlot == slot) sideDismiss.follow(dx);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                recycleBuffered(slot);
                slot.injecting = true;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                MotionEvent down = slot.bufferedDown;
                boolean current = down != null && e.getActionMasked() == MotionEvent.ACTION_UP
                        && e.getPointerCount() == 1 && down.getPointerCount() == 1
                        && e.getPointerId(0) == down.getPointerId(0)
                        && slot.touchTaskEpoch == slot.taskEpoch && slot.touchSceneEpoch == sceneEpoch;
                float dx = current ? e.getRawX() - down.getRawX() : 0;
                float dy = current ? e.getRawY() - down.getRawY() : 0;
                int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
                boolean dismiss = current && slot.rect != null && MiStepSideDismissGesture.accepts(
                        mainOnRight, dx, dy, slot.rect[2] - slot.rect[0],
                        ctx.getResources().getDisplayMetrics().density, slop,
                        e.getEventTime() - down.getEventTime());
                boolean tap = current && !slot.injecting && Math.abs(dx) <= slop && Math.abs(dy) <= slop;
                recycleBuffered(slot);
                slot.injecting = false;
                if (dismiss) {
                    dismissSideToBackground(slot);
                    if (sideDismiss != null && sideDismissSlot == slot) {
                        sideDismiss.follow(dx);
                        sideDismiss.settle(true);
                    }
                } else if (tap) {
                    log("cell tap slot=" + slotIndex + " -> swap");
                    onSlotTapped(slotIndex);
                }
                if (compactPending) compactCells();
                return true;
            }
            default:
                return false;
        }
    }

    private void recycleBuffered(Slot slot) {
        if (slot.bufferedDown != null) {
            slot.bufferedDown.recycle();
            slot.bufferedDown = null;
        }
    }

    private boolean followSideDismiss(Slot slot, MotionEvent e) {
        MiStepSideDismiss run = sideDismiss;
        if (!run.isTracking()) return true;
        MotionEvent down = slot.bufferedDown;
        int action = e.getActionMasked();
        boolean current = down != null && e.getPointerCount() == 1
                && e.getPointerId(0) == down.getPointerId(0)
                && slot.touchTaskEpoch == slot.taskEpoch && slot.touchSceneEpoch == sceneEpoch;
        float dx = current ? e.getRawX() - down.getRawX() : 0f;
        float dy = current ? e.getRawY() - down.getRawY() : 0f;
        if (action == MotionEvent.ACTION_MOVE && current) { run.follow(dx); return true; }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || !current
                || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            boolean commit = current && action == MotionEvent.ACTION_UP && slot.rect != null
                    && MiStepSideDismissGesture.accepts(mainOnRight, dx, dy,
                            slot.rect[2] - slot.rect[0], ctx.getResources().getDisplayMetrics().density,
                            ViewConfiguration.get(ctx).getScaledTouchSlop(), e.getEventTime() - down.getEventTime());
            if (current) run.follow(dx);
            recycleBuffered(slot); slot.injecting = false;
            run.settle(commit);
        }
        return true;
    }

    private void dismissSideToBackground(Slot slot) {
        if (scene != ACTIVE || !screenOn || keyguardLocked || transferBusy || promotion != null
                || cellMotion != null || !isActive(slot) || !slot.occupied || slot.carrier
                || slot.parked || slot.frozen || slot.promoting || slot.launching || slot.closing
                || slot.taskId < 0 || slot.taskToken == null || slot.controller == null) return;
        for (Slot other : slots) if (other.launching || other.promoting || other.closing) return;
        final int taskId = slot.taskId;
        final long binding = slot.taskEpoch;
        final long epoch = sceneEpoch;
        final View hostRoot = root;
        log("side outward swipe -> background task=" + taskId + " slot=" + slot.index);
        MiStepLiveBackdrop preparing = null;
        LiveSwapVisualLease preparingVisuals = null;
        try {
            final MiStepLayout geometry = layout;
            final int emptyMask = 1 << slot.index;
            Rect strip = liveSideStrip(geometry);
            final MiStepLiveBackdrop backing = preparing = MiStepLiveBackdrop.create(liveHostRootSurface(),
                    wallpaperBackdrop, screenW, screenH, strip, strip,
                    canvas -> drawLiveEmptyHints(canvas, geometry, geometry, emptyMask), MiStepHost::log);
            final LiveSwapVisualLease visuals = preparingVisuals =
                    new LiveSwapVisualLease(sideBackingViews[slot.index], slot.hint);
            transferBusy = true;
            slot.promoting = true;
            final MiStepSideDismiss[] run = new MiStepSideDismiss[1];
            Runnable release = () -> {
                run[0].close();
                if (sideDismiss == run[0]) { sideDismiss = null; sideDismissSlot = null; }
                recycleBuffered(slot); slot.injecting = false;
                if (root == hostRoot && sceneEpoch == epoch) {
                    for (Slot cell : slots) updateCellSurfaceVisibility(cell);
                    visuals.restore();
                    hostRoot.invalidate();
                } else visuals.restore();
                backing.closeAfterFrame(hostRoot);
                if (compactPending) compactCells();
                finishPendingSuspend();
            };
            float distance = (mainOnRight ? -1f : 1f)
                    * (slot.rect[2] - slot.rect[0] + HyperOsStyle.dp(ctx, 8f));
            run[0] = new MiStepSideDismiss(hostRoot, slot.view, distance,
                    () -> root == hostRoot && sceneEpoch == epoch && scene == ACTIVE
                            && isActive(slot) && slot.taskEpoch == binding && slot.taskId == taskId,
                    reachedEdge -> {
                        if (!reachedEdge) {
                            if (slot.taskEpoch == binding && slot.taskId == taskId) slot.promoting = slot.frozen;
                            transferBusy = false; release.run(); return;
                        }
                        returnToSystem(slot, false, () -> {
                            // Hide the empty real container before restoring its offscreen View.
                            if (slot.taskEpoch == binding && slot.taskId == taskId) clearSlot(slot);
                            log("side swipe handed back task=" + taskId + "; kept in system background");
                            release.run();
                        }, error -> {
                            log("side swipe handback stopped; binding retained task=" + taskId + ": " + error);
                            release.run();
                        });
                    }, MiStepHost::log);
            sideDismiss = run[0];
            sideDismissSlot = slot;
            log("side outward real View animation task=" + taskId + " distance=" + distance);
            // MOVE positions the real view immediately; UP completes or rebounds from it.
        } catch (Throwable error) {
            if (preparingVisuals != null) preparingVisuals.restore();
            if (preparing != null) preparing.closeAfterFrame(hostRoot);
            transferBusy = false;
            slot.promoting = slot.frozen;
            log("side outward preparation stopped: " + error);
            finishPendingSuspend();
        }
    }

    /** Keep tap-to-swap, transfer a drag to its real window without reinjecting coordinates. */
    private void transferTouchToTask(Slot slot, long eventTime) {
        if (root == null || slot.taskToken == null || !controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        try {
            android.os.Bundle tokens = new android.os.Bundle();
            tokens.putBinder("host", root.getWindowToken());
            tokens.putBinder("task", (android.os.IBinder) XposedHelpers.callMethod(slot.taskToken, "asBinder"));
            Intent request = new Intent("com.jianguo.mistep.TRANSFER_TOUCH");
            request.setPackage("android");
            request.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            request.putExtra("tokens", tokens);
            request.putExtra("taskId", slot.taskId);
            request.putExtra("eventTime", eventTime);
            ctx.sendBroadcast(request);
            log("side touch transfer requested slot=" + slot.index + " task=" + slot.taskId);
        } catch (Throwable t) {
            log("side touch transfer request failed slot=" + slot.index + ": " + t);
        }
    }

    private int uidOf(String pkg) {
        if (pkg == null) {
            return 0;
        }
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (Throwable t) {
            log("uidOf " + pkg + " failed: " + t);
            return 0;
        }
    }

    // ---------------------------------------------------------------- 点击 / 交换

    private void onSlotTapped(int slotIndex) {
        try {
            if (slotIndex <= SLOT_MAIN || slotIndex >= SLOT_COUNT) {
                return;
            }
            log("onSlotTapped slot=" + slotIndex
                    + (slots[slotIndex] != null && slots[slotIndex].carrier ? " carrier" : ""));
            if (scene != ACTIVE || transferBusy) return;
            Slot slot = slots[slotIndex];
            if (slot.closing) return;
            if (slot.carrier) {
                restoreCarrierToMain();
                return;
            }
            if (slot.parked && slot.occupied && !slot.frozen) {
                adoptExisting(slot, new LiveTask(slot), this::finishPendingSuspend,
                        error -> log("explicit resume retained task: " + error));
                return;
            }
            if (!slot.occupied) {
                captureMainIntoEmptySlot(slot);
                return;
            }
            if (slotIndex == SLOT_MAIN) {
                return;
            }
            swapWithMain(slotIndex);
        } catch (Throwable t) {
            log("onSlotTapped FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    /** Official empty-cell flow: open an app from Home, then collect that exact task. */
    private void captureMainIntoEmptySlot(Slot requestedSlot) {
        if (requestedSlot == null) return;
        final Slot slot = requestedSlot;
        if (carrierSlotIndex >= 0) {
            android.widget.Toast.makeText(ctx, "先点副格把相机放回主区",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (scene != ACTIVE || !screenOn || keyguardLocked || transferBusy
                || promotion != null || swapPreview != null
                || slot.occupied || slot.launching || slot.promoting || slot.frozen
                || slot.controller == null) return;
        for (Slot other : slots) if (other.launching || other.promoting || other.closing) return;
        final long epoch = sceneEpoch;
        transferBusy = true;
        if (!executeOnShell(() -> {
            try {
                LiveTask task = findMainTask(); // Deliberately excludes Home and existing cells.
                main.post(() -> {
                    if (scene != ACTIVE || epoch != sceneEpoch || !isActive(slot)
                            || slot.occupied || promotion != null || suspendRequested) {
                        transferBusy = false;
                        finishPendingSuspend();
                        return;
                    }
                    if (task == null) {
                        transferBusy = false;
                        log("empty cell keeps Home in main; open an app there first");
                        android.widget.Toast.makeText(ctx, "先在主屏打开应用，再点此格收进来",
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    hideIme();
                    startLiveCollect(slot, task);
                });
            } catch (Throwable error) {
                main.post(() -> {
                    transferBusy = false;
                    log("collect current main admission stopped: " + error);
                    finishPendingSuspend();
                });
            }
        })) {
            transferBusy = false;
            log("no Shell queue for collecting current main");
        }
    }

    /** Collect the actual fullscreen task; no captured app or desktop participates. */
    private void startLiveCollect(Slot slot, LiveTask task) {
        final MiStepLayout geometry = layout;
        final int width = screenW, height = screenH;
        final Object hostSurface;
        try { hostSurface = liveHostRootSurface(); }
        catch (Throwable error) {
            transferBusy = false;
            log("live collect Host unavailable: " + error);
            finishPendingSuspend();
            return;
        }
        bindTask(slot, task.id, task.token);
        Promotion ticket = new Promotion(slot, slots);
        ticket.sceneEpoch = sceneEpoch;
        ticket.bindingEpoch = slot.taskEpoch;
        ticket.previousMain = task;
        ticket.previousLayout = ticket.targetLayout = geometry;
        ticket.nativeSwap = ticket.nativeCollect = true;
        slot.promoting = true;
        promotion = ticket;
        transferBusy = false;
        OwnedTask candidate = ownedTasks.computeIfAbsent(task.token, key -> new OwnedTask(task.id, key));
        candidate.recoveryController = slot.controller;
        candidate.recoveryCandidate = true;
        ticket.previousMainCandidate = candidate;
        try {
            Rect side = liveSideStrip(geometry);
            ticket.liveBackdrop = MiStepLiveBackdrop.create(hostSurface, wallpaperBackdrop, width, height,
                    side, side, canvas -> drawLiveEmptyHints(canvas, geometry, geometry,
                            1 << slot.index), MiStepHost::log);
        } catch (Throwable error) {
            failPromotion(ticket, "collect wallpaper preparation: " + error);
            return;
        }
        // Keep the empty-cell affordance until the real task occupies it.
        ticket.liveVisual = new LiveSwapVisualLease(sideBackingViews[slot.index], null);
        prepareCellSurface(slot);
        if (!setLiveSwapInputBlocked(true)) {
            failPromotion(ticket, "transition input window unavailable"); return;
        }
        statusBar.hideFor(ticket, () -> failPromotion(ticket, "collect status-bar lease expired"));
        LiveSwapGeometry.Task app = new LiveSwapGeometry.Task(task.id, task.token, width, height,
                liveMainRect(geometry), liveCellRect(geometry, slot.index));
        statusBar.runAfterHiddenFrame(ticket, () -> afterLiveHostFrame(ticket, () -> {
            if (!executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (externalPromotionCurrent(ticket) && layout == geometry) {
                        Object shell = XposedHelpers.getObjectField(transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < zoomAnimUntil || !Boolean.TRUE.equals(
                                XposedHelpers.callMethod(shell, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        Object info = rootInfo(task);
                        if (!ordinaryRoot(info, 1) || !XposedHelpers.getBooleanField(info, "isVisible"))
                            throw new IllegalStateException("collect fullscreen identity changed");
                        candidate.userId = XposedHelpers.getIntField(info, "userId");
                        ticket.collectionHome = existingHomeForCollection(task);
                        resetIdleTaskView(slot);
                        accepted = MiStepLiveCollect.start(ctx.getClassLoader(), transitions,
                                slot.controller, app, ticket.collectionHome.token, candidate.userId,
                                new Rect(0, 0, width, height), hostSurface, slot.cellBackground,
                                () -> externalPromotionCurrent(ticket) && layout == geometry,
                                settled -> main.post(() -> prepareCollectHandoff(ticket, settled)),
                                result -> finishLiveResult(ticket, result), MiStepHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { log("live collect refused: " + error); }
                if (!accepted) main.post(() -> finishLiveCollect(ticket, new MiStepLiveSwap.Result(false,
                        false, new IllegalStateException("live collect preflight refused"))));
            })) failPromotion(ticket, "live collect Shell queue unavailable");
        }));
    }

    private void prepareCollectHandoff(Promotion ticket, Consumer<Boolean> settled) {
        if (!externalPromotionCurrent(ticket)) { settled.accept(false); return; }
        // The real app is still above Host. Remove the opaque empty-cell drawing
        // now, commit that frame, then let Shell return the app to its TaskView.
        if (ticket.slot.hint != null) ticket.slot.hint.setVisibility(View.GONE);
        MiStepCollectFrame.await(root, () -> externalPromotionCurrent(ticket), settled);
    }

    private void finishLiveCollect(Promotion ticket, MiStepLiveSwap.Result result) {
        if (!livePromotionBound(ticket) || ticket.nativeCompleting) return;
        ticket.nativeCompleting = true;
        Slot slot = ticket.slot;
        if (result.sideOwned && result.rolesConfirmed) {
            slot.pkg = ticket.previousMain.pkg;
            slot.occupied = slot.taskVisible = true;
            slot.windowMode = 6;
            slot.promoting = slot.launching = slot.parked = slot.frozen = false;
            ticket.previousMainCandidate.recoveryCandidate = false;
            HomeTaskReturn savedHome = coveredHome;
            if (savedHome != null && savedHome.covers(ticket.taskId, ticket.token)) coveredHome = null;
        } else if (result.rolesConfirmed) {
            ownedTasks.remove(ticket.token, ticket.previousMainCandidate);
            clearSlot(slot);
        } else {
            slot.promoting = slot.launching = false;
            slot.occupied = slot.frozen = true;
        }
        if (result.sideOwned && result.rolesConfirmed) {
            finishPromotionFocus(ticket, () -> completeLiveCollect(ticket, result), 0);
        } else completeLiveCollect(ticket, result);
    }

    private void completeLiveCollect(Promotion ticket, MiStepLiveSwap.Result result) {
        if (promotion != ticket || ticket.cancelled) return;
        Slot slot = ticket.slot;
        ticket.cancelled = true;
        promotion = null;
        restoreLiveSwapVisual(ticket);
        statusBar.finish(ticket);
        updateHint(slot);
        updateTouchRegion(slot);
        publishDesktopSideTasks();
        log("live collect settled task=" + ticket.taskId + " side=" + result.sideOwned
                + " confirmed=" + result.rolesConfirmed + " animated=" + result.succeeded + " error=" + result.error);
        requestCompaction();
        finishPendingSuspend();
    }

    /** Promote through the existing Shell queue; retain the slot until actual detachment. */
    private void swapWithMain(int slotIndex) {
        swapWithMain(slotIndex, false);
    }

    private void swapWithMain(int slotIndex, boolean desktopLaunch) {
        if (slotIndex <= SLOT_MAIN || slotIndex >= SLOT_COUNT) {
            return;
        }
        Slot cellSlot = slots[slotIndex];
        if (scene != ACTIVE || !screenOn || keyguardLocked || transferBusy
                || swapPreview != null || cellSlot.parked
                || promotion != null || cellSlot.launching || cellSlot.promoting) return;
        if (cellSlot.carrier) {
            // 镜像承载的任务本身仍在主区：把画面移回来即完成“升为主区”。
            restoreCarrierToMain();
            return;
        }
        for (Slot s : slots) if (s.launching || s.closing) return;
        if (!cellSlot.occupied || cellSlot.controller == null) {
            captureMainIntoEmptySlot(cellSlot);
            return;
        }
        if (cellSlot.taskToken == null) {
            log("promote: task token not ready; keep cell" + slotIndex);
            return;
        }
        final Promotion ticket = new Promotion(cellSlot, slots);
        MiStepClipboard.get(ctx).close();
        ticket.sceneEpoch = sceneEpoch;
        ticket.bindingEpoch = cellSlot.taskEpoch;
        ticket.previousMainOnRight = mainOnRight;
        ticket.targetMainOnRight = mainOnRight;
        promotion = ticket;
        if (desktopLaunch) ticket.slot.promoting = true;
        statusBar.hideFor(ticket, () -> {
            if (promotion == ticket && !ticket.cancelled) {
                failPromotion(ticket, "status-bar lease expired");
            } else if (swapPreviewStatusOwner == ticket) {
                closeSwapPreview();
            }
        });
        recycleBuffered(cellSlot);
        log("promote begin: cell" + slotIndex + " task=" + ticket.taskId);
        hideIme();
        if (!executeOnShell(() -> {
            try {
                if (ticket.cancelled) return;
                Object current = XposedHelpers.getObjectField(cellSlot.controller, "mTaskToken");
                if (!ticket.token.equals(current)) throw new IllegalStateException("task token changed");
                ticket.previousMain = desktopLaunch ? null : findMainTask();
                if (ticket.previousMain == null) ticket.homeBeforeCover = existingHomeTask();
                traceSwap(ticket);
                if (ticket.previousMain == null) {
                    // Home remains anchored. Its state is retained by its original
                    // task, rather than by animating or repainting a Home snapshot.
                    ticket.targetMainOnRight = ticket.previousMainOnRight;
                    main.post(() -> {
                        if (promotion != ticket || ticket.cancelled || !screenOn
                                || keyguardLocked || scene != ACTIVE) {
                            failPromotion(ticket, "Home promotion scene changed");
                            return;
                        }
                        if (ticket.homeBeforeCover == null) {
                            failPromotion(ticket, "exact Home task unavailable");
                            return;
                        }
                        ticket.slot.promoting = true;
                        log("cover existing Home task=" + ticket.homeBeforeCover.id
                                + " with app=" + ticket.taskId + " desktop=" + desktopLaunch
                                + " keepMainSide=" + ticket.previousMainOnRight);
                        startLiveHome(ticket);
                    });
                    return;
                }
                main.post(() -> startLiveSwap(ticket));
            } catch (Throwable t) {
                main.post(() -> failPromotion(ticket, "prepare: " + t));
            }
        })) failPromotion(ticket, "Shell executor unavailable");
    }

    private boolean setLiveSwapInputBlocked(boolean on) {
        if (transitionInput == null) return !on;
        if (on) {
            boolean acquired = transitionInput.block();
            if (acquired && transitionInputStartedAt == 0L) {
                transitionInputStartedAt = SystemClock.uptimeMillis();
                log("live input acquired uptime=" + transitionInputStartedAt);
            }
            return acquired;
        }
        transitionInputReleaseRequestedAt = SystemClock.uptimeMillis();
        log("live input final frame requested uptime=" + transitionInputReleaseRequestedAt);
        transitionInput.releaseAfterFrame();
        return true;
    }

    private long transitionInputStartedAt, transitionInputReleaseRequestedAt;

    private void acquireTransitionInputWindow() {
        if (transitionInputWindow != null) return;
        View blocker = new View(ctx);
        blocker.setOnTouchListener((view, event) -> true);
        blocker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(lp);
        params.setTitle("MiStepTransitionInput");
        // A sibling window sits above the borrowed app surfaces under Host's root.
        // Merely widening Host's own touch region cannot cover its higher children.
        wm.addView(blocker, params);
        transitionInputWindow = blocker;
    }

    private void releaseTransitionInputWindow() {
        View blocker = transitionInputWindow;
        transitionInputWindow = null;
        if (blocker == null) return;
        blocker.setVisibility(View.GONE);
        try { wm.removeViewImmediate(blocker); }
        catch (Throwable error) { log("live input window release: " + error); }
        if (transitionInputStartedAt != 0L) {
            long now = SystemClock.uptimeMillis();
            log("live input released uptime=" + now + " heldMs=" + (now - transitionInputStartedAt)
                    + " finalFrameMs=" + (transitionInputReleaseRequestedAt == 0L
                            ? -1L : now - transitionInputReleaseRequestedAt));
            transitionInputStartedAt = transitionInputReleaseRequestedAt = 0L;
        }
    }

    private Object liveHostRootSurface() {
        if (root == null || !root.isAttachedToWindow() || !root.isHardwareAccelerated())
            throw new IllegalStateException("live Host window unavailable");
        Object viewRoot = XposedHelpers.callMethod(root, "getViewRootImpl");
        Object surface = viewRoot == null ? null : XposedHelpers.callMethod(viewRoot, "getSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
            throw new IllegalStateException("live Host root surface unavailable");
        return surface;
    }

    private static LiveSwapGeometry.Rect liveMainRect(MiStepLayout geometry) {
        return new LiveSwapGeometry.Rect(geometry.mainOffsetX, geometry.mainOffsetY,
                geometry.width * geometry.mainScale, geometry.height * geometry.mainScale);
    }

    private static LiveSwapGeometry.Rect liveCellRect(MiStepLayout geometry, int index) {
        int[] bounds = geometry.cellBounds(index - 1);
        float scale = geometry.cellScale(index - 1);
        return new LiveSwapGeometry.Rect(bounds[0], bounds[1],
                geometry.width * scale, geometry.height * scale);
    }

    private static Rect liveSideStrip(MiStepLayout geometry) {
        return new Rect(geometry.mainOnRight ? 0 : geometry.mainWidth, geometry.topStrip,
                geometry.mainOnRight ? geometry.sideWidth : geometry.width, geometry.height);
    }

    private boolean livePeerBindingsCurrent(Promotion ticket) {
        for (LivePeerBinding peer : ticket.livePeers) {
            Slot slot = peer.slot;
            if (!isActive(slot) || slot.taskEpoch != peer.epoch || slot.taskId != peer.id
                    || slot.controller != peer.controller || !peer.token.equals(slot.taskToken)
                    || !slot.occupied || slot.parked || slot.frozen || slot.promoting || slot.launching)
                return false;
        }
        return true;
    }

    /** Real A/B leashes stay in their original columns as their roles expand/shrink. */
    private void startLiveSwap(Promotion ticket) {
        if (!externalPromotionCurrent(ticket) || ticket.previousMain == null) {
            failPromotion(ticket, "live swap scene changed before preparation");
            return;
        }
        final MiStepLayout geometry = layout;
        final MiStepLayout target = makeAdaptiveLayout(!geometry.mainOnRight);
        final int width = screenW, height = screenH;
        final LiveTask first = ticket.previousMain;
        LiveSwapGeometry.Task a = new LiveSwapGeometry.Task(first.id, first.token, width, height,
                liveMainRect(geometry), liveCellRect(target, ticket.slot.index));
        LiveSwapGeometry.Task b = new LiveSwapGeometry.Task(ticket.taskId, ticket.token, width, height,
                liveCellRect(geometry, ticket.slot.index), liveMainRect(target));
        final Object hostSurface;
        final java.util.List<LiveTaskSwapDriver.Peer> peers = new java.util.ArrayList<>();
        final Object[] cellBackgrounds = new Object[SLOT_COUNT - 1];
        try {
            hostSurface = liveHostRootSurface();
            for (int i = 1; i < SLOT_COUNT; i++) {
                Slot peer = slots[i];
                cellBackgrounds[i - 1] = peer.cellBackground;
                if (peer == ticket.slot || !peer.occupied) continue;
                if (peer.parked || peer.frozen || peer.carrier || peer.launching || peer.promoting
                        || peer.windowMode != 6 || peer.taskToken == null || peer.controller == null)
                    throw new IllegalStateException("peer not live for flip slot=" + i);
                ticket.livePeers.add(new LivePeerBinding(peer));
                peers.add(new LiveTaskSwapDriver.Peer(new LiveSwapGeometry.Task(peer.taskId,
                        peer.taskToken, width, height, liveCellRect(geometry, i), liveCellRect(target, i)),
                        peer.controller));
            }
            ticket.liveBackdrop = MiStepLiveBackdrop.create(hostSurface, wallpaperBackdrop, width, height,
                    // Include the main/side gap while its normal Host backing is hidden.
                    new Rect(geometry.mainOnRight ? 0 : geometry.mainWidth, geometry.topStrip,
                            geometry.mainOnRight ? geometry.sideWidth : width, height),
                    new Rect(target.mainOnRight ? 0 : target.mainWidth, target.topStrip,
                            target.mainOnRight ? target.sideWidth : width, height),
                    canvas -> drawLiveEmptyHints(canvas, geometry, target, 0), MiStepHost::log);
        } catch (Throwable error) { failPromotion(ticket, "live flip Host preparation: " + error); return; }
        ticket.nativeSwap = true;
        ticket.nativeFlip = true;
        ticket.previousLayout = geometry;
        ticket.targetLayout = target;
        ticket.targetMainOnRight = target.mainOnRight;
        final LiveSwapZoom.Zoom oldZoom = new LiveSwapZoom.Zoom(++zoomCommandGeneration,
                geometry.mainScale, geometry.mainOffsetX, geometry.mainOffsetY);
        final LiveSwapZoom.Zoom targetZoom = new LiveSwapZoom.Zoom(++zoomCommandGeneration,
                target.mainScale, target.mainOffsetX, target.mainOffsetY);
        ticket.slot.promoting = true;
        OwnedTask candidate = ownedTasks.computeIfAbsent(first.token, token -> new OwnedTask(first.id, token));
        candidate.recoveryController = ticket.slot.controller;
        candidate.recoveryCandidate = true;
        ticket.previousMainCandidate = candidate;
        OwnedTask oldSide = ownedTasks.get(ticket.token);
        if (oldSide != null) {
            oldSide.recoveryController = ticket.slot.controller;
            oldSide.recoveryCandidate = true;
        }
        ticket.liveVisual = new LiveSwapVisualLease(sideBackingViews[ticket.slot.index], ticket.slot.hint);
        for (int i = 1; i < SLOT_COUNT; i++) if (i != ticket.slot.index)
            ticket.peerVisuals.add(new LiveSwapVisualLease(sideBackingViews[i], slots[i].hint));
        if (!setLiveSwapInputBlocked(true)) {
            failPromotion(ticket, "transition input window unavailable"); return;
        }
        // Hold the full input region until the final Host draw. App gestures while
        // these tasks are borrowed can start another transition and overwrite their matrices.
        statusBar.runAfterHiddenFrame(ticket, () -> afterLiveHostFrame(ticket, () -> {
            if (!externalPromotionCurrent(ticket) || layout != geometry) {
                failPromotion(ticket, "live swap input frame scene changed");
                return;
            }
            if (!executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (externalPromotionCurrent(ticket) && layout == geometry && livePeerBindingsCurrent(ticket)) {
                        Object shellTransitions = XposedHelpers.getObjectField(transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < zoomAnimUntil || !Boolean.TRUE.equals(
                                XposedHelpers.callMethod(shellTransitions, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        Object info = rootInfo(first);
                        if (info == null) throw new IllegalStateException("live main task disappeared");
                        int user = XposedHelpers.getIntField(info, "userId");
                        ticket.previousMainCandidate.userId = user;
                        if (oldSide != null) oldSide.userId = user;
                        traceSwapEvent("before-single-native-transition");
                        LiveTaskSwapDriver.Flip request = new LiveTaskSwapDriver.Flip(a, b, user,
                                new Rect(0, 0, width, height), hostSurface, oldZoom, targetZoom,
                                (zoom, done) -> applyLiveZoom(ticket, zoom, done),
                                (zoom, restoring, done) -> applyLiveMainLayout(ticket, zoom, restoring, done),
                                peers.toArray(new LiveTaskSwapDriver.Peer[0]), cellBackgrounds, ticket.liveBackdrop);
                        accepted = LiveTaskSwapDriver.startFlip(ctx.getClassLoader(), transitions,
                                ticket.slot.controller, request,
                                () -> externalPromotionCurrent(ticket) && livePeerBindingsCurrent(ticket)
                                        && (layout == geometry || layout == target),
                                frames -> { /* Native input follows these real task surfaces. */ },
                                result -> finishLiveResult(ticket, result), MiStepHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { log("native live swap rejected: " + error); }
                if (!accepted) main.post(() -> finishLiveSwap(ticket,
                        new MiStepLiveSwap.Result(false, false,
                                new IllegalStateException("native preflight refused; no fallback transition"))));
            })) failPromotion(ticket, "live swap Shell queue unavailable");
        }));
    }

    /** Paint only empty Host controls; real app buffers remain the original task surfaces. */
    private void drawLiveEmptyHints(android.graphics.Canvas canvas, MiStepLayout original,
            MiStepLayout target, int revealEmptySlots) {
        // Match the resting side background, including gaps outside the hint bounds.
        canvas.drawColor(HyperOsStyle.topWallpaperTint(ctx));
        int outer = canvas.save();
        try {
            canvas.scale((float) wallpaperBackdrop.getWidth() / original.width,
                    (float) wallpaperBackdrop.getHeight() / original.height);
            for (int i = 1; i < SLOT_COUNT; i++) {
                Slot slot = slots[i];
                boolean revealed = (revealEmptySlots & (1 << i)) != 0;
                if (slot.hint == null || (!revealed
                        && (slot.occupied || slot.hint.getVisibility() != View.VISIBLE))) continue;
                // Old cells remain until naturally covered by the growing app; target
                // cells emerge below the shrinking app. Finish crops to the target side.
                // A revealed cell paints only its Host hint underneath the moving task.
                int[] before = original.cellBounds(i - 1), after = target.cellBounds(i - 1);
                int[][] positions = java.util.Arrays.equals(before, after)
                        ? new int[][]{before} : new int[][]{before, after};
                for (int[] rect : positions) {
                    int saved = canvas.save();
                    try {
                        canvas.translate(rect[0], rect[1]);
                        canvas.clipRect(0, 0, rect[2] - rect[0], rect[3] - rect[1]);
                        if (revealed) {
                            // An occupied hint may still have its pre-wallpaper fallback.
                            // Paint fresh empty chrome rather than revealing that dark tile.
                            WallpaperCropDrawable empty = new WallpaperCropDrawable(wallpaperBackdrop,
                                    original.width, original.height, rect[0], rect[1],
                                    HyperOsStyle.topWallpaperTint(ctx));
                            empty.setBounds(0, 0, rect[2] - rect[0], rect[3] - rect[1]);
                            empty.draw(canvas);
                            drawEmptyCellPlus(canvas, rect[2] - rect[0], rect[3] - rect[1],
                                    HyperOsStyle.textSecondary(ctx), new android.graphics.Paint(
                                            android.graphics.Paint.ANTI_ALIAS_FLAG));
                            continue;
                        }
                        android.graphics.drawable.Drawable background = slot.hint.getBackground();
                        if (background instanceof WallpaperCropDrawable) {
                            ((WallpaperCropDrawable) background).withScreenOrigin(rect[0], rect[1],
                                    () -> slot.hint.draw(canvas));
                        } else slot.hint.draw(canvas);
                    } finally { canvas.restoreToCount(saved); }
                }
            }
        } finally { canvas.restoreToCount(outer); }
    }

    /** B covers the existing Home; Home, Host direction and WMS Z do not participate. */
    private void startLiveHome(Promotion ticket) {
        if (!externalPromotionCurrent(ticket) || ticket.previousMain != null || ticket.homeBeforeCover == null) {
            failPromotion(ticket, "live Home scene/identity changed before preparation");
            return;
        }
        final MiStepLayout geometry = layout;
        final int width = screenW, height = screenH;
        final Object hostSurface;
        try {
            hostSurface = liveHostRootSurface();
            Rect side = liveSideStrip(geometry);
            ticket.liveBackdrop = MiStepLiveBackdrop.create(hostSurface, wallpaperBackdrop, width, height,
                    side, side, canvas -> drawLiveEmptyHints(canvas, geometry, geometry,
                            1 << ticket.slot.index), MiStepHost::log);
        }
        catch (Throwable error) { failPromotion(ticket, "live Home Host preparation: " + error); return; }
        LiveSwapGeometry.Task app = new LiveSwapGeometry.Task(ticket.taskId, ticket.token, width, height,
                liveCellRect(geometry, ticket.slot.index), liveMainRect(geometry));
        final LiveSwapZoom.Zoom zoom = new LiveSwapZoom.Zoom(++zoomCommandGeneration,
                geometry.mainScale, geometry.mainOffsetX, geometry.mainOffsetY);
        ticket.nativeSwap = true;
        ticket.nativeHome = true;
        ticket.previousLayout = ticket.targetLayout = geometry;
        ticket.slot.promoting = true;
        OwnedTask owned = ownedTasks.get(ticket.token);
        if (owned != null) { owned.recoveryController = ticket.slot.controller; owned.recoveryCandidate = true; }
        ticket.liveVisual = new LiveSwapVisualLease(sideBackingViews[ticket.slot.index], ticket.slot.hint);
        if (!setLiveSwapInputBlocked(true)) {
            failPromotion(ticket, "transition input window unavailable"); return;
        }
        statusBar.runAfterHiddenFrame(ticket, () -> afterLiveHostFrame(ticket, () -> {
            if (!externalPromotionCurrent(ticket) || layout != geometry) {
                failPromotion(ticket, "live Home input frame scene changed");
                return;
            }
            if (!executeOnShell(() -> {
                boolean accepted = false;
                try {
                    if (externalPromotionCurrent(ticket) && layout == geometry) {
                        Object shell = XposedHelpers.getObjectField(transitions, "mTransitions");
                        if (SystemClock.uptimeMillis() < zoomAnimUntil || !Boolean.TRUE.equals(
                                XposedHelpers.callMethod(shell, "isIdle")))
                            throw new IllegalStateException("workspace or native transition still animating");
                        Object info = rootInfo(new LiveTask(ticket.slot));
                        if (info == null) throw new IllegalStateException("live Home app disappeared");
                        int user = XposedHelpers.getIntField(info, "userId");
                        if (owned != null) owned.userId = user;
                        LiveTaskSwapDriver.Home request = new LiveTaskSwapDriver.Home(app,
                                ticket.homeBeforeCover.id, ticket.homeBeforeCover.token, user,
                                new Rect(0, 0, width, height), hostSurface, zoom,
                                new Object[]{ticket.slot.cellBackground}, ticket.liveBackdrop);
                        accepted = LiveTaskSwapDriver.startHome(ctx.getClassLoader(), transitions,
                                ticket.slot.controller, request,
                                () -> externalPromotionCurrent(ticket) && layout == geometry,
                                frames -> { /* Real task input follows the same surface transaction. */ },
                                result -> finishLiveResult(ticket, result), MiStepHost::log);
                        ticket.nativeSubmitted = accepted;
                    }
                } catch (Throwable error) { log("native Home promotion rejected: " + error); }
                if (!accepted) main.post(() -> finishLiveHome(ticket, new MiStepLiveSwap.Result(false,
                        true, new IllegalStateException("native Home preflight refused; no fallback transition"))));
            })) failPromotion(ticket, "live Home Shell queue unavailable");
        }));
    }

    /** Commit the Host visual/input hole itself before moving real tasks below its window. */
    private void afterLiveHostFrame(Promotion ticket, Runnable action) {
        final View drawingRoot = root;
        if (!externalPromotionCurrent(ticket) || drawingRoot == null
                || !drawingRoot.isAttachedToWindow() || !drawingRoot.isHardwareAccelerated()) {
            failPromotion(ticket, "live Host draw cannot commit");
            return;
        }
        try {
            drawingRoot.getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
                if (root == drawingRoot && externalPromotionCurrent(ticket)) action.run();
                else failPromotion(ticket, "live Host frame scene changed");
            }));
            drawingRoot.invalidate();
        } catch (Throwable error) { failPromotion(ticket, "live Host frame registration: " + error); }
    }

    private boolean holdsLiveFlipZoom() {
        Promotion active = promotion;
        return active != null && active.nativeFlip && !active.cancelled;
    }

    /** Recovery may run after screen-off, so this deliberately omits entry-only guards. */
    private boolean livePromotionBound(Promotion ticket) {
        return promotion == ticket && !ticket.cancelled && isActive(ticket.slot)
                && sceneEpoch == ticket.sceneEpoch && ticket.slot.taskEpoch == ticket.bindingEpoch
                && ticket.slot.taskId == ticket.taskId && ticket.token.equals(ticket.slot.taskToken);
    }

    private final class LiveZoomRequest implements Runnable {
        final Promotion ticket;
        final LiveSwapZoom.Zoom expected;
        final java.util.function.Consumer<LiveSwapZoom.Ack> completed;
        boolean delivered;
        LiveZoomRequest(Promotion ticket, LiveSwapZoom.Zoom expected,
                java.util.function.Consumer<LiveSwapZoom.Ack> completed) {
            this.ticket = ticket; this.expected = expected; this.completed = completed;
        }
        void complete(LiveSwapZoom.Ack ack) {
            if (delivered) return;
            delivered = true;
            main.removeCallbacks(this);
            if (pendingLiveZoom == this) pendingLiveZoom = null;
            completed.accept(ack);
        }
        void failed(String reason) {
            complete(new LiveSwapZoom.Ack(expected.generation, 0L,
                    expected.scale, expected.x, expected.y, false, reason));
        }
        @Override public void run() { failed("WMS live zoom commit timed out"); }
    }

    private boolean registerLiveZoomReceiver() {
        if (liveZoomReceiver != null) return true;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                LiveZoomRequest pending = pendingLiveZoom;
                if (intent == null || !LiveSwapZoomProtocol.ACK.equals(intent.getAction())) return;
                Object replay;
                try {
                    replay = intent.getParcelableExtra(LiveSwapZoomProtocol.FINISH_REPLAY,
                            android.view.SurfaceControl.Transaction.class);
                } catch (RuntimeException unreadable) {
                    if (pending != null) pending.failed("WMS finish replay parcel unreadable");
                    return;
                }
                if (pending == null || intent == null
                        || !LiveSwapZoomProtocol.ACK.equals(intent.getAction())
                        || intent.getLongExtra(LiveSwapZoomProtocol.GENERATION, -1L)
                                != pending.expected.generation) {
                    LiveSwapZoom.closeReplay(replay); return;
                }
                if (!livePromotionBound(pending.ticket)) {
                    LiveSwapZoom.closeReplay(replay);
                    pending.failed("live zoom ticket changed before WMS commit");
                    return;
                }
                pending.complete(new LiveSwapZoom.Ack(pending.expected.generation,
                        intent.getLongExtra(LiveSwapZoomProtocol.TRANSACTION, 0L),
                        Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.SCALE, 0)),
                        Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.X, 0)),
                        Float.intBitsToFloat(intent.getIntExtra(LiveSwapZoomProtocol.Y, 0)),
                        intent.getBooleanExtra(LiveSwapZoomProtocol.COMMITTED, false),
                        intent.getStringExtra(LiveSwapZoomProtocol.ERROR), replay));
            }
        };
        try {
            ctx.registerReceiver(receiver, new IntentFilter(LiveSwapZoomProtocol.ACK),
                    LiveSwapZoomProtocol.PERMISSION, main, Context.RECEIVER_EXPORTED);
            liveZoomReceiver = receiver;
            return true;
        } catch (Throwable error) {
            log("live zoom receiver unavailable: " + error);
            return false;
        }
    }

    /** WMS acknowledgement only; the native driver separately awaits the Host layout frame. */
    private void applyLiveZoom(Promotion ticket, LiveSwapZoom.Zoom target,
            java.util.function.Consumer<LiveSwapZoom.Ack> completed) {
        main.post(() -> {
            LiveZoomRequest request = new LiveZoomRequest(ticket, target, completed);
            if (!livePromotionBound(ticket) || !ticket.nativeFlip
                    || !registerLiveZoomReceiver()) {
                request.failed("live zoom ticket/receiver unavailable");
                return;
            }
            if (pendingLiveZoom != null) pendingLiveZoom.failed("live zoom superseded by recovery");
            pendingLiveZoom = request;
            zoomCommandGeneration = Math.max(zoomCommandGeneration, target.generation);
            zoomAnimUntil = SystemClock.uptimeMillis() + LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS;
            ticket.liveZoomGuardUntil = zoomAnimUntil;
            long issuedAt = System.currentTimeMillis();
            // ZOOM64 renews the WM lease itself. Ordinary transports would eagerly
            // reset the leashes while they still belong to the animation parent.
            // Publish their steady tuple only after the native finish commits.
            Intent intent = new Intent(LiveSwapZoomProtocol.APPLY).setPackage("android");
            intent.putExtra(LiveSwapZoomProtocol.GENERATION, target.generation);
            intent.putExtra(LiveSwapZoomProtocol.SCALE, Float.floatToIntBits(target.scale));
            intent.putExtra(LiveSwapZoomProtocol.X, Float.floatToIntBits(target.x));
            intent.putExtra(LiveSwapZoomProtocol.Y, Float.floatToIntBits(target.y));
            intent.putExtra(LiveSwapZoomProtocol.ISSUED, issuedAt);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            main.postDelayed(request, LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS);
            try { ctx.sendBroadcast(intent, LiveSwapZoomProtocol.PERMISSION); }
            catch (Throwable error) { request.failed("live zoom dispatch failed: " + error); }
        });
    }

    /** This callback confirms only the target Host frame, never the WMS scaling transaction. */
    private void applyLiveMainLayout(Promotion ticket, LiveSwapZoom.Zoom zoom, boolean restoring,
            java.util.function.Consumer<LiveSwapZoom.FrameAck> completed) {
        main.post(() -> {
            MiStepLayout target = restoring ? ticket.previousLayout : ticket.targetLayout;
            View drawingRoot = root;
            java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.function.Consumer<String> fail = error -> {
                if (delivered.compareAndSet(false, true))
                    completed.accept(new LiveSwapZoom.FrameAck(zoom.generation, false, error));
            };
            if (!livePromotionBound(ticket) || !ticket.nativeFlip || target == null
                    || !zoom.sameTuple(target.mainScale, target.mainOffsetX, target.mainOffsetY)
                    || drawingRoot == null || !drawingRoot.isAttachedToWindow()
                    || !drawingRoot.isHardwareAccelerated()) {
                fail.accept("live Host layout generation/scene unavailable");
                return;
            }
            Runnable timeout = () -> fail.accept("live Host layout frame timed out");
            try {
                drawingRoot.getViewTreeObserver().registerFrameCommitCallback(() -> main.post(() -> {
                    if (!livePromotionBound(ticket) || root != drawingRoot || layout != target) {
                        fail.accept("live Host layout changed before frame commit");
                    } else if (delivered.compareAndSet(false, true)) {
                        main.removeCallbacks(timeout);
                        completed.accept(new LiveSwapZoom.FrameAck(zoom.generation, true, null));
                    }
                }));
                main.postDelayed(timeout, LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS);
                applyMainLayout(target);
            } catch (Throwable error) {
                main.removeCallbacks(timeout);
                fail.accept("live Host layout failed: " + error);
            }
        });
    }

    /** Already on Shell's completion turn, after exact roles and the finish Surface commit. */
    private void finishLiveResult(Promotion ticket, MiStepLiveSwap.Result result) {
        boolean mainReady = result.rolesConfirmed && (ticket.nativeCollect ? result.sideOwned
                : ticket.nativeHome ? result.succeeded && !result.sideOwned : result.sideOwned);
        if (mainReady && externalPromotionCurrent(ticket)) {
            try { ticket.liveMainFocusResult = requestLiveMainFocus(ticket); }
            catch (Throwable error) {
                ticket.liveMainFocusResult = PromotionFocusTransfer.Result.UNAVAILABLE;
                log("live main focus request unavailable: " + error);
            }
            ticket.liveMainFocusPrepared = true;
            log("live main focus result=" + ticket.liveMainFocusResult + " task="
                    + (ticket.nativeCollect ? ticket.collectionHome == null ? -1
                            : ticket.collectionHome.id : ticket.taskId)
                    + " uptime=" + SystemClock.uptimeMillis());
        }
        main.post(() -> {
            if (ticket.nativeCollect) finishLiveCollect(ticket, result);
            else if (ticket.nativeHome) finishLiveHome(ticket, result);
            else finishLiveSwap(ticket, result);
        });
    }

    private PromotionFocusTransfer.Result requestLiveMainFocus(Promotion ticket) throws Exception {
        final LiveTask home = ticket.nativeCollect ? ticket.collectionHome : null;
        if (ticket.nativeCollect && home == null) return PromotionFocusTransfer.Result.UNAVAILABLE;
        final int id = home == null ? ticket.taskId : home.id;
        final Object token = home == null ? ticket.token : home.token;
        final int user = currentUserId();
        Object service = XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityTaskManager"), "getService");
        java.util.List<?> roots = (java.util.List<?>) XposedHelpers.callMethod(service, "getAllRootTaskInfos");
        if (home != null) for (Object info : roots) {
            if (XposedHelpers.getIntField(info, "displayId") != 0
                    || !XposedHelpers.getBooleanField(info, "isFocused")) continue;
            Object focused = XposedHelpers.getObjectField(info, "token");
            // A new main app or native freeform has priority over returning Home.
            // The collected task and other exact side tasks never own main input.
            if (!token.equals(focused) && !ticket.token.equals(focused)
                    && !ticket.otherSideTokens.contains(focused))
                return PromotionFocusTransfer.Result.UNAVAILABLE;
        }
        return PromotionFocusTransfer.request(new PromotionFocusTransfer.Tasks() {
            public PromotionFocusTransfer.State read(int taskId) {
                for (Object info : roots) {
                    if (XposedHelpers.getIntField(info, "taskId") != taskId
                            || (home == null ? !ordinaryRoot(info, 1)
                                    : !isHomeRoot(info) || windowModeOf(info) != 1)) continue;
                    return new PromotionFocusTransfer.State(XposedHelpers.getObjectField(info, "token"),
                            XposedHelpers.getIntField(info, "displayId"),
                            XposedHelpers.getIntField(info, "userId"), windowModeOf(info),
                            XposedHelpers.getBooleanField(info, "isVisible"),
                            XposedHelpers.getBooleanField(info, "isFocused"));
                }
                return null;
            }
            public void focus(int taskId) { XposedHelpers.callMethod(service, "setFocusedTask", taskId); }
        }, id, token, user);
    }

    private void finishLiveSwap(Promotion ticket, MiStepLiveSwap.Result result) {
        if (promotion != ticket || ticket.cancelled || ticket.nativeCompleting || !isActive(ticket.slot)) return;
        if (sceneEpoch != ticket.sceneEpoch || ticket.slot.taskEpoch != ticket.bindingEpoch
                || ticket.slot.taskId != ticket.taskId || !ticket.token.equals(ticket.slot.taskToken)) {
            log("stale native completion rejected; scene or original slot binding changed");
            return;
        }
        ticket.nativeCompleting = true;
        // This is a confirmed native finish, after the exact WMS/Host commits.
        // REQUEST_TIMEOUT_MS is a pending-request deadline, not a tap cooldown.
        if (result.rolesConfirmed && ticket.nativeFlip && pendingLiveZoom == null
                && ticket.liveZoomGuardUntil > 0L && zoomAnimUntil == ticket.liveZoomGuardUntil) {
            zoomAnimUntil = 0L;
            writeZoom(true);
        }
        Slot slot = ticket.slot;
        if (result.sideOwned) {
            // Facade has retired B and bound the same controller to A already.
            // Clear only the old logical slot; never adopt/relaunch A a second time.
            ownedTasks.remove(ticket.token);
            clearSlot(slot);
            bindTask(slot, ticket.previousMain.id, ticket.previousMain.token);
            if (ticket.previousMainCandidate != null) ticket.previousMainCandidate.recoveryCandidate = false;
            slot.pkg = ticket.previousMain.pkg;
            slot.occupied = true;
            slot.taskVisible = true;
            slot.windowMode = 6;
            slot.promoting = false;
            slot.launching = false;
            slot.parked = false;
            updateHint(slot);
            finishPromotionFocus(ticket, () -> {
                if (promotion != ticket || ticket.cancelled) return;
                traceSwapEvent("single-native-finish-and-focus");
                ticket.cancelled = true;
                promotion = null;
                coveredHome = null;
                restoreLiveSwapVisual(ticket);
                statusBar.finish(ticket);
                publishDesktopSideTasks();
                log("single native swap roles settled main=" + ticket.taskId + " side="
                        + slot.taskId + " animated=" + result.succeeded + " error=" + result.error);
                finishPendingSuspend();
            }, 0);
        } else {
            ticket.cancelled = true;
            promotion = null;
            boolean original = controllerOwns(slot, ticket.taskId, ticket.token)
                    && mayWriteSideSurface(slot) && result.rolesConfirmed;
            if (original) {
                if (ticket.previousMainCandidate != null)
                    ownedTasks.remove(ticket.previousMainCandidate.token, ticket.previousMainCandidate);
                OwnedTask retained = ownedTasks.get(ticket.token);
                if (retained != null) retained.recoveryCandidate = false;
            } else if (!ticket.nativeSubmitted && ticket.previousMainCandidate != null) {
                // Facade refused before a WCT, so this fullscreen A was never ours.
                ownedTasks.remove(ticket.previousMainCandidate.token, ticket.previousMainCandidate);
            }
            slot.promoting = !original;
            slot.frozen = !original;
            restoreLiveSwapVisual(ticket);
            statusBar.finish(ticket);
            publishDesktopSideTasks();
            log("single native swap unconfirmed; original side retained=" + original + " error=" + result.error);
            finishPendingSuspend();
        }
    }

    private void finishLiveHome(Promotion ticket, MiStepLiveSwap.Result result) {
        if (!livePromotionBound(ticket) || ticket.nativeCompleting) return;
        ticket.nativeCompleting = true;
        Slot slot = ticket.slot;
        if (result.succeeded && !result.sideOwned && result.rolesConfirmed) {
            // The facade already gave B to the existing fullscreen listener and committed finishT.
            ticket.handoffAttempted = ticket.handoffComplete = true;
            ownedTasks.remove(ticket.token);
            clearSlot(slot);
            finishPromotionFocus(ticket, () -> {
                if (promotion != ticket || ticket.cancelled) return;
                restoreLiveSwapVisual(ticket);
                statusBar.finish(ticket);
                completeHomePromotion(ticket);
            }, 0);
        } else {
            boolean original = result.sideOwned && result.rolesConfirmed
                    && controllerOwns(slot, ticket.taskId, ticket.token) && mayWriteSideSurface(slot);
            OwnedTask retained = ownedTasks.get(ticket.token);
            if (retained != null && original) retained.recoveryCandidate = false;
            ticket.cancelled = true;
            promotion = null;
            slot.promoting = !original;
            slot.frozen = !original;
            restoreLiveSwapVisual(ticket);
            statusBar.finish(ticket);
            publishDesktopSideTasks();
            log("live Home promotion unconfirmed; original side retained=" + original + " error=" + result.error);
            finishPendingSuspend();
        }
    }

    private void restoreLiveSwapVisual(Promotion ticket) {
        LiveSwapVisualLease visual = ticket.liveVisual;
        ticket.liveVisual = null;
        if (visual != null) visual.restore();
        for (LiveSwapVisualLease peer : ticket.peerVisuals) peer.restore();
        ticket.peerVisuals.clear();
        // Native ownership and the final logical bindings now agree. Reconcile
        // once, after the replacement, rather than hiding between clear/bind.
        for (Slot cell : slots) if (cell.index > SLOT_MAIN) updateCellSurfaceVisibility(cell);
        if (root != null) { root.requestLayout(); root.invalidate(); }
        if (ticket.liveBackdrop != null) {
            ticket.liveBackdrop.closeAfterFrame(root); ticket.liveBackdrop = null;
        }
        if (geometryRefreshAfterLive) {
            geometryRefreshAfterLive = false;
            scheduleDisplayGeometryRefresh();
        }
        // The draw also restores backing/hints and removes the wallpaper lease.
        // A gesture begun while the real tasks were borrowed remains consumed to UP.
        setLiveSwapInputBlocked(false);
        resumeNotificationLaunch(ticket);
    }

    private void enqueuePromotion(Promotion ticket) {
        if (promotion != ticket || ticket.cancelled) return;
        if (!executeOnShell(() -> {
            NativeObservation observation = null;
            try {
                if (ticket.cancelled) return;
                Object wct = fullscreenExitTransaction(ticket);
                traceSwapEvent("before-fullscreen-enqueue");
                // Our independent root is not a BubbleHelper child. Use the native queue with
                // the verified non-bubble transaction, without changing the global helper.
                // Keep the side matrix until the native mode change has settled. The actual
                // parent/matrix handoff is one atomic surface transaction in checkPromotion.
                observation = observeNativeCommit(ticket.token, () -> checkPromotion(ticket, 0));
                XposedHelpers.callMethod(transitions, "lambda$moveTaskViewToFullscreen$5",
                        wct, ticket.slot.controller);
            } catch (Throwable t) {
                cancelNativeObservation(observation);
                main.post(() -> failPromotion(ticket, "enqueue: " + t));
            }
        })) {
            failPromotion(ticket, "Shell executor unavailable");
        }
    }

    private void closeSwapPreview() {
        closeSwapPreview(null);
    }

    private void closeSwapPreview(Object fallbackStatusOwner) {
        if (promotion != null) stopLiveLift(promotion);
        if (swapPreview == null) {
            statusBar.finish(fallbackStatusOwner);
            return;
        }
        Object statusOwner = swapPreviewStatusOwner;
        SwapPreview closing = swapPreview;
        swapPreview = null;
        swapPreviewStatusOwner = null;
        // removeView can defer teardown: restore only after the matching root detaches.
        closing.close(() -> {
            statusBar.finish(statusOwner);
            statusBar.finish(fallbackStatusOwner);
        });
        if (root != null) root.requestLayout();
    }

    private void stopLiveLift(Promotion ticket) {
        if (ticket == null) return;
        if (ticket.liveLift != null) {
            if (swapPreview != null) swapPreview.seal();
            int[] current = ticket.slot.rect;
            Rect restore = current == null ? null
                    : new Rect(current[0], current[1], current[2], current[3]);
            ticket.liveLift.closeAndRestore(restore);
            ticket.liveLift = null;
        }
        if (ticket.slot.view != null) {
            ticket.slot.view.setElevation(0f);
            ticket.slot.view.requestLayout();
        }
    }

    private void closeCellMovePreview() {
        if (cellMovePreview == null) return;
        cellMovePreview.close();
        cellMovePreview = null;
        if (root != null) root.requestLayout();
    }

    private static void releaseCompactSnapshot(Promotion ticket) {
        if (ticket == null) return;
        if (ticket.compactSnapshot != null && !ticket.compactSnapshot.isRecycled()) {
            ticket.compactSnapshot.recycle();
        }
        ticket.compactSnapshot = null;
        ticket.compactSnapshotRect = null;
    }

    private void finishSwapPreview() {
        finishSwapPreview(0L);
    }

    private void finishSwapPreview(long settleMs) {
        finishSwapPreview(null, settleMs);
    }

    private void finishSwapPreview(Object statusOwner, long settleMs) {
        // Two host frames settle the replacement SurfaceView before revealing it.
        SwapPreview expected = swapPreview;
        Runnable reveal = () -> {
            // A no-preview completion also owns a lease. Old callbacks may never
            // restore a newer exchange, even when both captured a null bridge.
            if (swapPreview == expected) closeSwapPreview(statusOwner);
        };
        if (root != null) root.postOnAnimation(() -> root.postOnAnimation(() -> {
            if (settleMs <= 0L) reveal.run();
            else main.postDelayed(reveal, settleMs);
        }));
        else reveal.run();
    }

    private Object fullscreenExitTransaction(Promotion ticket) throws Exception {
        Object wct = Class.forName("android.window.WindowContainerTransaction")
                .getDeclaredConstructor().newInstance();
        Object token = ticket.token;
        XposedHelpers.callMethod(wct, "setWindowingMode", token, 0);
        XposedHelpers.callMethod(wct, "setInterceptBackPressedOnTaskRoot", token, false);
        XposedHelpers.callMethod(wct, "setTaskForceExcludedFromRecents", token, false);
        XposedHelpers.callMethod(wct, "setDisablePip", token, false);
        XposedHelpers.callMethod(wct, "setDisableLaunchAdjacent", token, false);
        XposedHelpers.callMethod(wct, "setAlwaysOnTop", token, false);
        XposedHelpers.callMethod(wct, "setFocusable", token, true);
        XposedHelpers.callMethod(wct, "setBounds", token, new Rect());
        XposedHelpers.callMethod(wct, "setForceTranslucent", token, false);
        XposedHelpers.callMethod(wct, "setHidden", token, false);
        XposedHelpers.callMethod(wct, "setTaskTrimmableFromRecents", token, true);
        XposedHelpers.callMethod(wct, "reorder", token, !ticket.background);
        // Side roots must remain above the opaque main task in WM's visibility traversal.
        // Their surfaces stay inside their own TaskViews and do not cover the main area.
        for (Object sideToken : ticket.otherSideTokens) {
            XposedHelpers.callMethod(wct, "setForceTranslucent", sideToken, true);
            XposedHelpers.callMethod(wct, "setAlwaysOnTop", sideToken, true);
            XposedHelpers.callMethod(wct, "setFocusable", sideToken, true);
            XposedHelpers.callMethod(wct, "reorder", sideToken, true);
        }
        Object owner = XposedHelpers.getObjectField(ticket.slot.controller, "mCaptionInsetsOwner");
        if (owner != null) {
            XposedHelpers.callMethod(wct, "removeInsetsSource", token, owner, 0,
                    android.view.WindowInsets.Type.captionBar());
        }
        return wct;
    }

    private void resetTaskMatrix(Object leash) throws Exception {
        if (leash == null) return;
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) return;
        Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                .getDeclaredConstructor().newInstance();
        try {
            XposedHelpers.callMethod(tx, "setMatrix", leash, 1f, 0f, 0f, 1f);
            XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
            XposedHelpers.callMethod(tx, "setWindowCrop", leash, new Rect(0, 0, screenW, screenH));
            XposedHelpers.callMethod(tx, "apply");
        } finally {
            closeSurfaceObject(tx, "close");
        }
    }

    /** Retire only our binding, then return its canonical task surface to the existing DA. */
    private void handoffPromotedTask(Promotion ticket) throws Throwable {
        if (ticket.handoffAttempted || ticket.cancelled) return;
        Object controller = ticket.slot.controller;
        Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
        Object lock = XposedHelpers.getObjectField(organizer, "mLock");
        synchronized (lock) {
            Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                    organizer, "mTasks")).get(ticket.taskId);
            if (appeared == null) return;
            Object info = XposedHelpers.callMethod(appeared, "getTaskInfo");
            Object token = XposedHelpers.getObjectField(info, "token");
            Object current = XposedHelpers.getObjectField(controller, "mTaskToken");
            if (!ticket.token.equals(token)
                    || XposedHelpers.getIntField(info, "taskId") != ticket.taskId
                    || XposedHelpers.getIntField(info, "displayId") != 0
                    || !supportsTaskUser(XposedHelpers.getIntField(info, "userId"))
                    || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                    || (!ticket.background && !XposedHelpers.getBooleanField(info, "isVisible"))
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))) return;
            Object assigned = XposedHelpers.callMethod(organizer, "getTaskListener", info, false);
            Object fallback = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                    organizer, "mTaskListeners")).get(-2); // target fullscreen listener type
            TaskHandoffPolicy.Owner owner = TaskHandoffPolicy.owner(
                    ticket.token, current, assigned, controller, fallback);
            if (ticket.candidateRecovery && assigned == fallback && fallback != null
                    && fallback != controller && !ticket.token.equals(current))
                owner = TaskHandoffPolicy.Owner.NATIVE_FULLSCREEN;
            if (owner == TaskHandoffPolicy.Owner.UNAVAILABLE) {
                throw new IllegalStateException("native fullscreen route is unavailable");
            }
            Object area = XposedHelpers.getObjectField(organizer, "mRootTaskDisplayAreaOrganizer");
            Object areaInfo = XposedHelpers.callMethod(area, "getDisplayAreaInfo", 0);
            Object parent = XposedHelpers.callMethod(area, "getDisplayAreaLeash", 0);
            Object canonical = XposedHelpers.callMethod(appeared, "getLeash");
            Object copy = getSurfaceField(controller, "mTaskLeash");
            if (areaInfo == null || XposedHelpers.getIntField(areaInfo, "featureId") != 1
                    || XposedHelpers.getIntField(areaInfo, "displayId") != 0
                    || parent == null || canonical == null
                    || (owner == TaskHandoffPolicy.Owner.OUR_CONTROLLER
                            && (copy == null || canonical == copy))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(parent, "isValid"))
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(canonical, "isValid"))) {
                throw new IllegalStateException("native parent/canonical surface is unavailable");
            }
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            boolean unregistered = false;
            try {
                // Prepare every surface operation before changing listener ownership.
                XposedHelpers.callMethod(area, "reparentToDisplayArea", 0, canonical, tx);
                XposedHelpers.callMethod(tx, "setMatrix", canonical, 1f, 0f, 0f, 1f);
                XposedHelpers.callMethod(tx, "setPosition", canonical, 0f, 0f);
                XposedHelpers.callMethod(tx, "setWindowCrop", canonical,
                        new Rect(0, 0, screenW, screenH));
                XposedHelpers.callMethod(tx, "setAlpha", canonical, 1f);
                XposedHelpers.callMethod(tx, ticket.background ? "hide" : "show", canonical);
                ticket.handoffAttempted = true;
                if (owner == TaskHandoffPolicy.Owner.OUR_CONTROLLER) {
                    XposedHelpers.callMethod(transitions, "unregisterTaskView", controller);
                    unregistered = true;
                    XposedHelpers.callMethod(organizer, "removeListener", controller);
                    if (XposedHelpers.callMethod(organizer, "getTaskListener", info, false) != fallback) {
                        throw new IllegalStateException("native fullscreen listener did not take ownership");
                    }
                }
                XposedHelpers.callMethod(tx, "apply");
                // Native removeListener hands off to fallback but does not notify the old owner.
                // This native callback retires/release its own copied handle, never canonical.
                if (owner == TaskHandoffPolicy.Owner.OUR_CONTROLLER) {
                    XposedHelpers.callMethod(controller, "onTaskVanished", info);
                    XposedHelpers.callMethod(transitions, "registerTaskView", controller);
                }
                unregistered = false;
                ticket.handoffComplete = true;
                traceSwapEvent("after-native-handoff");
                log("promote native handoff task=" + ticket.taskId + " owner="
                        + fallback.getClass().getName() + " parent=default-task-display-area");
            } finally {
                closeSurfaceObject(tx, "close");
                // A completed native detach may leave an empty view if re-registration failed.
                // Keep it frozen for exit rather than restoring a task now owned by fullscreen.
                if (unregistered) log("promote handoff interrupted; keep target frozen until exit");
            }
        }
    }

    /** At most three observations; notification or transaction submission alone is insufficient. */
    private void checkPromotion(Promotion ticket, int attempt) {
        if (promotion != ticket || ticket.cancelled || !isActive(ticket.slot)) return;
        if (!executeOnShell(() -> {
            boolean success = false;
            String detail;
            try {
                Object current = XposedHelpers.getObjectField(ticket.slot.controller, "mTaskToken");
                Object service = XposedHelpers.callStaticMethod(
                        Class.forName("android.app.ActivityTaskManager"), "getService");
                java.util.List<?> roots = (java.util.List<?>) XposedHelpers.callMethod(
                        service, "getAllRootTaskInfos");
                detail = "detached=" + (current == null);
                int visibleSides = 0;
                for (Object info : roots) {
                    Object rootToken = XposedHelpers.getObjectField(info, "token");
                    if (ticket.otherSideTokens.contains(rootToken)
                            && XposedHelpers.getBooleanField(info, "isVisible")) visibleSides++;
                }
                detail += " otherSidesVisible=" + visibleSides + "/" + ticket.otherSideTokens.size();
                for (Object info : roots) {
                    if (XposedHelpers.getIntField(info, "taskId") != ticket.taskId) continue;
                    Object config = XposedHelpers.getObjectField(info, "configuration");
                    Object winConfig = XposedHelpers.getObjectField(config, "windowConfiguration");
                    int mode = (Integer) XposedHelpers.callMethod(winConfig, "getWindowingMode");
                    boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
                    boolean ready = ticket.token.equals(XposedHelpers.getObjectField(info, "token"))
                            && mode == 1 && visible
                            && visibleSides == ticket.otherSideTokens.size();
                    if (ready && current != null && !ticket.handoffAttempted) {
                        handoffPromotedTask(ticket);
                        current = XposedHelpers.getObjectField(ticket.slot.controller, "mTaskToken");
                    }
                    success = current == null && mode == 1 && visible
                            && ready && (!ticket.handoffAttempted || ticket.handoffComplete);
                    detail += " mode=" + mode + " visible=" + visible
                            + " detachedNow=" + (current == null)
                            + " nativeHandoff=" + ticket.handoffComplete;
                    break;
                }
            } catch (Throwable t) {
                detail = "observe: " + t;
            }
            final boolean done = success;
            final String result = detail;
            main.post(() -> {
                if (promotion != ticket || ticket.cancelled || !isActive(ticket.slot)) return;
                log("promote observation task=" + ticket.taskId + " attempt=" + attempt + " " + result);
                if (done) {
                    traceSwapEvent("promotion-mode1-confirmed");
                    stopLiveLift(ticket);
                    ownedTasks.remove(ticket.token);
                    clearSlot(ticket.slot);
                    if (ticket.previousMain != null) {
                        releaseCompactSnapshot(ticket);
                        adoptExisting(ticket.slot, ticket.previousMain, () -> finishPromotionFocus(ticket, () -> {
                            traceSwapEvent("backfill-mode6-and-focus-confirmed");
                            ticket.cancelled = true;
                            promotion = null;
                            resumeNotificationLaunch(ticket);
                            // Keep the final captured frame opaque across the task-surface
                            // handoff; closing immediately exposed one black host frame.
                            coveredHome = null;
                            finishSwapPreview(ticket, 120L);
                            log("swap confirmed main=" + ticket.taskId + " side="
                                    + ticket.previousMain.id + " sameSlot=" + ticket.slot.index);
                            // 提升后的新主任务之前在副格里被设过 crop，必须清掉；否则主区顶部会残一条应用画面。
                            resetMainTaskLeash(ticket.taskId);
                            scheduleMainCropClear(400L);
                            finishPendingSuspend();
                        }, 0), error -> finishPromotionFocus(ticket, () -> {
                            ticket.cancelled = true;
                            promotion = null;
                            resumeNotificationLaunch(ticket);
                            closeSwapPreview(ticket);
                            log("swap backfill failed; both system tasks retained, no relaunch: " + error);
                            finishPendingSuspend();
                        }, 0));
                    } else {
                        finishPromotionFocus(ticket, () -> completeHomePromotion(ticket), 0);
                    }
                } else if (attempt < 2) {
                    main.postDelayed(() -> checkPromotion(ticket, attempt + 1), 700L);
                } else {
                    failPromotion(ticket, result);
                }
            });
        })) failPromotion(ticket, "Shell executor unavailable during observation");
    }

    /** The promoted binding is already retired; move the remaining owners into its gap. */
    private void completeHomePromotion(Promotion ticket) {
        if (promotion != ticket || ticket.cancelled) return;
        traceSwapEvent("Home-cover-and-focus-confirmed");
        ticket.cancelled = true;
        promotion = null;
        resumeNotificationLaunch(ticket);
        coveredHome = new HomeTaskReturn(ticket.taskId, ticket.token,
                ticket.homeBeforeCover.id, ticket.homeBeforeCover.token);
        releaseCompactSnapshot(ticket);
        finishSwapPreview(ticket, 0L);
        // Home and main geometry remain anchored. Only side owner positions change;
        // their controllers, task tokens and window/input state travel together.
        compactCells();
        if (root != null) root.requestLayout();
        log("home promotion confirmed task=" + ticket.taskId + "; side gap compacted");
        finishPendingSuspend();
    }

    /** Backfill can take focus again; make the focus request after that last WCT. */
    private void finishPromotionFocus(Promotion ticket, Runnable complete, int attempt) {
        if (promotion != ticket || ticket.cancelled) return;
        if (ticket.liveMainFocusPrepared) {
            // The live Shell callback already requested the exact main task.
            // Restore Host now; final-frame ownership still protects the handoff.
            complete.run();
            return;
        }
        final long epoch = sceneEpoch;
        final LiveTask home = ticket.nativeCollect ? ticket.collectionHome : null;
        if (ticket.nativeCollect && home == null) { complete.run(); return; }
        final int focusId = home == null ? ticket.taskId : home.id;
        final Object focusToken = home == null ? ticket.token : home.token;
        final int focusUser = currentUserId();
        if (scene != ACTIVE || suspendRequested || !screenOn) {
            complete.run();
            return;
        }
        if (!executeOnShell(() -> {
            PromotionFocusTransfer.Result result = PromotionFocusTransfer.Result.UNAVAILABLE;
            try {
                if (promotion == ticket && !ticket.cancelled && sceneEpoch == epoch
                        && scene == ACTIVE && !suspendRequested && screenOn) {
                    Object service = XposedHelpers.callStaticMethod(
                            Class.forName("android.app.ActivityTaskManager"), "getService");
                    PromotionFocusTransfer.Tasks tasks = new PromotionFocusTransfer.Tasks() {
                        public PromotionFocusTransfer.State read(int id) {
                            java.util.List<?> roots = (java.util.List<?>) XposedHelpers.callMethod(
                                    service, "getAllRootTaskInfos");
                            for (Object info : roots) {
                                if (XposedHelpers.getIntField(info, "taskId") != id
                                        || (home == null ? !ordinaryRoot(info, 1)
                                                : !isHomeRoot(info) || windowModeOf(info) != 1)) continue;
                                return new PromotionFocusTransfer.State(
                                        XposedHelpers.getObjectField(info, "token"),
                                        XposedHelpers.getIntField(info, "displayId"),
                                        XposedHelpers.getIntField(info, "userId"), windowModeOf(info),
                                        XposedHelpers.getBooleanField(info, "isVisible"),
                                        XposedHelpers.getBooleanField(info, "isFocused"));
                            }
                            return null;
                        }
                        public void focus(int id) {
                            XposedHelpers.callMethod(service, "setFocusedTask", id);
                        }
                    };
                    PromotionFocusTransfer.State initial = tasks.read(focusId);
                    if (initial != null) {
                        // Collection already revealed Home in its WCT. Never refocus it here:
                        // a new main app or native freeform may have taken focus during finish.
                        result = attempt == 0 && home == null
                                ? PromotionFocusTransfer.transfer(tasks, focusId,
                                        focusToken, focusUser)
                                : PromotionFocusTransfer.observe(tasks, focusId,
                                        focusToken, focusUser);
                    }
                }
            } catch (Throwable error) {
                log("promotion focus request failed task=" + focusId + ": " + error);
            }
            final PromotionFocusTransfer.Result observed = result;
            main.post(() -> {
                if (promotion != ticket || ticket.cancelled) return;
                log("promotion focus task=" + focusId + " attempt=" + attempt
                        + " result=" + observed + " afterBackfill=true");
                if (observed == PromotionFocusTransfer.Result.PENDING && attempt == 0
                        && sceneEpoch == epoch && scene == ACTIVE && !suspendRequested && screenOn) {
                    main.postDelayed(() -> finishPromotionFocus(ticket, complete, 1), 120L);
                } else complete.run();
            });
        })) {
            log("promotion focus unavailable: no Shell queue task=" + focusId);
            complete.run();
        }
    }

    private void failPromotion(Promotion ticket, String reason) {
        traceSwapEvent("promotion-failure");
        if (promotion != ticket || ticket.cancelled) return;
        if (ticket.nativeSwap) {
            requestLiveSwapCancellation(ticket, reason);
            return;
        }
        ticket.cancelled = true;
        promotion = null;
        releaseCompactSnapshot(ticket);
        if (ticket.liveBackdrop != null) { ticket.liveBackdrop.close(); ticket.liveBackdrop = null; }
        stopLiveLift(ticket);
        // 桥没有把主任务交给系统就失败时，必须把 view 级动画的变换还原，避免主窗留在格子里。
        if (ticket.mainLeash != null && !ticket.handoffAttempted) {
            try {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(ticket.mainLeash, "isValid"))) {
                    applyLeashTransform(ticket.mainLeash, 0f, 0f, 1f, 1f);
                    log("live main shrink restored after failure task=" + ticket.taskId);
                }
            } catch (Throwable error) {
                log("live main shrink restore failed: " + error);
            }
        }
        ticket.mainLeash = null;
        if (!ticket.handoffAttempted && ticket.targetMainOnRight != ticket.previousMainOnRight
                && mainOnRight != ticket.previousMainOnRight) {
            setMainSide(ticket.previousMainOnRight);
        }
        closeSwapPreview(ticket);
        Slot slot = ticket.slot;
        resumeNotificationLaunch(ticket);
        log("promote unconfirmed task=" + ticket.taskId + "; retain all slots: " + reason);
        if (ticket.externalLaunch) {
            // The native Launcher launch must never be undone by a mode6 rollback.
            // Retain the exact binding for the ordinary exit recovery path.
            slot.frozen = true;
            slot.promoting = true;
            log("external fullscreen handoff unconfirmed; retain binding for exit, no mode6 rollback");
            main.post(this::finishPendingSuspend);
            return;
        }
        if (ticket.handoffAttempted) {
            slot.frozen = true;
            log("promote native handoff was attempted; freeze until exit, without reclaiming task");
            main.post(this::finishPendingSuspend);
            return;
        }
        // Only restore a task still owned by this controller. Never remove/relaunch other cells.
        try {
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            if (ticket.token.equals(current) && isActive(slot)) {
                Object rollback = Class.forName("android.window.WindowContainerTransaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(rollback, "setWindowingMode", ticket.token, 6);
                XposedHelpers.callMethod(rollback, "setBounds", ticket.token, logicalBounds());
                XposedHelpers.callMethod(rollback, "setForceTranslucent", ticket.token, true);
                XposedHelpers.callMethod(rollback, "setAlwaysOnTop", ticket.token, true);
                XposedHelpers.callMethod(rollback, "setFocusable", ticket.token, true);
                applyWct("swap-rollback-same-task", rollback);
                slot.promoting = false;
                slot.scaleSurface = null;
                slot.scaleTaskId = Integer.MIN_VALUE;
                slot.scaleApplied = -1f;
                applyFullscreenBounds(slot);
                applySlotScale(slot);
            } else {
                log("promote task already detached; freeze cell until exit, without relaunching task");
            }
        } catch (Throwable t) {
            log("promote restore side failed: " + t);
        }
        main.post(this::finishPendingSuspend);
    }

    private void clearSlot(Slot slot) {
        slot.taskEpoch++;
        slot.removalCheckTask = -1;
        slot.retiredTaskId = slot.taskId;
        slot.occupied = false;
        slot.parked = false;
        slot.frozen = false;
        slot.promoting = false;
        slot.taskVisible = false;
        slot.taskId = -1;
        slot.taskToken = null;
        slot.windowMode = -1;
        slot.nativeReleasePending = false;
        slot.pendingSidePromotion = null;
        slot.launching = false;
        slot.injecting = false;
        slot.modeGuardUntil = 0L;
        if (slot.windowState != null) {
            slot.windowState.clear();
            synchronized (taskWindowStatesLock) {
                taskWindowStates.remove(slot.windowState.taskId);
            }
            slot.windowState = null;
        }
        slot.taskUid = 0;
        slot.pkg = null;
        slot.modeAdmissionPending = false;
        slot.modeRepairAttempts = 0;
        slot.scaleSurface = null;
        slot.scaleOnLeash = false;
        slot.scaleTaskId = Integer.MIN_VALUE;
        slot.scaleApplied = -1f;
        slot.scaleAttempts = 0;
        slot.reassertPending = false;
        slot.visibilityRepair.reset();
        recycleBuffered(slot);
        updateHint(slot);
        updateTouchRegion(slot);
        publishDesktopSideTasks();
        requestCompaction();
    }

    private boolean isActive(Slot slot) {
        return root != null && sInstance == this && slot.index > 0
                && slot.index < SLOT_COUNT && slots[slot.index] == slot;
    }

    private boolean canRender(Slot slot) {
        return isActive(slot) && !slot.parked && screenOn
                && (scene == ACTIVE || scene == RESUMING)
                && root.getVisibility() == View.VISIBLE;
    }

    private void postForSlot(Slot slot, Consumer<Slot> callback) {
        main.post(() -> { if (isActive(slot)) callback.accept(slot); });
    }

    /** Reject late callbacks from a task the native controller has already relinquished. */
    private boolean controllerOwns(Slot slot, int taskId, Object token) {
        try {
            Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            if (info != null) {
                ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
                if (base != null && "com.miui.home".equals(base.getPackageName())) return false;
            }
            return info != null && current != null
                    && XposedHelpers.getIntField(info, "taskId") == taskId
                    && (token == null || token.equals(current));
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestLiveSwapCancellation(Promotion ticket, String reason) {
        if (promotion != ticket || ticket.cancelled || ticket.cancelRequested || ticket.nativeCompleting) return;
        // WCT may already be applied while Shell still waits for onTransitionReady.
        // Keep this binding/ticket until the facade's unique recovery completion.
        ticket.cancelRequested = true;
        log("native swap cancellation requested; retain owner until recovery: " + reason);
        if (!executeOnShell(() -> {
            if (promotion != ticket || ticket.cancelled || ticket.nativeCompleting) return;
            if (ticket.nativeSubmitted) MiStepLiveSwap.cancel(ticket.slot.controller);
            else main.post(() -> {
                MiStepLiveSwap.Result result = new MiStepLiveSwap.Result(false, ticket.nativeHome,
                        new IllegalStateException("native swap cancelled before submission: " + reason));
                if (ticket.nativeCollect) finishLiveCollect(ticket, result);
                else if (ticket.nativeHome) finishLiveHome(ticket, result);
                else finishLiveSwap(ticket, result);
            });
        })) {
            // cancel() serializes itself on the original Shell executor.
            if (ticket.nativeSubmitted) MiStepLiveSwap.cancel(ticket.slot.controller);
            else {
                MiStepLiveSwap.Result result = new MiStepLiveSwap.Result(false, ticket.nativeHome,
                        new IllegalStateException("native cancellation Shell queue unavailable"));
                if (ticket.nativeCollect) finishLiveCollect(ticket, result);
                else if (ticket.nativeHome) finishLiveHome(ticket, result);
                else finishLiveSwap(ticket, result);
            }
        }
    }

    private void bindTask(Slot slot, int taskId, Object token) {
        if (slot.taskId != taskId || !java.util.Objects.equals(slot.taskToken, token)) {
            slot.pendingSidePromotion = null;
            slot.taskEpoch++;
            slot.removalCheckTask = -1;
            slot.visibilityRepair.reset();
            slot.scaleSurface = null;
            slot.scaleOnLeash = false;
            slot.scaleTaskId = Integer.MIN_VALUE;
            slot.scaleApplied = -1f;
            slot.scaleAttempts = 0;
            slot.taskUid = 0;
            slot.reassertPending = false;
            slot.windowMode = -1;
            slot.modeRepairAttempts = 0;
            recycleBuffered(slot);
        }
        slot.taskId = taskId;
        slot.taskToken = token;
        synchronized (taskWindowStatesLock) {
            slot.windowState = taskWindowStates.get(taskId);
            if (slot.windowState == null) {
                slot.windowState = new TaskWindowState(taskId);
                taskWindowStates.put(taskId, slot.windowState);
            }
        }
        slot.windowState.slotIndex = slot.index;
        OwnedTask retained = ownedTasks.computeIfAbsent(token, ignored -> new OwnedTask(taskId, token));
        retained.recoveryController = slot.controller;
    }

    private static int windowModeOf(Object info) {
        try {
            Object config = XposedHelpers.getObjectField(info, "configuration");
            Object winConfig = XposedHelpers.getObjectField(config, "windowConfiguration");
            return (Integer) XposedHelpers.callMethod(winConfig, "getWindowingMode");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int configurationOrientationOf(Object info) {
        try {
            Object config = XposedHelpers.getObjectField(info, "configuration");
            return XposedHelpers.getIntField(config, "orientation");
        } catch (Throwable ignored) {
            return TaskWindowState.UNKNOWN_ORIENTATION;
        }
    }

    private static int requestedOrientationOf(Object info) {
        try {
            return XposedHelpers.getIntField(info, "requestedOrientation");
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private void updateTaskWindowState(int slotIndex, Object info, int taskId, boolean active) {
        if (info == null || taskId < 0) return;
        try {
            int mode = windowModeOf(info);
            int configurationOrientation = configurationOrientationOf(info);
            int requestedOrientation = requestedOrientationOf(info);
            TaskWindowState state;
            synchronized (taskWindowStatesLock) {
                state = taskWindowStates.get(taskId);
                if (state == null) {
                    state = new TaskWindowState(taskId);
                    taskWindowStates.put(taskId, state);
                }
            }
            state.update(slotIndex, configurationOrientation, requestedOrientation,
                    TaskWindowState.UNKNOWN_ROTATION, mode, mode == 1, active);
            if (slotIndex > SLOT_MAIN && slotIndex < SLOT_COUNT) {
                slots[slotIndex].windowState = state;
            }
        } catch (Throwable error) {
            log("task window state update failed task=" + taskId + ": " + error);
        }
    }

    private void syncSlotWindowState(Slot slot) {
        if (slot != null && slot.windowState != null) {
            slot.windowState.slotIndex = slot.index;
        }
    }

    /**
     * TaskInfo 的多窗口能力标记：不支持的（例如相机）不是拒绝，而是走 mirror 承载。
     * 普通应用仍按原路线进 TaskView。
     */
    /** 规则：除桌面外都可进副格；系统标志不预拒绝，交给 native adoption 结果。 */
    private boolean launchActivitySupportsSide(Intent launch) {
        return true;
    }

    private void keepUnsupportedInMain(Slot slot, String pkg, String reason) {
        if (slot != null) {
            slot.launching = false;
            updateHint(slot);
            updateTouchRegion(slot);
        }
        log("side launch rejected slot=" + (slot == null ? -1 : slot.index)
                + " pkg=" + pkg + ": " + reason + "; task remains in main");
        android.widget.Toast.makeText(ctx, "该应用不支持副格，已在主区打开",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * TaskViewTaskController keeps mTaskNotFound after an idle task vanished without a token.
     * Reusing that controller otherwise makes the framework remove the next task immediately.
     */
    private void resetIdleTaskView(Slot slot) {
        if (slot == null || slot.controller == null) return;
        try {
            MiStepIdleTaskView.reset(slot.controller, text -> log(text + " slot=" + slot.index));
        } catch (Throwable error) {
            log("reset idle TaskView slot=" + (slot == null ? -1 : slot.index) + " failed: " + error);
        }
    }

    private void promoteExternalLaunch(Slot slot) {
        if (slot == null || slot.index <= SLOT_MAIN || !isActive(slot)) return;
        log("external launcher launch detected slot=" + slot.index
                + " task=" + slot.taskId + " mode=" + slot.windowMode + "; promote");
        queueSidePromotion(slot, false);
    }

    /**
     * Some system apps reject WINDOWING_MODE_MULTI_WINDOW and remain fullscreen.
     * Keep their task alive by opening it in the main pane instead of forcing a mode
     * change, which makes the framework destroy the task.
     */
    private void promoteUnsupportedSide(Slot slot) {
        if (slot == null || slot.index <= SLOT_MAIN || !isActive(slot)
                || slot.promoting || promotion != null || transferBusy) return;
        String pkg = slot.pkg == null ? "" : slot.pkg;
        slot.modeAdmissionPending = false;
        log("side mode unsupported slot=" + slot.index + " task=" + slot.taskId
                + " mode=" + slot.windowMode + " pkg=" + pkg + "; open in main");
        android.widget.Toast.makeText(ctx, "该应用不支持副格，已在主区打开",
                android.widget.Toast.LENGTH_SHORT).show();
        slot.modeGuardUntil = 0L;
        onSlotTapped(slot.index);
    }

    private void scheduleSideModeResolution(Slot slot, int taskId) {
        long delay = Math.max(220L,
                slot.modeGuardUntil - SystemClock.uptimeMillis() + 150L);
        main.postDelayed(() -> {
            if (!isActive(slot) || slot.taskId != taskId || slot.promoting
                    || slot.launching || slot.parked || !slot.occupied) return;
            if (slot.windowMode == 6) {
                slot.modeAdmissionPending = false;
                slot.modeRepairAttempts = 0;
                reapplyScale(slot);
            } else if (slot.windowMode == 1 && slot.modeAdmissionPending) {
                repairSideWindowMode(slot, taskId);
            }
        }, delay);
    }

    private void repairSideWindowMode(Slot slot, int taskId) {
        if (!isActive(slot) || slot.taskId != taskId || slot.promoting
                || slot.launching || slot.parked || !slot.modeAdmissionPending) return;
        long now = SystemClock.uptimeMillis();
        if (now < slot.modeGuardUntil) return;
        if (slot.modeRepairAttempts >= 2) {
            promoteUnsupportedSide(slot);
            return;
        }
        slot.modeRepairAttempts++;
        slot.modeGuardUntil = now + 850L;
        try {
            Object wct = Class.forName("android.window.WindowContainerTransaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(wct, "setWindowingMode", slot.taskToken, 6);
            XposedHelpers.callMethod(wct, "setBounds", slot.taskToken, logicalBounds());
            XposedHelpers.callMethod(wct, "setForceTranslucent", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setAlwaysOnTop", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setFocusable", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setHidden", slot.taskToken, false);
            XposedHelpers.callMethod(wct, "reorder", slot.taskToken, true);
            boolean applied = applyWct("initial-side-mode-repair-" + slot.modeRepairAttempts, wct);
            log("initial side mode repair slot=" + slot.index + " task=" + taskId
                    + " attempt=" + slot.modeRepairAttempts + " applied=" + applied);
        } catch (Throwable error) {
            log("initial side mode repair failed slot=" + slot.index + ": " + error);
        }
        main.postDelayed(() -> scheduleSideModeResolution(slot, taskId), 900L);
    }

    /** 与气泡同原语：IStatusBarService.hideCurrentInputMethodForBubbles(displayId)（BC L776）。 */
    private void hideIme() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "statusbar");
            Class<?> stub = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
            Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Method m = null;
            for (Class<?> c = svc.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("hideCurrentInputMethodForBubbles", int.class);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
            if (m == null) {
                log("hideIme: method not found");
                return;
            }
            m.setAccessible(true);
            m.invoke(svc, 0);
            log("hideIme invoked");
        } catch (Throwable t) {
            log("hideIme failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 启动 / 关闭任务

    private void launchPackage(int slotIndex, String pkg) {
        if (slotIndex <= SLOT_MAIN || slotIndex >= SLOT_COUNT) {
            log("launchPackage bad slot=" + slotIndex);
            return;
        }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null && pkg.contains("home")) {
            // 桌面（home）没有普通 LAUNCHER intent，用 CATEGORY_HOME 显式构造。
            i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setPackage(pkg);
            log("launchPackage: using CATEGORY_HOME for " + pkg);
        }
        if (i == null) {
            log("launchPackage: no launcher for " + pkg);
            return;
        }
        launchIntoSlot(slotIndex, i, pkg);
    }

    private void launchComponent(int slotIndex, ComponentName cn) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(cn);
        launchIntoSlot(slotIndex, i, cn.flattenToShortString());
    }

    private void launchIntoSlot(final int slotIndex, Intent launch, String label) {
        final Slot slot = slots[slotIndex];
        if (!isActive(slot) || slot.view == null || slot.launching || slot.promoting || promotion != null) {
            log("launchIntoSlot " + slotIndex + ": no view");
            return;
        }
        final String pkg = launch.getComponent() != null
                ? launch.getComponent().getPackageName() : launch.getPackage();
        if (pkg == null || "com.miui.home".equals(pkg)) return;
        for (Slot other : slots) {
            if (other != slot && (other.occupied || other.launching) && pkg.equals(other.pkg)) {
                log("launch rejected: package already in side slot=" + other.index);
                return;
            }
        }
        if (!boundsHooksReady || scene != ACTIVE || transferBusy) return;
        Runnable choose = () -> chooseExistingOrLaunch(slot, launch, label, pkg);
        if (slot.occupied) {
            returnToSystem(slot, false, () -> { clearSlot(slot); choose.run(); },
                    error -> log("replacement stopped; previous live task retained: " + error));
        } else choose.run();
    }

    private void chooseExistingOrLaunch(Slot slot, Intent launch, String label, String pkg) {
        if (!isActive(slot) || scene != ACTIVE) return;
        slot.launching = true;
        if (!executeOnShell(() -> {
            try {
                LiveTask existing = findPackageTask(pkg);
                main.post(() -> {
                    if (!isActive(slot) || scene != ACTIVE) { slot.launching = false; return; }
                    if (existing == null) {
                        startSlot(slot, launch, label);
                    } else {
                        slot.launching = false;
                        adoptExisting(slot, existing, this::finishPendingSuspend,
                                error -> {
                                    log("existing task admission failed; no launcher fallback: " + error);
                                    finishPendingSuspend();
                                });
                    }
                });
            } catch (Throwable error) {
                main.post(() -> { slot.launching = false; log("choose task stopped: " + error);
                    finishPendingSuspend(); });
            }
        })) { slot.launching = false; log("no Shell queue for selection"); }
    }

    private void startSlot(final Slot slot, Intent launch, String label) {
        if (!isActive(slot) || scene != ACTIVE || slot.promoting) { slot.launching = false; return; }
        final int index = slot.index;
        resetIdleTaskView(slot);
        prepareCellSurface(slot);
        slot.modeGuardUntil = SystemClock.uptimeMillis() + 2500L;
        slot.modeRepairAttempts = 0;
        slot.modeAdmissionPending = true;
        slot.pkg = launch.getComponent() != null
                ? launch.getComponent().getPackageName() : launch.getPackage();
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // v0.9：任务一律按"整屏手机"起（1080×2460），完整 UI 不受窗口最小宽限制；
        // 显示时用矩阵缩放塞进格子（官方Mi Step的等比缩小语义）。
        Rect rect = new Rect(0, 0, screenW, screenH);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1000 + index, launch,
                PendingIntent.FLAG_IMMUTABLE);
        ActivityOptions options = ActivityOptions.makeBasic();
        callHidden(options, "setLaunchWindowingMode", 6);          // WINDOWING_MODE_MULTI_WINDOW
        callHidden(options, "setRemoveWithTaskOrganizer", Boolean.FALSE);
        try {
            Method start = findStartActivity(slot.view.getClass());
            if (start == null) {
                slot.launching = false;
                log("startSlot " + index + ": startActivity not found");
                return;
            }
            start.setAccessible(true);
            start.invoke(slot.view, pi, launch, options, rect);
            launchCurrentSlot = slot;
            main.postDelayed(() -> {
                if (launchCurrentSlot == slot) {
                    launchCurrentSlot = null;
                    slot.launching = false;
                    log("launch watchdog fired slot=" + index + " label=" + label);
                    finishPendingSuspend();
                }
            }, LAUNCH_TIMEOUT_MS);
            log("startActivity slot=" + index + " label=" + label
                    + " pkg=" + launch.getPackage() + " rect=" + rect);
        } catch (Throwable t) {
            slot.launching = false;
            log("startSlot " + index + " FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    private static void callHidden(Object target, String name, Object... args) {
        try {
            XposedHelpers.callMethod(target, name, args);
        } catch (Throwable t) {
            log("hidden " + name + " failed: " + t);
        }
    }

    private static Method findStartActivity(Class<?> cls) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod("startActivity",
                        PendingIntent.class, Intent.class, ActivityOptions.class, Rect.class);
            } catch (NoSuchMethodException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 任务状态 / 密度 / 可见性

    private Object makeListener(final int slotIndex) {
        final Slot owner = slots[slotIndex];
        try {
            Class<?> iface = XposedHelpers.findClass(
                    "com.android.wm.shell.taskview.TaskView$Listener", ctx.getClassLoader());
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                String name = method.getName();
                                if ("onTaskCreated".equals(name) && args != null && args.length >= 1) {
                                    final int taskId = (Integer) args[0];
                                    postForSlot(owner, s -> onTaskIdKnown(s.index, taskId));
                                } else if ("onTaskInfoChanged".equals(name)
                                        && args != null && args.length >= 1) {
                                    final Object info = args[0];
                                    postForSlot(owner, s -> onTaskInfo(s.index, info));
                                } else if ("onTaskVisibilityChanged".equals(name)
                                        && args != null && args.length >= 2) {
                                    final int taskId = (Integer) args[0];
                                    final boolean visible = (Boolean) args[1];
                                    postForSlot(owner, s -> onTaskVisibility(s.index, taskId, visible));
                                } else if ("onTaskRemovalStarted".equals(name)
                                        && args != null && args.length >= 1) {
                                    final int taskId = (Integer) args[0];
                                    postForSlot(owner, s -> onTaskRemovalStarted(s.index, taskId));
                                } else if ("onInitialized".equals(name)) {
                                    log("listener#onInitialized slot=" + owner.index);
                                    postForSlot(owner, s -> { updateHint(s); setWorkspaceAlpha(workspaceAlpha); });
                                } else {
                                    log("listener#" + name + " slot=" + slotIndex);
                                }
                            } catch (Throwable t) {
                                log("listener dispatch failed: " + t);
                            }
                            return null;
                        }
                    });
        } catch (Throwable t) {
            log("makeListener failed: " + t);
            return null;
        }
    }

    private void onTaskIdKnown(int slotIndex, int taskId) {
        Slot slot = slots[slotIndex];
        if (slot.promoting || slot.parked || slot.retiredTaskId == taskId || !controllerOwns(slot, taskId, null)) return;
        Object token = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
        if (token == null || !controllerOwns(slot, taskId, token)) return;
        bindTask(slot, taskId, token);
        slot.occupied = true;
        slot.launching = false;
        if (slot == launchCurrentSlot) {
            launchCurrentSlot = null;
        }
        log("onTaskCreated slot=" + slotIndex + " taskId=" + taskId);
        updateHint(slot);
        updateTouchRegion(slot);
        finishPendingSuspend();
        main.postDelayed(this::compactCells, 120L);
        if (slotIndex > SLOT_MAIN) scheduleSideModeResolution(slot, taskId);
    }

    /** 目标机 TaskView 的主要状态回调：taskId / token / 可见性都从这里取。 */
    private void onTaskInfo(int slotIndex, Object info) {
        Slot slot = slots[slotIndex];
        try {
            int taskId = XposedHelpers.getIntField(info, "taskId");
            if (slot.promoting || slot.parked || slot.retiredTaskId == taskId) return;
            boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
            boolean running = XposedHelpers.getBooleanField(info, "isRunning");
            Object token = XposedHelpers.getObjectField(info, "token");
            int mode = windowModeOf(info);
            if (slotIndex > SLOT_MAIN && mode == 5 && slot.occupied
                    && taskId == slot.taskId && token != null && token.equals(slot.taskToken)) {
                releaseNativeFreeformSide(slot, taskId, token);
                return;
            }
            if (token == null || !controllerOwns(slot, taskId, token)) return;
            boolean surfaceGeometryChanged = slot.taskId != taskId || slot.windowMode != mode;
            boolean changed = slot.taskId != taskId || slot.occupied != running
                    || slot.taskVisible != visible || slot.windowMode != mode;
            bindTask(slot, taskId, token);
            ComponentName actual = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            if (actual != null) slot.pkg = actual.getPackageName();
            slot.occupied = running;
            slot.taskVisible = visible;
            slot.windowMode = mode;
            if (!running && changed) requestCompaction();
            if (slotIndex > SLOT_MAIN && mode == 6) {
                slot.modeAdmissionPending = false;
                slot.modeRepairAttempts = 0;
            }
            updateTaskWindowState(slotIndex, info, taskId, running);
            if (changed) {
                log("taskInfo slot=" + slotIndex + " task=" + taskId
                        + " running=" + running + " visible=" + visible
                        + " mode=" + mode
                        + " appBounds=" + appBoundsOf(info));
                updateHint(slot);
                updateTouchRegion(slot);
            }
            if (running) {
                // Wait for the native leash first. Early bounds writes can interrupt prepareOpen.
                reapplyScale(slot);
            }
            if (running && slotIndex > SLOT_MAIN && mode == 6 && surfaceGeometryChanged) {
                scheduleSideSurfaceRestore(slot, taskId, token, slot.taskEpoch, sceneEpoch);
            }
            if (running && !visible) scheduleSideVisibilityRepair(slot);
            if (running && mode == 1 && slotIndex > SLOT_MAIN
                    && !slot.promoting && !slot.launching && !slot.parked) {
                if (slot.modeAdmissionPending) {
                    if (SystemClock.uptimeMillis() >= slot.modeGuardUntil)
                        repairSideWindowMode(slot, taskId);
                    else scheduleSideModeResolution(slot, taskId);
                } else promoteExternalLaunch(slot);
            } else if (slot.pendingSidePromotion != null
                    && !slot.pendingSidePromotion.desktopLaunch && mode != 1) {
                slot.pendingSidePromotion = null;
            }
            if (changed) publishDesktopSideTasks();
        } catch (Throwable t) {
            log("onTaskInfo slot=" + slotIndex + " failed: " + t);
        }
    }

    private void releaseNativeFreeformSide(Slot slot, int taskId, Object token) {
        if (slot.nativeReleasePending) return;
        final long binding = slot.taskEpoch;
        final Object controller = slot.controller;
        slot.nativeReleasePending = true;
        slot.frozen = true;
        slot.windowMode = 5;
        slot.pendingSidePromotion = null;
        recycleBuffered(slot);
        if (!executeOnShell(() -> {
            boolean released = false;
            try {
                if (slot.taskEpoch == binding && slot.taskId == taskId && token.equals(slot.taskToken))
                    released = MiStepNativeSideRelease.release(controller, transitions, taskId, token);
            } catch (Throwable error) { log("native side release task=" + taskId + " failed: " + error); }
            final boolean done = released;
            postForSlot(slot, owner -> {
                if (owner.taskEpoch != binding || owner.taskId != taskId
                        || !token.equals(owner.taskToken) || owner.controller != controller) return;
                owner.nativeReleasePending = false;
                owner.frozen = false;
                if (done) {
                    ownedTasks.remove(token);
                    clearSlot(owner);
                    compactCells();
                    log("native freeform released side task=" + taskId + "; slot cleared, task retained");
                }
            });
        })) {
            slot.nativeReleasePending = false;
            slot.frozen = false;
        }
    }

    private void onTaskVisibility(int slotIndex, int taskId, boolean visible) {
        Slot slot = slots[slotIndex];
        if (slot.taskId != taskId || slot.promoting || slot.parked) return;
        slot.taskVisible = visible;
        log("taskVisibility slot=" + slotIndex + " task=" + taskId + " visible=" + visible);
        if (!visible) scheduleSideVisibilityRepair(slot);
    }

    private java.util.List<MiStepSideClose> closingTasks(Object info) {
        java.util.List<MiStepSideClose> result = new java.util.ArrayList<>();
        if (scene != ACTIVE || promotion != null || transferBusy || cellMotion != null || root == null) return result;
        try {
            MiStepLiveSwapSession.ShellBackend backend = new MiStepLiveSwapSession.ShellBackend(
                    XposedHelpers.getObjectField(transitions, "mTransitions"), ctx.getClassLoader());
            backend.assertShellThread();
            for (Object change : (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges")) {
                if ((Integer) XposedHelpers.callMethod(change, "getMode") != 2) continue;
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (task == null) continue;
                int id = XposedHelpers.getIntField(task, "taskId");
                Object token = XposedHelpers.getObjectField(task, "token");
                for (Slot slot : slots) {
                    if (slot.index == 0 || slot.taskId != id || !java.util.Objects.equals(slot.taskToken, token)
                            || !slot.occupied || slot.promoting || slot.parked || slot.closing || slot.frozen
                            || !controllerOwns(slot, id, token)) continue;
                    Object parent = getSurfaceField(slot.controller, "mSurfaceControl");
                    Object leash = XposedHelpers.callMethod(change, "getLeash");
                    if (parent == null || leash == null || slot.rect == null) continue;
                    long epoch = slot.taskEpoch;
                    FrameLayout closeRoot = root;
                    final MiStepLayout closeGeometry = layout;
                    Rect strip = liveSideStrip(closeGeometry);
                    MiStepLiveBackdrop wallpaper = MiStepLiveBackdrop.create(liveHostRootSurface(), wallpaperBackdrop,
                            screenW, screenH, strip, strip,
                            canvas -> drawLiveEmptyHints(canvas, closeGeometry, closeGeometry,
                                    1 << slot.index), MiStepHost::log);
                    slot.closing = true;
                    result.add(new MiStepSideClose(backend, slot.controller, leash, parent, slot.cellBackground, scaleFor(slot),
                            slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1],
                            () -> isActive(slot) && scene == ACTIVE && slot.taskId == id && slot.taskEpoch == epoch,
                            () -> { slot.closing = false; main.post(() -> {
                                if (!isActive(slot) || slot.taskId != id || slot.taskEpoch != epoch) {
                                    wallpaper.closeAfterFrame(closeRoot);
                                    return;
                                }
                                if (!executeOnShell(() -> {
                                    boolean stillOwned = controllerOwns(slot, id, token);
                                    if (!stillOwned) resetIdleTaskView(slot);
                                    main.post(() -> {
                                        if (isActive(slot) && slot.taskId == id && slot.taskEpoch == epoch) {
                                            if (stillOwned) onTaskRemovalStarted(slot.index, id);
                                            else { clearSlot(slot); compactCells(); }
                                        }
                                        wallpaper.closeAfterFrame(closeRoot);
                                    });
                                })) {
                                    onTaskRemovalStarted(slot.index, id);
                                    wallpaper.closeAfterFrame(closeRoot);
                                }
                            }); }, MiStepHost::log));
                    log("real side close task=" + id + " slot=" + slot.index);
                }
            }
        } catch (Throwable error) {
            for (MiStepSideClose close : result) close.abandon();
            result.clear();
            log("side close admission: " + error);
        }
        return result;
    }

    private void onTaskRemovalStarted(int slotIndex, int taskId) {
        Slot slot = slots[slotIndex];
        log("onTaskRemovalStarted slot=" + slotIndex + " taskId=" + taskId
                + " current=" + slot.taskId);
        if (slot.closing || slot.nativeReleasePending) return;
        if (slot.promoting || slot.parked) {
            log("early removal notification retained for promotion task=" + taskId);
            return;
        }
        if (slot.taskId != taskId || slot.removalCheckTask == taskId) return;
        slot.removalCheckTask = taskId;
        final long expectedEpoch = slot.taskEpoch;
        main.postDelayed(() -> {
            if (!isActive(slot) || slot.promoting || slot.taskId != taskId
                    || slot.taskEpoch != expectedEpoch) return;
            executeOnShell(() -> {
                boolean stillOwned = controllerOwns(slot, taskId, slot.taskToken);
                if (!stillOwned) resetIdleTaskView(slot);
                postForSlot(slot, owner -> {
                    if (owner.taskId != taskId || owner.taskEpoch != expectedEpoch || owner.promoting) return;
                    owner.removalCheckTask = -1;
                    if (stillOwned) {
                        log("removal not detached; retain task=" + taskId);
                        return;
                    }
                    clearSlot(owner);
                    compactCells();
                });
            });
        }, 250L);
    }

    /**
     * v0.9.9：副格是"栈"——某个格子空了，后面的占用格子自动往上排（官方 SideAreaStackView 的紧凑排列）。
     */
    private void compactCells() {
        if (root == null || layout == null) return;
        if (scene != ACTIVE || promotion != null || transferBusy || cellMotion != null) {
            compactPending = true;
            return;
        }
        // Pending callbacks and an active host gesture keep their owner geometry fixed.
        for (Slot s : slots) {
            if (s.promoting || s.launching || s.closing || s.nativeReleasePending
                    || s.bufferedDown != null || s.injecting) {
                compactPending = true;
                return;
            }
        }
        compactPending = false;
        publishDesktopSideTasks();
        java.util.List<CellMovePlan> moves = new java.util.ArrayList<>();
        int initialEmptySlots = 0;
        for (Slot slot : slots) if (slot.index > SLOT_MAIN && !slot.occupied)
            initialEmptySlots |= 1 << slot.index;
        for (int i = 1; i < SLOT_COUNT - 1; i++) {
            Slot cur = slots[i];
            if (cur.occupied) {
                continue;
            }
            for (int j = i + 1; j < SLOT_COUNT; j++) {
                Slot next = slots[j];
                if (!next.occupied) {
                    continue;
                }
                log("compactCells: slot" + j + " -> slot" + i + " (" + next.pkg + ")");
                recycleBuffered(cur);
                recycleBuffered(next);
                Rect oldRect = next.rect == null ? null : new Rect(
                        next.rect[0], next.rect[1], next.rect[2], next.rect[3]);
                SlotPositions.swap(slots, i, j);
                syncSlotWindowState(slots[i]);
                syncSlotWindowState(slots[j]);
                cur.rect = layout.cellBounds(cur.index - 1);
                next.rect = layout.cellBounds(next.index - 1);
                if (oldRect != null) {
                    moves.add(new CellMovePlan(next, oldRect));
                }
                break;
            }
        }
        if (!moves.isEmpty()) animateCellMoves(moves, initialEmptySlots);
    }

    private void requestCompaction() {
        if (scene != ACTIVE || root == null) return;
        compactPending = true;
        main.post(this::compactCells);
    }

    private void finishCellLayout() {
        for (int i = 1; i < SLOT_COUNT; i++) {
            Slot slot = slots[i];
            applyRect(slot);
            updateHint(slot);
            updateTouchRegion(slot);
            reapplyScale(slot);
        }
        applyWallpaperBackdrops();
        publishDesktopSideTasks();
        if (root != null) { root.requestLayout(); root.invalidate(); }
    }

    private boolean cellMoveCurrent(Slot slot, FrameLayout movingRoot, long epoch, long binding,
            int id, Object token, Object controller, View movingView, Object surface) {
        String changed = root != movingRoot ? "root" : sceneEpoch != epoch ? "scene"
                : !isActive(slot) ? "slot" : slot.taskEpoch != binding ? "binding"
                : slot.taskId != id ? "task" : !java.util.Objects.equals(slot.taskToken, token) ? "token"
                : slot.controller != controller ? "controller" : slot.view != movingView ? "view"
                : getSurfaceField(controller, "mSurfaceControl") != surface ? "surface"
                : !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")) ? "invalid surface" : null;
        if (changed != null) log("real cell motion binding changed: " + changed + " slot=" + slot.index
                + " task=" + id + " current=" + slot.taskId);
        return changed == null;
    }

    private static int compactionHintMask(int initialEmptySlots, int occupiedSlots) {
        return (initialEmptySlots | ~occupiedSlots) & ((1 << SLOT_COUNT) - 2);
    }

    private void animateCellMoves(java.util.List<CellMovePlan> plans, int initialEmptySlots) {
        final FrameLayout movingRoot = root;
        final long epoch = sceneEpoch;
        final java.util.List<MiStepCellMotion.Move> moves = new java.util.ArrayList<>();
        final java.util.List<LiveSwapVisualLease> hints = new java.util.ArrayList<>();
        MiStepLiveBackdrop wallpaper = null;
        try {
            int occupiedSlots = 0;
            for (Slot slot : slots) if (slot.index > SLOT_MAIN && slot.occupied)
                occupiedSlots |= 1 << slot.index;
            for (CellMovePlan plan : plans) {
                Slot slot = plan.slot;
                final long binding = slot.taskEpoch;
                final int id = slot.taskId;
                final Object token = slot.taskToken, controller = slot.controller;
                final Object surface = getSurfaceField(controller, "mSurfaceControl");
                if (!canRender(slot) || surface == null) continue;
                final View movingView = slot.view;
                moves.add(new MiStepCellMotion.Move(movingView, plan.from.left, plan.from.top,
                        slot.rect[0], slot.rect[1], () -> cellMoveCurrent(slot, movingRoot, epoch, binding,
                        id, token, controller, movingView, surface)));
            }
            if (moves.isEmpty()) { finishCellLayout(); return; }
            // Preserve existing empty pluses and reveal vacated sources. An occupied
            // intermediate destination never acquires a one-frame '+' during travel.
            final int revealEmptySlots = compactionHintMask(initialEmptySlots, occupiedSlots);
            final MiStepLayout motionGeometry = layout;
            Rect strip = liveSideStrip(motionGeometry);
            wallpaper = MiStepLiveBackdrop.create(liveHostRootSurface(), wallpaperBackdrop, screenW, screenH,
                    strip, strip, canvas -> drawLiveEmptyHints(canvas, motionGeometry, motionGeometry,
                            revealEmptySlots), MiStepHost::log);
            // One wallpaper/plus owner covers the complete strip during compaction.
            // Static backings otherwise cover its pluses and reappear between layouts.
            for (Slot slot : slots) if (slot.index > 0)
                hints.add(new LiveSwapVisualLease(sideBackingViews[slot.index], slot.hint));
            final MiStepLiveBackdrop backing = wallpaper;
            final MiStepCellMotion[] run = new MiStepCellMotion[1];
            run[0] = new MiStepCellMotion(movingRoot, moves,
                    () -> {
                        if (root != movingRoot || sceneEpoch != epoch) return;
                        finishCellLayout();
                        // Restore Host chrome in the SAME draw that retires the temporary
                        // layer, before waiting for that frame's committed callback.
                        for (LiveSwapVisualLease hint : hints) hint.restore();
                        backing.closeAfterFrame(movingRoot);
                    },
                    () -> {
                        for (LiveSwapVisualLease hint : hints) hint.restore();
                        movingRoot.invalidate();
                        backing.closeAfterFrame(movingRoot);
                        if (cellMotion != run[0]) return;
                        cellMotion = null;
                        transferBusy = false;
                        // finishCellLayout ran while cellMotion still owned visibility.
                        // Reconcile now, including the empty old source's resize background.
                        for (Slot cell : slots) updateCellSurfaceVisibility(cell);
                        if (compactPending) compactCells();
                        finishPendingSuspend();
                    }, MiStepHost::log);
            cellMotion = run[0];
            transferBusy = true;
            run[0].start();
        } catch (Throwable error) {
            for (LiveSwapVisualLease hint : hints) hint.restore();
            if (wallpaper != null) wallpaper.closeAfterFrame(movingRoot);
            cellMotion = null;
            transferBusy = false;
            finishCellLayout();
            log("real cell motion unavailable: " + error);
        }
    }

    /** 诊断用：从 RunningTaskInfo 里取 appCompatTaskInfo.topActivityAppBounds（px）。 */
    private static String appBoundsOf(Object info) {
        try {
            Object compat = XposedHelpers.getObjectField(info, "appCompatTaskInfo");
            if (compat == null) {
                return "null";
            }
            Object bounds = XposedHelpers.getObjectField(compat, "topActivityAppBounds");
            return String.valueOf(bounds);
        } catch (Throwable t) {
            return "unreadable(" + t + ")";
        }
    }

    /** An external fullscreen task can be reordered above our roots without killing them. */
    private void scheduleSideVisibilityRepair(Slot slot) {
        if (!canRender(slot) || scene != ACTIVE || !screenOn || keyguardLocked
                || slot.promoting || slot.launching
                || slot.frozen || !slot.occupied || slot.taskVisible || slot.reassertPending) return;
        final long epoch = sceneEpoch, binding = slot.taskEpoch;
        final int taskId = slot.taskId;
        final Object token = slot.taskToken;
        slot.reassertPending = true;
        main.postDelayed(() -> {
            slot.reassertPending = false;
            if (!visibilityRepairCurrent(slot, epoch, binding, taskId, token)) return;
            for (Slot side : slots) if (side.promoting || side.launching || side.frozen) return;
            executeOnShell(() -> {
                try {
                    if (!visibilityRepairCurrent(slot, epoch, binding, taskId, token)
                            || !controllerOwns(slot, taskId, token)) return;
                    Object foreground = null;
                    java.util.List<?> roots = rootInfos();
                    for (Object info : roots) {
                        if (!XposedHelpers.getBooleanField(info, "isVisible")
                                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                                || isSideToken(XposedHelpers.getObjectField(info, "token"))) continue;
                        int type = (Integer) XposedHelpers.callMethod(info, "getActivityType");
                        if (type != 1 && type != 2) continue;
                        if (foreground != null) return; // Ambiguous split/overlay scene: do not guess.
                        foreground = XposedHelpers.getObjectField(info, "token");
                    }
                    if (!slot.visibilityRepair.admit(epoch, foreground)) return;
                    Object wct = Class.forName("android.window.WindowContainerTransaction")
                            .getDeclaredConstructor().newInstance();
                    int count = 0;
                    for (Slot side : slots) {
                        if (!canRender(side) || side.promoting || side.launching || side.frozen
                                || !side.occupied || !controllerOwns(side, side.taskId, side.taskToken)) continue;
                        Object core = null;
                        for (Object info : roots) if (new LiveTask(side).matches(info)) { core = info; break; }
                        if (!ordinaryRoot(core, 6)) continue;
                        XposedHelpers.callMethod(wct, "setHidden", side.taskToken, false);
                        XposedHelpers.callMethod(wct, "setForceTranslucent", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "setAlwaysOnTop", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "setFocusable", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "reorder", side.taskToken, true);
                        count++;
                    }
                    if (count == 0 || !visibilityRepairCurrent(slot, epoch, binding, taskId, token)) return;
                    boolean applied = applyWct("retain-side-order", wct);
                    if (applied) {
                        for (Slot side : slots) {
                            if (canRender(side) && side.occupied && !side.promoting && !side.frozen
                                    && controllerOwns(side, side.taskId, side.taskToken))
                                XposedHelpers.callMethod(transitions, "setTaskViewVisible", side.controller, true);
                        }
                    }
                    log("side order repair kept live tasks=" + count + " foreground=" + foreground
                            + " applied=" + applied + "; no remove/relaunch");
                } catch (Throwable error) {
                    log("side order repair stopped; retain original task: " + error);
                }
            });
        }, 160L);
    }

    /** Idempotently show every live side TaskView after a task-set change. */
    private void reassertSideTaskViews(String reason) {
        if (!executeOnShell(() -> {
            int count = 0;
            for (Slot side : slots) {
                if (side.index <= SLOT_MAIN || !canRender(side) || !side.occupied
                        || side.parked || side.promoting || side.launching || side.frozen
                        || side.controller == null
                        || !controllerOwns(side, side.taskId, side.taskToken)) continue;
                try {
                    XposedHelpers.callMethod(transitions, "setTaskViewVisible", side.controller, true);
                    count++;
                } catch (Throwable error) {
                    log("side reassert failed slot=" + side.index + ": " + error);
                }
            }
            final int visible = count;
            main.post(() -> {
                for (Slot side : slots) {
                    if (side.index > SLOT_MAIN && side.occupied && !side.promoting && !side.parked)
                        reapplyScale(side);
                }
                log("side TaskViews reasserted reason=" + reason + " count=" + visible);
            });
        })) log("side TaskView reassert skipped reason=" + reason + ": no Shell queue");
    }

    private boolean visibilityRepairCurrent(Slot slot, long epoch, long binding, int id, Object token) {
        return canRender(slot) && scene == ACTIVE && !slot.promoting && !slot.launching && !slot.frozen
                && sceneEpoch == epoch && slot.taskEpoch == binding && slot.taskId == id
                && token != null && token.equals(slot.taskToken);
    }

    private String describeTasks() {
        StringBuilder sb = new StringBuilder();
        for (Slot s : slots) {
            sb.append(" [").append(s.index).append(':')
                    .append(s.taskId).append(s.occupied ? "" : "(empty)")
                    .append(s.taskVisible ? "/vis" : "/hid");
            if (s.windowState != null) {
                sb.append("/o").append(s.windowState.configurationOrientation)
                        .append("/r").append(s.windowState.resolvedRotation)
                        .append("/m").append(s.windowState.windowingMode)
                        .append(s.windowState.fullscreen ? "/full" : "/windowed");
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private boolean executeOnShell(Runnable task) {
        try {
            Object shell = XposedHelpers.getObjectField(transitions, "mShellExecutor");
            if (shell instanceof Executor) {
                ((Executor) shell).execute(task);
                return true;
            }
        } catch (Throwable t) {
            log("Shell executor failed: " + t);
        }
        return false;
    }

    // ---------------------------------------------------------------- v0.9 等比缩小（自绘矩阵）

    private float mainScale() {
        return layout.mainScale;
    }

    private float cellScale() {
        return layout.cellScale(0);
    }

    /** Only side tasks are hosted; the system handles main-screen scaling. */
    private float scaleFor(Slot slot) {
        return layout.cellScale(slot.index - 1);
    }

    /**
     * 把任务内容按 k 等比缩放显示到槽位里（官方 DisplayContentSmtEx.applyZoomedMatrixSmt 的等价做法）。
     * 任务窗口保持满屏（应用按整屏手机排版），只对承载表面做矩阵缩放，视觉就是"整屏缩小"。
     */
    private void applySlotScale(Slot slot) {
        if (holdsLiveFlipZoom() || !canRender(slot) || slot.promoting || !slot.occupied || slot.view == null
                || !mayWriteSideSurface(slot)) {
            return;
        }
        float k = scaleFor(slot);
        Object liveLeash = getSurfaceField(slot.controller, "mTaskLeash");
        if (!controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        if (liveLeash != null && slot.scaleSurface == liveLeash
                && slot.scaleTaskId == slot.taskId && Math.abs(slot.scaleApplied - k) < 0.0005f) {
            return;
        }
        // 顺序很重要（真机实测）：先读 leash 做缩放，再改整屏边界 —— 反过来会让
        // TaskViewTransitions 把 mTaskLeash 清空重建，900ms 内都读不到。
        if (liveLeash == null) {
            if (slot.scaleAttempts >= 8) return;
            slot.scaleAttempts++;
            log("applySlotScale slot=" + slot.index + " leash not ready, retry #" + slot.scaleAttempts);
            main.postDelayed(() -> applySlotScale(slot), 200L);
            return;
        }
        slot.scaleAttempts = 0;
        Object sc = liveLeash;
        slot.scaleOnLeash = true;
        if (applyScaleTransform(slot, sc)) {
            slot.scaleSurface = sc;
            slot.scaleTaskId = slot.taskId;
            slot.scaleApplied = k;
            OwnedTask accepted = ownedTasks.get(slot.taskToken);
            if (accepted != null) accepted.surface = sc;
            log("applySlotScale slot=" + slot.index + " task=" + slot.taskId
                    + " k=" + k + " onLeash=" + slot.scaleOnLeash + " surface=" + sc);
        }
        // TaskView 会把任务边界同步成视图大小（真机实测 DeepSeek=[0,622][807,2460]、设置=550
        // 宽右对齐）→ 改回整屏 1080×2460，应用才按"整屏手机"排版。放在缩放之后执行。
        // The opening/bounds guards already enforce logical full-screen sizing.
        // 转场/可见性变化可能覆盖矩阵：在几个延迟点重发。
        main.postDelayed(() -> reapplyScale(slot), 300L);
        main.postDelayed(() -> reapplyScale(slot), 1200L);
    }

    private void reapplyScale(Slot slot) {
        if (holdsLiveFlipZoom() || !canRender(slot) || slot.promoting || !slot.occupied || !mayWriteSideSurface(slot)) {
            return;
        }
        if (!controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        Object current = getSurfaceField(slot.controller, "mTaskLeash");
        if (current == null || current != slot.scaleSurface) {
            applySlotScale(slot);
            return;
        }
        if (!applyScaleTransform(slot, slot.scaleSurface)) {
            // 表面可能已失效（任务重启/改边界后转场重建）：清缓存 + 放开去重门槛，
            // 下次 TaskInfo 变化时重新解析并整套重来。
            slot.scaleSurface = null;
            slot.scaleTaskId = Integer.MIN_VALUE;
            slot.scaleApplied = -1f;
        }
    }

    /** 把任务边界改回整屏（0,0,1080,2460），应用即按"整屏手机"排版。 */
    private void applyFullscreenBounds(Slot slot) {
        if (holdsLiveFlipZoom() || !canRender(slot) || slot.promoting || slot.taskToken == null
                || !mayWriteSideSurface(slot)) {
            return;
        }
        if (!controllerOwns(slot, slot.taskId, slot.taskToken)
                || getSurfaceField(slot.controller, "mTaskLeash") == null) return;
        try {
            Object wct = Class.forName("android.window.WindowContainerTransaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(wct, "setForceTranslucent", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setBounds", slot.taskToken,
                    new Rect(0, 0, screenW, screenH));
            boolean ok = applyWct("fullscreenBounds(slot=" + slot.index + ")", wct);
            log("fullscreenBounds slot=" + slot.index + " task=" + slot.taskId + " ok=" + ok);
        } catch (Throwable t) {
            log("fullscreenBounds slot=" + slot.index + " failed: " + t);
        }
    }

    /** 统一施加"缩放 + 归位"：leash 需要 setPosition(0,0)，容器表面不能动位置。 */
    private boolean applyScaleTransform(Slot slot, Object sc) {
        if (holdsLiveFlipZoom() || !canRender(slot) || slot.promoting || !mayWriteSideSurface(slot)) return false;
        float k = scaleFor(slot);
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            if (slot.scaleOnLeash) {
                XposedHelpers.callMethod(tx, "setPosition", sc, 0f, 0f);
                // v1.1.3：视口也要对齐 —— 只缩不放位置会"比例对但只显示一部分"（真机反馈）。
                try {
                    XposedHelpers.callMethod(tx, "setWindowCrop", sc,
                            new Rect(0, 0, screenW, screenH));
                } catch (Throwable ignored) {
                    // 裁剪失败也继续，位置+矩阵仍是主体
                }
            }
            XposedHelpers.callMethod(tx, "setMatrix", sc, k, 0f, 0f, k);
            XposedHelpers.callMethod(tx, "apply");
            return true;
        } catch (Throwable t) {
            log("applyScaleTransform slot=" + slot.index + " failed: " + t);
            return false;
        } finally {
            closeSurfaceObject(tx, "close");
        }
    }

    /** A late mode6 finish must never pull a task selected by Launcher back into a cell. */
    private boolean mayWriteSideSurface(Slot slot) {
        if (slot == null || slot.windowMode != 6 || slot.controller == null) return false;
        PendingSidePromotion pending = slot.pendingSidePromotion;
        if (pending != null && pending.desktopLaunch) return false;
        try {
            // Controller.onTaskInfoChanged forwards info without replacing its
            // mTaskInfo on this ROM. Read the organizer's current canonical info.
            Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
            Object lock = XposedHelpers.getObjectField(organizer, "mLock");
            synchronized (lock) {
                Object tasks = XposedHelpers.getObjectField(organizer, "mTasks");
                Object appeared = XposedHelpers.callMethod(tasks, "get", slot.taskId);
                Object info = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                return info != null && XposedHelpers.getIntField(info, "taskId") == slot.taskId
                        && slot.taskToken != null && slot.taskToken.equals(
                                XposedHelpers.getObjectField(info, "token"))
                        && Integer.valueOf(6).equals(XposedHelpers.callMethod(info, "getWindowingMode"));
            }
        } catch (Throwable unavailable) { return false; }
    }

    /** 按字段名取 SurfaceControl（先本类后父类；取不到返回 null）。 */
    private static Object getSurfaceField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (!"android.view.SurfaceControl".equals(f.getType().getName())) {
                    continue;
                }
                f.setAccessible(true);
                Object surface = f.get(target);
                return surface != null && Boolean.TRUE.equals(
                        XposedHelpers.callMethod(surface, "isValid")) ? surface : null;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private boolean applyWct(String what, Object wct) {
        Throwable first = null;
        // 路线 A：框架公开类 WindowOrganizer（SystemUI 里可直接 new，真机已验证）。
        try {
            Class<?> c = Class.forName("android.window.WindowOrganizer");
            Object organizer = c.getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(organizer, "applyTransaction", wct);
            log("wct(" + what + ") via WindowOrganizer");
            return true;
        } catch (Throwable t) {
            first = t;
        }
        // 路线 B：从已捕获的 Shell 对象（transitions / controller）里找能 applyTransaction 的字段。
        try {
            Object applier = findWctApplier(wct);
            if (applier != null) {
                log("wct(" + what + ") via " + applier.getClass().getName());
                return true;
            }
        } catch (Throwable t) {
            log("wct fallback failed: " + t);
        }
        log("wct(" + what + ") FAILED: " + first);
        return false;
    }

    private Object findWctApplier(Object wct) {
        Class<?> wctCls;
        try {
            wctCls = Class.forName("android.window.WindowContainerTransaction");
        } catch (Throwable t) {
            return null;
        }
        Object[] roots = {transitions, slots[0].controller, slots[1].controller,
                slots[2].controller, slots[3].controller};
        for (Object r : roots) {
            if (r == null) {
                continue;
            }
            for (Class<?> c = r.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(r);
                        if (v == null || v == wct) {
                            continue;
                        }
                        Method m;
                        try {
                            m = v.getClass().getMethod("applyTransaction", wctCls);
                        } catch (NoSuchMethodException e) {
                            continue;
                        }
                        m.invoke(v, wct);
                        return v;
                    } catch (Throwable ignored) {
                        // 该字段不可读或不可用，继续找
                    }
                }
            }
        }
        return null;
    }

    private static void log(String msg) {
        android.util.Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            // 至少保留 logcat
        }
    }
}
