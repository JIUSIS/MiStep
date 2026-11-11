"""Exercise the production header layout, actions, options and Host tool lifecycle."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / "module/src/main/java/com/jiusis/mistep"
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
bar = (src / "MiStepTopBar.java").read_text(encoding="utf-8-sig")
host = (src / "MiStepHost.java").read_text(encoding="utf-8-sig")


def member(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


header_methods = "\n".join(member(bar, signature) for signature in (
    "    void setMainOnRight(", "    void applyOptions(",
    "    private void applyHeaderOptions(", "    FrameLayout createTabletHeader(",
    "    void clearTabletHeader(", "    private void cancelTabletToolsAnimation(",
    "    void setTabletToolsExpanded(", "    private void renderTabletTools(",
    "    void setWallpaperBackdrop(",
    "    @Override public boolean dispatchTouchEvent(", "    private final class ControlsHeader "))
host_methods = "\n".join(member(host, signature) for signature in (
    "    private void configureTabletTools(", "    private void setTabletToolsOpen(",
    "    private void resetTopForSuspend(",
    "                @Override public void onOpenClipboard("))
# Widen fixture access only; the extracted method bodies remain production code.
header_methods = header_methods.replace("private final class ControlsHeader", "final class ControlsHeader")
host_methods = host_methods.replace("private void configureTabletTools(", "void configureTabletTools(")
host_methods = host_methods.replace("private void resetTopForSuspend(", "void resetTopForSuspend(")
host_methods = host_methods.replace("@Override public void onOpenClipboard(", "public void onOpenClipboard(")
files = {
    "android/animation/Animator.java": "package android.animation;public class Animator {}",
    "android/animation/AnimatorListenerAdapter.java": """package android.animation;
public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}""",
    "android/animation/ValueAnimator.java": r"""package android.animation;
import java.util.*;import java.util.function.*;
public class ValueAnimator extends Animator {
 float from,to,value;List<Consumer<ValueAnimator>>updates=new ArrayList<>();List<AnimatorListenerAdapter>listeners=new ArrayList<>();
 public static boolean areAnimatorsEnabled(){return true;}
 public static ValueAnimator ofFloat(float...f){var v=new ValueAnimator();v.from=f[0];v.to=f[1];v.value=v.from;return v;}
 public void setDuration(long d){}public void setInterpolator(Object i){}
 public void addUpdateListener(Consumer<ValueAnimator>u){updates.add(u);}public void addListener(AnimatorListenerAdapter l){listeners.add(l);}
 public Object getAnimatedValue(){return value;}public void tick(float p){value=from+(to-from)*p;for(var u:updates)u.accept(this);}
 public void start(){tick(0);}public void cancel(){for(var l:listeners)l.onAnimationEnd(this);}
 public void end(){tick(1);for(var l:listeners)l.onAnimationEnd(this);}
}""",
    "android/view/animation/DecelerateInterpolator.java": """package android.view.animation;
public class DecelerateInterpolator {}""",
    "android/graphics/Rect.java": """package android.graphics;
public class Rect {public int left,top,right,bottom;
 public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}}""",
    "android/graphics/Paint.java": """package android.graphics;
public class Paint {
 float size=20;
 public static class FontMetrics {public float descent=4,ascent=-16;}
 public void setTextSize(float value){size=value;}
 public float measureText(String text){return text.length()*size*.55f;}
 public FontMetrics getFontMetrics(){return new FontMetrics();}
}""",
    "android/view/Gravity.java": """package android.view;
public class Gravity {public static final int CENTER=17;}""",
    "android/util/TypedValue.java": """package android.util;
public class TypedValue {public static final int COMPLEX_UNIT_PX=0;}""",
    "com/jiusis/mistep/TabletHeaderRegression.java": r"""
package com.jiusis.mistep;
import java.util.*;import android.graphics.Rect;

class MotionEvent {}

