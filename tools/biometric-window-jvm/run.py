from pathlib import Path
import os, subprocess, sys
root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
sources={
"android/view/WindowManager.java":r'''package android.view;
public interface WindowManager { public static class LayoutParams {
 public int type=2009,x=0,y=0,width=-1,height=-1,flags=17309698,privateFlags=16;
 public float dimAmount=.4f;public String packageName;private CharSequence title="com.android.systemui";
 public void setTitle(CharSequence t){title=t;}public CharSequence getTitle(){return title;}
}}''',
"android/view/Window.java":r'''package android.view;
public class Window {public WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();public int updates;
 public WindowManager.LayoutParams getAttributes(){return attrs;}
 public void setAttributes(WindowManager.LayoutParams a){attrs=a;updates++;}
}''',
"de/robv/android/xposed/XC_MethodHook.java":r'''package de.robv.android.xposed;
public class XC_MethodHook {public static class MethodHookParam {
 public Object thisObject,result;public boolean failed;public Object getResult(){return result;}
 public boolean hasThrowable(){return failed;}
}
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
 protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
 public void before(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}
 public void after(MethodHookParam p)throws Throwable{afterHookedMethod(p);}
}''',
"de/robv/android/xposed/XposedBridge.java":r'''package de.robv.android.xposed;
import java.util.*;public class XposedBridge {
 public static Map<String,XC_MethodHook> hooks=new HashMap<>();public static boolean failRegistration;public static int failures;
 public static void hookAllMethods(Class<?> c,String m,XC_MethodHook h){if(failRegistration){failures++;throw new LinkageError("ROM method unavailable");}hooks.put(m,h);}
 public static void log(String m){}
}''',
"de/robv/android/xposed/XposedHelpers.java":r'''package de.robv.android.xposed;
public class XposedHelpers {
 public static Class<?> target;public static Class<?> findClassIfExists(String n,ClassLoader l){
 if(!n.equals("com.android.systemui.biometrics.AuthContainerView"))throw new AssertionError(n);return target;}
 public static Object getObjectField(Object o,String n)throws Exception{return o.getClass().getField(n).get(o);}
 public static Object callMethod(Object o,String n,Object...args)throws Exception{return o.getClass().getMethod(n).invoke(o);}
}''',
"com/jiusis/mistep/BiometricWindowRegression.java":r'''package com.jiusis.mistep;
import android.view.*;import de.robv.android.xposed.*;
public class BiometricWindowRegression {
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 public static class NativeContainer {public Object mAlertDialog;}
 public static class NativeDialog {public Window window=new Window();public Window getWindow(){return window;}}
 static WindowManager.LayoutParams attrs(int type,String pkg){WindowManager.LayoutParams p=new WindowManager.LayoutParams();p.type=type;p.packageName=pkg;return p;}
 static void unchanged(WindowManager.LayoutParams a){check(a.x==0&&a.y==0&&a.width==-1&&a.height==-1,"OEM geometry changed");
 check(a.flags==17309698&&a.privateFlags==16&&a.dimAmount==.4f,"OEM auth/security flags changed");}
 public static void main(String[]args)throws Throwable{
  XposedHelpers.target=null;MiStepBiometricWindow.install(null);check(XposedBridge.hooks.isEmpty(),"missing native class changed");
  XposedHelpers.target=NativeContainer.class;MiStepBiometricWindow.install(null);
  check(XposedBridge.hooks.size()==2,"native factory and OEM first-show hook missing");
  for(String pkg:new String[]{null,"com.android.systemui"}){
   XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();WindowManager.LayoutParams a=attrs(2009,pkg);p.result=a;
   XposedBridge.hooks.get("getLayoutParams").after(p);
   check(InputMagnificationPolicy.shouldExcludeFromMagnification(a.type,"com.android.systemui",a.getTitle()),"factory not physical");
   unchanged(a);
   NativeContainer c=new NativeContainer();NativeDialog d=new NativeDialog();d.window.attrs=attrs(2009,pkg);c.mAlertDialog=d;
   p=new XC_MethodHook.MethodHookParam();p.thisObject=c;
   XposedBridge.hooks.get("show$2").before(p);
   check(d.window.updates==1,"OEM window not identified before first show");
   check(InputMagnificationPolicy.shouldExcludeFromMagnification(2009,"com.android.systemui",d.window.attrs.getTitle()),"first native dialog frame would scale");
   unchanged(d.window.attrs);
  }
  for(WindowManager.LayoutParams a:new WindowManager.LayoutParams[]{attrs(2017,"com.android.systemui"),attrs(2009,"other.app")}){
   check(!MiStepBiometricWindow.identify(a),"unrelated native params admitted");check(a.getTitle().equals("com.android.systemui"),"foreign title changed");unchanged(a);
  }
  check(!MiStepBiometricWindow.identify(null),"null params admitted");
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.failed=true;p.result=attrs(2009,null);
  XposedBridge.hooks.get("getLayoutParams").after(p);
  check(((WindowManager.LayoutParams)p.result).getTitle().equals("com.android.systemui"),"failed native factory changed");
  p=new XC_MethodHook.MethodHookParam();p.thisObject=new NativeContainer();XposedBridge.hooks.get("show$2").before(p);
  p.thisObject=new Object();XposedBridge.hooks.get("show$2").before(p);
  p.result=null;XposedBridge.hooks.get("getLayoutParams").after(p);
  check(!InputMagnificationPolicy.shouldExcludeFromMagnification(2009,"com.android.systemui","com.android.systemui"),"all keyguard dialogs excluded");
  XposedBridge.hooks.clear();XposedBridge.failRegistration=true;
  MiStepBiometricWindow.install(null);
  check(XposedBridge.failures==1,"registration failure fixture did not execute");
  check(XposedBridge.hooks.isEmpty(),"failed registration fabricated a working hook");
  System.out.println("Native biometric window: "+checks+" checks passed");
 }
}'''
}
files=[]
for rel,text in sources.items():
 p=out/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding="utf-8");files.append(str(p))
j=Path(os.environ["MISTEP_JAVA_BIN"])
production=root/"module/src/main/java/com/jiusis/mistep"
subprocess.run([str(j/"javac.exe"),"-encoding","UTF-8","-d",str(out),*files,
 str(production/"MiStepBiometricWindow.java"),str(production/"InputMagnificationPolicy.java")],check=True)
subprocess.run([str(j/"java.exe"),"-cp",str(out),"com.jiusis.mistep.BiometricWindowRegression"],check=True)
