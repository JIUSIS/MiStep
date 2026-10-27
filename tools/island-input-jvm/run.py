import os
"""Exercise the actual production Xposed callback against small Android/Xposed fakes."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'build/tests/island-input-jvm'
SRC = OUT / 'src'
CLASSES = OUT / 'classes'
JDK = Path(os.environ["MISTEP_JAVA_BIN"])
sources = {
'android/content/Context.java': '''package android.content;
public class Context { public Object getContentResolver(){ return this; } }
''',
'android/provider/Settings.java': '''package android.provider;
public class Settings { public static class Secure { public static int oneHand;
 public static boolean fail; public static int getInt(Object resolver,String key,int def){
 if(fail)throw new IllegalStateException(); return oneHand; } } }
''',
'android/view/MotionEvent.java': '''package android.view;
public class MotionEvent {
 public static final int ACTION_DOWN=0; public int action,display; public float x,y,rawX,rawY;
 public long eventTime=2330825,downTime=2330825; public boolean recycled;
 public static boolean failObtain,failLocation; public static MotionEvent lastCopy;
 public static MotionEvent obtain(MotionEvent e){ if(failObtain)throw new IllegalStateException();
 MotionEvent c=new MotionEvent();c.action=e.action;c.display=e.display;c.x=e.x;c.y=e.y;
 c.rawX=e.rawX;c.rawY=e.rawY;c.eventTime=e.eventTime;c.downTime=e.downTime;
 return lastCopy=c; }
 public int getActionMasked(){return action;} public int getDisplayId(){return display;}
 public float getX(){return x;}public float getY(){return y;}
 public float getRawX(){return rawX;}public float getRawY(){return rawY;}
 public void setLocation(float nx,float ny){if(failLocation)throw new IllegalStateException();x=nx;y=ny;}
 public void recycle(){if(recycled)throw new AssertionError("double recycle");recycled=true;}
}
''',
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
import java.util.HashMap;import java.util.Map;
public class XC_MethodHook {
 public static class MethodHookParam { public Object[] args;private final Map<String,Object> extra=new HashMap<>();
 public void setObjectExtra(String k,Object v){extra.put(k,v);}public Object getObjectExtra(String k){return extra.get(k);} }
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
 protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
}
''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
public class XposedBridge {public static XC_MethodHook callback;public static String name;
 public static void hookAllMethods(Class<?> c,String n,XC_MethodHook h){name=n;callback=h;}
 public static void log(String text){}
 public static void before(XC_MethodHook.MethodHookParam p)throws Throwable{callback.beforeHookedMethod(p);}
 public static void after(XC_MethodHook.MethodHookParam p)throws Throwable{callback.afterHookedMethod(p);}
}
''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public class XposedHelpers {public static String selected;
 public static Class<?> findClass(String n,ClassLoader c){selected=n;return Object.class;}
 public static Object callMethod(Object o,String n){return ((android.view.MotionEvent)o).getDisplayId();}
}
''',
'com/jianguo/mistep/MiStepHost.java': '''package com.jianguo.mistep;
class MiStepHost {static float[] zoom={.74654907f,661f,0f};
 static float[] activeIslandMonitorTransform(){return zoom;}}
''',
'com/jianguo/mistep/IslandMonitorHookTest.java': '''package com.jianguo.mistep;
import android.view.MotionEvent;import android.content.Context;import android.provider.Settings;
import de.robv.android.xposed.*;
public class IslandMonitorHookTest {
 static int checks;static boolean outsideCollapse;static MotionEvent original;
 public static void main(String[] args)throws Throwable{
  MiStepIslandMonitorInput.install(IslandMonitorHookTest.class.getClassLoader(),Context::new);
  check(XposedHelpers.selected.equals("com.android.systemui.statusbar.notification.island.DynamicIslandGestureMonitor$listener$1"));
  check(XposedBridge.name.equals("onPointerEvent"));
  original=event(0,0);XC_MethodHook.MethodHookParam p=param(original);XposedBridge.before(p);
  MotionEvent c=(MotionEvent)p.args[0];check(c!=original);near(c.x,991.22754f);near(c.y,282.6338f);
  near(c.rawX,740f);near(c.rawY,872f);check(c.eventTime==original.eventTime&&c.downTime==original.downTime);
  // Same ROM outside-collapse boundary, with an UP 200ms later instead of adb's 5ms tap.
  outsideCollapse=!inExpanded(c.x,c.y);check(!outsideCollapse);check(!inExpanded(original.x,original.y));
  XposedBridge.after(p);check(p.args[0]==original);check(c.recycled&&!original.recycled);
  near(original.x,740);near(original.y,872);near(original.rawX,740);near(original.rawY,872);
  for(int action:new int[]{1,2,3})unchanged(event(action,0));
  unchanged(event(0,1));
  MiStepHost.zoom=null;unchanged(event(0,0));MiStepHost.zoom=new float[]{.74654907f,661f,0f};
  Settings.Secure.oneHand=1;unchanged(event(0,0));Settings.Secure.oneHand=0;
  Settings.Secure.fail=true;unchanged(event(0,0));Settings.Secure.fail=false;
  MotionEvent.failObtain=true;unchanged(event(0,0));MotionEvent.failObtain=false;
  MotionEvent.failLocation=true;unchanged(event(0,0));check(MotionEvent.lastCopy.recycled);MotionEvent.failLocation=false;
  MotionEvent e=event(0,0);e.x=991.22754f;e.y=282.6338f;unchanged(e);
  e=event(0,0);p=param(e);XposedBridge.before(p);c=(MotionEvent)p.args[0];
  // Xposed runs the after callback even when the original plugin invocation throws.
  try{throw new IllegalStateException("plugin");}catch(IllegalStateException expected){}
  finally{XposedBridge.after(p);}check(p.args[0]==e);check(c.recycled);check(!e.recycled);
  p=param(null);XposedBridge.before(p);XposedBridge.after(p);check(p.args[0]==null);
  System.out.println("IslandMonitorHookTest PASS "+checks);
 }
 static boolean inExpanded(float x,float y){return x>=56&&x<1143&&y>=156&&y<407;}
 static MotionEvent event(int action,int display){MotionEvent e=new MotionEvent();
 e.action=action;e.display=display;e.x=e.rawX=740;e.y=e.rawY=872;return e;}
 static XC_MethodHook.MethodHookParam param(MotionEvent e){XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{e};return p;}
 static void unchanged(MotionEvent e)throws Throwable{XC_MethodHook.MethodHookParam p=param(e);
 XposedBridge.before(p);check(p.args[0]==e);XposedBridge.after(p);check(p.args[0]==e);check(!e.recycled);}
 static void near(float a,float b){check(Math.abs(a-b)<.001f);}
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
}
''',
}
paths = []
for name, content in sources.items():
    p = SRC / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding='utf8')
    paths.append(str(p))
prod = ROOT / 'module/src/main/java/com/jianguo/mistep'
paths += [str(prod / n) for n in ['IslandMonitorInputPolicy.java', 'MiStepIslandMonitorInput.java']]
CLASSES.mkdir(parents=True, exist_ok=True)
compile_result = subprocess.run([str(JDK / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17',
                                 '-d', str(CLASSES), *paths], capture_output=True)
(OUT / 'javac.log').write_bytes(compile_result.stdout + compile_result.stderr)
if compile_result.returncode:
    print(compile_result.stderr.decode('gbk', 'replace')[:3000])
    sys.exit(compile_result.returncode)
test = subprocess.run([str(JDK / 'java.exe'), '-cp', str(CLASSES),
                       'com.jianguo.mistep.IslandMonitorHookTest'], capture_output=True)
(OUT / 'tests.log').write_bytes(test.stdout + test.stderr)
print((test.stdout + test.stderr).decode('utf8', 'replace'))
sys.exit(test.returncode)
