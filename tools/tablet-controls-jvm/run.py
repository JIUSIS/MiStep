"""Exercise production combined-panel measuring, media lifecycle and gesture routing."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / "module/src/main/java/com/jiusis/mistep"
source = (src / "MiStepControlCenter.java").read_text(encoding="utf-8-sig")
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)


def member(signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


methods = "\n".join(member(s) for s in (
    "    @Override protected void onMeasure(", "    private void renderPages(",
    "    private void finishPage(", "    @Override public boolean dispatchTouchEvent(",
    "    @Override public boolean onInterceptTouchEvent(",
    "    @Override public void requestDisallowInterceptTouchEvent(",
    "    @Override public boolean onTouchEvent(",
    "    @Override public void onInitializeAccessibilityNodeInfo(",
    "    @Override public boolean performAccessibilityAction("))
methods = methods.replace("private void renderPages(", "void renderPages(").replace(
    "private void finishPage(", "void finishPage(")
java = r"""
package com.jiusis.mistep;
import java.util.*;
class Context {}
class Bundle {}
class View {
 static final int VISIBLE=0,INVISIBLE=4,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_AUTO=0,
  IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS=4;
 int visibility=VISIBLE,accessibility,width,height,left,top,right,bottom,dispatches;
 float translation;boolean shown=true,disallow;String description;
 Object params=new FrameLayout.LayoutParams(-1,-1);
 Object getLayoutParams(){return params;}void setLayoutParams(Object p){params=p;}
 void setVisibility(int value){visibility=value;}int getVisibility(){return visibility;}
 void setImportantForAccessibility(int value){accessibility=value;}
 void setContentDescription(String value){description=value;}
 void setTranslationX(float value){translation=value;}boolean isShown(){return shown;}
 int getWidth(){return width;}int getHeight(){return height;}
 void measure(int w,int h){onMeasure(w,h);}protected void onMeasure(int w,int h){width=w;height=h;}
 void layout(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
 boolean dispatchTouchEvent(MotionEvent event){dispatches++;return true;}
 boolean onInterceptTouchEvent(MotionEvent event){return false;}boolean onTouchEvent(MotionEvent event){return false;}
 void requestDisallowInterceptTouchEvent(boolean value){disallow=value;}
 void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){}
 boolean performAccessibilityAction(int action,Bundle args){return false;}
 static class MeasureSpec {static int getSize(int value){return value;}}
}
class FrameLayout extends View {
 final List<View>children=new ArrayList<>();
 void addView(View view){children.add(view);}
 @Override protected void onMeasure(int w,int h){
  super.onMeasure(w,h);
  for(View child:children){var p=(LayoutParams)child.params;child.measure(p.width<0?w:p.width,p.height<0?h:p.height);}
 }
 @Override void layout(int l,int t,int r,int b){
  super.layout(l,t,r,b);
  for(View child:children){var p=(LayoutParams)child.params;child.layout(p.leftMargin,p.topMargin,
   p.leftMargin+child.width,p.topMargin+child.height);}
 }
 static class LayoutParams {int width,height,leftMargin,topMargin;LayoutParams(int w,int h){width=w;height=h;}}
}
class Media extends View {boolean active;void setActive(boolean value){active=value;}
 boolean ownsHorizontalGesture(float x,float y){return false;}}
class Slider {final View root=new View();}
class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_MOVE=2,ACTION_UP=1,ACTION_CANCEL=3;
 int action;float x,y;MotionEvent(int a,float x,float y){action=a;this.x=x;this.y=y;}
 int getActionMasked(){return action;}int getPointerCount(){return 1;}float getX(){return x;}float getY(){return y;}
}
class VelocityTracker {static VelocityTracker obtain(){return new VelocityTracker();}
 void recycle(){}void addMovement(MotionEvent e){}void computeCurrentVelocity(int u){}float getXVelocity(){return 0;}}
