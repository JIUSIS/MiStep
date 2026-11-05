package com.jiusis.mistep;

import android.os.Bundle;
import java.util.function.Predicate;
import de.robv.android.xposed.XposedHelpers;

/** Explicit physical pointer focus before a native edge-back gesture starts. */
final class MiStepTopPointerFocus {
    static boolean contains(Bundle row, float x, float y) {
        if (row == null || !Float.isFinite(x) || !Float.isFinite(y)) return false;
        float scale = row.getFloat("scale", 1);
        int width = row.getInt("width", 0), height = row.getInt("height", 0);
        return row.getInt("displayId", -1) == 0 && Float.isFinite(scale)
                && scale > 0 && scale < 1 && width > height && height > 0
                && x >= 0 && y >= 0 && x < width * scale && y < height * scale;
    }

    /** Caller holds WMS's global lock and has resolved the current visible lease. */
    static boolean select(Object task, Object display, Predicate<Object> lease) {
        if (task == null || display == null || !MiStepSideRoleChange.isSide(task)
                || !lease.test(task) || XposedHelpers.callMethod(task, "getDisplayContent") != display
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisible"))) return false;
        Object activity = XposedHelpers.callMethod(task, "topRunningActivityLocked");
        Object window = activity == null ? null : XposedHelpers.callMethod(activity, "findMainWindow");
        if (window == null || XposedHelpers.callMethod(window, "getTask") != task) return false;
        if (MiStepImePolicy.isFocusedTopWindow(window, display)) return false;
        MiStepImePolicy.admitTopTouch(window, display, lease);
        if (!MiStepImePolicy.isTopAppFocused(task, display)) return false;
        XposedHelpers.callMethod(display, "updateFocusedWindowLocked", 0, true, 0);
        return MiStepImePolicy.isFocusedTopWindow(window, display);
    }

    private MiStepTopPointerFocus() {}
}
