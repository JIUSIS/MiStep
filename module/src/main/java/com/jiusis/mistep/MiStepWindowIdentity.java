package com.jiusis.mistep;

import android.content.Context;
import android.view.WindowManager;
import java.util.Arrays;
import de.robv.android.xposed.XposedHelpers;

/** WM lock only: resolve the registered window's native session and package owner. */
final class MiStepWindowIdentity {
    static boolean ownsHost(Object window, Object display) {
        if (window == null || display == null) return false;
        try {
            if (XposedHelpers.callMethod(window, "getDisplayContent") != display) return false;
            WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                    XposedHelpers.getObjectField(window, "mAttrs");
            if (attrs.type != 2038 || !"com.android.systemui".equals(attrs.packageName)
                    || !"MiStepHost".contentEquals(attrs.getTitle())
                    || (attrs.flags & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED) != 0) return false;
            int uid = XposedHelpers.getIntField(window, "mOwnerUid");
            Object session = XposedHelpers.getObjectField(window, "mSession");
            if (uid < 0 || session == null || XposedHelpers.getIntField(session, "mUid") != uid) return false;
            Object service = XposedHelpers.getObjectField(display, "mWmService");
            Context context = (Context) XposedHelpers.getObjectField(service, "mContext");
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            return packages != null && Arrays.asList(packages).contains("com.android.systemui");
        } catch (Throwable unavailable) {
            return false; // An unavailable package/session never grants a privileged lease.
        }
    }
    private MiStepWindowIdentity() {}
}