class ViewConfiguration {static ViewConfiguration get(Context c){return new ViewConfiguration();}int getScaledTouchSlop(){return 12;}}
class AccessibilityNodeInfo {
 static final int ACTION_SCROLL_FORWARD=4096,ACTION_SCROLL_BACKWARD=8192;
 static class AccessibilityAction {static final int ACTION_SCROLL_FORWARD=4096,ACTION_SCROLL_BACKWARD=8192;}
 boolean scrollable;final List<Integer>actions=new ArrayList<>();
 void setScrollable(boolean value){scrollable=value;}void addAction(int action){actions.add(action);}
}
class DeviceProfile {static boolean isTablet(Context c){return true;}}
class Panels extends FrameLayout {
 final boolean combined;final Context context=new Context();
 final FrameLayout firstPage=new FrameLayout();final Media musicPage=new Media();
 final View connectivityCard=new View(),model=new View(),scanner=new View(),payment=new View(),transit=new View();
 final View[]cells={new View(),new View(),new View(),new View()};
 final Slider volume=new Slider(),brightness=new Slider();
 final ControlCenterPages pages=new ControlCenterPages();
 VelocityTracker pageVelocity;int page,cancels,settles;float pageOffset,density;
 Panels(boolean tablet,float density){combined=tablet;this.density=density;addView(firstPage);addView(musicPage);
  firstPage.addView(connectivityCard);firstPage.addView(model);firstPage.addView(scanner);
  firstPage.addView(payment);firstPage.addView(transit);firstPage.addView(volume.root);firstPage.addView(brightness.root);
  for(View cell:cells)firstPage.addView(cell);
 }
 int dp(float v){return Math.round(v*density);}
 void size(View view,int x,int y,int width,int height){
  var p=new FrameLayout.LayoutParams(width,height);p.leftMargin=x;p.topMargin=y;view.setLayoutParams(p);
 }
 float pageSpan(){return ControlCenterPages.span(getWidth(),dp(12));}
 void cancelPageAnimation(){cancels++;}void settlePage(int target){page=target;settles++;}
 __METHODS__
}
public class TabletControlsRegression {
 static int checks;static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
 static void combined(int width,int height,float density){
  var panel=new Panels(true,density);panel.measure(width,height);panel.layout(0,0,width,height);
  panel.pageOffset=100;panel.renderPages();panel.finishPage();
  check(panel.firstPage.left==0&&panel.firstPage.right<panel.musicPage.left
   &&panel.musicPage.right==width,"control left / music right fill the panel without overlap");
  check(panel.firstPage.top==0&&panel.firstPage.bottom==height&&panel.musicPage.height==height,
   "both sections use the available panel height");
  check(panel.firstPage.translation==0&&panel.musicPage.translation==0,"combined layout ignores stale pager offsets");
  check(panel.firstPage.visibility==View.VISIBLE&&panel.musicPage.visibility==View.VISIBLE&&panel.musicPage.active,
   "control and original native media are simultaneously visible and active");
  for(View child:panel.firstPage.children)
   check(child.left>=0&&child.top>=0&&child.right<=panel.firstPage.width&&child.bottom<=height,
    "native controls remain inside the left section");
  check(panel.connectivityCard.right<=panel.payment.left&&panel.transit.right<=panel.volume.root.left,
   "connectivity, code buttons and sliders do not cover each other");
  for(MotionEvent event:new MotionEvent[]{new MotionEvent(0,10,10),new MotionEvent(2,200,10),new MotionEvent(1,200,10)}){
   check(panel.dispatchTouchEvent(event),"combined content dispatches to its real children");
   check(!panel.onInterceptTouchEvent(event),"combined layout never intercepts playback seeking as paging");
  }
  panel.requestDisallowInterceptTouchEvent(true);
  check(panel.disallow&&panel.cancels==0&&panel.settles==0&&panel.pageVelocity==null,
   "combined sliders retain native gesture ownership without creating pager state");
  var info=new AccessibilityNodeInfo();panel.onInitializeAccessibilityNodeInfo(info);
  check(!info.scrollable&&info.actions.isEmpty()
   &&!panel.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,new Bundle()),
   "combined panel offers both sections without obsolete page actions");
  panel.shown=false;panel.finishPage();check(!panel.musicPage.active,"hidden combined media unbinds");
  panel.shown=true;panel.finishPage();check(panel.musicPage.active,"visible combined media reactivates");
 }
 public static void main(String[] args){
  for(int[] screen:new int[][]{{3048,2032},{2560,1600},{2032,3048},{1280,800}})
   for(float density:new float[]{1f,1.5f,2.5f}){
    var l=MiStepLayout.of(screen[0],screen[1],false,density,true);
    var g=new TopBarGeometry(screen[0],l.topStrip,0,density,true,l.toolHeight);
    combined(g.controlWidth,g.blankBottom-g.blankTop,density);
   }
  var phone=new Panels(false,3);phone.measure(1152,400);phone.layout(0,0,1152,400);
  phone.renderPages();phone.finishPage();
  check(phone.firstPage.width==1152&&phone.musicPage.width==1152
   &&phone.musicPage.translation==phone.pageSpan(),"phone retains two full-width pages");
  check(phone.firstPage.visibility==View.VISIBLE&&phone.musicPage.visibility==View.INVISIBLE
   &&!phone.musicPage.active,"phone begins with controls only");
  phone.page=1;phone.pageOffset=phone.pageSpan();phone.renderPages();phone.finishPage();
  check(phone.firstPage.translation==-phone.pageSpan()&&phone.musicPage.translation==0
   &&phone.firstPage.visibility==View.INVISIBLE&&phone.musicPage.active,"phone still switches to native media");
  var info=new AccessibilityNodeInfo();phone.onInitializeAccessibilityNodeInfo(info);
  check(info.scrollable&&info.actions.contains(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),
   "phone keeps accessible page navigation");
  System.out.println("Production tablet combined control/media geometry/lifecycle/input checks="+checks+" passed (fake UI)");
 }
}
""".replace("__METHODS__", methods)
path = out / "TabletControlsRegression.java"
path.write_text(java, encoding="utf-8")
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk / "javac.exe"), "-encoding", "UTF-8", "--release", "17", "-d", str(out),
                *(str(src / name) for name in ("ControlCenterGeometry.java", "ControlCenterPages.java",
                    "TopBarGeometry.java", "MiStepLayout.java")), str(path)], check=True)
subprocess.run([str(jdk / "java.exe"), "-cp", str(out),
                "com.jiusis.mistep.TabletControlsRegression"], check=True)
