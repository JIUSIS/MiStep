package com.jiusis.mistep;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

public final class WorkspaceSettingsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        boolean allowed = uid == Process.myUid() || uid == Process.SYSTEM_UID;
        if (!allowed) {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages != null) for (String name : packages)
                if ("com.android.systemui".equals(name)) allowed = true;
        }
        if (!allowed) throw new SecurityException("Settings snapshots are restricted to Mi Step and SystemUI");
        if (!WorkspaceSettingsStore.READ.equals(method)) throw new UnsupportedOperationException("Read only");
        return WorkspaceSettingsStore.bundle(WorkspaceSettingsStore.read(getContext()));
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.com.jiusis.mistep.settings"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException("Use the read snapshot call");
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("Read only");
    }
}
