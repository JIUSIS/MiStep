package com.jiusis.mistep;

import android.view.MotionEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** A hosted upper task does not own the native caption in the magnified main area. */
final class MiStepTopNativeCaption {
    private static final java.util.Set<Object> controllers = java.util.Collections.newSetFromMap(
            new java.util.WeakHashMap<Object, Boolean>());

    static void install(ClassLoader loader) {
        try {
            Class<?> dot=XposedHelpers.findClass(
                    "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDot",loader);
            XposedHelpers.findAndHookMethod(dot,"isTouchedInCaption",MotionEvent.class,new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if(!owned(p.thisObject))return;
                    p.setResult(false);
                    if(((MotionEvent)p.args[0]).getActionMasked()==MotionEvent.ACTION_DOWN)
                        XposedBridge.log("MiStepProbe: upper native caption hit excluded");
                }
            });
            XposedHelpers.findAndHookMethod(dot,"handleCaptionClicked",new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if(!owned(p.thisObject))return;
                    p.setResult(null);
                    XposedBridge.log("MiStepProbe: upper native caption menu excluded");
                }
            });
            XposedHelpers.findAndHookMethod(dot,"needCaption",new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    // Let native relayout release both its dot surface and caption insets.
                    // A returned task no longer matches this exact TOP binding.
                    if(owned(p.thisObject))p.setResult(false);
                }
            });
            Class<?> controller=XposedHelpers.findClass(
                    "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationController",loader);
            XposedBridge.hookAllConstructors(controller,new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    synchronized(controllers) {controllers.add(p.thisObject);}
                }
            });
            XposedBridge.log("MiStepProbe: upper native caption scope installed");
        } catch(Throwable error) {
            XposedBridge.log("MiStepProbe: upper native caption hooks unavailable: "+error);
        }
    }

    /** Initial OEM decoration can precede confirmation of the hosted TOP binding. */
    static void refresh() {
        Object[] current;
        synchronized(controllers) {current=controllers.toArray();}
        for(Object controller:current) {
            if(!needsRefresh(controller))continue;
            try {
                Object executor=XposedHelpers.getObjectField(controller,"mMainExecutor");
                XposedHelpers.callMethod(executor,"execute",(Runnable)()->{
                    if(!needsRefresh(controller))return;
                    try {
                        XposedHelpers.callMethod(controller,"relayout",(Object)null);
                        XposedBridge.log("MiStepProbe: upper native caption refreshed");
                    } catch(Throwable error) {
                        XposedBridge.log("MiStepProbe: upper native caption refresh: "+error);
                    }
                });
            } catch(Throwable error) {
                XposedBridge.log("MiStepProbe: upper native caption queue: "+error);
            }
        }
    }

    private static boolean needsRefresh(Object controller) {
        try {
            return !Boolean.TRUE.equals(XposedHelpers.getObjectField(controller,"mIsClosed"))
                    && owned(controller)
                    && XposedHelpers.getObjectField(
                            XposedHelpers.getObjectField(controller,"mTopDecoration"),"mDecorationInsets")!=null;
        } catch(Throwable ignored) {return false;}
    }

    private static boolean owned(Object dot) {
        try {
            return MiStepHost.ownsTopNativeCaption(XposedHelpers.getObjectField(dot,"mRunningTaskInfo"));
        } catch(Throwable ignored) {return false;}
    }
}
