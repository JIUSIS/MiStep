import os
"""Compile and execute extracted production Home completion, retirement and compaction methods."""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[2];src=root/'module/src/main/java/com/jiusis/mistep'
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
parts=['    private void completeHomePromotion(', '    private void clearSlot(', '    private void compactCells()', '    private void compactCellsAfterCollection()', '    private static final class CellMovePlan', '    private void syncSlotWindowState(', '    private void onTaskIdKnown(', '    private void queueSidePromotion(', '    private void retrySidePromotion(', '    private void beginExternalPromotion(', '    private boolean externalPromotionCurrent(', '    private void afterExternalTransitionIdle(']
methods='\n'.join(method(host,s) for s in parts)+'\n'+method(host,'    private static final class PendingSidePromotion')
parts=['    private boolean compactScrollingSideRows()', '    private void mapSideSlots(',
       '    private int maxSideOffset()', '    private void finishSideScroll()',
       '    private void commitSideScrollPosition()', '    private boolean nearSideViewport(']
methods+='\n'+'\n'.join(method(host,s) for s in parts)
methods+='\n'+'\n'.join(method(host,s) for s in [
    '    private void appendSideRow()', '    private void finishAppendingSideRow(',
    '    private void releaseUnattachedSideView('])
body=r"""
package com.jiusis.mistep;
import java.util.*;
public class HomeCompactRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class Bitmap{boolean recycled;void recycle(){recycled=true;}boolean isRecycled(){return recycled;}}
 static class Rect{int left,top,right,bottom;Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}}
 static class View{int hintUpdates,touchUpdates,rectUpdates,releases;}
 static class Root{int layouts;void requestLayout(){layouts++;}}
 static class SideInputViewport{int removals;void removeView(View v){removals++;}}
 static class Controller{Object mTaskToken;Controller(Object t){mTaskToken=t;}}
 static class XposedHelpers{
  static Object getObjectField(Object o,String name){return o instanceof Controller?((Controller)o).mTaskToken:o;}
  static Object callMethod(Object o,String name,Object...args){
   if(o instanceof View v && name.equals("release"))v.releases++;
   else ((Idle)o).queue.add((Runnable)args[0]);
   return null;
  }
 }
 static class SystemClock{static long uptimeMillis(){return 10000;}}
 static class MiStepClipboard{static MiStepClipboard get(Object c){return new MiStepClipboard();}void close(){}}
 static class MiStepTopNativeCaption{static void refresh(){}} // Separate production caption/queue suite.
 static class StatusBar{void hideFor(Promotion p,Runnable r){}}
 static class Idle{List<Runnable>queue=new ArrayList<>();}
 static class WindowState{final int taskId;int slotIndex;boolean active=true;WindowState(int t,int s){taskId=t;slotIndex=s;}void clear(){active=false;slotIndex=-1;}}
 static class VisibilityRepair{void reset(){}}
 static class Slot implements SlotPositions.Entry {
  int index,sideRow=-1,taskId=-1,removalCheckTask=-1,retiredTaskId=-1,taskUid,windowMode=6,modeRepairAttempts,scaleTaskId,scaleAttempts;
  Object taskToken,scaleSurface,bufferedDown,controller;String pkg;View view=new View(),hint;int[]rect;
  boolean nativeReleasePending,closing,occupied,parked,frozen,promoting,taskVisible,launching,injecting,modeAdmissionPending,scaleOnLeash,reassertPending;
  long taskEpoch=3,modeGuardUntil=1000;float scaleApplied;WindowState windowState;
  VisibilityRepair visibilityRepair=new VisibilityRepair();PendingSidePromotion pendingSidePromotion;
  Slot(int i){index=i;}public int position(){return index;}public void position(int v){index=v;}
 }
 static class Promotion{Slot slot;boolean cancelled,cancelRequested,externalLaunch,previousMainOnRight,targetMainOnRight;long sceneEpoch,bindingEpoch;int taskId;Object token;Home homeBeforeCover=new Home();Promotion(Slot s,Slot[] slots){this(s);}Promotion(Slot s){slot=s;taskId=s.taskId;token=s.taskToken;}}
 static class Home{int id=1;Object token=new Object();}
 static class HomeTaskReturn{int app;HomeTaskReturn(int a,Object b,int c,Object d){app=a;}}
 static class Layout{boolean tabletLandscape;int cellCount=3;int[]cellBounds(int i){return new int[]{0,100+i*100,100,200+i*100};}}
 static class CellMovePreview {
  static class Move{Move(Rect a,Rect b,View c){}}
  CellMovePreview(Root r,Bitmap b,Rect x,List<Move>m){}void close(){}void setOnClosed(Runnable r){}
 }
 static class Main{List<Runnable>queue=new ArrayList<>();void postDelayed(Runnable r,long d){queue.add(r);}void post(Runnable r){queue.add(r);}}
 int SLOT_COUNT=4,CELL_SLOT_COUNT=4,SLOT_TOP=4;final int SLOT_MAIN=0;Slot[]slots=new Slot[4];Root root=new Root();Layout layout=new Layout();
 static class TopState {Object owner(){return null;}void release(Object key,boolean dismissed){}}
 TopState topTaskState=new TopState();Slot topOriginalSlot;
 Promotion promotion;HomeTaskReturn coveredHome;CellMovePreview cellMovePreview;boolean compactPending;Slot launchCurrentSlot;Object cellMotion;
 final List<Slot> sideOrder=new ArrayList<>(),sideAttached=new ArrayList<>();
 final Map<Slot,Float> sideCompactionShift=new IdentityHashMap<>();
 boolean sideArmed,sideCaptured,sideCompacting,sideScrolling,tabletDevice;Object sideScrollAnimator,pendingSideView;
 float sideOffset,savedSideOffset;
 int animatedSideRows,appendedSideRows;
 boolean failSideFactory,failSideAttach;
 static HomeCompactRegression sInstance;
 Object factory=this,executor=new Object();
 final SideInputViewport sideInputViewport=new SideInputViewport();
 static final int LAUNCH_TIMEOUT_MS=4000;
 java.util.function.Consumer<Object> sideCallback;
 boolean lastFullSideLayout;
 static class Options{boolean scrollSideTasks;}
 Options workspaceOptions=new Options();
 static class MiStepLayout{static final int MAX_CELL_COUNT=4;}
 static class DeviceProfile{static boolean isTablet(Object owner){return ((HomeCompactRegression)owner).tabletDevice;}}
 static class android{static class animation{static class ValueAnimator{static boolean areAnimatorsEnabled(){return true;}}}}
 boolean isSideMotionRunning(){return sideScrollAnimator!=null;}
 boolean ensureSideViewport(){return true;}
 boolean canMoveSideRows(){return true;}
 void layoutSideViewport(boolean all){lastFullSideLayout=all;}
 void syncSideViewportFocus(){}
 void recycleSideVelocity(){}
 boolean onTaskViewCreated(Slot s,Object view,boolean next){
  s.view=(View)view;s.hint=new View();sideAttached.add(s);return !failSideAttach;
 }
 void animateSideCompaction(){animatedSideRows=sideCompactionShift.size();sideCompacting=true;sideScrollAnimator=new Object();}
 void cancelSideAnimator(){sideScrollAnimator=null;sideCompacting=false;sideCompactionShift.clear();}
 String describeTasks(){return "";}
 static final int ACTIVE=1;int scene=ACTIVE;long sceneEpoch=1;boolean screenOn=true,keyguardLocked,transferBusy;long SIDE_PROMOTION_TIMEOUT_MS=3500;
 Object ctx=this;Idle transitions=new Idle();StatusBar statusBar=new StatusBar();Runnable observed;int promotionChecks,failures;
 Main main=new Main();Map<Integer,WindowState> taskWindowStates=new HashMap<>();final Object taskWindowStatesLock=new Object();
 int focusDone,previewFinished,registrations;boolean mainOnRight=true;
 void resumeNotificationLaunch(Promotion p){}void traceSwapEvent(String s){}void releaseCompactSnapshot(Promotion p){}void finishSwapPreview(Promotion p,long d){previewFinished++;}
 void releaseRetainedTopSnapshot(){}
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
 boolean legacyTaskInfoCallbacks;int taskInfoCalls;Object lastInfo;
 static class ShellCompat {
  static Object info;
  static Object currentTaskInfo(Object c,int id){return info;}
  static Object call(Object o,String n,Object...a){return XposedHelpers.callMethod(o,n,a);}
  static void createTaskView(Object f,Object ctx,Object executor,java.util.function.Consumer<Object> callback){
   HomeCompactRegression h=(HomeCompactRegression)f;
   h.appendedSideRows++;
   if(h.failSideFactory)throw new IllegalStateException("factory failed");
   h.sideCallback=callback;
  }
 }
 void onTaskInfo(int slot,Object info){taskInfoCalls++;lastInfo=info;}
 void bindTask(Slot s,int id,Object token){s.taskId=id;s.taskToken=token;}
 void scheduleSideModeResolution(Slot s,int id){}
 __METHODS__
 HomeCompactRegression(){sInstance=this;for(int i=0;i<4;i++){Slot s=slots[i]=new Slot(i);s.taskId=100+i;s.taskToken=new Object();s.pkg="app"+i;s.controller=new Controller(s.taskToken);s.occupied=i>0;s.windowState=new WindowState(s.taskId,i);taskWindowStates.put(s.taskId,s.windowState);s.rect=layout.cellBounds(i-1);}}
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
 static void fixedAfterScrolling() {
  var h=new HomeCompactRegression();
  h.SLOT_COUNT=6;h.SLOT_TOP=5;h.slots=Arrays.copyOf(h.slots,9);
  for(int i=4;i<h.slots.length;i++)h.slots[i]=new Slot(i);
  Slot first=h.slots[1],second=h.slots[2],third=h.slots[3],fourth=h.slots[6],fifth=h.slots[7],top=h.slots[5];
  fourth.occupied=fifth.occupied=true;
  h.sideOrder.addAll(List.of(first,second,third,fourth,fifth,h.slots[8]));
  h.sideScrolling=true;h.sideOffset=2f;h.mapSideSlots(2);
  h.compactCells();
  check(!h.sideScrolling&&h.sideOffset==0f&&h.slots[1]==first&&h.slots[3]==third,
        "disabling scrolling returns to the original three fixed cells");
  check(h.slots[6]==fourth&&h.slots[7]==fifth&&h.slots[5]==top,
        "the extra TaskViews and the independent top cell keep their own positions");

  Promotion p=new Promotion(first);h.promotion=p;
  Object secondToken=second.taskToken,thirdSurfaceOwner=third.controller;
  h.clearSlot(first);h.completeHomePromotion(p);
  check(h.slots[1]==second&&h.slots[2]==third&&h.slots[3]==first&&!first.occupied,
        "Home promotion after disabling scrolling fills the fixed gap in order");
  check(h.slots[1].taskToken==secondToken&&h.slots[2].controller==thirdSurfaceOwner,
        "fixed mode moves the original TaskView owners");
  check(h.slots[6]==fourth&&h.slots[7]==fifth&&h.appendedSideRows==0,
        "fixed compaction keeps the extra rows out of the viewport");
  check(h.animatedSideRows==2&&h.sideCompactionShift.get(second)==1f
        &&h.sideCompactionShift.get(third)==1f,"both surviving views take the real movement path");

  int publications=h.registrations;
  h.lastFullSideLayout=false;
  h.finishSideScroll();
  check(h.lastFullSideLayout&&h.registrations>publications&&!h.sideCompacting,
        "fixed compaction completion restores fixed visibility and publishes final bindings");

  h.workspaceOptions.scrollSideTasks=true;h.compactCells();
  check(h.sideScrolling&&h.sideOffset==1f&&h.slots[1]==third&&h.slots[2]==fourth&&h.slots[3]==fifth,
        "reopening brings back the preserved scroll position and live owners");
 }
 static void tabletScrolling(boolean landscape) {
  var h=new HomeCompactRegression();h.tabletDevice=true;
  h.layout.tabletLandscape=landscape;h.layout.cellCount=landscape?4:3;
  h.SLOT_COUNT=6;h.SLOT_TOP=5;h.CELL_SLOT_COUNT=h.layout.cellCount+1;
  h.slots=Arrays.copyOf(h.slots,6);
  h.slots[4]=new Slot(4);h.slots[5]=new Slot(5);Slot[] owners=h.slots.clone();
  for(int i=1;i<=5;i++){owners[i].occupied=true;owners[i].taskId=100+i;}
  h.workspaceOptions.scrollSideTasks=true;h.compactCells();
  check(h.sideScrolling&&h.sideOrder.equals(List.of(owners[1],owners[2],owners[3],owners[4])),
        "enable in either orientation registers four tablet owners without including TOP");
  check(h.appendedSideRows==1,"a full tablet strip requests one new empty task owner");
  h.slots=Arrays.copyOf(h.slots,8);
  Slot overflow=h.slots[6]=new Slot(6);overflow.occupied=true;overflow.taskId=106;
  Slot plus=h.slots[7]=new Slot(7);
  h.sideOrder.addAll(List.of(overflow,plus));
  h.layout.tabletLandscape=true;h.layout.cellCount=4;h.CELL_SLOT_COUNT=5;
  check(h.maxSideOffset()==2,"wide strip supports two extra positions");
  h.mapSideSlots(2);
  check(h.slots[4]==plus&&h.slots[2]==owners[4]&&h.slots[5]==owners[5],
        "wide strip uses its fourth slot and preserves independent TOP");
  h.layout.tabletLandscape=false;h.layout.cellCount=3;h.CELL_SLOT_COUNT=4;
  check(h.maxSideOffset()==3,"portrait permits an additional position");
  h.mapSideSlots(3);
  check(h.slots[1]==owners[4]&&h.slots[2]==overflow&&h.slots[3]==plus
        &&h.slots[4]==owners[1]&&h.slots[5]==owners[5]&&h.slots[6]==owners[2]&&h.slots[7]==owners[3],
        "portrait overflow starts at the spare index and skips independent TOP");
  h.sideOffset=3;h.workspaceOptions.scrollSideTasks=false;h.compactCells();
  check(h.slots[1]==owners[1]&&h.slots[3]==owners[3]&&h.slots[4]==owners[4]
        &&h.savedSideOffset==3,"fixed portrait preserves offscreen owners and stores the offset");
  h.workspaceOptions.scrollSideTasks=true;h.compactCells();
  check(h.sideOffset==3&&h.slots[1]==owners[4]&&h.slots[2].taskId==106&&h.slots[3]==plus,
        "re-enabling restores the same task IDs, owners, and offset");
 }
 static void extraTaskViewFailures(){
  var h=new HomeCompactRegression();
  h.SLOT_COUNT=6;h.SLOT_TOP=5;h.slots=Arrays.copyOf(h.slots,6);
  h.slots[4]=new Slot(4);h.slots[5]=new Slot(5);
  h.workspaceOptions.scrollSideTasks=true;h.failSideFactory=true;h.compactCells();
  check(h.slots.length==6&&h.sideOrder.size()==3&&h.pendingSideView==null,
        "a failed factory cannot leave an unusable last row");
  h.failSideFactory=false;h.compactCells();h.compactCells();
  check(h.appendedSideRows==2&&h.pendingSideView!=null,"one pending allocation is shared");
  h.workspaceOptions.scrollSideTasks=false;h.compactCells();
  check(!h.sideScrolling,"waiting for a new view cannot delay the fixed-mode switch");
  var late=h.sideCallback;h.main.queue.remove(0).run();
  check(h.pendingSideView==null&&h.slots.length==6,"missing callback expires without a placeholder");
  h.workspaceOptions.scrollSideTasks=true;h.compactCells();
  var staleView=new View();late.accept(staleView);h.main.queue.remove(1).run();
  check(staleView.releases==1&&h.pendingSideView!=null&&h.sideOrder.size()==3,
        "late view is released without consuming the new request");
  var badView=new View();h.failSideAttach=true;h.sideCallback.accept(badView);
  h.main.queue.remove(1).run();h.main.queue.remove(0).run();
  check(h.slots.length==6&&h.sideAttached.isEmpty()&&h.sideOrder.size()==3
        &&badView.releases==1&&h.sideInputViewport.removals==2,"failed attachment rolls back the owner and its views");
  h.failSideAttach=false;h.compactCells();View ready=new View();
  h.sideCallback.accept(ready);h.main.queue.remove(1).run();h.main.queue.remove(0).run();
  check(h.slots.length==7&&h.slots[6].view==ready&&h.sideOrder.size()==4
        &&!h.slots[6].occupied&&ready.releases==0,"the next request provides a usable empty row");
 }
 public static void main(String[]args){
  tabletScrolling(false);
  tabletScrolling(true);
  extraTaskViewFailures();
  for(int selected=1;selected<=4;selected++){
   var tablet=new HomeCompactRegression();tablet.layout.tabletLandscape=true;
   tablet.SLOT_COUNT=6;tablet.CELL_SLOT_COUNT=5;tablet.SLOT_TOP=5;tablet.slots=new Slot[6];
   for(int i=0;i<6;i++){
    Slot s=tablet.slots[i]=new Slot(i);s.rect=tablet.layout.cellBounds(i-1);
    s.occupied=i==selected||i==5;s.taskId=200+i;s.taskToken=new Object();
    s.controller=new Controller(s.taskToken);s.pkg="tablet"+i;
   }
   Slot chosen=tablet.slots[selected],upper=tablet.slots[5];
   tablet.compactCellsAfterCollection();
   check(tablet.slots[1]==chosen&&chosen.index==1,
     "new tablet collection immediately fills the first empty cell");
   check(tablet.slots[5]==upper&&upper.occupied,
     "collection keeps the independent portrait pane fixed");
   if(selected>1){
    tablet.transferBusy=true;tablet.compactCells();
    check(tablet.compactPending&&tablet.slots[1]==chosen,
      "a removal during collection waits for the active transfer");
    tablet.transferBusy=false;tablet.compactCellsAfterCollection();
    check(!tablet.compactPending&&tablet.slots[1]==chosen&&chosen.index==1,
      "collection completion must not drop pending removal compaction");
   }
  }
  for(int gap=1;gap<=4;gap++){
   var tablet=new HomeCompactRegression();tablet.layout.tabletLandscape=true;
   tablet.SLOT_COUNT=6;tablet.CELL_SLOT_COUNT=5;tablet.SLOT_TOP=5;tablet.slots=new Slot[6];
   for(int i=0;i<6;i++){
    Slot s=tablet.slots[i]=new Slot(i);s.rect=tablet.layout.cellBounds(i-1);
    s.occupied=i>0;s.taskId=200+i;s.taskToken=new Object();
    s.controller=new Controller(s.taskToken);s.pkg="tablet"+i;
   }
   Slot[] original=tablet.slots.clone();
   Slot chosen=original[gap],upper=original[5];
   tablet.clearSlot(chosen);
   tablet.compactPending=true;tablet.compactCells();
   int write=1;
   for(int originalIndex=1;originalIndex<=4;originalIndex++) if(originalIndex!=gap){
    check(tablet.slots[write]==original[originalIndex]
      && tablet.slots[write].index==write,
      "tablet top-row task fills the first available gap");
    write++;
   }
   check(tablet.slots[write]==chosen&&!chosen.occupied&&chosen.index==write,
     "tablet cleared task moves to the tail of the top row");
   check(tablet.slots[5]==upper&&upper.occupied&&!tablet.compactPending,
     "tablet independent portrait pane stays fixed and compaction settles");
  }
  for(int i=1;i<=3;i++)new HomeCompactRegression().selection(i);
  fixedAfterScrolling();
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
  h=new HomeCompactRegression();h.legacyTaskInfoCallbacks=true;ShellCompat.info=new Object();
  h.onTaskIdKnown(1,h.slots[1].taskId);
  check(h.taskInfoCalls==1&&h.lastInfo==ShellCompat.info,"legacy creation did not seed task state");
  h=new HomeCompactRegression();h.legacyTaskInfoCallbacks=false;h.onTaskIdKnown(1,h.slots[1].taskId);
  check(h.taskInfoCalls==0,"modern listener got duplicate initial task state");
  h=new HomeCompactRegression();h.legacyTaskInfoCallbacks=true;ShellCompat.info=null;h.onTaskIdKnown(1,h.slots[1].taskId);
  check(h.taskInfoCalls==0,"missing canonical info fabricated task state");
  System.out.println("actual Host Home/retirement/compaction/external checks="+checks+" passed");
 }
}
""".replace('__METHODS__',methods)
f=out/'HomeCompactRegression.java';f.write_text(body,encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(src/'SlotPositions.java'),str(src/'SidePromotionPolicy.java'),str(f)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.HomeCompactRegression'],check=True)
