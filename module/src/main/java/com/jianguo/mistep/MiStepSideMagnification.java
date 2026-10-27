package com.jianguo.mistep;

import de.robv.android.xposed.XposedHelpers;

/** TaskView owns its root leash and descendants while WM still keeps the logical tree. */
final class MiStepSideMagnification {
    private MiStepSideMagnification() {}

    static boolean ownsGeometry(Object container, Object display) {
        return container != null && display != null
                && XposedHelpers.callMethod(container, "asTask") == container
                && XposedHelpers.callMethod(container, "getDisplayContent") == display
                && XposedHelpers.callMethod(container, "getRootTask") == container
                && XposedHelpers.getObjectField(container, "mTaskOrganizer") != null
                && MiStepSideRoleChange.isSide(container);
    }
}
