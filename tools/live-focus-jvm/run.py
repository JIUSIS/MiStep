import os
"""Exercise the real Host live completion and main-focus methods with bounded queues."""
from pathlib import Path
import subprocess, sys
root = Path(__file__).resolve().parents[2]
src = root/'module/src/main/java/com/jianguo/mistep'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
source = (src/'MiStepHost.java').read_text(encoding='utf-8-sig')
def member(signature):
    start = source.index(signature); brace = source.index('{', start); end = brace+1; depth=1
    while depth:
        depth += (source[end]=='{')-(source[end]=='}'); end+=1
    return source[start:end]
methods = '\n'.join(member(s) for s in ('    private void finishLiveResult(',
            '    private PromotionFocusTransfer.Result requestLiveMainFocus(',
            '    private void finishPromotionFocus('))
files = {
'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long uptimeMillis(){return 1000;}}',
'android/app/ActivityTaskManager.java': 'package android.app;public class ActivityTaskManager {public static Object service;public static Object getService(){return service;}}',
'com/jianguo/mistep/LiveFocusRegression.java': r'''package com.jianguo.mistep;
import java.util.*;import android.os.SystemClock;
public class LiveFocusRegression {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static class MiStepLiveSwap {static class Result {boolean rolesConfirmed,sideOwned,succeeded;Result(boolean side,boolean roles){sideOwned=side;rolesConfirmed=roles;succeeded=true;}}}
 static class LiveTask {int id;Object token;LiveTask(int i,Object t){id=i;token=t;}}
 static class Promotion {int taskId=101;Object token="app";boolean nativeCollect,nativeHome,cancelled,liveMainFocusPrepared;PromotionFocusTransfer.Result liveMainFocusResult;LiveTask collectionHome;List<Object>otherSideTokens=new ArrayList<>();}
 static class Queue {List<Runnable> posts=new ArrayList<>();int delays;void post(Runnable r){posts.add(r);}void postDelayed(Runnable r,long delay){delays++;posts.add(r);}void flush(){while(!posts.isEmpty())posts.remove(0).run();}}
 static class XposedHelpers {
  static Object callStaticMethod(Class<?>c,String n,Object...args){try{return c.getMethod(n).invoke(null);}catch(Exception e){throw new RuntimeException(e);}}
  static Object callMethod(Object o,String n,Object...args){try{for(var m:o.getClass().getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(o,args);}throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}
 }
 static class Info {int taskId,displayId,userId,mode=1,type=1;Object token;boolean isVisible=true,isFocused;Info(int i,Object t){taskId=i;token=t;}}
 static class Service {List<Info>roots=new ArrayList<>();int requests;int last=-1;List<Info>getAllRootTaskInfos(){return roots;}void setFocusedTask(int id){requests++;last=id;}}
 Promotion promotion;long sceneEpoch=1;int scene=ACTIVE;static final int ACTIVE=0;boolean suspendRequested,screenOn=true;int shellCalls,completed;Queue main=new Queue(),shell=new Queue();
 boolean externalPromotionCurrent(Promotion t){return promotion==t&&!t.cancelled&&scene==ACTIVE&&!suspendRequested&&screenOn;}
 boolean ordinaryRoot(Object o,int mode){Info i=(Info)o;return i.mode==mode&&i.type==1;}boolean isHomeRoot(Object o){return ((Info)o).type==2;}int windowModeOf(Object o){return ((Info)o).mode;}int currentUserId(){return 0;}void log(String s){}
 boolean executeOnShell(Runnable r){shellCalls++;shell.post(r);return true;}
 void finishLiveCollect(Promotion t,MiStepLiveSwap.Result r){finishPromotionFocus(t,()->completed++,0);}void finishLiveHome(Promotion t,MiStepLiveSwap.Result r){finishPromotionFocus(t,()->completed++,0);}void finishLiveSwap(Promotion t,MiStepLiveSwap.Result r){finishPromotionFocus(t,()->completed++,0);}
 __METHODS__
 static LiveFocusRegression fixture(Service s){android.app.ActivityTaskManager.service=s;var h=new LiveFocusRegression();h.promotion=new Promotion();return h;}
 static void fast(LiveFocusRegression h,Service s,int expected){
  check(s.requests==expected&&h.completed==0,"focus request belongs to existing Shell completion before UI post");h.main.flush();
  check(h.completed==1&&h.shellCalls==0&&h.main.delays==0,"live final UI must not queue another Shell turn or 120ms poll");
 }
 public static void main(String[]args)throws Exception{
  var s=new Service();s.roots.add(new Info(101,"app"));var h=fixture(s);h.finishLiveResult(h.promotion,new MiStepLiveSwap.Result(true,true));fast(h,s,1);check(s.last==101&&h.promotion.liveMainFocusResult==PromotionFocusTransfer.Result.REQUESTED,"exact new main requested, not claimed observed");
  s=new Service();var i=new Info(101,"app");i.isFocused=true;s.roots.add(i);h=fixture(s);h.finishLiveResult(h.promotion,new MiStepLiveSwap.Result(true,true));fast(h,s,0);
  s=new Service();i=new Info(1,"home");i.type=2;s.roots.add(i);i=new Info(101,"app");i.mode=6;i.isFocused=true;s.roots.add(i);h=fixture(s);h.promotion.nativeCollect=true;h.promotion.collectionHome=new LiveTask(1,"home");h.finishLiveResult(h.promotion,new MiStepLiveSwap.Result(true,true));fast(h,s,1);check(s.last==1,"collected side cannot retain main focus");
  for(int mode:new int[]{1,5}){s=new Service();i=new Info(1,"home");i.type=2;s.roots.add(i);i=new Info(202,"new-main");i.mode=mode;i.isFocused=true;s.roots.add(i);h=fixture(s);h.promotion.nativeCollect=true;h.promotion.collectionHome=new LiveTask(1,"home");h.finishLiveResult(h.promotion,new MiStepLiveSwap.Result(true,true));fast(h,s,0);check(h.promotion.liveMainFocusResult==PromotionFocusTransfer.Result.UNAVAILABLE,"Home must not steal a competing app/native focus");}
  s=new Service();i=new Info(101,"app");s.roots.add(i);h=fixture(s);h.promotion.nativeHome=true;h.finishLiveResult(h.promotion,new MiStepLiveSwap.Result(false,true));fast(h,s,1);
  for(int kind=0;kind<4;kind++){s=new Service();i=new Info(101,"app");if(kind==0)i.token="replaced";if(kind==1)i.userId=10;if(kind==2)i.displayId=1;if(kind==3)i.isVisible=false;s.roots.add(i);h=fixture(s);h.finishLiveResult(h.promotion,new MiStepLiveSwap.Result(true,true));fast(h,s,0);}
  s=new Service();h=fixture(s);h.promotion.nativeCollect=true;h.finishLiveResult(h.promotion,new MiStepLiveSwap.Result(true,true));fast(h,s,0);
  s=new Service();s.roots.add(new Info(101,"app"));h=fixture(s);h.finishLiveResult(h.promotion,new MiStepLiveSwap.Result(true,false));check(!h.promotion.liveMainFocusPrepared&&s.requests==0,"unconfirmed native roles cannot request focus");
  h=fixture(s);h.promotion.cancelled=true;h.finishLiveResult(h.promotion,new MiStepLiveSwap.Result(true,true));check(!h.promotion.liveMainFocusPrepared&&s.requests==0,"cancelled scene cannot request focus");
  System.out.println("actual Host live focus/queue checks="+checks+" passed");
 }
}'''.replace('__METHODS__', methods)
}
paths=[]
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
paths += [str(src/'PromotionFocusTransfer.java'),str(root/'tests/PromotionFocusTransferTest.java')]
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
for name in ('PromotionFocusTransferTest','LiveFocusRegression'):
    subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.'+name],check=True)
