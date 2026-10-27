import os
"""Compile actual notification hook and Host entry/continuation with bounded native stubs."""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[2];out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=root/'module/src/main/java/com/jianguo/mistep'
host=(source/'MiStepHost.java').read_text(encoding='utf-8-sig')
def method(sig):
    a=host.index(sig);b=host.index('{',a)+1;d=1
    while d:d+=(host[b]=='{')-(host[b]=='}');b+=1
    return host[a:b]
files={
'android/os/Looper.java':'''package android.os;public class Looper{public static final Looper MAIN=new Looper();public static boolean onMain=true;public static Looper myLooper(){return onMain?MAIN:null;}}''',
'android/os/UserHandle.java':'''package android.os;public class UserHandle{public int id;public UserHandle(int i){id=i;}public int getIdentifier(){return id;}}''',
'android/content/ComponentName.java':'''package android.content;public class ComponentName{String p;public ComponentName(String s){p=s;}public String getPackageName(){return p;}}''',
'android/content/Intent.java':'''package android.content;public class Intent{public ComponentName component;public Object extras;public ComponentName getComponent(){return component;}}''',
'android/content/pm/ActivityInfo.java':'''package android.content.pm;public class ActivityInfo{public String packageName;}''',
'android/content/pm/ResolveInfo.java':'''package android.content.pm;public class ResolveInfo{public ActivityInfo activityInfo;}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager{public ResolveInfo result;public ResolveInfo resolveActivityAsUser(android.content.Intent i,int f,int u){return result;}}''',
'android/content/Context.java':'''package android.content;public class Context{public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.content.pm.PackageManager getPackageManager(){return pm;}}''',
'android/app/PendingIntent.java':'''package android.app;public class PendingIntent{public boolean activity=true;public int user;public android.content.Intent intent;public boolean isActivity(){return activity;}public android.os.UserHandle getCreatorUserHandle(){return new android.os.UserHandle(user);}public android.content.Intent getIntent(){return intent;}}''',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook{public static class MethodHookParam{public Object[] args;public Object thisObject;public boolean stopped;public void setResult(Object o){stopped=true;}}protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}public void run(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;import java.lang.reflect.*;public class XposedBridge{public static XC_MethodHook hook;public static void hookMethod(Method m,XC_MethodHook h){hook=h;}public static Object invokeOriginalMethod(Method m,Object o,Object[] a)throws Throwable{return m.invoke(o,a);}public static void log(String m){}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;import java.lang.reflect.*;public class XposedHelpers{public static Class<?> findClass(String s,ClassLoader l)throws Exception{return Class.forName(s);}public static Object getObjectField(Object o,String s){try{Field f=o.getClass().getDeclaredField(s);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}public static int getIntField(Object o,String s){return(Integer)getObjectField(o,s);}public static Object callMethod(Object o,String s,Object...a){try{for(Method m:o.getClass().getMethods())if(m.getName().equals(s)&&m.getParameterCount()==a.length){m.setAccessible(true);return m.invoke(o,a);}throw new Exception(s);}catch(Exception e){throw new RuntimeException(e);}}}''',
'com/android/systemui/statusbar/phone/StatusBarNotificationActivityStarter.java':'''package com.android.systemui.statusbar.phone;public class StatusBarNotificationActivityStarter{public int count;public Object[] received;public void startNotificationIntentPublic(android.app.PendingIntent p,android.content.Intent f,Object entry,Object row,boolean a,boolean b,boolean c){count++;received=new Object[]{p,f,entry,row,a,b,c};}}''',
}
files['com/jianguo/mistep/MiStepNotificationLaunch.java']=(source/'MiStepNotificationLaunch.java').read_text(encoding='utf-8')
files['com/jianguo/mistep/MiStepHost.java']='''package com.jianguo.mistep;import android.os.Looper;import de.robv.android.xposed.XposedHelpers;import java.util.*;
class MiStepHost{
 static MiStepHost sInstance;static final int ACTIVE=1,SLOT_MAIN=0;int scene=ACTIVE;boolean screenOn=true,keyguardLocked,transferBusy,canStart=true;Promotion promotion;Slot[] slots={new Slot(0),new Slot(1),new Slot(2)};QueueMain main=new QueueMain();
 static class QueueMain{ArrayList<Runnable> q=new ArrayList<>();Looper getLooper(){return Looper.MAIN;}void post(Runnable r){q.add(r);}void drain(){for(Runnable r:new ArrayList<>(q))r.run();q.clear();}}
 static class Info{int taskId=42,userId=0;}static class Controller{Info mTaskInfo=new Info();}
 static class Slot{int index,windowMode=6,taskId=42;boolean occupied=true,parked,frozen,carrier;String pkg="settings";Object taskToken=new Object();Controller controller=new Controller();Slot(int i){index=i;if(i!=1)occupied=false;}}
 static class Promotion{Slot slot;boolean cancelled;int taskId=42;Runnable notificationLaunch;Promotion(Slot s){slot=s;}}
 int currentUserId(){return 0;}boolean mayWriteSideSurface(Slot s){return true;}boolean controllerOwns(Slot s,int id,Object t){return true;}void swapWithMain(int i){if(canStart)promotion=new Promotion(slots[i]);}static void log(String s){}
'''+method('static boolean promoteForNotification(')+'\n'+method('private void resumeNotificationLaunch(')+'''
 void finish(){resumeNotificationLaunch(promotion);resumeNotificationLaunch(promotion);main.drain();}
}'''
files['com/jianguo/mistep/NotificationRouteTest.java']='''package com.jianguo.mistep;
import android.app.*;import android.content.*;import android.content.pm.*;import android.os.*;import de.robv.android.xposed.*;import com.android.systemui.statusbar.phone.*;
public class NotificationRouteTest{
 static int checks;static Context ctx;static MiStepHost h;static StatusBarNotificationActivityStarter starter;static PendingIntent pending;static Intent fill;static Object entry,row;static XC_MethodHook.MethodHookParam args;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void reset(){ctx=new Context();h=new MiStepHost();MiStepHost.sInstance=h;Looper.onMain=true;starter=new StatusBarNotificationActivityStarter();pending=new PendingIntent();pending.intent=new Intent();pending.intent.component=new ComponentName("settings");fill=new Intent();fill.extras=new Object();entry=new Object();row=new Object();args=new XC_MethodHook.MethodHookParam();args.thisObject=starter;args.args=new Object[]{pending,fill,entry,row,true,false,true};}
 static void click()throws Throwable{MiStepNotificationLaunch.install(NotificationRouteTest.class.getClassLoader(),()->ctx);XposedBridge.hook.run(args);if(!args.stopped)starter.startNotificationIntentPublic(pending,fill,entry,row,true,false,true);}
 public static void main(String[]a)throws Throwable{
  reset();click();check(args.stopped&&starter.count==0&&h.promotion!=null,"defer until same-task native finish");Object originalPending=pending;args.args[0]=new PendingIntent();h.finish();check(starter.count==1,"one dispatch despite repeated finish");check(starter.received[0]==originalPending&&starter.received[1]==fill&&starter.received[2]==entry&&starter.received[3]==row,"original identity and deep-link extras retained");check(starter.received[4].equals(true)&&starter.received[5].equals(false)&&starter.received[6].equals(true),"OEM flags retained");
  reset();h.canStart=false;click();check(!args.stopped&&starter.count==1,"refused preparation preserves native click");
  reset();h.transferBusy=true;click();check(!args.stopped,"busy bypass");
  reset();pending.user=10;click();check(!args.stopped,"other profile bypass");
  reset();h.keyguardLocked=true;click();check(!args.stopped,"locked bypass");
  reset();h.slots[1].frozen=true;click();check(!args.stopped,"frozen bypass");
  reset();h.slots[2].occupied=true;click();check(!args.stopped,"ambiguous same-package tasks bypass");
  reset();h.slots[1].controller.mTaskInfo.taskId=99;click();check(!args.stopped,"stale controller identity bypass");
  reset();pending.activity=false;click();check(!args.stopped,"service/broadcast not intercepted");
  reset();pending.intent.component=null;click();check(!args.stopped,"unresolved target bypass");
  reset();pending.intent.component=null;ctx.pm.result=new ResolveInfo();ctx.pm.result.activityInfo=new ActivityInfo();ctx.pm.result.activityInfo.packageName="settings";click();check(args.stopped,"implicit activity resolves for same user");h.finish();check(starter.count==1,"implicit original dispatched");
  reset();Looper.onMain=false;click();check(!args.stopped,"background thread bypass");
  reset();ctx=null;click();check(!args.stopped,"missing context bypass");
  reset();pending.intent.component=new ComponentName("other");click();check(!args.stopped,"unrelated app bypass");
  System.out.println("Actual notification hook/Host route: "+checks+" checks passed");
 }
}'''
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(out),*[str(out/x) for x in files]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.NotificationRouteTest'],check=True)
restore=method('private void restoreLiveSwapVisual(');fail=method('private void failPromotion(')
assert restore.index('setLiveSwapInputBlocked(false)')<restore.index('resumeNotificationLaunch(ticket)')
assert fail.index('requestLiveSwapCancellation')<fail.index('resumeNotificationLaunch(ticket)')
assert fail.index('closeSwapPreview(ticket)')<fail.index('resumeNotificationLaunch(ticket)')
print('Native unwind before original dispatch:3 completion ordering checks passed')
