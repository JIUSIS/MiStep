package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Slide the real SurfaceView and its hole together; hold outside until native handback. */
final class MiStepSideDismiss {
    private final View root, view;
    private final float baseX, distance;
    private final BooleanSupplier current;
    private final Consumer<Boolean> complete;
    private final Consumer<String> log;
    private final View.OnAttachStateChangeListener detach;
    private ValueAnimator animator;
    private boolean delivered, closed, written;
    private float writtenX;
    private float offset;
    private boolean settling;

    MiStepSideDismiss(View root, View view, float distance, BooleanSupplier current,
            Consumer<Boolean> complete, Consumer<String> log) {
        this.root = root; this.view = view; this.baseX = view.getTranslationX();
        this.distance = distance; this.current = current; this.complete = complete; this.log = log;
        detach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { finish(false); close(); }
        };
        root.addOnAttachStateChangeListener(detach);
    }

    boolean isTracking() { return !settling && !delivered && !closed; }

    void follow(float dx) {
        if (!isTracking() || !Float.isFinite(dx)) return;
        if (!current.getAsBoolean() || !root.isAttachedToWindow()) { finish(false); return; }
        offset = Math.copySign(Math.max(0f, Math.min(Math.abs(distance), dx * Math.signum(distance))), distance);
        position(offset);
    }

    private void position(float value) {
        writtenX = baseX + value;
        view.setTranslationX(writtenX); written = true;
        root.invalidate();
    }

    void start() { settle(true); }

    void settle(boolean commit) {
        if (!isTracking()) return;
        settling = true;
        try {
            if (!current.getAsBoolean() || !root.isAttachedToWindow()) { finish(false); return; }
            final float from = offset, to = commit ? distance : 0f;
            ValueAnimator run = ValueAnimator.ofFloat(0f, 1f);
            animator = run;
            run.setDuration(Math.max(90L, Math.round(220f * Math.abs(to - from) / Math.abs(distance))));
            run.setInterpolator(new android.view.animation.PathInterpolator(.22f, .7f, .12f, 1f));
            run.addUpdateListener(value -> {
                if (animator != run || delivered || closed) return;
                if (!current.getAsBoolean()) { finish(false); return; }
                position(from + (to - from) * (Float) value.getAnimatedValue());
            });
            run.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator value) {
                    if (animator == run && !closed) finish(commit && current.getAsBoolean());
                }
            });
            run.start();
        } catch (Throwable error) { log.accept("side outward animation: " + error); finish(false); }
    }

    private void finish(boolean reachedEdge) {
        if (delivered) return;
        delivered = true;
        ValueAnimator previous = animator; animator = null;
        if (previous != null) previous.cancel();
        if (!reachedEdge) restore();
        // On success the last View transform stays in place until the owner acknowledges.
        complete.accept(reachedEdge);
    }

    void close() {
        if (closed) return;
        closed = true;
        ValueAnimator previous = animator; animator = null;
        if (previous != null) previous.cancel();
        restore();
        root.removeOnAttachStateChangeListener(detach);
    }

    private void restore() {
        if (written && view.getTranslationX() == writtenX) view.setTranslationX(baseX);
        written = false;
    }
}
