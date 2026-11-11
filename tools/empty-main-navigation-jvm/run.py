"""Replay actual Host navigation methods and native CommandQueue bridge."""
from pathlib import Path
import os, subprocess, sys

root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
src=root/'module/src/main/java/com/jiusis/mistep'
host=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
def method(signature):
    start=host.index(signature);brace=host.index('{',start);end=brace+1;depth=1
    while depth:
        if host[end]=='{':depth+=1
        elif host[end]=='}':depth-=1
        end+=1
    return host[start:end]
methods=method('    public static void onSystemNavigation(')+'\n'+method('    private boolean emptyMainHomeFocused(')
files={
'android/os/SystemClock.java':'package android.os;public class SystemClock{public static long now;public static long uptimeMillis(){return now;}}',
'android/os/Handler.java':'package android.os;public class Handler{public void post(Runnable r){r.run();}}',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;
public class XC_MethodHook {public static class MethodHookParam{public Object thisObject;public boolean error;public boolean hasThrowable(){return error;}}
 protected void afterHookedMethod(MethodHookParam p)throws Throwable{}public void after(MethodHookParam p)throws Throwable{afterHookedMethod(p);}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;public class XposedBridge{
 public static XC_MethodHook constructor;public static void log(String s){}
 public static void hookAllConstructors(Class<?>c,XC_MethodHook h){constructor=h;}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers{
 public static Class<?>findClass(String n,ClassLoader l){return com.jiusis.mistep.EmptyMainRegression.Queue.class;}
 public static boolean getBooleanField(Object o,String n){try{return o.getClass().getField(n).getBoolean(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...args){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}}''',
'com/jiusis/mistep/ShellCompat.java':'''package com.jiusis.mistep;class ShellCompat{static boolean hasMethod(Class<?>c,String n,int a){
 for(var m:c.getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a)return true;return false;}}''',
'com/jiusis/mistep/NativeLandscapePolicy.java':'''package com.jiusis.mistep;class NativeLandscapePolicy{
 static boolean shouldRestoreFromHome(boolean l,boolean s,String r){return l&&s&&"homekey".equals(r);}}''',
'com/jiusis/mistep/MiStepHost.java':'''package com.jiusis.mistep;
import android.os.*;import de.robv.android.xposed.XposedHelpers;import java.util.*;
class MiStepHost {
 static MiStepHost sInstance;static final int ACTIVE=0,SUSPENDED=2;int scene=ACTIVE,suspends,resumes;
 long sceneEpoch=7;boolean retainedForLock,landscapeSuspended,screenOn=true,keyguardLocked,suspendRequested,transferBusy;
 Object promotion,topTransition,currentMain;Handler main=new Handler();
 final MiStepNavigationPolicy.EmptyMainGesture emptyMainGesture=new MiStepNavigationPolicy.EmptyMainGesture();
 public static class Info{public boolean isVisible=true,isFocused=true,home=true;}
 List<Object>roots=new ArrayList<>(List.of(new Info()));boolean ambiguous;
 Object findMainTask(){if(ambiguous)throw new IllegalStateException();return currentMain;}
 List<Object>rootInfos(){return roots;}boolean isHomeRoot(Object o){return ((Info)o).home;}
 void suspendSession(String r){suspends++;scene=SUSPENDED;sceneEpoch++;}void resumeSession(){resumes++;scene=ACTIVE;}
 static void log(String s){}
''' + methods + '\n}',
'com/jiusis/mistep/EmptyMainRegression.java':'''package com.jiusis.mistep;
import android.os.SystemClock;import de.robv.android.xposed.*;
public class EmptyMainRegression {
 static int checks;static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
 public static class Queue{public int shows;public boolean fail;public void showRecentApps(boolean alt){if(fail)throw new IllegalStateException();if(alt)throw new AssertionError();shows++;}}
 static MiStepHost fresh(){var h=new MiStepHost();MiStepHost.sInstance=h;SystemClock.now=100;return h;}
 static void send(String s){MiStepHost.onSystemNavigation(s);}
 public static void main(String[]args)throws Throwable{
  check(!MiStepNativeRecents.show(),"missing native queue does not claim success");
  MiStepNativeRecents.install(EmptyMainRegression.class.getClassLoader());var q=new Queue();
  var p=new XC_MethodHook.MethodHookParam();p.thisObject=q;XposedBridge.constructor.after(p);
  var h=fresh();send("fs_gesture");SystemClock.now=600;send("homekey");
  check(q.shows==1&&h.suspends==0&&h.scene==MiStepHost.ACTIVE,"empty Home swipe requests native overview while retaining workspace");
  SystemClock.now=625;send("homekey");check(q.shows==1&&h.suspends==0,"native overview follow-up Home cannot exit workspace or request twice");
  SystemClock.now=1400;send("homekey");check(q.shows==1&&h.suspends==1,"explicit later Home still exits after bounded overview window");
  h=fresh();send("homekey");check(h.suspends==1&&q.shows==1,"explicit Home without fs gesture unchanged");
  h=fresh();h.currentMain=new Object();send("fs_gesture");h.currentMain=null;send("homekey");check(h.suspends==1&&q.shows==1,"app-origin Home never mistaken for empty-origin gesture");
  h=fresh();send("fs_gesture");h.currentMain=new Object();send("homekey");check(h.suspends==1&&q.shows==1,"new main app cancels empty gesture context");
  h=fresh();send("fs_gesture");SystemClock.now=2101;send("homekey");check(h.suspends==1&&q.shows==1,"expired gesture cannot open overview");
  h=fresh();send("fs_gesture");h.sceneEpoch++;send("homekey");check(h.suspends==1&&q.shows==1,"other workspace epoch rejects old gesture");
  h=fresh();send("fs_gesture");send("recentapps");send("homekey");check(h.suspends==1&&q.shows==1,"native overview completion clears pending Home conversion");
  for(int gate=0;gate<10;gate++){
   h=fresh();var i=(MiStepHost.Info)h.roots.get(0);
   if(gate==0)h.screenOn=false;if(gate==1)h.keyguardLocked=true;if(gate==2)h.suspendRequested=true;
   if(gate==3)h.promotion=new Object();if(gate==4)h.transferBusy=true;if(gate==5)h.topTransition=new Object();
   if(gate==6)i.isVisible=false;if(gate==7)i.isFocused=false;if(gate==8)i.home=false;if(gate==9)h.ambiguous=true;
   send("fs_gesture");send("homekey");check(q.shows==1,"unsafe/uncertain main context retains native policy "+gate);
  }
  h=fresh();h.landscapeSuspended=true;h.scene=MiStepHost.SUSPENDED;send("homekey");check(h.resumes==1,"existing native landscape Home restore retained");
  h=fresh();h.retainedForLock=true;send("fs_gesture");send("homekey");check(h.suspends==0&&q.shows==1,"lock-retained workspace untouched");
  h=fresh();q.fail=true;send("fs_gesture");send("homekey");check(h.suspends==0&&q.shows==1,"failed overview does not falsely exit empty workspace");q.fail=false;
  h=fresh();send("fs_gesture");SystemClock.now=99;send("homekey");check(h.suspends==1&&q.shows==1,"negative clock delta rejects stale gesture");
  System.out.println("actual empty-main navigation/overview checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,content in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');paths.append(str(p))
paths.extend(str(src/name) for name in ['MiStepNavigationPolicy.java','MiStepNativeRecents.java'])
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.EmptyMainRegression'],check=True)
