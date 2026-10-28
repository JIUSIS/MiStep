package com.jiusis.mistep;

import de.robv.android.xposed.XposedHelpers;

/** Exact window-caption native return; logical task/snapshot bounds remain native. */
final class NativeFreeformReturn {
    private NativeFreeformReturn() {}

    static Object change(Object info) {
        if (info == null || !Integer.valueOf(11108).equals(XposedHelpers.callMethod(info, "getType"))
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getRootCount")))
            return null;
        Object root = XposedHelpers.callMethod(info, "getRoot", 0);
        if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(root, "getDisplayId"))) return null;
        Object nativeChange = null;
        for (Object change : (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges")) {
            if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(change, "getStartDisplayId"))
                    || !Integer.valueOf(0).equals(XposedHelpers.callMethod(change, "getEndDisplayId"))
                    || !XposedHelpers.callMethod(change, "getStartRotation")
                            .equals(XposedHelpers.callMethod(change, "getEndRotation"))) return null;
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null || XposedHelpers.getIntField(task, "displayId") != 0
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(task, "hasParentTask"))
                    || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task, "getWindowingMode")))
                return null;
            int mode = (Integer) XposedHelpers.callMethod(change, "getMode");
            int type = (Integer) XposedHelpers.callMethod(task, "getActivityType");
            if (mode == 6 && type == 1
                    && Integer.valueOf(5).equals(XposedHelpers.callMethod(change, "getStartWindowMode"))
                    && Integer.valueOf(1).equals(XposedHelpers.callMethod(change, "getEndWindowMode"))) {
                if (nativeChange != null) return null;
                nativeChange = change;
            } else if (mode != 4 || (type != 1 && type != 2)) return null;
        }
        return nativeChange;
    }

    static float initial(String property, float value, float[] main) {
        if (main == null || main.length != 3 || !(main[0] > 0f && main[0] < 1f)
                || !Float.isFinite(main[1]) || !Float.isFinite(main[2])) return value;
        if ("getCenterX".equals(property) || "getFolmeCenterX".equals(property))
            return (value - main[2]) / main[0];
        if ("getElegantY".equals(property)) return (value - main[1]) / main[0];
        return value / main[0];
    }
}
