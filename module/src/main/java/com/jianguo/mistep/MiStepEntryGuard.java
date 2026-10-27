package com.jianguo.mistep;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.os.UserManager;

/** Admission only. Never use this guard to prevent cleanup or returning tasks. */
final class MiStepEntryGuard {
    private MiStepEntryGuard() {}

    static boolean canOpen(Context context) {
        if (context == null) return false;
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
            UserManager user = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (power == null || keyguard == null || user == null) return false;
            return allows(power.isInteractive(), keyguard.isKeyguardLocked(),
                    keyguard.isDeviceLocked(), user.isUserUnlocked());
        } catch (Throwable unavailable) {
            // A missing service during early boot never grants permission to open over keyguard.
            return false;
        }
    }

    static boolean allows(boolean interactive, boolean keyguardLocked,
            boolean deviceLocked, boolean userUnlocked) {
        return interactive && !keyguardLocked && !deviceLocked && userUnlocked;
    }
}
