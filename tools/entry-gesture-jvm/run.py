"""Replay the real spy and native status observer; count pointer claims and openings."""
from pathlib import Path
import os, subprocess, sys

root = Path(__file__).resolve().parents[2]
src = Path(sys.argv[2]) if len(sys.argv) > 2 else root/'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([sys.executable, '-X', 'utf8', str(root/'tools/status-fade-jvm/run.py'), str(out), str(src)], check=True)

files = {
'android/graphics/Point.java': 'package android.graphics;public class Point {public int x,y;}',
'android/content/Context.java': '''package android.content;public class Context {
 public static final String WINDOW_SERVICE="window";public android.view.WindowManager wm=new android.view.WindowManager();
 public android.content.ContentResolver getContentResolver(){return new android.content.ContentResolver();}
 public void sendBroadcast(android.content.Intent i){}public Object getSystemService(String s){return wm;}public ClassLoader getClassLoader(){return getClass().getClassLoader();}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}}''',
'android/content/ContentResolver.java': 'package android.content;public class ContentResolver {}',
'android/content/Intent.java': 'package android.content;public class Intent {public Intent(String a){}public Intent setPackage(String s){return this;}public Intent putExtra(String k,int v){return this;}}',
'android/provider/Settings.java': '''package android.provider;public class Settings {public static class Global {
 public static int hide;public static boolean fail;public static int getInt(Object r,String key,int fallback){
 if(fail)throw new IllegalStateException();return hide;}}}''',
'android/content/res/Resources.java': 'package android.content.res;public class Resources {public android.util.DisplayMetrics getDisplayMetrics(){return new android.util.DisplayMetrics();}}',
'android/util/DisplayMetrics.java': 'package android.util;public class DisplayMetrics {public static float defaultDensity=1;public float density=defaultDensity;}',
'android/util/Log.java': 'package android.util;public class Log {public static int i(String t,String m){return 0;}}',
'android/os/Looper.java': 'package android.os;public class Looper {static final Looper main=new Looper();public static Looper myLooper(){return main;}public static Looper getMainLooper(){return main;}}',
'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long now;public static long uptimeMillis(){return now;}}',
'android/os/Handler.java': '''package android.os;import java.util.*;public class Handler {
 public static List<Runnable>pending=new ArrayList<>();public List<Runnable>delayed=new ArrayList<>();public Handler(){}public Handler(Looper l){}
 public Looper getLooper(){return Looper.myLooper();}public boolean post(Runnable r){r.run();return true;}
 public void postDelayed(Runnable r,long d){delayed.add(r);pending.add(r);}public void removeCallbacks(Runnable r){delayed.remove(r);pending.remove(r);}
 public static void drain(){var q=new ArrayList<>(pending);pending.clear();for(var r:q)r.run();}}''',
'android/view/Display.java': '''package android.view;public class Display {public static final int DEFAULT_DISPLAY=0;public int id;
 public int width=1080,height=2460,rotation;public int getDisplayId(){return id;}public int getRotation(){return rotation;}
 public static boolean cancelIfUnclaimed;public void getRealSize(android.graphics.Point p){
 if(cancelIfUnclaimed){cancelIfUnclaimed=false;var monitor=com.android.systemui.shared.system.InputMonitorCompat.latest;
 if(monitor.mInputMonitor.claims==0)monitor.listener.onInputEvent(new MotionEvent(3,552,2459,0));}
 p.x=width;p.y=height;}}''',
'android/view/WindowManager.java': '''package android.view;public class WindowManager {public Display display=new Display();public Display getDefaultDisplay(){return display;}
 public static class LayoutParams {public static final int TYPE_STATUS_BAR=2000;public int type=TYPE_STATUS_BAR;public String title="StatusBar";public CharSequence getTitle(){return title;}}}''',
'android/view/Choreographer.java': 'package android.view;public class Choreographer {public static Choreographer getInstance(){return new Choreographer();}}',
'android/view/InputDevice.java': 'package android.view;public class InputDevice {public static final int SOURCE_TOUCHSCREEN=4098;}',
'android/view/MotionEvent.java': '''package android.view;public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5,ACTION_POINTER_UP=6;
 public int action,display,count=1;public float x,y,x1,y1;public long time;public MotionEvent(int a,float x,float y,long t){action=a;this.x=x;this.y=y;time=t;}
 public int getActionMasked(){return action;}public int getDisplayId(){return display;}public int getPointerId(int i){return 7+i*5;}
 public int getPointerCount(){return count;}public boolean isFromSource(int s){return true;}public float getRawX(){return x;}public float getRawY(){return y;}public float getRawX(int i){return i==0?x:x1;}public float getRawY(int i){return i==0?y:y1;}public long getEventTime(){return time;}}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;import java.lang.reflect.*;public class XposedHelpers {
 public static XC_MethodHook hook,panelHook;public static Object findAndHookMethod(String n,ClassLoader c,String m,Object...a){if(m.equals("handleMessage"))hook=(XC_MethodHook)a[a.length-1];else if(m.equals("onShadeOrQsExpanded"))panelHook=(XC_MethodHook)a[a.length-1];return null;}
 public static int getIntField(Object o,String n){try{return o.getClass().getField(n).getInt(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static Class<?>findClass(String n,ClassLoader c){try{return Class.forName(n,true,c);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object newInstance(Class<?>c,Object...a){try{return c.getConstructors()[0].newInstance(a);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}}''',
'com/android/systemui/shared/system/InputChannelCompat.java': 'package com.android.systemui.shared.system;public class InputChannelCompat {public interface InputEventListener {void onInputEvent(Object e);}}',
'com/android/systemui/shared/system/InputMonitorCompat.java': '''package com.android.systemui.shared.system;public class InputMonitorCompat {
 public static InputMonitorCompat latest;public InputChannelCompat.InputEventListener listener;public Platform mInputMonitor=new Platform();
 public Receiver receiver=new Receiver();public static class Receiver {public boolean batching=true;public void setBatchingEnabled(boolean b){batching=b;}}
 public static class LegacyReceiver {public Receiver mReceiver;public LegacyReceiver(Receiver r){mReceiver=r;}}
 public InputMonitorCompat(String name,int display){latest=this;}public Object getInputReceiver(Object l,Object c,InputChannelCompat.InputEventListener p){listener=p;return Boolean.getBoolean("mistep.legacyReceiver")?new LegacyReceiver(receiver):receiver;}
 public static class Platform {public int claims;public void pilferPointers(){claims++;}}}''',
'com/jiusis/mistep/MiStepEntryGuard.java': 'package com.jiusis.mistep;final class MiStepEntryGuard {static boolean open=true;static boolean canOpen(Object c){return open;}}',
'com/jiusis/mistep/DeviceProfile.java': 'package com.jiusis.mistep;final class DeviceProfile {static boolean tablet;static boolean isTablet(Object c){return tablet;}}',
'com/jiusis/mistep/WorkspaceSettingsClient.java': '''package com.jiusis.mistep;import java.util.function.Consumer;
final class WorkspaceSettingsClient {static WorkspaceOptions initial=WorkspaceOptions.DEFAULT;static Consumer<WorkspaceOptions> changed;
 WorkspaceSettingsClient(Object context,Object handler,Consumer<WorkspaceOptions> callback){changed=callback;callback.accept(initial);}}''',
'com/jiusis/mistep/MiStepHost.java': '''package com.jiusis.mistep;final class MiStepHost {
 static boolean active,ready=true;static Object token;static int dismissed,dismissMoves,dismissEnds,dismissCancels;static int began,moved,ended,cancelled;static float displacement;
 static Object topDismissToken(){return ready?token:null;}static boolean beginTopDismiss(float dx,Object t){dismissed++;return ready&&t==token;}
 static int backs;static void requestTopBack(Object token){backs++;}
 static void moveTopDismiss(float d){dismissMoves++;displacement=d;}static void endTopDismiss(boolean c){dismissEnds++;if(c)dismissCancels++;}
 static Boolean pendingRight;static boolean hostSceneActive(){return active;}static void setPendingMainSide(boolean r){pendingRight=r;}
 static int topGestureHeight(){return ready?622:0;}static boolean beginTopGesture(){began++;return ready;}
 static int[] bounds;static int direction;
 static int[] topGestureBounds(){return !ready?null:bounds!=null?bounds:new int[]{0,0,1080,622};}
 static int topGestureDirection(){return direction;}
 static void moveTopGesture(float d,long t){moved++;displacement=d;}static void endTopGesture(boolean c){ended++;if(c)cancelled++;}}''',
'com/jiusis/mistep/EntryGestureRegression.java': r'''package com.jiusis.mistep;
import android.content.Context;import android.os.*;import android.view.*;import com.android.systemui.shared.system.InputMonitorCompat;
import de.robv.android.xposed.*;
public class EntryGestureRegression {
 static int checks,opened;static Context ctx=new Context();static long time;
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void command(int what,int a,int b,Object obj)throws Exception {
  Message m=new Message();m.what=what;m.arg1=a;m.arg2=b;m.obj=obj;
  var p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{m};
  var f=XposedHelpers.hook.getClass().getDeclaredMethod("beforeHookedMethod",XC_MethodHook.MethodHookParam.class);f.setAccessible(true);f.invoke(XposedHelpers.hook,p);
 }
 static void fullscreen(boolean hide)throws Exception{command(37<<16,hide?1:0,0,null);}
 static void bar(int display,int window,int state)throws Exception{command(12<<16,display,window,state);}
 public static class TransientArgs {public int argi1,argi2;TransientArgs(int display,int types){argi1=display;argi2=types;}}
 static void transientBar(boolean show,int display,int types)throws Exception{command((show?48:49)<<16,0,0,new TransientArgs(display,types));}
 static void panel(boolean expanded)throws Exception {
  var p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{expanded};
  var f=XposedHelpers.panelHook.getClass().getDeclaredMethod("beforeHookedMethod",XC_MethodHook.MethodHookParam.class);f.setAccessible(true);f.invoke(XposedHelpers.panelHook,p);
 }
 static void event(int action,float x,float y){InputMonitorCompat.latest.listener.onInputEvent(new MotionEvent(action,x,y,time+=100));}
 static void two(int action,float y){MotionEvent e=new MotionEvent(action,300,y,time+=70);e.count=2;e.x1=650;e.y1=y;InputMonitorCompat.latest.listener.onInputEvent(e);}
 static void horizontal(int action,float x,float x1){MotionEvent e=new MotionEvent(action,x,300,time+=70);e.count=2;e.x1=x1;e.y1=300;InputMonitorCompat.latest.listener.onInputEvent(e);}
 static void reset(int rotation,int width,int height)throws Exception {
  Handler.drain();HotZoneGesture.suspend();HotZoneGesture.raiseAll(ctx);HotZoneGesture.setEntryGesture(0);
  android.provider.Settings.Global.hide=0;android.provider.Settings.Global.fail=false;
  android.util.DisplayMetrics.defaultDensity=1;MiStepHost.active=false;MiStepHost.pendingRight=null;MiStepHost.token=null;MiStepHost.bounds=null;MiStepHost.direction=0;MiStepEntryGuard.open=true;DeviceProfile.tablet=false;
  ctx.wm.display.rotation=rotation;ctx.wm.display.width=width;ctx.wm.display.height=height;
  fullscreen(false);transientBar(false,0,1);panel(false);bar(0,1,0);opened=0;InputMonitorCompat.latest.mInputMonitor.claims=0;
 }
 static void swipe(boolean right){int w=ctx.wm.display.width;event(0,right?w-20:20,30);event(2,right?w-100:100,30);event(1,right?w-100:100,30);Handler.drain();}
 static void untouched(String why){check(opened==0,why+" opened MiStep");check(InputMonitorCompat.latest.mInputMonitor.claims==0,why+" stole original input");}
 public static void main(String[]args)throws Exception{
  WorkspaceSettingsClient.initial=WorkspaceOptions.DEFAULT.withEntryGesture(1);android.provider.Settings.Global.hide=1;
  MiStepStatusBar.install(EntryGestureRegression.class.getClassLoader());HotZoneGesture.install(ctx,()->opened++);
  event(0,540,2459);event(1,700,2459);Handler.drain();
  check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"saved bottom choice loads before lazy Host creation");
  WorkspaceSettingsClient.changed.accept(WorkspaceOptions.DEFAULT);
  opened=0;InputMonitorCompat.latest.mInputMonitor.claims=0;
  event(0,540,2459);event(1,700,2459);Handler.drain();untouched("live provider choice reaches idle spy");
  for(boolean right:new boolean[]{false,true}){
   reset(0,1080,2460);swipe(right);check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"ordinary portrait entry");
   check(Boolean.valueOf(!right).equals(MiStepHost.pendingRight),"portrait main opens opposite the starting corner");
   for(int r:new int[]{1,3}){reset(r,2460,1080);swipe(right);untouched("landscape "+r);}
   for(int r:new int[]{1,3}){
    reset(r,3048,2032);DeviceProfile.tablet=true;swipe(right);
    check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"tablet landscape entry "+r);
    check(Boolean.valueOf(!right).equals(MiStepHost.pendingRight),"tablet main opens opposite the starting corner "+r);
    reset(r,3048,2032);DeviceProfile.tablet=true;fullscreen(true);bar(0,1,2);swipe(right);untouched("tablet hidden fullscreen "+r);
    reset(r,3048,2032);DeviceProfile.tablet=true;panel(true);swipe(right);untouched("tablet native panel expanded "+r);
   }
   reset(0,1080,2460);bar(0,1,2);swipe(right);untouched("hidden native bar");
   reset(0,1080,2460);bar(0,1,1);swipe(right);untouched("hiding native bar");
   reset(0,1080,2460);fullscreen(true);bar(0,1,0);swipe(right);untouched("fullscreen without explicit native reveal");
   for(int r:new int[]{0,1,3}) {
    reset(r,r==0?1080:2460,r==0?2460:1080);fullscreen(true);bar(0,1,2);swipe(right);untouched("immersive hidden "+r);
    transientBar(true,0,1);swipe(right);untouched("reveal before bar is shown "+r);
    bar(0,1,0);swipe(right);check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"revealed status bar permits separate corner swipe "+r);
    opened=0;InputMonitorCompat.latest.mInputMonitor.claims=0;panel(true);swipe(right);untouched("expanded shade "+r);panel(false);
    transientBar(false,0,1);swipe(right);untouched("native transient aborted "+r);
    transientBar(true,0,2);swipe(right);untouched("navigation-only reveal "+r);
    transientBar(true,1,1);swipe(right);untouched("other display reveal "+r);
   }
  }
  reset(0,1080,2460);event(0,20,30);fullscreen(true);event(2,100,30);Handler.drain();untouched("fullscreen after DOWN");
  reset(0,1080,2460);event(0,20,30);event(2,100,30);fullscreen(true);Handler.drain();check(opened==0,"queued opening survived fullscreen");
  reset(0,1080,2460);fullscreen(true);transientBar(true,0,1);bar(0,1,0);event(0,20,30);event(2,100,30);transientBar(false,0,1);Handler.drain();check(opened==1,"admitted swipe cancelled when native bar retracted");
  for(boolean right:new boolean[]{false,true})for(int r:new int[]{1,3}) {
   reset(r,2460,1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);
   event(0,right?2440:20,30);bar(0,1,2);transientBar(false,0,1);
   event(2,right?2360:100,30);event(1,right?2360:100,30);Handler.drain();
   check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"visible DOWN lost to native retraction "+r);
   opened=0;InputMonitorCompat.latest.mInputMonitor.claims=0;swipe(right);untouched("new hidden DOWN after admitted swipe "+r);
   reset(r,2460,1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);
   event(0,right?2440:20,30);panel(true);event(2,right?2360:100,30);Handler.drain();untouched("panel expanded during admitted swipe "+r);
   reset(r,2460,1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);
   event(0,right?2440:20,30);event(2,right?2360:100,30);panel(true);Handler.drain();check(opened==0,"queued opening survived panel expansion "+r);
  }
  // Exact 11T spy trace: batched MOVE stops below 58dp; final displacement arrives on UP.
  reset(1,2460,1080);android.util.DisplayMetrics.defaultDensity=2.75f;
  fullscreen(true);transientBar(true,0,1);bar(0,1,0);
  var actual=InputMonitorCompat.latest.listener;
  actual.onInputEvent(new MotionEvent(0,2457,30,381057));
  actual.onInputEvent(new MotionEvent(2,2440.5342f,30,381069));
  actual.onInputEvent(new MotionEvent(2,2427.8232f,30,381078));
  actual.onInputEvent(new MotionEvent(2,2376.039f,30,381116));
  bar(0,1,2);transientBar(false,0,1);
  actual.onInputEvent(new MotionEvent(1,2210,30,381310));Handler.drain();
  check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"11T terminal-only displacement lost");
  for(boolean right:new boolean[]{false,true})for(int r:new int[]{0,1,3}) {
   int w=r==0?1080:2460;float start=right?w-20:20,end=right?w-240:240;
   reset(r,w,r==0?2460:1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);
   event(0,start,30);event(1,end,30);Handler.drain();
   check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"UP completes swipe "+right+":"+r);
   reset(r,w,r==0?2460:1080);fullscreen(true);bar(0,1,2);event(0,start,30);event(1,end,30);Handler.drain();untouched("hidden DOWN/long UP "+r);
   reset(r,w,r==0?2460:1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);event(0,start,30);event(1,start,30);Handler.drain();untouched("terminal tap "+r);
   reset(r,w,r==0?2460:1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);event(0,start,30);event(1,end,80);Handler.drain();untouched("terminal vertical drift "+r);
   reset(r,w,r==0?2460:1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);event(0,start,30);time+=501;event(1,end,30);Handler.drain();untouched("terminal late swipe "+r);
   reset(r,w,r==0?2460:1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);event(0,start,30);event(3,start,30);event(1,end,30);Handler.drain();untouched("UP after CANCEL "+r);
   reset(r,w,r==0?2460:1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);event(0,start,30);MiStepEntryGuard.open=false;event(1,end,30);Handler.drain();untouched("lock before UP "+r);
   reset(r,w,r==0?2460:1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);event(0,start,30);panel(true);event(1,end,30);Handler.drain();untouched("panel before UP "+r);
   reset(r,w,r==0?2460:1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);event(0,start,30);ctx.wm.display.rotation=(r+1)%4;event(1,end,30);Handler.drain();untouched("rotation before UP "+r);
   reset(r,w,r==0?2460:1080);fullscreen(true);transientBar(true,0,1);bar(0,1,0);event(0,start,30);event(2,end,30);event(1,end,30);Handler.drain();
   check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"MOVE then UP claimed twice "+r);
  }
  reset(0,1080,2460);event(0,20,30);event(2,100,30);MiStepHost.active=true;Handler.drain();check(opened==0,"queued opening survived active workspace");
  reset(0,1080,2460);bar(1,1,2);bar(0,2,2);swipe(false);check(opened==1,"unrelated display/nav blocked portrait");
  reset(0,1080,2460);fullscreen(true);fullscreen(false);swipe(false);check(opened==1,"leaving immersive failed to restore entry");
  reset(0,1080,2460);event(0,20,30);event(1,20,30);Handler.drain();untouched("ordinary tap");
  reset(0,1080,2460);MiStepEntryGuard.open=false;swipe(false);untouched("locked device");
  reset(0,1080,2460);MiStepHost.active=true;event(0,300,500);event(2,300,100);event(1,300,100);untouched("active single finger");
  event(0,300,500);two(5,500);two(2,490);check(InputMonitorCompat.latest.mInputMonitor.claims==0,"two-finger jitter claimed");
  two(2,420);check(InputMonitorCompat.latest.mInputMonitor.claims==1 && MiStepHost.began==1,"two-finger drag not claimed once");
  two(2,100);check(MiStepHost.displacement==-400 && InputMonitorCompat.latest.mInputMonitor.claims==1,"drag lost tracking/reclaimed");
  two(6,100);check(MiStepHost.ended==1 && MiStepHost.cancelled==0,"pointer release not settled");
  reset(0,1080,2460);MiStepHost.active=true;event(0,300,500);two(5,500);two(2,420);MiStepHost.ready=false;two(2,100);
  check(MiStepHost.cancelled==1,"readiness lost without cancelling");MiStepHost.ready=true;
  reset(0,1080,2460);event(0,300,500);two(5,500);two(2,100);untouched("inactive top drag");
  reset(0,1080,2460);MiStepHost.active=true;MiStepHost.token=new Object();
  event(0,300,300);horizontal(5,300,600);horizontal(2,380,680);
  check(InputMonitorCompat.latest.mInputMonitor.claims==1&&MiStepHost.dismissed==1,"horizontal TOP claims exactly once");
  horizontal(2,700,1000);check(MiStepHost.displacement==400&&MiStepHost.dismissMoves==2,"horizontal tracks same stream");
  horizontal(3,700,1000);check(MiStepHost.dismissCancels==1,"horizontal native CANCEL rebounds via correct axis");
  reset(0,1080,2460);MiStepHost.active=true;MiStepHost.token=new Object();
  event(0,300,300);horizontal(5,300,600);MiStepHost.token=new Object();horizontal(2,380,680);
  check(InputMonitorCompat.latest.mInputMonitor.claims==0,"token changed after DOWN must not steal input");
  for(boolean left:new boolean[]{true,false})for(int mode=0;mode<4;mode++) {
   reset(1,3048,2032);MiStepHost.active=true;MiStepHost.token=new Object();
   MiStepHost.bounds=left?new int[]{0,606,904,2032}:new int[]{2144,606,3048,2032};
   MiStepHost.direction=left?-1:1;
   float x=(left?0:2144)+300,y=1000;
   if(mode==2)x=left?1400:300;
   event(0,x,y);MotionEvent pair=new MotionEvent(5,x,y,time+=70);pair.count=2;pair.x1=x+150;pair.y1=y;
   InputMonitorCompat.latest.listener.onInputEvent(pair);
   MotionEvent move=new MotionEvent(2,x+(mode==1?-1:1)*MiStepHost.direction*100,y+(mode==3?200:0),time+=70);
   move.count=2;move.x1=move.x+150;move.y1=move.y;InputMonitorCompat.latest.listener.onInputEvent(move);
   check(InputMonitorCompat.latest.mInputMonitor.claims==(mode<=1?1:0),"portrait bounds horizontal main/background routing "+left+":"+mode);
   if(mode==0){move.action=3;InputMonitorCompat.latest.listener.onInputEvent(move);}
  }
  for(boolean left:new boolean[]{true,false}) {
   float end=left?400:680;
   reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);
   event(0,540,2459);event(1,end,2459);Handler.drain();untouched("visible gesture line blocks bottom");
   swipe(false);check(opened==1,"visible line falls back to original corner");
   reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
   event(0,540,2459);event(2,end,2459);event(1,end,2459);Handler.drain();
   check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"bottom outward claims once "+left);
   check(Boolean.valueOf(!left).equals(MiStepHost.pendingRight),"bottom main follows outward direction "+left);
   reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
   swipe(false);untouched("bottom selected leaves corner alone");
   event(0,540,2459);event(1,end,2459);Handler.drain();
   check(opened==1,"terminal bottom endpoint accepted "+left);
  }
  for(int scenario=0;scenario<13;scenario++) {
   reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
   if(scenario==0)android.provider.Settings.Global.fail=true;
   if(scenario==1)panel(true);
   if(scenario==2)MiStepEntryGuard.open=false;
   event(0,scenario==3?-1:540,scenario==4?2100:2459);
   if(scenario==5)android.provider.Settings.Global.hide=0;
   if(scenario==6)HotZoneGesture.setEntryGesture(0);
   if(scenario==7)ctx.wm.display.rotation=1;
   if(scenario==8)event(3,540,2459);
   if(scenario==9)time+=601;
   if(scenario==10)panel(true);
   if(scenario==11){MotionEvent pair=new MotionEvent(5,540,2459,time+=30);pair.count=2;InputMonitorCompat.latest.listener.onInputEvent(pair);}
   event(1,scenario==12?540:700,scenario==12?2100:2459);Handler.drain();
   untouched("bottom rejection scenario "+scenario);
  }
  reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
  event(0,540,2459);event(2,700,2459);android.provider.Settings.Global.hide=0;Handler.drain();
  check(opened==0,"queued bottom opening cancelled after line reappears");
  reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
  event(0,540,2459);event(2,700,2459);HotZoneGesture.setEntryGesture(0);Handler.drain();
  check(opened==0,"queued bottom opening cancelled after mode change");
  reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
  fullscreen(true);bar(0,1,2);event(0,540,2459);event(1,700,2459);Handler.drain();
  check(opened==1,"bottom admission independent of hidden status bar");
  reset(1,2460,1080);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
  event(0,1230,1079);event(1,1430,1079);Handler.drain();untouched("phone landscape remains unchanged");
  reset(1,3048,2032);DeviceProfile.tablet=true;HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
  event(0,1524,2031);event(1,1724,2031);Handler.drain();
  check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"tablet landscape bottom entry");
  for(float density:new float[]{1f,2.75f,3.5f})for(boolean tablet:new boolean[]{false,true}) {
   int width=tablet?3048:1080,height=tablet?2032:2460,edge=Math.round(8f*density);
   for(float start:new float[]{0,1,width/6f,width/2f,width*5f/6f,width-2,width-1})for(int sign:new int[]{-1,1}) {
    float end=start+sign*60f*density;
    if(end<0||end>=width)continue;
    reset(tablet?1:0,width,height);DeviceProfile.tablet=tablet;
    android.util.DisplayMetrics.defaultDensity=density;
    HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
    event(0,start,height-1);event(1,end,height-1);Handler.drain();
    check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,
      "any bottom start opens once "+density+":"+tablet+":"+start+":"+sign);
    check(Boolean.valueOf(sign>0).equals(MiStepHost.pendingRight),
      "any bottom start follows direction "+density+":"+tablet+":"+start+":"+sign);
   }
   for(float start:new float[]{-1,width}) {
    reset(tablet?1:0,width,height);DeviceProfile.tablet=tablet;
    android.util.DisplayMetrics.defaultDensity=density;
    HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
    event(0,start,height-1);event(1,width/2f,height-1);Handler.drain();
    untouched("off-display bottom start rejected "+start);
   }
   for(int inset:new int[]{1,edge,edge+1,Math.round(32f*density)}) {
    reset(tablet?1:0,width,height);DeviceProfile.tablet=tablet;
    android.util.DisplayMetrics.defaultDensity=density;
    HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
    event(0,width/2f,height-inset);event(1,width/2f+60f*density,height-inset);Handler.drain();
    if(inset<=edge) {
     check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"physical bottom edge opens "+density+":"+tablet+":"+inset);
     check(Boolean.TRUE.equals(MiStepHost.pendingRight),"physical bottom keeps swipe direction");
    } else untouched("content above bottom edge "+density+":"+tablet+":"+inset);
   }
  }
  for(float density:new float[]{1f,2.75f,3.5f})for(int sign:new int[]{-1,1}) {
   reset(0,1080,2460);android.util.DisplayMetrics.defaultDensity=density;
   HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
   event(0,540,2459);
   check(!InputMonitorCompat.latest.receiver.batching,"bottom DOWN must disable frame batching before native CANCEL");
   event(2,540+sign*3f*density,2459);
   check(InputMonitorCompat.latest.mInputMonitor.claims==1,"bottom horizontal intent must claim before native cancel at 5dp "+density+":"+sign);
   Handler.drain();check(opened==0,"early claim must not open before full sweep");
   event(2,540+sign*30f*density,2459);
   check(InputMonitorCompat.latest.mInputMonitor.claims==1,"bottom tracking must not reclaim");
   event(1,540+sign*60f*density,2459);Handler.drain();
   check(opened==1&&InputMonitorCompat.latest.mInputMonitor.claims==1,"retained stream completes exactly once");
   check(InputMonitorCompat.latest.receiver.batching,"bottom UP restores batching");
   check(Boolean.valueOf(sign>0).equals(MiStepHost.pendingRight),"retained stream keeps final direction");
   for(int mode=0;mode<3;mode++) {
    reset(0,1080,2460);android.util.DisplayMetrics.defaultDensity=density;
    HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
    event(0,540,2459);
    event(2,540+sign*(mode==0?1f:3f)*density,2459-(mode==2?6f:mode==1?3f:0)*density);
    event(1,540,2459);Handler.drain();untouched("bottom jitter/diagonal/vertical stays native "+mode);
   }
   reset(0,1080,2460);android.util.DisplayMetrics.defaultDensity=density;
   HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
   event(0,540,2459);event(2,540+sign*3f*density,2459);event(3,540+sign*5f*density,2459);
   event(1,540+sign*60f*density,2459);Handler.drain();check(opened==0,"cancel after claim never opens");
  }
  reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
  event(0,540,2459);Display.cancelIfUnclaimed=true;event(2,543,2459);
  check(InputMonitorCompat.latest.mInputMonitor.claims==1,"claim horizontal intent before synchronous display/settings/guard queries");
  event(1,600,2459);Handler.drain();check(opened==1,"retained stream survives competing claim during state queries");
  reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
  long replayDown=9000000;android.os.SystemClock.now=replayDown+250;
  HotZoneGesture.completeBottomReplay(1,replayDown,replayDown+200);
  check(opened==1&&Boolean.TRUE.equals(MiStepHost.pendingRight),"native penetration replay opens right");
  HotZoneGesture.completeBottomReplay(1,replayDown,replayDown+200);
  check(opened==1,"same physical replay cannot open twice");
  reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
  replayDown+=1000;android.os.SystemClock.now=replayDown+250;
  HotZoneGesture.completeBottomReplay(-1,replayDown,replayDown+200);
  check(opened==1&&Boolean.FALSE.equals(MiStepHost.pendingRight),"native penetration replay opens left");
  for(int gate=0;gate<6;gate++) {
   reset(0,1080,2460);HotZoneGesture.setEntryGesture(1);android.provider.Settings.Global.hide=1;
   replayDown+=1000;android.os.SystemClock.now=replayDown+250;
   if(gate==0)android.provider.Settings.Global.hide=0;
   if(gate==1)HotZoneGesture.setEntryGesture(0);
   if(gate==2)MiStepEntryGuard.open=false;
   if(gate==3)MiStepHost.active=true;
   if(gate==4)panel(true);
   if(gate==5)android.os.SystemClock.now=replayDown+1000;
   HotZoneGesture.completeBottomReplay(1,replayDown,replayDown+200);
   untouched("replay validates gate "+gate);
  }
  System.out.println("actual entry spy/native observer checks="+checks+" passed");
 }
}'''
}
paths=[]
for name, text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
paths += [str(src/n) for n in ['CornerSwipe.java','BottomCenterSwipe.java','WorkspaceOptions.java',
          'EntryGesturePolicy.java','EntryGestureSettings.java','TopTaskSwipe.java','TopEdgeBackSwipe.java','HotZoneGesture.java']]
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(out),'-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.EntryGestureRegression'],check=True)
subprocess.run([str(jdk/'java.exe'),'-Dmistep.legacyReceiver=true','-cp',str(out),'com.jiusis.mistep.EntryGestureRegression'],check=True)
