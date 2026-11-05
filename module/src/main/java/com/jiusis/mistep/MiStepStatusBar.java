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
import java.lang.ref.WeakReference;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Borrow real SystemUI overlays and native panels until the task roles have settled. */
final class MiStepStatusBar {
    private static MiStepStatusBar current;
    private static boolean nativeStateKnown;
    private static int nativeWindowState;
    private static boolean nativeTopAppHidesStatusBar;
    private static boolean nativeTransientStatusBar;
    private static boolean nativePanelExpanded;
    private static long cornerContext;
    private static WeakReference<Object> nativeControlCenter = new WeakReference<>(null);
    private static WeakReference<Object> nativeShadeContext = new WeakReference<>(null);
    private final Handler main;
    private final Consumer<String> log;
    private final SwapStatusBarLease lease;
    private final SwapPanelLease panels;
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
                            else if ((message.what & 0xffff0000) == (37 << 16)) {
                                boolean hidden=message.arg1!=0;
                                if(hidden!=nativeTopAppHidesStatusBar) {
                                    nativeTransientStatusBar=false;
                                    cornerContext++;
                                }
                                nativeTopAppHidesStatusBar=hidden;
                            } else if (((message.what & 0xffff0000) == (48 << 16)
                                    || (message.what & 0xffff0000) == (49 << 16)) && message.obj != null) {
                                try {
                                    int display=XposedHelpers.getIntField(message.obj,"argi1");
                                    int types=XposedHelpers.getIntField(message.obj,"argi2");
                                    if(display==Display.DEFAULT_DISPLAY && (types & 1)!=0)
                                        nativeTransientStatusBar=(message.what & 0xffff0000)==(48 << 16);
                                } catch(Throwable unsupported) {nativeTransientStatusBar=false;}
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log("MiStep: native status window observer unavailable: " + error);
        }
        try {
            XposedHelpers.findAndHookMethod("com.android.systemui.shade.NotificationShadeWindowControllerImpl",loader,
                    "onShadeOrQsExpanded",Boolean.class,new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            boolean expanded=Boolean.TRUE.equals(p.args[0]);
                            if(expanded!=nativePanelExpanded)cornerContext++;
                            nativePanelExpanded=expanded;
                            try { nativeShadeContext = new WeakReference<>(
                                    XposedHelpers.getObjectField(p.thisObject, "mContext")); }
                            catch (Throwable ignored) {}
                        }
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            nativePanelChanged();
                        }
                    });
        } catch(Throwable error) {
            XposedBridge.log("MiStep: native panel expansion observer unavailable: "+error);
        }
        try {
            XposedBridge.hookAllConstructors(XposedHelpers.findClass(
                    "com.miui.systemui.controlcenter.ControlCenterImpl", loader), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    nativeControlCenter = new WeakReference<>(p.thisObject);
                }
            });
            String delegate = "com.miui.systemui.controlcenter.container.ControlCenterExpandControllerDelegate";
            for (String method : new String[]{"onExpansionChanged", "onBlurRatioChanged", "onVisibleChanged"}) {
                XposedHelpers.findAndHookMethod(delegate, loader, method,
                        "onVisibleChanged".equals(method) ? boolean.class : float.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) { nativePanelChanged(); }
                });
            }
        } catch (Throwable error) {
            XposedBridge.log("MiStep: native control-center swap observer unavailable: " + error);
        }
    }

    private static void nativePanelChanged() {
        MiStepStatusBar bar = current;
        if (bar != null && bar.activeOwner != null) bar.main.post(bar.panels::changed);
    }

    private SwapPanelLease.Panel nativePanel() {
        Object center = nativeControlCenter.get();
        if (center != null) {
            SwapPanelLease.Panel panel = new SwapPanelLease.Panel() {
                private Object expandController() { return XposedHelpers.callMethod(center, "getExpandController"); }
                private Object read(String method) {
                    Object controller = expandController();
                    try { return controller.getClass().getMethod(method).invoke(controller); }
                    catch (ReflectiveOperationException error) { throw new IllegalStateException(method, error); }
                }
                private float value(String method) {
                    return ((Number) read(method)).floatValue();
                }
                public Object identity() { return center; }
                public boolean expanded() { return Boolean.TRUE.equals(XposedHelpers.callMethod(center, "getExpanded"))
                        || value("getExpansion") > 0f; }
                public void collapse() { XposedHelpers.callMethod(center, "collapse", true); }
                public boolean collapsed() { return !Boolean.TRUE.equals(XposedHelpers.callMethod(center, "getExpanded"))
                        && value("getExpansion") == 0f && value("getBlurRatio") == 0f
                        && Boolean.FALSE.equals(read("getVisible")); }
                public void expand() { XposedHelpers.callMethod(center, "expand", true); }
            };
            try { if (panel.expanded() || !nativePanelExpanded) return panel; }
            catch (Throwable error) { log.accept("native control-center state unavailable: " + error); return panel; }
        }
        Object context = nativeShadeContext.get();
        if (context == null || !nativePanelExpanded) return null;
        return new SwapPanelLease.Panel() {
            private Object service() { return XposedHelpers.callMethod(context, "getSystemService", "statusbar"); }
            public Object identity() { return context; }
            public boolean expanded() { return nativePanelExpanded; }
            public void collapse() { XposedHelpers.callMethod(service(), "collapsePanels"); }
            public boolean collapsed() { return !nativePanelExpanded; }
            public void expand() { XposedHelpers.callMethod(service(), "expandNotificationsPanel"); }
        };
    }

    /** A separate corner swipe is allowed only while the revealed status bar remains shown. */
    static boolean allowsCornerGesture(int width, int height) {
        if(width<=0 || height<=0 || nativePanelExpanded
                || nativeStateKnown && nativeWindowState!=0)return false;
        if(nativeTransientStatusBar)return nativeStateKnown && nativeWindowState==0;
        return height>width && !nativeTopAppHidesStatusBar;
    }

    static long cornerGestureContext() { return cornerContext; }

    /** The bar may retract during the same admitted swipe; a new DOWN still needs it. */
    static boolean keepsCornerGesture(long admittedContext) {
        return admittedContext==cornerContext && !nativePanelExpanded;
    }

    static String cornerStateForTrace() {
        return "known="+nativeStateKnown+" window="+nativeWindowState
                +" fullscreen="+nativeTopAppHidesStatusBar+" transient="+nativeTransientStatusBar
                +" panel="+nativePanelExpanded+" context="+cornerContext;
    }

    static void nativeWindowStateChanged(int display, int window, int state) {
        if (display != Display.DEFAULT_DISPLAY || window != 1 || state < 0 || state > 2) return;
        nativeStateKnown = true;
        nativeWindowState = state;
        if(state!=0)nativeTransientStatusBar=false;
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
                if (fade != null && fade.target != 0f && isNativeStatusBar(view))
                    bar.holdNativeHidden(view, fade.target);
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
        panels = new SwapPanelLease(error -> log.accept("swap native panel: " + error));
    }

    void hideFor(Object ticket, Runnable expire) {
        activeOwner = ticket;
        hiddenOwner = null;
        Consumer<List<View>> listener = views -> include(ticket, views);
        lease.begin(ticket, () -> {
            try { WindowRootsCompat.remove(listener); }
            catch (Throwable error) { log.accept("status bar listener removal: " + error); }
        }, () -> { try { expire.run(); } finally { finish(ticket); } });
        panels.begin(ticket, nativePanel());
        try {
            WindowRootsCompat.add(main::post, listener);
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
            if (!isSwapOverlay(view)) continue;
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
                    if (value == 0f && (hiddenOwner == ticket || !isDrawnSwapOverlay(view))) {
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

    private static boolean isNativeStatusBar(View view) {
        return view.getLayoutParams() instanceof WindowManager.LayoutParams params
                && params.type == WindowManager.LayoutParams.TYPE_STATUS_BAR;
    }

    private static boolean isSwapOverlay(View view) {
        if (view.getDisplay() == null || view.getDisplay().getDisplayId() != Display.DEFAULT_DISPLAY
                || !(view.getLayoutParams() instanceof WindowManager.LayoutParams params)) return false;
        return isNativeStatusBar(view) || params.type == 2009
                && "DynamicIslandWindow".contentEquals(params.getTitle());
    }

    private static boolean isDrawnSwapOverlay(View view) {
        return isSwapOverlay(view) && view.isAttachedToWindow() && view.isShown()
                && view.getWindowVisibility() == View.VISIBLE;
    }

    private void restoreBar(View view, float target) {
        if (!immediateRestore && target != 0f && isNativeStatusBar(view)
                && nativeStateKnown && nativeWindowState != 0) {
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
        try { WindowRootsCompat.add(main::post, nativeRoots); }
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
        try { WindowRootsCompat.remove(nativeRoots); }
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
            if (isDrawnSwapOverlay(view) && view.getTransitionAlpha() != 0f) {
                // A new root may have joined while the old root's frame was pending.
                runAfterHiddenFrame(ticket, action);
                return;
            }
        }
        hiddenOwner = ticket;
        panels.whenCollapsed(ticket, action);
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
                if (isDrawnSwapOverlay(view))
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
        boolean owned = lease.owns(ticket);
        if (activeOwner == ticket) activeOwner = null;
        if (hiddenOwner == ticket) hiddenOwner = null;
        lease.finish(ticket);
        // A completed swap leaves the native shade/control center collapsed.
        // Drawing leases (including the island) still restore independently above.
        if (owned) panels.finish(ticket, false);
    }
    void restore() {
        // Exit/lock cancels visual work immediately, including a fade-in whose lease
        // has already finished. A late animator callback cannot write alpha again.
        immediateRestore = true;
        panels.cancel(); // Exit/lock must never reopen an expanded panel over the new scene.
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
