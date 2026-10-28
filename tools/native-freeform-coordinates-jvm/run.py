import os
"""Focused admission and real-hook scope checks; no Android device needed."""
from pathlib import Path
import subprocess, tempfile

root = Path(__file__).resolve().parents[2]
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
sources = {
'android/view/MotionEvent.java': '''package android.view;
public class MotionEvent {
 public static final int ACTION_DOWN=0;
 public float x,y,rawX,rawY; public int display,action=2;
 public MotionEvent(float x,float y,float rx,float ry,int d){this.x=x;this.y=y;rawX=rx;rawY=ry;display=d;}
 public float getX(){return x;} public float getY(){return y;}
 public float getRawX(){return rawX;} public float getRawY(){return rawY;}
 public int getDisplayId(){return display;} public int getActionMasked(){return action;}
}''',
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
public class XC_MethodHook {
 public static class MethodHookParam {
  public Object[] args; public Throwable throwable;
  private final java.util.Map<String,Object> extras=new java.util.HashMap<>();
  public MethodHookParam(Object... a){args=a;}
  public void setObjectExtra(String k,Object v){extras.put(k,v);}
  public Object getObjectExtra(String k){return extras.get(k);}
 }
 protected void beforeHookedMethod(MethodHookParam p) throws Throwable{}
 protected void afterHookedMethod(MethodHookParam p) throws Throwable{}
 public void before(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}
 public void after(MethodHookParam p)throws Throwable{afterHookedMethod(p);}
}''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
public class XposedBridge {
 public static final java.util.Map<String,XC_MethodHook> hooks=new java.util.HashMap<>();
 public static void hookAllMethods(Class<?> c,String n,XC_MethodHook h){hooks.put(n,h);}
 public static void log(String s){if(s.contains("unavailable"))throw new AssertionError(s);}
}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public class XposedHelpers {
 public static Class<?> findClass(String n,ClassLoader cl){return Object.class;}
 public static void findAndHookMethod(Class<?> c,String n,Object... a){XposedBridge.hooks.put(n,(XC_MethodHook)a[a.length-1]);}
 public static Object callMethod(Object o,String n){try{return o.getClass().getMethod(n).invoke(o);}catch(Exception e){throw new RuntimeException(e);}}
}''',
'com/jiusis/mistep/NativeFreeformCoordinatesTest.java': '''package com.jiusis.mistep;
import android.view.MotionEvent;
import de.robv.android.xposed.*;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
public class NativeFreeformCoordinatesTest {
 static int checks;
 static XC_MethodHook touch,velocity;
 static void sample(float x,float y)throws Throwable{
  MethodHookParam p=new MethodHookParam((Object)new float[]{747,771});velocity.before(p);
  float[] a=(float[])p.args[0];if(a[0]!=x||a[1]!=y)throw new AssertionError("velocity space or scope leaked");checks++;
 }
 public static void main(String[] args)throws Throwable{
  NativeFreeformCoordinates.install(NativeFreeformCoordinatesTest.class.getClassLoader());
  touch=XposedBridge.hooks.get("handleMotionEvents");velocity=XposedBridge.hooks.get("update");
  sample(747,771);
  MotionEvent e=new MotionEvent(1000,200,747,771,0);
  MethodHookParam outer=new MethodHookParam(e,new Object(),new Object());touch.before(outer);
  sample(1000,200);e.x=1030;e.y=260;sample(1030,260);
  if(e.getRawX()!=747||e.getRawY()!=771)throw new AssertionError("raw event mutated");checks++;
  MethodHookParam other=new MethodHookParam(new MotionEvent(8,9,11,12,1),new Object(),new Object());
  touch.before(other);sample(747,771);touch.after(other);sample(1030,260);
  MethodHookParam nested=new MethodHookParam(new MotionEvent(55,66,77,88,0),new Object(),new Object());
  touch.before(nested);sample(55,66);nested.throwable=new RuntimeException("OEM failure");touch.after(nested);sample(1030,260);
  MethodHookParam noMonitor=new MethodHookParam(e,null,new Object());touch.before(noMonitor);sample(747,771);touch.after(noMonitor);
  MethodHookParam wide=new MethodHookParam((Object)new float[]{1,2,3});velocity.before(wide);
  if(((float[])wide.args[0])[0]!=1)throw new AssertionError("unrelated sample modified");checks++;
  touch.after(outer);sample(747,771);
  MethodHookParam identity=new MethodHookParam(new MotionEvent(90,130,90,130,0),new Object(),new Object());
  touch.before(identity);sample(90,130);touch.after(identity);sample(747,771);
  System.out.println("NativeFreeformCoordinates real-hook checks="+checks+" passed");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='mistep-native-coordinates-') as folder:
    work = Path(folder)
    files = []
    for name, content in sources.items():
        file = work / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding='utf-8')
        files.append(str(file))
    files += [str(root / 'module/src/main/java/com/jiusis/mistep' / (name + '.java'))
              for name in ('NativeFreeformPolicy', 'NativeFreeformCoordinates')]
    files.append(str(root / 'tests/NativeFreeformPolicyTest.java'))
    subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(work), *files], check=True)
    for name in ('NativeFreeformPolicyTest', 'NativeFreeformCoordinatesTest'):
        subprocess.run([str(jdk / 'java.exe'), '-cp', str(work), 'com.jiusis.mistep.' + name], check=True)
