package com.jiusis.mistep;

import java.lang.reflect.Proxy;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import de.robv.android.xposed.XposedHelpers;

/**
 * Real-surface driver for the verified task-pair adapter. Each ordinary swap submits one WCT.
 */
final class MiStepLiveSwapSession {
    enum State { NEW, WAITING, RUNNING, SETTLING, FINISHED, DECLINED, CONSUMED, FAILED }

    interface Finish { void finish(Object windowTransaction); }
    interface Animation { void cancel(); }
    interface Handler {
        boolean start(Object token, Object info, Object start, Object finish, Finish callback);
        void consumed(Object token, boolean aborted, Object transaction);
    }
    interface Backend {
        void assertShellThread();
        Object startTransition(Object wct, Handler handler);
        Object newTransaction();
        void parent(Object transaction, Object surface, Object parent, int layer);
        void paint(Object transaction, Object surface, LiveSwapGeometry.Frame frame);
        void apply(Object transaction);
        void close(Object transaction);
        void committed(Object transaction, Runnable completion);
        void await(BooleanSupplier condition, long timeout, Consumer<Boolean> completion);
        void abortWaiting(Object token);
        Animation animate(long duration, Consumer<Double> frame, Runnable end);
    }
    interface Scene {
        /** No mutations: require canonical identities, no competing transfer and proven raw input. */
        boolean mayStart();
        /** No mutations: resolve both exact Change leashes and their verified common parent. */
        Ready resolve(Object transitionInfo);
        /** Called only after resolve succeeds. Retire B once, then claim A with the idle controller. */
        void prepare(Object start, Object finish, Ready ready);
        /** Append host touch region and task-specific raw state for this same frame, without blocking input. */
        void input(Object transaction, LiveSwapGeometry.Frame[] frame);
        /** Append real owner parents/matrices/crop and matching input to the Shell-owned finish transaction. */
        void finish(Object transaction, boolean failed);
        /** A consumed transition belongs to Shell; append recovery, never invoke its finish callback again. */
        void consumed(Object transaction, boolean aborted);
        default Object finishWindowTransaction() { return null; }
        /** Undo an accepted WCT through the original organizer, before cancelling Shell pending. */
        void requestWaitingCancel();
        boolean waitingCancelReady();
        /** Keep live leashes in their animation parent until exact external commits are confirmed. */
        default void beforeFinish(boolean failed, Consumer<Throwable> ready) { ready.accept(null); }
        default void cancelFinishing() {}
        void complete(State state, Throwable error);
    }

    static final class Ready {
        final Object firstSurface, secondSurface, parent;
        final int firstLayer, secondLayer;
        final LiveSwapGeometry.Affine parentToPhysical, firstContentFromBuffer, secondContentFromBuffer;
        final Object[] surfaces;
        final int[] layers;
        final LiveSwapGeometry.Affine[] content;
        Ready(Object firstSurface, Object secondSurface, Object parent, int firstLayer, int secondLayer,
                LiveSwapGeometry.Affine parentToPhysical, LiveSwapGeometry.Affine firstContentFromBuffer,
                LiveSwapGeometry.Affine secondContentFromBuffer) {
            this(new Object[]{firstSurface, secondSurface}, parent, new int[]{firstLayer, secondLayer},
                    parentToPhysical, new LiveSwapGeometry.Affine[]{firstContentFromBuffer, secondContentFromBuffer});
        }
        Ready(Object[] surfaces, Object parent, int[] layers, LiveSwapGeometry.Affine parentToPhysical,
                LiveSwapGeometry.Affine[] content) {
            if (surfaces == null || surfaces.length == 0 || layers == null || content == null
                    || surfaces.length != layers.length || surfaces.length != content.length
                    || parent == null || parentToPhysical == null)
                throw new IllegalArgumentException("unverified real task surfaces or parent");
            parentToPhysical.inverse();
            for (int i = 0; i < surfaces.length; i++) {
                if (surfaces[i] == null || content[i] == null) throw new IllegalArgumentException("missing real task");
                content[i].inverse();
                for (int j = 0; j < i; j++)
                    if (surfaces[i] == surfaces[j] || layers[i] == layers[j])
                        throw new IllegalArgumentException("duplicate real surface/layer");
            }
            this.surfaces = surfaces.clone(); this.layers = layers.clone(); this.content = content.clone();
            this.firstSurface = surfaces[0]; this.secondSurface = surfaces.length > 1 ? surfaces[1] : null;
            this.parent = parent; this.firstLayer = layers[0]; this.secondLayer = layers.length > 1 ? layers[1] : 0;
            this.parentToPhysical = parentToPhysical; this.firstContentFromBuffer = content[0];
            this.secondContentFromBuffer = content.length > 1 ? content[1] : null;
        }
    }

