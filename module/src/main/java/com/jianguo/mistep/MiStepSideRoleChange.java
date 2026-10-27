package com.jianguo.mistep;

import java.util.IdentityHashMap;
import de.robv.android.xposed.XposedHelpers;

/** The exact native WCT call can publish mode before its force-translucent role bit. */
final class MiStepSideRoleChange {
    private static final ThreadLocal<IdentityHashMap<Object, Integer>> pending = new ThreadLocal<>();
    static Object enter(Object task, Object change, Object display) {
        if (task == null || change == null || display == null
                || XposedHelpers.callMethod(task, "getDisplayContent") != display
                || !Integer.valueOf(6).equals(XposedHelpers.callMethod(change, "getWindowingMode"))
                || (((Integer) XposedHelpers.callMethod(change, "getChangeMask")) & 64) == 0
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(change, "getForceTranslucent"))) return null;
        IdentityHashMap<Object, Integer> roles = pending.get();
        if (roles == null) { roles = new IdentityHashMap<>(); pending.set(roles); }
        roles.put(task, roles.getOrDefault(task, 0) + 1);
        return task;
    }
    static void exit(Object task) {
        if (task == null) return;
        IdentityHashMap<Object, Integer> roles = pending.get();
        if (roles == null) return;
        Integer depth = roles.get(task);
        if (depth == null) return;
        if (depth == 1) roles.remove(task); else roles.put(task, depth - 1);
        if (roles.isEmpty()) pending.remove();
    }
    static boolean isSide(Object task) {
        if (task == null) return false;
        IdentityHashMap<Object, Integer> roles = pending.get();
        return roles != null && roles.containsKey(task)
                || Integer.valueOf(6).equals(XposedHelpers.callMethod(task, "getWindowingMode"))
                && Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isForceTranslucent"));
    }
}
