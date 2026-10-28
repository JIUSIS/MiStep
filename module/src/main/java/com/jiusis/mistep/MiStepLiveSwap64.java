package com.jiusis.mistep;

import android.graphics.Rect;
import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Real-task flip/Home adapter. Temporary parent is the verified identity Host window root. */
final class MiStepLiveSwap64 {
    private MiStepLiveSwap64() {}
    private static final IdentityHashMap<Object, Run> ACTIVE = new IdentityHashMap<>();
    static void cancel(Object controller) {
        Run run;
        synchronized (ACTIVE) { run = ACTIVE.get(controller); }
        if (run != null) run.backend.execute(() -> { run.cancelled = true; run.session.cancel(); });
    }
    static boolean start(ClassLoader loader, Object taskViews, Object controller,
            LiveTaskSwapDriver.Flip flip, LiveTaskSwapDriver.Home home, BooleanSupplier current,
            Consumer<LiveSwapGeometry.Frame[]> input, Consumer<MiStepLiveSwap.Result> done,
            Consumer<String> log) {
        synchronized (ACTIVE) { if (ACTIVE.containsKey(controller)) return false; }
        try {
            Run run = new Run(loader, taskViews, controller, flip, home, current, input, done, log);
            if (!run.preflight()) return false;
            synchronized (ACTIVE) { if (ACTIVE.containsKey(controller)) return false; ACTIVE.put(controller, run); }
            if (run.start()) return true;
            synchronized (ACTIVE) { if (ACTIVE.get(controller) == run) ACTIVE.remove(controller); }
        } catch (Throwable error) { log.accept("64 live request refused: " + error); }
        return false;
    }

    private static final class Run implements MiStepLiveSwapSession.Scene {
        final Object taskViews, controller, organizer, shell, displayParent, root;
        final MiStepLiveSwapSession.ShellBackend backend;
        final LiveTaskSwapDriver.Flip flip;
        final LiveTaskSwapDriver.Home home;
        final BooleanSupplier current;
        final Consumer<LiveSwapGeometry.Frame[]> input;
        final Consumer<MiStepLiveSwap.Result> done;
        final Consumer<String> log;
        final LiveSwapGeometry.Task[] tasks;
        final Object[] peerLeashes, peerParents;
        final Object[] backgrounds;
        final MiStepLiveBackdrop backdrop;
        final MiStepLiveTaskPair pair;
        final MiStepLiveHomeTask single;
        final float oldSideScale, targetSideScale;
        MiStepLiveSwapSession session;
        boolean cancelled, waitingCancel, rollback, uncertain, delivered, resolved;
        Throwable settleError;
        Object finishReplay;
        long commitGeneration;