    private final Backend backend;
    private final Scene scene;
    private final LiveSwapGeometry.Task[] tasks;
    private final long duration;
    private Object token, finishTransaction;
    private Ready ready;
    private Finish finishCallback;
    private Animation animation;
    private boolean finishCalled, completeCalled;
    private boolean cancellingWaiting, cancelReady, consumedWhileCancelling;
    private State state = State.NEW;

    MiStepLiveSwapSession(Backend backend, Scene scene, LiveSwapGeometry.Task first,
            LiveSwapGeometry.Task second, long duration) {
        this(backend, scene, new LiveSwapGeometry.Task[]{first, second}, duration);
    }
    MiStepLiveSwapSession(Backend backend, Scene scene, LiveSwapGeometry.Task[] tasks, long duration) {
        if (backend == null || scene == null || tasks == null || tasks.length == 0
                || duration <= 0 || duration > 1000L)
            throw new IllegalArgumentException("invalid bounded live swap");
        LiveSwapGeometry.Affine[] content = new LiveSwapGeometry.Affine[tasks.length];
        java.util.Arrays.fill(content, LiveSwapGeometry.Affine.identity());
        LiveSwapGeometry.frames(tasks, LiveSwapGeometry.Affine.identity(), content, 0);
        this.backend = backend; this.scene = scene; this.tasks = tasks.clone();
        this.duration = duration;
    }

    State state() { return state; }

    /** Shell main only. Accepted pending work is reversed before its temporary owner is released. */
    void cancel() {
        backend.assertShellThread();
        if (state == State.SETTLING) { scene.cancelFinishing(); return; }
        if (state == State.RUNNING && !cancellingWaiting) {
            finish(true, new IllegalStateException("live swap cancelled"));
            return;
        }
        if (state != State.WAITING || cancellingWaiting) return;
        cancellingWaiting = true;
        try {
            scene.requestWaitingCancel();
            backend.await(scene::waitingCancelReady, 1500L, ready -> {
                try {
                if (!ready) {
                    state = State.FAILED;
                    complete(new IllegalStateException("original task roles not confirmed after pending cancellation"));
                    return;
                }
                cancelReady = true;
                if (consumedWhileCancelling) {
                    onConsumed(token, true, null);
                } else if (state == State.RUNNING) {
                    finish(true, new IllegalStateException("pending live swap cancelled"));
                } else if (state == State.WAITING) {
                    backend.abortWaiting(token);
                    // A removed/unknown Shell pending may have no handler notification.
                    if (state == State.WAITING) onConsumed(token, true, null);
                }
                } catch (Throwable error) { state = State.FAILED; complete(error); }
            });
        } catch (Throwable error) { state = State.FAILED; complete(error); }
    }

