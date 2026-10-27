package com.jianguo.mistep;

import android.view.WindowManager;

import de.robv.android.xposed.XposedHelpers;

/** Keep only our host windows in physical screen coordinates. */
final class HostWindowPolicy {
    private static final int NOT_MAGNIFIABLE = 1 << 22;
    private static final int EXCLUDE_FROM_MAGNIFICATION_REGION = 1 << 21;

    private HostWindowPolicy() {}

    static void configure(WindowManager.LayoutParams params) {
        // These windows belong to SystemUI, which holds INTERNAL_SYSTEM_WINDOW.
        // A transparent hole in a 2038 window's touch region alone does not prevent
        // InputDispatcher from rejecting touches to the application underneath.
        XposedHelpers.callMethod(params, "setTrustedOverlay");
        int flags = XposedHelpers.getIntField(params, "privateFlags");
        XposedHelpers.setIntField(params, "privateFlags",
                flags | NOT_MAGNIFIABLE | EXCLUDE_FROM_MAGNIFICATION_REGION);
    }
}
