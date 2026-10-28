package com.jiusis.mistep;

import de.robv.android.xposed.XposedHelpers;

/** The pre-transition cover initializer must not move the already-visible main task. */
final class NativeReturnFollow {
    private NativeReturnFollow() {}

    static boolean source(int type, int startArea, int endArea, int sourceId, Object taskInfo) {
        return type == 11108 && startArea == 3 && endArea == 0
                && eligible(taskInfo, 5) && XposedHelpers.getIntField(taskInfo, "taskId") == sourceId;
    }

    static boolean retainBackground(Object taskInfo, Object state, Object positionGroup) {
        if (!eligible(taskInfo, 1) || state == null || positionGroup == null
                || !Integer.valueOf(0).equals(XposedHelpers.callMethod(state, "getAnimationType"))) return false;
        java.util.Map<?, ?> properties = (java.util.Map<?, ?>) XposedHelpers.callMethod(state, "getFolmeProperty");
        return properties.remove(positionGroup) != null;
    }

    private static boolean eligible(Object info, int mode) {
        if (info == null || XposedHelpers.getIntField(info, "displayId") != 0
                || !Boolean.TRUE.equals(XposedHelpers.getObjectField(info, "isVisible"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                || !Integer.valueOf(mode).equals(XposedHelpers.callMethod(info, "getWindowingMode"))) return false;
        int type = (Integer) XposedHelpers.callMethod(info, "getActivityType");
        return type == 1 || (mode == 1 && type == 2);
    }
}
