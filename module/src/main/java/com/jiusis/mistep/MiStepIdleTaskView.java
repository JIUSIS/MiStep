package com.jiusis.mistep;

import java.util.ArrayList;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Retire only exact registrations for vanished tasks of an idle owned controller. */
final class MiStepIdleTaskView {
    static void reset(Object controller, Consumer<String> log) {
        Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
        synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
            if (XposedHelpers.getObjectField(controller, "mTaskToken") != null
                    || XposedHelpers.callMethod(controller, "getPendingInfo") != null) return;
            Object listeners = XposedHelpers.getObjectField(organizer, "mTaskListeners");
            Object tasks = XposedHelpers.getObjectField(organizer, "mTasks");
            ArrayList<Integer> retired = new ArrayList<>();
            int count = (Integer) XposedHelpers.callMethod(listeners, "size");
            for (int i = 0; i < count; i++) {
                if (XposedHelpers.callMethod(listeners, "valueAt", i) != controller) continue;
                int id = (Integer) XposedHelpers.callMethod(listeners, "keyAt", i);
                if (id < 0 || XposedHelpers.callMethod(tasks, "get", id) != null)
                    throw new IllegalStateException("idle controller retains live registration=" + id);
                retired.add(id);
            }
            // Validate all entries before changing any; preserve the original global organizer.
            for (int id : retired) XposedHelpers.callMethod(listeners, "remove", id);
            boolean notFound = XposedHelpers.getBooleanField(controller, "mTaskNotFound");
            XposedHelpers.callMethod(controller, "resetTaskInfo");
            log.accept("reset idle TaskView notFound=" + notFound + " retired=" + retired);
        }
    }
}
