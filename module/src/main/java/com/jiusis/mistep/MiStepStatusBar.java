package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Message;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewTreeObserver;
import android.view.inspector.WindowInspector;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Only status-bar drawing in this SystemUI process; preserves alpha, visibility and insets. */
final class MiStepStatusBar {
    private static MiStepStatusBar current;
    private static boolean nativeStateKnown;
    private static int nativeWindowState;
    private final Handler main;
    private final Consumer<String> log;
    private final SwapStatusBarLease lease;
    private final Map<View, Fade> fades = new IdentityHashMap<>();
    private final Map<View, Float> nativeHidden = new IdentityHashMap<>();
    private final Consumer<List<View>> nativeRoots = this::includeNativeHiddenRoots;
    private boolean observingNativeRoots;
    private boolean immediateRestore;
    private Object activeOwner;
    private Object hiddenOwner;

    static void install(ClassLoader loader) {
        try {
            // Observe the same main-thread command as the OEM callbacks. Never change
            // native insets visibility, control targets or the application's animation.
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.CommandQueue$H", loader,
                    "handleMessage", Message.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            Message message = (Message) p.args[0];
                            if ((message.what & 0xffff0000) == 786432
                                    && message.obj instanceof Integer state)
                                nativeWindowStateChanged(message.arg1, message.arg2, state);
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("MiStep: native status window observer unavailable: " + error);
        }
    }

    static void nativeWindowStateChanged(int display, int window, int state) {
        if (display != Display.DEFAULT_DISPLAY || window != 1 || state < 0 || state > 2) return;
        nativeStateKnown = true;
        nativeWindowState = state;
        MiStepStatusBar bar = current;
        if (bar == null) return;
        bar.log.accept("native status window state=" + state);
        if (bar.lease.owns(bar.activeOwner)) return;
        if (state == 0) {
            for (View view : new ArrayList<>(bar.nativeHidden.keySet()))
                bar.restoreBar(view, bar.nativeHidden.get(view));
        } else {
            for (View view : new ArrayList<>(bar.fades.keySet())) {
                Fade fade = bar.fades.get(view);
                if (fade != null && fade.target != 0f) bar.holdNativeHidden(view, fade.target);
            }
        }
    }

    private static final class Fade {
        final float target;
        final ValueAnimator animator;
        final List<Runnable> hidden = new ArrayList<>();
        Fade(float target, ValueAnimator animator) { this.target = target; this.animator = animator; }
    }

    MiStepStatusBar(Handler main, Consumer<String> log) {
        this.main = main;
        this.log = log;
        current = this;
        lease = new SwapStatusBarLease(new SwapStatusBarLease.Timer() {
            public void postDelayed(Runnable action, long delayMs) { main.postDelayed(action, delayMs); }
            public void remove(Runnable action) { main.removeCallbacks(action); }
        }, error -> log.accept("swap status bar: " + error));
    }

    void hideFor(Object ticket, Runnable expire) {
        activeOwner = ticket;
        hiddenOwner = null;
        Consumer<List<View>> listener = views -> include(ticket, views);
        lease.begin(ticket, () -> {
            try { WindowInspector.removeGlobalWindowViewsListener(listener); }
            catch (Throwable error) { log.accept("status bar listener removal: " + error); }
        }, expire);
        try {
            WindowInspector.addGlobalWindowViewsListener(main::post, listener);
        } catch (Throwable error) {
            log.accept("status bar listener unavailable; using attached windows: " + error);
        }
        try { include(ticket, WindowInspector.getGlobalWindowViews()); }
        catch (Throwable error) { log.accept("status bar window enumeration: " + error); }
    }

