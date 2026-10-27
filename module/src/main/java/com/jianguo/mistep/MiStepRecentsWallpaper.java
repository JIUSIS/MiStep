package com.jianguo.mistep;

import android.graphics.Rect;
import android.view.SurfaceControl;
import java.util.Map;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Fill the capture domain behind the visible, workspace-scaled wallpaper. */
final class MiStepRecentsWallpaper {
    private MiStepRecentsWallpaper() {}

    static boolean stage(Object info, Object change, Object wrapped, Object transaction,
            float[] main, Rect mainBounds, Map<Object, Object> leashes) {
        if (info == null || change == null || wrapped == null || transaction == null
                || main == null || main.length != 3 || mainBounds == null || leashes == null) return false;
        float scale = main[0], y = main[1], x = main[2];
        if (!(scale > 0f && scale < 1f) || !Float.isFinite(x) || !Float.isFinite(y))
            return false;
        int type = (Integer) XposedHelpers.callMethod(info, "getType");
        if ((type != 1021 && type != 3)
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getRootCount"))
                || XposedHelpers.callMethod(change, "getTaskInfo") != null
                || ((Integer) XposedHelpers.callMethod(change, "getFlags") & 2) == 0
                || XposedHelpers.callMethod(change, "getParent") != null) return false;
        Object canonical = XposedHelpers.callMethod(change, "getLeash");
        if (canonical == wrapped || canonical == null || leashes.get(canonical) != wrapped
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(canonical, "isValid"))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(wrapped, "isValid"))) return false;
        Object root = XposedHelpers.callMethod(info, "getRoot", 0);
        Object offset = XposedHelpers.callMethod(root, "getOffset");
        if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(root, "getDisplayId"))
                || XposedHelpers.getIntField(offset, "x") != 0
                || XposedHelpers.getIntField(offset, "y") != 0) return false;
        boolean home = false, app = false, included = false;
        for (Object item : (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges")) {
            if (item == change) included = true;
            if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(item, "getStartDisplayId"))
                    || !Integer.valueOf(0).equals(XposedHelpers.callMethod(item, "getEndDisplayId"))
                    || !XposedHelpers.callMethod(item, "getStartRotation").equals(
                            XposedHelpers.callMethod(item, "getEndRotation"))) return false;
            Object task = XposedHelpers.callMethod(item, "getTaskInfo");
            if (task == null) {
                if (((Integer) XposedHelpers.callMethod(item, "getFlags") & 2) == 0) return false;
                continue;
            }
            if (XposedHelpers.getIntField(task, "displayId") != 0
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(task, "hasParentTask"))
                    || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task, "getWindowingMode")))
                return false;
            int activity = (Integer) XposedHelpers.callMethod(task, "getActivityType");
            int mode = (Integer) XposedHelpers.callMethod(item, "getMode");
            if (activity == 2 && mode == 3 && !home) home = true;
            else if (activity == 1 && (mode == 2 || mode == 4)) app = true;
            else return false;
        }
        if (!home || !app || !included) return false;
        Object bounds = XposedHelpers.callMethod(change, "getStartAbsBounds");
        int width = XposedHelpers.getIntField(bounds, "right");
        int height = XposedHelpers.getIntField(bounds, "bottom");
        if (XposedHelpers.getIntField(bounds, "left") != 0
                || XposedHelpers.getIntField(bounds, "top") != 0 || width <= 0 || height <= 0
                || x < 0f || y < 0f || x + scale * width > width + 1f
                || y + scale * height > height + 1f) return false;
        RecentsWallpaperCrop crop = RecentsWallpaperCrop.mainPane(
                mainBounds.left, mainBounds.top, mainBounds.right, mainBounds.bottom, width, height);
        if (crop == null) return false;
        // First native passblur captures the full display. Expanding the visible
        // wallpaper itself also expands it throughout CLOSE_TO_HOME. Instead,
        // keep that layer at its existing workspace scale and fill behind it.
        // The OEM wrapper owns canonical at layer 0; this live mirror is at -1.
        Object fill = null;
        try (SurfaceControl.Transaction staged = new SurfaceControl.Transaction()) {
            fill = XposedHelpers.callStaticMethod(SurfaceControl.class, "mirrorSurface", canonical);
            if (fill == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(fill, "isValid")))
                throw new IllegalStateException("wallpaper capture fill unavailable");
            XposedHelpers.callMethod(staged, "reparent", fill, wrapped);
            XposedHelpers.callMethod(staged, "setMatrix", fill, 1f / scale, 0f, 0f, 1f / scale);
            XposedHelpers.callMethod(staged, "setPosition", fill, -x / scale, -y / scale);
            XposedHelpers.callMethod(staged, "setCrop", fill,
                    new Rect(crop.left, crop.top, crop.right, crop.bottom));
            XposedHelpers.callMethod(staged, "setLayer", fill, -1);
            XposedHelpers.callMethod(staged, "setAlpha", fill, 1f);
            XposedHelpers.callMethod(staged, "show", fill);
            // RecentsController.cleanUp releases every value, including runner
            // death and gesture cancellation. No independent timer or lease.
            leashes.put(fill, fill);
            XposedHelpers.callMethod(transaction, "merge", staged);
            XposedBridge.log("[Mi Step] Recents wallpaper capture fill cropped to main " + crop);
            return true;
        } catch (Throwable error) {
            if (fill != null) {
                leashes.remove(fill);
                XposedHelpers.callMethod(fill, "release");
            }
            throw new IllegalStateException("wallpaper capture fill staging failed", error);
        }
    }
}
