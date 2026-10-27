import os
"""Exercise extracted Host native completion/cancellation, visual lease and external guard."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
source = root / 'module/src/main/java/com/jianguo/mistep/MiStepHost.java'
out = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'build/tests/standalone'
out.mkdir(parents=True, exist_ok=True)
text = source.read_text(encoding='utf-8-sig')

def member(signature):
    start = text.index(signature)
    brace = text.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]

methods = '\n'.join(member(s) for s in [
    '    private static final class LiveSwapVisualLease',
    '    private void finishLiveSwap(',
    '    private void finishLiveCollect(',
    '    private void completeLiveCollect(',
    '    private boolean livePromotionBound(',
    '    private void restoreLiveSwapVisual(',
    '    private void requestLiveSwapCancellation(',
    '    private boolean leaveExternalChangeToNative(',
])

body = r'''
package com.jianguo.mistep;
import java.util.*;
public class LiveHostRegression {
 static int checks;
 static void check(boolean value, String reason) { checks++; if(!value)throw new AssertionError(reason); }
 static class View {
  static final int VISIBLE=0, INVISIBLE=4, GONE=8;
  float alpha=1; int visibility=0, layouts, invalidates;
  float getAlpha(){return alpha;} void setAlpha(float a){alpha=a;}
  int getVisibility(){return visibility;} void setVisibility(int v){visibility=v;}
  void requestLayout(){layouts++;} void invalidate(){invalidates++;}
 }
 static class LiveTask { int id; Object token; String pkg; LiveTask(int i,Object t){id=i;token=t;pkg="main";} }
 static class Slot {
  int index=1, taskId=2; Object taskToken=new Object(); Controller controller;
  long taskEpoch=3; String pkg="side";
  boolean occupied=true, taskVisible=true, promoting=true, launching, parked, frozen, modeAdmissionPending;
  int windowMode=6; View hint=new View();
 }
 static class Promotion {
  static class Backdrop {int closes;void close(){closes++;}void closeAfterFrame(View v){if(v!=null&&v.layouts==0)throw new AssertionError("backdrop retired before Host restoration");closes++;}} Backdrop liveBackdrop;
  Slot slot; int taskId; Object token; LiveTask previousMain=new LiveTask(1,new Object());
  boolean cancelled, nativeCompleting, nativeSubmitted, cancelRequested, externalLaunch, nativeHome, nativeFlip, nativeCollect;
  long liveZoomGuardUntil;
  long sceneEpoch=7,bindingEpoch=3; LiveSwapVisualLease liveVisual;
  ArrayList<LiveSwapVisualLease> peerVisuals=new ArrayList<>();
  OwnedTask previousMainCandidate;
  Promotion(Slot s){slot=s;taskId=s.taskId;token=s.taskToken;}
 }
 static class MiStepLiveSwap {
  static int cancels; static Object cancelled;
  static void cancel(Object c){cancels++;cancelled=c;}
  static class Result {
   boolean succeeded, sideOwned, rolesConfirmed=true; Throwable error;
   Result(boolean a,boolean b,Throwable e){succeeded=a;sideOwned=b;error=e;}
  }
 }
 static class Queue {
  ArrayDeque<Runnable> queue=new ArrayDeque<>();
  void post(Runnable r){queue.add(r);} void flush(){while(!queue.isEmpty())queue.remove().run();}
 }
 static class Bar {int finishes;void finish(Promotion p){finishes++;}}
 static class Info {
  int taskId,userId,displayId,mode=1; Object token;
  Info(int id,Object t){taskId=id;token=t;}
  public int getWindowingMode(){return mode;}
 }
 static class Change {int mode=6;Info info;Change(Info i){info=i;}public int getMode(){return mode;}public Info getTaskInfo(){return info;}}
 static class Appeared {Info info; Appeared(Info i){info=i;}public Info getTaskInfo(){return info;}}
 static class Organizer {Object mLock=new Object();android.util.SparseArray<Appeared> mTasks=new android.util.SparseArray<>();}
 static class Controller {Object mTaskToken;Organizer mTaskOrganizer=new Organizer();Controller(Object t){mTaskToken=t;}}
 static class Pending {Object mTaskView;Pending(Object c){mTaskView=c;}}
 static class Transitions {ArrayList<Pending> mPending=new ArrayList<>();}
 static class XposedHelpers {
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
  static Object callMethod(Object o,String n,Object...args){try{var m=o.getClass().getMethod(n);m.setAccessible(true);return m.invoke(o);}catch(Exception e){throw new RuntimeException(e);}}
 }
 static final int ACTIVE=1,SLOT_MAIN=0;
 int scene=ACTIVE;long sceneEpoch=7;boolean screenOn=true,keyguardLocked,transferBusy,inputPass=true,unavailable,geometryRefreshAfterLive;
 static class HomeTaskReturn {boolean covers(int id,Object token){return true;}}
 Slot[] slots=new Slot[4];Promotion promotion;HomeTaskReturn coveredHome=new HomeTaskReturn();View root=new View();
 long zoomAnimUntil; Object pendingLiveZoom;
 Queue main=new Queue(),shell=new Queue();Bar statusBar=new Bar();Transitions transitions=new Transitions();
 static class OwnedTask {Object token;boolean recoveryCandidate=true;OwnedTask(Object t){token=t;}}
 Map<Object,OwnedTask> ownedTasks=new HashMap<>();int clears,binds,suspends,publishes,focuses;Runnable focus;
 LiveHostRegression(){for(int i=0;i<4;i++){slots[i]=new Slot();slots[i].index=i;slots[i].controller=new Controller(slots[i].taskToken);}promotion=new Promotion(slots[1]);promotion.liveVisual=new LiveSwapVisualLease(new View(),slots[1].hint);ownedTasks.put(promotion.token,new OwnedTask(promotion.token));promotion.previousMainCandidate=new OwnedTask(promotion.previousMain.token);ownedTasks.put(promotion.previousMain.token,promotion.previousMainCandidate);}
 boolean isActive(Slot s){return s.index>0&&slots[s.index]==s;}
 boolean executeOnShell(Runnable r){if(unavailable)return false;shell.post(r);return true;}
 boolean controllerOwns(Slot s,int id,Object t){return s.controller.mTaskToken==t&&s.taskId==id;}
 boolean mayWriteSideSurface(Slot s){return s.windowMode==6;}
 boolean ordinaryRoot(Object o,int mode){return o instanceof Info&&((Info)o).displayId==0&&((Info)o).mode==mode;}
 void log(String s){}void traceSwapEvent(String s){}void publishDesktopSideTasks(){publishes++;}
 void setLiveSwapInputBlocked(boolean b){inputPass=b;}
 void scheduleDisplayGeometryRefresh(){}
 void finishPendingSuspend(){suspends++;}
 int compactions;void requestCompaction(){compactions++;}
 void clearSlot(Slot s){clears++;s.taskId=-1;s.taskToken=null;s.taskEpoch++;}
 void bindTask(Slot s,int id,Object t){binds++;s.taskId=id;s.taskToken=t;s.taskEpoch++;ownedTasks.putIfAbsent(t,new OwnedTask(t));}
 void writeZoom(boolean on){}
 void updateCellSurfaceVisibility(Slot s){}
 void resumeNotificationLaunch(Promotion p){}
 void updateHint(Slot s){}
 void updateTouchRegion(Slot s){}
 void finishPromotionFocus(Promotion p,Runnable done,int attempt){focuses++;focus=done;}
 void finishLiveHome(Promotion p, MiStepLiveSwap.Result r){}
 __METHODS__
 void collectCompletion(){
  Promotion p=promotion;p.nativeCollect=true;finishLiveCollect(p,new MiStepLiveSwap.Result(true,true,null));
  check(promotion==p&&!p.cancelled&&p.nativeCompleting&&focuses==1&&inputPass,"collect retains input and ticket until Home focus handoff");
  check(statusBar.finishes==0&&compactions==0,"collect must not expose input or compact before Home focus");
  finishLiveCollect(p,new MiStepLiveSwap.Result(true,true,null));
  check(focuses==1,"duplicate native finish must not request Home focus twice");
  focus.run();
  check(promotion==null&&p.slot.occupied&&!p.slot.promoting&&!p.slot.frozen,"collected binding not released for next tap");
  check(!p.previousMainCandidate.recoveryCandidate&&!inputPass&&statusBar.finishes==1,"collection final cleanup missing");
  check(compactions==1,"real collection settles before requesting gap compaction");
  finishLiveCollect(p,new MiStepLiveSwap.Result(true,true,null));check(statusBar.finishes==1,"collection completed twice");
 }
 void collectCancellation(){
  Promotion p=promotion;p.nativeCollect=true;requestLiveSwapCancellation(p,"before submission");shell.flush();main.flush();
  check(promotion==null&&clears==1&&!inputPass,"unsent collect cancellation did not clear temporary binding");
 }
 void nativeRoleCompletion(){
  Promotion p=promotion;Object old=p.token,next=p.previousMain.token;LiveSwapVisualLease visual=p.liveVisual;
  p.slot.controller.mTaskToken=next;
  finishLiveSwap(p,new MiStepLiveSwap.Result(true,true,null));
  check(promotion==p&&!p.cancelled&&p.nativeCompleting,"retain ticket through final focus");
  check(clears==1&&binds==1&&p.slot.taskToken==next&&p.slot.taskId==1,"adopt facade-owned A logically exactly once");
  check(!ownedTasks.containsKey(old)&&ownedTasks.containsKey(next),"old B ownership retired, A retained");
  check(inputPass&&!visual.restored,"leave visual hole until facade commit plus focus");
  finishLiveSwap(p,new MiStepLiveSwap.Result(true,true,null));check(clears==1&&focuses==1,"duplicate recovery cannot bind twice");
  focus.run();check(promotion==null&&p.cancelled&&!inputPass&&visual.restored&&statusBar.finishes==1&&suspends==1,"unique final cleanup restores visuals/input then deferred exit");
 }
 void cancellation(){
  Promotion p=promotion;p.nativeSubmitted=true;MiStepLiveSwap.cancels=0;
  requestLiveSwapCancellation(p,"screen lock");
  check(promotion==p&&!p.cancelled&&p.cancelRequested&&inputPass,"cancellation preserves logical owner and input until recovery");
  requestLiveSwapCancellation(p,"timeout");check(shell.queue.size()==1,"repeat cancellation coalesces");
  shell.flush();check(MiStepLiveSwap.cancels==1&&MiStepLiveSwap.cancelled==p.slot.controller&&suspends==0,"cancel original controller only; no premature suspend");
  p.slot.controller.mTaskToken=p.previousMain.token;
  finishLiveSwap(p,new MiStepLiveSwap.Result(false,true,new RuntimeException("cancelled")));
  check(p.slot.taskToken==p.previousMain.token&&promotion==p,"late A6/B1 recovery accepted after cancellation");
  focus.run();check(promotion==null&&suspends==1,"recovered new owner available to deferred exit");
 }
 void beforeSubmission(){
  Promotion p=promotion;MiStepLiveSwap.cancels=0;requestLiveSwapCancellation(p,"before frame");shell.flush();
  check(promotion==p&&main.queue.size()==1,"unsubmitted cancellation waits for serialized Shell result");
  main.flush();check(promotion==null&&p.slot.taskId==2&&!p.slot.frozen&&!p.slot.promoting&&!inputPass&&MiStepLiveSwap.cancels==0,"preflight cancellation preserves original side binding");
  check(!ownedTasks.containsKey(p.previousMain.token),"confirmed original roles release the A recovery candidate only");
 }
 void uncertainCollect(){Promotion p=promotion;p.nativeCollect=true;var r=new MiStepLiveSwap.Result(false,false,new RuntimeException("inverse timeout"));r.rolesConfirmed=false;finishLiveCollect(p,r);check(p.slot.frozen&&p.slot.occupied&&!p.slot.promoting&&!p.slot.launching,"terminal uncertain collect must not block every empty cell");main.flush();check(promotion==null&&!p.slot.promoting,"collect releases global promotion even when one cell is quarantined");}
 void uncertainRoles(){Promotion p=promotion;p.nativeSubmitted=true;var r=new MiStepLiveSwap.Result(false,false,new RuntimeException("role timeout"));r.rolesConfirmed=false;finishLiveSwap(p,r);check(p.slot.frozen&&p.slot.promoting&&ownedTasks.containsKey(p.previousMain.token)&&ownedTasks.containsKey(p.token),"unconfirmed role timeout preserves both A/B recovery ledger entries");}
 void staleCompletion(){Promotion old=promotion;promotion=new Promotion(slots[2]);finishLiveSwap(old,new MiStepLiveSwap.Result(true,true,null));check(clears==0&&binds==0&&promotion.slot==slots[2],"stale callback cannot mutate a newer ticket");}
 void staleEpoch(){Promotion old=promotion;old.slot.taskEpoch++;finishLiveSwap(old,new MiStepLiveSwap.Result(true,true,null));check(clears==0&&binds==0&&!old.nativeCompleting,"stale binding epoch cannot retire a replacement task");}
 void zoomGuardCompletion(){
  Promotion p=promotion;p.nativeFlip=true;p.liveZoomGuardUntil=zoomAnimUntil=1200;
  p.slot.controller.mTaskToken=p.previousMain.token;
  finishLiveSwap(p,new MiStepLiveSwap.Result(true,true,null));
  check(zoomAnimUntil==0,"confirmed native finish releases its own 1200ms request deadline immediately");
  check(promotion==p&&inputPass,"deadline release does not bypass final focus/input recovery");
 }
 void newerZoomGuard(){
  Promotion p=promotion;p.nativeFlip=true;p.liveZoomGuardUntil=1200;zoomAnimUntil=1500;
  p.slot.controller.mTaskToken=p.previousMain.token;
  finishLiveSwap(p,new MiStepLiveSwap.Result(true,true,null));
  check(zoomAnimUntil==1500,"old completion cannot release another zoom animation deadline");
 }
 void uncertainZoomGuard(){
  Promotion p=promotion;p.nativeFlip=true;p.liveZoomGuardUntil=zoomAnimUntil=1200;
  var r=new MiStepLiveSwap.Result(false,false,new RuntimeException("role timeout"));r.rolesConfirmed=false;
  finishLiveSwap(p,r);check(zoomAnimUntil==1200,"unconfirmed recovery cannot release zoom guard");
 }
 void guard(){
  promotion=null;Slot s=slots[1];s.promoting=false;Info info=new Info(s.taskId,s.taskToken);s.controller.mTaskOrganizer.mTasks.put(s.taskId,new Appeared(info));Change c=new Change(info);
  check(leaveExternalChangeToNative(c),"owned canonical fullscreen CHANGE stays with native handler");
  c.mode=3;check(leaveExternalChangeToNative(c),"owned fullscreen TO_FRONT stays native");
  c.mode=2;check(!leaveExternalChangeToNative(c),"close/removal still reaches original TaskView");c.mode=6;
  info.mode=6;check(!leaveExternalChangeToNative(c),"native mode6 task unaffected");info.mode=1;
  info.displayId=1;check(!leaveExternalChangeToNative(c),"other display unaffected");info.displayId=0;
  transitions.mPending.add(new Pending(s.controller));check(!leaveExternalChangeToNative(c),"our pending transition retains original handler");transitions.mPending.clear();
  promotion=new Promotion(s);check(!leaveExternalChangeToNative(c),"ordinary A/B native promotion never bypassed");
  promotion.externalLaunch=true;check(leaveExternalChangeToNative(c),"exact external ticket allowed");
  promotion.bindingEpoch++;check(!leaveExternalChangeToNative(c),"stale external binding rejected");promotion=null;
  s.modeAdmissionPending=true;check(!leaveExternalChangeToNative(c),"new-task mode admission untouched");s.modeAdmissionPending=false;
  s.controller.mTaskOrganizer.mTasks.put(s.taskId,new Appeared(new Info(s.taskId,new Object())));
  check(!leaveExternalChangeToNative(c),"canonical token mismatch preserves original behavior");
 }
 public static void main(String[]args){
  View backing=new View(),hint=new View(),peer=new View();backing.alpha=.37f;hint.alpha=.82f;hint.visibility=4;
  LiveSwapVisualLease lease=new LiveSwapVisualLease(backing,hint);
  check(backing.alpha==0&&hint.alpha==0&&hint.visibility==4&&peer.alpha==1,"only selected cell drawings hidden without visibility lifecycle change");
  lease.restore();check(backing.alpha==.37f&&hint.alpha==.82f&&hint.visibility==4,"precise original alpha/visibility restored");
  View retiredHint=new View();retiredHint.visibility=View.GONE;
  LiveSwapVisualLease retired=new LiveSwapVisualLease(null,retiredHint);
  retiredHint.visibility=View.VISIBLE;retired.restore();
  check(retiredHint.visibility==View.VISIBLE&&retiredHint.alpha==1f,"Home retirement keeps new empty hint visible when releasing the drawing lease");
  backing.alpha=.6f;lease.restore();check(backing.alpha==.6f,"restoration idempotent");
  new LiveHostRegression().nativeRoleCompletion();new LiveHostRegression().cancellation();new LiveHostRegression().beforeSubmission();new LiveHostRegression().uncertainRoles();new LiveHostRegression().uncertainCollect();new LiveHostRegression().staleCompletion();new LiveHostRegression().staleEpoch();new LiveHostRegression().guard();
  new LiveHostRegression().zoomGuardCompletion();new LiveHostRegression().newerZoomGuard();new LiveHostRegression().uncertainZoomGuard();
  new LiveHostRegression().collectCompletion();new LiveHostRegression().collectCancellation();
  System.out.println("actual Host live completion/cancel/visual/external guard checks="+checks+" passed");
 }
}
'''.replace('__METHODS__', methods)
java = out / 'LiveHostRegression.java'
java.write_text(body, encoding='utf8')
sparse = out / 'android/util/SparseArray.java'
sparse.parent.mkdir(parents=True, exist_ok=True)
sparse.write_text('package android.util; public class SparseArray<T> {private final java.util.Map<Integer,T> map=new java.util.HashMap<>(); public T get(int k){return map.get(k);} public void put(int k,T v){map.put(k,v);}}',encoding='utf8')
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), str(sparse), str(java)],check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jianguo.mistep.LiveHostRegression'],check=True)
