package com.jianguo.mistep;

import android.content.res.Configuration;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Keep the active IME process and its independently rotated window in agreement. */
final class MiStepImeProcessConfiguration {
    private final IdentityHashMap<Object, Configuration> owned = new IdentityHashMap<>();

    void install(ClassLoader loader) {
        Class<?> process = XposedHelpers.findClass(
                "com.android.server.wm.WindowProcessController", loader);
        XposedBridge.hookAllMethods(process, "onConfigurationChanged", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Configuration main = owned.get(p.thisObject);
                if (main == null || p.args.length != 1
                        || !(p.args[0] instanceof Configuration)) return;
                p.args[0] = project((Configuration) p.args[0], main);
            }
        });
    }

    static Configuration project(Configuration physical, Configuration main) {
        Configuration next = new Configuration(physical);
        next.updateFrom(main);
        // Preserve the sequence owned by the real global configuration pipeline.
        XposedHelpers.setIntField(next, "seq", XposedHelpers.getIntField(physical, "seq"));
        return next;
    }

    void update(Object window, Object display, Object main, Consumer<String> log) {
        if (window == null || display == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return;
        Object attrs = XposedHelpers.getObjectField(window, "mAttrs");
        int type = XposedHelpers.getIntField(attrs, "type");
        if (type != 2011) return;
        // Target the actual IME service process, never every process in its package.
        Object session = XposedHelpers.getObjectField(window, "mSession");
        int pid = XposedHelpers.getIntField(session, "mPid");
        Object wms = XposedHelpers.getObjectField(display, "mWmService");
        Object root = XposedHelpers.getObjectField(wms, "mRoot");
        if (XposedHelpers.callMethod(root, "getCurrentImeWindow") != window) return;
        Object atm = XposedHelpers.getObjectField(wms, "mAtmService");
        Object processes = XposedHelpers.getObjectField(atm, "mProcessMap");
        Object process = XposedHelpers.callMethod(processes, "getProcess", pid);
        if (process == null || XposedHelpers.getIntField(process, "mUid") < 10000
                || !XposedHelpers.getBooleanField(process, "mHasImeService")) return;
        Configuration desired = null;
        Object token = XposedHelpers.getObjectField(window, "mToken");
        Object state = XposedHelpers.getObjectField(token, "mFixedRotationTransformState");
        if (main != null && state != null) {
            Object target = XposedHelpers.callMethod(display, "getImeInputTarget");
            Object targetWindow = target == null ? null : XposedHelpers.callMethod(target, "getWindowState");
            Object targetActivity = targetWindow == null ? null
                    : XposedHelpers.getObjectField(targetWindow, "mActivityRecord");
            if (targetActivity == main) desired = new Configuration((Configuration)
                    XposedHelpers.getObjectField(state, "mRotatedOverrideConfiguration"));
        }
        reconcile(process, desired, log);
    }

    private void reconcile(Object process, Configuration desired, Consumer<String> log) {
        for (Object previous : owned.keySet().toArray()) {
            if (previous != process) release(previous, log);
        }
        if (desired == null) { release(process, log); return; }
        Configuration previous = owned.get(process);
        if (desired.equals(previous)) return;
        owned.put(process, desired);
        try {
            dispatchFromParent(process);
            log.accept("main IME process configuration orientation=" + desired.orientation
                    + " pid=" + XposedHelpers.getIntField(process, "mPid"));
        } catch (Throwable error) {
            owned.remove(process);
            // Undo a partially applied native update before propagating the failure.
            try { dispatchFromParent(process); } catch (Throwable ignored) { }
            throw error;
        }
    }

    private void dispatchFromParent(Object process) {
        Object parent = XposedHelpers.callMethod(process, "getParent");
        if (parent == null) throw new IllegalStateException("IME process has no native configuration parent");
        XposedHelpers.callMethod(process, "onConfigurationChanged",
                XposedHelpers.callMethod(parent, "getConfiguration"));
    }

    private void release(Object process, Consumer<String> log) {
        if (owned.remove(process) == null) return;
        dispatchFromParent(process);
        log.accept("main IME process configuration restored");
    }

    void clear(Consumer<String> log) {
        for (Object process : owned.keySet().toArray()) {
            try { release(process, log); }
            catch (Throwable error) { log.accept("IME process configuration restore failed: " + error); }
        }
    }
}
