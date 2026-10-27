package com.jianguo.mistep;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Notification-only handoff, after SystemUI's keyguard and remote-input decisions. */
final class MiStepNotificationLaunch {
    static void install(ClassLoader loader, Supplier<Context> context) {
        try {
            Class<?> starter = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter", loader);
            // OEM exposes a public dispatch wrapper; older ROMs use the private method.
            boolean hasPublic = false;
            for (Method method : starter.getDeclaredMethods()) {
                if (eligible(method, "startNotificationIntentPublic")) hasPublic = true;
            }
            String name = hasPublic ? "startNotificationIntentPublic" : "startNotificationIntent";
            int count = 0;
            for (Method method : starter.getDeclaredMethods()) {
                if (!eligible(method, name)) continue;
                int pendingIndex = -1;
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) if (types[i] == PendingIntent.class) pendingIndex = i;
                final int index = pendingIndex;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            PendingIntent pending = (PendingIntent) param.args[index];
                            Context ctx = context.get();
                            if (ctx == null || pending == null || !pending.isActivity()) return;
                            int user = (Integer) XposedHelpers.callMethod(
                                    pending.getCreatorUserHandle(), "getIdentifier");
                            String target = targetPackage(ctx, pending, user);
                            if (target == null) return;
                            Object receiver = param.thisObject;
                            Object[] originalArgs = param.args.clone();
                            AtomicBoolean sent = new AtomicBoolean();
                            Runnable original = () -> {
                                if (!sent.compareAndSet(false, true)) return;
                                try {
                                    XposedBridge.invokeOriginalMethod(method, receiver, originalArgs);
                                } catch (Throwable error) {
                                    log("notification original dispatch failed: " + error);
                                }
                            };
                            if (MiStepHost.promoteForNotification(target, user, original)) param.setResult(null);
                        } catch (Throwable error) {
                            // Unsupported/ambiguous notifications retain the native dispatch.
                            log("notification promotion bypassed: " + error);
                        }
                    }
                });
                count++;
            }
            log("notification activity dispatch hooks=" + count + " method=" + name);
        } catch (Throwable unavailable) {
            log("notification activity dispatch unavailable: " + unavailable);
        }
    }

    private static boolean eligible(Method method, String name) {
        if (!name.equals(method.getName()) || method.getReturnType() != void.class) return false;
        for (Class<?> type : method.getParameterTypes()) if (type == PendingIntent.class) return true;
        return false;
    }

    private static void log(String message) { XposedBridge.log("MiStepProbe: " + message); }

    private static String targetPackage(Context context, PendingIntent pending, int user) {
        Intent target = (Intent) XposedHelpers.callMethod(pending, "getIntent");
        if (target == null) return null;
        if (target.getComponent() != null) return target.getComponent().getPackageName();
        ResolveInfo resolved = (ResolveInfo) XposedHelpers.callMethod(context.getPackageManager(),
                "resolveActivityAsUser", target, 0x10000, user);
        return resolved == null || resolved.activityInfo == null ? null : resolved.activityInfo.packageName;
    }
}
