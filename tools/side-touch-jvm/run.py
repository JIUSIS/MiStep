import os
"""Execute real side-cell gesture arbitration and the scoped TaskView insets hook."""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[2];src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
host=(src/'MiStepHost.java').read_text(encoding='utf8')
def method(signature):
    a=host.index(signature);b=host.index('{',a);n=1;i=b+1
    while n:
        n+=(host[i]=='{')-(host[i]=='}');i+=1
    return host[a:i]
methods='\n'.join(method(s) for s in ['    private boolean onSlotTouch(', '    private void recycleBuffered('])
start=host.index('            installed.add(XposedHelpers.findAndHookMethod(view, "onComputeInternalInsets",')
end=host.index('            }));',start)+len('            }));')
hook=host[start:end].replace('MiStepHost h', 'SideTouchRegression h')
text='''package com.jiusis.mistep;import java.util.*;
public class SideTouchRegression {
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static class MotionEvent {static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;int a;float x,y;boolean recycled;MotionEvent(int a,float x,float y){this.a=a;this.x=x;this.y=y;}static MotionEvent obtain(MotionEvent e){return new MotionEvent(e.a,e.x,e.y);}int getPointerCount(){return 1;}int getPointerId(int i){return 0;}int getActionMasked(){return a;}float getRawX(){return x;}float getRawY(){return y;}float getX(){return x;}float getY(){return y;}long getEventTime(){return 10000;}void recycle(){recycled=true;}}
 static class ViewConfiguration {static ViewConfiguration get(Object c){return new ViewConfiguration();}int getScaledTouchSlop(){return 8;}}
 static class View {}static class Slot {boolean promoting,launching,closing,frozen,parked,occupied=true,injecting;View view=new View();int[]rect={0,0,100,200};long taskEpoch,touchTaskEpoch,touchSceneEpoch;MotionEvent bufferedDown;}
 static class XC_MethodHook {static class MethodHookParam {Object thisObject;boolean returned;void setResult(Object result){returned=true;}}protected void beforeHookedMethod(MethodHookParam p){}}
 static class XposedHelpers {static XC_MethodHook hook;static Class<?>findClass(String n,ClassLoader cl){return Object.class;}static Object findAndHookMethod(Class<?>c,String n,Object...args){hook=(XC_MethodHook)args[args.length-1];return hook;}}
 static SideTouchRegression sInstance;static final int SLOT_MAIN=0,SLOT_COUNT=4;Slot[]slots={new Slot(),new Slot(),new Slot(),new Slot()};static class Context{Context getResources(){return this;}Context getDisplayMetrics(){return this;}float density=1;}Context ctx=new Context();int taps,transfers,compactions;boolean compactPending;
 static class Dismiss{void follow(float dx){}void settle(boolean done){}}Dismiss sideDismiss;Object promotion,cellMotion;Slot sideDismissSlot;boolean transferBusy,mainOnRight=true;long sceneEpoch=1;
 boolean followSideDismiss(Slot s,MotionEvent e){return true;}
 boolean beginSideDismiss(Slot s,MotionEvent e){return false;}
 void dismissSideToBackground(Slot s){}void log(String s){}void onSlotTapped(int i){taps++;}void compactCells(){compactions++;}void transferTouchToTask(Slot s,long t){transfers++;}
 boolean ownsViewOrController(Object v){for(Slot s:slots)if(s.view==v)return true;return false;}
 void install(){Class<?>view=View.class;ClassLoader cl=getClass().getClassLoader();List<Object>installed=new ArrayList<>();
 HOOK
 }
 METHODS
 void event(int a,float y){onSlotTouch(1,new MotionEvent(a,10,y));}
 public static void main(String[]args){
  var h=new SideTouchRegression();h.event(0,10);h.event(1,10);check(h.taps==1&&h.transfers==0,"tap promotes exactly once");check(h.slots[1].bufferedDown==null&&!h.slots[1].injecting,"tap releases gesture ownership");
  h=new SideTouchRegression();h.compactPending=true;h.event(0,100);h.event(2,30);h.event(2,10);h.event(1,10);check(h.transfers==0&&h.taps==0,"vertical drag neither scrolls real side app nor promotes");check(h.compactions==1&&h.slots[1].bufferedDown==null&&!h.slots[1].injecting,"drag completion releases pending compaction");
  h=new SideTouchRegression();h.event(0,10);h.event(2,14);h.event(1,14);check(h.taps==1&&h.transfers==0,"small finger jitter remains a tap");
  h=new SideTouchRegression();h.event(0,10);h.event(3,10);check(h.taps==0&&h.slots[1].bufferedDown==null,"cancel cannot promote");
  h=new SideTouchRegression();h.slots[1].promoting=true;h.event(0,10);h.event(1,10);check(h.taps==0,"already promoting task cannot accept another gesture");
  h=new SideTouchRegression();check(!h.onSlotTouch(0,new MotionEvent(0,0,0)),"system main remains outside side arbitration");h.slots[1].occupied=false;check(!h.onSlotTouch(1,new MotionEvent(0,0,0)),"empty cell keeps its own click affordance");
  sInstance=h;h.install();var p=new XC_MethodHook.MethodHookParam();p.thisObject=h.slots[2].view;XposedHelpers.hook.beforeHookedMethod(p);check(p.returned,"owned empty/moved TaskView cannot punch a hole in Host side region");
  p=new XC_MethodHook.MethodHookParam();p.thisObject=new View();XposedHelpers.hook.beforeHookedMethod(p);check(!p.returned,"foreign TaskView input remains native");
  sInstance=null;p=new XC_MethodHook.MethodHookParam();p.thisObject=h.slots[2].view;XposedHelpers.hook.beforeHookedMethod(p);check(!p.returned,"inactive Host cannot intercept another scene");
  System.out.println("actual side gesture/insets checks="+checks+" passed");
 }
}'''.replace('HOOK',hook).replace('METHODS',methods)
path=out/'SideTouchRegression.java';path.write_text(text,encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(path),str(src/'MiStepSideDismissGesture.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.SideTouchRegression'],check=True)
