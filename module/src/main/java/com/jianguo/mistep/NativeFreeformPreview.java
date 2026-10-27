package com.jianguo.mistep;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** The OEM skips foreign mode6 updates but can retain their old fullscreen entries. */
final class NativeFreeformPreview {
    private static final AtomicInteger reports = new AtomicInteger();
    private NativeFreeformPreview() {}

    static void install(ClassLoader cl) {
        try {
            Class<?> follow = XposedHelpers.findClass(
                    "com.android.wm.shell.multitasking.miuimultiwinswitch.MulWinSwitchFollowAnimManager", cl);
            XposedHelpers.findAndHookMethod(follow, "filterVisibleTask", List.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args == null || p.args.length != 1
                            || !(p.args[0] instanceof List<?>)) return;
                    try {
                        prune(p.thisObject, (List<?>) p.args[0], MiStepHost.activeMainTransform());
                    } catch (Throwable error) {
                        report("validation unavailable: " + error);
                    }
                }
            });
            XposedBridge.log("MiStepProbe: native freeform preview validates current task map");
        } catch (Throwable error) {
            XposedBridge.log("MiStepProbe: native freeform preview hook unavailable: " + error);
        }
    }

    static int prune(Object follow, List<?> candidates, float[] main) {
        if (follow == null || candidates == null || candidates.isEmpty()
                || main == null || main.length != 3 || !(main[0] > 0f && main[0] < 1f)
                || !Float.isFinite(main[1]) || !Float.isFinite(main[2])) return 0;
        Object source = XposedHelpers.getObjectField(follow, "mDragTaskInfo");
        if (source == null) return 0;
        Object sourceInfo = XposedHelpers.callMethod(source, "getTaskInfo");
        if (!visibleAt(sourceInfo, 5)) return 0;
        Object repository = XposedHelpers.getObjectField(follow, "mMultiTaskingTaskRepository");
        Object tasks = XposedHelpers.getObjectField(repository, "mTasks");
        Object currentSource = XposedHelpers.callMethod(tasks, "get",
                XposedHelpers.getIntField(sourceInfo, "taskId"));
        if (!visibleAt(currentSource, 5)) return 0;

        // These are fresh query lists, not repository indexes. Validate every entry
        // before removing anything; a changed ROM layout must not half-edit the list.
        ArrayList<Integer> rejected = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Object candidate = candidates.get(i);
            if (candidate == null) continue;
            Object cached = XposedHelpers.callMethod(candidate, "getTaskInfo");
            if (cached == null || !Integer.valueOf(1).equals(
                    XposedHelpers.callMethod(cached, "getWindowingMode"))) continue;
            Object current = XposedHelpers.callMethod(tasks, "get",
                    XposedHelpers.getIntField(cached, "taskId"));
            if (!visibleAt(current, 1)) rejected.add(i);
        }
        for (int i = rejected.size() - 1; i >= 0; i--) candidates.remove((int) rejected.get(i));
        if (!rejected.isEmpty()) report("rejected stale fullscreen backgrounds=" + rejected.size());
        return rejected.size();
    }

    private static boolean visibleAt(Object info, int mode) {
        return info != null && XposedHelpers.getIntField(info, "displayId") == 0
                && Integer.valueOf(mode).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                && Boolean.TRUE.equals(XposedHelpers.callMethod(info, "isVisible"))
                && Boolean.TRUE.equals(XposedHelpers.getObjectField(info, "isVisibleRequested"));
    }

    private static void report(String message) {
        if (reports.getAndIncrement() < 16)
            XposedBridge.log("MiStepProbe: native freeform preview " + message);
    }
}
