package com.jiusis.mistep;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationTargetException;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Fixed transit target, launched through WeChat's normal in-process appbrand entry. */
final class MiStepWeChatNative {
    static final String PACKAGE = "com.tencent.mm";
    static final String ACTION = "com.jiusis.mistep.WECHAT_TRANSIT_NATIVE";
    static final String ISSUED = "issuedAt";
    private static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    private static final String USERNAME = "gh_3cf62f4f1d52";
    private static final String PATH = "pages/qrcode/index?noback=1&channel=xiaomi_fyp";
    private static boolean registered;
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static long accepted;

    private MiStepWeChatNative() {}

    static boolean fresh(long issued, long now) {
        return issued > 0 && now >= issued && now - issued <= 6000L;
    }

    static Method launchMethod(Class<?> proxy, Class<?> stat, Class<?> referrer, Class<?> optional) {
        Class<?>[] signature = {Context.class, String.class, String.class, String.class,
                int.class, int.class, stat, referrer, optional};
        Method found = null;
        for (Method candidate : proxy.getDeclaredMethods()) {
            if (!Modifier.isStatic(candidate.getModifiers()) || candidate.getReturnType() != boolean.class
                    || !java.util.Arrays.equals(candidate.getParameterTypes(), signature)) continue;
            if (found != null) throw new IllegalStateException("ambiguous appbrand launch entry");
            found = candidate;
        }
        if (found == null) throw new IllegalStateException("appbrand launch signature unavailable");
        found.setAccessible(true);
        return found;
    }

    static Object launchStat(Class<?> type) throws Exception {
        Object stat = type.getDeclaredConstructor().newInstance();
        Field scene;
        try { scene = type.getDeclaredField("scene"); }
        catch (NoSuchFieldException obfuscated) { scene = type.getDeclaredField("f"); }
        if (scene.getType() != int.class || Modifier.isStatic(scene.getModifiers()))
            throw new IllegalStateException("appbrand scene shape changed");
        scene.setAccessible(true);
        scene.setInt(stat, 1001);
        return stat;
    }

    static boolean homeReady(Object launcher) throws Exception {
        Object home = launcher.getClass().getMethod("getHomeUI").invoke(launcher);
        if (home == null) return false;
        // Verified against this WeChat's LauncherUI.onResume: HomeUI.a is set only
        // after the logged-in account is ready and HomeUI creation begins.
        Field ready = home.getClass().getDeclaredField("a");
        if (ready.getType() != boolean.class || Modifier.isStatic(ready.getModifiers()))
            throw new IllegalStateException("WeChat home readiness shape changed");
        ready.setAccessible(true);
        return ready.getBoolean(home);
    }

    static void install() {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!registered && param.thisObject instanceof Application app
                            && PACKAGE.equals(app.getPackageName())) register(app);
                } catch (Throwable error) {
                    XposedBridge.log("MiStepProbe: WeChat transit bridge registration failed: " + error);
                }
            }
        });
    }

    private static void register(Application app) {
        Handler main = new Handler(Looper.getMainLooper());
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) { resumed = new WeakReference<>(activity); }
            @Override public void onActivityPaused(Activity activity) {
                if (resumed.get() == activity) resumed.clear();
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                try {
                    boolean probeOnly = intent.getBooleanExtra("probeOnly", false);
                    long issued = intent.getLongExtra(ISSUED, 0);
                    Activity activity = resumed.get();
                    if (!probeOnly) {
                        if (!fresh(issued, SystemClock.elapsedRealtime())) { setResultCode(4); return; }
                        if (accepted == issued) { setResultCode(1); return; }
                        if (activity == null || activity.isFinishing()) { setResultCode(5); return; }
                        if (!"com.tencent.mm.ui.LauncherUI".equals(activity.getClass().getName())) {
                            String name = activity.getClass().getName();
                            setResultCode(name.contains(".account.") || name.endsWith(".WelcomeUI")
                                    ? 2 : 5);
                            return;
                        }
                        if (!activity.hasWindowFocus() || !homeReady(activity)) {
                            setResultCode(5); return;
                        }
                    }
                    ClassLoader loader = app.getClassLoader();
                    Class<?> stat = Class.forName("com.tencent.mm.plugin.appbrand.report.AppBrandStatObject", false, loader);
                    Method method = launchMethod(Class.forName("com.tencent.mm.plugin.appbrand.launching.AppBrandLaunchProxyUI", false, loader),
                            stat, Class.forName("com.tencent.mm.plugin.appbrand.config.AppBrandLaunchReferrer", false, loader),
                            Class.forName("com.tencent.mm.modelappbrand.LaunchParamsOptional", false, loader));
                    Object stats = launchStat(stat);
                    if (probeOnly) {
                        setResult(3, "native appbrand entry=" + method.getName() + "; fixed transit target; permission protected", null);
                        return;
                    }
                    // username, appId (resolved by WeChat), path, release type, version,
                    // native scene, optional referrer and optional launch parameters.
                    boolean started = Boolean.TRUE.equals(method.invoke(null,
                            activity, USERNAME, null, PATH, 0, 0, stats, null, null));
                    if (started) accepted = issued;
                    XposedBridge.log("MiStepProbe: WeChat native transit accepted=" + started);
                    setResultCode(started ? 1 : 4);
                } catch (Throwable error) {
                    Throwable cause = error;
                    while (cause instanceof InvocationTargetException invocation && invocation.getCause() != null)
                        cause = invocation.getCause();
                    XposedBridge.log("MiStepProbe: WeChat native transit unavailable: " + cause);
                    XposedBridge.log(cause);
                    setResultCode(4);
                }
            }
        }, new IntentFilter(ACTION), PERMISSION, main, Context.RECEIVER_EXPORTED);
        registered = true;
        XposedBridge.log("MiStepProbe: WeChat fixed transit native bridge build=2.9.163 registered; sender requires WRITE_SECURE_SETTINGS");
    }

    /** Run in system_server after bringing the current user's normal WeChat activity forward. */
    static void request(Context context, UserHandle user) {
        Handler main = new Handler(Looper.getMainLooper());
        long issued = SystemClock.elapsedRealtime();
        Intent request = new Intent(ACTION).setPackage(PACKAGE).putExtra(ISSUED, issued);
        class Attempt implements Runnable {
            int count;
            @Override public void run() {
                if (!fresh(issued, SystemClock.elapsedRealtime())) return;
                count++;
                BroadcastReceiver result = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent intent) {
                        int code = getResultCode();
                        if (code == 1) return;
                        if ((code == 0 || code == 5) && count < 6) {
                            main.postDelayed(Attempt.this, 500L); return;
                        }
                        String text = code == 2 ? "请先登录微信，再打开乘车码"
                                : code == 0 ? "请在 LSPosed 的 Mi Step 作用域中启用微信"
                                : code == 5 ? "微信尚未就绪，请返回微信首页后重试"
                                : "当前微信的乘车码入口暂不可用";
                        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
                    }
                };
                // The receiver restricts senders. WeChat itself need not hold the sender permission.
                try {
                    XposedHelpers.callMethod(context, "sendOrderedBroadcastAsUser", request, user,
                            null, result, main, 0, null, null);
                } catch (Throwable error) {
                    XposedBridge.log("MiStepProbe: WeChat transit dispatch failed: " + error);
                }
            }
        }
        main.postDelayed(new Attempt(), 350L);
    }
}
