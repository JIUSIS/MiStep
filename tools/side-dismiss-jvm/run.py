import os
"""Exercise the actual Host event handler and handback gate, with deterministic callbacks."""
from pathlib import Path
import subprocess, sys
root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
host = (src/'MiStepHost.java').read_text(encoding='utf-8-sig')
def method(signature):
    start = host.index(signature); end = host.index('{', start)+1; depth = 1
    while depth:
        depth += (host[end] == '{') - (host[end] == '}'); end += 1
    return host[start:end]
methods = '\n'.join(method(s) for s in ['    private boolean onSlotTouch(',
    '    private void recycleBuffered(', '    private boolean followSideDismiss(', '    private void dismissSideToBackground(']).replace('MiStepHost::log','SideDismissRegression::log')
body = r'''
package com.jiusis.mistep;
import java.util.function.Consumer;
public class SideDismissRegression {
 static int checks; static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static final int SLOT_MAIN=0,SLOT_COUNT=4,ACTIVE=1;
 int scene=ACTIVE,taps,compactions,returns,clears;long sceneEpoch=1;
 boolean screenOn=true,keyguardLocked,transferBusy,mainOnRight,compactPending,front;
 Object promotion,cellMotion; Context ctx=new Context(); Slot[] slots={new Slot(),new Slot(),new Slot(),new Slot()};
 View root=new View();MiStepSideDismiss sideDismiss; Slot sideDismissSlot;MiStepLayout layout=new MiStepLayout();Object wallpaperBackdrop=new Object();int screenW=1080,screenH=2460;
 Object[] sideBackingViews=new Object[4];static class LiveSwapVisualLease{LiveSwapVisualLease(Object b,Object h){}void restore(){}}
 static class View{void invalidate(){}}static class Rect{}static class MiStepLayout{}
 static class HyperOsStyle{static float dp(Context c,float f){return f*2.75f;}}
 static class MiStepLiveBackdrop{
  static MiStepLiveBackdrop create(Object p,Object w,int sw,int sh,Rect a,Rect b,Consumer<Object> painter,Consumer<String> log){return new MiStepLiveBackdrop();}
  void closeAfterFrame(View v){}
 }
 static class MiStepSideDismiss{
  boolean tracking=true;float dx;final Consumer<Boolean> cb;MiStepSideDismiss(View r,Object v,float distance,java.util.function.BooleanSupplier c,Consumer<Boolean>b,Consumer<String>l){cb=b;}
  boolean isTracking(){return tracking;}void follow(float x){dx=x;}void settle(boolean commit){tracking=false;cb.accept(commit);}void close(){}
 }
 Rect liveSideStrip(MiStepLayout g){return new Rect();}Object liveHostRootSurface(){return new Object();}
 void drawLiveEmptyHints(Object c,MiStepLayout a,MiStepLayout b,int mask){}void updateCellSurfaceVisibility(Slot s){}
 Runnable success;Consumer<String> failure;
 static class Slot {
  int index=1,taskId=42;long taskEpoch=2,touchTaskEpoch,touchSceneEpoch;
  boolean occupied=true,promoting,launching,closing,frozen,parked,injecting,carrier;
  Object taskToken=new Object(),controller=new Object(),view=new Object(),hint=new Object();int[] rect={814,600,1080,1200};MotionEvent bufferedDown;
 }
 static class Metrics{float density=2.75f;}static class Resources{Metrics getDisplayMetrics(){return new Metrics();}}
 static class Context{Resources getResources(){return new Resources();}}
 static class ViewConfiguration{static ViewConfiguration get(Context c){return new ViewConfiguration();}int getScaledTouchSlop(){return 8;}}
 static class MotionEvent{
  static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
  int a,n=1,id=0;float x,y;long t;boolean recycled;
  MotionEvent(int a,float x,float y,long t){this.a=a;this.x=x;this.y=y;this.t=t;}
  static MotionEvent obtain(MotionEvent e){MotionEvent c=new MotionEvent(e.a,e.x,e.y,e.t);c.id=e.id;c.n=e.n;return c;}
  int getActionMasked(){return a;}float getRawX(){return x;}float getRawY(){return y;}
  int getPointerCount(){return n;}int getPointerId(int i){return id;}long getEventTime(){return t;}void recycle(){recycled=true;}
 }
 boolean isActive(Slot s){return s==slots[1];} static void log(String s){}
 void onSlotTapped(int i){taps++;}void compactCells(){compactions++;}
 void clearSlot(Slot s){s.occupied=false;s.taskId=-1;s.taskEpoch++;clears++;compactPending=true;}
 void finishPendingSuspend(){}
 void returnToSystem(Slot s,boolean front,Runnable ok,Consumer<String> bad){returns++;this.front=front;success=ok;failure=bad;transferBusy=true;}
 void event(int a,float x,float y,long t){onSlotTouch(1,new MotionEvent(a,x,y,t));}
 void swipe(float dx,float dy,long duration){event(0,940,900,0);event(2,940+dx,900+dy,duration/2);event(1,940+dx,900+dy,duration);}
 __METHODS__
 public static void main(String[]args){
  for(boolean left:new boolean[]{false,true}){
   SideDismissRegression h=new SideDismissRegression();h.mainOnRight=left;h.swipe(left?-110:110,9,400);
   check(h.returns==1&&!h.front,"outward release requests background on both sides");
   check(h.clears==0&&h.slots[1].taskId==42,"task binding survives until ownership ack");
   h.transferBusy=false;h.success.run();check(h.clears==1&&!h.slots[1].occupied,"ack clears and queues compaction");
   check(h.compactPending,"remaining tasks compact after handback");
   h=new SideDismissRegression();h.mainOnRight=left;h.swipe(left?110:-110,0,400);
   check(h.returns==0&&h.taps==0,"inward drag neither dismisses nor swaps");
  }
  SideDismissRegression h=new SideDismissRegression();h.event(0,940,900,0);h.event(2,990,902,200);
  check(h.sideDismiss!=null&&h.sideDismiss.dx==50&&h.returns==0,"MOVE follows before UP without handback");
  h.event(2,1020,902,1000);check(h.sideDismiss.dx==80&&h.returns==0,"own MOVE still routes while transfer busy");
  h.event(3,1020,902,1200);check(h.sideDismiss==null&&h.returns==0&&!h.transferBusy,"CANCEL releases tracking without handback");
  h=new SideDismissRegression();h.swipe(110,140,400);check(h.returns==0&&h.taps==0,"vertical drag rejected");
  h=new SideDismissRegression();h.swipe(65,0,400);check(h.returns==0&&h.taps==0,"short drag rejected");
  h=new SideDismissRegression();h.swipe(110,0,1500);check(h.returns==1,"slow deliberate outward gesture accepted");
  h=new SideDismissRegression();h.event(0,940,900,0);h.event(1,944,903,100);check(h.taps==1&&h.returns==0,"tap still swaps");
  h=new SideDismissRegression();h.event(1,940,900,100);check(h.taps==0,"orphan up never swaps");
  h=new SideDismissRegression();h.event(0,940,900,0);h.event(1,1050,900,300);check(h.returns==1&&h.taps==0,"coalesced swipe accepted without MOVE");
  h=new SideDismissRegression();h.event(0,940,900,0);h.event(3,1050,900,300);check(h.returns==0&&h.taps==0,"cancel has no action");
  h=new SideDismissRegression();h.event(0,940,900,0);h.event(5,960,900,100);h.event(6,1000,900,200);h.event(1,1050,900,300);check(h.returns==0&&h.taps==0,"multi pointer sequence rejected");
  h=new SideDismissRegression();h.event(0,940,900,0);h.slots[1].taskEpoch++;h.event(1,1050,900,300);check(h.returns==0,"replacement binding rejected");
  h=new SideDismissRegression();h.event(0,940,900,0);h.sceneEpoch++;h.event(1,1050,900,300);check(h.returns==0,"replacement scene rejected");
  h=new SideDismissRegression();h.event(0,940,900,0);MotionEvent up=new MotionEvent(1,1050,900,300);up.id=2;h.onSlotTouch(1,up);check(h.returns==0,"different pointer rejected");
  h=new SideDismissRegression();h.transferBusy=true;h.swipe(110,0,400);check(h.returns==0,"active transfer blocks gesture");
  h=new SideDismissRegression();h.slots[1].carrier=true;h.swipe(110,0,400);check(h.returns==0,"mirror carrier is not released as owned side task");
  h=new SideDismissRegression();h.slots[2].launching=true;h.swipe(110,0,400);check(h.returns==0,"peer launch blocks handback");
  h=new SideDismissRegression();h.swipe(110,0,400);h.failure.accept("incomplete");check(h.clears==0&&h.slots[1].taskId==42,"failure retains binding");
  h=new SideDismissRegression();h.swipe(110,0,400);h.slots[1].taskEpoch++;h.slots[1].taskId=77;h.success.run();check(h.clears==0&&h.slots[1].taskId==77,"stale ack never clears replacement");
  for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
   check(!MiStepSideDismissGesture.accepts(false,bad,0,266,2.75f,8,400),"nonfinite dx rejected");
  check(!MiStepSideDismissGesture.accepts(false,110,0,0,2.75f,8,400),"missing geometry rejected");
  check(!MiStepSideDismissGesture.accepts(false,110,0,266,2.75f,8,-1),"stale clock rejected");
  System.out.println("actual side-dismiss checks="+checks+" passed");
 }
}
'''.replace('__METHODS__',methods)
files = {'com/jiusis/mistep/SideDismissRegression.java':body,
 'android/widget/Toast.java':'package android.widget; public class Toast { public static final int LENGTH_SHORT=0; public static Toast makeText(Object c,String s,int n){return new Toast();}public void show(){} }'}
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out)]+[str(out/n) for n in files]+[str(src/'MiStepSideDismissGesture.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.SideDismissRegression'],check=True)
