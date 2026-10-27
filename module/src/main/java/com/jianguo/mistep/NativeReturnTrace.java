package com.jianguo.mistep;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Temporary, opt-in bounded observation. Does not change surface operations. */
final class NativeReturnTrace {
    private static long expires;
    private static int count;
    private static String selected;
    private static final java.util.Map<Object, Boolean> transactions = new java.util.IdentityHashMap<>();

    static void install(ClassLoader cl, java.util.function.BooleanSupplier zoomed,
            java.util.Map<Object, Object> magnified, java.util.function.BooleanSupplier finalSnapshot) {
        try {
            Class<?> type = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl);
            Class<?> props = XposedHelpers.findClass("android.os.SystemProperties", cl);
            for (String method : new String[]{"setMatrix", "setPosition", "setScale", "reparent", "merge", "apply"}) {
                XposedBridge.hookAllMethods(type, method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!zoomed.getAsBoolean()) return;
                        try { observe(method, p.thisObject, p.args, props, magnified, finalSnapshot); }
                        catch (Throwable ignored) { }
                    }
                });
            }
        } catch (Throwable e) { XposedBridge.log("MiStepReturnTrace unavailable " + e); }
    }

    private static synchronized void observe(String method, Object tx, Object[] args, Class<?> props,
            java.util.Map<Object, Object> magnified, java.util.function.BooleanSupplier finalSnapshot) {
        long now = android.os.SystemClock.uptimeMillis();
        if (count >= 64 || (expires != 0 && now >= expires)) return;
        boolean transfer = method.equals("merge") || method.equals("apply");
        String surface = args.length == 0 ? "" : String.valueOf(args[0]);
        if (transfer) {
            if (!transactions.containsKey(tx)
                    && !(method.equals("merge") && transactions.containsKey(args[0]))) return;
        } else {
            if (!surface.contains("Task=")) return;
            if (selected == null) {
                String value = (String) XposedHelpers.callStaticMethod(props, "get", "debug.mistep.return_trace", "0");
                if (!value.matches("[1-9][0-9]{0,5}")) return;
                selected = "Task=" + value + "#";
            }
            if (!surface.contains(selected)) return;
            if (expires == 0) expires = now + 6000;
        }
        transactions.put(tx, Boolean.TRUE);
        StringBuilder detail = new StringBuilder();
        if (!transfer) for (java.util.Map.Entry<Object, Object> entry : magnified.entrySet()) {
            if (!entry.getValue().equals(args[0])) continue;
            Object task = entry.getKey();
            Object controller = XposedHelpers.getObjectField(task, "mTransitionController");
            detail.append(" mode=").append(XposedHelpers.callMethod(task, "getWindowingMode"))
                    .append(" lastSpec=").append(XposedHelpers.getObjectField(task, "mLastMagnificationSpec"));
            describe(detail, "collect", XposedHelpers.getObjectField(controller, "mCollectingTransition"), task);
            for (Object t : (Iterable<?>) XposedHelpers.getObjectField(controller, "mPlayingTransitions"))
                describe(detail, "playing", t, task);
            break;
        }
        int kept = 0;
        for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
            String n = f.getClassName();
            if (n.contains("NativeReturnTrace") || n.equals(Thread.class.getName())
                    || n.startsWith("dalvik.") || n.startsWith("java.lang.reflect.")
                    || n.startsWith("de.robv.") || n.startsWith("org.lsposed.") || !n.contains(".")) continue;
            if (kept++ == 13) break;
            detail.append('>').append(n).append('#').append(f.getMethodName()).append(':').append(f.getLineNumber());
        }
        String message = "MiStepReturnTrace n=" + (++count) + " at=" + now
                + " tx=" + System.identityHashCode(tx) + " final=" + finalSnapshot.getAsBoolean()
                + " " + method + " " + java.util.Arrays.toString(args) + detail;
        android.util.Log.i("MiStepReturnTrace", message);
        XposedBridge.log(message);
    }

    private static void describe(StringBuilder out, String label, Object transition, Object task) {
        if (transition == null) return;
        Object changes = XposedHelpers.getObjectField(transition, "mChanges");
        out.append(' ').append(label).append("Type=").append(XposedHelpers.getIntField(transition, "mType"));
        Object own = XposedHelpers.callMethod(changes, "get", task);
        if (own != null) out.append(" ownOld=").append(XposedHelpers.getIntField(own, "mWindowingMode"))
                .append('/').append(XposedHelpers.getObjectField(own, "mVisible"));
        int size = (Integer) XposedHelpers.callMethod(changes, "size");
        for (int i = 0; i < size; i++) {
            Object c = XposedHelpers.callMethod(changes, "valueAt", i);
            if (XposedHelpers.getIntField(c, "mWindowingMode") != 5) continue;
            out.append(" source=").append(XposedHelpers.getObjectField(c, "mContainer"))
                    .append(" oldVisible=").append(XposedHelpers.getObjectField(c, "mVisible"));
        }
    }
}
