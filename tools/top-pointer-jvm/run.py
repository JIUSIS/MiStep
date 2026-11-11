"""Actual pointer focus and status sampler against foreground, geometry and listener isolation."""
import ast, os, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'build/tests/top-pointer-jvm'
tree=ast.parse((ROOT/'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
shared=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='FILES' for t in n.targets))
x=shared['de/robv/android/xposed/XposedHelpers.java'];i=x.rfind('}')
x=x[:i]+''' public static Class<?> findClass(String n,ClassLoader l){try{return Class.forName(n);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object getStaticObjectField(Class<?> c,String n){try{return c.getField(n).get(null);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object findAndHookMethod(Class<?> c,String n,Object... args){return null;}
'''+x[i:]
FILES={
'de/robv/android/xposed/XposedHelpers.java':x,
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;public class XposedBridge {public static void hookAllMethods(Class<?> c,String n,XC_MethodHook h){}public static void hookAllConstructors(Class<?> c,XC_MethodHook h){}public static void log(String s){}}''',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook {public static class MethodHookParam {public Object thisObject;public Object[] args;Object result;public Object getResult(){return result;}public void setResult(Object r){result=r;}}protected void beforeHookedMethod(MethodHookParam p){}protected void afterHookedMethod(MethodHookParam p){}}''',
'android/os/Bundle.java':'''package android.os;import java.util.*;public class Bundle {Map<String,Object> m=new HashMap<>();public void putInt(String k,int v){m.put(k,v);}public int getInt(String k,int d){return (Integer)m.getOrDefault(k,d);}public void putFloat(String k,float v){m.put(k,v);}public float getFloat(String k,float d){return (Float)m.getOrDefault(k,d);}}''',
'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public void post(Runnable r){r.run();}}',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/view/View.java':'package android.view;public class View {}',
'com/miui/systemui/statusbar/core/StatusBarRegionSamplingInteractor$regionSamplingHelper$1.java':'package com.miui.systemui.statusbar.core;public class StatusBarRegionSamplingInteractor$regionSamplingHelper$1 {}',
'com/jiusis/mistep/MiStepHost.java':'package com.jiusis.mistep;class MiStepHost {static float[] activeStatusBarTransform(){return null;}}',
'com/jiusis/mistep/ShellCompat.java':'package com.jiusis.mistep;class ShellCompat {static boolean hasMethod(Class<?> c,String n,int a){return false;}}',
'com/jiusis/mistep/PointerRegression.java':r'''package com.jiusis.mistep;
import android.os.Bundle;import android.graphics.Rect;
public class PointerRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static class Task {Display display;int mode=6;boolean translucent=true,visible=true;Rect bounds=new Rect(0,0,2964,1707);Activity activity;
  Object getDisplayContent(){return display;}int getWindowingMode(){return mode;}boolean isForceTranslucent(){return translucent;}boolean isVisible(){return visible;}Rect getBounds(){return bounds;}Object topRunningActivityLocked(){return activity;}}
 static class Activity {Task task;Window window;boolean focusable=true,visible=true;Object getTask(){return task;}Object getDisplayContent(){return task.display;}boolean isFocusable(){return focusable;}boolean isVisibleRequested(){return visible;}Object findMainWindow(){return window;}}
 static class Window {Activity mActivityRecord;Object getTask(){return mActivityRecord.task;}Object getDisplayContent(){return mActivityRecord.task.display;}}
 static class Display {Activity mFocusedApp;Window mCurrentFocus;int updates,sets;boolean setFocusedApp(Activity a){boolean changed=mFocusedApp!=a;mFocusedApp=a;sets++;return changed;}boolean updateFocusedWindowLocked(int why,boolean update,int topDisplay){updates++;mCurrentFocus=mFocusedApp.window;return true;}}
 static class Case {Display d=new Display();Task t=new Task();Activity a=new Activity();Window w=new Window();boolean leased=true;Case(){t.display=d;t.activity=a;a.task=t;a.window=w;w.mActivityRecord=a;}boolean select(){return MiStepTopPointerFocus.select(t,d,x->leased);}}
 static class Helper {Object mCallback,mSamplingListener=new Object();Helper(Object cb){mCallback=cb;}}
 public static void main(String[] args){Bundle row=new Bundle();row.putInt("displayId",0);row.putInt("width",2964);row.putInt("height",1707);row.putFloat("scale",1080f/2964);
 check(MiStepTopPointerFocus.contains(row,1,300),"left edge excluded");check(MiStepTopPointerFocus.contains(row,1079,300),"right edge excluded");check(!MiStepTopPointerFocus.contains(row,540,623),"main pointer admitted");check(!MiStepTopPointerFocus.contains(row,-1,100),"negative pointer admitted");check(!MiStepTopPointerFocus.contains(row,Float.NaN,100),"NaN pointer admitted");row.putInt("displayId",1);check(!MiStepTopPointerFocus.contains(row,1,100),"foreign display admitted");row.putInt("displayId",0);row.putFloat("scale",1);check(!MiStepTopPointerFocus.contains(row,1,100),"full scale accepted");
 row.putFloat("scale",.605f);row.putInt("width",1251);row.putInt("height",2519);row.putFloat("left",2291);row.putFloat("top",508);
 check(MiStepTopPointerFocus.contains(row,2333,550),"portrait right task physical pointer admitted");
 check(!MiStepTopPointerFocus.contains(row,2000,550)&&!MiStepTopPointerFocus.contains(row,2333,500),"main/top pointer excluded from right task");
 row.putFloat("left",Float.NaN);check(!MiStepTopPointerFocus.contains(row,2333,550),"nonfinite offset rejected");
 Case c=new Case();c.t.bounds=new Rect(0,0,1251,2519);check(c.select(),"explicit portrait DOWN failed to select");check(c.d.mFocusedApp==c.a&&c.d.mCurrentFocus==c.w,"native Activity/Window focus diverged");check(c.d.updates==1,"native focus update missing");check(!c.select()&&c.d.updates==1,"already focused task updated repeatedly");
 Case stale=new Case();stale.leased=false;check(!stale.select()&&stale.d.sets==0,"unleased TOP stole focus");Case hidden=new Case();hidden.t.visible=false;check(!hidden.select(),"hidden TOP stole focus");Case main=new Case();main.t.mode=1;check(!main.select(),"main task changed");Case opaque=new Case();opaque.t.translucent=false;check(!opaque.select(),"native small window changed");Case wrongDisplay=new Case();wrongDisplay.t.display=new Display();check(!wrongDisplay.select(),"foreign task changed");Case blocked=new Case();blocked.a.focusable=false;check(!blocked.select()&&blocked.d.updates==0,"unfocusable Activity selected");Case noWindow=new Case();noWindow.a.window=null;check(!noWindow.select(),"missing window selected");Case mismatch=new Case();mismatch.a.task=new Task();check(!mismatch.select(),"foreign window selected");
 Case windowOnly=new Case();windowOnly.d.mFocusedApp=windowOnly.a;check(windowOnly.select()&&windowOnly.d.mCurrentFocus==windowOnly.w,"unchanged Activity did not reconcile native Window focus");
 float[] right={.7471545f,622,273};Helper owned=new Helper(new com.miui.systemui.statusbar.core.StatusBarRegionSamplingInteractor$regionSamplingHelper$1());MiStepStatusBarSampling.track(owned);
 check(MiStepStatusBarSampling.usesMainComposition(owned.mSamplingListener,right),"owned status sampler kept hidden Home stop layer");check(!MiStepStatusBarSampling.usesMainComposition(new Object(),right),"foreign sampler changed");Helper other=new Helper(new Object());MiStepStatusBarSampling.track(other);check(!MiStepStatusBarSampling.usesMainComposition(other.mSamplingListener,right),"foreign callback registered");check(!MiStepStatusBarSampling.usesMainComposition(owned.mSamplingListener,null),"exit changed native layer");check(!MiStepStatusBarSampling.usesMainComposition(owned.mSamplingListener,new float[]{Float.NaN,0,0}),"invalid transform accepted");
 Rect logical=new Rect(0,0,1080,110);Rect physical=MiStepStatusBarSampling.physical(logical,right);check(physical.equals(new Rect(273,622,1080,705)),"right main sampling coordinates wrong");check(logical.equals(new Rect(0,0,1080,110)),"native logical dark-icon bounds mutated");check(MiStepStatusBarSampling.physical(logical,null)==logical,"exit failed to keep native coordinates");
 System.out.println("actual TOP pointer focus/main composition sampling checks="+checks+" passed");}
}'''}
sources=[]
for name,body in FILES.items():
 p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf8');sources.append(str(p))
sources.append(str(ROOT/'tools/test-stubs/android/graphics/Rect.java'))
for name in ['MiStepTopPointerFocus','MiStepStatusBarSampling','MiStepImePolicy','MiStepSideRoleChange','ZoomChannelState','ZoomCommand','ZoomAnimCommand']:
 sources.append(str(ROOT/f'module/src/main/java/com/jiusis/mistep/{name}.java'))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),*sources],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.PointerRegression'],check=True)
