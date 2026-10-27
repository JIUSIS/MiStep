import os
"""Execute actual Host entry/exit methods with real-surface and deterministic animator doubles."""
from pathlib import Path
import subprocess
import sys
root=Path(__file__).resolve().parents[2]; src=root/'module/src/main/java/com/jianguo/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
host=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
def member(signature):
 a=host.index(signature);b=host.index('{',a);d=1;i=b+1
 while d:
  if host[i]=='{':d+=1
  elif host[i]=='}':d-=1
  i+=1
 return host[a:i]
methods='\n'.join(member(s) for s in ['    private void beginWorkspaceEntry(', '    private void awaitWorkspaceFrame(', '    private void setWorkspaceAlpha(', '    private void playWorkspaceFade(', '    private void suspendSession(', '    private void updateCellSurfaceVisibility('])
files={
'android/animation/Animator.java':'package android.animation;public class Animator {}',
'android/animation/AnimatorListenerAdapter.java':'package android.animation;public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}',
'android/animation/ValueAnimator.java':r'''package android.animation;
import java.util.*;import java.util.function.*;public class ValueAnimator extends Animator {
 float from,to,value;List<Consumer<ValueAnimator>>updates=new ArrayList<>();List<AnimatorListenerAdapter>listeners=new ArrayList<>();
 public static ValueAnimator ofFloat(float...f){ValueAnimator v=new ValueAnimator();v.from=f[0];v.to=f[1];v.value=v.from;return v;}
 public void setDuration(long d){}public void setInterpolator(Object i){}public void addUpdateListener(Consumer<ValueAnimator>u){updates.add(u);}public void addListener(AnimatorListenerAdapter l){listeners.add(l);}
 public Object getAnimatedValue(){return value;}public void tick(float p){value=from+(to-from)*p;for(var u:updates)u.accept(this);}public void start(){tick(0f);}
 public void cancel(){for(var l:listeners)l.onAnimationEnd(this);}public void end(){tick(1f);for(var l:listeners)l.onAnimationEnd(this);}
}''',
'android/view/animation/PathInterpolator.java':'package android.view.animation;public class PathInterpolator {public PathInterpolator(float a,float b,float c,float d){}}',
'android/view/SurfaceControl.java':r'''package android.view;
import java.util.*;public class SurfaceControl {public boolean valid=true,visible=true;public float alpha=1;public boolean isValid(){return valid;}
 public static class Transaction {List<Runnable>ops=new ArrayList<>();public Transaction setAlpha(Object o,float a){ops.add(()->((SurfaceControl)o).alpha=a);return this;}public Transaction show(Object o){ops.add(()->((SurfaceControl)o).visible=true);return this;}public Transaction hide(Object o){ops.add(()->((SurfaceControl)o).visible=false);return this;}public void apply(){ops.forEach(Runnable::run);}public void close(){}}
}''',
'de/robv/android/xposed/XposedHelpers.java':r'''package de.robv.android.xposed;
public class XposedHelpers {public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new IllegalStateException(e);}}}''',
'com/jianguo/mistep/WorkspaceRegression.java':r'''package com.jianguo.mistep;
import java.util.*;import android.view.SurfaceControl;import de.robv.android.xposed.XposedHelpers;
public class WorkspaceRegression {
 static class LiveSwapVisualLease {static void writeAlpha(View v,float a){v.setAlpha(a);}}
 static class SideDismiss {boolean isTracking(){return false;}void settle(boolean b){}}
 SideDismiss sideDismiss;
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static class View {static final int VISIBLE=0,GONE=8;float alpha=1;void setAlpha(float v){alpha=v;}}
 static class Observer {Runnable frame;void registerFrameCommitCallback(Runnable r){frame=r;}}
 static class FrameLayout extends View {Observer observer=new Observer();boolean attached=true,hardware=true;int invalidates;
  boolean isAttachedToWindow(){return attached;}boolean isHardwareAccelerated(){return hardware;}Observer getViewTreeObserver(){return observer;}void invalidate(){invalidates++;}void draw(){Runnable r=observer.frame;observer.frame=null;if(r!=null)r.run();}}
 static class Queue {List<Runnable>q=new ArrayList<>();void post(Runnable r){q.add(r);}void postDelayed(Runnable r,long d){q.add(r);}void flush(){while(!q.isEmpty())q.remove(0).run();}}
 static class Controller {SurfaceControl surface=new SurfaceControl();Object token=new Object();}
 static class Slot {int taskId=1;Object taskToken;boolean occupied=true,parked,promoting,launching;Controller controller=new Controller();View hint=new View(),view=new View();Slot(){taskToken=controller.token;}}
 static class Bitmap {void recycle(){}}
 static class StatusBar {void restore(){}}
 static class Clipboard {void close(){}}static class MiStepClipboard {static Clipboard get(Object c){return new Clipboard();}}
 static class SystemClock {static long uptimeMillis(){return 10000;}}
 static class Promotion {boolean nativeSwap,cancelled;}
 static final int ACTIVE=1,RESUMING=2,SUSPENDING=3,SUSPENDED=4,SLOT_COUNT=4,SLOT_MAIN=0;static final long ZOOM_ANIM_MS=300;
 FrameLayout root=new FrameLayout();View topBar=new View(),topStripBacking=new View();View[]sideBackingViews={null,new View(),new View(),new View()};Slot[]slots={new Slot(),new Slot(),new Slot(),new Slot()};
 static class CellMotion{void finish(){}}
 Queue main=new Queue();Object ctx=new Object();CellMotion cellMotion;StatusBar statusBar=new StatusBar();Promotion promotion;Bitmap mainBackdrop,wallpaperBackdrop=new Bitmap();
  boolean screenOn=true,zoomed=true,suspendRequested,landscapeSuspended,transferBusy,workspaceEntering;int scene=ACTIVE,zoomWrites,parks,returns,ready;long sceneEpoch=1,workspaceGeneration,zoomAnimUntil;
 android.animation.ValueAnimator workspaceAnimator;float workspaceAlpha=1f;
 void writeZoomAnimated(boolean on){zoomWrites++;}void writeZoom(boolean on){zoomWrites++;}void setZoomState(boolean on){zoomed=on;}void scheduleMainCropClear(long d){}void publishDesktopSideTasks(){}
 void closeSwapPreview(){}void closeCellMovePreview(){}void restoreCarrierToMain(){}void hideIme(){}void log(String s){}void failPromotion(Promotion p,String s){}
 void returnUnboundOwnedCandidates(long epoch,Runnable r){returns++;r.run();}void parkNext(int i){parks++;}
 Object getSurfaceField(Object o,String n){return o==null?null:((Controller)o).surface;}
 boolean controllerOwns(Slot s,int id,Object token){return s.taskId==id&&s.controller.token==token;}
 void closeSurfaceObject(Object t,String n){if(t!=null)((SurfaceControl.Transaction)t).close();}
 __METHODS__
 public static void main(String[]args){
  var h=new WorkspaceRegression();h.beginWorkspaceEntry(()->h.ready++);
  check(h.workspaceAlpha==0&&h.topBar.alpha==0&&h.slots[1].controller.surface.alpha==0,"entry hides controls and actual task parents before first buffer");
  check(h.zoomWrites==0&&h.root.observer.frame!=null,"main zoom waits for committed Host buffer");h.root.draw();h.main.flush();
  check(h.zoomWrites==1&&h.workspaceAnimator!=null,"one real main zoom starts after frame");h.workspaceAnimator.tick(.5f);
  check(h.workspaceAlpha==.5f&&h.slots[2].controller.surface.alpha==.5f,"actual side containers fade with Host controls");h.workspaceAnimator.end();
  check(h.workspaceAlpha==1&&h.ready==1,"entry continuation follows end frame");
  h=new WorkspaceRegression();h.beginWorkspaceEntry(null);h.scene=SUSPENDING;h.root.draw();h.main.flush();check(h.zoomWrites==0,"stale entry cannot shrink a closing workspace");
  h=new WorkspaceRegression();h.wallpaperBackdrop=null;h.beginWorkspaceEntry(null);check(h.root.observer.frame==null&&!h.main.q.isEmpty(),"entry waits for wallpaper instead of showing a black surround");h.wallpaperBackdrop=new Bitmap();h.main.flush();check(h.root.observer.frame!=null,"wallpaper readiness schedules real frame gate");
  h=new WorkspaceRegression();h.suspendSession("test");check(h.scene==SUSPENDING&&h.zoomWrites==1&&h.returns==0,"exit does not hand tasks back during real zoom");
  Object owner=h.slots[1].taskToken;h.workspaceAnimator.tick(.5f);check(h.slots[1].controller.surface.alpha==.5f&&h.slots[1].taskToken==owner,"exit fades real content without changing owner");
  h.workspaceAnimator.end();check(h.returns==1&&h.parks==1&&h.workspaceAlpha==0,"task handback starts only after fade finishes");
  h=new WorkspaceRegression();h.playWorkspaceFade(true,0,()->{throw new AssertionError("stale entry completed");});var old=h.workspaceAnimator;old.tick(.4f);h.workspaceGeneration++;h.playWorkspaceFade(false,1,null);float beforeLate=h.workspaceAlpha;old.tick(.8f);old.end();check(h.workspaceAlpha==beforeLate,"cancelled animator cannot alter new exit");
  h.slots[1].occupied=false;h.updateCellSurfaceVisibility(h.slots[1]);check(!h.slots[1].controller.surface.visible,"empty TaskView cannot leave its old resize background visible");
  h.slots[1].occupied=true;h.updateCellSurfaceVisibility(h.slots[1]);check(h.slots[1].controller.surface.visible,"same live binding reveals the actual container");
  h.slots[1].promoting=true;h.slots[1].occupied=false;h.updateCellSurfaceVisibility(h.slots[1]);check(h.slots[1].controller.surface.visible,"Shell owns container visibility during a role transition");
  h=new WorkspaceRegression();h.scene=RESUMING;h.beginWorkspaceEntry(null);h.suspendSession("exit-before-first-frame");
  check(h.scene==SUSPENDING&&!h.workspaceEntering,"exit during pending resume frame cannot strand RESUMING");h.root.draw();h.main.flush();check(h.zoomWrites==1,"late entry frame cannot restart zoom after exit");h.workspaceAnimator.end();check(h.parks==1,"pending-entry exit returns tasks");
  h=new WorkspaceRegression();h.scene=RESUMING;h.beginWorkspaceEntry(null);h.screenOn=false;h.suspendSession("screen-lock");
  check(h.scene==SUSPENDING&&h.parks==1&&h.workspaceAnimator==null,"screen lock restores immediately without waiting for hidden display frames");h.root.draw();h.main.flush();check(h.zoomWrites==1&&!h.zoomed,"late unlocked entry never re-enables zoom");
  System.out.println("actual workspace entry/exit/real-surface/late-frame checks="+checks+" passed");
 }
}'''.replace('__METHODS__',methods)
}
# Java closures in the fixture must capture an immutable owner, just as production does.
files['com/jianguo/mistep/WorkspaceRegression.java']=files['com/jianguo/mistep/WorkspaceRegression.java'].replace('h.beginWorkspaceEntry(()->h.ready++);','WorkspaceRegression initial=h;h.beginWorkspaceEntry(()->initial.ready++);')
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out)]+[str(out/n)for n in files],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.WorkspaceRegression'],check=True)
