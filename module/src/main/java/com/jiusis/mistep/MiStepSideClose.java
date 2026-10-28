package com.jiusis.mistep;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Borrow only the closing task's real transition leash until native finish. */
final class MiStepSideClose {
    private final MiStepLiveSwapSession.Backend backend;
    final Object controller, surface, parent;
    private final Object background;
    private final float scale, width, height;
    private final BooleanSupplier current;
    private final Runnable released;
    private final Consumer<String> log;
    private boolean prepared, ended;
    private MiStepLiveSwapSession.Animation animation;
    private Runnable pendingFinish;

    MiStepSideClose(MiStepLiveSwapSession.Backend backend, Object controller, Object surface,
            Object parent, Object background, float scale, float width, float height, BooleanSupplier current,
            Runnable released, Consumer<String> log) {
        this.backend = backend; this.controller = controller; this.surface = surface; this.parent = parent;
        this.background = background;
        this.scale = scale; this.width = width; this.height = height;
        this.current = current; this.released = released; this.log = log;
    }

    void prepare(Object start, Object finish) {
        if (!current.getAsBoolean() || !valid(surface) || !valid(parent)) return;
        // Native prepareCloseAnimation has reset its controller and set alpha=0.
        // The Change leash remains alive until the original finish callback.
        XposedHelpers.callMethod(start, "reparent", surface, parent);
        XposedHelpers.callMethod(start, "setLayer", surface, Integer.MAX_VALUE - 4);
        paint(start, 0);
        XposedHelpers.callMethod(finish, "hide", surface);
        if (valid(background)) XposedHelpers.callMethod(finish, "hide", background);
        prepared = true;
        log.accept("side close real leash prepared");
    }

    void animate(Runnable nativeFinish) {
        if (ended) { nativeFinish.run(); return; }
        pendingFinish = nativeFinish;
        if (!prepared) { finish(nativeFinish); return; }
        try {
            MiStepLiveSwapSession.Animation created = backend.animate(220L, f -> {
                if (ended) return;
                if (!current.getAsBoolean() || !valid(surface) || !valid(parent)) { finish(nativeFinish); return; }
                Object tx = backend.newTransaction();
                try { paint(tx, f.floatValue()); backend.apply(tx); }
                catch (Throwable error) { log.accept("side close frame: " + error); finish(nativeFinish); }
                finally { backend.close(tx); }
            }, () -> finish(nativeFinish));
            if (ended) created.cancel(); else animation = created;
            backend.await(() -> ended, 1000L, complete -> { if (!complete) finish(nativeFinish); });
        } catch (Throwable error) { log.accept("side close start: " + error); finish(nativeFinish); }
    }

    private void paint(Object tx, float f) {
        paint(tx, surface, scale, f);
        if (valid(background)) paint(tx, background, 1f, f);
    }

    private void paint(Object tx, Object surface, float scale, float f) {
        float remaining = Math.max(.001f, 1f - f);
        XposedHelpers.callMethod(tx, "setMatrix", surface, scale * remaining, 0f, 0f, scale * remaining);
        XposedHelpers.callMethod(tx, "setPosition", surface, width * f / 2, height * f / 2);
        XposedHelpers.callMethod(tx, "setAlpha", surface, Math.max(0f, 1f - f));
        XposedHelpers.callMethod(tx, "show", surface);
    }

    void abandon() { finish(() -> {}); }

    private void finish(Runnable nativeFinish) {
        if (ended) return;
        ended = true;
        if (animation != null) animation.cancel();
        try {
            if (prepared && valid(surface)) {
                Object tx = backend.newTransaction();
                try {
                    XposedHelpers.callMethod(tx, "hide", surface);
                    if (valid(background)) XposedHelpers.callMethod(tx, "hide", background);
                    backend.apply(tx);
                }
                finally { backend.close(tx); }
            }
        } catch (Throwable error) { log.accept("side close finish: " + error); }
        finally { try { (pendingFinish == null ? nativeFinish : pendingFinish).run(); } finally { released.run(); } }
    }

    private static boolean valid(Object surface) {
        return surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"));
    }
}