class Context {
 final Resources resources=new Resources();
}
class Resources {
 final Metrics metrics=new Metrics();
 Metrics getDisplayMetrics(){return metrics;}
}
class Metrics {float density=2.5f,scaledDensity=2.5f;}
class View {
 static final int VISIBLE=0,INVISIBLE=4,GONE=8;
 static final int IMPORTANT_FOR_ACCESSIBILITY_NO=2,IMPORTANT_FOR_ACCESSIBILITY_YES=1;
 interface OnClickListener {void onClick(View view);}
 final Context context;int visibility=VISIBLE,left,top,right,bottom,width,height,raises,requests;
 boolean enabled=true,attached;float alpha=1f;OnClickListener click;Object params;Rect clip;int dispatches;
 final Animator animator=new Animator(this);
 View(Context context){this.context=context;}
 Context getContext(){return context;}Resources getResources(){return context.resources;}
 void setVisibility(int value){visibility=value;}int getVisibility(){return visibility;}
 void setOnClickListener(OnClickListener value){click=value;}
 void performClick(){if(enabled&&visibility==VISIBLE&&click!=null)click.onClick(this);}
 void setContentDescription(String value){}void setTooltipText(String value){}
 void setImportantForAccessibility(int value){}void setEnabled(boolean value){enabled=value;}
 void setAlpha(float value){alpha=value;}void setScaleX(float value){}void setScaleY(float value){}
 void requestLayout(){requests++;}void bringToFront(){raises++;}
 boolean isAttachedToWindow(){return attached;}boolean isLaidOut(){return true;}
 int getWidth(){return width;}int getHeight(){return height;}void setClipBounds(Rect value){clip=value;}
 boolean dispatchTouchEvent(MotionEvent event){dispatches++;return false;}
 Object background;void setBackground(Object value){background=value;}void setBackgroundColor(int value){}
 Animator animate(){return animator;}
 void measure(int w,int h){onMeasure(w,h);}
 protected void onMeasure(int w,int h){setMeasuredDimension(w,h);}
 void setMeasuredDimension(int w,int h){width=w;height=h;}
 void layout(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;onLayout(true,l,t,r,b);}
 protected void onLayout(boolean changed,int l,int t,int r,int b){}
 static class MeasureSpec {static final int EXACTLY=1;
  static int getSize(int spec){return spec;}static int makeMeasureSpec(int size,int mode){return size;}}
 static class Animator {static final List<Animator> pending=new ArrayList<>();
  final View owner;Runnable end;float target;
  Animator(View owner){this.owner=owner;}
  void cancel(){pending.remove(this);end=null;}Animator alpha(float value){target=value;return this;}Animator setStartDelay(long value){return this;}
  Animator setDuration(long value){return this;}Animator withEndAction(Runnable value){end=value;return this;}
  void start(){pending.remove(this);pending.add(this);}
  static void finishAll(){for(Animator value:new ArrayList<>(pending)){
   pending.remove(value);value.owner.setAlpha(value.target);Runnable done=value.end;value.end=null;
   if(done!=null)done.run();
  }}}
}
class FrameLayout extends View {
 final List<View> children=new ArrayList<>();
 FrameLayout(Context c){super(c);}
 void setClipChildren(boolean value){}void setClipToPadding(boolean value){}
 void addView(View view){children.add(view);}void addView(View view,LayoutParams p){view.params=p;addView(view);}
 void removeView(View view){children.remove(view);}
 static class LayoutParams {int width,height,topMargin;
  LayoutParams(int w,int h){width=w;height=h;}}
}
class TextView extends View {
 final android.graphics.Paint paint=new android.graphics.Paint();
 TextView(Context c){super(c);}void setText(String value){}void setTextColor(int color){}
 void setGravity(int value){}void setSingleLine(boolean value){}void setIncludeFontPadding(boolean value){}
 void setTypeface(Object value){}android.graphics.Paint getPaint(){return paint;}
 void setTextSize(int unit,float value){paint.setTextSize(value);}
}
class Typeface {static final int NORMAL=0;static Object create(String family,int style){return new Object();}}
class Bitmap {boolean isRecycled(){return false;}}
class Drawable {}
class LayerDrawable extends Drawable {LayerDrawable(Drawable[] d){}}
class WallpaperCropDrawable extends Drawable {WallpaperCropDrawable(Bitmap b,int w,int h,int x,int y,int t){}}
class HyperOsStyle {static int textPrimary(Context c){return 0;}static int page(Context c){return 0;}
 static Drawable topBackground(Context c){return new Drawable();}static int topWallpaperTint(Context c){return 0;}
 static Drawable topWallpaperWash(Context c){return new Drawable();}}
