import os
"""Run actual MiStepStatusBar/lease with controlled animator and draw callbacks."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jianguo/mistep'
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
files = {
'android/animation/Animator.java': 'package android.animation;public class Animator {}',
'android/animation/AnimatorListenerAdapter.java': 'package android.animation;public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}public void onAnimationCancel(Animator a){}}',
'android/animation/ValueAnimator.java': r'''package android.animation;import java.util.*;public class ValueAnimator extends Animator {
 public static List<ValueAnimator> all=new ArrayList<>();public float from,to,value;public long duration;public boolean ended,cancelled;
 public List<AnimatorUpdateListener>updates=new ArrayList<>();public List<AnimatorListenerAdapter>listeners=new ArrayList<>();
 public interface AnimatorUpdateListener {void onAnimationUpdate(ValueAnimator a);}
 public static ValueAnimator ofFloat(float...values){ValueAnimator a=new ValueAnimator();a.from=values[0];a.to=values[values.length-1];a.value=a.from;all.add(a);return a;}
 public ValueAnimator setDuration(long ms){duration=ms;return this;}public void setInterpolator(Object o){}public void addUpdateListener(AnimatorUpdateListener l){updates.add(l);}public void addListener(AnimatorListenerAdapter l){listeners.add(l);}
 public Object getAnimatedValue(){return value;}public void start(){step(0);}public void step(float fraction){value=from+(to-from)*fraction;for(var l:updates)l.onAnimationUpdate(this);}
 public void end(){step(1);ended=true;for(var l:listeners)l.onAnimationEnd(this);}public void cancel(){cancelled=true;for(var l:listeners)l.onAnimationCancel(this);for(var l:listeners)l.onAnimationEnd(this);}
 public static ValueAnimator latest(){return all.get(all.size()-1);}
}''',
'android/os/Looper.java': 'package android.os;public class Looper {static final Looper main=new Looper();public static Looper myLooper(){return main;}}',
'android/os/Handler.java': 'package android.os;import java.util.*;public class Handler {public List<Runnable>delayed=new ArrayList<>();public Looper getLooper(){return Looper.myLooper();}public boolean post(Runnable r){r.run();return true;}public void postDelayed(Runnable r,long d){delayed.add(r);}public void removeCallbacks(Runnable r){delayed.remove(r);}}',
'android/view/animation/LinearInterpolator.java': 'package android.view.animation;public class LinearInterpolator {}',
'android/view/Display.java': 'package android.view;public class Display {public static final int DEFAULT_DISPLAY=0;public int id;public int getDisplayId(){return id;}}',
'android/view/WindowManager.java': 'package android.view;public interface WindowManager {class LayoutParams {public static final int TYPE_STATUS_BAR=2000;public int type=TYPE_STATUS_BAR;}}',
'android/view/ViewTreeObserver.java': 'package android.view;public class ViewTreeObserver {public Runnable pending;public void registerFrameCommitCallback(Runnable r){pending=r;}public void commit(){Runnable r=pending;pending=null;if(r!=null)r.run();}}',
'android/view/View.java': r'''package android.view;import java.util.*;public class View {
 public float alpha=.63f;public int invalidates;public boolean attached=true,hardware=true;public Display display=new Display();public WindowManager.LayoutParams params=new WindowManager.LayoutParams();public ViewTreeObserver observer=new ViewTreeObserver();public List<Runnable>nextFrame=new ArrayList<>();
 public Object getLayoutParams(){return params;}public Display getDisplay(){return display;}public float getTransitionAlpha(){return alpha;}public void setTransitionAlpha(float a){alpha=a;}public void invalidate(){invalidates++;}
 public boolean isAttachedToWindow(){return attached;}public boolean isHardwareAccelerated(){return hardware;}public ViewTreeObserver getViewTreeObserver(){return observer;}
 public void postOnAnimation(Runnable r){nextFrame.add(r);}public void pulse(){var copy=new ArrayList<>(nextFrame);nextFrame.clear();for(var r:copy)r.run();}
}''',
'android/view/inspector/WindowInspector.java': 'package android.view.inspector;import android.view.View;import java.util.*;import java.util.function.Consumer;public class WindowInspector {public static List<View>views=new ArrayList<>();public static List<Consumer<List<View>>>listeners=new ArrayList<>();public static List<View>getGlobalWindowViews(){return views;}public static void addGlobalWindowViewsListener(java.util.concurrent.Executor e,Consumer<List<View>>l){listeners.add(l);}public static void removeGlobalWindowViewsListener(Consumer<List<View>>l){listeners.remove(l);}public static void notifyViews(){for(var l:new ArrayList<>(listeners))l.accept(views);}}',
'com/jianguo/mistep/StatusFadeRegression.java': r'''package com.jianguo.mistep;
import android.animation.*;import android.view.*;import android.view.inspector.*;import android.os.*;import java.util.*;
public class StatusFadeRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}static boolean near(float a,float b){return Math.abs(a-b)<.00001;}
 public static void main(String[]args){
  Handler handler=new Handler();List<String>errors=new ArrayList<>();MiStepStatusBar bar=new MiStepStatusBar(handler,message->{if(!message.startsWith("live status fade "))errors.add(message);});View view=new View();WindowInspector.views.add(view);Object originalParams=view.params;
  View foreign=new View();foreign.params.type=2038;View otherDisplay=new View();otherDisplay.display.id=2;WindowInspector.views.add(foreign);WindowInspector.views.add(otherDisplay);
  Object one=new Object(),two=new Object();int[]started={0};bar.hideFor(one,()->{});
  check(near(view.alpha,.63f),"hide begins from the live alpha instead of snapping to zero");
  ValueAnimator out=ValueAnimator.latest();check(out.duration==120,"bounded 120ms fade out");out.step(.5f);check(view.alpha>0&&view.alpha<.63f,"fade out has visible intermediate alpha");
  bar.runAfterHiddenFrame(one,()->started[0]++);check(view.observer.pending==null&&started[0]==0,"swap cannot pass draw gate while status bar is fading");
  out.end();check(view.alpha==0&&view.observer.pending!=null,"hidden draw is requested only after zero alpha");view.observer.commit();check(started[0]==0,"committed frame alone does not start swap");view.pulse();check(started[0]==1,"one composition opportunity starts the matching swap once");
  check(foreign.alpha==.63f&&otherDisplay.alpha==.63f&&view.params==originalParams,"unrelated windows, display and insets are untouched");
  bar.finish(one);check(view.alpha==0&&!bar.owns(one),"normal completion starts gradual reveal from hidden state");ValueAnimator in=ValueAnimator.latest();check(in.duration==180,"bounded 180ms fade in");in.step(.5f);check(view.alpha>0&&view.alpha<.63f,"fade in has visible intermediate alpha");in.end();check(near(view.alpha,.63f),"fade in restores exact original alpha");
  bar.hideFor(one,()->{});ValueAnimator.latest().end();bar.finish(one);ValueAnimator interrupted=ValueAnimator.latest();interrupted.step(.3f);float midpoint=view.alpha;
  bar.hideFor(two,()->{});ValueAnimator replacement=ValueAnimator.latest();check(near(view.alpha,midpoint)&&replacement!=interrupted,"next swap reverses current fade without a bright snap");interrupted.end();check(near(view.alpha,midpoint)&&bar.owns(two),"late old fade completion cannot reveal the next swap");
  replacement.end();bar.finish(two);ValueAnimator.latest().end();check(near(view.alpha,.63f),"rapid swap retains OEM alpha rather than saving an animation midpoint");
  bar.hideFor(one,()->{});ValueAnimator hide=ValueAnimator.latest();hide.step(.5f);bar.runAfterHiddenFrame(one,()->started[0]++);bar.restore();check(near(view.alpha,.63f)&&!bar.owns(one),"exit or lock restores immediately during fade out");hide.end();view.observer.commit();view.pulse();check(started[0]==1&&near(view.alpha,.63f),"late fade or draw after exit cannot start a swap or strand alpha");
  bar.hideFor(one,()->{});ValueAnimator.latest().end();bar.finish(one);ValueAnimator reveal=ValueAnimator.latest();reveal.step(.5f);bar.restore();check(near(view.alpha,.63f),"exit during fade in settles the original alpha immediately");reveal.end();check(near(view.alpha,.63f),"late reveal end after restore is inert");
  bar.hideFor(one,()->{});ValueAnimator old=ValueAnimator.latest();View rebuilt=new View();rebuilt.alpha=.4f;WindowInspector.views.add(rebuilt);WindowInspector.notifyViews();ValueAnimator rebuiltOut=ValueAnimator.latest();check(rebuiltOut!=old&&rebuilt.alpha==.4f,"rebuilt real status window joins the fade with its own alpha");old.end();rebuiltOut.end();bar.restore();check(near(view.alpha,.63f)&&near(rebuilt.alpha,.4f),"exit restores every status window independently");
  WindowInspector.views=new ArrayList<>(List.of(view));bar.hideFor(one,()->{});bar.runAfterHiddenFrame(one,()->started[0]++);ValueAnimator.latest().end();view.observer.commit();View late=new View();late.alpha=.4f;WindowInspector.views.add(late);WindowInspector.notifyViews();ValueAnimator lateOut=ValueAnimator.latest();view.pulse();check(started[0]==1,"root joining a pending hidden frame delays swap admission");lateOut.end();view.observer.commit();late.observer.commit();view.pulse();late.pulse();check(started[0]==2,"replacement root must fade and commit before admission");
  View during=new View();during.alpha=.2f;WindowInspector.views.add(during);WindowInspector.notifyViews();check(during.alpha==0,"root rebuilt after admission stays hidden throughout the live swap");bar.restore();check(near(view.alpha,.63f)&&near(late.alpha,.4f)&&near(during.alpha,.2f),"late roots still restore their own OEM alpha");
  WindowInspector.views=new ArrayList<>(List.of(view));bar.hideFor(one,()->{});bar.runAfterHiddenFrame(one,()->started[0]++);view.attached=false;ValueAnimator.latest().end();check(started[0]==3,"detached status root does not strand swap admission");bar.restore();view.attached=true;
  WindowInspector.views=new ArrayList<>(List.of(foreign));bar.hideFor(one,()->{});bar.runAfterHiddenFrame(one,()->started[0]++);check(started[0]==4,"no attached status window requires no invented bitmap or animation");bar.restore();
  check(WindowInspector.listeners.isEmpty()&&handler.delayed.isEmpty()&&errors.isEmpty(),"all listeners and watchdogs released without errors");
  System.out.println("actual live status fade checks="+checks+" passed");
 }
}'''
}
paths = []
for name, body in files.items():
    p = out / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(body, encoding='utf8')
    paths.append(str(p))
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                str(src / 'MiStepStatusBar.java'), str(src / 'SwapStatusBarLease.java'), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'com.jianguo.mistep.StatusFadeRegression'], check=True)