    /** The supplied WCT must contain both task role changes; old startRootTask is never called here. */
    boolean start(Object oneWct) {
        backend.assertShellThread();
        if (state != State.NEW || oneWct == null || !scene.mayStart()) return false;
        state = State.WAITING;
        try {
            token = backend.startTransition(oneWct, new Handler() {
                @Override public boolean start(Object t, Object info, Object start, Object finish, Finish cb) {
                    return onStart(t, info, start, finish, cb);
                }
                @Override public void consumed(Object t, boolean aborted, Object tx) { onConsumed(t, aborted, tx); }
            });
            if (token == null) throw new IllegalStateException("Shell rejected transition");
            return true;
        } catch (Throwable error) {
            state = State.FAILED;
            complete(error);
            return false;
        }
    }

    private boolean onStart(Object t, Object info, Object start, Object finish, Finish cb) {
        backend.assertShellThread();
        if (state == State.FAILED && cancellingWaiting && token != null && token.equals(t)
                && start != null && finish != null && cb != null) {
            // A late Ready after cancellation timeout still belongs to our original WCT.
            // Retire its Shell lifecycle once; do not launch a competing default animator.
            state = State.RUNNING;
            finishTransaction = finish; finishCallback = cb;
            finish(true, new IllegalStateException("late ready after unconfirmed cancellation"));
            return true;
        }
        if (state != State.WAITING || token == null || !token.equals(t)) return false;
        if (start == null || finish == null || cb == null) {
            state = State.DECLINED;
            complete(new IllegalStateException("incomplete native transition callback"));
            return false;
        }
        // Once our WCT was accepted, do not delegate the same task pair to another animator.
        state = State.RUNNING;
        finishTransaction = finish; finishCallback = cb;
        if (cancellingWaiting) {
            if (cancelReady) finish(true, new IllegalStateException("pending live swap cancelled"));
            return true;
        }
        try { ready = scene.resolve(info); }
        catch (Throwable error) {
            finish(true, new IllegalStateException("accepted task scene resolve failed", error));
            return true;
        }
        if (ready == null) {
            finish(true, new IllegalStateException("accepted task pair no longer resolves"));
            return true;
        }
        // From here onward, own finish even if prepare partially changes task-listener state.
        try {
            scene.prepare(start, finish, ready);
            if (ready.surfaces.length != tasks.length) throw new IllegalStateException("real task count changed");
            for (int i = 0; i < tasks.length; i++)
                backend.parent(start, ready.surfaces[i], ready.parent, ready.layers[i]);
            paint(start, 0);
            backend.apply(start);
            Animation created = backend.animate(duration, this::onFrame, () -> finish(false, null));
            if (state == State.RUNNING) animation = created;
            else if (created != null) created.cancel();
        } catch (Throwable error) { finish(true, error); }
        return true;
    }

    private void paint(Object tx, double fraction) {
        LiveSwapGeometry.Frame[] frames = LiveSwapGeometry.frames(tasks,
                ready.parentToPhysical, ready.content, fraction);
        for (int i = 0; i < tasks.length; i++) backend.paint(tx, ready.surfaces[i], frames[i]);
        scene.input(tx, frames);
    }

    private void onFrame(Double fraction) {
        backend.assertShellThread();
        if (state != State.RUNNING) return;
        Object tx = null;
        try {
            tx = backend.newTransaction();
            paint(tx, fraction);
            backend.apply(tx);
        } catch (Throwable error) { finish(true, error); }
        finally { if (tx != null) backend.close(tx); }
    }

    private void finish(boolean failed, Throwable error) {
        backend.assertShellThread();
        if (state != State.RUNNING || finishCalled) return;
        finishCalled = true;
        state = State.SETTLING;
        if (animation != null) animation.cancel();
        try {
            scene.beforeFinish(failed, gateError -> {
                backend.assertShellThread();
                if (state != State.SETTLING) return;
                finishNow(failed || gateError != null, error == null ? gateError : error);
            });
        } catch (Throwable gateError) { finishNow(true, error == null ? gateError : error); }
    }

