package com.jiusis.mistep;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Slides captured cell pixels over the affected column while the real TaskViews settle.
 *
 * A TaskView contains a separate task Surface. Animating the View transform moves only the
 * SurfaceView host and visibly tears the task image from its surface. This bridge owns only
 * bitmap pixels and never a task, token or leash.
 */
final class CellMovePreview {
    private static final long MOVE_MS = 240L;
    private static final long REVEAL_MS = 70L;

    static final class Move {
        final Rect from;
        final Rect to;
        final View owner;

        Move(Rect from, Rect to, View owner) {
            this.from = new Rect(from);
            this.to = new Rect(to);
            this.owner = owner;
        }
    }

    private final FrameLayout parent;
    private final FrameLayout overlay;
    private final Bitmap snapshot;
    private final List<ImageView> moving = new ArrayList<>();
    private final List<Move> moves;
    private ValueAnimator animator;
    private Runnable onClosed;
    private boolean closed;

    CellMovePreview(FrameLayout parent, Bitmap snapshot, Rect snapshotRect,
            List<Move> moves) {
        this.parent = parent;
        this.snapshot = snapshot;
        this.moves = new ArrayList<>(moves);
        overlay = new FrameLayout(parent.getContext());
        overlay.setClipChildren(false);
        overlay.setElevation(1200f);
        overlay.setOnTouchListener((view, event) -> true);

        int maskTop = Integer.MAX_VALUE;
        int maskBottom = Integer.MIN_VALUE;
        for (Move move : this.moves) {
            maskTop = Math.min(maskTop, Math.min(move.from.top, move.to.top));
            maskBottom = Math.max(maskBottom, Math.max(move.from.bottom, move.to.bottom));
        }
        View mask = new View(parent.getContext());
        mask.setBackgroundColor(HyperOsStyle.page(parent.getContext()));
        FrameLayout.LayoutParams maskParams = new FrameLayout.LayoutParams(
                snapshotRect.width(), maskBottom - maskTop);
        maskParams.leftMargin = snapshotRect.left;
        maskParams.topMargin = maskTop;
        overlay.addView(mask, maskParams);

        for (Move move : this.moves) {
            Bitmap pixels = Bitmap.createBitmap(snapshot,
                    move.from.left - snapshotRect.left,
                    move.from.top - snapshotRect.top,
                    move.from.width(), move.from.height());
            ImageView image = new ImageView(parent.getContext());
            image.setImageBitmap(pixels);
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            image.setPivotX(0f);
            image.setPivotY(0f);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    move.from.width(), move.from.height());
            params.leftMargin = move.from.left;
            params.topMargin = move.from.top;
            overlay.addView(image, params);
            moving.add(image);
        }

        parent.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        parent.requestLayout();

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(MOVE_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            float fraction = (Float) animation.getAnimatedValue();
            for (int i = 0; i < this.moves.size(); i++) {
                Move move = this.moves.get(i);
                ImageView image = moving.get(i);
                image.setTranslationX((move.to.left - move.from.left) * fraction);
                image.setTranslationY((move.to.top - move.from.top) * fraction);
                image.setScaleX(1f + ((float) move.to.width() / move.from.width() - 1f)
                        * fraction);
                image.setScaleY(1f + ((float) move.to.height() / move.from.height() - 1f)
                        * fraction);
            }
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!closed) reveal();
            }
        });
        overlay.postOnAnimation(animator::start);
    }

    void setOnClosed(Runnable callback) {
        onClosed = callback;
        if (closed && onClosed != null) onClosed.run();
    }

    private void reveal() {
        // Keep the settled captured frame visible while the real task Surface catches up.
        overlay.postOnAnimation(() -> overlay.postOnAnimation(() -> {
            if (closed) return;
            ValueAnimator fade = ValueAnimator.ofFloat(1f, 0f);
            fade.setDuration(REVEAL_MS);
            fade.addUpdateListener(animation ->
                    overlay.setAlpha((Float) animation.getAnimatedValue()));
            fade.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    close();
                }
            });
            fade.start();
        }));
    }

    void close() {
        if (closed) return;
        closed = true;
        if (animator != null) animator.cancel();
        try {
            parent.removeView(overlay);
        } catch (Throwable ignored) {
            // Parent may already be detached during a scene exit.
        }
        parent.postDelayed(() -> {
            for (ImageView image : moving) image.setImageDrawable(null);
            if (!snapshot.isRecycled()) snapshot.recycle();
        }, 120L);
        if (onClosed != null) onClosed.run();
    }
}
