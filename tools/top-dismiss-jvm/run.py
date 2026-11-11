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
 static class Layout {int topStrip=622;boolean tabletLandscape,mainOnRight;}
 static class Roll {boolean rolling;boolean rolling(){return rolling;}}
 static class Pane {}
 static class Info {int id,user;Object token;boolean ordinary=true,side;}
 static class LiveTask {int id;Object token;LiveTask(TopTaskState.Key k){id=k.taskId;token=k.token;}
  boolean matches(Object i){return i instanceof Info f&&f.id==id&&token.equals(f.token);}}
 static class TopCandidate {TopTaskState.Key key;LiveTask task;TopCandidate(TopTaskState.Key k){key=k;task=new LiveTask(k);}}
 static class XposedHelpers {static int getIntField(Object o,String n){return ((Info)o).user;}
  static Object callMethod(Object o,String n,Object...a){var w=(android.window.WindowContainerTransaction)o;w.token=a[0];if(n.equals("reorder"))w.front=(boolean)a[1];return null;}}
 static class Slot {int[] rect={2144,606,3048,2032};int taskId=42;long taskEpoch=2;boolean occupied=true,parked,frozen,promoting,launching,closing;
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
 boolean controller=true,front,liveMainReveal,transitionAvailable=true;float topDismissDx,topDismissDirection;
 Runnable pendingHandback;int reveals,cancelled;
 Object ctx=new Object(),promotion,cellMotion,topTransition,topOriginalSlot=new Object();
 View root=new View();Layout layout=new Layout();Roll topRoll=new Roll();
 Slot[] slots={new Slot(),new Slot(),new Slot(),new Slot(),new Slot()};
 MiStepSideDismiss sideDismiss;Slot sideDismissSlot;TopTaskState topTaskState=new TopTaskState();
 Runnable success;Consumer<String> failure;
 TopCandidate tabletCoveredMain;TopTaskState.Key tabletReturnedTop;
 TopCandidate covered;TopCandidate currentTopCandidate(){return covered;}
 Info nativeInfo;int restores;boolean queue;Runnable shellWork;
 Object rootInfo(LiveTask t){return nativeInfo;}boolean ordinaryRoot(Object i,int mode){return i instanceof Info f&&f.ordinary;}
 boolean isSideToken(Object t){return nativeInfo.side;}
 boolean executeOnShell(Runnable r){if(queue)shellWork=r;else r.run();return true;}
 boolean applyWct(String reason,Object w){check(((android.window.WindowContainerTransaction)w).front,"restore reorders exact live task forward");restores++;return true;}
 TopDismissRegression(){sInstance=this;var key=new TopTaskState.Key(42,0,slots[4].taskToken);var t=topTaskState.request(key,sceneEpoch,true,true,true,true);topTaskState.commit(t,sceneEpoch);}
 static boolean hostSceneActive(){return sInstance.scene==ACTIVE&&sInstance.root!=null&&sInstance.screenOn&&!sInstance.keyguardLocked;}
 boolean controllerOwns(Slot s,int id,Object t){return controller;}
 boolean isActive(Slot s){return s==slots[4]&&root!=null;}
 void updateHint(Slot s){hints++;}void updateTouchRegion(Slot s){}void finishPendingSuspend(){finished++;}
 void preferMainAfterTopChange(){focused++;}
 void clearSlot(Slot s){s.occupied=false;s.taskToken=null;s.taskId=-1;s.taskEpoch++;s.promoting=false;clears++;}
 void returnToSystem(Slot s,boolean front,Runnable ok,Consumer<String> bad){returns++;this.front=front;success=ok;failure=bad;}
 void returnToSystem(Slot s,boolean front,boolean live,Runnable ok,Consumer<String> bad){liveMainReveal=live;returnToSystem(s,front,ok,bad);}
 boolean startLiveMainRevealTransition(Runnable r){if(!transitionAvailable)return false;topTransition=new Object();pendingHandback=r;return true;}
 void revealTopTaskTransition(){if(topTransition!=null)reveals++;}
 void cancelTopTaskTransition(){if(topTransition!=null)cancelled++;topTransition=null;}
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
  for(boolean right:new boolean[]{false,true}) {
   h=fresh();h.layout.tabletLandscape=true;h.layout.mainOnRight=right;h.screenW=3048;
   h.slots[4].rect=right?new int[]{0,606,904,2032}:new int[]{2144,606,3048,2032};
   int sign=right?-1:1;
   check(topGestureHeight()==1426&&topGestureBounds()==h.slots[4].rect,"portrait gesture uses actual pane bounds");
   begin(h,sign*80);check(Math.abs(h.sideDismiss.distance)<1000,"outward travel uses portrait pane width");
   moveTopDismiss(sign*210);endTopDismiss(false);
   check(h.returns==1,"portrait threshold uses pane width rather than full tablet width");
   h.nativeSuccess();check(h.clears==1&&h.topTaskState.owner()==null,"portrait push returns original real task on ack");
   h=fresh();h.layout.tabletLandscape=true;h.layout.mainOnRight=right;h.screenW=3048;
   h.slots[4].rect=right?new int[]{0,606,904,2032}:new int[]{2144,606,3048,2032};
   var original=new TopCandidate(new TopTaskState.Key(91,0,new Object()));h.covered=original;
   var returned=h.topTaskState.owner();begin(h,-sign*80);moveTopDismiss(-sign*300);endTopDismiss(false);
   check(h.returns==0&&h.pendingHandback!=null&&h.topTransition!=null,"inward release prepares live reveal before native handoff");
   h.pendingHandback.run();
   check(h.returns==1&&h.front&&h.liveMainReveal&&h.clears==0,"inward native handoff retains real canonical surface at initial scale");
   h.nativeSuccess();check(h.tabletCoveredMain==original&&h.tabletReturnedTop.equals(returned)
      &&h.clears==1&&h.focused==0&&h.reveals==1,"inward ack remembers covered main and expands only after native ownership confirmation");
   h=fresh();h.layout.tabletLandscape=true;h.layout.mainOnRight=right;
   begin(h,-sign*80);moveTopDismiss(-sign*400);endTopDismiss(false);h.pendingHandback.run();h.nativeFailure();
   check(h.slots[4].occupied&&h.clears==0&&h.cancelled==1&&h.reveals==0&&h.sideDismiss==null,
      "failed inward handoff cancels reveal and retains exact live binding");
   h=fresh();h.layout.tabletLandscape=true;h.layout.mainOnRight=right;h.transitionAvailable=false;
   begin(h,-sign*80);moveTopDismiss(-sign*400);endTopDismiss(false);
   check(h.returns==1&&h.front&&!h.liveMainReveal,"unavailable reveal preserves native handback without static substitute");
   h=fresh();h.layout.tabletLandscape=true;h.layout.mainOnRight=right;
   begin(h,-sign*80);endTopDismiss(true);
   check(h.tabletReturnedTop==null&&h.returns==0,"cancelled inward gesture never records or changes main task");
  }
  h=fresh();check(!beginTopDismiss(100,new Object()),"reject token replaced since DOWN");
  for(int state=0;state<8;state++){
   h=fresh();h.layout.tabletLandscape=true;var selected=h.topTaskState.owner();
   var oldMain=new TopCandidate(new TopTaskState.Key(91,0,new Object()));
   h.tabletCoveredMain=oldMain;h.tabletReturnedTop=selected;h.nativeInfo=new Info();
   h.nativeInfo.id=91;h.nativeInfo.token=oldMain.key.token;
   switch(state){case 1:h.nativeInfo.token=new Object();break;case 2:h.nativeInfo.user=10;break;
    case 3:h.nativeInfo.ordinary=false;break;case 4:h.nativeInfo.side=true;break;
    case 5:selected=new TopTaskState.Key(99,0,new Object());break;case 6:h.layout.tabletLandscape=false;break;case 7:h.nativeInfo=null;break;}
   h.restoreTabletCoveredMain(selected);
   check(h.restores==(state==0?1:0)&&h.tabletCoveredMain==null&&h.tabletReturnedTop==null,"covered-main restore validates exact live identity/user/root and consumes memory "+state);
  }
  h=fresh();h.layout.tabletLandscape=true;h.queue=true;h.tabletReturnedTop=h.topTaskState.owner();
  h.tabletCoveredMain=new TopCandidate(new TopTaskState.Key(91,0,new Object()));
  h.restoreTabletCoveredMain(h.topTaskState.owner());h.sceneEpoch++;h.shellWork.run();
  check(h.restores==0,"scene change cancels queued original-main restoration");
  check(!beginTopDismiss(Float.NaN,h.slots[4].taskToken),"reject nonfinite drag");
  System.out.println("Actual TOP dismissal lease/native handback checks="+checks+" passed");
 }
}'''
methods='\n'.join(member(sig) for sig in ('    private boolean topReady(', '    static int topGestureHeight(',
 '    static int[] topGestureBounds(', '    static int topGestureDirection(',
 '    private static int topDismissWidth(', '    static boolean topDismissDirectionAllowed(',
 '    static Object topDismissToken(', '    static boolean beginTopDismiss(', '    static void moveTopDismiss(', '    static void endTopDismiss(',
 '    private void restoreTabletCoveredMain('))
p=out/'com/jiusis/mistep/TopDismissRegression.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body.replace('__METHODS__',methods),encoding='utf8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
wct=out/'android/window/WindowContainerTransaction.java';wct.parent.mkdir(parents=True,exist_ok=True)
wct.write_text('package android.window; public class WindowContainerTransaction {public Object token;public boolean front;}',encoding='utf8')
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(p),str(wct),str(src/'TopTaskState.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TopDismissRegression'],check=True)
