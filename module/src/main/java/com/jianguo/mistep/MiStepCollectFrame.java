package com.jianguo.mistep;

import android.view.View;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A committed empty-hint removal must precede reparenting the live app below Host. */
final class MiStepCollectFrame {
    static void await(View root, BooleanSupplier current, Consumer<Boolean> complete) {
        if (root == null || !root.isAttachedToWindow() || !root.isHardwareAccelerated()
                || !current.getAsBoolean()) { complete.accept(false); return; }
        AtomicBoolean done = new AtomicBoolean();
        Runnable[] timeout = new Runnable[1];
        View.OnAttachStateChangeListener[] detach = new View.OnAttachStateChangeListener[1];
        Consumer<Boolean> finish = result -> {
            if (!done.compareAndSet(false, true)) return;
            root.removeCallbacks(timeout[0]);
            root.removeOnAttachStateChangeListener(detach[0]);
            complete.accept(result && root.isAttachedToWindow() && current.getAsBoolean());
        };
        timeout[0] = () -> finish.accept(false);
        detach[0] = new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) { finish.accept(false); }
        };
        root.addOnAttachStateChangeListener(detach[0]);
        root.postDelayed(timeout[0], 1000L); // Failure deadline, never an animation delay.
        try {
            root.getViewTreeObserver().registerFrameCommitCallback(() -> root.post(() -> finish.accept(true)));
            root.invalidate();
        } catch (Throwable error) { finish.accept(false); }
    }
}
