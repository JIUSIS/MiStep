"""Replay real TOP ownership and installed caption hooks; no native rendering claim."""
from pathlib import Path
import os, subprocess, sys

root=Path(__file__).resolve().parents[2]
src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
start=source.index('    static boolean ownsTopNativeCaption(')
end=source.index('{',start)+1;depth=1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
ownership=source[start:end]
files={
 'com/jiusis/mistep/MiStepHost.java':'''package com.jiusis.mistep;
import de.robv.android.xposed.XposedHelpers;
class MiStepHost {
 static final int ACTIVE=1,SLOT_TOP=4;static MiStepHost sInstance;
 Object root=new Object();int scene=ACTIVE;Slot[]slots={null,null,null,null,new Slot()};
 static class Slot {boolean occupied=true;int taskId=42;Object taskToken="upper";}
'''+ownership+'\n}',
 'android/view/MotionEvent.java':'''package android.view;
public class MotionEvent {public static final int ACTION_DOWN=0;public int getActionMasked(){return 0;}}''',
 'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;
public class XC_MethodHook {
 public static class MethodHookParam {public Object thisObject;public Object[]args;public Object result;public boolean returnEarly;public void setResult(Object x){result=x;returnEarly=true;}}
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
 protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
 public void invoke(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}
 public void invokeAfter(MethodHookParam p)throws Throwable{afterHookedMethod(p);}
}''',
 'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;
public class XposedBridge {public static java.util.List<String>logs=new java.util.ArrayList<>();public static XC_MethodHook constructors;public static void hookAllConstructors(Class<?>c,XC_MethodHook hook){constructors=hook;}public static void log(String s){logs.add(s);}}''',
 'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;
public class XposedHelpers {
 public static java.util.Map<String,XC_MethodHook>hooks=new java.util.HashMap<>();
 public static Class<?> findClass(String name,ClassLoader loader)throws Exception {if(name.endsWith(".MiuiDecorationController"))return Class.forName("com.jiusis.mistep.TopCaptionRegression$Controller");if(!name.endsWith(".MiuiDecorationDot"))throw new AssertionError(name);return Class.forName("com.jiusis.mistep.TopCaptionRegression$Dot");}
 public static void findAndHookMethod(Class<?> c,String name,Object... args)throws Exception {Class<?>[]types=new Class<?>[args.length-1];for(int i=0;i<types.length;i++)types[i]=(Class<?>)args[i];c.getDeclaredMethod(name,types);hooks.put(name,(XC_MethodHook)args[args.length-1]);}
 public static Object getObjectField(Object o,String name){for(Class<?>c=o.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){}catch(Exception e){throw new RuntimeException(e);}throw new IllegalArgumentException(name);}
 public static int getIntField(Object o,String name){return (Integer)getObjectField(o,name);}
 public static Object callMethod(Object o,String name,Object...args){try{for(var m:o.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new NoSuchMethodException(name);}catch(Exception e){throw new RuntimeException(e);}}
}''',
 'com/jiusis/mistep/TopCaptionRegression.java':'''package com.jiusis.mistep;
import android.view.MotionEvent;import de.robv.android.xposed.*;
public class TopCaptionRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static class Info {public int taskId=42,displayId=0,mode=6;public Object token="upper";public int getWindowingMode(){return mode;}}
 public static class CaptionBase {Object mRunningTaskInfo;}
 public static class Dot extends CaptionBase {Object mDecorationInsets=new Object();public boolean isTouchedInCaption(MotionEvent e){return true;}public void handleCaptionClicked(){}public boolean needCaption(){return true;}}
 public static class NativeExecutor {java.util.List<Runnable>queue=new java.util.ArrayList<>();public void execute(Runnable r){queue.add(r);}void drain(){var pending=new java.util.ArrayList<>(queue);queue.clear();for(var r:pending)r.run();}}
 public static class Controller extends CaptionBase {boolean mIsClosed;Dot mTopDecoration=new Dot();NativeExecutor mMainExecutor=new NativeExecutor();int relayouts;public void relayout(Object info){if(info!=null)throw new AssertionError("must use latest native info");relayouts++;mTopDecoration.mDecorationInsets=null;}}
 static Controller nativeController(Object info)throws Throwable {var c=new Controller();c.mRunningTaskInfo=info;var p=new XC_MethodHook.MethodHookParam();p.thisObject=c;XposedBridge.constructors.invokeAfter(p);return c;}
 static MiStepHost setup(){return MiStepHost.sInstance=new MiStepHost();}
 static void replay(Object info,boolean blocked,String why)throws Throwable {
  Dot dot=new Dot();dot.mRunningTaskInfo=info;
  XC_MethodHook.MethodHookParam hit=new XC_MethodHook.MethodHookParam();hit.thisObject=dot;hit.args=new Object[]{new MotionEvent()};
  XposedHelpers.hooks.get("isTouchedInCaption").invoke(hit);
  check(hit.returnEarly==blocked,why+": hit dispatch");
  check(!blocked || Boolean.FALSE.equals(hit.result),why+": native spy must see no TOP hit");
  XC_MethodHook.MethodHookParam click=new XC_MethodHook.MethodHookParam();click.thisObject=dot;click.args=new Object[0];
  XposedHelpers.hooks.get("handleCaptionClicked").invoke(click);
  check(click.returnEarly==blocked,why+": direct caption click");
  XC_MethodHook.MethodHookParam need=new XC_MethodHook.MethodHookParam();need.thisObject=dot;need.args=new Object[0];
  XposedHelpers.hooks.get("needCaption").invoke(need);
  check(need.returnEarly==blocked,why+": native caption decision");
  check(!blocked || Boolean.FALSE.equals(need.result),why+": no native caption surface or inset in TOP");
 }
 public static void main(String[]args)throws Throwable {
  MiStepTopNativeCaption.install(TopCaptionRegression.class.getClassLoader());
  check(XposedHelpers.hooks.size()==3,"exact OEM decision and input hooks installed");
  check(XposedBridge.constructors!=null,"native controller observation installed");
  check(XposedBridge.logs.stream().anyMatch(s->s.contains("scope installed")),"startup diagnostic missing");
  setup();replay(new Info(),true,"current TOP");
  for(int state=0;state<13;state++) {
   var h=setup();var i=new Info();Object candidate=i;
   switch(state){case 0:MiStepHost.sInstance=null;break;case 1:h.root=null;break;case 2:h.scene=2;break;
    case 3:h.slots[4].occupied=false;break;case 4:h.slots[4].taskId=-1;break;case 5:h.slots[4].taskToken=null;break;
    case 6:i.displayId=1;break;case 7:i.mode=1;break;case 8:i.mode=5;break;case 9:i.taskId=43;break;
    case 10:i.token="reused-id";break;case 11:candidate=null;break;case 12:candidate=new Object();break;}
   replay(candidate,false,"foreign/returned/invalid task "+state);
  }
  var h=setup();h.slots[4].taskToken="new-binding";
  replay(new Info(),false,"old binding after task reuse");
  var current=new Info();current.token="new-binding";replay(current,true,"current replacement binding");
  current.mode=1;replay(current,false,"native handback before slot callback");
  check(XposedBridge.logs.stream().anyMatch(s->s.contains("hit excluded")),"hit diagnostic missing");
  h=setup();h.slots[4].occupied=false;var early=new Info();var nativeEarly=nativeController(early);
  MiStepTopNativeCaption.refresh();check(nativeEarly.mMainExecutor.queue.isEmpty(),"unconfirmed admission may not change native decoration");
  h.slots[4].occupied=true;MiStepTopNativeCaption.refresh();MiStepTopNativeCaption.refresh();
  check(nativeEarly.mMainExecutor.queue.size()==2,"confirmed TOP requests native executor");
  nativeEarly.mMainExecutor.drain();check(nativeEarly.relayouts==1,"existing caption cleaned once; duplicate queued callback rejected");
  MiStepTopNativeCaption.refresh();check(nativeEarly.mMainExecutor.queue.isEmpty(),"clean caption does not cause a relayout loop");
  var stale=nativeController(new Info());MiStepTopNativeCaption.refresh();h.slots[4].taskToken="replacement";stale.mMainExecutor.drain();
  check(stale.relayouts==0,"stale token rejected at execution");
  h=setup();var returnedInfo=new Info();var returned=nativeController(returnedInfo);MiStepTopNativeCaption.refresh();returnedInfo.mode=1;returned.mMainExecutor.drain();
  check(returned.relayouts==0,"native handback before queued refresh preserved");
  var freeformInfo=new Info();freeformInfo.mode=5;var freeform=nativeController(freeformInfo);MiStepTopNativeCaption.refresh();
  check(freeform.mMainExecutor.queue.isEmpty(),"ordinary freeform preserved");
  var closed=nativeController(new Info());closed.mIsClosed=true;MiStepTopNativeCaption.refresh();
  check(closed.mMainExecutor.queue.isEmpty(),"closed native controller preserved");
  System.out.println("Production upper native caption ownership/hook checks="+checks+" passed (native status-bar test separate)");
 }
}'''
}
paths=[]
for name,body in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'MiStepTopNativeCaption.java'))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TopCaptionRegression'],check=True)
