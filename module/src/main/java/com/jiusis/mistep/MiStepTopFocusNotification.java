package com.jiusis.mistep;

import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XposedHelpers;

/** Publish real TOP focus changes to native task listeners, including Launcher's front cache. */
final class MiStepTopFocusNotification {
    private Object display;
    private Key front;
    private boolean publishedTop, publishing, pending;
    private long generation;

    private record Key(Object token, int taskId, int userId) {}

    synchronized void clear() {
        display = null;
        front = null;
        publishedTop = false;
        generation++;
    }

    /** Observe native notifications too, so a real reorder is never announced twice. */
    synchronized void observed(Object currentDisplay, Object info) {
        if (currentDisplay == null || info == null) return;
        if (display != currentDisplay) { clear(); display = currentDisplay; }
        Key key = key(info);
        if (key != null) front = key;
    }

    /** Called after the native window-focus update, while its WM lock is still held. */
    synchronized boolean reconcile(Object currentDisplay, Predicate<Object> ownsTop,
            BooleanSupplier active) {
        if (publishing) { pending = true; return false; }
        if (currentDisplay == null) return false;
        if (display != currentDisplay) { clear(); display = currentDisplay; }
        boolean changed = false;
        // Native local listeners run synchronously and may change focus during the broadcast.
        // Re-read once after a reentrant update; never loop indefinitely under the WM lock.
        for (int attempt = 0; attempt < 2 && display == currentDisplay; attempt++) {
            pending = false;
            changed |= reconcileOnce(ownsTop, active);
            if (!pending) break;
        }
        return changed;
    }

    private boolean reconcileOnce(Predicate<Object> ownsTop, BooleanSupplier active) {
        Object window = XposedHelpers.getObjectField(display, "mCurrentFocus");
        Object app = XposedHelpers.getObjectField(display, "mFocusedApp");
        if (window == null || app == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display
                || XposedHelpers.callMethod(app, "getDisplayContent") != display) return false;
        Object task = XposedHelpers.callMethod(window, "getTask");
        if (task == null || XposedHelpers.callMethod(app, "getTask") != task
                || XposedHelpers.callMethod(task, "getDisplayContent") != display
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisible"))) return false;
        boolean top = active.getAsBoolean()
                && MiStepImePolicy.isFocusedTopTask(task, display, ownsTop);
        // A passive side task, IME or transient mismatched focus cannot update the cache.
        if (!top && (!publishedTop || Integer.valueOf(6).equals(
                XposedHelpers.callMethod(task, "getWindowingMode")))) return false;
        Object info = XposedHelpers.callMethod(task, "getTaskInfo");
        Key next = key(info);
        if (next == null || top && !ownsTop.test(task)) return false;
        if (next.equals(front)) { publishedTop = top; return false; }
        Object atms = XposedHelpers.getObjectField(task, "mAtmService");
        Object controller = XposedHelpers.callMethod(atms, "getTaskChangeNotificationController");
        long epoch = generation;
        publishing = true;
        try {
            XposedHelpers.callMethod(controller, "notifyTaskMovedToFront", info);
            if (epoch == generation) {
                front = next;
                publishedTop = top;
            }
            return true;
        } finally { publishing = false; }
    }

    private static Key key(Object info) {
        if (info == null || XposedHelpers.getIntField(info, "displayId") != 0) return null;
        int id = XposedHelpers.getIntField(info, "taskId");
        int user = XposedHelpers.getIntField(info, "userId");
        Object token = XposedHelpers.getObjectField(info, "token");
        Object binder = token == null ? null : XposedHelpers.callMethod(token, "asBinder");
        return binder == null || id < 0 || user < 0 ? null : new Key(binder, id, user);
    }
}
