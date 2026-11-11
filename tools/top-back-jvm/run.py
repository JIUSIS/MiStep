"""Execute the real protected Back adapter, including stale and wrong-owner rejection."""
from pathlib import Path
import os
import subprocess
import sys

root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/Manifest.java':'package android;public class Manifest {public static class permission {public static final String WRITE_SECURE_SETTINGS="secure";}}',
'android/os/IBinder.java':'package android.os;public interface IBinder {}',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long now=1000;public static long uptimeMillis(){return now;}}',
'android/os/Bundle.java':'''package android.os;import java.util.*;public class Bundle {Map<String,Object> data=new HashMap<>();public void putBinder(String n,IBinder b){data.put(n,b);}public IBinder getBinder(String n){return (IBinder)data.get(n);}}''',
'android/content/Context.java':'''package android.content;public class Context {public static final String INPUT_SERVICE="input";public Object input;public Intent sent;public Object getSystemService(String name){return input;}public void sendBroadcast(Intent i,String permission){if(!permission.equals("secure"))throw new AssertionError();sent=i;}}''',
'android/content/Intent.java':'''package android.content;import java.util.*;import android.os.*;public class Intent {Map<String,Object>data=new HashMap<>();public String pkg;public Intent(String action){}public Intent setPackage(String p){pkg=p;return this;}public Intent putExtra(String n,Object o){data.put(n,o);return this;}public Bundle getBundleExtra(String n){return (Bundle)data.get(n);}public long getLongExtra(String n,long d){return data.get(n)==null?d:(Long)data.get(n);}}''',
'android/view/InputDevice.java':'package android.view;public class InputDevice {public static final int SOURCE_KEYBOARD=257;}',
'android/view/KeyEvent.java':'''package android.view;public class KeyEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_BACK=4,FLAG_FROM_SYSTEM=8,FLAG_VIRTUAL_HARD_KEY=64;public int action,code,display=-1;public KeyEvent(long a,long b,int c,int d,int e,int f,int g,int h,int i,int j){action=c;code=d;}public void setDisplayId(int d){display=d;}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers {public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}}''',
'com/jiusis/mistep/MiStepImePolicy.java':'''package com.jiusis.mistep;import java.util.function.Predicate;class MiStepImePolicy {static boolean isFocusedTopWindow(Object w,Object d,Predicate<Object>lease){return w!=null&&((BackRegression.Window)w).focused&&lease.test(((BackRegression.Window)w).task);}}''',
'com/jiusis/mistep/MiStepDesktopLaunchBridge.java':'''package com.jiusis.mistep;class MiStepDesktopLaunchBridge {static boolean ownsTopTask(Object task){return ((BackRegression.Task)task).leased;}}''',
'com/jiusis/mistep/BackRegression.java':'''package com.jiusis.mistep;import android.os.*;import android.content.*;import android.view.*;import java.util.*;
public class BackRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static class Token {IBinder binder=new IBinder(){};public IBinder asBinder(){return binder;}}
 public static class Info {public Token token=new Token();}
 public static class Task {public boolean leased=true;Info info=new Info();public Info getTaskInfo(){return info;}}
 public static class Window {public boolean focused=true;public int mOwnerUid=10290;Task task=new Task();public Task getTask(){return task;}}
 public static class Display {public Window mCurrentFocus=new Window();}
 public static class Input {List<KeyEvent> events=new ArrayList<>();int uid;boolean accept=true,failDown;public boolean injectInputEvent(KeyEvent e,int mode){throw new AssertionError("untargeted injection forbidden");}public boolean injectInputEvent(KeyEvent e,int mode,int owner){check(mode==0,"native async mode");events.add(e);uid=owner;if(failDown&&e.action==0)throw new IllegalStateException("native down rejected");return accept;}}
 public static void main(String[]args){
  for(int gate=0;gate<8;gate++) {
   SystemClock.now=1000;var d=new Display();var c=new Context();var input=new Input();c.input=input;
   MiStepTopBack.request(c,d.mCurrentFocus.task.info.token);check(c.sent!=null&&"android".equals(c.sent.pkg),"request is scoped to system receiver");
   if(gate==1)SystemClock.now=1600;
   if(gate==2)d.mCurrentFocus.task.info.token=new Token();
   if(gate==3)d.mCurrentFocus.focused=false;
   if(gate==4)d.mCurrentFocus.task.leased=false;
   if(gate==5)d.mCurrentFocus.mOwnerUid=-1;
   if(gate==6)d.mCurrentFocus=null;
   if(gate==7)c.sent.putExtra("at",1100L);
   Runnable dispatch=MiStepTopBack.prepare(c,d,c.sent);
   if(gate!=0){check(dispatch==null,"stale/token/focus/lease/uid/null/future refused "+gate);continue;}
   check(input.events.isEmpty(),"no input injection while preparing under WM lock");dispatch.run();
   check(input.events.size()==2&&input.uid==10290,"Back targets only exact owner UID");
   check(input.events.get(0).action==0&&input.events.get(1).action==1&&input.events.get(1).code==4&&input.events.get(0).display==0,"paired native Back on display0");
   input.events.clear();SystemClock.now=1700;dispatch.run();check(input.events.isEmpty(),"queued stale dispatch rejected");
  }
   for(int failure=0;failure<2;failure++){
    SystemClock.now=1000;var d=new Display();var c=new Context();var input=new Input();c.input=input;
    input.accept=false;input.failDown=failure==1;MiStepTopBack.request(c,d.mCurrentFocus.task.info.token);
    Runnable dispatch=MiStepTopBack.prepare(c,d,c.sent);
    boolean threw=false;try{dispatch.run();}catch(RuntimeException expected){threw=true;}
    check(threw==(failure==1),"native exception remains visible");
    check(input.events.size()==2&&input.events.get(1).action==1&&input.uid==10290,"rejected native down still releases exact target");
   }
   System.out.println("actual protected TOP Back checks="+checks+" passed");
 }
}'''
}
paths=[]
for n,t in files.items():
 p=out/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(t,encoding='utf-8');paths.append(str(p))
paths.append(str(root/'module/src/main/java/com/jiusis/mistep/MiStepTopBack.java'))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.BackRegression'],check=True)