    private void finishNow(boolean failed, Throwable error) {
        if (state != State.SETTLING) return;
        state = failed ? State.FAILED : State.FINISHED;
        try { scene.finish(finishTransaction, failed); }
        catch (Throwable recoveryError) { state = State.FAILED; if (error == null) error = recoveryError; }
        final Throwable settledError = error;
        try {
            completeAfterCommit(finishTransaction, settledError);
            if (finishCallback != null) finishCallback.finish(scene.finishWindowTransaction());
        } catch (Throwable callbackError) {
            state = State.FAILED;
            complete(error == null ? callbackError : error);
        }
    }

    private void onConsumed(Object t, boolean aborted, Object transaction) {
        backend.assertShellThread();
        if (token == null || !token.equals(t) || state == State.CONSUMED
                || state == State.FINISHED || state == State.FAILED) return;
        if (cancellingWaiting && !cancelReady) {
            consumedWhileCancelling = true;
            return;
        }
        state = State.CONSUMED;
        if (animation != null) animation.cancel();
        Object tx = transaction;
        boolean owned = tx == null;
        Throwable error = null;
        try {
            if (owned) tx = backend.newTransaction();
            scene.consumed(tx, aborted);
            completeAfterCommit(tx, null);
            if (owned) backend.apply(tx);
        } catch (Throwable failure) { error = failure; }
        finally {
            if (owned && tx != null) backend.close(tx);
            if (error != null) complete(error);
        }
    }

    private void complete(Throwable error) {
        if (completeCalled) return;
        completeCalled = true;
        scene.complete(state, error);
    }

    private void completeAfterCommit(Object tx, Throwable error) {
        boolean[] committed = {false};
        backend.committed(tx, () -> { committed[0] = true; complete(error); });
        backend.await(() -> committed[0], 1500L, ready -> {
            if (!ready && !completeCalled) {
                state = State.FAILED;
                complete(new IllegalStateException("native finish transaction commit not acknowledged", error));
            }
        });
    }

