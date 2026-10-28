package com.jiusis.mistep;

final class DesktopIconLaunchPolicy {
    private static final String LAUNCHER = "com.miui.home";
    private static final String ACTION_MAIN = "android.intent.action.MAIN";

    private DesktopIconLaunchPolicy() {}

    static String packageForLauncherEntry(String callerPackage, String action,
            boolean launcherCategory, String componentPackage) {
        if (!LAUNCHER.equals(callerPackage) || !ACTION_MAIN.equals(action)
                || !launcherCategory || componentPackage == null || componentPackage.isEmpty()
                || LAUNCHER.equals(componentPackage)) return null;
        return componentPackage;
    }

    /** Returns the side-slot index, relative to sidePackages, only for one exact match. */
    static int uniqueSideSlot(String packageName, String[] sidePackages, boolean[] occupied) {
        if (packageName == null || sidePackages == null || occupied == null
                || sidePackages.length != occupied.length) return -1;
        int match = -1;
        for (int i = 0; i < sidePackages.length; i++) {
            if (!occupied[i] || !packageName.equals(sidePackages[i])) continue;
            if (match >= 0) return -1;
            match = i;
        }
        return match;
    }
}
