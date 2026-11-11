package com.jiusis.mistep;

import android.view.SurfaceControl;
import de.robv.android.xposed.XposedHelpers;

/** Publish the workspace sampling coordinates to the OEM compositor. */
final class MiStepPassBlurCoordinates {
    private Object display;
    private SurfaceControl surface;

    boolean stage(Object owner, Object transaction, float scale, float x, float y) {
        if (owner == null || transaction == null || !Float.isFinite(scale)
                || scale <= 0f || scale > 1f || !Float.isFinite(x) || !Float.isFinite(y)
                || (scale == 1f && (x != 0f || y != 0f))
                || !Integer.valueOf(0).equals(XposedHelpers.callMethod(owner, "getDisplayId")))
            return false;
        if (surface == null) {
            if (scale == 1f) return false;
            Object builder = XposedHelpers.newInstance(SurfaceControl.Builder.class);
            // OS2 recognizes FullscreenMagnification:0; OS3 recognizes AppZoomOut:0.
            // Both exact shipped compositor branches consume the same coordinate hint.
            // Actual RequestedLayerState::merge recognizes its native substring
            // before setMagnifyPos/Scale. This hidden, unparented container carries
            // no content and never replaces or reparents an application layer.
            XposedHelpers.callMethod(builder, "setName", "MiStep/FullscreenMagnification:0/AppZoomOut:0");
            XposedHelpers.callMethod(builder, "setContainerLayer");
            XposedHelpers.callMethod(builder, "setHidden", true);
            surface = (SurfaceControl) XposedHelpers.callMethod(builder, "build");
            display = owner;
        }
        if (display != owner || surface == null
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) return false;
        XposedHelpers.callMethod(transaction, "setMatrix", surface, scale, 0f, 0f, scale);
        XposedHelpers.callMethod(transaction, "setPosition", surface, x, y);
        // Keep this process-owned handle at identity after exit. Releasing it
        // before the borrowed transaction commits can discard the final reset.
        // System-server death releases the handle with its compositor client.
        return true;
    }
}