    private void include(Object ticket, List<View> views) {
        if (!lease.owns(ticket)) return;
        List<SwapStatusBarLease.Window> windows = new ArrayList<>();
        for (View view : views) {
            if (!(view.getLayoutParams() instanceof WindowManager.LayoutParams params)
                    || params.type != WindowManager.LayoutParams.TYPE_STATUS_BAR
                    || view.getDisplay() == null
                    || view.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) continue;
            windows.add(new SwapStatusBarLease.Window() {
                public Object identity() { return view; }
                public float transitionAlpha() {
                    Float pending = nativeHidden.get(view);
                    if (pending != null) return pending;
                    Fade current = fades.get(view);
                    // A new swap may interrupt the previous fade-in. Preserve the OEM
                    // destination rather than saving its temporary animation midpoint.
                    return current != null && current.target != 0f
                            ? current.target : view.getTransitionAlpha();
                }
                public void transitionAlpha(float value) {
                    if (value == 0f && hiddenOwner == ticket) {
                        // A rebuilt bar must stay hidden after the swap was admitted.
                        cancelFade(view);
                        view.setTransitionAlpha(0f);
                        view.invalidate();
                    } else if (value != 0f) restoreBar(view, value);
                    else animateBar(view, value);
                }
            });
        }
        lease.include(ticket, windows);
    }

    private void restoreBar(View view, float target) {
        if (!immediateRestore && target != 0f && nativeStateKnown && nativeWindowState != 0) {
            holdNativeHidden(view, target);
            return;
        }
        nativeHidden.remove(view);
        stopNativeRootObservationIfEmpty();
        animateBar(view, target);
    }

    private void holdNativeHidden(View view, float target) {
        nativeHidden.put(view, target);
        cancelFade(view);
        view.setTransitionAlpha(0f);
        view.invalidate();
        log.accept("native status reveal deferred while hidden");
        if (observingNativeRoots) return;
        observingNativeRoots = true;
        try { WindowInspector.addGlobalWindowViewsListener(main::post, nativeRoots); }
        catch (Throwable error) {
            observingNativeRoots = false;
            log.accept("native status root observer unavailable: " + error);
        }
    }

    private void includeNativeHiddenRoots(List<View> views) {
        if (!observingNativeRoots || lease.owns(activeOwner) || nativeWindowState == 0) return;
        for (View view : views) {
            if (nativeHidden.containsKey(view)
                    || !(view.getLayoutParams() instanceof WindowManager.LayoutParams params)
                    || params.type != WindowManager.LayoutParams.TYPE_STATUS_BAR
                    || view.getDisplay() == null
                    || view.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY) continue;
            holdNativeHidden(view, view.getTransitionAlpha());
        }
    }

    private void stopNativeRootObservationIfEmpty() {
        if (!nativeHidden.isEmpty() || !observingNativeRoots) return;
        observingNativeRoots = false;
        try { WindowInspector.removeGlobalWindowViewsListener(nativeRoots); }
        catch (Throwable error) { log.accept("native status root observer removal: " + error); }
    }

    private void cancelFade(View view) {
        Fade previous = fades.remove(view);
        if (previous != null) previous.animator.cancel();
    }

    private void animateBar(View view, float target) {
        float start = view.getTransitionAlpha();
        cancelFade(view);
        if (immediateRestore || !view.isAttachedToWindow() || start == target) {
            view.setTransitionAlpha(target);
            view.invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(start, target);
        Fade fade = new Fade(target, animator);
        fades.put(view, fade);
        long duration = target == 0f ? 120L : 180L;
        animator.setDuration(duration);
        animator.setInterpolator(new android.view.animation.LinearInterpolator());
        animator.addUpdateListener(value -> {
            if (fades.get(view) != fade) return;
            view.setTransitionAlpha((Float) value.getAnimatedValue());
            view.invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ignored) {
                if (fades.get(view) != fade) return;
                fades.remove(view);
                view.setTransitionAlpha(target);
                view.invalidate();
                for (Runnable ready : fade.hidden) ready.run();
                fade.hidden.clear();
            }
        });
        try {
            animator.start();
            log.accept("live status fade target=" + target + " duration=" + duration);
        } catch (Throwable error) {
            cancelFade(view);
            view.setTransitionAlpha(target);
            view.invalidate();
            log.accept("status bar fade unavailable: " + error);
        }
    }

