"""Replay real retained-parent repair after an already-visible task escaped to DTA."""
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = Path(sys.argv[2]) if len(sys.argv)>2 else root / 'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
source = (src / 'MiStepHost.java').read_text(encoding='utf8')

def extract(signature):
    start = source.index(signature); pos = source.index('{', start) + 1; depth = 1
    while depth:
        depth += (source[pos] == '{') - (source[pos] == '}'); pos += 1
    return source[start:pos]

actual = '\n'.join(extract(s) for s in (
    '    private static final class RenderBinding {',
    '    private void appendRetainedSurfaces(',
    '    private void appendSlotViewport(',
    '    private void trackRetainedSurfaces(',
    '    private void appendWorkspaceSurfaces(',
    '    private void appendHiddenKeyguardSurfaces(',
    '    private void restoreRetainedWorkspaceSurfaces(',
    '    private void reassertSideTaskViews(',
    '    private boolean ensureSideViewport(',
    '    private Rect sideViewportBounds('))
if '    private void stageSideViewport(' in source:
    actual += '\n' + extract('    private void stageSideViewport(')
actual += '\n' + extract('    private boolean retainedResumeStaging(')
actual += '\n' + extract('    private void syncSideViewportFocus(')
focus_stub = r'''package android.window;
public class WindowContainerTransaction {
 public final java.util.Map<Object,Boolean> focusable=new java.util.HashMap<>();
 public void setFocusable(Object token,boolean allowed){focusable.put(token,allowed);}
}'''
stub = r'''package android.view;
import java.util.*;
public class SurfaceControl {
 public static class Leash {public Object parent,crop;public float x=99,y=99,k=1,alpha;public int layer=99;public boolean shown,valid=true;public boolean isValid(){return valid;}public void release(){valid=false;}}
 public static class Builder {
  final Leash layer=new Leash();
  public Builder setName(String name){return this;}
  public Builder setParent(Object parent){layer.parent=parent;return this;}
  public Builder setContainerLayer(){return this;}
  public Leash build(){return layer;}
 }
 public static class Transaction {
  public static final List<Transaction> all=new ArrayList<>();public int applies,closes;public final List<String> ops=new ArrayList<>();final List<Runnable> pending=new ArrayList<>();
  public Transaction(){all.add(this);}public Transaction reparent(Leash l,Object p){ops.add("parent");pending.add(()->l.parent=p);return this;}
  public Transaction setLayer(Leash l,int z){ops.add("layer");pending.add(()->l.layer=z);return this;}
  public Transaction setPosition(Leash l,float x,float y){ops.add("position");pending.add(()->{l.x=x;l.y=y;});return this;}
  public Transaction setWindowCrop(Leash l,Object c){ops.add("crop");pending.add(()->l.crop=c);return this;}
  public Transaction setMatrix(Leash l,float a,float b,float c,float d){ops.add("matrix");pending.add(()->l.k=a);return this;}
  public Transaction setAlpha(Leash l,float a){ops.add("alpha");pending.add(()->l.alpha=a);return this;}
  public Transaction show(Leash l){ops.add("show");pending.add(()->l.shown=true);return this;}
  public void apply(){applies++;pending.forEach(Runnable::run);pending.clear();}public void close(){closes++;}
 }
}'''
body = r'''package com.jiusis.mistep;
import java.util.*;import android.graphics.Rect;import android.view.SurfaceControl.Leash;import android.view.SurfaceControl.Transaction;
public class SideParentRegression {
 static final int SLOT_MAIN=0,SLOT_TOP=4,ACTIVE=2,RESUMING=1;int scene=ACTIVE;long sceneEpoch=7;boolean screenOn=true,topFace,keyguardLocked,retainedForLock,keyguardWindowHidden;Object root=new Object();
 boolean retainedOnExit;float workspaceAlpha=1f;
 MiStepLayout layout=MiStepLayout.of(1080,2460,false,2.75f);
 final int screenH=2460;
 final Rect sideViewportCrop=new Rect();
 Object sideViewport,sideViewportHost,hostSurface=new Leash();
 Object liveHostRootSurface(){return hostSurface;}
 static class Controller {Object mTaskLeash,mSurfaceControl;int task;Object token;}
 static class MiStepLiveSwap64 {
  static final Map<Controller,Object> borrowed=new IdentityHashMap<>();
  static boolean isBorrowingPeer(Object controller,int taskId,Object token){
   return controller instanceof Controller c && c.task==taskId && token.equals(borrowed.get(c));
  }
 }
 static class View {int width=300,height=640;Rect clip;int getWidth(){return width;}int getHeight(){return height;}Rect getClipBounds(){return clip;}}
 static class Slot {int index,taskId,sideRow=-1,windowMode=6,currentMode=6;long taskEpoch=3;Object taskToken=new Object(),crop=new Object();Controller controller=new Controller();View view=new View();boolean occupied=true,promoting,launching,closing,frozen,parked,sideShown,render=true,writable=true;float scale=.247f;Slot(int i){index=i;taskId=100+i;controller.task=taskId;controller.token=taskToken;controller.mTaskLeash=new Leash();controller.mSurfaceControl=new Leash();}}
 final Slot[] slots={new Slot(0),new Slot(1),new Slot(2),new Slot(3),new Slot(4)};
 boolean presentsSlot(Slot s){return true;}
 final Queue<Runnable> shell=new ArrayDeque<>();static class Main {Queue<Runnable> q=new ArrayDeque<>();void post(Runnable r){q.add(r);}}final Main main=new Main();int scales;boolean queueAvailable=true;
 public static class Transitions {int calls;public void setTaskViewVisible(Controller c,boolean visible){calls++;/* OEM no-op: already visible */}}final Transitions transitions=new Transitions();
 boolean executeOnShell(Runnable r){if(!queueAvailable)return false;shell.add(r);return true;}
 final Map<Object,Boolean> focusable=new HashMap<>();
 boolean applyWct(String name,Object wct){focusable.putAll(((android.window.WindowContainerTransaction)wct).focusable);return true;}
 boolean canRender(Slot s){return s.render&&!keyguardLocked&&!s.parked;}boolean mayWriteSideSurface(Slot s){return s.writable;}
 boolean isActive(Slot s){return s.render;}
 boolean holdingFlip,transferBusy;Object promotion;boolean holdsLiveFlipZoom(){return holdingFlip;}
 static class NativeObservation {java.util.concurrent.atomic.AtomicBoolean consumed=new java.util.concurrent.atomic.AtomicBoolean();Object token;}
 NativeObservation pendingNativeObservation;
 final FinishTransactions<Long> workspaceFinishes=new FinishTransactions<>(16);
 final FinishTransactions<RenderBinding> finishTransactions=new FinishTransactions<>(16);
 final FinishTransactions<NativeObservation> nativeObservations=new FinishTransactions<>(16);
 static class Info {int type;Info(int t){type=t;}int getType(){return type;}List<Object> getChanges(){return List.of();}}
 boolean isSideToken(Object t){return false;}
 boolean controllerOwns(Slot s,int id,Object token){return s.controller!=null&&s.controller.task==id&&Objects.equals(s.controller.token,token);}
 Object currentOrganizedTaskInfo(Slot slot,int id,Object token){return controllerOwns(slot,id,token)?slot.currentMode:null;}
 int windowModeOf(Object info){return info instanceof Integer value?value:-1;}
 Object getSurfaceField(Controller c,String n){return n.equals("mTaskLeash")?c.mTaskLeash:c.mSurfaceControl;}
 Object logicalBounds(Slot s){return s.crop;}float scaleFor(Slot s){return s.scale;}void reapplyScale(Slot s){scales++;}void log(String s){}
 static void closeSurfaceObject(Object o,String m){if(o!=null)XposedHelpers.callMethod(o,m);}
 static class XposedHelpers {static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}static Object callMethod(Object o,String n,Object...args){for(var m:o.getClass().getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)try{m.setAccessible(true);return m.invoke(o,args);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}throw new IllegalStateException(n);}}
 ACTUAL
 static int checks;static void check(boolean x,String m){checks++;if(!x)throw new AssertionError(m);}
 static SideParentRegression fresh(){Transaction.all.clear();return new SideParentRegression();}
 void drain(){while(!shell.isEmpty())shell.remove().run();while(!main.q.isEmpty())main.q.remove().run();}
 static void excluded(java.util.function.Consumer<SideParentRegression> mutate,String label){var h=fresh();for(var s:h.slots)s.render=false;h.slots[3].render=true;mutate.accept(h);h.reassertSideTaskViews(label);h.drain();check(Transaction.all.isEmpty(),label);}
 static void stale(java.util.function.Consumer<SideParentRegression> mutate,String label){var h=fresh();var s=h.slots[3];var frame=new RenderBinding(s,h.sceneEpoch,s.controller.mTaskLeash,s.controller.mSurfaceControl);mutate.accept(h);var tx=new Transaction();h.appendRetainedSurfaces(tx,List.of(frame));check(tx.ops.isEmpty(),label);}
 public static void main(String[] args){
  var staged=fresh();staged.scene=RESUMING;staged.retainedOnExit=true;staged.workspaceAlpha=0f;
  check(staged.ensureSideViewport()&&((Leash)staged.sideViewport).alpha==0f,"replacement viewport stays hidden during retained staging");
  var returning=staged.slots[3];var stagedFinish=new Transaction();
  staged.appendRetainedSurfaces(stagedFinish,List.of(new RenderBinding(returning,staged.sceneEpoch,
    returning.controller.mTaskLeash,returning.controller.mSurfaceControl)));stagedFinish.apply();
  check(((Leash)returning.controller.mSurfaceControl).alpha==0f,"native finish keeps a returning task parent transparent");
  var h=fresh();Object dta=new Object();var file=h.slots[3];var leash=(Leash)file.controller.mTaskLeash;leash.parent=dta;h.reassertSideTaskViews("resume complete");check(leash.parent==dta,"repair waits for Shell queue");h.drain();
  check(leash.parent==file.controller.mSurfaceControl,"already-visible canonical task returns to exact slot parent");
  check(leash.x==0&&leash.y==0&&leash.k==file.scale&&leash.crop==file.crop,"parent and local geometry restored together");
  check(leash.shown&&leash.alpha==1&&leash.layer==0,"owned task visible at original layer");
  check(Transaction.all.size()==1&&Transaction.all.get(0).applies==1&&Transaction.all.get(0).closes==1,"one applied and closed transaction");
  check(Transaction.all.get(0).ops.size()==24&&h.transitions.calls==3,"three side tasks repaired and parent viewports clipped, main and inactive TOP excluded");
  Rect physicalCrop=(Rect)((Leash)file.controller.mSurfaceControl).crop;
  check(physicalCrop.width()==300&&physicalCrop.height()==640,"outer crop uses physical view pixels rather than logical task bounds");
  check(file.taskId==103&&file.taskEpoch==3,"no task relaunch or identity rewrite");
  h=fresh();h.scene=RESUMING;h.topFace=true;h.reassertSideTaskViews("resume");h.drain();check(h.transitions.calls==4,"active TOP and all cells can recover during resume");
  excluded(x->x.slots[3].promoting=true,"promoting");excluded(x->x.slots[3].launching=true,"launching");excluded(x->x.slots[3].frozen=true,"frozen");excluded(x->x.slots[3].parked=true,"parked");excluded(x->x.slots[3].occupied=false,"empty");excluded(x->x.slots[3].controller.task=999,"foreign owner");excluded(x->x.slots[3].controller.token=new Object(),"foreign token");excluded(x->x.screenOn=false,"screen off");excluded(x->x.scene=0,"inactive");excluded(x->x.root=null,"no host");
  h=fresh();h.reassertSideTaskViews("old scene");h.sceneEpoch++;h.drain();check(Transaction.all.isEmpty()&&h.transitions.calls==0,"queued repair cannot cross scene epoch");
  stale(x->x.sceneEpoch++,"stale scene binding");stale(x->x.slots[3].taskEpoch++,"stale task binding");stale(x->x.slots[3].taskId++,"replaced task id");stale(x->x.slots[3].taskToken=new Object(),"replaced token");stale(x->x.slots[3].controller.mTaskLeash=new Leash(),"retired leash");stale(x->x.slots[3].controller.mSurfaceControl=new Object(),"retired parent");stale(x->x.slots[3].writable=false,"surface ownership gate");stale(x->x.slots[3].controller.task=999,"owner changed before append");
  h=fresh();var s=h.slots[3];var f=new RenderBinding(s,h.sceneEpoch,s.controller.mTaskLeash,s.controller.mSurfaceControl);var tx=new Transaction();h.appendRetainedSurfaces(tx,List.of(f,f));check(tx.ops.size()==8,"same slot appended only once");
  h=fresh();s=h.slots[4];s.view.width=1200;s.view.height=560;s.view.clip=new Rect(0,70,1200,490);
  tx=new Transaction();h.appendSlotViewport(tx,s);check(tx.applies==0,"crop appended to supplied transaction without a separate apply");tx.apply();
  Rect clipped=(Rect)((Leash)s.controller.mSurfaceControl).crop;check(clipped.top==70&&clipped.bottom==490&&clipped.width()==1200,"TOP live view clip respected in parent coordinates");
  check(((Leash)s.controller.mSurfaceControl).x==99&&((Leash)s.controller.mSurfaceControl).k==1,"outer parent placement and scale remain render-thread owned");
  for(int gate=0;gate<9;gate++){
   h=fresh();s=h.slots[1];if(gate==0)h.holdingFlip=true;if(gate==1)s.view.width=0;
   if(gate==2)s.view.clip=new Rect(500,500,600,600);if(gate==3)((Leash)s.controller.mSurfaceControl).valid=false;
   if(gate==4)s.controller.token=new Object();if(gate==5)s.writable=false;if(gate==6)s.promoting=true;
   if(gate==7)s.launching=true;if(gate==8)s.view=null;
   tx=new Transaction();h.appendSlotViewport(tx,s);check(tx.ops.isEmpty(),"no crop during flip, empty viewport, detached or foreign task");
  }
  h=fresh();var start=new Transaction();var finish=new Transaction();h.trackRetainedSurfaces(new Info(6),start,finish);
  check(h.workspaceFinishes.size()==2,"CHANGE marks exact native start and finish transactions");
  h.appendWorkspaceSurfaces(start,h.workspaceFinishes.take(start));check(start.applies==0,"native start transaction retains apply ownership");
  start.apply();for(int i=1;i<4;i++)check(((Leash)h.slots[i].controller.mTaskLeash).parent==h.slots[i].controller.mSurfaceControl,"live task remains inside TaskView from first rotation frame");
  h.sceneEpoch++;tx=new Transaction();h.appendWorkspaceSurfaces(tx,h.workspaceFinishes.take(finish));check(tx.ops.isEmpty(),"late finish cannot clip or reparent next scene");
  h=fresh();start=new Transaction();finish=new Transaction();h.trackRetainedSurfaces(new Info(1),start,finish);
  check(h.workspaceFinishes.take(start).isEmpty()&&h.workspaceFinishes.take(finish).size()==1,"ordinary open keeps existing final-only behavior");
  h=fresh();start=new Transaction();finish=new Transaction();h.trackRetainedSurfaces(new Info(6),start,finish);
  tx=new Transaction();h.workspaceFinishes.merge(tx,start);check(h.workspaceFinishes.take(start).isEmpty(),"merged start identity consumed once");
  h.slots[2].writable=false;h.slots[3].promoting=true;h.appendWorkspaceSurfaces(tx,h.workspaceFinishes.take(tx));tx.apply();
  check(((Leash)h.slots[1].controller.mTaskLeash).shown&&!((Leash)h.slots[2].controller.mTaskLeash).shown&&!((Leash)h.slots[3].controller.mTaskLeash).shown,"merged rotation start respects current native ownership and handoff");
  h=fresh();start=new Transaction();finish=new Transaction();h.trackRetainedSurfaces(new Info(6),start,finish);
  Slot peer1=h.slots[1],peer2=h.slots[2],ordinary=h.slots[3];
  Leash peerLeash1=(Leash)peer1.controller.mTaskLeash,peerLeash2=(Leash)peer2.controller.mTaskLeash;
  Object animatedParent=new Object();
  MiStepLiveSwap64.borrowed.put(peer1.controller,peer1.taskToken);
  MiStepLiveSwap64.borrowed.put(peer2.controller,peer2.taskToken);
  start.reparent(peerLeash1,animatedParent);start.setLayer(peerLeash1,102);start.setPosition(peerLeash1,412,622);
  start.reparent(peerLeash2,animatedParent);start.setLayer(peerLeash2,103);start.setPosition(peerLeash2,412,1237);
  h.appendWorkspaceSurfaces(start,h.workspaceFinishes.take(start));start.apply();
  check(peerLeash1.parent==animatedParent&&peerLeash1.layer==102&&peerLeash1.x==412,"first peer keeps its animation parent and screen transform");
  check(peerLeash2.parent==animatedParent&&peerLeash2.layer==103&&peerLeash2.y==1237,"second peer keeps its animation parent and screen transform");
  check(((Leash)ordinary.controller.mTaskLeash).parent==ordinary.controller.mSurfaceControl,"unleased side task keeps regular parent repair");
  MiStepLiveSwap64.borrowed.clear();
  h.appendRetainedSurfaces(finish,h.finishTransactions.take(finish));
  h.appendWorkspaceSurfaces(finish,h.workspaceFinishes.take(finish));finish.apply();
  check(peerLeash1.parent==peer1.controller.mSurfaceControl&&peerLeash2.parent==peer2.controller.mSurfaceControl
    &&peerLeash1.layer==0&&peerLeash2.layer==0,"finished or aborted peer leases allow canonical recovery");
  h=fresh();h.retainedForLock=true;h.keyguardWindowHidden=true;
  h.restoreRetainedWorkspaceSurfaces();
  check(Transaction.all.size()==1&&Transaction.all.get(0).applies==1&&Transaction.all.get(0).closes==1,"unlock surface transaction applied and closed synchronously");
  for(int i=1;i<4;i++)check(((Leash)h.slots[i].controller.mTaskLeash).parent==h.slots[i].controller.mSurfaceControl,"live canonical parents ready before Shell queue or overlay reveal");
  check(h.shell.isEmpty()&&h.transitions.calls==0,"surface preparation does not enqueue native TaskView transitions");
  for(int gate=0;gate<6;gate++){
   h=fresh();h.retainedForLock=true;h.keyguardWindowHidden=true;
   if(gate==0)h.keyguardLocked=true;if(gate==1)h.screenOn=false;if(gate==2)h.root=null;
   if(gate==3)h.scene=0;if(gate==4)h.retainedForLock=false;if(gate==5)h.keyguardWindowHidden=false;
   h.restoreRetainedWorkspaceSurfaces();check(Transaction.all.isEmpty(),"locked, absent, inactive or already-visible host not restored");
  }
  h=fresh();h.retainedForLock=true;h.keyguardWindowHidden=true;h.keyguardLocked=true;h.screenOn=false;
  var hidden=new Transaction();h.appendHiddenKeyguardSurfaces(hidden,List.of(h.sceneEpoch));
  check(hidden.applies==0,"hidden geometry is appended to native transaction, never separately applied");
  hidden.apply();
  for(int i=1;i<4;i++)check(((Leash)h.slots[i].controller.mTaskLeash).parent==h.slots[i].controller.mSurfaceControl,"native start and finish cannot expose canonical task fullscreen");
  check(h.keyguardLocked&&h.keyguardWindowHidden&&!h.screenOn,"hidden parent repair never grants unlock or reveals Host");
  for(int gate=0;gate<7;gate++){
   h=fresh();h.retainedForLock=true;h.keyguardWindowHidden=true;var nativeTx=new Transaction();
   if(gate==0)h.retainedForLock=false;if(gate==1)h.keyguardWindowHidden=false;if(gate==2)h.root=null;
   if(gate==3)h.scene=0;if(gate==4)h.sceneEpoch++;if(gate==5)for(var item:h.slots)item.controller.task=999;
   if(gate==6)for(var item:h.slots)item.frozen=true;
   h.appendHiddenKeyguardSurfaces(nativeTx,List.of(7L));check(nativeTx.ops.isEmpty(),"hidden repair requires exact retained Host, scene and task owner");
  }
  h=fresh();check(h.ensureSideViewport(),"initial side viewport is available");
  var viewport=(Leash)h.sideViewport;
  check(viewport.parent==h.hostSurface&&viewport.shown&&viewport.crop.equals(
          new Rect(h.layout.cellX,h.layout.topStrip,1080,2460)),
        "initial viewport clips to the physical side column");
  var child=(Leash)h.slots[1].controller.mSurfaceControl;child.parent=viewport;
  viewport.parent=null;viewport.shown=false;
  check(h.ensureSideViewport()&&viewport.parent==h.hostSurface&&viewport.shown,
        "native Host replacement can preserve both the Java handle and the crop");
  h.hostSurface=new Leash();
  check(h.ensureSideViewport()&&h.sideViewport==viewport&&viewport.parent==h.hostSurface&&child.parent==viewport,
        "different Host handle keeps the same viewport and real TaskView children");
  h.layout=MiStepLayout.of(3048,2032,false,2.5f,true);
  check(h.ensureSideViewport()&&viewport.crop.equals(new Rect(0,95,3048,601))
         &&child.parent==viewport,"tablet horizontal viewport crops the same real task containers");
  h=fresh();
  h.slots[1].sideRow=0;h.slots[1].sideShown=false;
  h.slots[2].sideRow=1;h.slots[2].sideShown=true;
  h.slots[3].sideRow=2;h.slots[3].controller.token=new Object();
  h.syncSideViewportFocus();h.drain();
  check(h.focusable.equals(Map.of(h.slots[1].taskToken,false,h.slots[2].taskToken,true)),
        "clipped task loses focus, visible task can focus, unowned and non-row tasks are skipped");
  h.slots[1].sideShown=true;h.slots[1].windowMode=1;h.syncSideViewportFocus();h.drain();
  check(h.focusable.get(h.slots[1].taskToken),"live mode6 focus is restored despite the late fullscreen callback");
  h.focusable.clear();h.slots[1].currentMode=1;h.syncSideViewportFocus();h.drain();
  check(!h.focusable.containsKey(h.slots[1].taskToken),"a real fullscreen task never receives a side focus update");
  h.focusable.clear();h.syncSideViewportFocus();h.sceneEpoch++;h.drain();
  check(h.focusable.isEmpty(),"queued focus changes cannot cross an exit or resume");
  System.out.println("actual side parent repair checks="+checks+" passed");
 }
}'''.replace('ACTUAL', actual)
(out/'SurfaceControl.java').write_text(stub, encoding='utf8')
(out/'WindowContainerTransaction.java').write_text(focus_stub, encoding='utf8')
(out/'SideParentRegression.java').write_text(body, encoding='utf8')
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), str(out/'SurfaceControl.java'), str(out/'WindowContainerTransaction.java'), str(out/'SideParentRegression.java'), str(root/'tools/test-stubs/android/graphics/Rect.java'), str(src/'FinishTransactions.java'), str(src/'MiStepLayout.java')], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.SideParentRegression'], check=True)
