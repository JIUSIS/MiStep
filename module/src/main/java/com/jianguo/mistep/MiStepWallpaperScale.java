package com.jianguo.mistep;

import java.util.List;
import java.util.function.Supplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Keeps the wallpaper's extra scene zoom out of the main area's background. */
final class MiStepWallpaperScale {
    private static volatile boolean installed;
    private MiStepWallpaperScale() {}

    static void install(ClassLoader cl, Supplier<Object> activeDisplay) {
        try {
            Class<?> controller = XposedHelpers.findClass(
                    "com.android.server.wm.WallpaperController", cl);
            XposedHelpers.findAndHookMethod(controller, "zoomOutToScale", float.class,
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || !(p.getResult() instanceof Float)) return;
                    float nativeScale = (Float) p.getResult();
                    if (!Float.isFinite(nativeScale) || nativeScale <= 1f) return;
                    try {
                        Object display = activeDisplay.get();
                        if (display != null && inWorkspace(p.thisObject, display)) {
                            // Run the original first: it consumes the caller's max scale.
                            // cropZoom and wallpaper offsets are applied separately by WM.
                            p.setResult(1f);
                        }
                    } catch (Throwable unavailable) {
                        // An uncertain display or lock state keeps native behavior.
                    }
                }
            });
            installed = true;
            XposedBridge.log("[MiStep] workspace wallpaper scene-scale hook installed");
        } catch (Throwable unavailable) {
            XposedBridge.log("[MiStep] wallpaper scene-scale hook unavailable: " + unavailable);
        }
    }

    private static boolean inWorkspace(Object controller, Object display) {
        if (XposedHelpers.getObjectField(controller, "mDisplayContent") != display
                || ((Number) XposedHelpers.callMethod(display, "getDisplayId")).intValue() != 0)
            return false;
        Object service = XposedHelpers.getObjectField(display, "mWmService");
        return Thread.holdsLock(XposedHelpers.getObjectField(service, "mGlobalLock"))
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(service, "isKeyguardLocked"))
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(display, "isAodShowing"));
    }

    /** Recompute on entry/exit even if the launcher sends no new wallpaper command. */
    static void refresh(Object display) {
        if (!installed) return;
        try {
            Object service = XposedHelpers.getObjectField(display, "mWmService");
            if (!Thread.holdsLock(XposedHelpers.getObjectField(service, "mGlobalLock"))) return;
            Object controller = XposedHelpers.getObjectField(display, "mWallpaperController");
            Object tokens = XposedHelpers.getObjectField(controller, "mWallpaperTokens");
            if (!(tokens instanceof List<?>)) return;
            for (Object token : (List<?>) tokens)
                XposedHelpers.callMethod(token, "updateWallpaperOffset");
        } catch (Throwable unavailable) {
            XposedBridge.log("[MiStep] wallpaper scale refresh unavailable: " + unavailable);
        }
    }
}
