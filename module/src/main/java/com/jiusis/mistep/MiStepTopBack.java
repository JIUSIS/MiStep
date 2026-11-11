package com.jiusis.mistep;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import de.robv.android.xposed.XposedHelpers;

/** A leased pane can request Back only for its exact currently focused app UID. */
final class MiStepTopBack {
    static final String ACTION = "com.jiusis.mistep.TOP_EDGE_BACK";

    static void request(Context context, Object token) {
        IBinder binder = (IBinder) XposedHelpers.callMethod(token, "asBinder");
        android.os.Bundle state = new android.os.Bundle();
        state.putBinder("task", binder);
        context.sendBroadcast(new Intent(ACTION).setPackage("android")
                .putExtra("state", state).putExtra("at", SystemClock.uptimeMillis()),
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    /** Caller holds the real WM lock; the returned dispatch runs outside that lock. */
    static Runnable prepare(Context context, Object display, Intent intent) {
        long age = SystemClock.uptimeMillis() - intent.getLongExtra("at", -1000);
        android.os.Bundle state = intent.getBundleExtra("state");
        IBinder token = state == null ? null : state.getBinder("task");
        if (age < 0 || age > 500 || token == null || display == null) return null;
        Object window = XposedHelpers.getObjectField(display, "mCurrentFocus");
        if (!MiStepImePolicy.isFocusedTopWindow(window, display,
                MiStepDesktopLaunchBridge::ownsTopTask)) return null;
        Object task = XposedHelpers.callMethod(window, "getTask");
        Object info = XposedHelpers.callMethod(task, "getTaskInfo");
        if (!token.equals(XposedHelpers.callMethod(
                XposedHelpers.getObjectField(info, "token"), "asBinder"))) return null;
        int uid = XposedHelpers.getIntField(window, "mOwnerUid");
        if (uid < 1000) return null;
        Object input = context.getSystemService(Context.INPUT_SERVICE);
        return () -> {
            long now = SystemClock.uptimeMillis();
            if (now - intent.getLongExtra("at", -1000) > 500) return;
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0,
                    0, -1, 0, KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(now, now+1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0,
                    0, -1, 0, KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                    InputDevice.SOURCE_KEYBOARD);
            XposedHelpers.callMethod(down, "setDisplayId", 0);
            XposedHelpers.callMethod(up, "setDisplayId", 0);
            try { XposedHelpers.callMethod(input,"injectInputEvent",down,0,uid); }
            finally { XposedHelpers.callMethod(input,"injectInputEvent",up,0,uid); }
        };
    }

    private MiStepTopBack() {}
}
