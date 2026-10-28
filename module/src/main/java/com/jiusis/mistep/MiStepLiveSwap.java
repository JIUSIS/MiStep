package com.jiusis.mistep;

import android.graphics.Rect;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.IdentityHashMap;
import de.robv.android.xposed.XposedHelpers;

/** One WCT swaps two existing ordinary tasks in the current, fixed main/side layout. */
final class MiStepLiveSwap {
    private MiStepLiveSwap() {}
    private static final IdentityHashMap<Object, Active> ACTIVE = new IdentityHashMap<>();
    private static final class Active {
        final MiStepLiveSwapSession.ShellBackend backend;
        MiStepLiveSwapSession session;
        Active(MiStepLiveSwapSession.ShellBackend backend) { this.backend = backend; }
    }
    /** May be called from Host main; all ownership work remains on the existing Shell executor. */
    static void cancel(Object controller) {
        MiStepLiveCollect.cancel(controller);
        MiStepLiveSwap64.cancel(controller);
        Active active;
        synchronized (ACTIVE) { active = ACTIVE.get(controller); }
        if (active != null) active.backend.execute(() -> active.session.cancel());
    }
    static boolean startFlip(ClassLoader loader, Object taskViews, Object controller, LiveTaskSwapDriver.Flip request,
            BooleanSupplier current, Consumer<LiveSwapGeometry.Frame[]> input,
            Consumer<Result> done, Consumer<String> log) {
        synchronized (ACTIVE) { if (ACTIVE.containsKey(controller)) return false; }
        return MiStepLiveSwap64.start(loader, taskViews, controller, request, null, current, input, done, log);
    }
    static boolean startHome(ClassLoader loader, Object taskViews, Object controller, LiveTaskSwapDriver.Home request,
            BooleanSupplier current, Consumer<LiveSwapGeometry.Frame[]> input,
            Consumer<Result> done, Consumer<String> log) {
        synchronized (ACTIVE) { if (ACTIVE.containsKey(controller)) return false; }
        return MiStepLiveSwap64.start(loader, taskViews, controller, null, request, current, input, done, log);
    }
    static final class Result {
        final boolean succeeded, sideOwned, rolesConfirmed;
        final Throwable error;
        Result(boolean succeeded, boolean sideOwned, Throwable error) {
            this(succeeded, sideOwned, true, error);
        }
        Result(boolean succeeded, boolean sideOwned, boolean rolesConfirmed, Throwable error) {
            this.succeeded = succeeded; this.sideOwned = sideOwned; this.error = error;
            this.rolesConfirmed = rolesConfirmed;
        }
    }

