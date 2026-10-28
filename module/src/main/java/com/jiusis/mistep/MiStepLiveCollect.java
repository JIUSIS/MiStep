package com.jiusis.mistep;

import android.graphics.Rect;
import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Single real-task collection, using the same Shell animation/finish protocol as live swaps. */
final class MiStepLiveCollect implements MiStepLiveSwapSession.Scene {
    private static final IdentityHashMap<Object, MiStepLiveCollect> ACTIVE = new IdentityHashMap<>();
    private final Object controller, shell, hostRoot, background;
    private final MiStepLiveSwapSession.ShellBackend backend;
    private final MiStepLiveCollectTask task;
    private final MiStepLiveSwapSession session;
    private final BooleanSupplier current;
    private final Consumer<MiStepLiveSwap.Result> done;
    private final Consumer<String> log;
    private final Consumer<Consumer<Boolean>> handoff;
    private final float scale;
    private final Object homeToken;
    private final Object collectedToken;
    private final int userId;
    private final float mainScale, mainX, mainY;
    private Object homeSurface, homeTransitionRoot;
    private int rootX, rootY;
    private boolean waitingCancel, uncertain, delivered, cancelled;

    static void cancel(Object controller) {
        MiStepLiveCollect run;
        synchronized (ACTIVE) { run = ACTIVE.get(controller); }
        if (run != null) run.backend.execute(run.session::cancel);
    }
    static boolean start(ClassLoader loader, Object taskViews, Object controller, LiveSwapGeometry.Task geometry,
            Object homeToken, int userId, Rect logical, Object hostRoot, Object background,
            BooleanSupplier current, Consumer<Consumer<Boolean>> handoff,
            Consumer<MiStepLiveSwap.Result> done, Consumer<String> log) {
        synchronized (ACTIVE) { if (ACTIVE.containsKey(controller)) return false; }
        MiStepLiveCollect run = null;
        try {
            run = new MiStepLiveCollect(loader, taskViews, controller, geometry, homeToken, userId,
                    logical, hostRoot, background, current, handoff, done, log);
            if (!current.getAsBoolean() || !run.task.preflight()) return false;
            Object wct = run.task.windowTransaction(false, false);
            run.task.arm(loader);
            synchronized (ACTIVE) { ACTIVE.put(controller, run); }
            if (run.session.start(wct)) return true;
        } catch (Throwable error) { log.accept("live collect admission refused: " + error); }
        if (run != null) {
            Object tx = run.backend.newTransaction();
            try { run.task.settle(tx, run.scale); run.backend.apply(tx); }
            catch (Throwable error) { log.accept("live collect admission recovery: " + error); }
            finally { run.backend.close(tx); }
            synchronized (ACTIVE) { if (ACTIVE.get(controller) == run) ACTIVE.remove(controller); }
        }
        return false;
    }
    private MiStepLiveCollect(ClassLoader loader, Object taskViews, Object controller, LiveSwapGeometry.Task geometry,
            Object homeToken, int userId, Rect logical, Object hostRoot, Object background,
            BooleanSupplier current, Consumer<Consumer<Boolean>> handoff,
            Consumer<MiStepLiveSwap.Result> done, Consumer<String> log) throws Exception {
        this.controller = controller; this.hostRoot = hostRoot; this.background = background;
        this.homeToken = homeToken; this.collectedToken = geometry.token; this.userId = userId;
        mainScale = (float) (geometry.start.width / geometry.bufferWidth);
        if (Math.abs(mainScale - geometry.start.height / geometry.bufferHeight) > .003)
            throw new IllegalStateException("collect Home requires uniform main transform");
        mainX = (float) geometry.start.left; mainY = (float) geometry.start.top;
        this.current = current; this.handoff = handoff; this.done = done; this.log = log;
        shell = XposedHelpers.getObjectField(taskViews, "mTransitions");
        backend = new MiStepLiveSwapSession.ShellBackend(shell, loader); backend.assertShellThread();
        Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
        Object area = XposedHelpers.getObjectField(organizer, "mRootTaskDisplayAreaOrganizer");
        Object areaInfo = XposedHelpers.callMethod(area, "getDisplayAreaInfo", 0);
        if (areaInfo == null || XposedHelpers.getIntField(areaInfo, "featureId") != 1
                || XposedHelpers.getIntField(areaInfo, "displayId") != 0 || !MiStepLiveHomeTask.valid(hostRoot))
            throw new IllegalStateException("collect real parent unavailable");
        task = new MiStepLiveCollectTask(organizer, taskViews, controller, geometry.id, geometry.token,
                homeToken, userId, logical, XposedHelpers.callMethod(area, "getDisplayAreaLeash", 0), log);
        scale = (float) (geometry.end.width / geometry.bufferWidth);
        if (Math.abs(scale - geometry.end.height / geometry.bufferHeight) > .003)
            throw new IllegalStateException("collect requires uniform task buffer");
        session = new MiStepLiveSwapSession(backend, this, new LiveSwapGeometry.Task[]{geometry}, 300L);
    }
    @Override public boolean mayStart() { return current.getAsBoolean(); }
    @Override public MiStepLiveSwapSession.Ready resolve(Object info) {
        homeSurface = null;
        homeTransitionRoot = null;
        boolean otherTask = false;
        for (Object change : (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges")) {
            Object home = XposedHelpers.callMethod(change, "getTaskInfo");
            if (home == null) continue;
            Object token = XposedHelpers.getObjectField(home, "token");
            if (!homeToken.equals(token)) {
                if (!collectedToken.equals(token)) otherTask = true;
                continue;
            }
            if (homeSurface != null || XposedHelpers.getIntField(home, "displayId") != 0
                    || XposedHelpers.getIntField(home, "userId") != userId
                    || !Integer.valueOf(2).equals(XposedHelpers.callMethod(home, "getActivityType"))
                    || !Integer.valueOf(1).equals(XposedHelpers.callMethod(home, "getWindowingMode")))
                throw new IllegalStateException("collect Home transition identity changed");
            homeSurface = XposedHelpers.callMethod(change, "getLeash");
            if (!MiStepLiveHomeTask.valid(homeSurface))
                throw new IllegalStateException("collect Home transition leash invalid");
            boolean found = false;
            int count = (Integer) XposedHelpers.callMethod(info, "getRootCount");
            for (int i = 0; i < count; i++) {
                Object transitionRoot = XposedHelpers.callMethod(info, "getRoot", i);
                if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(transitionRoot, "getDisplayId"))) continue;
                android.graphics.Point offset = (android.graphics.Point) XposedHelpers.callMethod(transitionRoot, "getOffset");
                rootX = offset.x; rootY = offset.y; found = true; break;
            }
            if (!found) throw new IllegalStateException("collect Home transition root missing");
        }
        if (homeSurface != null && !otherTask) {
            int count = (Integer) XposedHelpers.callMethod(info, "getRootCount");
            for (int i = 0; i < count; i++) {
                Object transitionRoot = XposedHelpers.callMethod(info, "getRoot", i);
                if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(transitionRoot, "getDisplayId"))) continue;
                homeTransitionRoot = XposedHelpers.callMethod(transitionRoot, "getLeash");
                if (!MiStepLiveHomeTask.valid(homeTransitionRoot))
                    throw new IllegalStateException("collect Home transition root leash invalid");
                break;
            }
        }
        return new MiStepLiveSwapSession.Ready(new Object[]{task.resolve(info)}, hostRoot, new int[]{100},
                LiveSwapGeometry.Affine.identity(), new LiveSwapGeometry.Affine[]{LiveSwapGeometry.Affine.identity()});
    }
    private void background(Object tx, boolean show) {
        if (MiStepLiveHomeTask.valid(background)) XposedHelpers.callMethod(tx, show ? "show" : "hide", background);
    }
    @Override public void prepare(Object start, Object finish, MiStepLiveSwapSession.Ready ready) {
        if (!mayStart()) throw new IllegalStateException("collect scene changed before prepare");
        try { task.prepare(start, finish); }
        catch (Exception error) { throw new IllegalStateException("collect native preparation", error); }
        if (homeTransitionRoot != null) {
            // WMS prepares this exact Root relative to its magnified display area.
            // Replacing it with a physical scale here would magnify Home twice
            // after the last native mini closes. App collection still uses Host coordinates.
            log.accept("collect Home and wallpaper retain WM-owned transform scale=" + mainScale
                    + " position=" + mainX + "," + mainY);
        }
        background(start, false);
    }
    @Override public void input(Object tx, LiveSwapGeometry.Frame[] frames) {
        if (!mayStart()) throw new IllegalStateException("collect scene changed during frame");
        // SurfaceFlinger transforms the actual app input with its real task surface.
    }
    @Override public void beforeFinish(boolean failed, Consumer<Throwable> ready) {
        if (waitingCancel) { ready.accept(null); return; }
        if (!failed && !cancelled && mayStart()) {
            backend.await(task::sideOwned, 1500L, seen -> {
                if (!seen || cancelled || !mayStart()) { reverse(ready); return; }
                handoff.accept(committed -> backend.execute(() -> {
                    if (committed && !cancelled && mayStart()) ready.accept(null);
                    else reverse(ready);
                }));
            });
            return;
        }
        reverse(ready);
    }
    @Override public void cancelFinishing() { cancelled = true; }
    private void reverse(Consumer<Throwable> ready) {
        try {
            XposedHelpers.callMethod(XposedHelpers.getObjectField(shell, "mOrganizer"), "applyTransaction",
                    task.windowTransaction(true, false));
            backend.await(task::originalRoles, 1500L, seen -> {
                if (!seen) uncertain = true;
                ready.accept(new IllegalStateException(seen ? "collect cancelled; original task restored" : "collect inverse role unconfirmed"));
            });
        } catch (Throwable error) { uncertain = true; ready.accept(error); }
    }
    @Override public void finish(Object tx, boolean failed) {
        if (uncertain || waitingCancel && !task.originalRoles())
            throw new IllegalStateException("collect owner remains unconfirmed");
        task.settle(tx, scale); background(tx, true);
    }
    @Override public Object finishWindowTransaction() { return task.finishWindowTransaction(); }
    @Override public void requestWaitingCancel() {
        waitingCancel = true;
        try {
            XposedHelpers.callMethod(XposedHelpers.getObjectField(shell, "mOrganizer"), "applyTransaction",
                    task.windowTransaction(true, true));
        } catch (Exception error) { throw new IllegalStateException("collect pending inverse failed", error); }
    }
    @Override public boolean waitingCancelReady() { return task.originalRoles(); }
    @Override public void consumed(Object tx, boolean aborted) { task.settle(tx, scale); background(tx, true); }
    @Override public void complete(MiStepLiveSwapSession.State state, Throwable error) {
        if (delivered) return;
        for (Throwable cause = error; cause != null;) {
            log.accept("live collect finish cause=" + cause);
            Throwable next = cause.getCause();
            if (next == cause) break;
            cause = next;
        }
        delivered = true;
        boolean side = task.sideOwned();
        // Admission failures can complete before start() returns. Retire an exact
        // original-role lease here as well, before reporting that nothing is owned.
        if (!side && task.originalRoles() && !task.originalSettled()) {
            Object tx = backend.newTransaction();
            try { task.settle(tx, scale); background(tx, true); backend.apply(tx); }
            catch (Throwable recovery) { if (error == null) error = recovery; }
            finally { backend.close(tx); }
        }
        boolean roles = !uncertain && (side || task.originalSettled());
        if (waitingCancel && !roles) task.releaseOriginalWhenSeen();
        synchronized (ACTIVE) { if (ACTIVE.get(controller) == this) ACTIVE.remove(controller); }
        done.accept(new MiStepLiveSwap.Result(state == MiStepLiveSwapSession.State.FINISHED
                && side && roles && error == null, side, roles, error));
    }
}
