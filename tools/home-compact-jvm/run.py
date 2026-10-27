import os
"""Compile and execute extracted production Home completion, retirement and compaction methods."""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[2];src=root/'module/src/main/java/com/jianguo/mistep'
out=Path(sys.argv[1]) if len(sys.argv)>1 else root/'build/tests/standalone'
out.mkdir(parents=True,exist_ok=True)
def method(t,s):
 a=t.index(s);b=t.index('{',a);depth=1;i=b+1
 while depth:
  if t[i]=='{':depth+=1
  elif t[i]=='}':depth-=1
  i+=1
 return t[a:i]
host=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
parts=['    private void completeHomePromotion(', '    private void clearSlot(', '    private void compactCells()', '    private static final class CellMovePlan', '    private void syncSlotWindowState(', '    private void onTaskIdKnown(', '    private void queueSidePromotion(', '    private void retrySidePromotion(', '    private void beginExternalPromotion(', '    private boolean externalPromotionCurrent(', '    private void afterExternalTransitionIdle(']
methods='\n'.join(method(host,s) for s in parts)+'\n'+method(host,'    private static final class PendingSidePromotion')
body=r"""
package com.jianguo.mistep;
import java.util.*;
public class HomeCompactRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class Bitmap{boolean recycled;void recycle(){recycled=true;}boolean isRecycled(){return recycled;}}
 static class Rect{int left,top,right,bottom;Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}}
 static class View{int hintUpdates,touchUpdates,rectUpdates;}
 static class Root{int layouts;void requestLayout(){layouts++;}}
 static class Controller{Object mTaskToken;Controller(Object t){mTaskToken=t;}}
 static class XposedHelpers{
  static Object getObjectField(Object o,String name){return o instanceof Controller?((Controller)o).mTaskToken:o;}
  static Object callMethod(Object o,String name,Object...args){((Idle)o).queue.add((Runnable)args[0]);return null;}
 }
 static class SystemClock{static long uptimeMillis(){return 10000;}}
 static class MiStepClipboard{static MiStepClipboard get(Object c){return new MiStepClipboard();}void close(){}}
 static class StatusBar{void hideFor(Promotion p,Runnable r){}}
 static class Idle{List<Runnable>queue=new ArrayList<>();}
 static class WindowState{final int taskId;int slotIndex;boolean active=true;WindowState(int t,int s){taskId=t;slotIndex=s;}void clear(){active=false;slotIndex=-1;}}
 static class VisibilityRepair{void reset(){}}
 static class Slot implements SlotPositions.Entry {
  int index,taskId=-1,removalCheckTask=-1,retiredTaskId=-1,taskUid,windowMode=6,modeRepairAttempts,scaleTaskId,scaleAttempts;
  Object taskToken,scaleSurface,bufferedDown,controller;String pkg;View view=new View();int[]rect;
  boolean nativeReleasePending,closing,occupied,parked,frozen,promoting,taskVisible,launching,injecting,modeAdmissionPending,scaleOnLeash,reassertPending;
  long taskEpoch=3,modeGuardUntil=1000;float scaleApplied;WindowState windowState;
  VisibilityRepair visibilityRepair=new VisibilityRepair();PendingSidePromotion pendingSidePromotion;
  Slot(int i){index=i;}public int position(){return index;}public void position(int v){index=v;}
 }
 static class Promotion{Slot slot;boolean cancelled,cancelRequested,externalLaunch,previousMainOnRight,targetMainOnRight;long sceneEpoch,bindingEpoch;int taskId;Object token;Home homeBeforeCover=new Home();Promotion(Slot s,Slot[] slots){this(s);}Promotion(Slot s){slot=s;taskId=s.taskId;token=s.taskToken;}}
 static class Home{int id=1;Object token=new Object();}
 static class HomeTaskReturn{int app;HomeTaskReturn(int a,Object b,int c,Object d){app=a;}}
 static class Layout{int[]cellBounds(int i){return new int[]{0,100+i*100,100,200+i*100};}}
 static class CellMovePreview {
  static class Move{Move(Rect a,Rect b,View c){}}
  CellMovePreview(Root r,Bitmap b,Rect x,List<Move>m){}void close(){}void setOnClosed(Runnable r){}
 }
 static class Main{List<Runnable>queue=new ArrayList<>();void postDelayed(Runnable r,long d){queue.add(r);}void post(Runnable r){queue.add(r);}}
 final int SLOT_COUNT=4,SLOT_MAIN=0;Slot[]slots=new Slot[4];Root root=new Root();Layout layout=new Layout();
 Promotion promotion;HomeTaskReturn coveredHome;CellMovePreview cellMovePreview;boolean compactPending;Slot launchCurrentSlot;Object cellMotion;
 static final int ACTIVE=1;int scene=ACTIVE;long sceneEpoch=1;boolean screenOn=true,keyguardLocked,transferBusy;long SIDE_PROMOTION_TIMEOUT_MS=3500;
 Object ctx=new Object();Idle transitions=new Idle();StatusBar statusBar=new StatusBar();Runnable observed;int promotionChecks,failures;
 Main main=new Main();Map<Integer,WindowState> taskWindowStates=new HashMap<>();final Object taskWindowStatesLock=new Object();
 int focusDone,previewFinished,registrations;boolean mainOnRight=true;
 void resumeNotificationLaunch(Promotion p){}void traceSwapEvent(String s){}void releaseCompactSnapshot(Promotion p){}void finishSwapPreview(Promotion p,long d){previewFinished++;}
 void finishPendingSuspend(){focusDone++;}void log(String s){}void closeSwapPreview(){}
 void recycleBuffered(Slot s){s.bufferedDown=null;}
 void updateHint(Slot s){s.view.hintUpdates++;}void updateTouchRegion(Slot s){s.view.touchUpdates++;}
 void applyRect(Slot s){s.view.rectUpdates++;}void reapplyScale(Slot s){}
 // This suite checks the real owner/geometry algorithm; surface frames have their own actual-class suite.
 void animateCellMoves(List<CellMovePlan> moves,int emptySlots){for(Slot s:slots){applyRect(s);updateHint(s);updateTouchRegion(s);}}
 void requestCompaction(){compactPending=true;}
 void publishDesktopSideTasks(){registrations++;}
 boolean isActive(Slot s){return s.index>0&&s.index<SLOT_COUNT&&slots[s.index]==s;}
 boolean executeOnShell(Runnable r){r.run();return true;}
 Home existingHomeTask(){return new Home();}
 void traceSwap(Promotion p){}
 void observeNativeCommit(Object token,Runnable r){observed=r;}
 void checkPromotion(Promotion p,int i){promotionChecks++;}
 void failPromotion(Promotion p,String why){failures++;}

 boolean controllerOwns(Slot s,int id,Object token){return s.taskId==id&&(token==null||token==s.taskToken);}
 void bindTask(Slot s,int id,Object token){s.taskId=id;s.taskToken=token;}
 void scheduleSideModeResolution(Slot s,int id){}
 __METHODS__
 HomeCompactRegression(){for(int i=0;i<4;i++){Slot s=slots[i]=new Slot(i);s.taskId=100+i;s.taskToken=new Object();s.pkg="app"+i;s.controller=new Controller(s.taskToken);s.occupied=i>0;s.windowState=new WindowState(s.taskId,i);taskWindowStates.put(s.taskId,s.windowState);s.rect=layout.cellBounds(i-1);}}
 void selection(int index){
  Slot mainOwner=slots[0],retired=slots[index];Object mainToken=mainOwner.taskToken;Promotion p=new Promotion(retired);promotion=p;
  List<Slot> survivors=new ArrayList<>();for(int i=1;i<4;i++)if(i!=index)survivors.add(slots[i]);
  Object c=survivors.get(0).controller,t=survivors.get(0).taskToken;WindowState w=survivors.get(0).windowState;
  long epoch=survivors.get(0).taskEpoch;int oldId=retired.taskId;long oldEpoch=retired.taskEpoch;
  retired.pendingSidePromotion=new PendingSidePromotion(retired,sceneEpoch,true,15000);retired.injecting=true;retired.launching=true;
  clearSlot(retired);completeHomePromotion(p);
  check(slots[0]==mainOwner&&mainOwner.taskToken==mainToken&&mainOnRight,"Home/main geometry and binding stay anchored");
  check(slots[1]==survivors.get(0)&&slots[2]==survivors.get(1),"both side tasks fill selected gap in original order");
  check(slots[3]==retired&&retired.index==3&&!retired.occupied,"empty retired owner moves to tail");
  check(slots[1].controller==c&&slots[1].taskToken==t&&slots[1].windowState==w&&slots[1].taskEpoch==epoch,"controller/token/window state/binding travel together");
  check(w.slotIndex==1&&slots[2].windowState.slotIndex==2,"orientation metadata follows new positions");
  check(slots[1].rect[1]==100&&slots[2].rect[1]==200,"TaskViews use new physical positions");
  check(retired.retiredTaskId==oldId&&retired.taskEpoch==oldEpoch+1&&retired.pendingSidePromotion==null&&retired.windowMode==-1&&!retired.injecting&&!retired.launching,"retired callbacks and gestures invalidated");
  int layouts=root.layouts;completeHomePromotion(p);check(root.layouts==layouts,"duplicate completion cannot move owners again");
  onTaskIdKnown(retired.index,oldId);check(!retired.occupied,"late retired task callback cannot resurrect it in tail");
 }
 public static void main(String[]args){
  for(int i=1;i<=3;i++)new HomeCompactRegression().selection(i);
  var h=new HomeCompactRegression();Slot gap=h.slots[1],moving=h.slots[2];h.clearSlot(gap);Object gesture=new Object();moving.bufferedDown=gesture;
  h.compactCells();check(h.compactPending&&h.slots[2]==moving&&moving.bufferedDown==gesture,"compaction waits without discarding active DOWN");
  moving.bufferedDown=null;h.compactCells();check(!h.compactPending&&h.slots[1]==moving,"pending gap fills after gesture end");
  h=new HomeCompactRegression();gap=h.slots[1];moving=h.slots[2];h.clearSlot(gap);moving.closing=true;h.compactCells();check(h.compactPending&&h.slots[2]==moving,"real closing leash keeps its native cell parent until finish");moving.closing=false;h.compactCells();check(h.slots[1]==moving,"completed close unblocks compaction");
  h=new HomeCompactRegression();gap=h.slots[1];moving=h.slots[2];h.clearSlot(gap);moving.promoting=true;h.compactCells();
  check(h.compactPending&&h.slots[2]==moving,"pending native callback keeps owner fixed");moving.promoting=false;h.compactCells();check(h.slots[1]==moving,"owner moves only after transition settles");
  h=new HomeCompactRegression();moving=h.slots[1];moving.windowMode=1;h.queueSidePromotion(moving,false);
  check(h.promotion!=null&&h.promotion.externalLaunch&&h.observed!=null,"native mode1 launch is observed instead of another fullscreen pending");
  check(h.promotionChecks==0&&h.main.queue.isEmpty(),"no ownership handoff before original transition completion");
  h.observed.run();check(h.promotionChecks==0&&h.transitions.queue.size()==1,"finish signal waits for native tracks idle");
  h.transitions.queue.remove(0).run();h.main.queue.remove(0).run();check(h.promotionChecks==1,"handoff observation follows original transition idle");
  h=new HomeCompactRegression();moving=h.slots[1];moving.windowMode=6;h.queueSidePromotion(moving,true);
  check(h.promotion==null&&moving.pendingSidePromotion!=null&&!h.main.queue.isEmpty(),"early Launcher notice waits for actual native mode1");
  moving.windowMode=1;h.main.queue.remove(0).run();check(h.promotion!=null&&h.promotion.externalLaunch,"same binding mode1 consumes early notice");
  Runnable late=h.observed;h.clearSlot(moving);late.run();check(h.transitions.queue.isEmpty(),"late finish for retired task cannot affect a replacement binding");
  System.out.println("actual Host Home/retirement/compaction/external checks="+checks+" passed");
 }
}
""".replace('__METHODS__',methods)
f=out/'HomeCompactRegression.java';f.write_text(body,encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(src/'SlotPositions.java'),str(src/'SidePromotionPolicy.java'),str(f)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.HomeCompactRegression'],check=True)