    /**
     * Runs on Shell main. Returning false means no transition was submitted; callers retain the
     * layout/tasks and report the failure. Once accepted, completed is the only completion path.
     * input excludes the two moving physical rectangles from Host's touch region; real app input
     * continues through SurfaceFlinger's window transforms. Editor raw is unchanged and pending QA.
     */
    static boolean start(ClassLoader loader, Object taskViews, Object controller,
            LiveSwapGeometry.Task first, LiveSwapGeometry.Task second, int userId, Rect logical,
            float mainScale, float mainX, float mainY, float sideScale, Object cellBackground,
            BooleanSupplier sceneCurrent, Consumer<LiveSwapGeometry.Frame[]> input,
            Consumer<Result> completed, Consumer<String> log) {
        Object shell = XposedHelpers.getObjectField(taskViews, "mTransitions");
        Object executor = XposedHelpers.getObjectField(shell, "mMainExecutor");
        XposedHelpers.callMethod(executor, "assertCurrentThread");
        synchronized (ACTIVE) { if (ACTIVE.containsKey(controller)) return false; }
        if (mainScale <= 0 || mainScale >= 1 || sideScale <= 0 || sideScale >= mainScale
                || !Float.isFinite(mainScale) || !Float.isFinite(mainX) || !Float.isFinite(mainY)
                || !Float.isFinite(sideScale) || !sceneCurrent.getAsBoolean()) return false;
        MiStepLiveTaskPair pair = null;
        Object cleanup = null;
        try {
            Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
            Object area = XposedHelpers.getObjectField(organizer, "mRootTaskDisplayAreaOrganizer");
            Object areaInfo = XposedHelpers.callMethod(area, "getDisplayAreaInfo", 0);
            Object parent = XposedHelpers.callMethod(area, "getDisplayAreaLeash", 0);
            Object side = XposedHelpers.getObjectField(controller, "mSurfaceControl");
            if (areaInfo == null || XposedHelpers.getIntField(areaInfo, "featureId") != 1
                    || XposedHelpers.getIntField(areaInfo, "displayId") != 0) return false;
            pair = new MiStepLiveTaskPair(organizer, taskViews, controller, first.id, first.token,
                    second.id, second.token, userId, parent, side, logical, log);
            if (!pair.preflight()) return false;
            MiStepLiveTaskPair ownedPair = pair;
            MiStepLiveSwapSession.ShellBackend backend = new MiStepLiveSwapSession.ShellBackend(shell, loader);
            Active active = new Active(backend);
            LiveSwapGeometry.Affine parentToScreen = new LiveSwapGeometry.Affine(mainScale, 0, 0,
                    mainScale, mainX, mainY);
            Object wct = pair.windowTransaction(null);
            pair.arm(loader);
            MiStepLiveSwapSession session = new MiStepLiveSwapSession(backend, new MiStepLiveSwapSession.Scene() {
                private Throwable cleanupError;
                private boolean waitingCancel;
                private boolean delivered;
                private void background(Object tx, boolean visible) {
                    if (cellBackground != null && Boolean.TRUE.equals(XposedHelpers.callMethod(cellBackground, "isValid")))
                        XposedHelpers.callMethod(tx, visible ? "show" : "hide", cellBackground);
                }
                @Override public boolean mayStart() { return sceneCurrent.getAsBoolean(); }
                @Override public MiStepLiveSwapSession.Ready resolve(Object info) {
                    return sceneCurrent.getAsBoolean() ? ownedPair.resolve(info, parentToScreen) : null;
                }
                @Override public void prepare(Object start, Object finish, MiStepLiveSwapSession.Ready ready) {
                    if (!sceneCurrent.getAsBoolean()) throw new IllegalStateException("live scene changed before start");
                    try { ownedPair.prepare(start, finish); }
                    catch (Exception error) { throw new IllegalStateException("live pair claim failed", error); }
                    background(start, false);
                }
                @Override public void input(Object transaction, LiveSwapGeometry.Frame[] frame) {
                    if (!sceneCurrent.getAsBoolean()) throw new IllegalStateException("live scene changed mid-frame");
                    input.accept(frame);
                }
                @Override public void finish(Object transaction, boolean failed) {
                    if (waitingCancel && !ownedPair.originalRoles())
                        throw new IllegalStateException("pending inverse roles remain unconfirmed");
                    if (failed) ownedPair.recover(transaction, sideScale);
                    else ownedPair.finish(transaction, sideScale);
                    background(transaction, true);
                }
                @Override public Object finishWindowTransaction() { return ownedPair.finishWindowTransaction(); }
                @Override public void requestWaitingCancel() {
                    waitingCancel = true;
                    try {
                        XposedHelpers.callMethod(XposedHelpers.getObjectField(shell, "mOrganizer"),
                                "applyTransaction", ownedPair.reverseWindowTransaction());
                    } catch (Exception error) { throw new IllegalStateException("pending reverse WCT failed", error); }
                }
                @Override public boolean waitingCancelReady() { return ownedPair.originalRoles(); }
                @Override public void consumed(Object transaction, boolean aborted) {
                    ownedPair.recover(transaction, sideScale);
                    background(transaction, true);
                }
                @Override public void complete(MiStepLiveSwapSession.State state, Throwable error) {
                    Runnable deliver = () -> {
                        if (delivered) return;
                        delivered = true;
                        synchronized (ACTIVE) { if (ACTIVE.get(controller) == active) ACTIVE.remove(controller); }
                        boolean owned = cleanupError == null && ownedPair.sideOwned();
                        Throwable resultError = error != null ? error : cleanupError;
                        completed.accept(new Result(state == MiStepLiveSwapSession.State.FINISHED
                                && owned && resultError == null, owned,
                                owned || ownedPair.originalRoles(), resultError));
                    };
                    if (waitingCancel && !ownedPair.originalRoles()) {
                        // Do not infer roles from an old canonical object after the reverse IPC.
                        ownedPair.releaseOriginalLeaseWhenSeen();
                        synchronized (ACTIVE) { if (ACTIVE.get(controller) == active) ACTIVE.remove(controller); }
                        completed.accept(new Result(false, false, false, error));
                        return;
                    }
                    if (state == MiStepLiveSwapSession.State.DECLINED || state == MiStepLiveSwapSession.State.FAILED) {
                        Object tx = backend.newTransaction();
                        try {
                            ownedPair.recover(tx, sideScale); background(tx, true);
                            boolean[] committed = {false};
                            backend.committed(tx, () -> { committed[0] = true; deliver.run(); });
                            backend.apply(tx);
                            backend.await(() -> committed[0], 1500L, acknowledged -> {
                                if (!acknowledged && !delivered) {
                                    cleanupError = new IllegalStateException("recovery transaction commit not acknowledged");
                                    deliver.run();
                                }
                            });
                        }
                        catch (Throwable failure) { cleanupError = failure; deliver.run(); }
                        finally { backend.close(tx); }
                        return;
                    }
                    deliver.run();
                }
            }, first, second, 300L);
            active.session = session;
            synchronized (ACTIVE) { ACTIVE.put(controller, active); }
            if (session.start(wct)) return true;
            synchronized (ACTIVE) { if (ACTIVE.get(controller) == active) ACTIVE.remove(controller); }
            cleanup = backend.newTransaction();
            pair.recover(cleanup, sideScale); backend.apply(cleanup); backend.close(cleanup); cleanup = null;
            return false;
        } catch (Throwable error) {
            log.accept("live swap refused/failed before submission: " + error);
            if (pair != null) {
                try {
                    if (cleanup == null) cleanup = XposedHelpers.newInstance(Class.forName("android.view.SurfaceControl$Transaction"));
                    pair.recover(cleanup, sideScale); XposedHelpers.callMethod(cleanup, "apply");
                } catch (Throwable recoveryError) { log.accept("live pair refusal cleanup: " + recoveryError); }
            }
            return false;
        } finally {
            if (cleanup != null) XposedHelpers.callMethod(cleanup, "close");
        }
    }

}