class MiStepIconButton extends View {
 static final int SETTINGS=0,EXIT=1,PREVIOUS=2,NEXT=3,CLIPBOARD=4,TORCH=5,
  SCREENSHOT=6,RECORD=7,TOOLS=11,PORTRAIT_TASK=12;
 final int icon;boolean exitLeft;MiStepIconButton(Context c,int i,String label){super(c);icon=i;}
 void setExitLeft(boolean value){exitLeft=value;}
}
class TestBar extends FrameLayout {
 boolean mainOnRight;WorkspaceOptions options=WorkspaceOptions.DEFAULT;
 Bitmap glassWallpaper;int glassWidth,glassHeight;
 int tabletHeaderHeight;float tabletToolsProgress=1f;boolean tabletToolsExpanded,taskCellsCovered;
 android.animation.ValueAnimator tabletToolsAnimator;
 final View quickRail=new View(getContext());
 ControlsHeader header,tabletHeader;TopBarGeometry geometry=new TopBarGeometry(1200,600,0,2.5f);
 final Map<String,Integer> actions=new HashMap<>();int steps;
 final Listener listener=new Listener();
 final Center controlCenter=new Center(getContext());
 int clipboardOpens;
 void toggleClipboard(Object bitmap,int width,int height){
  if(getVisibility()!=View.VISIBLE)throw new AssertionError("clipboard parent must be visible");
  clipboardOpens++;
 }
 class Center extends View {Center(Context c){super(c);}void applyOptions(WorkspaceOptions value){}void setWallpaper(Bitmap b,int w,int h){}}
 class Listener {
  void hit(String name){actions.merge(name,1,Integer::sum);}
  void onOpenLandscape(){hit("taskTitle");}void onExit(){hit("exit");}
  boolean covered;int coverageChanges;void onTaskCellsCovered(boolean value){covered=value;coverageChanges++;}
  void onOpenSettings(){hit("settings");}void onOpenClipboard(){hit("clipboard");}
  void onToggleTorch(){hit("torch");}void onScreenshot(){hit("shot");}
  void onScreenRecord(){hit("record");}
 }
 TestBar(Context c){super(c);header=new ControlsHeader(c);addView(header);}
 void stepApp(int direction){steps+=direction;listener.hit(direction<0?"previous":"next");}
 __HEADER_METHODS__
}
class HostFixture {
 Object tabletCoveredMain,tabletReturnedTop;
 Context ctx=new Context();FrameLayout root=new FrameLayout(ctx),topLiveClip=new FrameLayout(ctx),tabletTools;
 TestBar topBar=new TestBar(ctx);MiStepLayout layout=MiStepLayout.of(3048,2032,false,2.5f,true);
 boolean tabletToolsOpen,topFace;static final int SLOT_TOP=0;
 float workspaceAlpha=1f;
 static final int ACTIVE=1;int scene=ACTIVE,screenW=3048,screenH=2032;
 Object promotion,wallpaperBackdrop;boolean transferBusy;
 static class Slot {boolean occupied;}Slot[] slots={new Slot()};
 int captures,releases,updates;static int clipboardCloses,scannerCloses,pickerCloses;
 long topFaceGeneration;boolean topSelectionPending;View topTaskPane=new View(ctx);
 static class Roll {void reset(boolean b){}}Roll topRoll=new Roll();
 static class State {void sceneChanged(){}}State topTaskState=new State();
 void cancelTopTaskTransition(){}
 static class MiStepClipboard {
  static MiStepClipboard get(Context c){return new MiStepClipboard();}
  void close(){clipboardCloses++;}
 }
 static class MiStepScanner {static void closeImmediately(){scannerCloses++;}}
 void releaseTopTask(boolean front,boolean dismissed,Runnable after){releases++;}
 void captureMainIntoTop(){captures++;}void updateTopTaskGeometry(){updates++;}
 __HOST_METHODS__
}
public class TabletHeaderRegression {
 static int checks;
 static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
 static void layout(TestBar bar,FrameLayout header,int width,int height){
  header.measure(width,height);header.layout(0,0,width,height);
  var h=(TestBar.ControlsHeader)header;
  check(h.title.left+h.title.right==width,"title remains centered");
  List<View> visible=new ArrayList<>();
  for(View child:h.children)if(child.getVisibility()==View.VISIBLE){
   check(child.left>=0&&child.top==0&&child.right<=width&&child.bottom==height,
    "every visible title control fits the physical toolbar");
   visible.add(child);
  }
  visible.sort(Comparator.comparingInt(v->v.left));
  for(int i=1;i<visible.size();i++)check(visible.get(i-1).right<=visible.get(i).left,
    "title controls and centered title never overlap");
 }
 static void side(TestBar.ControlsHeader h,boolean mainRight){
  check(h.exit.exitLeft==mainRight,"exit arrow follows the main side");
  for(View control:new View[]{h.exit,h.settings,h.clipboard,h.shot})
   if(control.getVisibility()==View.VISIBLE)
    check(mainRight?control.right<=h.title.left:control.left>=h.title.right,
     "exit/settings/clipboard/screenshot group follows the main side");
  for(View control:new View[]{h.tools,h.previous,h.next,h.torch,h.record})
   if(control.getVisibility()==View.VISIBLE)
    check(mainRight?control.left>=h.title.right:control.right<=h.title.left,
     "quick app/tools group mirrors to the opposite side");
 }
 static void controls(TestBar.ControlsHeader h,float alpha,boolean enabled){
  for(View control:h.children)if(control instanceof MiStepIconButton&&control.getVisibility()==View.VISIBLE){
   check(control.alpha==alpha,"every visible button participates in the swap opacity");
   check(control.enabled==enabled,"every visible button participates in swap input blocking");
  }
  check(h.title.alpha==1f,"centered title stays stable during button exchange");
 }
 public static void main(String[] args){
  for(int[] screen:new int[][]{{3048,2032},{2560,1600},{1280,800}})
   for(float density:new float[]{1,1.5f,2.5f}){
    Context c=new Context();c.resources.metrics.density=density;c.resources.metrics.scaledDensity=density*1.6f;
    TestBar bar=new TestBar(c);int[] extras={0,0};
    var h=(TestBar.ControlsHeader)bar.createTabletHeader(Math.round(36*density),()->extras[0]++);
    check(h.buttons.length==8&&h.children.size()==10,"eight original actions plus tools and title; no extra collect button");
    check(bar.header.getVisibility()==View.GONE,"drawer cannot duplicate the permanent header");
    layout(bar,h,screen[0],Math.round(36*density));
    for(var button:h.buttons)button.performClick();
    for(String action:new String[]{"exit","settings","clipboard","previous","next","torch","shot","record"})
     check(bar.actions.getOrDefault(action,0)==1,"original action connected: "+action);
    h.tools.performClick();
    check(extras[0]==1,"tool expansion entry works");
    for(boolean mainRight:new boolean[]{false,true})for(int mask=0;mask<256;mask++){
     int hidden=0;for(int i=0;i<8;i++)if((mask&(1<<i))!=0)hidden|=1<<WorkspaceOptions.HEADER_IDS[i];
     bar.setMainOnRight(mainRight);
     bar.applyOptions(new WorkspaceOptions(hidden,0));layout(bar,h,screen[0],Math.round(36*density));
     side(h,mainRight);
     for(int i=0;i<8;i++)check((h.buttons[i].getVisibility()==View.GONE)==((mask&(1<<i))!=0),
      "tablet header follows live user visibility choices");
    }
    bar.clearTabletHeader();
    check(bar.tabletHeader==null&&bar.header.getVisibility()==View.VISIBLE,"portrait header restored");
   }
  Context c=new Context();TestBar bar=new TestBar(c);
  var animated=(TestBar.ControlsHeader)bar.createTabletHeader(90,()->{});
  layout(bar,animated,3048,90);animated.attached=true;
  int originalExit=animated.exit.left,originalTools=animated.tools.left;
  bar.setMainOnRight(true);
  for(View control:animated.children)if(control instanceof MiStepIconButton)
   check(!control.enabled,"all nine tablet controls block input during fade-out");
  check(animated.exit.left==originalExit&&animated.tools.left==originalTools,
   "buttons retain old positions until fade-out completes");
  View.Animator.finishAll();controls(animated,0f,false);
  check(animated.exit.left==originalExit&&animated.tools.left==originalTools,
   "layout waits for the transparent frame");
  layout(bar,animated,3048,90);side(animated,true);controls(animated,0f,false);
  check(animated.exit.left!=originalExit&&animated.tools.left!=originalTools,
   "both original and added button groups exchange positions while hidden");
  View.Animator.finishAll();controls(animated,1f,true);
  bar.setMainOnRight(false);View.Animator.finishAll();controls(animated,0f,false);
  bar.applyOptions(WorkspaceOptions.DEFAULT);
  layout(bar,animated,3048,90);side(animated,false);controls(animated,1f,true);
  View.Animator.finishAll();controls(animated,1f,true);
  bar.setMainOnRight(true);View.Animator.finishAll();layout(bar,animated,3048,90);
  bar.setMainOnRight(false);View.Animator.finishAll();layout(bar,animated,3048,90);
  View.Animator.finishAll();side(animated,false);controls(animated,1f,true);
  bar.measure(3048,606);bar.layout(0,0,3048,606);bar.attached=true;
  bar.setTabletToolsExpanded(false,false);
  check(!bar.listener.covered,"collapsed drawer releases the shared cell presentation gate");
  check(bar.getVisibility()==View.INVISIBLE&&bar.clip.top==90&&bar.clip.bottom==90,
   "collapsed drawer hides all content and preserves the independent title area");
  bar.setTabletToolsExpanded(true,true);
  check(bar.listener.covered,"opening covers real tasks before the first drawer frame");
  check(bar.getVisibility()==View.VISIBLE&&bar.clip.bottom==90&&bar.tabletToolsAnimator!=null,
   "opening starts with a clipped live drawer instead of a visibility jump");
  check(bar.dispatchTouchEvent(new MotionEvent())&&bar.dispatches==0,"opening drawer blocks content actions");
  var opening=bar.tabletToolsAnimator;opening.tick(.5f);
  check(bar.clip.top==90&&bar.clip.bottom==348&&bar.controlCenter.alpha==.5f&&bar.quickRail.alpha==.5f,
   "half-open controls and app rail reveal below the title without changing parent workspace opacity");
  bar.setTabletToolsExpanded(false,true);var closing=bar.tabletToolsAnimator;
  check(bar.listener.covered,"closing holds cells until the final drawer frame");
  opening.end();check(bar.tabletToolsAnimator==closing&&bar.clip.bottom==348,
   "cancelled opening callbacks cannot reopen a closing drawer");
  closing.end();check(bar.getVisibility()==View.INVISIBLE&&bar.clip.bottom==90,"close hides only after its final frame");
  check(!bar.listener.covered,"only final collapse restores the original live cells");
  bar.setTabletToolsExpanded(true,true);bar.tabletToolsAnimator.end();
  check(bar.clip.bottom==606&&bar.controlCenter.alpha==1f&&!bar.dispatchTouchEvent(new MotionEvent())
    &&bar.dispatches==1,"fully expanded drawer restores content interaction");
  bar.clearTabletHeader();
  check(!bar.listener.covered,"rotation to phone-style layout clears the old coverage lease");
  check(bar.clip==null&&bar.controlCenter.alpha==1f&&bar.quickRail.alpha==1f,
   "portrait restoration clears tablet reveal clipping and child opacity");
  HostFixture host=new HostFixture();host.configureTabletTools();
  check(host.root.children.size()==1&&host.tabletTools==host.topBar.tabletHeader,"Host installs the complete header");
  FrameLayout first=host.tabletTools;host.configureTabletTools();
  check(host.root.children.size()==1&&!host.root.children.contains(first),"reconfigure replaces only the old toolbar");
  for(float opacity:new float[]{0f,.5f,1f}){
   host.workspaceAlpha=opacity;host.configureTabletTools();
   check(host.tabletTools.alpha==opacity,"rebuilt tablet title inherits the current workspace opacity");
  }
  var h=host.topBar.tabletHeader;h.tools.performClick();
  check(host.tabletToolsOpen&&host.topBar.getVisibility()==View.VISIBLE&&host.tabletTools.raises>0,
    "tools open above real task panes while permanent header stays on top");
  h.tools.performClick();
  check(!host.tabletToolsOpen&&host.topBar.getVisibility()==View.INVISIBLE
    &&HostFixture.clipboardCloses==1&&HostFixture.scannerCloses==1,"tool close also closes transient panels");
  host.onOpenClipboard();
  check(host.tabletToolsOpen&&host.topBar.clipboardOpens==1,"clipboard opens its actual visible parent first");
  host.transferBusy=true;host.onOpenClipboard();
  check(host.topBar.clipboardOpens==1,"clipboard cannot bypass an active task transfer");
  host.topBar.attached=true;host.topBar.measure(3048,606);
  host.topBar.setWallpaperBackdrop(new Bitmap(),3048,2032);
  check(host.topBar.background==null,"wallpaper refresh cannot reintroduce the tablet drawer white wash");
  host.topBar.setTabletToolsExpanded(true,true);var pending=host.topBar.tabletToolsAnimator;
  host.resetTopForSuspend();pending.end();
  check(!host.tabletToolsOpen&&host.topBar.getVisibility()==View.INVISIBLE&&host.topBar.tabletToolsAnimator==null,
   "exit cancels stale drawer frames and closes the real drawer");
  host.configureTabletTools();check(!host.tabletToolsOpen&&host.topBar.getVisibility()==View.INVISIBLE,
   "next workspace entry starts collapsed");
  host.layout=MiStepLayout.of(2032,3048,false,2.5f,true);host.topFace=false;host.configureTabletTools();
  check(host.tabletTools==null&&host.topBar.tabletHeader==null&&host.topBar.header.getVisibility()==View.VISIBLE,
    "portrait transition restores the original header without stale toolbar");
  check(host.topBar.background!=null,"portrait restores its existing wallpaper surface");
  System.out.println("Production tablet header layout/actions/options/lifecycle checks="+checks+" passed (fake UI)");
 }
}
""".replace("__HEADER_METHODS__", header_methods).replace("__HOST_METHODS__", host_methods)
}
# The real Host calls this existing picker close method on its MiStepTopBar.
files["com/jiusis/mistep/TabletHeaderRegression.java"] = files[
    "com/jiusis/mistep/TabletHeaderRegression.java"].replace(
    "void stepApp(int direction)", "void closeQuickAppPicker(){HostFixture.pickerCloses++;}\n void stepApp(int direction)")
paths = []
for relative, content in files.items():
    path = out / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    paths.append(str(path))
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk / "javac.exe"), "-encoding", "UTF-8", "--release", "17", "-d", str(out),
                *(str(src / name) for name in (
                    "MiStepLayout.java", "WorkspaceOptions.java", "TopBarGeometry.java",
                    "TopBarSwapAnimation.java")), *paths], check=True)
subprocess.run([str(jdk / "java.exe"), "-cp", str(out),
                "com.jiusis.mistep.TabletHeaderRegression"], check=True)
