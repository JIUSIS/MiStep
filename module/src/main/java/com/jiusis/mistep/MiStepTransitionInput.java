package com.jiusis.mistep;

import android.view.MotionEvent;
import android.view.View;
import java.util.function.Consumer;

/** Own input while real task surfaces are borrowed; release on the final Host frame. */
final class MiStepTransitionInput {
    private final View root;
    private final Consumer<Boolean> changed;
    private final Consumer<String> log;
    private final Runnable acquireWindow, releaseWindow;
    private long generation;
    private boolean blocked, gesture;

    MiStepTransitionInput(View root, Runnable acquireWindow, Runnable releaseWindow,
            Consumer<Boolean> changed, Consumer<String> log) {
        this.root = root; this.changed = changed; this.log = log;
        this.acquireWindow = acquireWindow; this.releaseWindow = releaseWindow;
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                generation++; set(false); gesture = false;
            }
        });
    }

    boolean block() {
        generation++;
        if (blocked) return true;
        try { acquireWindow.run(); set(true); return true; }
        catch (Throwable error) {
            releaseWindow.run(); log.accept("live input ownership unavailable: " + error); return false;
        }
    }

    void releaseAfterFrame() {
        final long expected = ++generation;
        Runnable release = () -> {
            if (generation == expected) set(false);
        };
        if (!root.isAttachedToWindow() || !root.isHardwareAccelerated()) { release.run(); return; }
        try {
            root.getViewTreeObserver().registerFrameCommitCallback(() -> root.post(release));
            root.invalidate();
        } catch (Throwable error) {
            log.accept("live input final frame unavailable: " + error); release.run();
        }
    }

    boolean consume(int action) {
        if (action == MotionEvent.ACTION_DOWN) gesture = blocked;
        boolean consume = blocked || gesture;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) gesture = false;
        return consume;
    }

    private void set(boolean value) {
        if (blocked == value) return;
        if (!value) releaseWindow.run();
        blocked = value; changed.accept(value);
    }
}
