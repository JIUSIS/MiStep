import os
"""Execute actual Host entry/exit methods with real-surface and deterministic animator doubles."""
from pathlib import Path
import subprocess
import sys
root=Path(__file__).resolve().parents[2]; src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
host=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
def member(signature):
 a=host.index(signature);b=host.index('{',a);d=1;i=b+1
 while d:
  if host[i]=='{':d+=1
  elif host[i]=='}':d-=1
  i+=1
 return host[a:i]
methods='\n'.join(member(s) for s in ['    private void beginWorkspaceEntry(', '    private void awaitWorkspaceFrame(', '    private void setWorkspaceAlpha(', '    private android.graphics.RectF workspaceMainHole(', '    private void updateWorkspaceBacking(', '    private void updateWorkspaceBackdropVisibility(', '    private void playWorkspaceFade(', '    private void suspendSession(', '    private boolean ownsCellPresentation(', '    private boolean cellPresentationVisible(', '    private void updateCellSurfaceVisibility('])
files={
'android/animation/Animator.java':'package android.animation;public class Animator {}',
'android/animation/AnimatorListenerAdapter.java':'package android.animation;public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}',
'android/animation/ValueAnimator.java':r'''package android.animation;
import java.util.*;import java.util.function.*;public class ValueAnimator extends Animator {
 float from,to,value;List<Consumer<ValueAnimator>>updates=new ArrayList<>();List<AnimatorListenerAdapter>listeners=new ArrayList<>();
 public static ValueAnimator ofFloat(float...f){ValueAnimator v=new ValueAnimator();v.from=f[0];v.to=f[1];v.value=v.from;return v;}
 public long delay;public void setStartDelay(long d){delay=d;}public void setCurrentPlayTime(long t){tick(t/300f);}public void setDuration(long d){}public void setInterpolator(Object i){}public void addUpdateListener(Consumer<ValueAnimator>u){updates.add(u);}public void addListener(AnimatorListenerAdapter l){listeners.add(l);}
 public Object getAnimatedValue(){return value;}public void tick(float p){value=from+(to-from)*p;for(var u:updates)u.accept(this);}public void start(){tick(0f);}
 public void cancel(){for(var l:listeners)l.onAnimationEnd(this);}public void end(){tick(1f);for(var l:listeners)l.onAnimationEnd(this);}
}''',
'android/view/animation/PathInterpolator.java':'package android.view.animation;public class PathInterpolator {public PathInterpolator(float a,float b,float c,float d){}}',
'android/graphics/RectF.java':'package android.graphics;public class RectF {public float left,top,right,bottom;public RectF(float a,float b,float c,float d){left=a;top=b;right=c;bottom=d;}}',
'android/view/SurfaceControl.java':r'''package android.view;
import java.util.*;public class SurfaceControl {public boolean valid=true,visible=true;public float alpha=1;public boolean isValid(){return valid;}
 public static class Transaction {List<Runnable>ops=new ArrayList<>();public Transaction setAlpha(Object o,float a){ops.add(()->((SurfaceControl)o).alpha=a);return this;}public Transaction show(Object o){ops.add(()->((SurfaceControl)o).visible=true);return this;}public Transaction hide(Object o){ops.add(()->((SurfaceControl)o).visible=false);return this;}public void apply(){ops.forEach(Runnable::run);}public void close(){}}
}''',
'de/robv/android/xposed/XposedHelpers.java':r'''package de.robv.android.xposed;
public class XposedHelpers {public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new IllegalStateException(e);}}}''',
'com/jiusis/mistep/WorkspaceRegression.java':r'''package com.jiusis.mistep;
import java.util.*;import android.view.SurfaceControl;import de.robv.android.xposed.XposedHelpers;
public class WorkspaceRegression {
 static class LiveSwapVisualLease {static void writeAlpha(View v,float a){v.setAlpha(a);}}
 static class SideDismiss {boolean isTracking(){return false;}void settle(boolean b){}}
 SideDismiss sideDismiss;
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static class Drawable {int alpha=255;void setAlpha(int a){alpha=a;}}
 static class WallpaperCropDrawable extends Drawable {android.graphics.RectF hole;void setMainHole(android.graphics.RectF r){hole=r;}}
 static class View {static final int VISIBLE=0,GONE=8;float alpha=1;int pickerCloses;Drawable background=new WallpaperCropDrawable();Drawable getBackground(){return background;}void setAlpha(float v){alpha=v;}void closeQuickAppPicker(){pickerCloses++;}}
 static class Observer {Runnable frame;void registerFrameCommitCallback(Runnable r){frame=r;}}
 static class FrameLayout extends View {Observer observer=new Observer();boolean attached=true,hardware=true;int invalidates;
  boolean isAttachedToWindow(){return attached;}boolean isHardwareAccelerated(){return hardware;}Observer getViewTreeObserver(){return observer;}void invalidate(){invalidates++;}void draw(){Runnable r=observer.frame;observer.frame=null;if(r!=null)r.run();}}
 static class Queue {List<Runnable>q=new ArrayList<>();void post(Runnable r){q.add(r);}void postDelayed(Runnable r,long d){q.add(r);}void flush(){while(!q.isEmpty())q.remove(0).run();}}
 static class Controller {SurfaceControl surface=new SurfaceControl();Object token=new Object();}
 static class Slot {int index=1;int taskId=1;Object taskToken;boolean occupied=true,parked,promoting,launching;Controller controller=new Controller();View hint=new View(),view=new View();Slot(){taskToken=controller.token;}}
 static class Bitmap {void recycle(){}}
 static class StatusBar {void restore(){}}
 static class Clipboard {void close(){}}static class MiStepClipboard {static Clipboard get(Object c){return new Clipboard();}}
 static class SystemClock {static long uptimeMillis(){return 10000;}}
 static class Promotion {boolean nativeSwap,cancelled;}
 static final int ACTIVE=1,RESUMING=2,SUSPENDING=3,SUSPENDED=4,SLOT_COUNT=4,CELL_SLOT_COUNT=4,SLOT_MAIN=0,SLOT_TOP=4;static final long ZOOM_ANIM_MS=300;
 FrameLayout root=new FrameLayout();View topBar=new View(),tabletTools=new View(),topStripBacking=new View(),mainGapBacking=new View(),topTaskPane=new View();boolean topFace,taskCellsCovered;Roll topRoll;View[]sideBackingViews={null,new View(),new View(),new View()};Slot[]slots={new Slot(),new Slot(),new Slot(),new Slot()};
 static class Roll{boolean rolling(){return false;}boolean showingTask(){return false;}}
 static class CellMotion{void finish(){}}
 Queue main=new Queue();Object ctx=new Object();CellMotion cellMotion;StatusBar statusBar=new StatusBar();Promotion promotion;Bitmap mainBackdrop,wallpaperBackdrop=new Bitmap();
  boolean screenOn=true,zoomed=true,suspendRequested,landscapeSuspended,transferBusy,workspaceEntering,keyguardLocked;int scene=ACTIVE,zoomWrites,parks,returns,ready;long sceneEpoch=1,workspaceGeneration,zoomAnimUntil;
 static class Layout {boolean tabletLandscape;float mainScale=.7017717f;int mainOffsetX,mainOffsetY=606;}
 Layout layout=new Layout();int screenW=3048,screenH=2032;long workspaceZoomStart=10032;
 Object workspaceBackdrop;int backdropAcquires;void prepareWorkspaceBackdrop(long g,int attempt,Runnable ready){backdropAcquires++;ready.run();}
 android.animation.ValueAnimator workspaceAnimator;float workspaceAlpha=1f;
 void writeZoomAnimated(boolean on){zoomWrites++;}void writeZoom(boolean on){zoomWrites++;}void setZoomState(boolean on){zoomed=on;}void scheduleMainCropClear(long d){}void publishDesktopSideTasks(){}
 void resetTopForSuspend(){}
 void closeSwapPreview(){}void closeCellMovePreview(){}void restoreCarrierToMain(){}void hideIme(){}void log(String s){}void failPromotion(Promotion p,String s){}
 void returnUnboundOwnedCandidates(long epoch,Runnable r){returns++;r.run();}void parkNext(int i){parks++;}
 Object getSurfaceField(Object o,String n){return o==null?null:((Controller)o).surface;}
 boolean controllerOwns(Slot s,int id,Object token){return s.taskId==id&&s.controller.token==token;}
 void closeSurfaceObject(Object t,String n){if(t!=null)((SurfaceControl.Transaction)t).close();}
 __METHODS__
 public static void main(String[]args){
  var h=new WorkspaceRegression();h.beginWorkspaceEntry(()->h.ready++);
  check(h.backdropAcquires==0,"native backdrop acquisition must wait for the first committed Host frame");
  check(h.workspaceAlpha==0&&h.topBar.alpha==0&&h.mainGapBacking.alpha==0&&h.slots[1].controller.surface.alpha==0,"entry hides controls, gap background and actual task parents before first buffer");
  check(h.tabletTools.alpha==0,"permanent tablet title is hidden before the first workspace frame");
  check(h.zoomWrites==0&&h.root.observer.frame!=null,"main zoom waits for committed Host buffer");h.root.draw();h.main.flush();
  check(h.backdropAcquires==1,"one native backdrop acquisition follows committed visibility");
  check(h.zoomWrites==1&&h.workspaceAnimator!=null,"one real main zoom starts after frame");h.workspaceAnimator.tick(.5f);
  check(h.workspaceAlpha==.5f&&h.slots[2].controller.surface.alpha==.5f,"actual side containers fade with Host controls");h.workspaceAnimator.end();
  check(h.tabletTools.alpha==1,"permanent tablet title reaches full opacity with the real workspace");
  check(h.workspaceAlpha==1&&h.ready==1,"entry continuation follows end frame");
  h=new WorkspaceRegression();h.beginWorkspaceEntry(null);h.scene=SUSPENDING;h.root.draw();h.main.flush();check(h.zoomWrites==0,"stale entry cannot shrink a closing workspace");
  h=new WorkspaceRegression();h.beginWorkspaceEntry(null);h.keyguardLocked=true;h.root.draw();h.main.flush();check(h.zoomWrites==0,"late entry frame cannot magnify keyguard");
  h=new WorkspaceRegression();h.wallpaperBackdrop=null;h.beginWorkspaceEntry(null);check(h.root.observer.frame==null&&!h.main.q.isEmpty(),"entry waits for wallpaper instead of showing a black surround");h.wallpaperBackdrop=new Bitmap();h.main.flush();check(h.root.observer.frame!=null,"wallpaper readiness schedules real frame gate");
  h=new WorkspaceRegression();h.suspendSession("test");check(h.scene==SUSPENDING&&h.zoomWrites==1&&h.returns==0,"exit does not hand tasks back during real zoom");
  check(h.topBar.pickerCloses==1,"exit closes the shortcut editor before task handback");
  Object owner=h.slots[1].taskToken;h.workspaceAnimator.tick(.5f);check(h.slots[1].controller.surface.alpha==.5f&&h.slots[1].taskToken==owner,"exit fades real content without changing owner");
  check(h.tabletTools.alpha==.5f,"permanent tablet title follows exit opacity instead of remaining visible");
  h.workspaceAnimator.end();check(h.returns==1&&h.parks==1&&h.workspaceAlpha==0,"task handback starts only after fade finishes");
  check(h.tabletTools.alpha==0,"permanent tablet title is fully hidden before task handback");
  h=new WorkspaceRegression();h.playWorkspaceFade(true,0,()->{throw new AssertionError("stale entry completed");});var old=h.workspaceAnimator;old.tick(.4f);h.workspaceGeneration++;h.playWorkspaceFade(false,1,null);float beforeLate=h.workspaceAlpha;old.tick(.8f);old.end();check(h.workspaceAlpha==beforeLate,"cancelled animator cannot alter new exit");
  h.slots[1].occupied=false;h.updateCellSurfaceVisibility(h.slots[1]);check(!h.slots[1].controller.surface.visible,"empty TaskView cannot leave its old resize background visible");
  h.slots[1].occupied=true;h.updateCellSurfaceVisibility(h.slots[1]);check(h.slots[1].controller.surface.visible,"same live binding reveals the actual container");
  h.slots[1].occupied=false;h.updateCellSurfaceVisibility(h.slots[1]);h.setWorkspaceAlpha(.6f);
  check(h.slots[1].view.alpha==0&&h.slots[1].controller.surface.alpha==0,"workspace fade cannot resurrect an empty native resize board");
  h.slots[1].occupied=true;h.updateCellSurfaceVisibility(h.slots[1]);
  h.slots[1].promoting=true;h.slots[1].occupied=false;h.updateCellSurfaceVisibility(h.slots[1]);check(h.slots[1].controller.surface.visible,"Shell owns container visibility during a role transition");
  h.slots[1].controller.surface.alpha=.37f;h.setWorkspaceAlpha(.2f);
  check(h.slots[1].controller.surface.alpha==.37f,"workspace fade respects Shell presentation ownership during handoff");
  h=new WorkspaceRegression();h.taskCellsCovered=true;h.setWorkspaceAlpha(.8f);
  check(h.slots[1].controller.surface.alpha==0&&h.slots[1].hint.alpha==0,"workspace callback cannot expose covered cells or plus hints");
  h=new WorkspaceRegression();h.scene=RESUMING;h.beginWorkspaceEntry(null);h.suspendSession("exit-before-first-frame");
  check(h.scene==SUSPENDING&&!h.workspaceEntering,"exit during pending resume frame cannot strand RESUMING");h.root.draw();h.main.flush();check(h.zoomWrites==1,"late entry frame cannot restart zoom after exit");h.workspaceAnimator.end();check(h.parks==1,"pending-entry exit returns tasks");
  h=new WorkspaceRegression();h.scene=RESUMING;h.beginWorkspaceEntry(null);h.screenOn=false;h.suspendSession("screen-lock");
  check(h.scene==SUSPENDING&&h.parks==1&&h.workspaceAnimator==null,"screen lock restores immediately without waiting for hidden display frames");h.root.draw();h.main.flush();check(h.zoomWrites==1&&!h.zoomed,"late unlocked entry never re-enables zoom");
  h=new WorkspaceRegression();h.tabletTools=null;h.beginWorkspaceEntry(null);
  check(h.workspaceAlpha==0,"portrait workspace remains valid without a permanent tablet title");
  for(boolean mainRight:new boolean[]{false,true}) {
   h=new WorkspaceRegression();h.layout.tabletLandscape=true;h.layout.mainOffsetX=mainRight?909:0;
   h.playWorkspaceFade(false,0,null);check(h.workspaceAnimator.delay==32,"Host shares the published monotonic start");
   for(float p:new float[]{0f,.25f,.5f,.75f,1f}) {
    h.setWorkspaceAlpha(p);var hole=((WallpaperCropDrawable)h.topStripBacking.background).hole;
    check(h.topStripBacking.alpha==1&&h.mainGapBacking.alpha==1,"wallpaper stays opaque outside moving main");
    check(Math.abs(hole.left-h.layout.mainOffsetX*p)<.01&&Math.abs(hole.top-606*p)<.01,"hole follows actual main offset");
    check(Math.abs(hole.right-hole.left-3048*(1+(.7017717f-1)*p))<.01,"hole uses the same main scale");
    check(h.slots[1].controller.surface.alpha==p&&((WallpaperCropDrawable)h.slots[1].hint.background).hole!=null,"real task fade and wallpaper exclusion coexist");
   }
   h.workspaceBackdrop=new Object();h.setWorkspaceAlpha(.5f);
   check(h.topStripBacking.alpha==1&&h.mainGapBacking.alpha==1,"cutout wallpaper remains opaque while the native buffer reaches its first composition");
   check(h.slots[1].hint.background.alpha==255&&h.slots[1].controller.surface.alpha==.5f,"hint wallpaper stays opaque while the actual task remains live");
   h.setWorkspaceAlpha(1f);check(h.slots[1].hint.background.alpha==255,"settled hint wallpaper restores opacity");
   h.setWorkspaceAlpha(.5f);h.workspaceBackdrop=null;h.setWorkspaceAlpha(.5f);
   check(h.slots[1].hint.background.alpha==255,"failed or released native wallpaper restores original hint background");
   h.layout.tabletLandscape=false;h.setWorkspaceAlpha(.5f);
   check(((WallpaperCropDrawable)h.topStripBacking.background).hole==null&&h.topStripBacking.alpha==.5f,"portrait restores original background behavior");
  }
  h=new WorkspaceRegression();h.layout.tabletLandscape=true;h.wallpaperBackdrop=null;
  h.topStripBacking.background=new Drawable();h.mainGapBacking.background=new Drawable();
  for(View backing:h.sideBackingViews)if(backing!=null)backing.background=new Drawable();
  Object coldTask=h.slots[1].taskToken;h.beginWorkspaceEntry(null);
  check(h.topStripBacking.alpha==0&&h.mainGapBacking.alpha==0,"cold tablet entry hides uncut placeholder strip and gap");
  for(int i=1;i<CELL_SLOT_COUNT;i++)check(h.sideBackingViews[i].alpha==0,"cold tablet entry cannot expose white page placeholders slot="+i);
  check(h.zoomWrites==0&&h.root.observer.frame==null,"cold entry keeps the real main unscaled until wallpaper is ready");
  h.mainGapBacking=new View();h.mainGapBacking.background=new Drawable();h.updateWorkspaceBackdropVisibility();
  check(h.mainGapBacking.alpha==0,"an asynchronously created placeholder obeys the same cold-entry gate");
  h.wallpaperBackdrop=new Bitmap();h.topStripBacking.background=new WallpaperCropDrawable();h.mainGapBacking.background=new WallpaperCropDrawable();
  for(View backing:h.sideBackingViews)if(backing!=null)backing.background=new WallpaperCropDrawable();
  float coldSurfaceAlpha=h.slots[1].controller.surface.alpha;h.updateWorkspaceBackdropVisibility();
  check(h.topStripBacking.alpha==1&&h.mainGapBacking.alpha==1,"real cutout wallpaper becomes drawable after replacement");
  check(((WallpaperCropDrawable)h.topStripBacking.background).hole.right==3048&&((WallpaperCropDrawable)h.topStripBacking.background).hole.bottom==2032,"wallpaper replacement excludes the entire still-fullscreen main");
  check(h.slots[1].controller.surface.alpha==coldSurfaceAlpha&&h.slots[1].taskToken==coldTask,"asynchronous wallpaper replacement never changes live task visibility or identity");
  h.main.flush();check(h.root.observer.frame!=null&&h.zoomWrites==0,"wallpaper readiness still waits for a real Host frame");
  h.root.draw();h.main.flush();check(h.zoomWrites==1,"cold entry starts one zoom after the actual frame gate");
  System.out.println("actual workspace entry/exit/real-surface/late-frame checks="+checks+" passed");
 }
}'''.replace('__METHODS__',methods)
}
# Java closures in the fixture must capture an immutable owner, just as production does.
files['com/jiusis/mistep/TopControlsCreationRegression.java']=r'''package com.jiusis.mistep;
import java.util.function.Consumer;
public class TopControlsCreationRegression {
 static class View {float alpha=1f;boolean visible;void setAlpha(float a){alpha=a;}void setOnClickListener(Consumer<View> c){}void bringToFront(){}}
 static class FrameLayout extends View {float attachedAlpha=-1;View child;static class LayoutParams{LayoutParams(int w,int h){}}void addView(View v,LayoutParams p){child=v;attachedAlpha=v.alpha;}}
 static class MiStepTopTaskPane extends View {View hint;MiStepTopTaskPane(Object c,View h){hint=h;}}
 static class MiStepTopRoll extends View {MiStepTopRoll(Object c,Runnable settled,Consumer<Float> render){}}
 static class Slot {View hint,view=new View();Object controller=new Object(),taskToken=new Object();}
 static class Layout {boolean tabletLandscape;int topStrip=450;}
 static final int SLOT_TOP=5;Slot[] slots={new Slot(),new Slot(),new Slot(),new Slot(),new Slot(),new Slot()};
 Object ctx=new Object();float workspaceAlpha;FrameLayout root=new FrameLayout(),topLiveClip=new FrameLayout();View topBar=new View();
 MiStepTopTaskPane topTaskPane;MiStepTopRoll topRoll;Layout layout=new Layout();boolean topFace;
 View makeEmptyHint(){return new View();}void captureMainIntoTop(){}void topRollSettled(){}void renderTopRollFrame(float p){}
 void updateTopTaskGeometry(){topTaskPane.visible=true;}
 __CREATE__
 public static void main(String[] args){int checks=0;for(boolean tablet:new boolean[]{true,false})for(float alpha:new float[]{0f,.4f,1f}){
  var h=new TopControlsCreationRegression();h.layout.tabletLandscape=tablet;h.workspaceAlpha=alpha;
  Slot upper=h.slots[SLOT_TOP];View liveView=upper.view;Object controller=upper.controller,token=upper.taskToken;
  h.createTopTaskControls();FrameLayout parent=tablet?h.root:h.topLiveClip;
  // Portrait subsequently attaches the roll to the same parent; capture at pane attachment separately.
  if(h.topTaskPane.alpha!=alpha)throw new AssertionError("late-created pane exposes white hint before workspace entry: "+alpha);checks++;
  if(tablet&&parent.attachedAlpha!=alpha)throw new AssertionError("pane must inherit opacity before attachment");checks++;
  if(!h.topTaskPane.visible||h.topTaskPane.hint!=upper.hint)throw new AssertionError("created pane keeps its live empty hint while visible");checks++;
  if(upper.view!=liveView||upper.controller!=controller||upper.taskToken!=token)throw new AssertionError("creation changes the real task owner");checks++;
 }System.out.println("actual asynchronous TOP controls creation checks="+checks+" passed");}
}'''.replace('__CREATE__',member('    private void createTopTaskControls()'))
files['com/jiusis/mistep/WorkspaceRegression.java']=files['com/jiusis/mistep/WorkspaceRegression.java'].replace('h.beginWorkspaceEntry(()->h.ready++);','WorkspaceRegression initial=h;h.beginWorkspaceEntry(()->initial.ready++);')
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out)]+[str(out/n)for n in files],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.WorkspaceRegression'],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TopControlsCreationRegression'],check=True)
