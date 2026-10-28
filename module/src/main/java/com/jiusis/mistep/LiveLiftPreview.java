package com.jiusis.mistep;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.animation.AccelerateDecelerateInterpolator;

import de.robv.android.xposed.XposedHelpers;

/** Animate a real TaskView surface; no screenshot is enlarged for the promoted task. */
final class LiveLiftPreview {
    private final Object surface;
    private final Rect from;
    private final Rect to;
    private final ValueAnimator animator;
    private boolean closed;

    LiveLiftPreview(Object surface, Rect from, Rect to) {
        if (surface == null) throw new IllegalArgumentException("surface is required");
        this.surface = surface;
        this.from = new Rect(from);
        this.to = new Rect(to);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SwapPreview.DURATION_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> apply((Float) a.getAnimatedValue()));
        apply(0f);
    }

    void start() {
        if (!closed) animator.start();
    }

    private void apply(float fraction) {
        if (closed) return;
        Object transaction = null;
        try {
            float sx = 1f + ((float) to.width() / from.width() - 1f) * fraction;
            float sy = 1f + ((float) to.height() / from.height() - 1f) * fraction;
            float x = from.left + (to.left - from.left) * fraction;
            float y = from.top + (to.top - from.top) * fraction;
            transaction = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(transaction, "setMatrix", surface, sx, 0f, 0f, sy);
            XposedHelpers.callMethod(transaction, "setPosition", surface, x, y);
            XposedHelpers.callMethod(transaction, "apply");
        } catch (Throwable error) {
            android.util.Log.i("MiStepProbe", "live lift surface transform failed: " + error);
        } finally {
            if (transaction != null) {
                try { XposedHelpers.callMethod(transaction, "close"); } catch (Throwable ignored) {}
            }
        }
    }

    void closeAndRestore(Rect currentSlot) {
        if (closed) return;
        animator.cancel();
        closed = true;
        Object transaction = null;
        try {
            transaction = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(transaction, "setMatrix", surface, 1f, 0f, 0f, 1f);
            Rect restore = currentSlot == null ? from : currentSlot;
            XposedHelpers.callMethod(transaction, "setPosition", surface, restore.left, restore.top);
            XposedHelpers.callMethod(transaction, "apply");
        } catch (Throwable error) {
            android.util.Log.i("MiStepProbe", "live lift restore failed: " + error);
        } finally {
            if (transaction != null) {
                try { XposedHelpers.callMethod(transaction, "close"); } catch (Throwable ignored) {}
            }
        }
    }
}
