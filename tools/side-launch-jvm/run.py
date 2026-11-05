"""Replay the actual Host launch method and inspect the native launch boundary."""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'build/tests/side-launch-jvm'
SOURCE_ROOT = Path(os.environ.get('MISTEP_LAUNCH_SOURCE_ROOT', ROOT))
text = (SOURCE_ROOT / 'module/src/main/java/com/jiusis/mistep/MiStepHost.java').read_text(encoding='utf-8')
start = text.index('    private void startSlot(final Slot slot, Intent launch, String label)')
end = text.index('    private static void callHidden(', start)
method = text[start:end]
OUT.mkdir(parents=True, exist_ok=True)
source = OUT / 'SideLaunchReplay.java'
source.write_text(r'''
import java.lang.reflect.Method;
import java.util.*;
public class SideLaunchReplay {
 static final int ACTIVE=1, LAUNCH_TIMEOUT_MS=4000;
 int scene=ACTIVE, screenW=1200, screenH=2608, resets, prepared;
 Object ctx=new Object(); Slot launchCurrentSlot; Handler main=new Handler();
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void log(String text){}
 boolean isActive(Slot slot){return slot!=null&&slot.index==1;}
 void resetIdleTaskView(Slot slot){resets++;}
 void prepareCellSurface(Slot slot){prepared++;}
 void finishPendingSuspend(){}
 static Method findStartActivity(Class<?> cls)throws Exception{
  return cls.getDeclaredMethod("startActivity",PendingIntent.class,Intent.class,ActivityOptions.class,Rect.class);
 }
 static void callHidden(Object target,String name,Object...args){
  ActivityOptions o=(ActivityOptions)target;
  if(name.equals("setLaunchWindowingMode"))o.mode=(Integer)args[0];
  if(name.equals("setRemoveWithTaskOrganizer"))o.remove=(Boolean)args[0];
 }
 static class Handler{Runnable timeout;void postDelayed(Runnable r,long ms){timeout=r;}}
 static class SystemClock{static long uptimeMillis(){return 1234;}}
 static class XposedBridge{static void log(Throwable t){throw new AssertionError(t);}}
 static class Rect{int left,top,right,bottom;Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}}
 static class Component{String getPackageName(){return "test.app";}}
 static class Intent {
  static final int FLAG_ACTIVITY_NEW_TASK=0x10000000;
  int flags;Component getComponent(){return new Component();}String getPackage(){return "test.app";}
  void addFlags(int f){flags|=f;}
 }
 static class ActivityOptions {
  static final int MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE=4;
  int creator,sender,mode;boolean remove=true;
  static ActivityOptions makeBasic(){return new ActivityOptions();}
  ActivityOptions setPendingIntentCreatorBackgroundActivityStartMode(int v){creator=v;return this;}
  ActivityOptions setPendingIntentBackgroundActivityStartMode(int v){sender=v;return this;}
  Map<String,Integer> toBundle(){return Map.of("creator",creator,"sender",sender);}
 }
 static class PendingIntent {
  static final int FLAG_IMMUTABLE=0x4000000;
  static int creations;int request,flags;Intent intent;Map<String,Integer> options;
  static PendingIntent getActivity(Object c,int request,Intent intent,int flags){
   return getActivity(c,request,intent,flags,Map.of("creator",0));
  }
  static PendingIntent getActivity(Object c,int request,Intent intent,int flags,Map<String,Integer> opts){
   creations++;PendingIntent pi=new PendingIntent();pi.request=request;pi.intent=intent;pi.flags=flags;pi.options=opts;return pi;
  }
 }
 static class NativeView {
  PendingIntent pi;Intent intent;ActivityOptions opts;Rect rect;int starts;
  public void startActivity(PendingIntent p,Intent i,ActivityOptions o,Rect r){starts++;pi=p;intent=i;opts=o;rect=r;}
 }
 static class Slot {
  int index=1,modeRepairAttempts;boolean promoting,launching=true,modeAdmissionPending;
  long modeGuardUntil;String pkg;NativeView view=new NativeView();
 }
''' + method + r'''
 public static void main(String[]args){
  SideLaunchReplay host=new SideLaunchReplay();Slot slot=new Slot();Intent intent=new Intent();
  host.startSlot(slot,intent,"test");NativeView v=slot.view;
  check(v.starts==1,"native launch boundary not reached");
  check(v.pi.options.get("creator")==4,"creator must grant only while visible");
  check(v.opts.sender==4,"sender must opt in only while visible");
  check(v.pi.flags==PendingIntent.FLAG_IMMUTABLE,"pending intent mutability changed");
  check(v.pi.request==1001&&v.pi.intent==intent&&v.intent==intent,"slot/intent identity changed");
  check(v.opts.mode==6&&!v.opts.remove,"TaskView ownership launch options changed");
  check(v.rect.left==0&&v.rect.top==0&&v.rect.right==1200&&v.rect.bottom==2608,"logical bounds changed");
  check((intent.flags&Intent.FLAG_ACTIVITY_NEW_TASK)!=0,"new task flag missing");
  check(slot.pkg.equals("test.app")&&slot.modeAdmissionPending,"launch admission missing");
  check(host.launchCurrentSlot==slot&&host.main.timeout!=null,"watchdog missing");
  host.main.timeout.run();check(!slot.launching&&host.launchCurrentSlot==null,"launch timeout leaks busy state");
  for(int gate=0;gate<3;gate++){
   SideLaunchReplay stopped=new SideLaunchReplay();Slot s=new Slot();int before=PendingIntent.creations;
   if(gate==0)stopped.scene=0;if(gate==1)s.promoting=true;if(gate==2)s.index=7;
   stopped.startSlot(s,new Intent(),"blocked");
   check(s.view.starts==0&&PendingIntent.creations==before,"inactive launch was authorized");
   check(!s.launching,"inactive launch remains busy");
  }
  System.out.println("actual Host side launch boundary checks="+checks+" passed; native BAL/device validation separate");
 }
}
''', encoding='utf-8')
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(OUT), str(source)], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(OUT), 'SideLaunchReplay'], check=True)
