"""Replay actual Host handoff against OS2's copied-handle native detach contract."""
from pathlib import Path
import os, subprocess, sys

root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
src=root/'module/src/main/java/com/jiusis/mistep'
source=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
start=source.index('    private void handoffPromotedTask(')
brace=source.index('{',start);end=brace+1;depth=1
while depth:
    if source[end]=='{':depth+=1
    elif source[end]=='}':depth-=1
    end+=1
method=source[start:end]
files={
'android/graphics/Rect.java': 'package android.graphics;public class Rect {public Rect(int a,int b,int c,int d){}}',
'android/util/SparseArray.java': '''package android.util;public class SparseArray<T>{
 java.util.Map<Integer,T> m=new java.util.HashMap<>();public T get(int i){return m.get(i);}public void put(int i,T v){m.put(i,v);}}''',
'android/view/SurfaceControl.java': '''package android.view;
public class SurfaceControl {
 public static java.util.List<String> events=new java.util.ArrayList<>();
 public static class Layer {public Object parent;public float scale=1;public boolean shown;}
 public Layer layer;public boolean valid=true;public int releases;
 public SurfaceControl(){layer=new Layer();}public SurfaceControl(SurfaceControl other){layer=other.layer;}
 public boolean isValid(){return valid;}public void release(){valid=false;releases++;}
 public static class Transaction {
  java.util.List<Runnable> ops=new java.util.ArrayList<>();public boolean closed;
  public Transaction reparent(SurfaceControl s,Object p){ops.add(()->{s.layer.parent=p;events.add(p==null?"detach":"attach");});return this;}
  public Transaction setMatrix(SurfaceControl s,float a,float b,float c,float d){ops.add(()->s.layer.scale=a);return this;}
  public Transaction setPosition(SurfaceControl s,float x,float y){return this;}
  public Transaction setWindowCrop(SurfaceControl s,android.graphics.Rect r){return this;}
  public Transaction setAlpha(SurfaceControl s,float a){return this;}
  public Transaction show(SurfaceControl s){ops.add(()->s.layer.shown=true);return this;}
  public Transaction hide(SurfaceControl s){ops.add(()->s.layer.shown=false);return this;}
  public void apply(){for(var op:ops)op.run();ops.clear();}public void close(){closed=true;}
 }
}''',
'com/jiusis/mistep/NativeHandoffRegression.java': r'''package com.jiusis.mistep;
import android.graphics.Rect;import android.view.SurfaceControl;import java.util.*;
public class NativeHandoffRegression {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static class Info {public int taskId=88,displayId,userId;public Object token=new Object();public boolean isVisible=true;
  public int getWindowingMode(){return 1;}public boolean hasParentTask(){return false;}}
 public static class Appeared {public Info info=new Info();public SurfaceControl canonical=new SurfaceControl();
  public Info getTaskInfo(){return info;}public SurfaceControl getLeash(){return canonical;}}
 public static class AreaInfo {public int displayId,featureId=1;}
 public static class Area {public SurfaceControl parent=new SurfaceControl();public AreaInfo info=new AreaInfo();
  public AreaInfo getDisplayAreaInfo(int display){return info;}public SurfaceControl getDisplayAreaLeash(int display){return parent;}
  public void reparentToDisplayArea(int display,SurfaceControl s,SurfaceControl.Transaction tx){tx.reparent(s,parent);}}
 public static class Organizer {public Object mLock=new Object();public android.util.SparseArray<Object>mTasks=new android.util.SparseArray<>(),mTaskListeners=new android.util.SparseArray<>();
  public Object assigned;public Area area=new Area();public Object getTaskListener(Object info,boolean pending){return assigned;}}
 public static class Controller {public Organizer mTaskOrganizer=new Organizer();public Object mTaskToken;public SurfaceControl mTaskLeash;public int vanished;
  public void onTaskVanished(Info info){
   if(mTaskToken==null||!mTaskToken.equals(info.token))return;
   vanished++;SurfaceControl.events.add("vanished");
   // The actual OS2 method detaches this copied handle's same native layer.
   new SurfaceControl.Transaction().reparent(mTaskLeash,null).apply();
   mTaskLeash.release();mTaskLeash=null;mTaskToken=null;
  }}
 static class Slot {Object controller;String pkg="com.android.settings";}
 static class Promotion {boolean handoffAttempted,cancelled,candidateRecovery,background,handoffComplete,liveMainReveal;
  Slot slot=new Slot();int taskId=88;Object token,liveRevealSurface;}
 static class TopTransition {Object surface;void setLiveSurface(Object s){surface=s;}}
 static class XposedHelpers {
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}
  static Object callMethod(Object o,String n,Object...args){
   for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)
    try{return m.invoke(o,args);}catch(java.lang.reflect.InvocationTargetException e){throw new RuntimeException(e.getCause());}catch(Exception e){throw new RuntimeException(e);}
   throw new IllegalStateException(n);
  }}
 static class ShellCompat {
  static Object rootArea(Object o){return ((Organizer)o).area;}
  static Object call(Object o,String n,Object...args){
   if(n.equals("unregisterTaskView")||n.equals("registerTaskView")){SurfaceControl.events.add(n);return null;}
   if(n.equals("removeListener")){var org=(Organizer)o;org.assigned=org.mTaskListeners.get(-2);SurfaceControl.events.add("fallback");return null;}
   return XposedHelpers.callMethod(o,n,args);
  }}
 static class TopTaskReturnPolicy {static final float CENTER_START_SCALE=.14f;static float centeredOffset(int size,float scale){return size*(1-scale)/2;}}
 int screenW=1080,screenH=2460;Object transitions=new Object();TopTransition topTransition;
 boolean supportsTaskUser(int id){return id==0;}Object getSurfaceField(Object o,String n){return XposedHelpers.getObjectField(o,n);}
 void closeSurfaceObject(Object o,String n){if(o!=null)XposedHelpers.callMethod(o,n);}void log(String s){}void traceSwapEvent(String s){}
 __METHOD__
 static class Fixture {NativeHandoffRegression h=new NativeHandoffRegression();Controller c=new Controller();Appeared a=new Appeared();Promotion p=new Promotion();SurfaceControl copy;
  Fixture(){SurfaceControl.events.clear();p.slot.controller=c;p.token=a.info.token;c.mTaskToken=p.token;copy=new SurfaceControl(a.canonical);c.mTaskLeash=copy;
   a.canonical.layer.parent=new Object();c.mTaskOrganizer.mTasks.put(88,a);c.mTaskOrganizer.mTaskListeners.put(-2,new Object());c.mTaskOrganizer.assigned=c;}
  void run()throws Throwable{h.handoffPromotedTask(p);}}
 public static void main(String[]args)throws Throwable{
  var f=new Fixture();f.run();
  check(f.a.canonical.layer.parent==f.c.mTaskOrganizer.area.parent,"native onTaskVanished detached canonical after handoff");
  check(f.copy.layer==f.a.canonical.layer&&f.copy!=f.a.canonical,"copied Java handles retain one native layer");
  check(f.c.vanished==1&&f.copy.releases==1&&f.a.canonical.isValid(),"old owner releases only its own handle once");
  check(f.p.handoffComplete&&f.c.mTaskToken==null&&f.c.mTaskOrganizer.assigned==f.c.mTaskOrganizer.mTaskListeners.get(-2),"native fullscreen owns same task after cleanup");
  check(SurfaceControl.events.lastIndexOf("attach")>SurfaceControl.events.lastIndexOf("detach"),"final display attachment follows native detach");
  int events=SurfaceControl.events.size();f.run();check(SurfaceControl.events.size()==events&&f.c.vanished==1,"completed handoff cannot detach twice");
  f=new Fixture();f.p.liveMainReveal=true;f.h.topTransition=new TopTransition();f.run();
  check(f.a.canonical.layer.parent==f.c.mTaskOrganizer.area.parent&&f.a.canonical.layer.scale==.14f&&f.h.topTransition.surface==f.a.canonical,"live reveal gets attached canonical at center scale");
  f=new Fixture();f.p.background=true;f.run();check(!f.a.canonical.layer.shown&&f.a.canonical.layer.parent==f.c.mTaskOrganizer.area.parent,"background return attaches without stealing visibility");
  f=new Fixture();f.c.mTaskToken=null;f.c.mTaskOrganizer.assigned=f.c.mTaskOrganizer.mTaskListeners.get(-2);f.run();
  check(f.c.vanished==0&&f.p.handoffComplete&&f.a.canonical.layer.parent==f.c.mTaskOrganizer.area.parent,"already-native owner never receives old callback");
  f=new Fixture();f.p.cancelled=true;f.run();check(!f.p.handoffComplete&&f.c.vanished==0&&SurfaceControl.events.isEmpty(),"cancelled ticket never mutates a task");
  f=new Fixture();f.a.info.userId=10;f.run();check(!f.p.handoffAttempted&&f.c.vanished==0,"foreign user task rejected before surface writes");
  f=new Fixture();f.p.token=new Object();f.run();check(!f.p.handoffAttempted&&f.c.vanished==0,"changed token rejected before detach");
  f=new Fixture();f.c.mTaskOrganizer.area.parent.valid=false;try{f.run();throw new AssertionError("invalid parent accepted");}catch(IllegalStateException expected){}
  check(f.c.vanished==0&&!f.p.handoffAttempted,"invalid parent keeps original owner intact");
  System.out.println("actual Host native surface handoff checks="+checks+" passed");
 }
}'''.replace('__METHOD__',method),
}
paths=[]
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(src/'TaskHandoffPolicy.java'),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.NativeHandoffRegression'],check=True)
