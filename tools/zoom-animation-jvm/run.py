"""Replay the production WM animation start, shared clock and stale callbacks."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
source = (src / 'HostEntry.java').read_text(encoding='utf-8-sig')


def member(signature):
    a = source.index(signature)
    b = source.index('{', a) + 1
    depth = 1
    while depth:
        depth += (source[b] == '{') - (source[b] == '}')
        b += 1
    return source[a:b]


methods = '\n'.join(member(s) for s in (
    '    private void startZoomAnimation(', '    void cancelZoomAnimation('))
files = {
    'android/os/SystemClock.java': '''package android.os;
public class SystemClock {public static long now=1000;public static long uptimeMillis(){return now;}}''',
    'android/animation/Animator.java': 'package android.animation;public class Animator {}',
    'android/animation/AnimatorListenerAdapter.java': '''package android.animation;
public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}''',
    'android/animation/ValueAnimator.java': '''package android.animation;
import java.util.*;import java.util.function.*;
public class ValueAnimator extends Animator {
 public long delay,duration,seek;float value;List<Consumer<ValueAnimator>>updates=new ArrayList<>();
 List<AnimatorListenerAdapter>listeners=new ArrayList<>();
 public static ValueAnimator ofFloat(float...v){return new ValueAnimator();}
 public void setDuration(long d){duration=d;}public void setStartDelay(long d){delay=d;}
 public void setInterpolator(Object o){}public void addUpdateListener(Consumer<ValueAnimator> u){updates.add(u);}
 public void addListener(AnimatorListenerAdapter l){listeners.add(l);}public Object getAnimatedValue(){return value;}
 public void tick(float p){value=p;updates.forEach(u->u.accept(this));}public void start(){tick(0);}
 public void setCurrentPlayTime(long t){seek=t;tick((float)t/duration);}
 public void cancel(){listeners.forEach(l->l.onAnimationEnd(this));}
 public void end(){tick(1);listeners.forEach(l->l.onAnimationEnd(this));}
}''',
    'android/view/animation/PathInterpolator.java': '''package android.view.animation;
public class PathInterpolator {public PathInterpolator(float a,float b,float c,float d){}}''',
    'com/jiusis/mistep/ZoomAnimationRegression.java': r'''package com.jiusis.mistep;
import android.animation.*;import android.view.animation.PathInterpolator;import android.os.SystemClock;
public class ZoomAnimationRegression {
 static class Looper {static Object current=new Object();static Object myLooper(){return current;}}
 static class Handler {Object owner=Looper.current;Object getLooper(){return owner;}void post(Runnable r){r.run();}}
 static class Dispatch {int invalidations;void invalidateLastAttempt(){invalidations++;}}
 static class ZoomTarget {Handler handler=new Handler();Object globalLock=new Object();Dispatch dispatch=new Dispatch();}
 static ZoomTarget sZoomTarget;static String sZoomAnimKey;static ValueAnimator sZoomAnimator;
 static PathInterpolator zoomAnimInterpolator;static boolean sZoomFrameApply;
 static float sZoomScale=1,sZoomOffset,sZoomOffsetX;static int writes,checks;
 static void log(String s){}
 void applyDisplayZoomLocked(ZoomTarget t,float scale,float y,float x){
  check(Thread.holdsLock(t.globalLock),"real WM global lock retained");writes++;
  sZoomScale=scale;sZoomOffset=y;sZoomOffsetX=x;
 }
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 __METHODS__
 static ZoomTarget fresh(){sZoomTarget=new ZoomTarget();sZoomAnimator=null;sZoomAnimKey=null;
  sZoomScale=1;sZoomOffset=sZoomOffsetX=0;writes=0;SystemClock.now=1000;return sZoomTarget;}
 public static void main(String[]args){
  var h=new ZoomAnimationRegression();var t=fresh();
  var p=new ZoomAnimCommand.Plan(.7f,606,909,300,1032);
  h.startZoomAnimation(t,p,"entry");var first=sZoomAnimator;
  check(first.delay==32&&first.seek==0,"WM waits for the same future monotonic start as Host");
  h.startZoomAnimation(t,p,"entry");check(first==sZoomAnimator,"second transport does not restart the animation");
  first.tick(.5f);check(Math.abs(sZoomScale-.85f)<.001,"WM uses the actual main surface scale");
  h.startZoomAnimation(t,new ZoomAnimCommand.Plan(1,0,0,300,900),"exit");var exit=sZoomAnimator;
  check(exit.delay==0&&exit.seek==100&&Math.abs(sZoomScale-.9f)<.001,"late delivery catches up from the actual in-flight state");
  first.end();check(sZoomAnimator==exit&&Math.abs(sZoomScale-.9f)<.001,"cancelled entry cannot write old frames or finish over exit");
  exit.end();check(sZoomScale==1&&sZoomOffset==0&&sZoomOffsetX==0&&sZoomAnimator==null,"exit reaches real full-screen transform");
  t=fresh();h.startZoomAnimation(t,new ZoomAnimCommand.Plan(.7f,606,0,300),"phone");
  check(sZoomAnimator.delay==0&&sZoomAnimator.seek==0,"old phone command retains its original start behavior");
  t=fresh();h.startZoomAnimation(t,new ZoomAnimCommand.Plan(.7f,606,0,300,1),"late");
  check(sZoomAnimator.seek==300&&Math.abs(sZoomScale-.7f)<.001,"stale queue delivery cannot restart a full-duration animation");
  System.out.println("Actual WM animation shared-start/stale-callback checks="+checks+" passed");
 }
}'''.replace('__METHODS__', methods),
}
paths = []
for name, content in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf8')
    paths.append(str(path))
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                *paths, str(src/'ZoomAnimCommand.java'), str(src/'ZoomCommand.java')], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.ZoomAnimationRegression'], check=True)