        Run(ClassLoader loader, Object taskViews, Object controller, LiveTaskSwapDriver.Flip flip,
                LiveTaskSwapDriver.Home home, BooleanSupplier current,
                Consumer<LiveSwapGeometry.Frame[]> input, Consumer<MiStepLiveSwap.Result> done,
                Consumer<String> log) throws Exception {
            this.taskViews = taskViews; this.controller = controller; this.flip = flip; this.home = home;
            this.current = current; this.input = input; this.done = done; this.log = log;
            shell = XposedHelpers.getObjectField(taskViews, "mTransitions");
            backend = new MiStepLiveSwapSession.ShellBackend(shell, loader); backend.assertShellThread();
            organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
            Object area = XposedHelpers.getObjectField(organizer, "mRootTaskDisplayAreaOrganizer");
            Object areaInfo = XposedHelpers.callMethod(area, "getDisplayAreaInfo", 0);
            if (areaInfo == null || XposedHelpers.getIntField(areaInfo, "featureId") != 1
                    || XposedHelpers.getIntField(areaInfo, "displayId") != 0)
                throw new IllegalStateException("verified display area unavailable");
            displayParent = XposedHelpers.callMethod(area, "getDisplayAreaLeash", 0);
            Object sideParent = XposedHelpers.getObjectField(controller, "mSurfaceControl");
            root = flip != null ? flip.hostRoot : home.hostRoot;
            backgrounds = flip != null ? flip.cellBackgrounds : home.cellBackgrounds;
            backdrop = (MiStepLiveBackdrop) (flip != null ? flip.backdrop : home.backdrop);
            if (flip != null) {
                pair = new MiStepLiveTaskPair(organizer, taskViews, controller, flip.first.id, flip.first.token,
                        flip.second.id, flip.second.token, flip.userId, displayParent, sideParent, flip.logical, log);
                single = null;
                tasks = new LiveSwapGeometry.Task[2 + flip.peers.length];
                tasks[0] = flip.first; tasks[1] = flip.second;
                peerLeashes = new Object[flip.peers.length]; peerParents = new Object[flip.peers.length];
                for (int i = 0; i < flip.peers.length; i++) tasks[i + 2] = flip.peers[i].task;
                oldSideScale = uniform(flip.second.start, flip.second);
                targetSideScale = uniform(flip.first.end, flip.first);
            } else {
                pair = null;
                single = new MiStepLiveHomeTask(organizer, taskViews, controller, displayParent, sideParent, home);
                tasks = new LiveSwapGeometry.Task[]{home.app};
                peerLeashes = new Object[0]; peerParents = new Object[0];
                oldSideScale = uniform(home.app.start, home.app); targetSideScale = oldSideScale;
            }
            if (!MiStepLiveHomeTask.valid(root)) throw new IllegalStateException("Host identity root missing");
            LiveSwapGeometry.Affine[] content = new LiveSwapGeometry.Affine[tasks.length];
            java.util.Arrays.fill(content, LiveSwapGeometry.Affine.identity());
            LiveSwapGeometry.frames(tasks, LiveSwapGeometry.Affine.identity(), content, 0);
        }
        static float uniform(LiveSwapGeometry.Rect rect, LiveSwapGeometry.Task task) {
            double x = rect.width / task.bufferWidth, y = rect.height / task.bufferHeight;
            if (Math.abs(x - y) > .002 || x <= 0 || x >= 1)
                throw new IllegalArgumentException("non-uniform or unscaled task geometry");
            return (float) x;
        }
        boolean preflight() {
            if (!current.getAsBoolean() || (pair != null ? !pair.preflight() : !single.preflight())) return false;
            try { if (flip != null) for (int i = 0; i < flip.peers.length; i++) peer(i); return true; }
            catch (Throwable error) { log.accept("64 peer preflight refused: " + error); return false; }
        }
        Object peer(int i) {
            LiveTaskSwapDriver.Peer peer = flip.peers[i];
            Object entry = XposedHelpers.callMethod(XposedHelpers.getObjectField(organizer, "mTasks"), "get", peer.task.id);
            if (entry == null) throw new IllegalStateException("peer disappeared");
            Object info = XposedHelpers.callMethod(entry, "getTaskInfo");
            if (!peer.task.token.equals(XposedHelpers.getObjectField(info, "token"))
                    || XposedHelpers.getIntField(info, "userId") != flip.userId
                    || XposedHelpers.getIntField(info, "displayId") != 0
                    || !Integer.valueOf(6).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                    || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getActivityType"))
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                    || XposedHelpers.callMethod(organizer, "getTaskListener", info, false) != peer.controller
                    || !peer.task.token.equals(XposedHelpers.getObjectField(peer.controller, "mTaskToken")))
                throw new IllegalStateException("peer identity/owner changed");
            Object leash = XposedHelpers.callMethod(entry, "getLeash");
            Object parent = XposedHelpers.getObjectField(peer.controller, "mSurfaceControl");
            if (!MiStepLiveHomeTask.valid(leash) || !MiStepLiveHomeTask.valid(parent)
                    || !Boolean.TRUE.equals(XposedHelpers.getObjectField(peer.controller, "mSurfaceCreated")))
                throw new IllegalStateException("peer surface unavailable");
            if (peerLeashes[i] != null && !XposedHelpers.callMethod(peerLeashes[i], "getLayerId")
                    .equals(XposedHelpers.callMethod(leash, "getLayerId")))
                throw new IllegalStateException("peer leash changed during lease");
            peerLeashes[i] = leash; peerParents[i] = parent; return leash;
        }
        boolean start() throws Exception {
            if (pair != null) pair.arm(controller.getClass().getClassLoader());
            session = new MiStepLiveSwapSession(backend, this, tasks, 300L);
            return session.start(pair != null ? pair.windowTransaction(null) : single.windowTransaction(false));
        }
        @Override public boolean mayStart() { return current.getAsBoolean() && !cancelled; }
        @Override public MiStepLiveSwapSession.Ready resolve(Object info) {
            if (!current.getAsBoolean() || !MiStepLiveHomeTask.valid(root)) return null;
            Object[] surfaces = new Object[tasks.length]; int[] layers = new int[tasks.length];
            LiveSwapGeometry.Affine[] content = new LiveSwapGeometry.Affine[tasks.length];
            if (pair != null) {
                MiStepLiveSwapSession.Ready two = pair.resolve(info, LiveSwapGeometry.Affine.identity());
                if (two == null) return null;
                surfaces[0] = two.firstSurface; surfaces[1] = two.secondSurface;
                for (int i = 0; i < flip.peers.length; i++) surfaces[i + 2] = peer(i);
            } else surfaces[0] = single.resolve(info);
            // B is above shrinking A and the two moving peers while it grows in its original column.
            for (int i = 0; i < tasks.length; i++) { layers[i] = i + 10; content[i] = LiveSwapGeometry.Affine.identity(); }
            if (pair != null) { layers[0] = 100; layers[1] = 101; }
            resolved = true;
            return new MiStepLiveSwapSession.Ready(surfaces, root, layers, LiveSwapGeometry.Affine.identity(), content);
        }
        void background(Object tx, boolean show) {
            for (Object surface : backgrounds) if (MiStepLiveHomeTask.valid(surface))
                // A successful Home promotion retires its side task. Its former backing
                // must stay hidden in this same finish, before Host clears the binding.
                XposedHelpers.callMethod(tx, show && (home == null || rollback) ? "show" : "hide", surface);
            // Crop changes share the real-task reparent transaction. Host releases
            // the remaining side strip after restored cell drawings have committed.
            if (backdrop != null) backdrop.stage(tx, show, rollback, home == null);
        }
        @Override public void prepare(Object start, Object finish, MiStepLiveSwapSession.Ready ready) {
            if (!mayStart()) throw new IllegalStateException("64 scene changed before start");
            try { if (pair != null) pair.prepare(start, finish); else single.prepare(); }
            catch (Exception error) { throw new IllegalStateException("64 native owner preparation", error); }
            background(start, false);
            if (single != null) single.stageContainer(start, false);
        }
        @Override public void input(Object tx, LiveSwapGeometry.Frame[] frames) {
            if (!mayStart()) throw new IllegalStateException("64 scene changed during live frame");
            if (flip != null) for (int i = 0; i < flip.peers.length; i++) peer(i);
            input.accept(frames);
        }
        @Override public void cancelFinishing() { cancelled = true; }
        @Override public void beforeFinish(boolean failed, Consumer<Throwable> ready) {
            if (waitingCancel) { rollback = true; ready.accept(null); return; }
            if (home != null) {
                if (failed || cancelled || !current.getAsBoolean()) reverse(ready, new IllegalStateException("Home live cancelled"));
                else ready.accept(null);
                return;
            }
            if (failed || cancelled || !current.getAsBoolean() || !resolved) {
                reverse(ready, new IllegalStateException("live flip cancelled before layout handoff")); return;
            }
            commits(flip.targetZoom, false, targetError -> {
                if (targetError == null && !cancelled && current.getAsBoolean()) ready.accept(null);
                else reverse(ready, targetError == null ? new IllegalStateException("flip cancelled during commit") : targetError);
            });
        }
        void commits(LiveSwapZoom.Zoom zoom, boolean restoring, Consumer<Throwable> ready) {
            commitGeneration = zoom.generation;
            final boolean[] seen = {false, false}; final Throwable[] error = {null};
            try {
                flip.layout.apply(zoom, restoring, ack -> backend.execute(() -> {
                    if (delivered || commitGeneration != zoom.generation) return;
                    if (seen[0]) return; seen[0] = true;
                    if (ack == null || !ack.confirms(zoom.generation)) error[0] = new IllegalStateException("Host layout frame not confirmed");
                }));
                flip.zoom.apply(zoom, ack -> backend.execute(() -> {
                    if (delivered || commitGeneration != zoom.generation) {
                        if (ack != null) LiveSwapZoom.closeReplay(ack.finishReplay); return;
                    }
                    if (seen[1]) { if (ack != null) LiveSwapZoom.closeReplay(ack.finishReplay); return; }
                    seen[1] = true;
                    if (ack == null || !ack.confirmsFinish(zoom)) {
                        if (ack != null) LiveSwapZoom.closeReplay(ack.finishReplay);
                        error[0] = new IllegalStateException("exact WMS zoom commit/finish replay not confirmed");
                    } else {
                        LiveSwapZoom.closeReplay(finishReplay);
                        finishReplay = ack.finishReplay;
                    }
                }));
            } catch (Throwable failure) { error[0] = failure; }
            backend.await(() -> error[0] != null || (seen[0] && seen[1]) || (!restoring && cancelled), 900L,
                    complete -> ready.accept(error[0] != null ? error[0]
                            : complete && seen[0] && seen[1] ? null : new IllegalStateException("layout/zoom commit timeout")));
        }
        void reverse(Consumer<Throwable> ready, Throwable original) {
            settleError = original;
            try {
                Object inverse = pair != null ? pair.reverseWindowTransaction() : single.windowTransaction(true);
                XposedHelpers.callMethod(XposedHelpers.getObjectField(shell, "mOrganizer"), "applyTransaction", inverse);
                backend.await(() -> pair != null ? pair.rollbackRolesReady() : single.roles(true), 1500L, roles -> {
                    if (!roles) { uncertain = true; ready.accept(new IllegalStateException("inverse canonical roles unconfirmed", original)); return; }
                    rollback = true;
                    if (flip == null) { ready.accept(original); return; }
                    LiveSwapZoom.Zoom restored = flip.oldZoom.withNewGeneration(flip.targetZoom.generation + 1);
                    commits(restored, true, restoreError -> {
                        if (restoreError != null) uncertain = true;
                        ready.accept(restoreError == null ? original : restoreError);
                    });
                });
            } catch (Throwable error) { uncertain = true; ready.accept(error); }
        }
        void peersFinish(Object tx, boolean original) {
            if (flip == null) return;
            for (int i = 0; i < flip.peers.length; i++) {
                Object leash = peer(i); LiveSwapGeometry.Task task = flip.peers[i].task;
                float scale = uniform(original ? task.start : task.end, task);
                XposedHelpers.callMethod(tx, "reparent", leash, peerParents[i]);
                XposedHelpers.callMethod(tx, "setMatrix", leash, scale, 0f, 0f, scale);
                XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setWindowCrop", leash, new Rect(flip.logical));
                XposedHelpers.callMethod(tx, "setAlpha", leash, 1f); XposedHelpers.callMethod(tx, "show", leash);
            }
        }
        @Override public void finish(Object tx, boolean failed) {
            if (uncertain) throw new IllegalStateException("64 external/canonical handoff remains unconfirmed");
            if (flip != null && resolved && !waitingCancel && !LiveSwapZoom.replayOpen(finishReplay))
                throw new IllegalStateException("WMS finish replay missing or closed");
            if (pair != null) {
                if (rollback) pair.restoreOriginal(tx, oldSideScale); else pair.finish(tx, targetSideScale);
            } else single.finish(tx, rollback, oldSideScale);
            peersFinish(tx, rollback); background(tx, true);
            if (flip != null && resolved && !waitingCancel) {
                // Last writer of the very same Shell finish transaction, never a later frame.
                XposedHelpers.callMethod(tx, "merge", finishReplay);
                LiveSwapZoom.closeReplay(finishReplay); finishReplay = null;
            }
        }
        @Override public Object finishWindowTransaction() { return pair != null ? pair.finishWindowTransaction() : single.finishWindowTransaction(); }
        @Override public void requestWaitingCancel() {
            waitingCancel = true;
            try {
                Object reverse = pair != null ? pair.reverseWindowTransaction() : single.windowTransaction(true);
                XposedHelpers.callMethod(XposedHelpers.getObjectField(shell, "mOrganizer"), "applyTransaction", reverse);
            } catch (Exception error) { throw new IllegalStateException(error); }
        }
        @Override public boolean waitingCancelReady() { return pair != null ? pair.originalRoles() : single.roles(true); }
        @Override public void consumed(Object tx, boolean aborted) {
            if (flip != null && resolved) { uncertain = true; throw new IllegalStateException("consumed flip requires ledger recovery"); }
            rollback = pair != null ? pair.originalRoles() : single.roles(true);
            if (pair != null) pair.recover(tx, oldSideScale); else single.finish(tx, rollback, oldSideScale);
            peersFinish(tx, true); background(tx, true);
        }
        @Override public void complete(MiStepLiveSwapSession.State state, Throwable error) {
            if (delivered) return; delivered = true;
            LiveSwapZoom.closeReplay(finishReplay); finishReplay = null;
            synchronized (ACTIVE) { if (ACTIVE.get(controller) == this) ACTIVE.remove(controller); }
            boolean side = !uncertain && (pair != null ? pair.sideOwned() : single.sideOwned());
            boolean roles = !uncertain && (pair != null ? side || pair.rollbackRolesReady() : side || single.roles(false));
            if (waitingCancel && !roles && pair != null) pair.releaseOriginalLeaseWhenSeen();
            boolean success = state == MiStepLiveSwapSession.State.FINISHED && error == null && !rollback
                    && roles && (pair != null ? side : !side);
            done.accept(new MiStepLiveSwap.Result(success, side, roles, error != null ? error : settleError));
        }
    }
}
