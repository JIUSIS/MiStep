import os
"""Execute the actual collection owner against the existing bounded Shell fakes."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'build/tests/live-collect-jvm'
JDK = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([sys.executable, '-X', 'utf8', str(ROOT / 'tools/live-swap-jvm/run.py'), str(OUT)], check=True)
source = OUT / 'com/jiusis/mistep/LiveCollectRegression.java'
source.write_text(r'''package com.jiusis.mistep;
import android.graphics.Rect;
import java.util.*;
import com.android.wm.shell.ShellTaskOrganizer.TaskListener;
import com.jiusis.mistep.LivePairRegression.*;
public class LiveCollectRegression {
 static int checks;
 static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class Case {
  Fixture f=new Fixture(); Surface parent=new Surface(1); MiStepLiveCollectTask task;
  Case(){f.o.mTaskListeners.map.remove(12);f.c.mTaskToken=null;f.c.mTaskInfo=null;f.c.mTaskLeash=null;
   task=new MiStepLiveCollectTask(f.o,f.views,f.c,11,"A","HOME",0,new Rect(0,0,1200,2608),parent,s->{});}
  void arm()throws Exception{task.arm(LiveCollectRegression.class.getClassLoader());}
  Surface canonical(){return ((Appeared)f.o.mTasks.get(11)).leash;}
  LiveHomeRegression.Transition transition(){LiveHomeRegression.Transition t=new LiveHomeRegression.Transition();
   t.changes.add(new LiveHomeRegression.Change(new Info(11,"A",6),new Surface(11)));return t;}
 }
 public static void main(String[]args)throws Exception {
  Case a=new Case();check(a.task.preflight(),"valid empty TaskView rejected");a.arm();
  check(a.f.o.getTaskListener(a.f.a,false)!=a.f.o.fullscreen,"exact temporary lease missing");
  List<String> w=((android.window.WindowContainerTransaction)a.task.windowTransaction(false,false)).ops;
  check(w.contains("mode:A:6")&&w.contains("reorder:HOME:true"),"real app/Home ordering missing");
  check(w.contains("focusable:A:true"),"collected side must keep the multi-resume eligibility of 101");
  check(((android.window.WindowContainerTransaction)a.task.windowTransaction(true,false)).ops.contains("focusable:A:true"),"inverse fullscreen focus not restored");
  check(w.stream().noneMatch(s->s.contains(":B:")),"unrelated task mutated");
  check(a.task.resolve(a.transition())!=null,"canonical Change missing");a.f.a.mode=6;
  Tx start=new Tx(),finish=new Tx();a.task.prepare(start,finish);
  check(start.ops.contains("hide:500"),"empty destination resize background not hidden in real-task start transaction");
  check(a.task.sideOwned(),"same empty controller did not claim app");
  a.task.settle(finish,.2473f);check(finish.ops.contains("parent:11:500"),"real app not restored to side parent");
  check(finish.ops.contains("matrix:11:0.2473"),"real app side scale lost");
  check(finish.ops.contains("show:500"),"real app and destination container not restored together");
  check(a.canonical().releases==0,"canonical task leash released");
  a.task.windowTransaction(true,false);a.f.a.mode=1;Surface copy=a.f.c.mTaskLeash;
  a.task.settle(new Tx(),.2473f);check(a.task.originalSettled(),"native fullscreen owner not restored");
  check(copy.releases==1&&a.canonical().releases==0,"copy/canonical lifecycle wrong");
  a.task.settle(new Tx(),.2473f);check(copy.releases==1,"copied handle retired twice");
  Case pending=new Case();pending.arm();pending.task.windowTransaction(true,true);
  check(!pending.task.originalRoles(),"stale mode1 accepted as inverse acknowledgement");
  pending.task.releaseOriginalWhenSeen();TaskListener held=(TaskListener)pending.f.o.getTaskListener(pending.f.a,false);
  pending.f.a.mode=6;held.onTaskInfoChanged(pending.f.a);
  check(pending.f.o.getTaskListener(pending.f.a,false)==held,"mode6 prematurely released lease");
  pending.f.a.mode=1;held.onTaskInfoChanged(pending.f.a);check(pending.task.originalSettled(),"late inverse callback leaked lease");
  Case partial=new Case();partial.arm();partial.f.a.mode=6;partial.f.views.falsePrepare=true;
  try{partial.task.prepare(new Tx(),new Tx());throw new AssertionError("false prepare accepted");}catch(IllegalStateException expected){checks++;}
  partial.task.settle(new Tx(),.2473f);check(partial.task.sideOwned()&&partial.f.views.visible,"partial prepare left app hidden");
  List<String> hidden=((android.window.WindowContainerTransaction)partial.task.finishWindowTransaction()).ops;
  check(hidden.get(hidden.size()-1).equals("hidden:A:false"),"finish WCT hid collected app");
  for(int fault=0;fault<3;fault++){
   Case retry=new Case();retry.arm();retry.f.a.mode=6;retry.task.prepare(new Tx(),new Tx());retry.f.a.mode=1;
   if(fault==0)retry.f.o.failRemove=1;if(fault==1)retry.f.c.failVanish=1;if(fault==2)retry.f.views.failRegister=1;
   try{retry.task.settle(new Tx(),.2473f);throw new AssertionError("failure not injected");}catch(IllegalStateException expected){checks++;}
   check(!retry.task.originalSettled(),"partial retirement reported restored");retry.task.settle(new Tx(),.2473f);
   check(retry.task.originalSettled(),"partial retirement could not resume at stage "+fault);
   check(retry.f.c.registered&&retry.canonical().releases==0,"native repository/canonical damaged");
  }
  Case occupied=new Case();occupied.f.c.mTaskToken="B";check(!occupied.task.preflight(),"occupied controller admitted");
  Case otherOwner=new Case();otherOwner.f.o.mTaskListeners.put(99,otherOwner.f.c);check(!otherOwner.task.preflight(),"multi-task owner admitted");
  Case foreign=new Case();foreign.f.a.userId=999;check(!foreign.task.preflight(),"foreign user admitted");
  Case wrong=new Case();wrong.arm();LiveHomeRegression.Transition bad=wrong.transition();bad.changes.get(0).leash=new Surface(77);
  try{wrong.task.resolve(bad);throw new AssertionError("noncanonical surface admitted");}catch(IllegalStateException expected){checks++;}
  Case dead=new Case();dead.f.c.mTaskNotFound=true;dead.f.o.mTaskListeners.put(99,dead.f.c);
  check(!dead.task.preflight(),"deleted exact registration reproduces rejected reuse");
  MiStepIdleTaskView.reset(dead.f.c,t->{});
  check(dead.f.o.mTaskListeners.get(99)==null&&!dead.f.c.mTaskNotFound&&dead.f.c.resets==1,"dead exact owner/notFound not retired together");
  check(dead.task.preflight(),"retired empty controller cannot accept a new live task");
  check(dead.f.o.mTaskListeners.get(-2)==dead.f.o.fullscreen&&dead.f.o.mTasks.get(11)!=null,"original organizer or live main damaged");
  Case multi=new Case();multi.f.o.mTaskListeners.put(98,multi.f.c);multi.f.o.mTaskListeners.put(99,multi.f.c);MiStepIdleTaskView.reset(multi.f.c,t->{});
  check(multi.f.o.mTaskListeners.get(98)==null&&multi.f.o.mTaskListeners.get(99)==null,"multiple dead identities not retired");
  for(int key:new int[]{-6,11}){Case live=new Case();live.f.o.mTaskListeners.put(99,live.f.c);live.f.o.mTaskListeners.put(key,live.f.c);
   try{MiStepIdleTaskView.reset(live.f.c,t->{});throw new AssertionError("live or global listener retired");}catch(IllegalStateException expected){checks++;}
   check(live.f.o.mTaskListeners.get(99)==live.f.c&&live.f.o.mTaskListeners.get(key)==live.f.c&&live.f.c.resets==0,"validation failure partially mutated organizer");}
  Case busy=new Case();busy.f.c.mPendingInfo=new Object();busy.f.o.mTaskListeners.put(99,busy.f.c);MiStepIdleTaskView.reset(busy.f.c,t->{});
  check(busy.f.c.resets==0&&busy.f.o.mTaskListeners.get(99)==busy.f.c,"pending TaskView reset");busy.f.c.mPendingInfo=null;busy.f.c.mTaskToken="B";MiStepIdleTaskView.reset(busy.f.c,t->{});
  check(busy.f.c.resets==0,"occupied TaskView reset");
  System.out.println("actual live collect ownership checks="+checks+" passed");
 }
}''', encoding='utf8')
subprocess.run([str(JDK/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-cp', str(OUT), '-d', str(OUT),
                str(ROOT/'module/src/main/java/com/jiusis/mistep/MiStepLiveCollectTask.java'), str(ROOT/'module/src/main/java/com/jiusis/mistep/MiStepIdleTaskView.java'), str(source)], check=True)
subprocess.run([str(JDK/'java.exe'), '-cp', str(OUT), 'com.jiusis.mistep.LiveCollectRegression'], check=True)
