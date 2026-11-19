package com.jiusis.mistep;

import android.content.res.Configuration;
import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;

/** Keep each upper Activity's metrics in the viewport already selected by its Task. */
final class MiStepTopConfiguration {
    /** Called only for an exact owned transform (including native apply's synchronous scope). */
    static boolean applyRotated(Object activity, Object task, Configuration local) {
        if (activity == null || local == null || !MiStepSideRoleChange.isSide(task)) return false;
        Rect carrier = (Rect) XposedHelpers.callMethod(task, "getBounds");
        Object localWindow = XposedHelpers.getObjectField(local, "windowConfiguration");
        Rect viewport = (Rect) XposedHelpers.callMethod(localWindow, "getBounds");
        if (carrier == null || viewport == null || carrier.width() <= carrier.height()
                || viewport.width() != carrier.height() || viewport.height() != carrier.width()
                || local.densityDpi <= 0 || local.screenWidthDp <= 0
                || local.screenHeightDp <= 0 || local.smallestScreenWidthDp <= 0) return false;
        Configuration resolved = (Configuration) XposedHelpers.callMethod(
                activity, "getResolvedOverrideConfiguration");
        // WM resolves against its rotated parent, but ConfigurationContainer finally merges
        // against the original wide Task. Explicitly carry the local resource qualifiers.
        // Keep WM's app/inset geometry and sequence; they can legitimately reduce w/h dp.
        resolved.densityDpi = local.densityDpi;
        resolved.orientation = local.orientation;
        resolved.smallestScreenWidthDp = local.smallestScreenWidthDp;
        resolved.screenLayout = local.screenLayout;
        if (resolved.screenWidthDp > resolved.screenHeightDp && resolved.screenHeightDp > 0
                && local.screenWidthDp < local.screenHeightDp) {
            Object resolvedWindow = XposedHelpers.getObjectField(resolved, "windowConfiguration");
            Rect app = (Rect) XposedHelpers.callMethod(resolvedWindow, "getAppBounds");
            boolean hasPortraitApp = app != null && app.width() > 0 && app.height() > app.width()
                    && app.width() <= viewport.width() && app.height() <= viewport.height();
            resolved.screenWidthDp = hasPortraitApp
                    ? Math.round(app.width() * 160f / local.densityDpi) : local.screenWidthDp;
            resolved.screenHeightDp = hasPortraitApp
                    ? Math.round(app.height() * 160f / local.densityDpi) : local.screenHeightDp;
        }
        if (resolved.screenWidthDp <= 0) resolved.screenWidthDp = local.screenWidthDp;
        if (resolved.screenHeightDp <= 0) resolved.screenHeightDp = local.screenHeightDp;
        return true;
    }

    static boolean apply(Object activity, Object task, Configuration parent) {
        if (activity == null || parent == null || !MiStepSideRoleChange.isSide(task)) return false;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if (bounds == null || bounds.width() <= bounds.height()) return false;
        // A fixed-rotation token already supplies its local portrait configuration.
        // Do not overwrite it with the wide TaskView carrier's metrics.
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(activity, "hasFixedRotationTransform"))) {
            Rect rotated = (Rect) XposedHelpers.callMethod(activity, "getFixedRotationTransformDisplayBounds");
            if (rotated != null && rotated.width() == bounds.height()
                    && rotated.height() == bounds.width()) return false;
        }
        Object parentWindow = XposedHelpers.getObjectField(parent, "windowConfiguration");
        Rect parentBounds = (Rect) XposedHelpers.callMethod(parentWindow, "getBounds");
        Rect appBounds = (Rect) XposedHelpers.callMethod(parentWindow, "getAppBounds");
        // Only the explicit full-viewport TOP configuration opts in. Other Shell tasks do not.
        if (!bounds.equals(parentBounds) || !bounds.equals(appBounds)
                || parent.densityDpi <= 0 || parent.screenWidthDp <= 0
                || parent.screenHeightDp <= 0 || parent.smallestScreenWidthDp <= 0) return false;
        Configuration resolved = (Configuration) XposedHelpers.callMethod(
                activity, "getResolvedOverrideConfiguration");
        Object window = XposedHelpers.getObjectField(resolved, "windowConfiguration");
        XposedHelpers.callMethod(window, "setAppBounds", new Rect(bounds));
        // Display APIs used by video layouts and dialogs must not return the physical phone size.
        XposedHelpers.callMethod(window, "setMaxBounds", new Rect(bounds));
        resolved.densityDpi = parent.densityDpi;
        resolved.screenWidthDp = parent.screenWidthDp;
        resolved.screenHeightDp = parent.screenHeightDp;
        resolved.smallestScreenWidthDp = parent.smallestScreenWidthDp;
        resolved.orientation = parent.orientation;
        resolved.screenLayout = parent.screenLayout;
        return true;
    }
}
