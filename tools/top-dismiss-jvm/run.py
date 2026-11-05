"""Replay the production TOP dismissal lease and native handback callbacks."""
from pathlib import Path
import os, subprocess, sys
root=Path(__file__).resolve().parents[2]
src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
host=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
def member(signature):
    start=host.index(signature);end=host.index('{',start)+1;depth=1
    while depth:
        depth+=(host[end]=='{')-(host[end]=='}');end+=1
    return host[start:end].replace('MiStepHost','TopDismissRegression')
body=r'''package com.jiusis.mistep;
import java.util.function.*;
public class TopDismissRegression {
 static final int ACTIVE=1,SLOT_TOP=4;static TopDismissRegression sInstance;
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static class View {void invalidate(){}}
 static class Layout {int topStrip=622;}
 static class Roll {boolean rolling;boolean rolling(){return rolling;}}
 static class Pane {}
 static class Slot {int taskId=42;long taskEpoch=2;boolean occupied=true,parked,frozen,promoting,launching,closing;
  Object taskToken=new Object();View view=new View();}
 static class HyperOsStyle {static float dp(Object c,float v){return v*2.75f;}}
 static class MiStepClipboard {static MiStepClipboard get(Object c){return new MiStepClipboard();}void close(){}}
 static class MiStepScanner {static void closeImmediately(){}}
 static class MiStepSideDismiss {
  final BooleanSupplier current;final Consumer<Boolean> complete;float distance,dx;boolean tracking=true,closed;
  MiStepSideDismiss(View r,View v,float d,BooleanSupplier c,Consumer<Boolean> done,Consumer<String> log){distance=d;current=c;complete=done;}
  boolean isTracking(){return tracking;}void follow(float d){dx=d;if(!current.getAsBoolean())settle(false);}
  void settle(boolean commit){if(!tracking)return;tracking=false;complete.accept(commit&&current.getAsBoolean());}
  void close(){closed=true;}
 }
 int scene=ACTIVE,screenW=1080,returns,clears,finished,focused,hints;long sceneEpoch=1;
 boolean screenOn=true,keyguardLocked,suspendRequested,transferBusy,workspaceEntering,topFace=true,topFromMain=true;
 boolean controller=true,front;float topDismissDx,topDismissDirection;
 Object ctx=new Object(),promotion,cellMotion,topTransition,topOriginalSlot=new Object();
 View root=new View();Layout layout=new Layout();Roll topRoll=new Roll();
 Slot[] slots={new Slot(),new Slot(),new Slot(),new Slot(),new Slot()};
 MiStepSideDismiss sideDismiss;Slot sideDismissSlot;TopTaskState topTaskState=new TopTaskState();
 Runnable success;Consumer<String> failure;
 TopDismissRegression(){sInstance=this;var key=new TopTaskState.Key(42,0,slots[4].taskToken);var t=topTaskState.request(key,sceneEpoch,true,true,true,true);topTaskState.commit(t,sceneEpoch);}
 static boolean hostSceneActive(){return sInstance.scene==ACTIVE&&sInstance.root!=null&&sInstance.screenOn&&!sInstance.keyguardLocked;}
 boolean controllerOwns(Slot s,int id,Object t){return controller;}
 boolean isActive(Slot s){return s==slots[4]&&root!=null;}
 void updateHint(Slot s){hints++;}void updateTouchRegion(Slot s){}void finishPendingSuspend(){finished++;}
 void preferMainAfterTopChange(){focused++;}
 void clearSlot(Slot s){s.occupied=false;s.taskToken=null;s.taskId=-1;s.taskEpoch++;s.promoting=false;clears++;}
 void returnToSystem(Slot s,boolean front,Runnable ok,Consumer<String> bad){returns++;this.front=front;success=ok;failure=bad;}
 void nativeSuccess(){transferBusy=false;slots[4].promoting=false;success.run();}
 void nativeFailure(){transferBusy=false;slots[4].promoting=false;failure.accept("native rejected");}
 static void log(String s){}
 __METHODS__
 static TopDismissRegression fresh(){return new TopDismissRegression();}
 static void begin(TopDismissRegression h,float dx){check(beginTopDismiss(dx,h.slots[4].taskToken),"valid real TOP starts");}
 public static void main(String[]args){
  for(int sign:new int[]{-1,1}){
   var h=fresh();begin(h,sign*80);var run=h.sideDismiss;
   check(h.transferBusy&&h.slots[4].promoting&&run.distance*sign>1080,"real view goes to selected edge");
   check(topGestureHeight()==622,"tracking continues while own transfer is busy");
   moveTopDismiss(sign*400);endTopDismiss(false);
   check(h.returns==1&&!h.front&&h.clears==0&&!run.closed,"keep original binding offscreen until native ack");
   h.nativeSuccess();check(h.clears==1&&!h.slots[4].occupied&&h.topTaskState.owner()==null&&h.sideDismiss==null&&run.closed,"ack clears only old TOP and releases animation");
   check(h.topOriginalSlot==null&&!h.topFromMain&&h.topFace&&h.focused==1,"background push leaves empty TOP, not original side slot");
  }
  for(boolean cancel:new boolean[]{false,true}){
   var h=fresh();begin(h,80);moveTopDismiss(cancel?400:100);endTopDismiss(cancel);
   check(h.returns==0&&h.clears==0&&h.slots[4].occupied&&!h.transferBusy&&!h.slots[4].promoting&&h.sideDismiss==null,"short drag/cancel restores retained task");
  }
  var h=fresh();begin(h,80);moveTopDismiss(-400);endTopDismiss(false);check(h.returns==0,"reversed direction rebounds");
  h=fresh();begin(h,-80);moveTopDismiss(-400);endTopDismiss(false);h.nativeFailure();
  check(h.slots[4].occupied&&h.clears==0&&h.sideDismiss==null&&!h.transferBusy,"failed native handback restores same task binding");
  h=fresh();begin(h,80);h.slots[4].taskEpoch++;moveTopDismiss(400);
  check(h.returns==0&&h.clears==0&&h.sideDismiss==null,"replacement epoch blocks stale completion");
  h=fresh();begin(h,80);h.suspendRequested=true;endTopDismiss(false);
  check(h.returns==0&&!h.transferBusy&&h.sideDismiss==null,"exit cancels tracking and releases busy state");
  for(int state=0;state<13;state++){
   h=fresh();Object token=h.slots[4].taskToken;
   switch(state){case 0:h.scene=2;break;case 1:h.root=null;break;case 2:h.screenOn=false;break;case 3:h.keyguardLocked=true;break;
    case 4:h.suspendRequested=true;break;case 5:h.topFace=false;break;case 6:h.topRoll.rolling=true;break;case 7:h.topTransition=new Object();break;
    case 8:h.controller=false;break;case 9:h.slots[4].parked=true;break;case 10:h.slots[4].frozen=true;break;case 11:h.slots[1].launching=true;break;case 12:h.transferBusy=true;break;}
   check(topDismissToken()==null&&!beginTopDismiss(100,token)&&h.returns==0,"reject unsafe state "+state);
  }
  h=fresh();check(!beginTopDismiss(100,new Object()),"reject token replaced since DOWN");
  check(!beginTopDismiss(Float.NaN,h.slots[4].taskToken),"reject nonfinite drag");
  System.out.println("Actual TOP dismissal lease/native handback checks="+checks+" passed");
 }
}'''
methods='\n'.join(member(sig) for sig in ('    private boolean topReady(', '    static int topGestureHeight(',
 '    static Object topDismissToken(', '    static boolean beginTopDismiss(', '    static void moveTopDismiss(', '    static void endTopDismiss('))
p=out/'com/jiusis/mistep/TopDismissRegression.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body.replace('__METHODS__',methods),encoding='utf8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(p),str(src/'TopTaskState.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TopDismissRegression'],check=True)
