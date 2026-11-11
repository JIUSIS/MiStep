"""Replay the actual installed WM focus callbacks, including nested native failures."""
import ast
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SRC = Path(os.environ.get('MISTEP_FOCUS_SOURCE', ROOT / 'module/src/main/java/com/jiusis/mistep'))
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'build/tests/ime-window-focus-jvm'
JDK = Path(os.environ['MISTEP_JAVA_BIN'])
host = (SRC / 'HostEntry.java').read_text(encoding='utf-8-sig')
start = host.index('    private void installWorkspaceWindowFocus(ClassLoader cl) {')
end = host.index('\n    private void ', start + 10)
install = host[start:end]
tree = ast.parse((ROOT / 'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
shared = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign)
              and any(isinstance(t, ast.Name) and t.id == 'FILES' for t in n.targets))
helpers = shared['de/robv/android/xposed/XposedHelpers.java']
helpers = helpers.replace('public class XposedHelpers {', r'''public class XposedHelpers {
 public static final java.util.Map<java.lang.reflect.Method,XC_MethodHook> hooks=new java.util.HashMap<>();
 public static Class<?> findClass(String name,ClassLoader loader){try{return Class.forName(name);}catch(Exception e){throw new IllegalStateException(e);}}
 public static Object callStaticMethod(Class<?> owner,String name,Object...args){try{
  for(Method method:owner.getDeclaredMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length)return method.invoke(null,args);
  throw new NoSuchMethodException(name);
 }catch(Exception e){throw new IllegalStateException(e);}}
 public static void findAndHookMethod(Class<?> owner,String name,Object...args){try{
  Class<?>[] types=new Class<?>[args.length-1];for(int i=0;i<types.length;i++)types[i]=(Class<?>)args[i];
  Method method=owner.getDeclaredMethod(name,types);hooks.put(method,(XC_MethodHook)args[args.length-1]);
 }catch(Exception e){throw new IllegalStateException(e);}}
 public static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Throwable{
  Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
  XC_MethodHook hook=hooks.get(method);XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=target;p.args=args;
  if(hook!=null)hook.before(p);
  try{p.result=method.invoke(target,args);}catch(InvocationTargetException e){p.error=e.getCause();}
  if(hook!=null)hook.after(p);
  if(p.error!=null)throw p.error;return p.result;
 }
''')
FILES = {
    'android/view/InputDevice.java':'package android.view;public class InputDevice {public static final int SOURCE_TOUCHSCREEN=4098;}',
    'android/view/MotionEvent.java':'package android.view;public class MotionEvent {public static final int ACTION_DOWN=0;public int action,display,count=1,source=4098;public float x=1,y=300;public int getActionMasked(){return action;}public int getPointerCount(){return count;}public int getDisplayId(){return display;}public float getRawX(){return x;}public float getRawY(){return y;}public boolean isFromSource(int s){return source==s;}}',
    'android/os/Bundle.java':'package android.os;public class Bundle {public int getInt(String k,int d){return d;}public float getFloat(String k,float d){return d;}}',
    'com/android/server/wm/PointerEventDispatcher.java':'package com.android.server.wm;public class PointerEventDispatcher {public void onInputEvent(android.view.MotionEvent e){}}',
    'de/robv/android/xposed/XposedHelpers.java': helpers,
    'de/robv/android/xposed/XposedBridge.java': r'''package de.robv.android.xposed;
public class XposedBridge{
 public static final java.util.Set<java.lang.reflect.Member> deoptimized=new java.util.HashSet<>();
 public static void hookAllMethods(Class<?> c,String n,XC_MethodHook h){for(var m:c.getDeclaredMethods())if(m.getName().equals(n))XposedHelpers.hooks.put(m,h);}
 public static boolean deoptimizeMethod(java.lang.reflect.Member method){deoptimized.add(method);return true;}
}''',
    'de/robv/android/xposed/XC_MethodHook.java': r'''package de.robv.android.xposed;
public class XC_MethodHook{
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
 public void before(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}public void after(MethodHookParam p)throws Throwable{afterHookedMethod(p);}
 public static class MethodHookParam{
  public Object thisObject,result;public Object[] args;public Throwable error;
  private final java.util.Map<String,Object> extras=new java.util.HashMap<>();
  public Object getResult(){return result;}public void setResult(Object value){result=value;error=null;}
  public void setObjectExtra(String key,Object value){extras.put(key,value);}public Object getObjectExtra(String key){return extras.get(key);}
  public boolean hasThrowable(){return error!=null;}
 }
}''',
    'com/android/server/wm/WindowState.java': r'''package com.android.server.wm;
public class WindowState{
 public DisplayContent display;public Object task;public Object mActivityRecord;public boolean eligible=true,fail;
 public WindowState(DisplayContent d,Object t){display=d;task=t;}
 public DisplayContent getDisplayContent(){return display;}public Object getTask(){return task;}
 public void handleTapOutsideFocusInsideSelf(){}
 public boolean canReceiveKeys(){return canReceiveKeys(false);}
 public boolean canReceiveKeys(boolean fromUserTouch){if(fail)throw new IllegalStateException("native eligibility failure");return eligible;}
}''',
    'com/android/server/wm/DisplayContent.java': r'''package com.android.server.wm;
import de.robv.android.xposed.XposedHelpers;
public class DisplayContent{
 public WindowState mCurrentFocus;public Object mFocusedApp;public Object selectedRoot;public int appChanges;public boolean nativeCutoff;
 public boolean setFocusedApp(Object app){mFocusedApp=app;appChanges++;return true;}
 public WindowState[] windows=new WindowState[0];
 public Runnable nested;public boolean fail,booleanEligibility,touchEligibility=true;public WindowState selected;
 public WindowState findFocusedWindow()throws Throwable{
  if(nested!=null)nested.run();if(fail)throw new IllegalStateException("native scan failure");
  for(WindowState w:windows){boolean keys=(Boolean)(booleanEligibility
   ?XposedHelpers.invoke(w,"canReceiveKeys",new Class<?>[]{boolean.class},touchEligibility)
   :XposedHelpers.invoke(w,"canReceiveKeys",new Class<?>[0]));if(keys){if(nativeCutoff && mFocusedApp!=null && XposedHelpers.callMethod(mFocusedApp,"getTask")!=w.task)return selected=null;return selected=w;}}
  return selected=null;
 }
 public int localFocusDisplay=99;
 public boolean updateFocusedWindowLocked(int mode,boolean input,int display){localFocusDisplay=display;if(display!=-1){mCurrentFocus=null;return false;}for(var w:windows)if(w.mActivityRecord==mFocusedApp){mCurrentFocus=w;return true;}return false;}
 public WindowState findFocusedWindowIfNeeded(int top){return null;}
 public boolean lambda$new$3(WindowState window){return true;}
 public void unrelatedNativeMethod(){}
}''',
    'com/jiusis/mistep/MiStepDesktopLaunchBridge.java': r'''package com.jiusis.mistep;
public class MiStepDesktopLaunchBridge{
 static final java.util.Map<Object,Lease> leases=new java.util.IdentityHashMap<>();
 static class Lease{boolean alive=true,fail;}
 static Object topTask;static Object topTaskAt(Object display,float x,float y){return x>=0&&x<1080&&y>=0&&y<622?topTask:null;}static boolean ownsTopTask(Object task){return task!=null&&task==topTask;}
 static Object recentsLease(Object task){return leases.get(task);}
 static boolean recentsLeaseAlive(Object value){Lease lease=(Lease)value;if(lease.fail)throw new IllegalStateException("lease read failure");return lease.alive;}
}''',
    'com/jiusis/mistep/HostEntry.java': r'''package com.jiusis.mistep;
import de.robv.android.xposed.*;
class HostEntry{
 static boolean sZoomed;static Object sDisplayContent;
 static class ZoomTarget{Object display,globalLock=new Object();}static ZoomTarget sZoomTarget;
 static final ThreadLocal<Object> sWindowFocusScanDisplay=new ThreadLocal<>();
 static final java.util.List<String> logs=new java.util.ArrayList<>();
 static void log(String text){logs.add(text);}
 static void install(){new HostEntry().installWorkspaceWindowFocus(HostEntry.class.getClassLoader());}
''' + install + '\n}',
    'com/jiusis/mistep/ImeWindowFocusTest.java': r'''package com.jiusis.mistep;
import android.graphics.Rect;import de.robv.android.xposed.*;import com.android.server.wm.*;
public class ImeWindowFocusTest{
 static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static class Task{
  public DisplayContent display;public Activity activity;
  public DisplayContent getDisplayContent(){return display;}public Activity topRunningActivityLocked(){return activity;}
  public int mode=6;public boolean translucent=true,fail;public Rect bounds=new Rect(0,0,1080,2460);
  public int getWindowingMode(){if(fail)throw new IllegalStateException("task lookup failure");return mode;}
  public boolean isVisible(){return true;}public boolean isForceTranslucent(){return translucent;}public Rect getBounds(){return bounds;}
 }
 public static class Area{public Task selected;public Task getFocusedRootTask(){return selected;}}
 public static class Activity{public Task task;public Area area;public boolean focusable=true,visible=true;
  Activity(Task t,Area a){task=t;area=a;t.activity=this;}public Task getTask(){return task;}
  public DisplayContent getDisplayContent(){return task.display;}public Area getDisplayArea(){return area;}
  public Object findMainWindow(){for(var w:task.display.windows)if(w.mActivityRecord==this)return w;return null;}public boolean isFocusable(){return focusable;}public boolean isVisibleRequested(){return visible;}
 }
 static WindowState scan(DisplayContent d)throws Throwable{return (WindowState)XposedHelpers.invoke(d,"findFocusedWindow",new Class<?>[0]);}
 static boolean keys(WindowState w,boolean touch)throws Throwable{return (Boolean)XposedHelpers.invoke(w,"canReceiveKeys",touch?new Class<?>[]{boolean.class}:new Class<?>[0],touch?new Object[]{true}:new Object[0]);}
 static void nested(DisplayContent d){try{scan(d);}catch(Throwable e){throw new IllegalStateException(e);}}
 public static void main(String[] args)throws Throwable{
  HostEntry.install();check(XposedHelpers.hooks.size()==5,"actual install did not register pointer plus four window hooks");
  check(XposedHelpers.hooks.containsKey(WindowState.class.getDeclaredMethod("canReceiveKeys",boolean.class)),"boolean false scan path was not hooked");
  check(XposedBridge.deoptimized.size()==4,"exact four observed wrappers/callers were not deoptimized");
  check(XposedBridge.deoptimized.contains(DisplayContent.class.getDeclaredMethod("updateFocusedWindowLocked",int.class,boolean.class,int.class)),"update caller not deoptimized");
  check(XposedBridge.deoptimized.contains(DisplayContent.class.getDeclaredMethod("findFocusedWindowIfNeeded",int.class)),"intermediate scan wrapper not deoptimized");
  check(XposedBridge.deoptimized.contains(DisplayContent.class.getDeclaredMethod("lambda$new$3",WindowState.class)),"predicate caller not deoptimized");
  check(XposedBridge.deoptimized.contains(WindowState.class.getDeclaredMethod("canReceiveKeys")),"tiny eligibility wrapper not deoptimized");
  check(!XposedBridge.deoptimized.contains(WindowState.class.getDeclaredMethod("canReceiveKeys",boolean.class))&&!XposedBridge.deoptimized.contains(DisplayContent.class.getDeclaredMethod("unrelatedNativeMethod")),"unrelated method deoptimized");
  DisplayContent d=new DisplayContent();HostEntry.sDisplayContent=d;HostEntry.sZoomed=true;
  Task sideTask=new Task(),mainTask=new Task();sideTask.display=d;mainTask.display=d;mainTask.mode=1;mainTask.translucent=false;
  WindowState side=new WindowState(d,sideTask),main=new WindowState(d,mainTask);d.windows=new WindowState[]{side,main};
  var lease=new MiStepDesktopLaunchBridge.Lease();MiStepDesktopLaunchBridge.leases.put(sideTask,lease);
  check(keys(side,false),"eligibility changed outside focus scan");check(keys(side,true),"direct touch eligibility changed");
  check(scan(d)==main,"passive side above main retained actual window focus");check(HostEntry.sWindowFocusScanDisplay.get()==null,"normal scan leaked marker");
  main.eligible=false;check(scan(d)==null,"native false main became eligible");main.eligible=true;
  side.eligible=false;check(scan(d)==main,"native false side was force admitted");side.eligible=true;
  HostEntry.sZoomed=false;check(scan(d)==side,"inactive workspace changed native focus");HostEntry.sZoomed=true;
  HostEntry.sDisplayContent=null;check(scan(d)==side,"unknown workspace display changed native focus");HostEntry.sDisplayContent=d;
  DisplayContent foreign=new DisplayContent();WindowState foreignSide=new WindowState(foreign,sideTask);foreign.windows=new WindowState[]{foreignSide};
  check(scan(foreign)==foreignSide,"foreign display scan changed focus");
  d.windows=new WindowState[]{foreignSide,main};check(scan(d)==foreignSide,"foreign window changed focus in workspace scan");d.windows=new WindowState[]{side,main};
  lease.alive=false;check(scan(d)==side,"expired exact lease changed native focus");lease.alive=true;
  MiStepDesktopLaunchBridge.leases.clear();check(scan(d)==side,"unleased portrait mode6 was suppressed");MiStepDesktopLaunchBridge.leases.put(sideTask,lease);
  sideTask.bounds=new Rect(0,0,2460,1080);d.mCurrentFocus=null;check(scan(d)==main,"landscape passive cell cannot steal focus");
  sideTask.bounds=null;check(scan(d)==side,"unknown bounds were suppressed");sideTask.bounds=new Rect(0,0,1080,2460);
  d.booleanEligibility=true;d.touchEligibility=false;check(scan(d)==main,"direct boolean false path bypassed scan filter");
  main.eligible=false;check(scan(d)==null,"boolean false native rejection was overridden");main.eligible=true;
  d.touchEligibility=true;check(scan(d)==side,"native touch boolean true call changed during focus scan");
  side.eligible=false;d.windows=new WindowState[]{side};check(scan(d)==null,"native touch true false result force-admitted");side.eligible=true;d.windows=new WindowState[]{side,main};d.booleanEligibility=false;
  d.nested=()->{nested(foreign);check(HostEntry.sWindowFocusScanDisplay.get()==d,"nested foreign scan failed to restore outer marker");};
  check(scan(d)==main,"nested scan lost outer filtering");check(foreign.selected==foreignSide,"nested foreign focus suppressed");check(HostEntry.sWindowFocusScanDisplay.get()==null,"nested normal scan leaked marker");d.nested=null;
  Object outer=new Object();HostEntry.sWindowFocusScanDisplay.set(outer);check(scan(d)==main,"preexisting marker stopped native scan");check(HostEntry.sWindowFocusScanDisplay.get()==outer,"preexisting marker was not restored");HostEntry.sWindowFocusScanDisplay.remove();
  d.fail=true;boolean failed=false;try{scan(d);}catch(IllegalStateException e){failed=true;}check(failed,"native scan exception swallowed");check(HostEntry.sWindowFocusScanDisplay.get()==null,"native throw leaked marker");d.fail=false;
  foreign.fail=true;d.nested=()->nested(foreign);failed=false;try{scan(d);}catch(IllegalStateException e){failed=true;}check(failed,"nested native scan exception swallowed");check(HostEntry.sWindowFocusScanDisplay.get()==null,"nested throw leaked marker");foreign.fail=false;d.nested=null;
  side.fail=true;failed=false;try{scan(d);}catch(IllegalStateException e){failed=true;}check(failed,"native eligibility exception swallowed");check(HostEntry.sWindowFocusScanDisplay.get()==null,"eligibility throw leaked marker");side.fail=false;
  int logs=HostEntry.logs.size();sideTask.fail=true;check(scan(d)==side,"uncertain lookup changed native true");check(HostEntry.logs.size()==logs+1,"uncertain lookup lacked existing hook log");sideTask.fail=false;
  lease.fail=true;check(scan(d)==side,"uncertain lease changed native true");lease.fail=false;
  check(keys(side,false),"finished scans changed later native eligibility");
  Area area=new Area();area.selected=mainTask;Activity oldApp=new Activity(sideTask,area),mainApp=new Activity(mainTask,area);
  d.mFocusedApp=oldApp;d.nativeCutoff=true;d.mCurrentFocus=side;
  check(scan(d)==main,"native Z cutoff kept stale passive Activity focus");
  check(d.mFocusedApp==mainApp && d.appChanges==1,"native setFocusedApp did not reconcile Activity/InputMonitor contract");
  check(scan(d)==main && d.appChanges==1,"stable main Activity was repeatedly reset");
  d.mFocusedApp=oldApp;lease.alive=false;check(scan(d)==side && d.mFocusedApp==oldApp,"expired lease redirected Activity focus");lease.alive=true;
  d.mFocusedApp=oldApp;area.selected=null;check(scan(d)==null && d.mFocusedApp==oldApp,"missing native root invented focus");area.selected=mainTask;
  mainApp.visible=false;check(scan(d)==null && d.mFocusedApp==oldApp,"invisible target received Activity focus");mainApp.visible=true;
  mainApp.focusable=false;check(scan(d)==null && d.mFocusedApp==oldApp,"nonfocusable target received Activity focus");mainApp.focusable=true;
  area.selected=sideTask;check(scan(d)==null && d.mFocusedApp==oldApp,"passive fallback received native Activity focus");area.selected=mainTask;
  sideTask.bounds=new Rect(0,0,2460,1080);check(scan(d)==main && d.mFocusedApp==mainApp,"landscape passive Activity is reconciled to main");sideTask.bounds=new Rect(0,0,1080,2460);d.mFocusedApp=oldApp;
  HostEntry.sZoomed=false;check(scan(d)==side && d.mFocusedApp==oldApp,"inactive workspace redirected Activity focus");HostEntry.sZoomed=true;
  int previous=d.appChanges;mainTask.display=foreign;check(scan(d)==null && d.appChanges==previous,"cross-display Activity target admitted");mainTask.display=d;
  d.mFocusedApp=oldApp;check(scan(d)==main && d.appChanges==previous+1,"valid later scan failed native repair");
  Task upperTask=new Task();upperTask.display=d;upperTask.bounds=new Rect(0,0,1251,2519);
  Activity upperApp=new Activity(upperTask,area);WindowState upper=new WindowState(d,upperTask);upper.mActivityRecord=upperApp;
  HostEntry.sZoomTarget=new HostEntry.ZoomTarget();HostEntry.sZoomTarget.display=d;
  MiStepDesktopLaunchBridge.topTask=upperTask;d.windows=new WindowState[]{upper,main};d.nativeCutoff=false;
  d.mCurrentFocus=upper;d.mFocusedApp=mainApp;
  check(scan(d)==main,"stale TOP window retained native focus over main app");
  check(d.mFocusedApp==mainApp,"IME scan stole main Activity focus");
  check(keys(upper,true),"explicit TOP user touch was rejected");
  synchronized(HostEntry.sZoomTarget.globalLock){XposedHelpers.invoke(upper,"handleTapOutsideFocusInsideSelf",new Class<?>[0]);}
  check(d.mFocusedApp==upperApp && scan(d)==upper,"explicit TOP tap failed native Activity/window selection");
  d.mCurrentFocus=upper;check(MiStepImePolicy.isFocusedTopWindow(upper,d,MiStepDesktopLaunchBridge::ownsTopTask),"selected portrait interactive task could not control IME");
  d.setFocusedApp(mainApp);check(scan(d)==main,"later main touch could not release TOP window");
  check(!MiStepImePolicy.isFocusedTopWindow(upper,d),"stale TOP still controlled main IME");
  MiStepDesktopLaunchBridge.topTask=null;
  synchronized(HostEntry.sZoomTarget.globalLock){XposedHelpers.invoke(upper,"handleTapOutsideFocusInsideSelf",new Class<?>[0]);}
  check(d.mFocusedApp==mainApp,"unleased TOP pointer changed Activity focus");MiStepDesktopLaunchBridge.topTask=upperTask;
  XposedHelpers.invoke(upper,"handleTapOutsideFocusInsideSelf",new Class<?>[0]);check(d.mFocusedApp==mainApp,"touch without WM lock changed focus");
  upperApp.focusable=false;check(!MiStepImePolicy.admitTopTouch(upper,d,t->true),"nonfocusable TOP admitted");upperApp.focusable=true;
  upperApp.visible=false;check(!MiStepImePolicy.admitTopTouch(upper,d,t->true),"hidden TOP admitted");upperApp.visible=true;
  check(!MiStepImePolicy.admitTopTouch(upper,foreign,t->true),"foreign TOP touch admitted");
  upperTask.mode=1;check(!MiStepImePolicy.admitTopTouch(upper,d,t->true),"returned main task admitted");upperTask.mode=6;
  upperTask.mode=1;check(!MiStepImePolicy.isInactiveTopWindow(upper,d,t->true),"returned mode1 suppressed by lingering registry");upperTask.mode=6;
  check(!MiStepImePolicy.isInactiveTopWindow(upper,foreign,t->true),"foreign TOP window suppressed");
  var dispatcher=new PointerEventDispatcher();var event=new android.view.MotionEvent();d.mFocusedApp=mainApp;d.mCurrentFocus=main;
  XposedHelpers.invoke(dispatcher,"onInputEvent",new Class<?>[]{android.view.MotionEvent.class},event);check(d.mFocusedApp==upperApp&&d.mCurrentFocus==upper,"native pointer DOWN failed TOP focus before edge back");
  check(d.localFocusDisplay==-1,"local pointer update preselected default display and skipped native focus scan");
  d.mFocusedApp=mainApp;d.mCurrentFocus=main;event.y=700;XposedHelpers.invoke(dispatcher,"onInputEvent",new Class<?>[]{android.view.MotionEvent.class},event);check(d.mFocusedApp==mainApp,"main pointer stole TOP focus");
  event.y=300;event.action=1;XposedHelpers.invoke(dispatcher,"onInputEvent",new Class<?>[]{android.view.MotionEvent.class},event);check(d.mFocusedApp==mainApp,"UP selected TOP");event.action=0;
  event.count=2;XposedHelpers.invoke(dispatcher,"onInputEvent",new Class<?>[]{android.view.MotionEvent.class},event);check(d.mFocusedApp==mainApp,"secondary pointer selected TOP");event.count=1;
  event.display=1;XposedHelpers.invoke(dispatcher,"onInputEvent",new Class<?>[]{android.view.MotionEvent.class},event);check(d.mFocusedApp==mainApp,"foreign display selected TOP");event.display=0;
  event.source=8194;XposedHelpers.invoke(dispatcher,"onInputEvent",new Class<?>[]{android.view.MotionEvent.class},event);check(d.mFocusedApp==mainApp,"mouse selected TOP");event.source=4098;
  HostEntry.sZoomed=false;XposedHelpers.invoke(dispatcher,"onInputEvent",new Class<?>[]{android.view.MotionEvent.class},event);check(d.mFocusedApp==mainApp,"inactive host selected TOP");HostEntry.sZoomed=true;
  MiStepDesktopLaunchBridge.topTask=null;XposedHelpers.invoke(dispatcher,"onInputEvent",new Class<?>[]{android.view.MotionEvent.class},event);check(d.mFocusedApp==mainApp,"missing lease selected TOP");
  System.out.println("actual installed WM window-focus callbacks checks="+checks+" passed");
 }
}'''
}
sources = []
for name, body in FILES.items():
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf-8')
    sources.append(str(path))
sources.append(str(ROOT / 'tools/test-stubs/android/graphics/Rect.java'))
sources.extend(str(SRC / (name + '.java')) for name in
               ['MiStepTopPointerFocus', 'MiStepImePolicy', 'MiStepSideRoleChange', 'ZoomChannelState', 'ZoomCommand', 'ZoomAnimCommand'])
subprocess.run([str(JDK / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(OUT), *sources], check=True)
subprocess.run([str(JDK / 'java.exe'), '-cp', str(OUT), 'com.jiusis.mistep.ImeWindowFocusTest'], check=True)
