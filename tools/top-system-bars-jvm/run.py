"""Actual TOP return helper against native owner, focus, transition and stale callback cases."""
import ast, os, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'build/tests/top-system-bars-jvm'
JDK=Path(os.environ['MISTEP_JAVA_BIN'])
tree=ast.parse((ROOT/'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
shared=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='FILES' for t in n.targets))
FILES={
'de/robv/android/xposed/XposedHelpers.java':shared['de/robv/android/xposed/XposedHelpers.java'],
'android/os/SystemClock.java':'package android.os; public class SystemClock { public static long now=1000; public static long uptimeMillis(){return now;} }',
'android/os/Handler.java':'''package android.os; import java.util.*; public class Handler {
 public ArrayDeque<Runnable> queue=new ArrayDeque<>();public boolean accept=true;
 public boolean post(Runnable r){if(!accept)return false;queue.add(r);return true;}
 public void runAll(){while(!queue.isEmpty())queue.remove().run();}
}''',
'com/jiusis/mistep/TopBarsRegression.java':r'''package com.jiusis.mistep;
import java.util.*;import android.os.*;
public class TopBarsRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class Task {Object display,root=this;int mode=6,type=1;boolean translucent=true,visible=true;
  Object getDisplayContent(){return display;}Object getRootTask(){return root;}
  int getWindowingMode(){return mode;}int getActivityType(){return type;}
  boolean isForceTranslucent(){return translucent;}boolean isVisible(){return visible;}}
 static class Window {Task task;int requestedTypes=3;Window(Task t){task=t;}Task getTask(){return task;}}
 static class Display {Window mFocusedApp,mCurrentFocus;}
 static class Native {Object mDisplayContent;Task mCurrentTopTask;Handler mHandler=new Handler();int state=-1,calls;boolean ready=true,split,fail;
  int getBarState(){return state;}boolean isTaskBarReady(){return ready;}boolean isInSplitScreen(){return split;}
  void updateStatusBarAnotherApp(){if(fail)throw new IllegalStateException("native failure");calls++;}}
 static class Case {Object lock=new Object();Display d=new Display();Task t=new Task();Window w=new Window(t);Native n=new Native();
  MiStepTopSystemBars h=new MiStepTopSystemBars();boolean active=true;List<String> logs=new ArrayList<>();
  Case(){SystemClock.now=1000;t.display=d;n.mDisplayContent=d;n.mCurrentTopTask=t;d.mCurrentFocus=d.mFocusedApp=w;}
  void remember(){synchronized(lock){h.remember(w,d);}}
  void returned(){t.mode=1;t.translucent=false;}
  void changed(){synchronized(lock){h.changed(n,t,d,lock,()->active,logs::add);}}
  void focused(){synchronized(lock){h.focused(d,lock,()->active,logs::add);}}
  void run(){n.mHandler.runAll();}
 }
 public static void main(String[] args){
  Case ok=new Case();ok.remember();ok.returned();ok.changed();ok.changed();
  check(ok.n.mHandler.queue.size()==1,"duplicate return queued twice");check(ok.n.calls==0,"native API called on WMS caller thread");
  ok.run();check(ok.n.calls==1&&ok.logs.size()==1,"native return not reconciled");ok.changed();ok.focused();ok.run();check(ok.n.calls==1,"completed visit restored twice");
  Case immersive=new Case();immersive.w.requestedTypes=0;immersive.remember();immersive.returned();immersive.changed();immersive.run();
  check(immersive.n.calls==1&&immersive.w.requestedTypes==0,"app immersive visibility overwritten");
  Case unknown=new Case();unknown.returned();unknown.changed();unknown.run();check(unknown.n.calls==0,"unowned main task accepted");
  Case top=new Case();top.remember();top.changed();top.run();check(top.n.calls==0,"active TOP changed main bar");
  Case ordinary=new Case();ordinary.t.mode=1;ordinary.t.translucent=false;ordinary.remember();ordinary.changed();ordinary.run();check(ordinary.n.calls==0,"ordinary fullscreen remembered as TOP");
  Case freeform=new Case();freeform.remember();freeform.t.mode=5;freeform.changed();freeform.returned();freeform.changed();freeform.run();check(freeform.n.calls==0,"native freeform handoff later stolen");
  Case opacity=new Case();opacity.remember();opacity.t.mode=1;opacity.changed();check(opacity.n.mHandler.queue.isEmpty(),"live handoff transparency ignored");
  opacity.t.translucent=false;opacity.changed();opacity.run();check(opacity.n.calls==1,"opacity-restored event lost");
  Case noFocus=new Case();noFocus.remember();noFocus.returned();noFocus.d.mCurrentFocus=new Window(new Task());noFocus.changed();noFocus.run();check(noFocus.n.calls==0,"window focus mismatch ignored");
  noFocus.d.mCurrentFocus=noFocus.w;noFocus.focused();noFocus.run();check(noFocus.n.calls==1,"native focus event did not retry");
  Case appFocus=new Case();appFocus.remember();appFocus.returned();appFocus.d.mFocusedApp=null;appFocus.changed();appFocus.run();check(appFocus.n.calls==0,"missing Activity focus ignored");
  Case invisible=new Case();invisible.remember();invisible.returned();invisible.t.visible=false;invisible.changed();invisible.run();check(invisible.n.calls==0,"background task stole native bar");
  Case otherMain=new Case();otherMain.remember();otherMain.returned();otherMain.n.mCurrentTopTask=new Task();otherMain.changed();otherMain.run();check(otherMain.n.calls==0,"different native main owner ignored");
  Case display=new Case();display.remember();display.returned();display.t.display=new Display();display.changed();display.run();check(display.n.calls==0,"moved task accepted");
  Case controllerDisplay=new Case();controllerDisplay.remember();controllerDisplay.returned();controllerDisplay.n.mDisplayContent=new Display();controllerDisplay.changed();controllerDisplay.run();check(controllerDisplay.n.calls==0,"foreign display controller accepted");
  Case home=new Case();home.remember();home.returned();home.t.type=2;home.changed();home.run();check(home.n.calls==0,"Home accepted as returned app");
  Case nested=new Case();nested.remember();nested.returned();nested.t.root=new Task();nested.changed();nested.run();check(nested.n.calls==0,"nested task accepted");
  Case busy=new Case();busy.remember();busy.returned();busy.n.state=1;busy.changed();busy.run();check(busy.n.calls==0,"native transition interrupted");
  busy.n.state=-1;busy.changed();busy.run();check(busy.n.calls==1,"native transition completion did not retry");
  Case missing=new Case();missing.remember();missing.returned();missing.n.ready=false;missing.changed();missing.run();check(missing.n.calls==0,"missing native bars ignored");
  Case split=new Case();split.remember();split.returned();split.n.split=true;split.changed();split.run();check(split.n.calls==0,"native split policy overridden");
  Case exit=new Case();exit.remember();exit.returned();exit.changed();exit.h.clear();exit.run();check(exit.n.calls==0,"exit did not cancel queued callback");
  Case inactive=new Case();inactive.remember();inactive.returned();inactive.changed();inactive.active=false;inactive.run();check(inactive.n.calls==0,"inactive workspace callback accepted");
  Case lateFocus=new Case();lateFocus.remember();lateFocus.returned();lateFocus.changed();lateFocus.d.mCurrentFocus=null;lateFocus.run();check(lateFocus.n.calls==0,"late focus drift ignored");
  lateFocus.d.mCurrentFocus=lateFocus.w;lateFocus.focused();lateFocus.run();check(lateFocus.n.calls==1,"focus retry after delayed drift lost");
  Case recollect=new Case();recollect.remember();recollect.returned();recollect.changed();recollect.t.mode=6;recollect.t.translucent=true;recollect.remember();recollect.run();check(recollect.n.calls==0,"old visit changed recollected task");
  recollect.returned();recollect.changed();recollect.run();check(recollect.n.calls==1,"fresh collection visit not restored");
  Case expired=new Case();expired.remember();expired.returned();expired.changed();SystemClock.now=7000;expired.run();check(expired.n.calls==0,"expired queued return accepted");
  Case timeout=new Case();timeout.remember();timeout.returned();timeout.n.state=1;timeout.changed();SystemClock.now=7000;timeout.n.state=-1;timeout.changed();timeout.run();check(timeout.n.calls==0,"late native completion accepted");
  Case queueFail=new Case();queueFail.remember();queueFail.returned();queueFail.n.mHandler.accept=false;queueFail.changed();queueFail.n.mHandler.accept=true;queueFail.focused();queueFail.run();check(queueFail.n.calls==1,"failed Handler post prevented a real focus retry");
  Case failure=new Case();failure.remember();failure.returned();failure.n.fail=true;failure.changed();failure.run();check(failure.logs.size()==1&&failure.n.calls==0,"native failure not contained");
  failure.n.fail=false;failure.focused();failure.run();check(failure.n.calls==0,"failed visit repeatedly changed global bars");
  System.out.println("actual TOP native bar owner/focus/lifecycle checks="+checks+" passed");
 }
}'''}
sources=[]
for name,body in FILES.items():
 p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf8');sources.append(str(p))
for name in ['MiStepTopSystemBars','MiStepSideRoleChange']:
 sources.append(str(ROOT/f'module/src/main/java/com/jiusis/mistep/{name}.java'))
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),*sources],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.TopBarsRegression'],check=True)
