package com.jianguo.mistep;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

public final class LiveSwapSessionTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static final class Tx {
        int pictures, input, ownership, parents;
        boolean applied, closed;
        Runnable commit;
        void committed() { if (commit != null) { Runnable ready = commit; commit = null; ready.run(); } }
    }
    private static final class Backend implements MiStepLiveSwapSession.Backend {
        MiStepLiveSwapSession.Handler handler;
        Consumer<Double> frame;
        Runnable end;
        int starts, cancels, aborts;
        boolean failPaint, holdAwait;
        Consumer<Boolean> awaited;
        final List<Tx> owned = new ArrayList<>();
        @Override public void assertShellThread() {}
        @Override public Object startTransition(Object wct, MiStepLiveSwapSession.Handler handler) {
            starts++; this.handler = handler; return "ours";
        }
        @Override public Object newTransaction() { Tx tx = new Tx(); owned.add(tx); return tx; }
        @Override public void parent(Object tx, Object surface, Object parent, int layer) { ((Tx) tx).parents++; }
        @Override public void paint(Object tx, Object surface, LiveSwapGeometry.Frame frame) {
            if (failPaint && ((Tx) tx).pictures == 1) throw new IllegalStateException("lost second leash");
            ((Tx) tx).pictures++;
        }
        @Override public void apply(Object transaction) {
            Tx tx = (Tx) transaction;
            if (tx.pictures != 0) check(tx.pictures == 2 && tx.input == 1, "partial two-task frame applied");
            tx.applied = true;
            tx.committed();
        }
        @Override public void close(Object transaction) { ((Tx) transaction).closed = true; }
        @Override public void committed(Object transaction, Runnable completion) { ((Tx) transaction).commit = completion; }
        @Override public void await(BooleanSupplier condition, long timeout, Consumer<Boolean> completion) {
            if (holdAwait || !condition.getAsBoolean()) awaited = completion; else completion.accept(true);
        }
        @Override public void abortWaiting(Object token) { aborts++; handler.consumed(token, true, null); }
        @Override public MiStepLiveSwapSession.Animation animate(long duration, Consumer<Double> frame, Runnable end) {
            this.frame = frame; this.end = end;
            return () -> { cancels++; end.run(); };
        }
    }
    private static final class Scene implements MiStepLiveSwapSession.Scene {
        boolean allowed = true, ready = true, failPrepare, failResolve;
        Throwable finalError;
        int prepares, finishes, consumes, completions, waitingCancels;
        boolean failed;
        MiStepLiveSwapSession.State finalState;
        final Object a = new Object(), b = new Object(), parent = new Object();
        @Override public boolean mayStart() { return allowed; }
        @Override public MiStepLiveSwapSession.Ready resolve(Object info) {
            if (failResolve) throw new IllegalStateException("exact Home transition mismatch");
            if (!ready) return null;
            return new MiStepLiveSwapSession.Ready(a, b, parent, 4, 5,
                    LiveSwapGeometry.Affine.identity(), LiveSwapGeometry.Affine.identity(),
                    LiveSwapGeometry.Affine.identity());
        }
        @Override public void prepare(Object start, Object finish, MiStepLiveSwapSession.Ready ready) {
            prepares++;
            if (failPrepare) throw new IllegalStateException("claim A failed after B retirement");
            ((Tx) start).ownership++;
        }
        @Override public void input(Object tx, LiveSwapGeometry.Frame[] frame) {
            check(frame.length == 2 && frame[0].task.id != frame[1].task.id, "input identity missing");
            ((Tx) tx).input++;
        }
        @Override public void finish(Object transaction, boolean failed) {
            finishes++; this.failed = failed; ((Tx) transaction).ownership++;
        }
        @Override public void consumed(Object transaction, boolean aborted) {
            consumes++; ((Tx) transaction).ownership++;
        }
        @Override public void complete(MiStepLiveSwapSession.State state, Throwable error) {
            completions++; finalState = state; finalError = error;
        }
        @Override public void requestWaitingCancel() { waitingCancels++; }
        @Override public boolean waitingCancelReady() { return true; }
    }
    private static final class Fixture {
        final Backend backend = new Backend();
        final Scene scene = new Scene();
        final Tx start = new Tx(), finish = new Tx();
        int callbacks;
        boolean holdFinishCommit;
        final MiStepLiveSwapSession session;
        Fixture() {
            LiveSwapGeometry.Rect main = new LiveSwapGeometry.Rect(304, 661, 896, 1999);
            LiveSwapGeometry.Rect side = new LiveSwapGeometry.Rect(0, 240, 297, 663);
            session = new MiStepLiveSwapSession(backend, scene,
                    new LiveSwapGeometry.Task(11820, "A", 1200, 2676, main, side),
                    new LiveSwapGeometry.Task(11815, "B", 1200, 2676, side, main), 300);
        }
        boolean begin() {
            check(session.start(new Object()), "single transition did not start");
            return backend.handler.start("ours", new Object(), start, finish, wct -> {
                callbacks++;
                if (!holdFinishCommit) finish.committed();
            });
        }
    }
    public static void main(String[] args) {
        Fixture normal = new Fixture();
        check(normal.begin(), "did not claim own transition");
        normal.backend.frame.accept(.25); normal.backend.frame.accept(.75); normal.backend.frame.accept(1d);
        normal.backend.end.run(); normal.backend.end.run();
        check(normal.backend.starts == 1, "multiple transitions submitted");
        check(normal.start.applied && normal.start.parents == 2, "start did not atomically parent both tasks");
        check(normal.callbacks == 1 && normal.scene.finishes == 1 && normal.scene.completions == 1,
                "finish was duplicated by cancel/end reentry");
        check(!normal.finish.applied && !normal.finish.closed, "Shell finish transaction ownership stolen");
        check(normal.finish.ownership == 1 && normal.session.state() == MiStepLiveSwapSession.State.FINISHED,
                "real owner finish missing");
        check(normal.backend.owned.size() == 3, "wrong number of animation frames");
        for (Tx tx : normal.backend.owned) check(tx.applied && tx.closed, "frame transaction leaked");
        normal.backend.handler.consumed("ours", true, new Tx());
        check(normal.scene.consumes == 0 && normal.callbacks == 1, "finished transition consumed twice");

        Fixture blocked = new Fixture(); blocked.scene.allowed = false;
        check(!blocked.session.start(new Object()) && blocked.backend.starts == 0,
                "unverified input/parent started WCT");

        Fixture foreign = new Fixture(); foreign.session.start(new Object());
        check(!foreign.backend.handler.start("foreign", new Object(), foreign.start, foreign.finish, wct -> foreign.callbacks++),
                "foreign transition swallowed");
        check(!foreign.start.applied && foreign.scene.prepares == 0, "foreign transition mutated");
        foreign.backend.handler.consumed("foreign", true, new Tx());
        check(foreign.session.state() == MiStepLiveSwapSession.State.WAITING, "foreign consume changed session");

        Fixture missing = new Fixture(); missing.scene.ready = false;
        check(missing.begin(), "accepted WCT delegated competing ownership to default animator");
        check(missing.scene.prepares == 0 && !missing.start.applied && missing.callbacks == 1,
                "unresolved accepted pair did not short-circuit native finish");
        Tx delegated = new Tx(); missing.backend.handler.consumed("ours", false, delegated);
        check(missing.scene.consumes == 0 && !delegated.applied && !delegated.closed,
                "failed native transition was consumed twice");

        Fixture cancelled = new Fixture(); cancelled.begin();
        Tx recovery = new Tx();
        cancelled.backend.handler.consumed("ours", true, recovery);
        check(cancelled.scene.completions == 0, "consumed Shell finish was reported before transaction commit");
        recovery.committed();
        cancelled.backend.handler.consumed("ours", true, recovery);
        cancelled.backend.end.run(); cancelled.backend.frame.accept(.9);
        check(cancelled.callbacks == 0 && cancelled.scene.finishes == 0 && cancelled.scene.consumes == 1,
                "consumed transition invoked native finish again");
        check(cancelled.scene.completions == 1 && recovery.ownership == 1,
                "consumed recovery/completion repeated");
        check(!recovery.applied && !recovery.closed, "consumed provided transaction stolen");

        Fixture noTransaction = new Fixture(); noTransaction.begin();
        noTransaction.backend.handler.consumed("ours", true, null);
        check(noTransaction.backend.owned.size() == 1 && noTransaction.backend.owned.get(0).applied
                && noTransaction.backend.owned.get(0).closed, "owned abort transaction not applied/closed");

        Fixture lostLeash = new Fixture(); lostLeash.begin(); lostLeash.backend.failPaint = true;
        lostLeash.backend.frame.accept(.5); lostLeash.backend.end.run();
        check(lostLeash.callbacks == 1 && lostLeash.scene.finishes == 1 && lostLeash.scene.failed,
                "mid-frame leash loss did not finish once");
        check(lostLeash.backend.owned.get(0).closed && !lostLeash.backend.owned.get(0).applied,
                "half frame submitted after second leash failure");

        Fixture failedOwnership = new Fixture(); failedOwnership.scene.failPrepare = true;
        check(failedOwnership.begin(), "partially mutated ownership incorrectly forwarded to default handler");
        check(failedOwnership.callbacks == 1 && failedOwnership.scene.failed && !failedOwnership.start.applied,
                "failed owner preparation did not recover and finish once");
        check(!normal.session.start(new Object()), "completed session reused");

        Fixture commitGate = new Fixture(); commitGate.holdFinishCommit = true; commitGate.begin();
        commitGate.backend.end.run();
        check(commitGate.callbacks == 1 && commitGate.scene.completions == 0,
                "finish callback return was mistaken for transaction commit");
        commitGate.finish.committed(); commitGate.finish.committed();
        check(commitGate.scene.completions == 1, "committed finish delivered repeatedly");

        Fixture lostCommit = new Fixture(); lostCommit.holdFinishCommit = true; lostCommit.begin();
        lostCommit.backend.end.run(); lostCommit.backend.awaited.accept(false);
        check(lostCommit.scene.completions == 1 && lostCommit.session.state() == MiStepLiveSwapSession.State.FAILED,
                "missing commit acknowledgement held completion forever");
        lostCommit.finish.committed();
        check(lostCommit.scene.completions == 1, "late commit repeated failed completion");

        Fixture userCancel = new Fixture(); userCancel.begin(); userCancel.session.cancel();
        check(userCancel.callbacks == 1 && userCancel.scene.failed && userCancel.scene.completions == 1,
                "running cancellation did not finalize native roles exactly once");

        Fixture pendingCancel = new Fixture(); pendingCancel.session.start(new Object());
        pendingCancel.session.cancel(); pendingCancel.session.cancel();
        check(pendingCancel.scene.waitingCancels == 1 && pendingCancel.backend.aborts == 1
                && pendingCancel.scene.completions == 1, "pending cancellation was not reversed and consumed once");

        Fixture readyWhileCancel = new Fixture(); readyWhileCancel.backend.holdAwait = true;
        readyWhileCancel.session.start(new Object()); readyWhileCancel.session.cancel();
        check(readyWhileCancel.backend.handler.start("ours", new Object(), readyWhileCancel.start,
                readyWhileCancel.finish, wct -> { readyWhileCancel.callbacks++; readyWhileCancel.finish.committed(); }),
                "ready cancellation delegated accepted task pair");
        check(readyWhileCancel.scene.prepares == 0 && readyWhileCancel.callbacks == 0,
                "cancellation before inverse-role confirmation changed ownership");
        readyWhileCancel.backend.awaited.accept(true);
        check(readyWhileCancel.callbacks == 1 && readyWhileCancel.scene.completions == 1,
                "inverse-role acknowledgement did not finish held transition");

        Fixture cancelTimeout = new Fixture(); cancelTimeout.backend.holdAwait = true;
        cancelTimeout.session.start(new Object()); cancelTimeout.session.cancel();
        cancelTimeout.backend.awaited.accept(false);
        check(cancelTimeout.scene.completions == 1 && cancelTimeout.backend.aborts == 0
                && cancelTimeout.session.state() == MiStepLiveSwapSession.State.FAILED,
                "unconfirmed inverse roles were aborted or held forever");
        check(cancelTimeout.backend.handler.start("ours", new Object(), cancelTimeout.start,
                cancelTimeout.finish, wct -> { cancelTimeout.callbacks++; cancelTimeout.finish.committed(); }),
                "late Ready after timeout escaped to a competing default animator");
        check(cancelTimeout.callbacks == 1 && cancelTimeout.scene.completions == 1,
                "late Ready repeated completion or stranded native finish");
        Fixture resolveFailure = new Fixture(); resolveFailure.scene.failResolve = true;
        check(resolveFailure.begin(), "resolve failure escaped to another animator");
        check(resolveFailure.scene.finalError != null && resolveFailure.scene.finalError.getCause() != null
                        && resolveFailure.scene.finalError.getCause().getMessage().equals("exact Home transition mismatch"),
                "original resolve failure was swallowed");
        check(resolveFailure.callbacks == 1 && resolveFailure.scene.completions == 1,
                "resolve failure did not finish once");
        System.out.println("LiveSwapSessionTest: " + checks + " checks passed");
    }
}
