package com.jiusis.mistep;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import de.robv.android.xposed.XposedHelpers;

/** Resolve the real TOP task under WMS's lock; reject stale or foreign bindings. */
final class MiStepTopRawInput {
    static int uid(Bundle row, ClassLoader loader) {
        if (row == null) return -1;
        float scale = row.getFloat("scale", 1);
        int width = row.getInt("width", 0), height = row.getInt("height", 0);
        IBinder binder = row.getBinder("task");
        if (binder == null || !Float.isFinite(scale) || scale <= 0 || scale >= 1
                || width <= 0 || height <= 0 || row.getInt("displayId", -1) != 0
                || !Float.isFinite(row.getFloat("left",0)) || row.getFloat("left",0)<0
                || !Float.isFinite(row.getFloat("top",0)) || row.getFloat("top",0)<0
                || row.getInt("taskId", -1) < 0 || row.getInt("userId", -1) < 0) return -1;
        Object task = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                "com.android.server.wm.WindowContainer", loader), "fromBinder", binder);
        if (task == null || !MiStepSideRoleChange.isSide(task)
                || XposedHelpers.getIntField(task, "mTaskId") != row.getInt("taskId")) return -1;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if (bounds == null || bounds.left != 0 || bounds.top != 0
                || bounds.width() != width || bounds.height() != height) return -1;
        Object info = XposedHelpers.callMethod(task, "getTaskInfo");
        Object token = XposedHelpers.getObjectField(info, "token");
        if (XposedHelpers.getIntField(info, "taskId") != row.getInt("taskId")
                || XposedHelpers.getIntField(info, "userId") != row.getInt("userId")
                || XposedHelpers.getIntField(info, "displayId") != 0
                || !binder.equals(XposedHelpers.callMethod(token, "asBinder"))) return -1;
        Object activity = XposedHelpers.getObjectField(info, "topActivityInfo");
        if (activity == null) return -1;
        Object app = XposedHelpers.getObjectField(activity, "applicationInfo");
        int uid = XposedHelpers.getIntField(app, "uid");
        return uid >= 10000 && uid / 100000 == row.getInt("userId") ? uid : -1;
    }
}
