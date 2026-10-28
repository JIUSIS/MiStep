package com.jiusis.mistep;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Preserve native recents filters, except TaskView's topmost flag for our exact live tasks. */
final class MiStepRecents {
    private static final ThreadLocal<Query> QUERY = new ThreadLocal<>();
    private static final class Query {
        final Object task, lease;
        Query(Object task, Object lease) { this.task = task; this.lease = lease; }
    }
    private static final String SAVED = "mistep.recents.previous";
    private static final String ENTERED = "mistep.recents.entered";

    static void install(ClassLoader loader) {
        XC_MethodHook.Unhook visibility = null;
        try {
            Class<?> task = XposedHelpers.findClass("com.android.server.wm.Task", loader);
            Class<?> recent = XposedHelpers.findClass("com.android.server.wm.RecentTasks", loader);
            visibility = XposedHelpers.findAndHookMethod(recent, "isVisibleRecentTask", task, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra(SAVED, QUERY.get());
                    p.setObjectExtra(ENTERED, Boolean.TRUE);
                    QUERY.remove();
                    if (p.args.length == 1) {
                        Object lease = MiStepDesktopLaunchBridge.recentsLease(p.args[0]);
                        if (lease != null) QUERY.set(new Query(p.args[0], lease));
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(p.getObjectExtra(ENTERED))) return;
                    Query previous = (Query) p.getObjectExtra(SAVED);
                    if (previous == null) QUERY.remove(); else QUERY.set(previous);
                }
            });
            XposedHelpers.findAndHookMethod(task, "isAlwaysOnTopWhenVisible", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Query query = QUERY.get();
                    // Do not build TaskInfo in this hook: that can query this same property recursively.
                    if (query != null && query.task == p.thisObject
                            && MiStepDesktopLaunchBridge.recentsLeaseAlive(query.lease))
                        p.setResult(false);
                }
            });
            XposedBridge.log("MiStepProbe: exact side-task recents filter installed");
        } catch (Throwable error) {
            if (visibility != null) visibility.unhook();
            XposedBridge.log("MiStepProbe: recents hook unavailable: " + error);
        }
    }
}
