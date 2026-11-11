package com.jiusis.mistep;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

/** The module app owns persistence. SystemUI receives only a read-only snapshot. */
final class WorkspaceSettingsStore {
    static final Uri URI = Uri.parse("content://com.jiusis.mistep.settings/options");
    static final String READ = "read", HIDDEN = "hidden_buttons", SWAP = "swap_mode",
            ENTRY_GESTURE = "entry_gesture", KEEP_ON_LOCK = "keep_on_lock";
    private WorkspaceSettingsStore() {}

    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences("workspace_settings", Context.MODE_PRIVATE);
    }

    static WorkspaceOptions read(Context context) {
        SharedPreferences preferences = preferences(context);
        return new WorkspaceOptions(preferences.getInt(HIDDEN, 0), preferences.getInt(SWAP, 0),
                preferences.getInt(ENTRY_GESTURE, WorkspaceOptions.STATUS_BAR_INWARD),
                preferences.getBoolean(KEEP_ON_LOCK, false));
    }

    static boolean write(Context context, WorkspaceOptions options) {
        if (options.entryGesture != read(context).entryGesture
                && !EntryGesturePolicy.canSelect(options.entryGesture,
                        EntryGestureSettings.lineHidden(context))) return false;
        boolean saved = preferences(context).edit().putInt(HIDDEN, options.hiddenButtons)
                .putInt(SWAP, options.swapMode).putInt(ENTRY_GESTURE, options.entryGesture)
                .putBoolean(KEEP_ON_LOCK, options.keepOnLock).commit();
        if (saved) context.getContentResolver().notifyChange(URI, null);
        return saved;
    }

    static Bundle bundle(WorkspaceOptions options) {
        Bundle result = new Bundle();
        result.putInt(HIDDEN, options.hiddenButtons); result.putInt(SWAP, options.swapMode);
        result.putInt(ENTRY_GESTURE, options.entryGesture);
        result.putBoolean(KEEP_ON_LOCK, options.keepOnLock);
        return result;
    }

    static WorkspaceOptions decode(Bundle bundle) {
        if (bundle == null) throw new IllegalArgumentException("Settings provider returned no snapshot");
        return new WorkspaceOptions(bundle.getInt(HIDDEN, 0), bundle.getInt(SWAP, 0),
                bundle.getInt(ENTRY_GESTURE, WorkspaceOptions.STATUS_BAR_INWARD),
                bundle.getBoolean(KEEP_ON_LOCK, false));
    }
}
