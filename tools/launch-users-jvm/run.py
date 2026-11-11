"""Exercise production Host quick-entry and desktop continuation with same-package users."""
from pathlib import Path
import os, subprocess, sys
root=Path(__file__).resolve().parents[2];src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
host=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
def member(sig):
    a=host.index(sig);b=host.index('{',a)+1;d=1
    while d:d+=(host[b]=='{')-(host[b]=='}');b+=1
    return host[a:b]
files={
'android/content/ComponentName.java':'package android.content;public record ComponentName(String pkg){public String getPackageName(){return pkg;}public String flattenToShortString(){return pkg;}}',
'android/content/Intent.java':'''package android.content;public class Intent {public static final String ACTION_MAIN="main",CATEGORY_LAUNCHER="launcher";public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_RESET_TASK_IF_NEEDED=2;public Intent(String a){}public Intent addCategory(String c){return this;}public Intent setComponent(ComponentName c){return this;}public Intent addFlags(int f){return this;}}''',
'android/content/Context.java':'package android.content;public class Context {public int launches;public void startActivity(Intent i){launches++;}}',
'android/provider/Settings.java':'package android.provider;public class Settings {public static final String ACTION_SETTINGS="settings";}',
'android/widget/Toast.java':'package android.widget;public class Toast {public static final int LENGTH_SHORT=0;public static Toast makeText(Object c,String s,int d){return new Toast();}public void show(){}}',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers {public static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}}''',
'com/jiusis/mistep/LaunchUsersRegression.java':'''package com.jiusis.mistep;
import android.content.*;import de.robv.android.xposed.XposedHelpers;
public class LaunchUsersRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static class Info {int userId;Info(int u){userId=u;}}static class Controller {Info mTaskInfo;Controller(int u){mTaskInfo=new Info(u);}}
 static class Slot {int index,taskId=42;boolean occupied=true,parked,frozen,owns=true,closing,carrier;Object taskToken=new Object();String pkg="app";Controller controller;Slot(int i,int u){index=i;controller=new Controller(u);}}
 static class QuickApp {ComponentName component=new ComponentName("app");String label="app";}
 static final int ACTIVE=1,SLOT_MAIN=0,SLOT_COUNT=3,SLOT_TOP=4;int scene=ACTIVE,tapped=-1,queued=-1,current;
 boolean transferBusy,taskCellsCovered;Object promotion;Context ctx=new Context();Slot[]slots={new Slot(0,0),new Slot(1,999),new Slot(2,0)};
 int currentUserId(){return current;}boolean supportsTaskUser(int u){return u==0||u==999;}
 boolean controllerOwns(Slot s,int id,Object token){return s.owns;}
 static class LiveTask{LiveTask(Slot s){}}
 static class XposedBridge{static void log(Throwable t){throw new AssertionError(t);}}
 void restoreCarrierToMain(){throw new AssertionError("unexpected carrier");}void finishPendingSuspend(){}
 void swapWithMain(int i){tapped=i;}void captureMainIntoEmptySlot(Slot s){tapped=s.index;}
 void adoptExisting(Slot s,LiveTask task,Runnable done,java.util.function.Consumer<String> failed){tapped=s.index;done.run();}
 void queueSidePromotion(Slot s,boolean desktop){queued=s.index;}void releaseTopTask(boolean front,boolean dismissed,Runnable after){if(!front||!dismissed)throw new AssertionError();tapped=SLOT_TOP;}void scheduleMainCropClear(long t){}static void log(String s){}
'''+member('private void onSlotTapped(')+member('private boolean sideTaskMatchesUser(')+member('private void launchQuick(')+member('private void openSettingsInMain(')+member('private void promoteSideTaskForDesktopLaunch(')+'''
 public static void main(String[]args){
  var h=new LaunchUsersRegression();h.launchQuick(new QuickApp());check(h.tapped==2&&h.ctx.launches==0,"owner shortcut selected clone or relaunched owner");
  h=new LaunchUsersRegression();h.slots[2].occupied=false;h.launchQuick(new QuickApp());check(h.tapped==-1&&h.ctx.launches==1,"clone captured owner shortcut");
  h=new LaunchUsersRegression();h.current=999;h.launchQuick(new QuickApp());check(h.tapped==1,"current profile shortcut lost identity");
  h=new LaunchUsersRegression();h.promoteSideTaskForDesktopLaunch("app",999);check(h.queued==1,"desktop clone selection mismatched");
  h=new LaunchUsersRegression();h.promoteSideTaskForDesktopLaunch("app",0);check(h.queued==2,"desktop owner selection mismatched");
  h=new LaunchUsersRegression();h.slots[2].index=SLOT_TOP;h.promoteSideTaskForDesktopLaunch("app",0);check(h.tapped==SLOT_TOP&&h.queued==-1,"upper task returns to main without lower-cell swap");
  h=new LaunchUsersRegression();h.promoteSideTaskForDesktopLaunch("app",10);check(h.queued==-1,"unassociated user was promoted");
  h=new LaunchUsersRegression();h.slots[2].controller.mTaskInfo.userId=999;h.promoteSideTaskForDesktopLaunch("app",0);check(h.queued==-1,"stale user drift captured owner");
  for(int kind=0;kind<5;kind++){h=new LaunchUsersRegression();h.slots[2].occupied=false;Slot s=h.slots[1];
   if(kind==0)s.parked=true;if(kind==1)s.frozen=true;if(kind==2)s.controller=null;if(kind==3)s.taskToken=null;if(kind==4)s.owns=false;
   h.promoteSideTaskForDesktopLaunch("app",999);check(h.queued==-1,"stale side binding admitted "+kind);}
  h=new LaunchUsersRegression();for(Slot s:h.slots)s.pkg="com.android.settings";h.openSettingsInMain();check(h.tapped==2,"settings entry selected wrong user");
  h=new LaunchUsersRegression();h.taskCellsCovered=true;h.launchQuick(new QuickApp());
  check(h.tapped==2&&h.ctx.launches==0,"covered top row still accepts an explicit quick-app command using its exact existing task");
  h=new LaunchUsersRegression();h.taskCellsCovered=true;for(Slot s:h.slots)s.pkg="com.android.settings";h.openSettingsInMain();
  check(h.tapped==2&&h.ctx.launches==0,"covered top row does not suppress the system-settings command");
  System.out.println("actual Host launch user checks="+checks+" passed");
 }
}'''
}
paths=[]
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths,str(src/'DesktopIconLaunchPolicy.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.LaunchUsersRegression'],check=True)
