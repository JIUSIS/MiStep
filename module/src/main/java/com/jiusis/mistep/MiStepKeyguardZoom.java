package com.jiusis.mistep;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Native lock-entry barrier; clearing it never restores or authenticates a workspace. */
final class MiStepKeyguardZoom {
    interface Reset { void run() throws Throwable; }
    private volatile boolean blocked;
    boolean blocked() { return blocked; }
    boolean onAuthenticatedReveal(boolean authenticated) {
        if (!authenticated) return false;
        blocked = false;
        return true;
    }
    void onShown(int displayId, boolean keyguard, boolean aod, Reset reset) throws Throwable {
        if (displayId != 0) return;
        blocked = keyguard || aod;
        if (blocked) reset.run();
    }
    void install(ClassLoader loader, Reset reset) {
        XposedHelpers.findAndHookMethod(
                "com.android.server.wm.KeyguardController", loader, "setKeyguardShown",
                int.class, boolean.class, boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try { onShown((Integer) p.args[0], (Boolean) p.args[1],
                                (Boolean) p.args[2], reset); }
                        catch (Throwable error) {
                            XposedBridge.log("MiStepProbe: native keyguard zoom reset failed: " + error);
                        }
                    }
                });
    }
}
