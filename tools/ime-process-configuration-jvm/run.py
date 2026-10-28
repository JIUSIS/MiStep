import os
"""Exercise the production process lease, hook, routing, and restoration together."""
from pathlib import Path
import ast, subprocess, sys
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'build/tests/ime-process-configuration-jvm'
JDK=Path(os.environ["MISTEP_JAVA_BIN"])
tree=ast.parse((ROOT/'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
files=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='FILES' for t in n.targets))
FILES={
'de/robv/android/xposed/XposedHelpers.java':files['de/robv/android/xposed/XposedHelpers.java'],
'android/content/res/Configuration.java':r'''package android.content.res;
public class Configuration {
 public int orientation,width,height,seq,densityDpi;
 public Configuration(){} public Configuration(Configuration c){orientation=c.orientation;width=c.width;height=c.height;seq=c.seq;densityDpi=c.densityDpi;}
 public int updateFrom(Configuration c){if(c.orientation!=0)orientation=c.orientation;if(c.width!=0)width=c.width;if(c.height!=0)height=c.height;if(c.densityDpi!=0)densityDpi=c.densityDpi;seq=c.seq;return 0;}
 public boolean equals(Object o){return o instanceof Configuration c&&orientation==c.orientation&&width==c.width&&height==c.height&&seq==c.seq&&densityDpi==c.densityDpi;}
}''',
'de/robv/android/xposed/XC_MethodHook.java':r'''package de.robv.android.xposed;
public class XC_MethodHook {protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}public static class MethodHookParam{public Object thisObject;public Object[] args;}}''',
'de/robv/android/xposed/XposedBridge.java':r'''package de.robv.android.xposed;
public class XposedBridge {static XC_MethodHook hook;public static Object hookAllMethods(Class<?> c,String n,XC_MethodHook h){hook=h;return null;}
 public static Object before(Object receiver,Object arg){XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=receiver;p.args=new Object[]{arg};try{hook.beforeHookedMethod(p);}catch(Throwable e){throw new RuntimeException(e);}return p.args[0];}}''',
'com/android/server/wm/WindowProcessController.java':r'''package com.android.server.wm;
import android.content.res.Configuration;import de.robv.android.xposed.XposedBridge;
public class WindowProcessController {
 public int mPid,mUid=10484,calls;public boolean mHasImeService=true,fail;public Configuration actual;public Parent parent=new Parent();
 public WindowProcessController(int id){mPid=id;}
 public static class Parent{public Configuration config=new Configuration();public Configuration getConfiguration(){return config;}}
 public Parent getParent(){return parent;}
 public void onConfigurationChanged(Configuration c){calls++;Configuration next=(Configuration)XposedBridge.before(this,c);if(fail){fail=false;throw new IllegalStateException("native failure");}actual=new Configuration(next);}
}''',
'com/jiusis/mistep/ImeProcessConfigurationTest.java':r'''package com.jiusis.mistep;
import android.content.res.Configuration;import com.android.server.wm.WindowProcessController;import java.util.*;
public class ImeProcessConfigurationTest {
 static int checks;static void check(boolean c,String why){checks++;if(!c)throw new AssertionError(why);}
 static class Attr{int type=2011;}static class Session{int mPid;}static class Token{State mFixedRotationTransformState=new State();}
 static class State{Configuration mRotatedOverrideConfiguration=cfg(2,2608,1200,20);}
 static class Window{Attr mAttrs=new Attr();Session mSession=new Session();Token mToken=new Token();Object mActivityRecord;Display display;Window(Display d,int p){display=d;mSession.mPid=p;}Object getDisplayContent(){return display;}Window getWindowState(){return this;}}
 static class MapOfProcesses{Map<Integer,WindowProcessController> values=new HashMap<>();Object getProcess(int pid){return values.get(pid);}}
 static class Atm{MapOfProcesses mProcessMap=new MapOfProcesses();}static class Root{Window current;Window getCurrentImeWindow(){return current;}}
 static class Wms{Atm mAtmService=new Atm();Root mRoot=new Root();}static class Display{Wms mWmService=new Wms();Window target;Window getImeInputTarget(){return target;}}
 static Configuration cfg(int o,int w,int h,int s){Configuration c=new Configuration();c.orientation=o;c.width=w;c.height=h;c.seq=s;return c;}
 public static void main(String[]args){
  MiStepImeProcessConfiguration owner=new MiStepImeProcessConfiguration();owner.install(ImeProcessConfigurationTest.class.getClassLoader());
  Display d=new Display();Object main=new Object();Window w=new Window(d,15359);d.mWmService.mRoot.current=w;d.target=new Window(d,2);d.target.mActivityRecord=main;
  WindowProcessController p=new WindowProcessController(15359),other=new WindowProcessController(1613);p.parent.config=cfg(1,1200,2608,7);p.parent.config.densityDpi=480;other.parent.config=p.parent.config;
  d.mWmService.mAtmService.mProcessMap.values.put(15359,p);d.mWmService.mAtmService.mProcessMap.values.put(1613,other);
  owner.update(w,d,main,s->{});check(p.actual.orientation==2,"IME process stayed portrait");check(p.actual.width==2608&&p.actual.height==1200,"bounds did not rotate");check(p.actual.seq==7,"global sequence overwritten");check(p.actual.densityDpi==480,"physical density lost");check(other.calls==0,"main package process changed");
  check(p.parent.config.orientation==1&&p.parent.config.width==1200,"parent configuration mutated");check(w.mToken.mFixedRotationTransformState.mRotatedOverrideConfiguration.seq==20,"rotation owner mutated");
  owner.update(w,d,main,s->{});check(p.calls==1,"same lease redispatched");
  Configuration fresh=cfg(1,1200,2608,8);fresh.densityDpi=500;p.parent.config=fresh;p.onConfigurationChanged(fresh);check(p.actual.orientation==2&&p.actual.seq==8&&p.actual.densityDpi==500,"global change escaped projection or lost new data");
  Window stale=new Window(d,1613);owner.update(stale,d,main,s->{});check(p.actual.orientation==2&&other.calls==0,"stale IME window displaced active lease");
  owner.update(w,new Display(),main,s->{});check(p.actual.orientation==2,"foreign display changed lease");
  d.target.mActivityRecord=new Object();owner.update(w,d,main,s->{});check(p.actual.orientation==1&&p.actual.width==1200&&p.actual.seq==8,"target change failed to restore current parent");
  d.target.mActivityRecord=main;owner.update(w,d,main,s->{});check(p.actual.orientation==2,"new lease failed");
  Window next=new Window(d,1613);d.mWmService.mRoot.current=next;owner.update(next,d,main,s->{});check(p.actual.orientation==1&&other.actual.orientation==2,"IME process switch not restored");
  owner.clear(s->{});check(other.actual.orientation==1,"exit restoration failed");int calls=other.calls;owner.clear(s->{});check(other.calls==calls,"cleanup not idempotent");
  next.mToken.mFixedRotationTransformState=null;owner.update(next,d,main,s->{});check(other.calls==calls,"unrotated IME was overridden");
  next.mToken.mFixedRotationTransformState=new State();other.mHasImeService=false;owner.update(next,d,main,s->{});check(other.calls==calls,"non IME process admitted");other.mHasImeService=true;other.mUid=1000;owner.update(next,d,main,s->{});check(other.calls==calls,"system process admitted");other.mUid=10484;
  other.fail=true;boolean failed=false;try{owner.update(next,d,main,s->{});}catch(RuntimeException e){failed=true;}check(failed&&other.actual.orientation==1,"failed projection did not restore parent");
  owner.update(next,d,main,s->{});check(other.actual.orientation==2,"failed lease stuck in cache");owner.clear(s->{});check(other.actual.orientation==1,"final restore failed");
  System.out.println("production IME process configuration/routing/restoration checks="+checks+" passed");
 }
}'''
}
helper=FILES['de/robv/android/xposed/XposedHelpers.java']
pos=helper.rfind('}')
FILES['de/robv/android/xposed/XposedHelpers.java']=helper[:pos]+r'''
 public static Class<?> findClass(String name,ClassLoader loader){try{return Class.forName(name,true,loader);}catch(Exception e){throw new RuntimeException(e);}}
 public static void setIntField(Object receiver,String name,int value){try{java.lang.reflect.Field f=receiver.getClass().getDeclaredField(name);f.setAccessible(true);f.setInt(receiver,value);}catch(Exception e){throw new RuntimeException(e);}}
'''+helper[pos:]
sources=[]
for name,body in FILES.items():
    p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');sources.append(str(p))
sources.append(str(ROOT/'module/src/main/java/com/jiusis/mistep/MiStepImeProcessConfiguration.java'))
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),*sources],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.ImeProcessConfigurationTest'],check=True)
