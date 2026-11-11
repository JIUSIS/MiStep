"""Exercise the real scene-scale hook against a ROM with no DisplayContent.isAodShowing."""
from pathlib import Path
import os, subprocess, sys

root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
public class XC_MethodHook {
 public static class MethodHookParam {public Object thisObject;public Object result;public boolean error;
  public Object getResult(){return result;}public void setResult(Object x){result=x;}public boolean hasThrowable(){return error;}}
 protected void afterHookedMethod(MethodHookParam p) throws Throwable{}
 public void after(MethodHookParam p)throws Throwable{afterHookedMethod(p);}
}''',
'de/robv/android/xposed/XposedBridge.java':'package de.robv.android.xposed;public class XposedBridge{public static void log(String s){}}',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;
public class XposedHelpers {
 public static XC_MethodHook hook;
 public static Class<?>findClass(String n,ClassLoader l){return Object.class;}
 public static Object findAndHookMethod(Class<?>c,String n,Object...a){hook=(XC_MethodHook)a[a.length-1];return null;}
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
 public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
}''',
'com/jiusis/mistep/ShellCompat.java':'package com.jiusis.mistep;class ShellCompat{static boolean hasMethod(Class<?>c,String n,int a){return false;}}',
'com/jiusis/mistep/WallpaperScaleRegression.java':'''package com.jiusis.mistep;
import de.robv.android.xposed.*;
public class WallpaperScaleRegression {
 static int checks;static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
 public static class Info{public int state=2;}
 public static class Service{public Object mGlobalLock=new Object();public boolean locked;public boolean isKeyguardLocked(){return locked;}}
 public static class Display{public Service mWmService=new Service();public int id;public Info info=new Info();
  public int getDisplayId(){return id;}public Info getDisplayInfo(){return info;}}
 public static class Controller{public Object mDisplayContent;Controller(Object d){mDisplayContent=d;}}
 static Display active;
 static float apply(Controller c,float nativeScale,boolean hold,boolean error)throws Throwable{
  var p=new XC_MethodHook.MethodHookParam();p.thisObject=c;p.result=nativeScale;p.error=error;
  if(hold)synchronized(((Display)c.mDisplayContent).mWmService.mGlobalLock){XposedHelpers.hook.after(p);}
  else XposedHelpers.hook.after(p);
  return (Float)p.result;
 }
 public static void main(String[]args)throws Throwable{
  MiStepWallpaperScale.install(WallpaperScaleRegression.class.getClassLoader(),()->active);
  active=new Display();var d=active;var c=new Controller(d);
  check(apply(c,1.108f,true,false)==1f,"OS2 active wallpaper must remove extra 1.108 scene scale without absent AOD method");
  check(apply(c,1.108f,false,false)==1.108f,"must hold exact WM global lock");
  d.mWmService.locked=true;check(apply(c,1.108f,true,false)==1.108f,"keyguard retains native scale");d.mWmService.locked=false;
  for(int state:new int[]{0,1,3,4,5,6}){d.info.state=state;check(apply(c,1.108f,true,false)==1.108f,"non-ON display retains native scale "+state);}d.info.state=2;
  active=null;check(apply(c,1.108f,true,false)==1.108f,"inactive workspace unchanged");active=new Display();
  check(apply(c,1.108f,true,false)==1.108f,"other display instance unchanged");active=d;
  d.id=1;check(apply(c,1.108f,true,false)==1.108f,"secondary display unchanged");d.id=0;
  for(float scale:new float[]{1f,.9f,0f,-1f,Float.POSITIVE_INFINITY,Float.NaN})
   check(Float.compare(apply(c,scale,true,false),scale)==0,"invalid or non-extra native scale unchanged");
  check(apply(c,1.108f,true,true)==1.108f,"native exception not replaced");
  d.info=null;check(apply(c,1.108f,true,false)==1.108f,"unknown display state fails open");
  System.out.println("actual wallpaper scene-scale checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,content in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');paths.append(str(p))
paths.append(str(root/'module/src/main/java/com/jiusis/mistep/MiStepWallpaperScale.java'))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.WallpaperScaleRegression'],check=True)
