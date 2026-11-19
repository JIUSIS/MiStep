package com.jiusis.mistep;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import de.robv.android.xposed.XposedHelpers;

/** Match an application entry to its task root, never to an unrelated same-package task. */
final class TaskLaunchIdentity {
    private TaskLaunchIdentity() {}

    static boolean matches(PackageManager pm, ComponentName entry, Object info) {
        if (entry == null || info == null) return false;
        ComponentName target = canonical(pm, entry);
        try {
            Intent base = (Intent) XposedHelpers.getObjectField(info, "baseIntent");
            if (base != null && target.equals(canonical(pm, base.getComponent()))) return true;
        } catch (Throwable unavailable) { /* Older task-info providers may omit baseIntent. */ }
        try {
            return target.equals(canonical(pm,
                    (ComponentName) XposedHelpers.getObjectField(info, "baseActivity")));
        } catch (Throwable unavailable) { return false; }
    }

    private static ComponentName canonical(PackageManager pm, ComponentName component) {
        if (component == null || pm == null) return component;
        try {
            ActivityInfo activity = pm.getActivityInfo(component, 0);
            String target = activity.targetActivity;
            if (target != null && !target.isEmpty()) {
                if (target.startsWith(".")) target = component.getPackageName() + target;
                return new ComponentName(component.getPackageName(), target);
            }
        } catch (Exception unavailable) { /* An exact component remains a usable identity. */ }
        return component;
    }
}
