package com.jiusis.mistep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Deliver the main environment through Android's native process configuration path. */
final class MiStepMainUiConfiguration {
    static final String ACTION = "com.jiusis.mistep.MAIN_UI_CONFIGURATION";
    private static Configuration main;
    private static Configuration physical;
    private static boolean delivering;

    private static Configuration effective(Configuration base) {
        Configuration next = new Configuration(base);
        if (main != null) next.updateFrom(main);
        return next;
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> nativeController = XposedHelpers.findClass("android.app.ConfigurationController", loader);
            XposedBridge.hookAllMethods(nativeController, "handleConfigurationChangedInner", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (delivering || !(p.args[0] instanceof Configuration)) return;
                    Configuration incoming = (Configuration) p.args[0];
                    physical = new Configuration(incoming);
                    if (main != null) {
                        Configuration next = effective(incoming);
                        XposedHelpers.setIntField(next, "seq", XposedHelpers.getIntField(incoming, "seq"));
                        p.args[0] = next;
                    }
                }
            });
            Class<?> service = XposedHelpers.findClass("com.android.systemui.SystemUIService", loader);
            XposedBridge.hookAllMethods(service, "onCreate", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Context context = (Context) p.thisObject;
                    physical = new Configuration(context.getResources().getConfiguration());
                    context.registerReceiver(new BroadcastReceiver() {
                        @Override public void onReceive(Context c, Intent intent) {
                            main = intent.getParcelableExtra("configuration", Configuration.class);
                            Configuration next = effective(physical);
                            // Native isOtherSeqNewer accepts seq=0 for a local forced configuration.
                            XposedHelpers.setIntField(next, "seq", 0);
                            delivering = true;
                            try {
                                Object thread = XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass("android.app.ActivityThread", loader), "currentActivityThread");
                                Object controller = XposedHelpers.getObjectField(thread, "mConfigurationController");
                                XposedHelpers.callMethod(controller, "handleConfigurationChanged", next);
                                XposedBridge.log("MiStep: main native SystemUI process configuration orientation="
                                        + next.orientation + " resources=" + context.getResources().getConfiguration().orientation);
                            } catch (Throwable error) {
                                XposedBridge.log("MiStep: main native process configuration failed: " + error);
                            } finally { delivering = false; }
                        }
                    }, new IntentFilter(ACTION), android.Manifest.permission.WRITE_SECURE_SETTINGS,
                            null, Context.RECEIVER_EXPORTED);
                }
            });
        } catch (Throwable error) { XposedBridge.log("MiStep: main SystemUI configuration bridge unavailable: " + error); }
    }
}
