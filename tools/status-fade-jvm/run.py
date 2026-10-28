import os
"""Run actual MiStepStatusBar/lease with controlled animator and draw callbacks."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = Path(sys.argv[2]) if len(sys.argv) > 2 else root / 'module/src/main/java/com/jiusis/mistep'
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
'android/os/Message.java': 'package android.os;public class Message {public int what,arg1,arg2;public Object obj;}',
'de/robv/android/xposed/XC_MethodHook.java': 'package de.robv.android.xposed;public class XC_MethodHook {public static class MethodHookParam {public Object[] args;} protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}}',
'de/robv/android/xposed/XposedHelpers.java': 'package de.robv.android.xposed;public class XposedHelpers {public static Object findAndHookMethod(String n,ClassLoader c,String m,Object...a){return null;}}',
'de/robv/android/xposed/XposedBridge.java': 'package de.robv.android.xposed;public class XposedBridge {public static void log(String s){}}',
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
'com/jiusis/mistep/StatusFadeRegression.java': r'''package com.jiusis.mistep;
import android.animation.*;import android.view.*;import android.view.inspector.*;import android.os.*;import java.util.*;
public class StatusFadeRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}static boolean near(float a,float b){return Math.abs(a-b)<.00001;}
 static void nativeState(int display,int window,int state){try{var m=MiStepStatusBar.class.getDeclaredMethod("nativeWindowStateChanged",int.class,int.class,int.class);m.setAccessible(true);m.invoke(null,display,window,state);}catch(NoSuchMethodException old201){/* Replay the real old restoration behavior. */}catch(Exception e){throw new RuntimeException(e);}}
 public static void main(String[]args){
  Handler handler=new Handler();List<String>errors=new ArrayList<>();MiStepStatusBar bar=new MiStepStatusBar(handler,message->{if(!message.startsWith("live status fade ")&&!message.startsWith("native status "))errors.add(message);});View view=new View();WindowInspector.views.add(view);Object originalParams=view.params;
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
  WindowInspector.views=new ArrayList<>(List.of(view));nativeState(0,1,0);bar.hideFor(one,()->{});ValueAnimator.latest().end();nativeState(0,1,2);int animators=ValueAnimator.all.size();bar.finish(one);
  check(view.alpha==0&&ValueAnimator.all.size()==animators&&!bar.owns(one),"immersive main must not fade the hidden native bar back into its hide animation");
  check(handler.delayed.isEmpty(),"native hidden drawing does not retain a swap deadline or input cooldown");
  nativeState(2,1,0);nativeState(0,2,0);check(view.alpha==0,"other display and navigation bar visibility cannot reveal the main status bar");
  View nativeRebuilt=new View();nativeRebuilt.alpha=.41f;WindowInspector.views.add(nativeRebuilt);WindowInspector.notifyViews();check(nativeRebuilt.alpha==0,"a rebuilt root stays hidden during the native hide handoff");
  nativeState(0,1,0);for(var a:new ArrayList<>(ValueAnimator.all))if(!a.cancelled&&!a.ended)a.end();check(near(view.alpha,.63f)&&near(nativeRebuilt.alpha,.41f),"native status show restores exact OEM alpha for original and rebuilt roots");WindowInspector.views=new ArrayList<>(List.of(view));
  bar.hideFor(one,()->{});ValueAnimator.latest().end();bar.finish(one);ValueAnimator lateReveal=ValueAnimator.latest();lateReveal.step(.2f);nativeState(0,1,2);check(view.alpha==0,"native hide arriving during reveal immediately suppresses that stale reveal");lateReveal.end();check(view.alpha==0,"late cancelled reveal cannot expose a hidden native bar");
  bar.hideFor(two,()->{});nativeState(0,1,0);check(view.alpha==0,"native show during next swap cannot bypass its hidden frame ownership");bar.finish(two);ValueAnimator.latest().end();check(near(view.alpha,.63f),"replacement swap preserves original alpha across native-hidden drawing");
  bar.hideFor(one,()->{});ValueAnimator.latest().end();nativeState(0,1,2);bar.finish(one);bar.restore();check(near(view.alpha,.63f),"exit restores a native-hidden deferred alpha immediately");nativeState(0,1,0);check(near(view.alpha,.63f),"late native show after exit leaves the restored bar unchanged");
  bar.hideFor(one,()->{});ValueAnimator.latest().end();nativeState(0,1,2);for(var timeout:new ArrayList<>(handler.delayed))timeout.run();check(!bar.owns(one)&&view.alpha==0,"watchdog ends a timed out swap while native drawing stays hidden");nativeState(0,1,0);ValueAnimator.latest().end();check(near(view.alpha,.63f),"native show must restore alpha even when the lease ended through watchdog cleanup");
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
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'com.jiusis.mistep.StatusFadeRegression'], check=True)
