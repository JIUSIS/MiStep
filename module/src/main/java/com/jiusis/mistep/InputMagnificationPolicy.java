package com.jiusis.mistep;

final class InputMagnificationPolicy {
    private static final int PRIVATE_FLAG_EXCLUDE_FROM_MAGNIFICATION = 0x08000000;

    private InputMagnificationPolicy() {}

    static boolean shouldForceMagnification(int windowType, int privateFlags) {
        if (windowType == 2024 || windowType == 2027 || windowType == 2039) return false;
        if (windowType == 2011 || windowType == 2012 || windowType == 2019) return true;
        return (privateFlags & PRIVATE_FLAG_EXCLUDE_FROM_MAGNIFICATION) != 0;
    }

    static boolean shouldForceMagnification(int windowType, int privateFlags,
            String packageName, CharSequence title) {
        // OS4's real permission capsule, dot, RemoteViews and touch area share
        // this window. Admit the whole native window, without changing its state.
        if (windowType == 2017 && "com.android.systemui".equals(packageName)
                && title != null && "FlaresView".contentEquals(title)) return true;
        // The independent pin icon window must share WM's visual and input
        // transform. Other 2027 gesture windows keep their native policy.
        if (windowType == 2027 && "com.android.systemui".equals(packageName)
                && title != null && "MiuiBubbles".contentEquals(title)) return true;
        return shouldForceMagnification(windowType, privateFlags);
    }
}
