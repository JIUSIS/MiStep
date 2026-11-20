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
methods='\n'.join(method(s) for s in [
    '    private boolean onSlotTouch(', '    private void recycleBuffered(',
    '    private boolean acceptsSideDismiss(',
    '    private FrameLayout createSideInputViewport(', '    private boolean isInSideViewport(',
    '    private Slot slotAtPoint(', '    private boolean trackSideScroll(',
    '    private float sidePitch(', '    private void recycleSideVelocity(',
    '    private boolean clipToSideViewport(', '    private void updateTouchRegion(',
])
created=method('    private boolean onTaskViewCreated(')
assert '(sideInputViewport == null ? root : sideInputViewport).addView(view, lp)' in created
assert '(sideInputViewport == null ? root : sideInputViewport).addView(slot.hint, hintParams(slot))' in created
start=host.index('            installed.add(XposedHelpers.findAndHookMethod(view, "onComputeInternalInsets",')
end=host.index('            }));',start)+len('            }));')
hook=host[start:end].replace('MiStepHost h', 'SideTouchRegression h')
text='''package com.jiusis.mistep;import java.util.*;import java.lang.reflect.Method;
public class SideTouchRegression {
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static class MotionEvent {static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;int a;float x,y;boolean recycled;MotionEvent(int a,float x,float y){this.a=a;this.x=x;this.y=y;}static MotionEvent obtain(MotionEvent e){return new MotionEvent(e.a,e.x,e.y);}int getPointerCount(){return 1;}int getPointerId(int i){return 0;}int getActionMasked(){return a;}float getRawX(){return x;}float getRawY(){return y;}float getX(){return x;}float getY(){return y;}long getEventTime(){return 10000;}void recycle(){recycled=true;}}
 static class ViewConfiguration {static ViewConfiguration get(Object c){return new ViewConfiguration();}int getScaledTouchSlop(){return 8;}int getScaledMinimumFlingVelocity(){return 50;}int getScaledMaximumFlingVelocity(){return 6000;}}
 static class Rect {
  int l,t,r,b;Rect(int l,int t,int r,int b){this.l=l;this.t=t;this.r=r;this.b=b;}
  boolean intersect(int l,int t,int r,int b){
   if(this.l>=r||l>=this.r||this.t>=b||t>=this.b)return false;
   this.l=Math.max(l,this.l);this.t=Math.max(t,this.t);this.r=Math.min(r,this.r);this.b=Math.min(b,this.b);return true;
  }
 }
 static class Region {
  int[] bounds;Region(int l,int t,int r,int b){bounds=new int[]{l,t,r,b};}Region(Rect r){this(r.l,r.t,r.r,r.b);}
 }
 public static class View {Region obscured;public void setObscuredTouchRegion(Region r){obscured=r;}}
 static class Slot {int index,sideRow=-1;boolean sideShown,promoting,launching,closing,frozen,parked,occupied=true,injecting;View view=new View();int[]rect={0,0,100,200};long taskEpoch,touchTaskEpoch,touchSceneEpoch;MotionEvent bufferedDown;}
 static class FrameLayout {
  int dispatches;
  FrameLayout(Context ctx) {}
  void setClipChildren(boolean clip) {}
  boolean dispatchTouchEvent(MotionEvent event) {dispatches++;return true;}
 }
 MiStepLayout layout=MiStepLayout.of(1080,2460,false,2.75f);
 int screenW=1080,screenH=2460;
 boolean sideScrolling,sideArmed,sideCaptured,sideCompacting,sideDesktopRevealing,liveSwapInputBlocked;
 float sideDownX,sideDownY,sideLastPosition,sideOffset;int flingVelocity;boolean settled;
 android.view.VelocityTracker sideVelocity;
 static class android {static class view {static class VelocityTracker {
  MotionEvent first,last;static VelocityTracker obtain(){return new VelocityTracker();}
  void addMovement(MotionEvent e){if(first==null)first=e;last=e;}
  void computeCurrentVelocity(int units,int max){}
  float getXVelocity(int id){return (last.x-first.x)*4;}float getYVelocity(int id){return (last.y-first.y)*4;}
  void recycle(){}
 }}}
 boolean canMoveSideRows(){return !taskCellsCovered;}
 int maxSideOffset(){return 3;}
 boolean isSideMotionRunning(){return false;}
 void cancelSideAnimator(){}
 void finishSideScroll(){settled=true;}
 void settleSideScroll(){settled=true;}
 void requestCompaction(){}
 boolean flingSideRows(int velocity){flingVelocity=velocity;return velocity!=0;}
 void setSideOffset(float offset){sideOffset=offset;}
 static class XC_MethodHook {static class MethodHookParam {Object thisObject;boolean returned;void setResult(Object result){returned=true;}}protected void beforeHookedMethod(MethodHookParam p){}}
 static class XposedHelpers {static XC_MethodHook hook;static Class<?>findClass(String n,ClassLoader cl){return Object.class;}static Object findAndHookMethod(Class<?>c,String n,Object...args){hook=(XC_MethodHook)args[args.length-1];return hook;}}
 static SideTouchRegression sInstance;static final int SLOT_MAIN=0,SLOT_TOP=5,CELL_SLOT_COUNT=4;Slot[]slots={new Slot(),new Slot(),new Slot(),new Slot()};static class Context{Context getResources(){return this;}Context getDisplayMetrics(){return this;}float density=1;}Context ctx=new Context();int taps,transfers,compactions;boolean compactPending;
 static class Dismiss{void follow(float dx){}void settle(boolean done){}}Dismiss sideDismiss;Object promotion,cellMotion;Slot sideDismissSlot;boolean transferBusy,taskCellsCovered,mainOnRight=true;long sceneEpoch=1;
 boolean followSideDismiss(Slot s,MotionEvent e){return true;}
 boolean beginSideDismiss(Slot s,MotionEvent e){return false;}
 void dismissSideToBackground(Slot s){}void log(String s){}void onSlotTapped(int i){taps++;}void compactCells(){compactions++;}void transferTouchToTask(Slot s,long t){transfers++;}
 boolean ownsViewOrController(Object v){for(Slot s:slots)if(s.view==v)return true;return false;}
 void install(){Class<?>view=View.class;ClassLoader cl=getClass().getClassLoader();List<Object>installed=new ArrayList<>();
 HOOK
 }
 METHODS
 void event(int a,float y){onSlotTouch(1,new MotionEvent(a,10,y));}
 void checkViewport(){
  FrameLayout viewport=createSideInputViewport();
  int first=layout.topStrip;
  Slot row=slots[1];row.index=1;row.sideRow=0;row.sideShown=true;row.rect=new int[]{layout.cellX,first-32,1080,1199};
  check(!viewport.dispatchTouchEvent(new MotionEvent(0,900,first-1))&&viewport.dispatches==0,
        "clipped row yields its down to the toolbar");
  check(viewport.dispatchTouchEvent(new MotionEvent(0,900,first))&&viewport.dispatches==1,
        "first visible pixel can start an ordinary tap");
  check(viewport.dispatchTouchEvent(new MotionEvent(3,900,first-1))&&viewport.dispatches==2,
        "a started gesture can still deliver cancel outside the viewport");
  check(!viewport.dispatchTouchEvent(new MotionEvent(0,layout.cellX-1,800))
        &&!viewport.dispatchTouchEvent(new MotionEvent(0,1080,800))
        &&!viewport.dispatchTouchEvent(new MotionEvent(0,900,2460)),
        "gutter and exclusive right and bottom edges miss the side viewport");
  check(slotAtPoint(900,first-1)==null&&slotAtPoint(900,first)==row,
        "drag/drop uses the same visible portion of the row");
  row.sideShown=false;
  check(slotAtPoint(900,first+1)==null,"a parked row cannot receive a drop");
  layout=MiStepLayout.of(1080,2460,true,2.75f);
  check(!viewport.dispatchTouchEvent(new MotionEvent(0,900,800))
        &&viewport.dispatchTouchEvent(new MotionEvent(0,5,800)),
        "input follows the side column when the main panel moves right");
  row.sideRow=-1;row.rect=new int[]{0,first,layout.cellWidth,1230};
  check(slotAtPoint(5,first)==row,"ordinary fixed-cell hit-testing still works");
 }
 void checkLandscape(){
  layout=MiStepLayout.of(3048,2032,false,2.5f,true);screenW=layout.width;screenH=layout.height;
  int y=layout.sideViewportTop+12;
  FrameLayout viewport=createSideInputViewport();
  Slot row=slots[1];row.index=1;row.sideRow=0;row.sideShown=true;row.rect=layout.sideScrollBounds(0,.5f);
  check(!viewport.dispatchTouchEvent(new MotionEvent(0,0,layout.toolHeight-1))
        && !viewport.dispatchTouchEvent(new MotionEvent(0,0,layout.sideViewportBottom))
        && viewport.dispatchTouchEvent(new MotionEvent(0,0,y)),
        "toolbar and lower panel are excluded while the first visible pixel receives touches");
  check(slotAtPoint(-1,y)==null&&slotAtPoint(0,y)==row,"drop follows the left edge of the horizontal viewport");
  updateTouchRegion(row);
  check(row.view.obscured.bounds[0]==0&&row.view.obscured.bounds[2]==row.rect[2],
        "TaskView input uses only the visible portion at the left edge");
  row.rect=layout.sideScrollBounds(4,.5f);updateTouchRegion(row);
  check(row.view.obscured.bounds[0]==row.rect[0]&&row.view.obscured.bounds[2]==layout.width,
        "TaskView input also clips at the trailing edge");
  row.sideShown=false;updateTouchRegion(row);
  check(Arrays.equals(row.view.obscured.bounds,new int[]{0,0,screenW,screenH}),
        "a fully clipped task cannot receive direct input");
 }
 void checkScrollAxis(boolean horizontal){
  layout=horizontal?MiStepLayout.of(3048,2032,false,2.5f,true):MiStepLayout.of(2032,3048,false,2.5f,true);
  float x=layout.sideViewportLeft+300,y=layout.sideViewportTop+300;
  sideScrolling=true;
  check(!trackSideScroll(new MotionEvent(0,x,y))&&sideArmed,"down in the viewport starts arbitration");
  check(!trackSideScroll(new MotionEvent(2,x-4,y-4))&&!sideCaptured,"jitter leaves an ordinary tap");
  check(trackSideScroll(new MotionEvent(2,x-(horizontal?160:3),y-(horizontal?3:160)))
        &&Math.abs(sideOffset*sidePitch()-152)<.01f,"drag captures on the configured axis only");
  check(trackSideScroll(new MotionEvent(1,x-(horizontal?280:5),y-(horizontal?5:280)))
        &&flingVelocity==1120&&!sideArmed&&!sideCaptured,"fling reads velocity from the same axis");
  trackSideScroll(new MotionEvent(0,x,y));
  check(!trackSideScroll(new MotionEvent(2,x-(horizontal?4:160),y-(horizontal?160:4)))
        &&!sideCaptured&&!sideArmed,"a perpendicular drag stays available to the cell");
  check(!trackSideScroll(new MotionEvent(0,x,layout.sideViewportTop-1))&&!sideArmed,
        "outside down cannot begin scrolling");
 }
 public static void main(String[]args){
  new SideTouchRegression().checkViewport();
  new SideTouchRegression().checkLandscape();
  new SideTouchRegression().checkScrollAxis(false);
  new SideTouchRegression().checkScrollAxis(true);
  var h=new SideTouchRegression();h.event(0,10);h.event(1,10);check(h.taps==1&&h.transfers==0,"tap promotes exactly once");check(h.slots[1].bufferedDown==null&&!h.slots[1].injecting,"tap releases gesture ownership");
  h=new SideTouchRegression();h.taskCellsCovered=true;h.event(0,10);h.event(1,10);
  check(h.taps==0&&h.slots[1].bufferedDown==null,"covered native cells cannot receive taps through transparent control space");
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
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(path),str(src/'MiStepSideDismissGesture.java'),str(src/'MiStepLayout.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.SideTouchRegression'],check=True)
