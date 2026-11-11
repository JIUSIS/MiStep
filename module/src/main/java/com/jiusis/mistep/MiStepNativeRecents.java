package com.jiusis.mistep;

import java.lang.ref.WeakReference;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Request native overview through SystemUI's existing CommandQueue. */
final class MiStepNativeRecents {
    private static WeakReference<Object> queue = new WeakReference<>(null);
    private static boolean installed;
    private MiStepNativeRecents() {}

    static void install(ClassLoader loader) {
        if (installed) return;
        try {
            Class<?> type = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.CommandQueue", loader);
            if (!ShellCompat.hasMethod(type, "showRecentApps", 1))
                throw new IllegalStateException("native showRecentApps unavailable");
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) queue = new WeakReference<>(param.thisObject);
                }
            });
            installed = true;
        } catch (Throwable error) {
            XposedBridge.log("[MiStep] native overview bridge unavailable: " + error);
        }
    }

    static boolean show() {
        Object current = queue.get();
        if (current == null) return false;
        try {
            // show is idempotent; toggle could dismiss an overview already opening.
            XposedHelpers.callMethod(current, "showRecentApps", false);
            return true;
        } catch (Throwable error) {
            XposedBridge.log("[MiStep] native overview request failed: " + error);
            return false;
        }
    }
}
