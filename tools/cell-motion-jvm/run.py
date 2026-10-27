import os
"""Run real View-motion and transition-input classes against deterministic Android frame queues."""
from pathlib import Path
import subprocess, sys
root=Path(__file__).resolve().parents[2]
src=root/'module/src/main/java/com/jianguo/mistep'
out=Path(sys.argv[1]); out.mkdir(parents=True,exist_ok=True)
files={
'android/view/MotionEvent.java': r"""package android.view;public class MotionEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;}""",
'android/view/ViewTreeObserver.java': r"""package android.view;import java.util.*;public class ViewTreeObserver {public boolean fail;public List<Runnable>callbacks=new ArrayList<>();public void registerFrameCommitCallback(Runnable r){if(fail)throw new IllegalStateException("observer");callbacks.add(r);}public void commit(){var copy=new ArrayList<>(callbacks);callbacks.clear();copy.forEach(Runnable::run);}}""",
'android/view/View.java': r"""package android.view;import java.util.*;public class View {
 public boolean attached=true,hardware=true;public float x,y,tx,ty,holeX,holeY,surfaceX,surfaceY;public Object task=new Object();public int invalidates;public ViewTreeObserver observer=new ViewTreeObserver();public List<View>children=new ArrayList<>();public List<Runnable>posts=new ArrayList<>();
 public interface OnAttachStateChangeListener{void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}public List<OnAttachStateChangeListener>listeners=new ArrayList<>();
 public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.add(l);}public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.remove(l);}public void detach(){attached=false;for(var l:new ArrayList<>(listeners))l.onViewDetachedFromWindow(this);}
 public List<Runnable>deadlines=new ArrayList<>();public boolean postDelayed(Runnable r,long ms){deadlines.add(r);return true;}public void removeCallbacks(Runnable r){deadlines.remove(r);}public void expire(){for(var r:new ArrayList<>(deadlines))r.run();}public boolean post(Runnable r){posts.add(r);return true;}public void flush(){while(!posts.isEmpty())posts.remove(0).run();}public boolean isAttachedToWindow(){return attached;}public boolean isHardwareAccelerated(){return hardware;}public ViewTreeObserver getViewTreeObserver(){return observer;}public void invalidate(){invalidates++;}
 public float getTranslationX(){return tx;}public float getTranslationY(){return ty;}public void setTranslationX(float f){tx=f;}public void setTranslationY(float f){ty=f;}public void setX(float f){tx=f-x;}public void setY(float f){ty=f-y;}
 public void draw(){holeX=surfaceX=x+tx;holeY=surfaceY=y+ty;for(var v:children)v.draw();observer.commit();flush();}
}""",
'android/animation/Animator.java': r"""package android.animation;public class Animator {}""",
'android/animation/AnimatorListenerAdapter.java': r"""package android.animation;public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}""",
'android/animation/ValueAnimator.java': r"""package android.animation;import java.util.*;public class ValueAnimator extends Animator {
 public interface AnimatorUpdateListener{void onAnimationUpdate(ValueAnimator v);}public static ValueAnimator latest;public static int starts;public float value;public long duration;public List<AnimatorUpdateListener>updates=new ArrayList<>();public List<AnimatorListenerAdapter>listeners=new ArrayList<>();
 public static ValueAnimator ofFloat(float...v){latest=new ValueAnimator();return latest;}public void setDuration(long d){duration=d;}public void setInterpolator(Object o){}public void addUpdateListener(AnimatorUpdateListener u){updates.add(u);}public void addListener(AnimatorListenerAdapter l){listeners.add(l);}public Object getAnimatedValue(){return value;}
 public void start(){starts++;frame(0);}public void frame(float f){value=f;for(var u:updates)u.onAnimationUpdate(this);}public void end(){frame(1);listeners.forEach(l->l.onAnimationEnd(this));}public void cancel(){listeners.forEach(l->l.onAnimationEnd(this));}
}""",
'android/view/animation/PathInterpolator.java': r"""package android.view.animation;public class PathInterpolator {public PathInterpolator(float a,float b,float c,float d){}}""",
'com/jianguo/mistep/CellMotionRegression.java': r"""package com.jianguo.mistep;
import java.util.*;import android.view.*;import android.animation.*;
public class CellMotionRegression {
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 View root=new View(),a=new View(),b=new View();boolean valid=true;int layouts,done;MiStepCellMotion run;
 CellMotionRegression(){a.x=4;a.y=300;b.x=4;b.y=500;root.children.add(a);root.children.add(b);root.draw();run=new MiStepCellMotion(root,List.of(new MiStepCellMotion.Move(a,4,300,4,100,()->valid),new MiStepCellMotion.Move(b,4,500,4,300,()->valid)),()->{layouts++;a.y=100;b.y=300;},()->done++,s->{});}
 void start(){run.start();root.flush();}
 public static void main(String[]args){
  var h=new CellMotionRegression();Object task=h.a.task;h.start();check(ValueAnimator.latest.duration==240&&h.layouts==0,"start retains source layout until motion ends");
  ValueAnimator.latest.frame(.5f);check(h.a.ty==-100&&h.b.ty==-100,"both View RenderNodes move together");check(h.a.surfaceY==300,"surface changes wait for the native View frame");h.root.draw();
  check(h.a.holeY==200&&h.a.surfaceY==200&&h.b.holeY==400&&h.b.surfaceY==400,"each rendered hole matches its live surface");check(h.a.task==task,"no replacement of actual task ownership");
  ValueAnimator.latest.end();check(h.layouts==1&&h.a.ty==0&&h.b.ty==0&&h.done==0,"layout and transform reset before one final draw");h.root.draw();check(h.a.holeY==100&&h.a.surfaceY==100&&h.b.surfaceY==300&&h.done==1,"completion waits for final frame commit");h.run.finish();h.root.flush();h.root.draw();check(h.done==1&&h.layouts==1,"duplicate finish is idempotent");
  h=new CellMotionRegression();int starts=ValueAnimator.starts;h.run.finish();h.run.start();h.root.flush();h.root.draw();check(ValueAnimator.starts==starts&&h.done==1,"cancel queued before start has no late animation");
  h=new CellMotionRegression();h.start();ValueAnimator.latest.frame(.4f);h.valid=false;Object replacement=new Object();h.a.task=replacement;ValueAnimator.latest.frame(.6f);h.root.draw();check(h.a.task==replacement&&h.a.ty==0&&h.done==1,"stale task binding only restores its owned View transform");
  h=new CellMotionRegression();h.start();ValueAnimator.latest.frame(.5f);h.a.tx=99;h.run.finish();h.root.flush();h.root.draw();check(h.a.tx==99&&h.a.ty==0,"external x transform preserved while owned y is restored");
  h=new CellMotionRegression();h.start();ValueAnimator.latest.frame(.3f);h.root.detach();h.root.flush();check(h.done==1&&h.a.ty==0&&h.root.listeners.isEmpty(),"detach stops motion and cannot strand final draw");
  h=new CellMotionRegression();h.start();ValueAnimator.latest.end();h.root.detach();h.root.draw();check(h.done==1,"late commit after detach cannot complete twice");
  h=new CellMotionRegression();h.root.observer.fail=true;h.start();ValueAnimator.latest.end();check(h.done==1&&h.a.ty==0,"unavailable frame observer releases motion without leaked transform");
  View view=new View();List<Boolean>states=new ArrayList<>();int[] windows={0};var gate=new MiStepTransitionInput(view,()->windows[0]++,()->windows[0]--,states::add,s->{});
  gate.block();check(windows[0]==1,"separate input window covers borrowed children");check(states.equals(List.of(true))&&gate.consume(0),"animation owns DOWN before tasks are borrowed");gate.releaseAfterFrame();check(gate.consume(2)&&states.size()==1,"MOVE stays blocked before final draw");view.draw();check(windows[0]==0,"input window removed only after final frame");check(states.equals(List.of(true,false)),"real frame commit releases window input without millisecond cooldown");check(gate.consume(1)&&!gate.consume(0),"blocked gesture UP is consumed; next DOWN works immediately");
  gate.block();gate.releaseAfterFrame();gate.block();view.draw();check(windows[0]==1,"new transition retains the same input window");check(gate.consume(0),"old final-frame callback cannot release a newer transition");gate.releaseAfterFrame();view.draw();check(gate.consume(3)&&!gate.consume(0),"CANCEL ends old gesture after release");
  gate.block();gate.releaseAfterFrame();view.detach();view.draw();check(windows[0]==0,"detach removes input window exactly once");check(!gate.consume(0),"detach revokes pending input ownership and late frame is harmless");
  View owner=new View(),moving=new View();owner.children.add(moving);moving.setX(4);moving.setY(500);int[]completed={0};
  var realLayout=new MiStepCellMotion(owner,List.of(new MiStepCellMotion.Move(moving,4,500,4,100,()->true)),()->moving.setY(100),()->completed[0]++,s->{});
  realLayout.start();owner.flush();ValueAnimator.latest.frame(.5f);owner.draw();check(moving.surfaceY==300,"third-to-first midpoint follows View absolute position");
  ValueAnimator.latest.end();owner.draw();check(moving.surfaceY==100&&moving.ty==100&&completed[0]==1,"setY final layout must not be undone by restoring the old translation");
  var secondMove=new MiStepCellMotion(owner,List.of(new MiStepCellMotion.Move(moving,4,100,4,300,()->true)),()->moving.setY(300),()->{},s->{});
  secondMove.start();owner.flush();ValueAnimator.latest.frame(.5f);owner.draw();check(moving.surfaceY==200,"next compaction starts at committed position, without accumulated stale offset");ValueAnimator.latest.end();owner.draw();check(moving.surfaceY==300,"consecutive absolute setY commits retain the destination");
  View commit=new View();List<Boolean>handoffs=new ArrayList<>();MiStepCollectFrame.await(commit,()->true,handoffs::add);
  check(handoffs.isEmpty()&&commit.invalidates==1,"collect must retain real upper layer until Host empty hint removal commits");commit.draw();
  check(handoffs.equals(List.of(true))&&commit.deadlines.isEmpty()&&commit.listeners.isEmpty(),"committed handoff releases hooks/deadline");commit.expire();commit.draw();check(handoffs.size()==1,"handoff completed twice");
  View stale=new View();boolean[]current={true};List<Boolean>staleResult=new ArrayList<>();MiStepCollectFrame.await(stale,()->current[0],staleResult::add);current[0]=false;stale.draw();check(staleResult.equals(List.of(false)),"stale Host commit accepted");
  View detached=new View();List<Boolean>detachedResult=new ArrayList<>();MiStepCollectFrame.await(detached,()->true,detachedResult::add);detached.detach();detached.draw();check(detachedResult.equals(List.of(false)),"detached Host returned app below a missing window");
  View timeout=new View();List<Boolean>timeoutResult=new ArrayList<>();MiStepCollectFrame.await(timeout,()->true,timeoutResult::add);timeout.expire();timeout.draw();check(timeoutResult.equals(List.of(false)),"missing render acknowledgement stranded collect");
  View noGpu=new View();noGpu.hardware=false;List<Boolean>noGpuResult=new ArrayList<>();MiStepCollectFrame.await(noGpu,()->true,noGpuResult::add);check(noGpuResult.equals(List.of(false)),"software View accepted as committed render gate");
  System.out.println("actual View motion/frame/owner/gesture checks="+checks+" passed");
 }
}"""
}
for n,t in files.items():
 p=out/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(t,encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out)]+[str(out/n) for n in files]+[str(src/'MiStepCellMotion.java'),str(src/'MiStepTransitionInput.java'),str(src/'MiStepCollectFrame.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.CellMotionRegression'],check=True)
