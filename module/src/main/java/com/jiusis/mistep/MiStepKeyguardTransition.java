package com.jiusis.mistep;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Retained TaskViews must not consume the native keyguard exit transition. */
final class MiStepKeyguardTransition {
    private static final int KEYGUARD_GOING_AWAY = 0x100;

    static void installNativeStart(ClassLoader loader, java.util.function.BiConsumer<Object, Object> prepare) {
        for (String name : new String[]{
                "com.android.systemui.keyguard.KeyguardService$1",
                "com.android.systemui.keyguard.KeyguardViewMediator$ExitTransition"}) {
            try {
                Class<?> adapter = XposedHelpers.findClass(name, loader);
                XposedBridge.hookAllMethods(adapter, "startAnimation", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (p.args.length != 4 || p.args[1] == null || p.args[2] == null) return;
                        try {
                            int flags = (Integer) XposedHelpers.callMethod(p.args[1], "getFlags");
                            if ((flags & KEYGUARD_GOING_AWAY) != 0) prepare.accept(p.args[1], p.args[2]);
                        } catch (Throwable unavailable) {
                            XposedBridge.log("MiStepProbe: native retained start unavailable: " + unavailable);
                        }
                    }
                });
            } catch (Throwable unavailable) {
                XposedBridge.log("MiStepProbe: native retained adapter unavailable: " + name);
            }
        }
    }

    static List<XC_MethodHook.Unhook> install(Class<?> transitions, Predicate<Object> retainedOwner) {
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            for (String name : new String[]{"handleRequest", "startAnimation"}) {
                boolean request = "handleRequest".equals(name);
                hooks.addAll(XposedBridge.hookAllMethods(transitions, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if ((request ? param.args.length != 2
                                : param.args.length != 5 && param.args.length != 6)
                                || !retainedOwner.test(param.thisObject) || param.args[1] == null) return;
                        final int flags;
                        try {
                            flags = (Integer) XposedHelpers.callMethod(param.args[1], "getFlags");
                        } catch (Throwable unavailable) { return; }
                        if ((flags & KEYGUARD_GOING_AWAY) == 0) return;
                        // Before TaskView can claim, reparent, apply, or finish this transition.
                        param.setResult(request ? null : Boolean.FALSE);
                        XposedBridge.log("MiStepProbe: retained keyguard exit kept native " + name);
                    }
                }));
            }
            return hooks;
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            throw error;
        }
    }
}
