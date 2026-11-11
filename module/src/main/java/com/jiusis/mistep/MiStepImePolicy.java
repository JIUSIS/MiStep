package com.jiusis.mistep;

import android.graphics.Rect;
import java.util.function.Predicate;
import de.robv.android.xposed.XposedHelpers;

/** Reuse the host's renewable lease; never change an IME preference. */
final class MiStepImePolicy {
    private MiStepImePolicy() {}

    /** A passive side task is not an IME layer target; a touched TOP editor is. */
    static boolean isFocusedTopWindow(Object window, Object display) {
        return isFocusedTopWindow(window,display, task -> {
            Rect b=(Rect)XposedHelpers.callMethod(task,"getBounds");
            return b!=null && b.width()>b.height();
        });
    }

    static boolean isFocusedTopWindow(Object window, Object display, Predicate<Object> lease) {
        if (window == null || display == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return false;
        return isFocusedTopTask(XposedHelpers.callMethod(window, "getTask"), display, lease);
    }

    static boolean isFocusedTopTask(Object task, Object display) {
        return isFocusedTopTask(task,display,t -> {
            Rect b=(Rect)XposedHelpers.callMethod(t,"getBounds");
            return b!=null && b.width()>b.height();
        });
    }

    static boolean isFocusedTopTask(Object task, Object display, Predicate<Object> lease) {
        if (display == null || task == null || !Integer.valueOf(6).equals(
                XposedHelpers.callMethod(task, "getWindowingMode"))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isForceTranslucent"))) return false;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if (bounds == null || bounds.left != 0 || bounds.top != 0
                || bounds.height() <= 0 || bounds.width() <= 0 || !lease.test(task)) return false;
        Object focused = XposedHelpers.getObjectField(display, "mCurrentFocus");
        return focused != null
                && XposedHelpers.callMethod(focused, "getDisplayContent") == display
                && XposedHelpers.callMethod(focused, "getTask") == task
                && isTopAppFocused(task, display);
    }

    /** The old focused window can survive a native main-app focus request. */
    static boolean isTopAppFocused(Object task, Object display) {
        if (task == null || display == null) return false;
        Object app = XposedHelpers.getObjectField(display, "mFocusedApp");
        return app != null && XposedHelpers.callMethod(app, "getDisplayContent") == display
                && XposedHelpers.callMethod(app, "getTask") == task;
    }

    static boolean isInactiveTopWindow(Object window, Object display, Predicate<Object> topLease) {
        if (window == null || display == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return false;
        Object task = XposedHelpers.callMethod(window, "getTask");
        if (!MiStepSideRoleChange.isSide(task) || !topLease.test(task)) return false;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        return bounds != null && bounds.left == 0 && bounds.top == 0
                && bounds.height() > 0 && bounds.width() > 0
                && !isTopAppFocused(task, display);
    }

    /** Called at native tap-outside dispatch, never while scanning an old IME target. */
    static boolean admitTopTouch(Object window, Object display, Predicate<Object> topLease) {
        if (window == null || display == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return false;
        Object task = XposedHelpers.callMethod(window, "getTask");
        if (!MiStepSideRoleChange.isSide(task) || !topLease.test(task)) return false;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if (bounds == null || bounds.left != 0 || bounds.top != 0
                || bounds.height() <= 0 || bounds.width() <= 0) return false;
        Object activity = XposedHelpers.getObjectField(window, "mActivityRecord");
        if (activity == null || XposedHelpers.callMethod(activity, "getTask") != task
                || XposedHelpers.callMethod(activity, "getDisplayContent") != display
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "isFocusable"))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "isVisibleRequested"))) return false;
        return Boolean.TRUE.equals(XposedHelpers.callMethod(display, "setFocusedApp", activity));
    }

    /** Only leased portrait cells are passive; TOP must obtain focus before it has an editor. */
    static boolean isPassiveSideWindow(Object window, Object display, Predicate<Object> liveSideLease) {
        if (window == null || display == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return false;
        return isPassiveSideTask(XposedHelpers.callMethod(window, "getTask"), display, liveSideLease);
    }

    static boolean isPassiveSideTask(Object task, Object display, Predicate<Object> liveSideLease) {
        if (display == null || task == null
                || XposedHelpers.callMethod(task, "getDisplayContent") != display) return false;
        if (!Integer.valueOf(6).equals(
                XposedHelpers.callMethod(task, "getWindowingMode"))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isForceTranslucent"))) return false;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        return bounds != null && bounds.width() > 0 && bounds.height() > 0
                && liveSideLease.test(task);
    }

    /** A late side lease must reconcile native Activity focus before WM's Z-order cutoff. */
    static boolean reconcilePassiveAppFocus(Object display, Predicate<Object> liveSideLease) {
        Object focused = XposedHelpers.getObjectField(display, "mFocusedApp");
        if (focused == null || XposedHelpers.callMethod(focused, "getDisplayContent") != display
                || !isPassiveSideTask(XposedHelpers.callMethod(focused, "getTask"), display, liveSideLease)) return false;
        Object area = XposedHelpers.callMethod(focused, "getDisplayArea");
        Object root = area == null ? null : XposedHelpers.callMethod(area, "getFocusedRootTask");
        if (root == null || XposedHelpers.callMethod(root, "getDisplayContent") != display
                || isPassiveSideTask(root, display, liveSideLease)) return false;
        Object next = XposedHelpers.callMethod(root, "topRunningActivityLocked");
        if (next == null || next == focused || XposedHelpers.callMethod(next, "getDisplayContent") != display
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(next, "isFocusable"))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(next, "isVisibleRequested"))) return false;
        // This updates InputMonitor and task notifications through the ROM's own contract.
        return Boolean.TRUE.equals(XposedHelpers.callMethod(display, "setFocusedApp", next));
    }

    static boolean suppressAutomaticFloating(String encoded, long bootAt, long now) {
        ZoomChannelState lease = new ZoomChannelState(bootAt);
        if (!lease.offerEncoded(encoded, now)) return false;
        String[] parts = lease.current(now).command.split("\\s+");
        int scaleIndex = "anim".equals(parts[0]) ? 1 : 0;
        return Float.parseFloat(parts[scaleIndex]) < 1f;
    }
}
