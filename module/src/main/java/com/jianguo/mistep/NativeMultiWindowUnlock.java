package com.jianguo.mistep;

import android.content.ComponentName;
import java.lang.reflect.Method;
import java.util.ArrayList;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Removes app admission restrictions while retaining native window/display ownership. */
final class NativeMultiWindowUnlock {
    private NativeMultiWindowUnlock() {}

    static void install(ClassLoader loader, boolean server) {
        String adapter = "android.util.MiuiMultiWindowAdapter";
        for (String method : new String[]{"getFreeformBlackList", "getFreeformBlackListFromCloud",
                "getAbnormalFreeformBlackList", "getAbnormalFreeformBlackListFromCloud",
                "getStartFromFreeformBlackList", "getStartFromFreeformBlackListFromCloud",
                "getForegroundPinAppBlackList", "getForegroundPinAppBlackListFromCloud"})
            replace(loader, adapter, method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setResult(new ArrayList<String>());
                }
            });
        Class<?> type = XposedHelpers.findClassIfExists(adapter, loader);
        if (type != null) for (String field : new String[]{"FREEFORM_BLACK_LIST", "ABNORMAL_FREEFORM_BLACK_LIST",
                "START_FROM_FREEFORM_BLACK_LIST_ACTIVITY", "FOREGROUND_PIN_APP_BLACK_LIST"}) {
            try { XposedHelpers.setStaticObjectField(type, field, new ArrayList<String>()); }
            catch (Throwable e) { log(field, e); }
        }
        String utils = "android.util.MiuiMultiWindowUtils";
        replace(loader, utils, "getFreeformBlackList", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(new ArrayList<String>()); }
        });
        constant(loader, utils, "isForceResizeable", true);
        for (String name : new String[]{"packageSupportFreeform", "supportFreeFromWindow"})
            replace(loader, utils, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length == 2 && p.args[1] instanceof String
                            && !((String) p.args[1]).isEmpty()) p.setResult(true);
                }
            });
        if (!server) return;

        XC_MethodHook resize = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                // Home/recents/system containers keep their native activity-type policy.
                try {
                    int activityType = (Integer) XposedHelpers.callMethod(p.thisObject, "getActivityType");
                    if (activityType == 0 || activityType == 1) p.setResult(true);
                } catch (Throwable e) { log("activity type", e); }
            }
        };
        replace(loader, "com.android.server.wm.Task", "isResizeable", resize);
        replace(loader, "com.android.server.wm.ActivityRecord", "isResizeable", resize);
        constant(loader, "com.android.server.wm.TaskDisplayArea", "supportsNonResizableMultiWindow", true);
        constant(loader, "com.android.server.wm.TaskDisplayArea", "supportsActivityMinWidthHeightMultiWindow", true);
        constant(loader, "com.android.server.wm.MiuiSplitScreenImpl", "inResizeBlackList", false);
        replace(loader, "com.android.server.wm.ActivityTaskManagerService", "supportsSplitScreen", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.hasThrowable() || Boolean.TRUE.equals(p.getResult()) || p.args.length != 1
                        || !(p.args[0] instanceof ComponentName)) return;
                try {
                    // Match ATMS's original per-calling-user lookup, including cloned profiles.
                    Object pm = XposedHelpers.callStaticMethod(XposedHelpers.findClass("android.app.AppGlobals", loader), "getPackageManager");
                    Object user = XposedHelpers.callStaticMethod(android.os.UserHandle.class, "getCallingUserId");
                    Object info = XposedHelpers.callMethod(pm, "getActivityInfo", p.args[0], 1024L, user);
                    if (info != null) p.setResult(true);
                } catch (Throwable ignored) { }
            }
        });
    }

    private static void constant(ClassLoader loader, String cls, String name, boolean value) {
        replace(loader, cls, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult(value); }
        });
    }

    private static void replace(ClassLoader loader, String cls, String name, XC_MethodHook hook) {
        Class<?> type = XposedHelpers.findClassIfExists(cls, loader);
        if (type == null) { log(cls + "." + name, new ClassNotFoundException(cls)); return; }
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (!name.equals(method.getName())) continue;
            try { XposedBridge.hookMethod(method, hook); count++; }
            catch (Throwable e) { log(cls + "." + name, e); }
        }
        if (count == 0) log(cls + "." + name, new NoSuchMethodException(name));
    }

    private static void log(String site, Throwable e) {
        XposedBridge.log("MiStepProbe: native multiwindow " + site + ": " + e.getClass().getSimpleName());
    }
}