    /** Actual 17PM Shell adapter; only explicit callers create it. No global organizer registration. */
    static final class ShellBackend implements Backend {
        private final Object transitions, executor;
        private final ClassLoader loader;
        private final Class<?> transactionClass;
        ShellBackend(Object transitions, ClassLoader loader) throws Exception {
            this.transitions = transitions; this.loader = loader;
            executor = XposedHelpers.getObjectField(transitions, "mMainExecutor");
            transactionClass = Class.forName("android.view.SurfaceControl$Transaction");
        }
        @Override public void assertShellThread() { XposedHelpers.callMethod(executor, "assertCurrentThread"); }
        @Override public Object startTransition(Object wct, Handler handler) {
            try {
                Class<?> type = Class.forName("com.android.wm.shell.transition.Transitions$TransitionHandler", false, loader);
                Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{type}, (p, m, args) -> {
                    String name = m.getName();
                    if (name.equals("startAnimation") && (args.length == 5 || args.length == 6)) {
                        int shift = args.length == 6 ? 1 : 0;
                        Object callback = args[4 + shift];
                        return handler.start(args[0], args[1], args[2 + shift], args[3 + shift],
                                finalWct -> XposedHelpers.callMethod(callback, "onTransitionFinished", finalWct));
                    }
                    if (name.equals("onTransitionConsumed")) {
                        handler.consumed(args[0], Boolean.TRUE.equals(args[1]), args[2]);
                        return null;
                    }
                    // Do not consume/finish a merge we did not animate. Shell queues it after this one.
                    if (name.equals("mergeAnimation")) return null;
                    if (name.equals("canHandleTransition")) return false;
                    if (name.equals("getTransitionType")) return 6;
                    if (name.equals("toString")) return "MiStepSingleLiveSwap";
                    if (name.equals("hashCode")) return System.identityHashCode(p);
                    if (name.equals("equals")) return p == args[0];
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == int.class) return 0;
                    return null;
                });
                return XposedHelpers.callMethod(transitions, "startTransition", 6, wct, proxy);
            } catch (Throwable error) { throw new IllegalStateException("target Shell handler unavailable", error); }
        }
        @Override public Object newTransaction() { return XposedHelpers.newInstance(transactionClass); }
        @Override public void parent(Object tx, Object surface, Object parent, int layer) {
            XposedHelpers.callMethod(tx, "reparent", surface, parent);
            XposedHelpers.callMethod(tx, "setLayer", surface, layer);
        }
        @Override public void paint(Object tx, Object surface, LiveSwapGeometry.Frame frame) {
            LiveSwapGeometry.Affine m = frame.local;
            // SurfaceControl's four-float overload is scaleX, skewY, skewX, scaleY.
            XposedHelpers.callMethod(tx, "setMatrix", surface, (float) m.a, (float) m.b, (float) m.c, (float) m.d);
            XposedHelpers.callMethod(tx, "setPosition", surface, (float) m.x, (float) m.y);
            LiveSwapGeometry.Affine content = frame.contentFromBuffer;
            double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (int x : new int[]{0, frame.task.bufferWidth}) {
                for (int y : new int[]{0, frame.task.bufferHeight}) {
                    double cx = content.mapX(x, y), cy = content.mapY(x, y);
                    minX = Math.min(minX, cx); maxX = Math.max(maxX, cx);
                    minY = Math.min(minY, cy); maxY = Math.max(maxY, cy);
                }
            }
            android.graphics.Rect crop = new android.graphics.Rect((int) Math.floor(minX),
                    (int) Math.floor(minY), (int) Math.ceil(maxX), (int) Math.ceil(maxY));
            XposedHelpers.callMethod(tx, "setWindowCrop", surface, crop);
            XposedHelpers.callMethod(tx, "setAlpha", surface, 1f);
            XposedHelpers.callMethod(tx, "show", surface);
        }
        @Override public void apply(Object tx) { XposedHelpers.callMethod(tx, "apply"); }
        @Override public void close(Object tx) { XposedHelpers.callMethod(tx, "close"); }
        void execute(Runnable action) { XposedHelpers.callMethod(executor, "execute", action); }
        @Override public void committed(Object tx, Runnable completion) {
            XposedHelpers.callMethod(tx, "addTransactionCommittedListener",
                    (java.util.concurrent.Executor) executor,
                    (android.view.SurfaceControl.TransactionCommittedListener) completion::run);
        }
        @Override public void await(BooleanSupplier condition, long timeout, Consumer<Boolean> completion) {
            final long deadline = android.os.SystemClock.uptimeMillis() + timeout;
            Runnable probe = new Runnable() {
                @Override public void run() {
                    boolean ready = condition.getAsBoolean();
                    if (ready || android.os.SystemClock.uptimeMillis() >= deadline) completion.accept(ready);
                    else XposedHelpers.callMethod(executor, "executeDelayed", this, 16L);
                }
            };
            // Let organizer callbacks queued by the synchronous reverse-WCT call run first.
            XposedHelpers.callMethod(executor, "executeDelayed", probe, 16L);
        }
        @Override public void abortWaiting(Object token) {
            XposedHelpers.callMethod(transitions, "abortTransition", token, 0);
            Object known = XposedHelpers.callMethod(XposedHelpers.getObjectField(transitions,
                    "mKnownTransitions"), "get", token);
            if (known != null && XposedHelpers.getObjectField(known, "mInfo") != null
                    && !Boolean.TRUE.equals(XposedHelpers.getObjectField(known, "mAborted")))
                XposedHelpers.callMethod(transitions, "onAbort", known);
        }
        @Override public Animation animate(long duration, Consumer<Double> frame, Runnable end) {
            android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(duration);
            // Match the original lift/drop motion: quick departure, eased settle, no
            // linear cross-fade that makes the two panes appear to teleport.
            animator.setInterpolator(new android.view.animation.PathInterpolator(.34f, .69f, .1f, 1f));
            animator.addUpdateListener(value -> frame.accept(((Float) value.getAnimatedValue()).doubleValue()));
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator value) { end.run(); }
            });
            animator.start();
            return animator::cancel;
        }
    }
}
