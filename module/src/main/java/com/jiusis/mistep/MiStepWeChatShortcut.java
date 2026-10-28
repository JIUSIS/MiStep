package com.jiusis.mistep;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.os.Binder;
import android.os.UserHandle;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** Reads WeChat's existing pinned entry for the foreground user; never creates one. */
final class MiStepWeChatShortcut {
    static final String PACKAGE = "com.tencent.mm";
    static final int OPENED = 1, MISSING = 2, AMBIGUOUS = 3;

    static Intent select(List<ShortcutInfo> shortcuts, UserHandle user) {
        Intent selected = null;
        if (shortcuts == null) return null;
        for (ShortcutInfo shortcut : shortcuts) {
            if (shortcut == null || !shortcut.isEnabled() || !shortcut.isPinned()
                    || !PACKAGE.equals(shortcut.getPackage()) || !user.equals(shortcut.getUserHandle())) continue;
            CharSequence label = shortcut.getShortLabel();
            if (label == null || !label.toString().startsWith("乘车码")) continue;
            Intent[] intents = shortcut.getIntents();
            if (intents == null || intents.length != 1 || intents[0] == null) continue;
            Intent saved = intents[0];
            if (!"com.tencent.mm.action.WX_SHORTCUT".equals(saved.getAction())
                    || saved.getSelector() != null
                    || saved.getPackage() != null && !PACKAGE.equals(saved.getPackage())
                    || saved.getComponent() != null && !PACKAGE.equals(saved.getComponent().getPackageName())) continue;
            if (selected != null) throw new IllegalStateException("Multiple WeChat transit shortcuts");
            // Preserve all WeChat-generated extra values and their types; no cached tokens.
            selected = new Intent(saved).setClassName(PACKAGE,
                    "com.tencent.mm.plugin.base.stub.WXShortcutEntryActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return selected;
    }

    static int open(Context context) {
        long identity = Binder.clearCallingIdentity();
        try {
            int currentUser = (Integer) XposedHelpers.callStaticMethod(ActivityManager.class, "getCurrentUser");
            UserHandle user = (UserHandle) XposedHelpers.callStaticMethod(UserHandle.class, "of", currentUser);
            Context wechat = (Context) XposedHelpers.callMethod(context, "createPackageContextAsUser", PACKAGE, 0, user);
            ShortcutManager manager = wechat.getSystemService(ShortcutManager.class);
            if (manager == null) return MISSING;
            Intent launch;
            try { launch = select(manager.getPinnedShortcuts(), user); }
            catch (IllegalStateException ambiguous) { return AMBIGUOUS; }
            if (launch == null) return MISSING;
            Object resolved = XposedHelpers.callMethod(context.getPackageManager(), "resolveActivityAsUser",
                    launch, PackageManager.MATCH_DEFAULT_ONLY, currentUser);
            if (resolved == null) return 0;
            XposedHelpers.callMethod(context, "startActivityAsUser", launch, user);
            return OPENED;
        } catch (Throwable unavailable) {
            // Log no shortcut extras, IDs, tokens or signed payloads.
            de.robv.android.xposed.XposedBridge.log("[Mi Step] WeChat transit shortcut unavailable: "
                    + unavailable.getClass().getSimpleName());
            return 0;
        } finally { Binder.restoreCallingIdentity(identity); }
    }
}
