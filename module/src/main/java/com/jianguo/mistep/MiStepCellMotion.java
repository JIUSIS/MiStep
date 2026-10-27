package com.jianguo.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Move the live SurfaceView RenderNode, including its hole, surface and crop, in one frame. */
final class MiStepCellMotion {
    static final class Move {
        final View view;
        final float dx, dy, baseX, baseY;
        final BooleanSupplier current;
        float writtenX, writtenY;
        boolean written;
        Move(View view, float fromX, float fromY, float toX, float toY, BooleanSupplier current) {
            this.view = view; dx = toX - fromX; dy = toY - fromY; this.current = current;
            baseX = view.getTranslationX(); baseY = view.getTranslationY();
        }
        void position(float fraction) {
            writtenX = baseX + dx * fraction; writtenY = baseY + dy * fraction;
            view.setTranslationX(writtenX); view.setTranslationY(writtenY); written = true;
        }
        void restore() {
            // Undo only our last View transform, also if a replacement task arrived in it.
            if (written && view.getTranslationX() == writtenX) view.setTranslationX(baseX);
            if (written && view.getTranslationY() == writtenY) view.setTranslationY(baseY);
        }
    }

    private final View root;
    private final List<Move> moves;
    private final Runnable layout, complete;
    private final Consumer<String> log;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final View.OnAttachStateChangeListener detachListener;
    private ValueAnimator animation;
    private boolean ending;

    MiStepCellMotion(View root, List<Move> moves, Runnable layout, Runnable complete, Consumer<String> log) {
        this.root = root; this.moves = moves; this.layout = layout; this.complete = complete; this.log = log;
        detachListener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { end(); delivered(); }
        };
        root.addOnAttachStateChangeListener(detachListener);
    }

    void start() {
        root.post(() -> {
            if (ending || finished.get()) return;
            try {
                if (!frame(0f)) return;
                ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
                animation = animator;
                animator.setDuration(240L);
                animator.setInterpolator(new android.view.animation.PathInterpolator(.22f, .7f, .12f, 1f));
                animator.addUpdateListener(value -> {
                    if (animation == animator) frame((Float) value.getAnimatedValue());
                });
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator value) { end(); }
                });
                animator.start();
            } catch (Throwable error) { log.accept("cell motion start failed: " + error); end(); }
        });
    }

    void finish() { root.post(this::end); }

    private boolean frame(float fraction) {
        if (ending || finished.get()) return false;
        try {
            for (Move move : moves) if (!move.current.getAsBoolean()) { end(); return false; }
            float p = Math.max(0f, Math.min(1f, fraction));
            // SurfaceView owns its parent surface position. Its RenderThread listener keeps
            // this real View's transparent hole, surface and crop in the same Host frame.
            for (Move move : moves) move.position(p);
            root.invalidate();
            return true;
        } catch (Throwable error) { log.accept("cell motion frame failed: " + error); end(); return false; }
    }

    private void end() {
        if (ending || finished.get()) return;
        ending = true;
        ValueAnimator previous = animation; animation = null;
        if (previous != null) previous.cancel();
        try {
            // applyRect uses View.setX/setY, which write these same translation fields.
            // Restore our animation first, then commit the destination; doing this in
            // reverse can undo a successful final layout and poison the next move.
            for (Move move : moves) move.restore();
            layout.run();
            if (!root.isAttachedToWindow() || !root.isHardwareAccelerated()) { delivered(); return; }
            root.getViewTreeObserver().registerFrameCommitCallback(() -> root.post(this::delivered));
            root.invalidate();
        } catch (Throwable error) {
            for (Move move : moves) move.restore();
            log.accept("cell motion finish failed: " + error); delivered();
        }
    }

    private void delivered() {
        if (!finished.compareAndSet(false, true)) return;
        root.removeOnAttachStateChangeListener(detachListener);
        complete.run();
    }
}
