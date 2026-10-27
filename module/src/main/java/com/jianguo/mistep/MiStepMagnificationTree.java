package com.jianguo.mistep;

import de.robv.android.xposed.XposedHelpers;

/** Make a deferred WM magnification replay independent of older tree ownership. */
final class MiStepMagnificationTree {
    private MiStepMagnificationTree() {}

    static boolean prepare(Object container, Object display, Object transaction, Object spec,
            java.util.Map<Object, Object> tracked) {
        if (container != display || display == null || transaction == null || spec == null
                || XposedHelpers.getObjectField(display, "mMagnificationSpec") != spec
                || tracked == null)
            return false;
        // Recording a finish transaction already updates WM's spec fields, before
        // Shell applies its surface operations. After topology changes, native clear
        // cannot see surfaces whose records were cleared by a newer traversal. Each
        // replay therefore resets every surface previously magnified by WM, then the
        // original traversal installs its current spec in this SAME transaction.
        for (java.util.Map.Entry<Object, Object> entry : tracked.entrySet()) {
            Object owner = entry.getKey(), surface = entry.getValue();
            int mode = ((Number) XposedHelpers.callMethod(owner, "getWindowingMode")).intValue();
            if (XposedHelpers.callMethod(owner, "getDisplayContent") != display
                    || XposedHelpers.getObjectField(owner, "mSurfaceControl") != surface
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))
                    || mode == 6)
                continue;
            // Once WM has released a native task's spec, its canonical leash scale
            // belongs to the OEM freeform animator (including the settled small size).
            // Descendant WM surfaces still need their stale magnification removed.
            if (mode == 5 && XposedHelpers.callMethod(owner, "asTask") != null
                    && XposedHelpers.getObjectField(owner, "mLastMagnificationSpec") == null)
                continue;
            Object position = XposedHelpers.getObjectField(owner, "mLastSurfacePosition");
            XposedHelpers.callMethod(transaction, "setMatrix", surface, 1f, 0f, 0f, 1f);
            XposedHelpers.callMethod(transaction, "setPosition", surface,
                    (float) XposedHelpers.getIntField(position, "x"),
                    (float) XposedHelpers.getIntField(position, "y"));
        }
        return true;
    }
}
