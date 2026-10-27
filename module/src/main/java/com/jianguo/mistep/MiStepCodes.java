package com.jianguo.mistep;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import de.robv.android.xposed.XposedHelpers;

/** Whitelisted native HyperOS payment/transit functions, dispatched in system_server. */
final class MiStepCodes {
    static final String ACTION = "com.jianguo.mistep.OPEN_CODE";
    static final int PAYMENT = 1, TRANSIT = 2;

    static String title(int kind) { return kind == PAYMENT ? "付款码" : "乘车码"; }

    static void launch(Context context, int kind, int provider) {
        if (kind != PAYMENT && kind != TRANSIT || provider < 0 || provider > 2) return;
        Intent request = new Intent(ACTION).setPackage("android")
                .putExtra("kind", kind).putExtra("provider", provider);
        context.sendOrderedBroadcast(request, android.Manifest.permission.WRITE_SECURE_SETTINGS,
                new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent result) {
                        if (getResultCode() != 1) Toast.makeText(context,
                                getResultCode() == MiStepWeChatShortcut.MISSING
                                        ? "请先在微信乘车码菜单中添加到桌面，再重试"
                                        : getResultCode() == MiStepWeChatShortcut.AMBIGUOUS
                                        ? "检测到多个微信乘车码快捷方式，请保留需要的桌面入口后重试"
                                        : "当前系统的" + title(kind) + "入口暂不可用", Toast.LENGTH_SHORT).show();
                    }
                }, new Handler(Looper.getMainLooper()), 0, null, null);
    }

    static int receive(Context context, ClassLoader loader, Intent intent) {
        int kind = intent.getIntExtra("kind", -1), provider = intent.getIntExtra("provider", -1);
        if (kind != PAYMENT && kind != TRANSIT || provider < 0 || provider > 2) return 0;
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (keyguard == null || keyguard.isKeyguardLocked()) return 0;
        if (kind == TRANSIT && provider == 0) return MiStepWeChatShortcut.open(context);
        try {
            Class<?> type = XposedHelpers.findClass("com.miui.server.input.util.ShortCutActionsUtils", loader);
            Object actions = XposedHelpers.callStaticMethod(type, "getInstance", context);
            String method = method(kind, provider);
            Object result;
            // Both system choices enter the OS wallet/card selector. The user chooses
            // their configured payment or transit card there; no card data is read.
            if (provider == 2) result = XposedHelpers.callMethod(actions, method, "mistep");
            else {
                try { result = XposedHelpers.callMethod(actions, method, "mistep", 0); }
                catch (NoSuchMethodError olderRom) { result = XposedHelpers.callMethod(actions, method, "mistep"); }
            }
            return Boolean.TRUE.equals(result) ? 1 : 0;
        } catch (Throwable error) {
            de.robv.android.xposed.XposedBridge.log("[Mi Step] native code entry unavailable: " + error);
            return 0;
        }
    }

    static String method(int kind, int provider) {
        if (kind != PAYMENT && kind != TRANSIT || provider < 0 || provider > 2)
            throw new IllegalArgumentException("unsupported code entry");
        if (provider == 2) return "launchMiPay";
        if (kind == PAYMENT) return provider == 0 ? "launchWeixinPaymentCode" : "launchAlipayPaymentCode";
        return provider == 0 ? "launchWeixinBusCode" : "launchAlipayBusCode";
    }
}
