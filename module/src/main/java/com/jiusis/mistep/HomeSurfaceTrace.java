package com.jiusis.mistep;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** One-shot, opt-in observation of Home geometry. Never changes a transaction. */
final class HomeSurfaceTrace {
    private static long expires;
    private static int count;
    private static final java.util.Map<Object, Boolean> transactions = new java.util.WeakHashMap<>();

    static void install(ClassLoader cl) {
        try {
            Class<?> type = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
            for (String method : new String[]{"setMatrix", "setPosition", "setScale", "reparent", "merge", "apply"}) {
                XposedBridge.hookAllMethods(type, method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try { observe(method, p.thisObject, p.args); }
                        catch (Throwable ignored) { /* Observation must not affect playback. */ }
                    }
                });
            }
        } catch (Throwable error) { XposedBridge.log("MiStepHomeTrace unavailable: " + error); }
    }

    private static synchronized void observe(String method, Object tx, Object[] args) {
        if (count >= 180 || (expires != 0 && android.os.SystemClock.uptimeMillis() >= expires)) return;
        boolean transfer = method.equals("merge") || method.equals("apply");
        String surface = args.length == 0 ? "" : String.valueOf(args[0]);
        if (transfer) {
            if (!transactions.containsKey(tx)
                    && !(method.equals("merge") && transactions.containsKey(args[0]))) return;
        } else if (!(surface.contains("Task=1#") || surface.contains("Task=2#")
                )) return;
        if (expires == 0) {
            Object armed = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.SystemProperties", null),
                    "get", "debug.mistep.home_trace", "0");
            if (!"1".equals(armed)) return;
            expires = android.os.SystemClock.uptimeMillis() + 5000;
        }
        transactions.put(tx, Boolean.TRUE);
        StringBuilder stack = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (name.contains("HomeSurfaceTrace") || name.equals(Thread.class.getName())
                    || name.startsWith("dalvik.") || name.startsWith("java.lang.reflect.")
                    || name.startsWith("de.robv.") || name.startsWith("org.lsposed.")
                    || name.startsWith("android.app.") || !name.contains(".")) continue;
            if (kept++ == 9) break;
            stack.append(name).append('#').append(frame.getMethodName()).append(':')
                    .append(frame.getLineNumber()).append('>');
        }
        String details = method + " " + java.util.Arrays.toString(args) + " " + stack;
        count++;
        String message = "MiStepHomeTrace n=" + count + " at=" + android.os.SystemClock.uptimeMillis()
                + " pid=" + android.os.Process.myPid() + " tx=" + System.identityHashCode(tx)
                + " " + details;
        android.util.Log.i("MiStepHomeTrace", message);
        XposedBridge.log(message);
    }
}
