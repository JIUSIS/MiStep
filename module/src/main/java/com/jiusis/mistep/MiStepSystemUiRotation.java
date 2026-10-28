package com.jiusis.mistep;

import java.util.IdentityHashMap;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Independent native system-window rotation inside the portrait workspace. */
final class MiStepSystemUiRotation {
    private static final class Lease {
        final Object state, mainState;
        Lease(Object state, Object mainState) { this.state = state; this.mainState = mainState; }
    }
    private final IdentityHashMap<Object, Lease> owned = new IdentityHashMap<>();
    private boolean updating;

    static boolean privacyWindow(int type, String pkg, CharSequence title) {
        return type == 2017 && "com.android.systemui".equals(pkg)
                && title != null && "FlaresView".contentEquals(title);
    }
    private static boolean imeWindow(Object window) {
        try {
            // MIUI/Android marks both the INPUT_METHOD root and visible child
            // panels (for example a floating candidate PopupWindow) as IME
            // windows. The root can be surface-less while the child is visible,
            // so relying on the window type alone leaves that child unrotated.
            if (XposedHelpers.getBooleanField(window, "mIsImWindow")) return true;
        } catch (Throwable ignored) { }
        try {
            Object parent = XposedHelpers.getObjectField(window, "mParentWindow");
            if (parent == null) return false;
            Object attrs = XposedHelpers.getObjectField(parent, "mAttrs");
            int type = XposedHelpers.getIntField(attrs, "type");
            return type == 2011 || type == 2012;
        } catch (Throwable ignored) { return false; }
    }
    private static boolean systemWindow(Object window) {
        Object attrs = XposedHelpers.getObjectField(window, "mAttrs");
        int type = XposedHelpers.getIntField(attrs, "type");
        if (type == 2011 || type == 2012 || imeWindow(window)) return true;
        if (!"com.android.systemui".equals(XposedHelpers.getObjectField(attrs, "packageName"))) return false;
        return type == 2000 || type == 2019 || type == 2040 || type == 2008 || type == 2014
                || type == 2017 || type == 2020 || type == 2024;
    }
    private static Object state(Object token) {
        return XposedHelpers.getObjectField(token, "mFixedRotationTransformState");
    }
    void update(Object window, Object display, Object main, Consumer<String> log) {
        if (updating || window == null || display == null || !systemWindow(window)
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return;
        Object token = XposedHelpers.getObjectField(window, "mToken");
        if (token == null) return;
        updating = true;
        try {
            Object mainState = null;
            if (main != null && XposedHelpers.callMethod(main, "getDisplayContent") == display
                    && !Boolean.TRUE.equals(XposedHelpers.callMethod(main, "isActivityTypeHome"))
                    && !Boolean.TRUE.equals(XposedHelpers.callMethod(main, "inMultiWindowMode"))) {
                mainState = state(main);
            }
            Object attrs = XposedHelpers.getObjectField(window, "mAttrs");
            int type = XposedHelpers.getIntField(attrs, "type");
            if (mainState != null && (type == 2011 || type == 2012)) {
                Object target = XposedHelpers.callMethod(display, "getImeInputTarget");
                Object targetWindow = target == null ? null : XposedHelpers.callMethod(target, "getWindowState");
                Object task = targetWindow == null ? null : XposedHelpers.callMethod(targetWindow, "getTask");
                if (task != null && !Integer.valueOf(1).equals(XposedHelpers.callMethod(task, "getWindowingMode")))
                    mainState = null;
            }
            Lease lease = owned.get(token);
            if (lease != null && state(token) != lease.state) {
                // A native owner replaced our state; never finish that new owner's transform.
                owned.remove(token); lease = null;
            }
            if (mainState == null) {
                release(token); return;
            }
            if (lease != null && lease.mainState == mainState) return;
            if (lease == null && state(token) != null) return;
            if (lease != null) release(token);
            Object info = XposedHelpers.callMethod(main, "getFixedRotationTransformDisplayInfo");
            Object frames = XposedHelpers.callMethod(main, "getFixedRotationTransformDisplayFrames");
            if (info == null || frames == null || XposedHelpers.callMethod(token, "getParent") == null) return;
            Object config = XposedHelpers.getObjectField(mainState, "mRotatedOverrideConfiguration");
            // Create an INDEPENDENT native state. linkFixedRotationTransform shares the
            // finish lifecycle and could let a disappearing IME finish the app's transform.
            XposedHelpers.callMethod(token, "applyFixedRotationTransform", info, frames, config);
            Object installed = state(token);
            if (installed == null || installed == mainState)
                throw new IllegalStateException("system UI rotation did not acquire an independent native state");
            owned.put(token, new Lease(installed, mainState));
            // Legacy WM rotates/positions the token itself in updateSurfacePosition.
            // Its optional position leash would add that same translation a second time.
            Object controller = XposedHelpers.getObjectField(token, "mTransitionController");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(controller, "useShellTransitionsRotation")))
                XposedHelpers.callMethod(token, "getOrCreateFixedRotationLeash",
                        XposedHelpers.callMethod(token, "getPendingTransaction"));
            log.accept("main system UI rotation configured token=" + token);
        } finally { updating = false; }
    }
    private void release(Object token) {
        Lease lease = owned.get(token);
        if (lease == null) return;
        if (state(token) == lease.state) XposedHelpers.callMethod(token, "finishFixedRotationTransform");
        owned.remove(token);
    }
    void clear(Consumer<String> log) {
        for (Object token : owned.keySet().toArray()) {
            try { release(token); }
            catch (Throwable error) { log.accept("system UI rotation release deferred: " + error); }
        }
    }

    /** A permission window can be created after WM distributed the current zoom spec. */
    static boolean magnifyPrivacy(Object window, Object display, Object spec, Object transaction) {
        if (window == null || display == null || spec == null || transaction == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return false;
        Object attrs = XposedHelpers.getObjectField(window, "mAttrs");
        if (!privacyWindow(XposedHelpers.getIntField(attrs, "type"),
                (String) XposedHelpers.getObjectField(attrs, "packageName"),
                (CharSequence) XposedHelpers.callMethod(attrs, "getTitle"))) return false;
        Object token = XposedHelpers.getObjectField(window, "mToken");
        if (token == null || XposedHelpers.callMethod(token, "getParent") == null) return false;
        for (Object ancestor = XposedHelpers.callMethod(token, "getParent"); ancestor != null;
                ancestor = XposedHelpers.callMethod(ancestor, "getParent")) {
            if (XposedHelpers.getObjectField(ancestor, "mLastMagnificationSpec") != null) return false;
        }
        // Prefer the native tokens display-area outside a fixed-rotation leash.
        // Never redistribute magnification through app TaskDisplayAreas during a live swap.
        Object parent = XposedHelpers.callMethod(token, "getParent");
        Object container = parent.getClass().getName().equals("com.android.server.wm.DisplayArea$Tokens")
                && Boolean.TRUE.equals(XposedHelpers.callMethod(parent, "shouldMagnify")) ? parent : token;
        XposedHelpers.callMethod(container, "applyMagnificationSpec", transaction, spec);
        return true;
    }
}
