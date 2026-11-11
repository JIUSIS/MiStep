package com.jiusis.mistep;

import android.view.Window;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Identify only the native biometric container; OEM geometry and auth stay native. */
final class MiStepBiometricWindow {
    private static final String TITLE = "MiStepNativeBiometricPrompt";

    static void install(ClassLoader loader) {
        try {
            installNativeContainer(loader);
        } catch (Throwable error) {
            // Optional ROM integration must not interrupt the remaining host hooks.
            XposedBridge.log("MiStepProbe: native biometric hooks unavailable: " + error);
        }
    }

    private static void installNativeContainer(ClassLoader loader) {
        Class<?> container = XposedHelpers.findClassIfExists(
                "com.android.systemui.biometrics.AuthContainerView", loader);
        if (container == null) return;
        XposedBridge.hookAllMethods(container, "getLayoutParams", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable() && param.getResult() instanceof WindowManager.LayoutParams)
                    identify((WindowManager.LayoutParams) param.getResult());
            }
        });
        // OS4 uses an OEM AlertDialog instead of adding AuthContainerView itself.
        // Mark its real Window before show(), so the first frame is physical too.
        XposedBridge.hookAllMethods(container, "show$2", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object dialog = XposedHelpers.getObjectField(param.thisObject, "mAlertDialog");
                    if (dialog == null) return;
                    Window window = (Window) XposedHelpers.callMethod(dialog, "getWindow");
                    if (window == null) return;
                    WindowManager.LayoutParams attrs = window.getAttributes();
                    if (identify(attrs)) window.setAttributes(attrs);
                } catch (Throwable error) {
                    XposedBridge.log("MiStepProbe: native biometric window identity unavailable: " + error);
                }
            }
        });
    }

    static boolean identify(WindowManager.LayoutParams attrs) {
        if (attrs == null || attrs.type != 2009) return false;
        // WindowManager fills a null package from this native SystemUI context.
        if (attrs.packageName != null && !"com.android.systemui".equals(attrs.packageName))
            return false;
        attrs.setTitle(TITLE);
        return true;
    }
}
