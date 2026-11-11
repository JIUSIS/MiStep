"""Exercise production trace callbacks with passive Android/Xposed JVM fixtures."""
import subprocess


def run(root, out, jdk, trace_source=None):
    folder = out / 'trace'
    files = {
        'android/content/Context.java': '''package android.content; public class Context {
 public static final String KEYGUARD_SERVICE="keyguard",POWER_SERVICE="power";public static final int RECEIVER_NOT_EXPORTED=4;
 public Object getSystemService(String key){return key.equals(KEYGUARD_SERVICE)?new android.app.KeyguardManager():new android.os.PowerManager();}
 public java.io.File getFilesDir(){return new java.io.File(System.getProperty("trace.output"));}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){return null;}}''',
        'android/content/BroadcastReceiver.java': 'package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
        'android/content/Intent.java': 'package android.content;public class Intent {public static final String ACTION_SCREEN_OFF="screen_off";}',
        'android/content/IntentFilter.java': 'package android.content;public class IntentFilter {public IntentFilter(String s){}}',
        'android/app/KeyguardManager.java': 'package android.app;public class KeyguardManager {public static boolean locked;public boolean isKeyguardLocked(){return locked;}}',
        'android/os/PowerManager.java': 'package android.os;public class PowerManager {public boolean isInteractive(){return true;}}',
        'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long now=1000;public static long uptimeMillis(){return now;}public static long elapsedRealtime(){return now;}}',
        'android/os/Process.java': 'package android.os;public class Process {public static int myPid(){return 90;}}',
        'android/graphics/Rect.java': 'package android.graphics;public class Rect {}',
        'android/graphics/Region.java': 'package android.graphics;public class Region {}',
        'android/view/MotionEvent.java': '''package android.view;public class MotionEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
 public int action;public MotionEvent(int a){action=a;}public int getActionMasked(){return action;}public long getEventTime(){return 1010;}
 public float getX(){return 5;}public float getY(){return 100;}public float getRawX(){return 5;}public float getRawY(){return 100;}
 public int getPointerCount(){return 1;}public int getDisplayId(){return 0;}}''',
        'android/view/KeyEvent.java': 'package android.view;public class KeyEvent {public static final int KEYCODE_BACK=4;public int getKeyCode(){return 4;}public int getAction(){return 0;}public long getEventTime(){return 1000;}}',
        'android/hardware/input/InputManager.java': '''package android.hardware.input;public class InputManager {
 public int calls;public boolean injectInputEvent(android.view.KeyEvent e,int mode){calls++;return true;}
 public boolean injectInputEvent(android.view.KeyEvent e,int mode,int targetUid){calls++;return targetUid==10321;}}''',
        'android/view/View.java': 'package android.view;public class View {public void getLocationOnScreen(int[] a){}public boolean getGlobalVisibleRect(android.graphics.Rect r){return true;}public int getWidth(){return 100;}public int getHeight(){return 100;}}',
        'android/view/WindowManager.java': 'package android.view;public interface WindowManager {class LayoutParams {public int type,privateFlags;public CharSequence getTitle(){return "";}}}',
        'com/android/systemui/navigationbar/gestural/DisplayBackGestureHandlerImpl$inputEventReceiver$1.java': '''package com.android.systemui.navigationbar.gestural;
 public class DisplayBackGestureHandlerImpl$inputEventReceiver$1 {public int calls;public void onInputEvent(android.view.MotionEvent event){calls++;}}''',
        'com/android/systemui/navigationbar/gestural/BackPanelController.java': '''package com.android.systemui.navigationbar.gestural;
 public class BackPanelController {public int calls;public void onMotionEvent(android.view.MotionEvent event){calls++;}}''',
        'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;public class XC_MethodHook {
 public class Unhook {public void unhook(){XposedBridge.unhooks++;}}
 public static class MethodHookParam {public Object thisObject;public Object[] args;public Object result;public Throwable error;
 public Object getResult(){return result;}public boolean hasThrowable(){return error!=null;}}
 protected void beforeHookedMethod(MethodHookParam p){}protected void afterHookedMethod(MethodHookParam p){}}''',
        'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;import java.util.*;import java.lang.reflect.*;
 public class XposedBridge {public static int unhooks;static Map<String,List<XC_MethodHook>> hooks=new HashMap<>();
 public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c,String name,XC_MethodHook h){
 boolean exists=Arrays.stream(c.getDeclaredMethods()).anyMatch(m->m.getName().equals(name));if(!exists)return Set.of();
 hooks.computeIfAbsent(c.getName()+"#"+name,k->new ArrayList<>()).add(h);return Set.of(h.new Unhook());}
 public static Object fire(Object target,String name,Object...args)throws Exception {
 var p=new XC_MethodHook.MethodHookParam();p.thisObject=target;p.args=args;
 var hs=hooks.getOrDefault(target.getClass().getName()+"#"+name,List.of());for(var h:hs)h.beforeHookedMethod(p);
 Method method=Arrays.stream(target.getClass().getDeclaredMethods()).filter(m->m.getName().equals(name)&&m.getParameterCount()==args.length).findFirst().orElseThrow();
 method.setAccessible(true);try {p.result=method.invoke(target,args);}catch(InvocationTargetException e){p.error=e.getCause();}
 for(var h:hs)h.afterHookedMethod(p);if(p.error!=null)throw new AssertionError(p.error);return p.result;}
 public static void log(String s){}}''',
        'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;import java.lang.reflect.*;import java.util.*;public class XposedHelpers {
 public static Class<?> findClass(String n,ClassLoader l){try{return Class.forName(n,false,l);}catch(Exception e){throw new RuntimeException(e);}}
 static Field field(Class<?> c,String n){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception e){throw new RuntimeException(e);}}
 public static Object getObjectField(Object o,String n){try{return field(o.getClass(),n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}public static long getLongField(Object o,String n){return (Long)getObjectField(o,n);}
 public static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}public static float getFloatField(Object o,String n){return (Float)getObjectField(o,n);}
 public static int getStaticIntField(Class<?> c,String n){try{return field(c,n).getInt(null);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...a){try{Method m=Arrays.stream(o.getClass().getDeclaredMethods()).filter(x->x.getName().equals(n)&&x.getParameterCount()==a.length).findFirst().orElseThrow();m.setAccessible(true);return m.invoke(o,a);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callStaticMethod(Class<?> c,String n,Object...a){return null;}public static Object newInstance(Class<?> c,Object...a){return null;}}''',
        'com/jiusis/mistep/TraceDependencies.java': '''package com.jiusis.mistep;
 class MiStepEntryGuard {static boolean canOpen(android.content.Context c){return true;}}
 class MiStepRecentsWallpaper {static int argumentOffset(Object[] a){return -1;}}
 class MiStepHost {static final int SLOT_TOP=0;long sceneEpoch=7;int scene=1;Object topTransition=new Object();Slot[] slots={new Slot()};
 static class Slot{int taskId=81;}static float[] activeMainTransform(){return null;}boolean startTopTaskTransition(boolean b){return true;}
 void revealTopTaskTransition(){}void cancelTopTaskTransition(){}void releaseTopTaskNow(){}void returnToSystem(){}
 void handoffPromotedTask(){}void checkPromotion(){}void finishPromotionFocus(){}}
 class HotZoneGesture {void onMotion(android.view.MotionEvent e){}}
 class MiStepStatusBar {static String cornerStateForTrace(){return "";}}
 class MiStepTopBack {static int requests;static void request(android.content.Context c,Object token){requests++;}static Runnable prepare(){return null;}}
 class MiStepTopTaskTransition {boolean closed,collapsed,revealing;void start(){}void collect(){}boolean collectionReady(){return true;}
 void handoff(){}void reveal(){revealing=true;}void animate(float a,float b,long c){}void close(){closed=true;}
 boolean applyLiveScale(float f){return true;}void applyCollectionScale(float f){}}
 class MiStepPassiveInputChannel {Object input;static MiStepPassiveInputChannel open(Object s,String n,ClassLoader c){return new MiStepPassiveInputChannel();}void close(){}}
 class MiStepDesktopLaunchBridge {static boolean ownsTopTask(Object task){return false;}static Object recentsLease(Object task){return null;}}
 class MiStepImePolicy {static boolean isTopAppFocused(Object t,Object d){return false;}}
 class TransitionMagnificationCompat {static boolean isStart(Object[] a){return false;}}
 ''',
        'com/jiusis/mistep/RuntimeTraceRegression.java': '''package com.jiusis.mistep;
 import java.lang.reflect.*;import java.nio.file.*;import de.robv.android.xposed.*;import android.view.MotionEvent;
 public class RuntimeTraceRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static Object call(String name,Class<?>[] types,Object...args)throws Exception{Method m=MiStepInputTrace.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}
 static String lines()throws Exception{Field f=MiStepInputTrace.class.getDeclaredField("LINES");f.setAccessible(true);return f.get(null).toString();}
 static Object edge(TopEdgeBackSwipe s,int action,float x,long at,Object owner)throws Exception{
 return XposedBridge.fire(s,"event",action,1,0,x,120f,at,1000,1000,1f,1,owner);}
 public static void main(String[] args)throws Exception {
 MiStepInputTrace.arm(new android.content.Context(),false,null);
 String arm=lines();for(String key:new String[]{"bootId=","pid=90","epochMillis=","elapsedRealtime=1000","generation=","duration=6000","maxLines=128"})check(arm.contains(key),"ARM identity missing "+key);
 call("hookSystemUi",new Class<?>[]{ClassLoader.class},RuntimeTraceRegression.class.getClassLoader());
 var receiver=new com.android.systemui.navigationbar.gestural.DisplayBackGestureHandlerImpl$inputEventReceiver$1();
 var backPanel=new com.android.systemui.navigationbar.gestural.BackPanelController();
 MotionEvent nativeEvent=new MotionEvent(0);XposedBridge.fire(receiver,"onInputEvent",nativeEvent);XposedBridge.fire(backPanel,"onMotionEvent",nativeEvent);
 check(receiver.calls==1&&backPanel.calls==1&&nativeEvent.action==0,"native observers changed dispatch");
 check(lines().contains("observer=native-display-back-receiver installed=true")&&lines().contains("native-display-back-receiver source=monitor eventTime="),"ROM back receiver invisible");
 check(lines().contains("observer=native-edge-back-handle installed=false"),"missing AOSP observer hidden");
 var swipe=new TopEdgeBackSwipe();Object owner=new Object();
 check(edge(swipe,0,5,1000,owner).equals(0),"observer changed DOWN");
 check(edge(swipe,2,30,1040,owner).equals(1),"observer changed CLAIM");
 check(edge(swipe,1,85,1080,owner).equals(2),"observer changed BACK");
 String captured=lines();check(captured.contains("top-edge-before action=1")&&captured.contains("currentOwner=true"),"owner admission invisible");
 check(captured.contains("decision=1")&&captured.contains("decision=2"),"CLAIM/BACK invisible");
 edge(swipe,0,5,1100,owner);check(edge(swipe,1,85,1200,new Object()).equals(0),"observer altered owner rejection");
 check(lines().contains("currentOwner=false"),"owner rejection invisible");
 var host=new MiStepHost();check(XposedBridge.fire(host,"startTopTaskTransition",true).equals(true),"host result changed");
 check(lines().contains("top-host-phase=startTopTaskTransition:exit epoch=7 scene=1 task=81"),"host epoch/task invisible");
 var transition=new MiStepTopTaskTransition();XposedBridge.fire(transition,"animate",0f,1f,180L);XposedBridge.fire(transition,"reveal");
 check(lines().contains("from=0.0 to=1.0 duration=180")&&lines().contains("revealing=true"),"live animation phase invisible");
  call("hookSystemServer",new Class<?>[]{ClassLoader.class},RuntimeTraceRegression.class.getClassLoader());
  var input=new android.hardware.input.InputManager();
  check(XposedBridge.fire(input,"injectInputEvent",new android.view.KeyEvent(),0,10321).equals(true)&&input.calls==1,"targeted Back observer changed native result");
  check(lines().contains("observer=back-dispatch installed=true")&&lines().contains("targetUid=10321 result=true"),"actual targeted Back dispatch invisible");
  int beforeUntargeted=lines().length();XposedBridge.fire(input,"injectInputEvent",new android.view.KeyEvent(),0);
  check(input.calls==2&&lines().length()==beforeUntargeted,"two-argument input misreported as targeted Back");
 int before=lines().split("\\n").length;for(int i=0;i<1000;i++)call("event",new Class<?>[]{String.class,MotionEvent.class},"sample",new MotionEvent(2));
 check(lines().split("\\n").length-before==24,"MOVE samples unbounded");
 call("line",new Class<?>[]{String.class},"x".repeat(5000));check(!lines().contains("x".repeat(1025)),"trace line grew beyond cap");
 call("line",new Class<?>[]{String.class},"one\\ntwo\\rthree");check(lines().contains("one two three"),"embedded newline bypassed line cap");
 for(int i=0;i<200;i++)call("line",new Class<?>[]{String.class},"numeric="+i);
 Path file=Path.of(System.getProperty("trace.output"),"mistep-input-traces","ui-1000.txt");
 String saved=Files.readString(file);check(saved.lines().count()==128&&saved.contains("TRACE_BUDGET_REACHED"),"128 line budget not enforced");
 check(saved.lines().allMatch(s->s.length()<=1024),"physical trace line exceeded 1024 chars");
 check(XposedBridge.unhooks>0,"budget failed to remove observers");
 android.os.SystemClock.now=9000;MiStepInputTrace.arm(new android.content.Context(),false,null);android.app.KeyguardManager.locked=true;
 call("event",new Class<?>[]{String.class,MotionEvent.class},"locked",new MotionEvent(0));
 check(lines().isEmpty(),"lock failed to finish trace");
 System.out.println("Production MiStepInputTrace observer checks="+checks+" passed (JVM fixtures; no ROM/Surface acceptance)");
 }}'''
    }
    for name, data in files.items():
        target = folder / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(data, encoding='utf-8')
    production = root / 'module/src/main/java/com/jiusis/mistep'
    subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(folder),
                    *(str(folder / name) for name in files),
                    str(trace_source or production / 'MiStepInputTrace.java'),
                    *(str(production / name) for name in ('InputTraceGate.java', 'TopTaskSwipe.java', 'TopEdgeBackSwipe.java'))], check=True)
    subprocess.run([str(jdk / 'java.exe'), '-Dtrace.output=' + str(folder), '-cp', str(folder),
                    'com.jiusis.mistep.RuntimeTraceRegression'], check=True)
