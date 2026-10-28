package com.jiusis.mistep;

import de.robv.android.xposed.XposedHelpers;

/** Relinquish our exact side binding after the OEM has converted it to freeform. */
final class MiStepNativeSideRelease {
    static boolean release(Object controller, Object transitions, int taskId, Object token) {
        if (controller == null || transitions == null || taskId < 0 || token == null) return false;
        Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
        synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
            Object tasks = XposedHelpers.getObjectField(organizer, "mTasks");
            Object appeared = XposedHelpers.callMethod(tasks, "get", taskId);
            Object info = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
            if (info == null || XposedHelpers.getIntField(info, "taskId") != taskId
                    || !token.equals(XposedHelpers.getObjectField(info, "token"))
                    || XposedHelpers.getIntField(info, "displayId") != 0
                    || !Integer.valueOf(5).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))) return false;
            Object listeners = XposedHelpers.getObjectField(organizer, "mTaskListeners");
            Object fallback = XposedHelpers.callMethod(listeners, "get", -5);
            Object assigned = XposedHelpers.callMethod(organizer, "getTaskListener", info, false);
            Object current = XposedHelpers.getObjectField(controller, "mTaskToken");
            if (fallback == null || fallback == controller) return false;
            if (current == null) return assigned == fallback;
            if (!token.equals(current) || (assigned != controller && assigned != fallback)) return false;
            int size = (Integer) XposedHelpers.callMethod(listeners, "size");
            for (int i = 0; i < size; i++) {
                if (XposedHelpers.callMethod(listeners, "valueAt", i) == controller
                        && !Integer.valueOf(taskId).equals(XposedHelpers.callMethod(listeners, "keyAt", i)))
                    return false;
            }
            Object canonical = XposedHelpers.callMethod(appeared, "getLeash");
            Object copy = XposedHelpers.getObjectField(controller, "mTaskLeash");
            if (canonical == null || canonical == copy
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(canonical, "isValid"))) return false;
            // The native freeform transition already owns geometry. No WCT, reparent,
            // matrix, crop, hide or task removal belongs in this listener handoff.
            XposedHelpers.callMethod(transitions, "unregisterTaskView", controller);
            try {
                if (assigned == controller) XposedHelpers.callMethod(organizer, "removeListener", controller);
                if (XposedHelpers.callMethod(organizer, "getTaskListener", info, false) != fallback)
                    throw new IllegalStateException("native freeform listener did not take ownership");
                XposedHelpers.callMethod(controller, "onTaskVanished", info);
                return XposedHelpers.getObjectField(controller, "mTaskToken") == null;
            } finally {
                XposedHelpers.callMethod(transitions, "registerTaskView", controller);
            }
        }
    }
}