    private void afterFadeOut(Object ticket, View view, Runnable action) {
        if (!lease.owns(ticket)) return;
        Fade fade = fades.get(view);
        if (fade == null && view.getTransitionAlpha() != 0f) {
            animateBar(view, 0f);
            fade = fades.get(view);
        }
        Runnable ready = () -> { if (lease.owns(ticket)) action.run(); };
        if (fade != null && fade.target == 0f) fade.hidden.add(ready);
        else ready.run();
    }

    private void admitHidden(Object ticket, Runnable action) {
        if (!lease.owns(ticket)) return;
        for (View view : WindowInspector.getGlobalWindowViews()) {
            if (view.getLayoutParams() instanceof WindowManager.LayoutParams params
                    && params.type == WindowManager.LayoutParams.TYPE_STATUS_BAR
                    && view.isAttachedToWindow() && view.getDisplay() != null
                    && view.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY
                    && view.getTransitionAlpha() != 0f) {
                // A new root may have joined while the old root's frame was pending.
                runAfterHiddenFrame(ticket, action);
                return;
            }
        }
        hiddenOwner = ticket;
        action.run();
    }

    /** Fade the real bar out before the existing hidden-draw gate permits the swap.
     *  Submission alone is not proof of presentation; no status-bar bitmap is used. */
    void runAfterHiddenFrame(Object ticket, Runnable action) {
        if (android.os.Looper.myLooper() != main.getLooper()) {
            main.post(() -> runAfterHiddenFrame(ticket, action));
            return;
        }
        if (!lease.owns(ticket)) return;
        List<View> bars = new ArrayList<>();
        try {
            for (View view : WindowInspector.getGlobalWindowViews()) {
                if (view.getLayoutParams() instanceof WindowManager.LayoutParams params
                        && params.type == WindowManager.LayoutParams.TYPE_STATUS_BAR
                        && view.isAttachedToWindow() && view.getDisplay() != null
                        && view.getDisplay().getDisplayId() == Display.DEFAULT_DISPLAY)
                    bars.add(view);
            }
        } catch (Throwable error) { log.accept("status bar frame enumeration: " + error); return; }
        include(ticket, bars);
        if (bars.isEmpty()) { admitHidden(ticket, action); return; }
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(bars.size());
        for (View view : bars) {
            afterFadeOut(ticket, view, () -> {
                if (!view.isAttachedToWindow() || !view.isHardwareAccelerated()) {
                    if (remaining.decrementAndGet() == 0) admitHidden(ticket, action);
                    return;
                }
            try {
                ViewTreeObserver observer = view.getViewTreeObserver();
                observer.registerFrameCommitCallback(() -> main.post(() -> {
                    if (remaining.decrementAndGet() != 0 || !lease.owns(ticket)) return;
                    // Give the submitted hidden frame a composition opportunity before
                    // reparenting the real tasks under the magnified status bar.
                    view.postOnAnimation(() -> {
                        admitHidden(ticket, action);
                    });
                }));
                view.invalidate();
            } catch (Throwable error) { log.accept("status bar hidden frame unavailable: " + error); }
            });
        }
    }

    boolean owns(Object ticket) { return lease.owns(ticket); }
    void finish(Object ticket) {
        if (activeOwner == ticket) activeOwner = null;
        if (hiddenOwner == ticket) hiddenOwner = null;
        lease.finish(ticket);
    }
    void restore() {
        // Exit/lock cancels visual work immediately, including a fade-in whose lease
        // has already finished. A late animator callback cannot write alpha again.
        immediateRestore = true;
        activeOwner = null;
        hiddenOwner = null;
        try {
            lease.restore();
            for (View view : new ArrayList<>(nativeHidden.keySet())) {
                cancelFade(view);
                view.setTransitionAlpha(nativeHidden.remove(view));
                view.invalidate();
            }
            stopNativeRootObservationIfEmpty();
            for (View view : new ArrayList<>(fades.keySet())) {
                Fade fade = fades.get(view);
                cancelFade(view);
                view.setTransitionAlpha(fade.target);
                view.invalidate();
            }
        } finally { immediateRestore = false; }
    }
}
