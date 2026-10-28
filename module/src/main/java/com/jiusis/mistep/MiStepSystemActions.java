package com.jiusis.mistep;

import android.view.View;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** User initiated actions through the exact OS4 SystemUI quick settings implementation. */
final class MiStepSystemActions {
    private static final String HOST =
            "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter";
    private static final String FLASHLIGHT =
            "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl";
    private static final String OEM_RECORD_SPEC =
            "custom(com.miui.screenrecorder/.service.QuickService)";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Object> ownedTiles = new HashMap<>();
    private static final Map<String, Long> lastRequests = new HashMap<>();
    private static volatile WeakReference<Object> host = new WeakReference<>(null);
    private static volatile WeakReference<Object> flashlight = new WeakReference<>(null);
    private static boolean installed;
    private static boolean receiverRegistered;
    private static int logsRemaining = 6;
    private static PendingRecord pendingRecord;

    /** Call at package load, before SystemUI constructs its Dagger graph. */
    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        installed = true;
        MiStepOs4Controls.install(loader);
        try {
            Class<?> type = XposedHelpers.findClass(HOST, loader);
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) host = new WeakReference<>(param.thisObject);
                }
            });
        } catch (Throwable error) {
            logFailure("install", error);
        }
        try {
            Class<?> type = XposedHelpers.findClass(FLASHLIGHT, loader);
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) flashlight = new WeakReference<>(param.thisObject);
                }
            });
        } catch (Throwable error) {
            logFailure("flashlight install", error);
        }
    }

    /** Context registration belongs to SystemUIService.onCreate; install still runs early. */
    static void start(Context context, ClassLoader loader) {
        install(loader);
        if (context == null) return;
        MAIN.post(() -> {
            if (receiverRegistered) return;
            try {
                IntentFilter filter = new IntentFilter("android.intent.action.USER_SWITCHED");
                context.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent intent) {
                        if (pendingRecord != null) pendingRecord.finish(false);
                        clearOwnedTiles();
                        lastRequests.clear();
                    }
                }, filter);
                receiverRegistered = true;
            } catch (Throwable error) {
                logFailure("user receiver", error);
            }
        });
    }

    static void toggleTorch(Context context) {
        request(context, "flashlight", "手电筒", MiStepSystemActions::toggleRealTorch);
    }

    private static void toggleRealTorch() {
        if (android.app.ActivityManager.isUserAMonkey()) return;
        Object controller = flashlight.get();
        if (controller == null) {
            // The existing tile is also a verified reference to the same OEM controller.
            // Do not use its mState: an unlistened tile can retain the value from boot.
            controller = XposedHelpers.getObjectField(requireTile("flashlight"), "mFlashlightController");
            if (controller == null) throw new IllegalStateException("flashlight controller unavailable");
            flashlight = new WeakReference<>(controller);
        }
        boolean enabled = Boolean.TRUE.equals(XposedHelpers.callMethod(controller, "isEnabled"));
        if (!enabled && !Boolean.TRUE.equals(XposedHelpers.callMethod(controller, "isAvailable"))) {
            throw new IllegalStateException("system reports flashlight unavailable");
        }
        // The OEM controller owns battery/temperature/camera policy and its serial executor.
        XposedHelpers.callMethod(controller, "setFlashlight", !enabled);
    }

    static void takeScreenshot(Context context) {
        request(context, "screenshot", "截图", () -> clickTile("screenshot"));
    }

    static void toggleQuickSetting(Context context, String spec) {
        String label = quickSettingLabel(spec);
        request(context, spec, label, () -> clickTile(spec));
    }

    static final class QuickSettingState {
        final int state;
        final String label;
        final Object nativeState;
        QuickSettingState(int state, String label) { this(state, label, null); }
        QuickSettingState(int state, String label, Object nativeState) {
            this.state = state; this.label = label; this.nativeState = nativeState;
        }
    }

    /** Keep one listening token per visible control; the tile supplies actual controller state. */
    static Runnable observeQuickSetting(String spec, Consumer<QuickSettingState> changed) {
        String label = quickSettingLabel(spec);
        Object token = new Object();
        Object[] binding = new Object[2];
        boolean[] released = {false};
        MAIN.post(() -> {
            if (released[0]) return;
            try {
                Object tile = requireTile(spec);
                Class<?> type = XposedHelpers.findClass(
                        "com.android.systemui.plugins.qs.QSTile$Callback", tile.getClass().getClassLoader());
                Object callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                        (proxy, method, args) -> {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                            if ("toString".equals(method.getName())) return "MiStepQuickSetting-" + spec;
                            if ("getCallbackType".equals(method.getName())) return 0;
                            if ("onStateChanged".equals(method.getName()) && args != null && args.length > 0) {
                                Object state = args[0];
                                int value = XposedHelpers.getIntField(state, "state");
                                MAIN.post(() -> { if (!released[0]) changed.accept(new QuickSettingState(value, label, state)); });
                            }
                            return null;
                        });
                binding[0] = tile; binding[1] = callback;
                XposedHelpers.callMethod(tile, "addCallback", callback);
                XposedHelpers.callMethod(tile, "setListening", token, true);
                XposedHelpers.callMethod(tile, "refreshState");
            } catch (Throwable error) {
                logFailure("observe " + spec, error);
                if (!released[0]) changed.accept(new QuickSettingState(0, label));
            }
        });
        return () -> {
            released[0] = true;
            MAIN.post(() -> {
                if (binding[0] == null) return;
                try { XposedHelpers.callMethod(binding[0], "removeCallback", binding[1]); }
                catch (Throwable error) { logFailure("tile callback release", error); }
                try { XposedHelpers.callMethod(binding[0], "setListening", token, false); }
                catch (Throwable error) { logFailure("tile listener release", error); }
            });
        };
    }

    private static String quickSettingLabel(String spec) {
        if ("wifi".equals(spec)) return "Wi-Fi";
        if ("bt".equals(spec)) return "蓝牙";
        if ("cell".equals(spec)) return "数据";
        throw new IllegalArgumentException("unsupported control tile");
    }

    static void openVpn(Context context) {
        request(context, "vpn", "VPN", () -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_VPN_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        });
    }

    static void openScanner(Context context) {
        openScanner(context, null);
    }

    static void openScanner(Context context, View anchor) {
        request(context, "scanner-choice", "扫一扫", () -> MiStepScanner.choose(context, anchor, selected -> {
            if (selected == 2) openSystemScanner(context);
            else request(context, "scanner-app", "扫一扫", () -> MiStepScanner.launch(context, selected));
        }));
    }

    static void openCode(Context context, View anchor, int kind) {
        String label = MiStepCodes.title(kind);
        request(context, "code-choice-" + kind, label,
                () -> MiStepScanner.choose(context, anchor, label,
                        provider -> request(context, "code-open-" + kind, label,
                                () -> MiStepCodes.launch(context, kind, provider))));
    }

    private static void openSystemScanner(Context context) {
        request(context, "scanner", "扫一扫", () -> {
            // Let the system's configured QR provider choose its native scan activity.
            try {
                Object tile = requireTile("scanner");
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(tile, "isAvailable"))) {
                    clickAvailableTile(tile);
                    return;
                }
            } catch (Throwable ignored) {}
            Intent intent = new Intent("miui.intent.action.scanner");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(context.getPackageManager()) == null)
                intent = context.getPackageManager().getLaunchIntentForPackage("com.xiaomi.scanner");
            if (intent == null) throw new IllegalStateException("OS scanner provider unavailable");
            context.startActivity(intent);
        });
    }

    static void toggleScreenRecord(Context context) {
        request(context, "screenrecord", "录屏", () -> {
            Object currentHost = requireHost();
            Object oemTile = findTile(currentHost, OEM_RECORD_SPEC);
            if (oemTile != null) {
                Object state = XposedHelpers.callMethod(oemTile, "getState");
                if (state != null && XposedHelpers.getIntField(state, "state") != 0) {
                    clickAvailableTile(oemTile);
                    return;
                }
            }
            if (pendingRecord != null) return;
            if (oemTile == null) oemTile = ownedTiles.get(OEM_RECORD_SPEC);
            if (oemTile == null) {
                oemTile = XposedHelpers.callMethod(currentHost, "createTile", OEM_RECORD_SPEC);
                if (oemTile == null) throw new IllegalStateException("OEM recording tile unavailable");
                ownedTiles.put(OEM_RECORD_SPEC, oemTile);
            }
            PendingRecord pending = new PendingRecord(context, oemTile);
            pendingRecord = pending;
            pending.begin();
        });
    }

    /** A custom tile needs one real listening update before its first click. No polling. */
    private static final class PendingRecord {
        final Context context;
        final Object tile;
        final Object token = new Object();
        final Runnable timeout = () -> {
            logFailure("record init timeout", new IllegalStateException());
            finish(true);
        };
        Object callback;
        boolean finished;

        PendingRecord(Context context, Object tile) {
            this.context = context;
            this.tile = tile;
        }

        void begin() {
            try {
                Class<?> callbackType = XposedHelpers.findClass(
                        "com.android.systemui.plugins.qs.QSTile$Callback", tile.getClass().getClassLoader());
                callback = Proxy.newProxyInstance(callbackType.getClassLoader(),
                        new Class<?>[] { callbackType }, (proxy, method, args) -> {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                            if ("toString".equals(method.getName())) return "MiStepRecordingTileCallback";
                            if ("getCallbackType".equals(method.getName())) return 0;
                            if ("onStateChanged".equals(method.getName())) MAIN.post(this::dispatchIfReady);
                            return null;
                        });
                XposedHelpers.callMethod(tile, "addCallback", callback);
                XposedHelpers.callMethod(tile, "setListening", token, true);
                MAIN.postDelayed(timeout, 2500L);
            } catch (Throwable error) {
                logFailure("record init", error);
                finish(true);
            }
        }

        void dispatchIfReady() {
            if (finished || pendingRecord != this) return;
            try {
                Object state = XposedHelpers.callMethod(tile, "getState");
                if (state == null || XposedHelpers.getIntField(state, "state") == 0) return;
                KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguard != null && keyguard.isKeyguardLocked()) {
                    finish(false);
                    return;
                }
                clickAvailableTile(tile);
                finish(false);
            } catch (Throwable error) {
                logFailure("record click", error);
                finish(true);
            }
        }

        void finish(boolean failed) {
            if (finished) return;
            finished = true;
            if (pendingRecord == this) pendingRecord = null;
            MAIN.removeCallbacks(timeout);
            if (callback != null) {
                try { XposedHelpers.callMethod(tile, "removeCallback", callback); }
                catch (Throwable error) { logFailure("record callback", error); }
            }
            // Preserve the system's onClick binder dispatch before releasing our own listener.
            MAIN.postDelayed(() -> {
                try {
                    if (!Boolean.TRUE.equals(XposedHelpers.callMethod(tile, "isDestroyed"))) {
                        XposedHelpers.callMethod(tile, "setListening", token, false);
                    }
                }
                catch (Throwable error) { logFailure("record listener", error); }
            }, 1000L);
            if (failed) Toast.makeText(context,
                    "录屏暂不可用，请从系统控制中心操作", Toast.LENGTH_SHORT).show();
        }
    }

    private static void clickTile(String spec) {
        clickAvailableTile(requireTile(spec));
    }

    private static Object requireTile(String spec) {
        Object currentHost = requireHost();
        Object tile = findTile(currentHost, spec);
        if (tile == null) {
            tile = ownedTiles.get(spec);
            if (tile != null && Boolean.TRUE.equals(XposedHelpers.callMethod(tile, "isDestroyed"))) {
                ownedTiles.remove(spec);
                tile = null;
            }
            if (tile == null) {
                tile = XposedHelpers.callMethod(currentHost, "createTile", spec);
                if (tile == null) throw new IllegalStateException("system tile unavailable: " + spec);
                ownedTiles.put(spec, tile);
            }
        }
        return tile;
    }

    private static void clickAvailableTile(Object tile) {
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(tile, "isAvailable"))) {
            throw new IllegalStateException("system reports action unavailable");
        }
        // Null is the same Expandable argument used by MiuiQSHostAdapter.clickTile.
        // QSTileImpl.click posts onto the tile handler and preserves admin/lock policies.
        XposedHelpers.callMethod(tile, "click", new Object[] { null });
    }

    private static Object requireHost() {
        Object currentHost = host.get();
        if (currentHost == null) throw new IllegalStateException("SystemUI host not captured");
        return currentHost;
    }

    private static Object findTile(Object currentHost, String spec) {
        Object result = XposedHelpers.callMethod(currentHost, "getTiles");
        if (!(result instanceof Collection<?>)) return null;
        for (Object tile : (Collection<?>) result) {
            if (tile != null && spec.equals(XposedHelpers.callMethod(tile, "getTileSpec"))) return tile;
        }
        return null;
    }

    private interface Action { void run() throws Throwable; }

    private static void request(Context context, String key, String label, Action action) {
        if (context == null) return;
        MAIN.post(() -> {
            long now = SystemClock.uptimeMillis();
            Long previous = lastRequests.get(key);
            if (previous != null && now - previous < 350L) return;
            lastRequests.put(key, now);
            try {
                KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguard != null && keyguard.isKeyguardLocked()) {
                    Toast.makeText(context, "请先解锁再使用" + label, Toast.LENGTH_SHORT).show();
                    return;
                }
                action.run();
            } catch (Throwable error) {
                logFailure(key, error);
                Toast.makeText(context, label + "暂不可用，请从系统控制中心操作", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static void clearOwnedTiles() {
        for (Object tile : ownedTiles.values()) {
            try { XposedHelpers.callMethod(tile, "destroy"); }
            catch (Throwable error) { logFailure("destroy", error); }
        }
        ownedTiles.clear();
    }

    private static synchronized void logFailure(String action, Throwable error) {
        if (logsRemaining <= 0) return;
        logsRemaining--;
        XposedBridge.log("[Mi Step] system action " + action + " unavailable: "
                + error.getClass().getSimpleName());
    }
}
