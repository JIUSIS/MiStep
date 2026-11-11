package com.jiusis.mistep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.WindowManager;
import java.util.Map;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** The native hidden WindowState, rather than a client buffer callback, owns reveal. */
final class MiStepRetainedWindow {
    static final String ACTION = "com.jiusis.mistep.RETAINED_WINDOW";
    private Object window;
    private long generation;
    private float scale, x, y;
    private final java.util.Map<Object, Float> heldMain = new java.util.IdentityHashMap<>();
    private long heldUntil;
    private Handler wmHandler;

    interface Reveal { void apply(Object transaction, long generation, float scale, float x, float y) throws Throwable; }

    Bundle retain(Object display, Intent intent) {
        Bundle state = intent.getBundleExtra("state");
        IBinder token = state == null ? null : state.getBinder("host");
        if (display == null || token == null) return null;
        Object service = XposedHelpers.getObjectField(display, "mWmService");
        Object candidate = ((Map<?, ?>) XposedHelpers.getObjectField(service, "mWindowMap")).get(token);
        if (!owns(candidate, display)) {
            try { RuntimeDiagnosticEvents.event("RETAIN_REJECT generation="+intent.getLongExtra("generation",0)
                        +" reason=window_identity candidate="+candidate
                        +" ownerUid="+(candidate==null?"NONE":XposedHelpers.getIntField(candidate,"mOwnerUid"))
                        +" expectedOwner=registered_SystemUI_package_and_native_session");
            } catch(Throwable ignored) { /* Diagnostics never changes the ownership decision. */ }
            return null;
        }
        long next = intent.getLongExtra("generation", 0);
        if (next <= 0 || next < generation) return null;
        if (intent.getBooleanExtra("cancel", false)) {
            if (candidate == window) {
                releaseMain(XposedHelpers.callMethod(display, "getPendingTransaction"));
                window = null; generation = next;
            }
            return null;
        }
        float s = intent.getFloatExtra("scale", Float.NaN);
        float ox = intent.getFloatExtra("x", Float.NaN), oy = intent.getFloatExtra("y", Float.NaN);
        if (!Float.isFinite(s) || s <= 0 || s >= 1 || !Float.isFinite(ox) || !Float.isFinite(oy)) return null;
        // Never authorize warming from goingAway or an occluded-but-on-screen window.
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(candidate, "isOnScreen"))) return null;
        if (!heldMain.isEmpty()) releaseMain(XposedHelpers.callMethod(display, "getPendingTransaction"));
        window = candidate; generation = next; scale = s; x = ox; y = oy;
        // Native keyguard already hides the Host. Set the canonical main windows
        // before its start transaction can reveal newly recreated client buffers.
        for (Object main : ((Map<?, ?>) XposedHelpers.getObjectField(service, "mWindowMap")).values())
            holdOpeningMain(main, display, true);
        XposedHelpers.callMethod(display, "scheduleAnimation");
        Bundle result = new Bundle(); result.putLong("generation", next); return result;
    }

    static boolean owns(Object window, Object display) {
        return MiStepWindowIdentity.ownsHost(window, display);
    }

    void install(ClassLoader loader, java.util.function.Supplier<Object> display,
            java.util.function.BooleanSupplier authenticated, Reveal reveal) {
        Class<?> nativeWindow = XposedHelpers.findClass("com.android.server.wm.WindowState", loader);
        XposedBridge.hookAllMethods(nativeWindow, "prepareSurfaces", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (window == null || p.hasThrowable() || p.args.length != 0) return;
                try {
                    Object current = p.thisObject;
                    if (current != window) {
                        holdOpeningMain(current, display.get(), false);
                        return;
                    }
                    if (current != window || !owns(current, display.get())) return;
                    Object animator = XposedHelpers.getObjectField(current, "mWinAnimator");
                    if (!surfaceShown(animator)
                            || XposedHelpers.getIntField(animator, "mDrawState") != 4
                            || !Boolean.TRUE.equals(XposedHelpers.callMethod(current, "isOnScreen"))
                            || !authenticated.getAsBoolean()) return;
                    Object transaction = XposedHelpers.callMethod(current, "getPendingTransaction");
                    reveal.apply(transaction, generation, scale, x, y);
                    releaseMain(transaction);
                    window = null;
                    XposedBridge.log("MiStepProbe: authenticated native Host visibility transaction restored workspace");
                } catch (Throwable error) {
                    try { releaseMain(XposedHelpers.callMethod(p.thisObject, "getPendingTransaction")); }
                    catch (Throwable ignored) {}
                    window = null; // Never retry a failed native visibility transaction.
                    XposedBridge.log("MiStepProbe: retained native visibility handoff stopped: " + error);
                }
            }
        });
    }

    static boolean surfaceShown(Object animator) {
        if (ShellCompat.hasMethod(animator.getClass(), "getShown", 0))
            return Boolean.TRUE.equals(XposedHelpers.callMethod(animator, "getShown"));
        return XposedHelpers.getBooleanField(animator, "mSurfaceShown");
    }

    void setHandler(Handler handler) { wmHandler = handler; }

    /** Native keyguard has already chosen these opening windows. Only postpone
     * their visibility; never show a task or touch the physical lockscreen. */
    private void holdOpeningMain(Object current, Object display, boolean prime) {
        if (wmHandler == null || XposedHelpers.callMethod(current, "getDisplayContent") != display
                || XposedHelpers.getObjectField(current, "mActivityRecord") == null
                || ((Number) XposedHelpers.callMethod(current, "getWindowingMode")).intValue() != 1
                || (!prime && !Boolean.TRUE.equals(XposedHelpers.callMethod(current, "isOnScreen")))) return;
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams) XposedHelpers.getObjectField(current, "mAttrs");
        if (attrs.type < 1 || attrs.type > 3
                || (attrs.flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0
                || Boolean.TRUE.equals(XposedHelpers.callMethod(
                        XposedHelpers.getObjectField(current, "mActivityRecord"), "canShowWhenLocked"))) return;
        Object animator = XposedHelpers.getObjectField(current, "mWinAnimator");
        if (!prime && !surfaceShown(animator)) return;
        Object surface = XposedHelpers.callMethod(current, "getSurfaceControl");
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return;
        Object transaction = XposedHelpers.callMethod(current, "getPendingTransaction");
        if (!prime && heldUntil == 0) {
            heldUntil = android.os.SystemClock.uptimeMillis() + 1500L;
            final Object owner = window;
            final long lease = generation;
            wmHandler.postDelayed(() -> {
                Object service = XposedHelpers.getObjectField(display, "mWmService");
                synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                    if (owner != window || lease != generation) return;
                    Object pending = XposedHelpers.callMethod(display, "getPendingTransaction");
                    releaseMain(pending); window = null;
                    XposedHelpers.callMethod(display, "scheduleAnimation");
                    XposedBridge.log("MiStepProbe: retained opening-main watchdog returned native surfaces");
                }
            }, 1500L);
        }
        if (heldUntil != 0 && android.os.SystemClock.uptimeMillis() >= heldUntil) { releaseMain(transaction); window = null; return; }
        if (!heldMain.containsKey(surface)) {
            heldMain.put(surface, ((Number) XposedHelpers.getObjectField(animator, "mAlpha")).floatValue());
            XposedBridge.log("MiStepProbe: native opening main held until authenticated Host visibility");
        }
        XposedHelpers.callMethod(transaction, "setAlpha", surface, 0f);
    }

    private void releaseMain(Object transaction) {
        for (java.util.Map.Entry<Object, Float> entry : heldMain.entrySet()) {
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(entry.getKey(), "isValid")))
                XposedHelpers.callMethod(transaction, "setAlpha", entry.getKey(), entry.getValue());
        }
        heldMain.clear(); heldUntil = 0;
    }

    static void request(Context context, Handler handler, IBinder token, long generation,
            float scale, float x, float y, Consumer<Boolean> ready) {
        Bundle state = new Bundle(); state.putBinder("host", token);
        try {
            context.sendOrderedBroadcast(new Intent(ACTION).setPackage("android").putExtra("state", state)
                    .putExtra("generation", generation).putExtra("scale", scale).putExtra("x", x).putExtra("y", y),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS, new BroadcastReceiver() {
                        @Override public void onReceive(Context ignored, Intent intent) {
                            Bundle result = getResultExtras(false);
                            ready.accept(getResultCode() == -1 && result != null
                                    && result.getLong("generation", -1) == generation);
                        }
                    }, handler, 0, null, null);
        } catch (Throwable error) { ready.accept(false); }
    }
}
