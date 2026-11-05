"""Exercise the real gesture/animation clock, including interrupted and detached live faces."""
import os, shutil, subprocess, sys
from pathlib import Path
root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=root/'module/src/main/java/com/jiusis/mistep'
for name in ('MiStepTopRoll.java','MiStepTopTaskTransition.java'):
    text=(source/name).read_text(encoding='utf-8-sig')
    for forbidden in ('import android.graphics.Bitmap','drawBitmap(', 'ImageView', 'SwapPreview.capture'):
        assert forbidden not in text, (name,forbidden)
host=(source/'MiStepHost.java').read_text(encoding='utf-8-sig')
for forbidden in ('SwapPreview.capture(', 'SwapPreview.captureRegion(', 'new SwapPreview(', 'new CellMovePreview('):
    assert forbidden not in host, forbidden
files={
'android/content/Context.java':'package android.content; public class Context {}',
'android/view/View.java':'''package android.view; public class View {
 public static final int INVISIBLE=4,IMPORTANT_FOR_ACCESSIBILITY_NO=2; int height=600;
 public View(android.content.Context c){} public void setVisibility(int v){}
 public void setImportantForAccessibility(int i){} public int getHeight(){return height;}
 protected void onDetachedFromWindow(){} protected void onSizeChanged(int w,int h,int ow,int oh){height=h;}
}''',
'android/view/animation/DecelerateInterpolator.java':'package android.view.animation; public class DecelerateInterpolator {public DecelerateInterpolator(float f){}}',
'android/animation/Animator.java':'package android.animation; public class Animator {}',
'android/animation/AnimatorListenerAdapter.java':'package android.animation; public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}',
'android/animation/ValueAnimator.java':'''package android.animation; import java.util.*; import java.util.function.Consumer;
public class ValueAnimator extends Animator {
 public static ValueAnimator latest; float start,end,value;public boolean running;
 List<Consumer<ValueAnimator>> updates=new ArrayList<>();List<AnimatorListenerAdapter> listeners=new ArrayList<>();
 public static ValueAnimator ofFloat(float a,float b){ValueAnimator v=new ValueAnimator();v.start=a;v.end=b;v.value=a;latest=v;return v;}
 public void setDuration(long d){} public void setInterpolator(Object i){}
 public void addUpdateListener(Consumer<ValueAnimator> l){updates.add(l);} public Object getAnimatedValue(){return value;}
 public void addListener(AnimatorListenerAdapter l){listeners.add(l);}public void start(){running=true;}
 public void pulse(float f){value=start+(end-start)*f;for(var l:updates)l.accept(this);}
 public void end(){pulse(1);running=false;for(var l:listeners)l.onAnimationEnd(this);}
 public void cancel(){running=false;for(var l:listeners)l.onAnimationEnd(this);}
}''',
'com/jiusis/mistep/LiveRollRegression.java':'''package com.jiusis.mistep;
import java.util.*;import android.animation.ValueAnimator;
public class LiveRollRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static boolean near(float a,float b){return Math.abs(a-b)<.001f;}
 public static void main(String[]args){
  List<TopRollMotion.Frame> frames=new ArrayList<>();List<Boolean> settled=new ArrayList<>();
  MiStepTopRoll roll=new MiStepTopRoll(new android.content.Context(),settled::add,frames::add);
  roll.reset(false);check(!roll.rolling()&&!roll.showingTask(),"starts on controls");
  roll.beginGesture();roll.moveGesture(-300,10);var f=frames.get(frames.size()-1);
  check(roll.rolling()&&f.taskScale>.5f&&f.frontScale>.5f,"both original faces move during a real drag");
  roll.finishGesture(true);ValueAnimator.latest.end();f=frames.get(frames.size()-1);
  check(!roll.rolling()&&!roll.showingTask()&&near(f.frontScale,1)&&near(f.taskScale,0),"cancel restores controls projection");
  check(settled.equals(Arrays.asList(false)),"one settled event after cancel");
  roll.show(true);ValueAnimator prior=ValueAnimator.latest;prior.pulse(.25f);
  check(roll.rolling()&&roll.showingTask()&&settled.size()==1,"programmatic roll keeps task live before ownership settles");
  roll.beginGesture();check(!prior.running&&roll.rolling()&&settled.size()==1,"interruption cancels old animator without stale settled callback");
  roll.moveGesture(-600,30);roll.finishGesture(false);ValueAnimator.latest.end();f=frames.get(frames.size()-1);
  check(roll.showingTask()&&!roll.rolling()&&near(f.taskScale,1)&&near(f.frontScale,0),"interrupted drag reaches task identity");
  check(settled.equals(Arrays.asList(false,true)),"only current gesture commits face");
  roll.beginGesture();roll.moveGesture(300,40);f=frames.get(frames.size()-1);
  check(f.taskScale>.5f&&f.frontScale>.5f,"downward return continues to project same task");
  roll.moveGesture(600,50);roll.finishGesture(false);ValueAnimator.latest.end();f=frames.get(frames.size()-1);
  check(!roll.showingTask()&&near(f.frontScale,1)&&near(f.taskScale,0),"return restores front without a placeholder face");
  roll.show(true);ValueAnimator.latest.pulse(.4f);int count=settled.size();roll.onDetachedFromWindow();
  check(!roll.rolling()&&settled.size()==count,"detachment releases animation without false handoff");
  f=frames.get(frames.size()-1);check(near(f.taskScale,1),"detachment resets original task transform to settled identity");
  roll.onSizeChanged(1080,622,1080,600);f=frames.get(frames.size()-1);
  check(near(f.taskTop,0)&&near(f.taskScale,1),"resize refreshes settled projection");
  System.out.println("Actual live roll clock checks="+checks+" passed; no screenshot animation callsites");
 }
}'''}
for name in ('TopRollMotion.java','MiStepTopRoll.java'):
    files['com/jiusis/mistep/'+name]=(source/name).read_text(encoding='utf-8-sig')
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*[str(out/p) for p in files]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.LiveRollRegression'],check=True)
