package com.jiusis.mistep;

import android.os.Handler;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Return the OEM bar-follow owner after our TOP task returns without a Shell transition.
 * All entry points run under the owning WMS global lock. Insets visibility stays native. */
final class MiStepTopSystemBars {
    private static final class Visit {
        final Object display;
        long returnedAt;
        boolean queued;
        Visit(Object display) { this.display = display; }
    }
    private final WeakHashMap<Object, Visit> visits = new WeakHashMap<>();
    private WeakReference<Object> controller = new WeakReference<>(null);

    /** Called only after the exact TOP viewport lease produced a separate InsetsState. */
    void remember(Object window, Object display) {
        Object task = XposedHelpers.callMethod(window, "getTask");
        if (task == null || XposedHelpers.callMethod(task, "getDisplayContent") != display
                || !MiStepSideRoleChange.isSide(task)) return;
        Visit old = visits.get(task);
        if (old == null || old.display != display || old.returnedAt != 0)
            visits.put(task, new Visit(display));
    }

    void changed(Object nativeController, Object task, Object display, Object lock,
            BooleanSupplier active, Consumer<String> log) {
        Visit visit = visits.get(task);
        if (visit == null || nativeController == null) return;
        controller = new WeakReference<>(nativeController);
        try {
            if (!active.getAsBoolean() || visit.display != display
                    || XposedHelpers.callMethod(task, "getDisplayContent") != display) {
                visits.remove(task); return;
            }
            int mode = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            if (mode == 6) return;
            if (mode != 1) { visits.remove(task); return; }
            long now = SystemClock.uptimeMillis();
            if (visit.returnedAt == 0) visit.returnedAt = Math.max(1L, now);
            if (now - visit.returnedAt > 5000L) { visits.remove(task); return; }
            if (visit.queued || !ready(nativeController, task, display)) return;
            visit.queued = true;
            Handler handler = (Handler) XposedHelpers.getObjectField(nativeController, "mHandler");
            if (!handler.post(() -> {
                synchronized (lock) {
                    if (visits.get(task) != visit) return;
                    visit.queued = false;
                    try {
                        if (!active.getAsBoolean()
                                || SystemClock.uptimeMillis() - visit.returnedAt > 5000L) {
                            visits.remove(task); return;
                        }
                        if (!ready(nativeController, task, display)) return;
                        // The ROM reparents its app bar and animates the Home/app pair.
                        // Its Insets animation leash still obeys the app's own immersive request.
                        XposedHelpers.callMethod(nativeController, "updateStatusBarAnotherApp");
                        visits.remove(task);
                        log.accept("upper native system bar owner reconciled after return");
                    } catch (Throwable unavailable) {
                        visits.remove(task);
                        log.accept("upper native system bar return unavailable: " + unavailable);
                    }
                }
            })) visit.queued = false;
        } catch (Throwable unavailable) {
            visits.remove(task);
            log.accept("upper native system bar return unavailable: " + unavailable);
        }
    }

    void focused(Object display, Object lock, BooleanSupplier active, Consumer<String> log) {
        if (visits.isEmpty()) return;
        Object nativeController = controller.get();
        if (nativeController == null) return;
        changed(nativeController, XposedHelpers.getObjectField(nativeController, "mCurrentTopTask"),
                display, lock, active, log);
    }

    private boolean ready(Object nativeController, Object task, Object display) {
        if (XposedHelpers.getObjectField(nativeController, "mDisplayContent") != display
                || XposedHelpers.callMethod(task, "getDisplayContent") != display
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task, "getWindowingMode"))
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task, "getActivityType"))
                || XposedHelpers.callMethod(task, "getRootTask") != task
                || Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isForceTranslucent"))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisible"))
                || XposedHelpers.getObjectField(nativeController, "mCurrentTopTask") != task
                || !Integer.valueOf(-1).equals(XposedHelpers.callMethod(nativeController, "getBarState"))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(nativeController, "isTaskBarReady"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(nativeController, "isInSplitScreen"))) return false;
        Object focusedApp = XposedHelpers.getObjectField(display, "mFocusedApp");
        Object focusedWindow = XposedHelpers.getObjectField(display, "mCurrentFocus");
        return focusedApp != null && focusedWindow != null
                && XposedHelpers.callMethod(focusedApp, "getTask") == task
                && XposedHelpers.callMethod(focusedWindow, "getTask") == task;
    }

    void clear() { visits.clear(); controller.clear(); }
}
