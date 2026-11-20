"""Production HostEntry and exact registry together, with fake native WM."""
import ast, os, re, subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
JDK = Path(os.environ['MISTEP_JAVA_BIN'])
phone = ROOT/'tools/phone-rotation-jvm/run.py'
ns = {'__file__': str(phone), '__name__': 'fixture'}
exec(compile(phone.read_text(encoding='utf-8').split('sources=[]')[0], str(phone), 'exec'), ns)
files = ns['FILES'].copy()
animation = ROOT/'tools/rotation-animation-jvm/run.py'
animation_ns = {'__file__': str(animation), '__name__': 'fixture'}
exec(compile(animation.read_text(encoding='utf-8').split('sources=[]')[0],
             str(animation), 'exec'), animation_ns)
files.update(animation_ns['ANIMATION_FILES'])
files['android/content/res/Configuration.java'] = animation_ns['FILES']['android/content/res/Configuration.java']
files['com/android/server/wm/WindowState.java'] = files['com/android/server/wm/WindowState.java'].replace(
 'public boolean visible=true;', '''public boolean visible=true,drawn=true;
 public final WindowAnimator mWinAnimator=new WindowAnimator();
 public boolean isDrawn(){return drawn;}
 public static class WindowAnimator{public boolean shown;public boolean getShown(){return shown;}}''')
tree = ast.parse((ROOT/'tools/desktop-route-jvm/run.py').read_text(encoding='utf-8-sig'))
desktop = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'files' for t in n.targets))
for name in ['android/os/IBinder.java', 'android/os/Binder.java', 'android/os/Bundle.java', 'android/os/SystemClock.java', 'android/content/Intent.java', 'com/android/server/wm/WindowContainer.java']:
 files[name] = desktop[name]
files['android/os/Bundle.java'] = files['android/os/Bundle.java'].replace('Map<String,Object>m=', 'public Bundle getBundle(String k){return (Bundle)m.get(k);} public void putBundle(String k,Bundle v){m.put(k,v);} public float getFloat(String k,float d){return m.containsKey(k)?(Float)m.get(k):d;} Map<String,Object>m=')
for name in ['Handler.java', 'XposedBridge.java', 'Log.java', 'WindowManager.java']:
 text = (ROOT/'tools/rotation-jvm'/name).read_text()
 package = re.search(r'package ([\w.]+);', text).group(1)
 files[package.replace('.', '/')+'/'+name] = text
files['android/util/Log.java'] = 'package android.util; public class Log { public static java.util.List<String> messages=new java.util.ArrayList<>(); public static int i(String tag,String message){messages.add(message);return 0;} }'
files['com/android/server/wm/ActivityRecord.java'] = 'package com.android.server.wm; public class ActivityRecord {}'
files['de/robv/android/xposed/XC_MethodHook.java'] = r'''package de.robv.android.xposed;
public class XC_MethodHook {
 public class Unhook {}
 public static class MethodHookParam {
  public Object thisObject,result;public Object[] args;public boolean failed;public boolean returned;
  java.util.Map<String,Object> extras=new java.util.HashMap<>();
  public Object getResult(){return result;}public void setResult(Object r){result=r;returned=true;}
  public boolean hasThrowable(){return failed;}public Object getObjectExtra(String k){return extras.get(k);}
  public void setObjectExtra(String k,Object v){extras.put(k,v);}
 }
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
 protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
 public void before(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}
 public void after(MethodHookParam p)throws Throwable{afterHookedMethod(p);}
}'''
files['de/robv/android/xposed/XposedBridge.java'] = files['de/robv/android/xposed/XposedBridge.java'].replace(
 'public static int errors;', '''public static int errors;
 public static java.util.Map<String,XC_MethodHook> hooks=new java.util.HashMap<>();
 public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> type,String name,XC_MethodHook hook){hooks.put(name,hook);return java.util.Collections.emptySet();}
 ''')
files['com/jiusis/mistep/PhoneWorkspaceTest.java'] = r'''package com.jiusis.mistep;
import android.os.*;import android.content.Intent;import android.content.res.Configuration;import android.graphics.Rect;import java.lang.reflect.*;import java.util.*;import com.android.server.wm.WindowContainer;
public class PhoneWorkspaceTest {
 static HostEntry host=new HostEntry();static int checks;static Binder owner;static long sequence;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static Field f(String n)throws Exception{Field f=HostEntry.class.getDeclaredField(n);f.setAccessible(true);return f;}
 static Object call(String n,Class<?>[]t,Object...a)throws Exception{Method m=HostEntry.class.getDeclaredMethod(n,t);m.setAccessible(true);return m.invoke(host,a);}
 static class Token{IBinder binder=new Binder();IBinder asBinder(){return binder;}}
 static class Info{int taskId,userId,displayId;Token token=new Token();Info(int id){taskId=id;}}
 static class Controller{boolean useShellTransitionsRotation(){return true;}}
 static class WindowToken{Display mDisplayContent;WindowToken(Display d){mDisplayContent=d;}Object asActivityRecord(){return null;}}
 static class Task extends FakeRotationActivity.Task{int mTaskId,mUserId,mode,queries;Display display;Activity top;Info info;Configuration config=new Configuration();List<Activity> children=new ArrayList<>();
 Task(Display d,int id,int mode){display=d;mTaskId=id;this.mode=mode;info=new Info(id);bounds=mode==6?new Rect(0,0,3000,1725):new Rect(0,0,1080,2460);WindowContainer.tasks.put(info.token.binder,this);}
 Display getDisplayContent(){return display;}int getWindowingMode(){return mode;}boolean isForceTranslucent(){return mode==6;}Activity topRunningActivity(){return top;}Info getTaskInfo(){queries++;return info;}public Rect getBounds(){return bounds;}public Configuration getConfiguration(){return config;}void forAllActivities(java.util.function.Consumer<Object> visitor,boolean topToBottom){for(Activity a:new ArrayList<>(children))visitor.accept(a);}}
 static class LocalState extends FakeRotationActivity.State{Configuration mRotatedOverrideConfiguration;}
 static class Activity extends FakeRotationActivity{Display mDisplayContent;PhoneWorkspaceTest.Task task;int request=2,starts,leashes;boolean finishing,mRemovingFromDisplay,entryKept,returnKept,scopeConfig,attached=true,closing;boolean visible,startingOnly;Controller mTransitionController=new Controller();Configuration config=new Configuration();
 public boolean inTransition(){return closing;}
 boolean isVisible(){return visible;}
 Object findMainWindow(boolean includeStarting){return includeStarting||!startingOnly?window:null;}
 Activity(PhoneWorkspaceTest.Task t){task=t;t.top=this;t.children.add(this);mDisplayContent=t.display;}public PhoneWorkspaceTest.Task getTask(){return task;}Display getDisplayContent(){return mDisplayContent;}int getRootTaskId(){return task.mTaskId;}boolean isActivityTypeHome(){return false;}boolean inMultiWindowMode(){return task.mode==6||task.mode==5;}int getOverrideOrientation(){return request;}Configuration getConfiguration(){return config;}public PhoneWorkspaceTest.Task getParent(){return attached?task:null;}boolean hasFixedRotationTransform(){return mFixedRotationTransformState!=null;}
 void install(){LocalState s=new LocalState();s.mRotatedOverrideConfiguration=new Configuration(config);s.mAssociatedTokens.add(this);mFixedRotationTransformState=s;}
 void finishFixedRotationTransform(){finishFixedRotationTransform(null);}
 public void finishFixedRotationTransform(Runnable r){State state=mFixedRotationTransformState;super.finishFixedRotationTransform(r);for(FakeRotationActivity token:state.mAssociatedTokens)token.fixed=null;}
 boolean keep(Configuration previous){try{return (Boolean)call(@keepTopActivityForRotationConfig@,new Class[]{Object.class,int.class,Object.class},this,0x1580,previous);}catch(Exception e){throw new RuntimeException(e);}}
 public void onConfigurationChanged(Configuration c){Configuration previous=config;super.onConfigurationChanged(c);config=new Configuration(c);returnKept=keep(previous);}
 void applyFixedRotationTransform(Object i,Object frames,Configuration c){Configuration previous=config;try{scopeConfig=call(@ownedTopRotationConfigurationLocked@,new Class[]{Object.class,Object.class},this,task)==c;}catch(Exception e){throw new RuntimeException(e);}starts++;config=c;install();entryKept=keep(previous);}
 Object getOrCreateFixedRotationLeash(Object t){if(fixed==null){leashes++;fixed=new android.view.SurfaceControl();fixed.parent=parentSurface;}return fixed;}}
 static class Rotation{Display mDisplayContent;int proposed,lockedRotation;boolean frozen;int getRotation(){return 0;}boolean isRotationFrozen(){return frozen;}int rotationForOrientation(int o,int previous){if(o==0)return 1;if(o==8)return 3;if(o==1)return 0;return frozen?lockedRotation:proposed;}}
 static class Service{Object mGlobalLock=new Object();}
 static class Display{PhoneRotationTest.Info info=new PhoneRotationTest.Info();Rotation mDisplayRotation=new Rotation();Service mWmService=new Service();Activity top;Display(){mDisplayRotation.mDisplayContent=this;}Object getDisplayInfo(){return info;}Activity topRunningActivity(){return top;}void startFixedRotationTransform(Activity a,int r){a.starts++;a.install();}}
 static Bundle row(Activity a){Bundle b=new Bundle();b.putBinder(@task@,a.task.info.token.binder);b.putInt(@taskId@,a.task.mTaskId);b.putInt(@userId@,a.task.mUserId);b.putInt(@displayId@,0);return b;}
 static void register(Activity main,Activity side,Activity top){owner=new Binder();Bundle b=new Bundle();b.putBinder(@owner@,owner);b.putLong(@sequence@,++sequence);b.putParcelableArrayList(@rows@,new ArrayList<>(List.of(row(side))));b.putBundle(@rotationMain@,row(main));b.putBundle(@topInput@,row(top));check(MiStepDesktopLaunchBridge.update(new Intent(@register@).putExtra(@state@,b)),@registry accepted@);}
 static void refresh(Display d)throws Exception{call(@refreshMiStepTaskRotationLocked@,new Class[]{Object.class},d);}
 static boolean retain(Object a)throws Exception{return (Boolean)call(@retainTaskRotationOnNativeFinishLocked@,new Class[]{Object.class},a);}
 static de.robv.android.xposed.XC_MethodHook.MethodHookParam param(Object a,Object result,Object...args){var p=new de.robv.android.xposed.XC_MethodHook.MethodHookParam();p.thisObject=a;p.result=result;p.args=args;return p;}
 public static void main(String[]args)throws Throwable{
  Display d=new Display();Activity main=new Activity(new Task(d,7,1)),side=new Activity(new Task(d,8,6)),top=new Activity(new Task(d,9,6));side.task.bounds=new Rect(0,0,1080,2460);top.request=0;d.top=top;
  f(@sDisplayContent@).set(null,d);f(@sFullscreenRotationActive@).set(null,true);f(@sHeldDisplayRotation@).set(null,0);f(@sZoomed@).set(null,true);f(@sTaskRotationResolver@).set(null,Rotation.class.getDeclaredMethod(@rotationForOrientation@,int.class,int.class));register(main,side,top);
  int messages=android.util.Log.messages.size();check(!retain(new WindowToken(d))&&android.util.Log.messages.size()==messages,@ordinary IME WindowToken finishes natively without an Activity getTask error@);
  check(MiStepDesktopLaunchBridge.rotationActivities(d).size()==3,@all zero-transform active roles found@);check(main.task.queries+side.task.queries+top.task.queries==0,@enumeration avoids TaskInfo aggregation@);
  refresh(d);check(main.starts==0&&side.starts==0&&top.starts==0,@portrait requires no local state@);
  d.mDisplayRotation.proposed=1;refresh(d);check(main.starts==1&&side.starts==1&&top.starts==1,@first sensor turn refreshes main side TOP@);check(top.config.orientation==1&&top.config.windowConfiguration.bounds.width()==1725&&top.config.windowConfiguration.bounds.height()==3000,@TOP real portrait viewport in landscape carrier@);
  check(top.entryKept,@first native synchronous config dispatch keeps TOP before lease registration@);
  check(top.scopeConfig,@first resolve can read exact local config before state/lease install@);
  check(!(Boolean)call(@keepTopActivityForRotationConfig@,new Class[]{Object.class,int.class,Object.class},top,0x1580,new Configuration(top.config)),@same-direction unrelated relaunch stays native@);
  check(!(Boolean)call(@keepTopActivityForRotationConfig@,new Class[]{Object.class,int.class,Object.class},main,0x1580,new Configuration()),@main Activity not covered by TOP relaunch guard@);
  check(main.mWmService.animators.size()==1&&top.mWmService.animators.size()==1&&main.window.mSurfaceAnimator.starts==0,@main and TOP start dedicated native carrier animation@);
  Object state=top.mFixedRotationTransformState;refresh(d);check(top.mFixedRotationTransformState==state&&top.starts==1,@unchanged update idempotent@);check(retain(main)&&retain(side)&&retain(top),@native finish preserves exact owners while TOP focused@);
  check(main.mWmService.animators.size()==1&&top.mWmService.animators.size()==1,@unchanged rotation never restarts animation@);
  var activeAnimator=top.mWmService.lastAnimator;var activeAdapter=activeAnimator.current;activeAnimator.current=new com.android.server.wm.LocalAnimationAdapter(null,null);
  d.mDisplayRotation.proposed=3;call(@applyTopRotationLocked@,new Class[]{Object.class,Object.class,Object.class,String.class},d,top,top.task,@foreign adapter test@);
  check(top.mFixedRotationTransformState==state&&top.starts==1&&top.finishes==0,@unreleased foreign animation prevents new transform overwrite@);
  d.mDisplayRotation.proposed=0;call(@applyTopRotationLocked@,new Class[]{Object.class,Object.class,Object.class,String.class},d,top,top.task,@foreign adapter return test@);
  check(top.mFixedRotationTransformState==state&&top.mWmService.animators.size()==1,@blocked portrait release cannot animate an unfinished transform@);
  activeAnimator.current=activeAdapter;d.mDisplayRotation.proposed=1;
  Activity nextMain=new Activity(new Task(d,10,1));register(nextMain,side,top);refresh(d);check(main.mFixedRotationTransformState==null&&f(@sMiStepFixedRotationApp@).get(null)==nextMain&&retain(nextMain),@departing owner cleaned before confirmed new main@);main=nextMain;
  Handler handler=new Handler();f(@sZoomBridgeHandler@).set(null,handler);for(int i=0;i<4;i++)call(@queueWorkspaceRotationRefresh@,new Class[]{Object.class},d.mDisplayRotation);check(handler.pending.size()==1,@sensor proposals coalesced@);d.mDisplayRotation.proposed=3;handler.pending.remove(0).run();check(top.starts==2&&top.finishes==1&&top.config.windowConfiguration.rotation==3,@reverse turn replaces owned local state@);
  d.mDisplayRotation.frozen=true;d.mDisplayRotation.lockedRotation=3;d.mDisplayRotation.proposed=0;refresh(d);check(top.starts==2&&retain(top),@native user rotation lock respected@);d.mDisplayRotation.frozen=false;refresh(d);check(main.mFixedRotationTransformState==null&&side.mFixedRotationTransformState==null&&top.mFixedRotationTransformState==null,@portrait return releases all owned transforms@);
  check(top.returnKept,@native finish config dispatch keeps live TOP after state reset@);
  Configuration oldPortrait=new Configuration();oldPortrait.windowConfiguration.setDisplayRotation(3);
  check(!(Boolean)call(@keepTopActivityForRotationConfig@,new Class[]{Object.class,int.class,Object.class},top,0x1580,oldPortrait),@release guard cannot leak beyond synchronous native finish@);
  check(main.mWmService.animators.size()==3&&top.mWmService.animators.size()==3,@portrait return also animates through native WM@);
  d.mDisplayRotation.proposed=1;refresh(d);FakeRotationActivity.State foreign=new FakeRotationActivity.State();top.mFixedRotationTransformState=foreign;int finishes=top.finishes;refresh(d);check(top.mFixedRotationTransformState==foreign&&top.finishes==finishes&&!retain(top),@foreign native state never finished@);
  top.mFixedRotationTransformState=null;refresh(d);top.task.mode=1;call(@applyMiStepFixedRotationLocked@,new Class[]{Object.class,Object.class,String.class},d,top,@role test@);check(!((Map<?,?>)f(@sTopRotationBounds@).get(null)).containsKey(top),@TOP to main drops local viewport ownership@);
  top.task.mode=6;main.task.mUserId=10;check(!MiStepDesktopLaunchBridge.rotationActivities(d).contains(main),@wrong user rejects stale binding@);main.task.mUserId=0;side.task.display=new Display();check(!MiStepDesktopLaunchBridge.rotationActivities(d).contains(side),@foreign display rejected@);side.task.display=d;side.task.mode=5;check(!MiStepDesktopLaunchBridge.rotationActivities(d).contains(side),@native freeform remains separate@);owner.alive=false;check(MiStepDesktopLaunchBridge.rotationActivities(d).isEmpty(),@dead owner rejected@);
  call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@fixture cleanup@);
  Display pages=new Display();Activity pageMain=new Activity(new Task(pages,70,1)),pageSide=new Activity(new Task(pages,80,6)),first=new Activity(new Task(pages,90,6));pageSide.task.bounds=new Rect(0,0,1080,2460);first.request=0;pages.top=first;pages.mDisplayRotation.proposed=1;
  f(@sDisplayContent@).set(null,pages);register(pageMain,pageSide,first);refresh(pages);Object shared=first.mFixedRotationTransformState;check(shared!=null,@first TOP page owns native state@);
  Activity second=new Activity(first.task);second.config=new Configuration(first.config);second.mFixedRotationTransformState=first.mFixedRotationTransformState;second.mFixedRotationTransformState.mAssociatedTokens.add(second);pages.top=second;
  check(Boolean.TRUE.equals(call(@applyMiStepFixedRotationLocked@,new Class[]{Object.class,Object.class,String.class},pages,second,@native linked page@)),@new TOP page adopts only its task's already-owned shared state@);
  check(second.starts==0&&second.mFixedRotationTransformState==shared,@shared native link must not create another transform or animation@);
  check(second.leashes==1&&second.fixed!=null,@linked TOP page needs its own real native fixed-rotation leash@);
  refresh(pages);check(second.leashes==1,@unchanged TOP refresh reuses the existing fixed leash@);
  check(call(@ownedTopRotationConfigurationLocked@,new Class[]{Object.class,Object.class},second,second.task)==((LocalState)shared).mRotatedOverrideConfiguration,@linked page resolves from the owned local configuration@);
  call(@applyMiStepFixedRotationLocked@,new Class[]{Object.class,Object.class,String.class},pages,first,@old page configuration@);
  check(first.mFixedRotationTransformState==shared&&second.mFixedRotationTransformState==shared&&first.finishes==0,@old page callback must not finish the new page's shared transform@);
  first.finishing=true;check(retain(first)&&retain(second),@finishing old page cannot cancel current TOP's shared state@);first.finishing=false;
  first.task.top=first;pages.top=first;refresh(pages);check(first.starts==1&&first.mFixedRotationTransformState==shared&&retain(second),@Back reuses shared native state without landscape flash@);
  pages.mDisplayRotation.proposed=0;refresh(pages);check(first.mFixedRotationTransformState==null&&second.mFixedRotationTransformState==null,@sensor return finishes the entire native association@);
  check(!((Map<?,?>)f(@sTopRotationBounds@).get(null)).containsKey(first)&&!((Map<?,?>)f(@sTopRotationBounds@).get(null)).containsKey(second),@group return clears every TOP lease@);
  pages.mDisplayRotation.proposed=1;refresh(pages);shared=first.mFixedRotationTransformState;second.mFixedRotationTransformState=first.mFixedRotationTransformState;second.mFixedRotationTransformState.mAssociatedTokens.add(second);first.task.top=second;pages.top=second;
  call(@applyMiStepFixedRotationLocked@,new Class[]{Object.class,Object.class,String.class},pages,second,@second native linked page@);
  f(@sZoomed@).set(null,false);f(@sFullscreenRotationActive@).set(null,false);call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@host exit@);
  check(first.mFixedRotationTransformState==null&&second.mFixedRotationTransformState==null&&((Map<?,?>)f(@sTaskRotations@).get(null)).isEmpty(),@exit clears all linked page states and ownership@);
  check(first.config.densityDpi==first.task.config.densityDpi&&second.config.densityDpi==first.task.config.densityDpi,@exit sends parent density back to every linked page@);
  check(call(@ownedTopRotationConfigurationLocked@,new Class[]{Object.class,Object.class},second,second.task)==null,@portrait density scope ends on exit@);
  f(@sFullscreenRotationActive@).set(null,true);f(@sZoomed@).set(null,true);pages.mDisplayRotation.proposed=1;refresh(pages);
  Activity delayed=second;Object delayedState=delayed.mFixedRotationTransformState;var delayedAnimator=delayed.mWmService.lastAnimator;var delayedAdapter=delayedAnimator.current;delayedAnimator.current=new com.android.server.wm.LocalAnimationAdapter(null,null);
  f(@sFullscreenRotationActive@).set(null,false);f(@sZoomed@).set(null,false);call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@busy host exit@);
  check(delayed.mFixedRotationTransformState==delayedState,@exit never steals a foreign live animation@);
  handler.pending.clear();call(@queueInactiveRotationCleanupLocked@,new Class[]{Object.class},pages);check(handler.pending.size()==1,@unfinished exit schedules bounded native cleanup@);
  delayedAnimator.current=delayedAdapter;handler.pending.remove(0).run();check(delayed.mFixedRotationTransformState==null&&handler.pending.isEmpty(),@native animation completion allows deferred exit state/config restoration@);
  call(@queueInactiveRotationCleanupLocked@,new Class[]{Object.class},pages);check(handler.pending.isEmpty(),@clean exit does not poll@);
  f(@sFullscreenRotationActive@).set(null,true);f(@sZoomed@).set(null,true);refresh(pages);delayedState=delayed.mFixedRotationTransformState;delayedAnimator=delayed.mWmService.lastAnimator;delayedAdapter=delayedAnimator.current;delayedAnimator.current=new com.android.server.wm.LocalAnimationAdapter(null,null);
  f(@sFullscreenRotationActive@).set(null,false);f(@sZoomed@).set(null,false);call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@new-session fence@);call(@queueInactiveRotationCleanupLocked@,new Class[]{Object.class},pages);
  f(@sFullscreenRotationActive@).set(null,true);f(@sZoomed@).set(null,true);handler.pending.remove(0).run();check(delayed.mFixedRotationTransformState==delayedState&&handler.pending.isEmpty(),@new active session cancels old exit cleanup@);
  delayedAnimator.current=delayedAdapter;f(@sFullscreenRotationActive@).set(null,false);f(@sZoomed@).set(null,false);call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@final cleanup@);
  f(@sFullscreenRotationActive@).set(null,true);f(@sZoomed@).set(null,true);refresh(pages);delayedState=delayed.mFixedRotationTransformState;delayedAnimator=delayed.mWmService.lastAnimator;delayedAdapter=delayedAnimator.current;delayedAnimator.current=new com.android.server.wm.LocalAnimationAdapter(null,null);
  f(@sFullscreenRotationActive@).set(null,false);f(@sZoomed@).set(null,false);call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@busy bound@);call(@queueInactiveRotationCleanupLocked@,new Class[]{Object.class},pages);
  int retries=0;while(!handler.pending.isEmpty()&&retries<4){handler.pending.remove(0).run();retries++;}check(retries==3&&handler.pending.isEmpty()&&delayed.mFixedRotationTransformState==delayedState,@exit cleanup stops after three attempts and never steals native ownership@);
  delayedAnimator.current=delayedAdapter;call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@bounded fixture cleanup@);
  f(@sFullscreenRotationActive@).set(null,true);f(@sZoomed@).set(null,true);
  Display independent=new Display();Activity independentMain=new Activity(new Task(independent,170,1)),independentSide=new Activity(new Task(independent,180,6)),bottom=new Activity(new Task(independent,190,6));independentSide.task.bounds=new Rect(0,0,1080,2460);bottom.task.config.densityDpi=480;independent.top=bottom;independent.mDisplayRotation.proposed=1;
  f(@sDisplayContent@).set(null,independent);register(independentMain,independentSide,bottom);refresh(independent);
  Object bottomState=bottom.mFixedRotationTransformState;int portraitDpi=bottom.config.densityDpi;
  Activity foreground=new Activity(bottom.task);independent.top=foreground;
  call(@applyMiStepFixedRotationLocked@,new Class[]{Object.class,Object.class,String.class},independent,foreground,@independent foreground@);
  check(foreground.mFixedRotationTransformState!=null&&foreground.mFixedRotationTransformState!=bottomState,@independent native page states are both supported@);
  call(@applyMiStepFixedRotationLocked@,new Class[]{Object.class,Object.class,String.class},independent,bottom,@bottom configuration callback@);
  check(bottom.mFixedRotationTransformState==bottomState&&bottom.config.orientation==1&&bottom.config.densityDpi==portraitDpi&&foreground.config.densityDpi==portraitDpi,@interactive Back bottom keeps portrait layout and density before commit@);
  check(retain(bottom)&&retain(foreground),@native lifetime keeps independent pages of the same live TOP task@);
  call(@installFixedMainRotationHooks@,new Class[]{ClassLoader.class},PhoneWorkspaceTest.class.getClassLoader());
  var hooks=de.robv.android.xposed.XposedBridge.hooks;var update=hooks.get(@updateReportedConfigurationAndSend@);var decide=hooks.get(@shouldRelaunchLocked@);var relaunch=hooks.get(@relaunchActivityLocked@);var freeze=hooks.get(@startFreezingScreenLocked@);
  check(update!=null&&decide!=null&&relaunch!=null&&freeze!=null,@actual production hooks installed@);
  var prepare=hooks.get(@prepareSurfaces@);
  check(prepare!=null,@native Activity surface preparation is hooked@);
  var preparingBottom=param(bottom,null);var carrier=bottom.fixed;
  carrier.shown=false;bottom.visible=true;
  prepare.after(preparingBottom);
  check(!carrier.shown,@native window surface still hidden: leave its carrier hidden@);
  bottom.window.mWinAnimator.shown=true;bottom.window.drawn=false;
  prepare.after(preparingBottom);
  check(!carrier.shown,@an undrawn real window cannot reveal the carrier@);
  bottom.window.drawn=true;bottom.startingOnly=true;
  prepare.after(preparingBottom);
  check(!carrier.shown,@a starting window is not live page content@);
  bottom.startingOnly=false;bottom.visible=false;
  prepare.after(preparingBottom);
  check(!carrier.shown,@animation-only showing cannot expose an invisible Activity@);
  bottom.visible=true;bottom.closing=true;
  prepare.after(preparingBottom);
  check(!carrier.shown,@Shell transition still owns the hidden carrier@);
  bottom.closing=false;bottom.mFixedRotationTransformState=new LocalState();
  prepare.after(preparingBottom);
  check(!carrier.shown,@replaced native state cannot reuse the previous lease@);
  bottom.mFixedRotationTransformState=(FakeRotationActivity.State)bottomState;
  prepare.after(preparingBottom);
  check(carrier.shown&&bottom.fixed==carrier&&bottom.task.top==foreground,
        @Back preview reveals the same carrier while the old page is still on top@);
  carrier.shown=false;bottom.finishing=true;
  prepare.after(preparingBottom);
  check(!carrier.shown,@finishing page cannot be shown by the local visibility repair@);
  bottom.finishing=false;carrier.valid=false;
  prepare.after(preparingBottom);
  check(!carrier.shown,@invalid rotation carrier is left to the native lifecycle@);
  carrier.valid=true;bottom.visible=false;bottom.window.mWinAnimator.shown=false;
  var outside=param(bottom,null,false,0x1580);relaunch.before(outside);check(Boolean.FALSE.equals(outside.args[0]),@no preservation outside native configuration scope@);
  Configuration oldLayout=new Configuration(bottom.task.config);
  var outer=param(bottom,null);update.before(outer);
  var nativeDecision=param(bottom,true,0x1580,oldLayout);decide.after(nativeDecision);
  check(Boolean.TRUE.equals(nativeDecision.result)&&!nativeDecision.returned,@native Activity relaunch decision stays true so old pixel Views rebuild@);
  var restart=param(bottom,null,false,0x1580);relaunch.before(restart);check(Boolean.TRUE.equals(restart.args[0])&&!restart.returned,@independent bottom relaunch rebuilds with real Window preserved@);
  var localFreeze=param(bottom,null,0x1580);freeze.before(localFreeze);check(localFreeze.returned,@OS2 local rotation does not freeze physical display@);
  var locale=param(bottom,null,false,0x1584);relaunch.before(locale);check(Boolean.FALSE.equals(locale.args[0]),@locale plus rotation still uses native Window lifetime@);
  var nested=param(bottom,null);update.before(nested);restart=param(bottom,null,false,0x1580);relaunch.before(restart);check(Boolean.FALSE.equals(restart.args[0]),@nested unrelated config cannot borrow outer permission@);update.after(nested);
  restart=param(bottom,null,false,0x1580);relaunch.before(restart);check(Boolean.TRUE.equals(restart.args[0]),@nested return restores precise outer configuration scope@);
  Object original=bottom.mFixedRotationTransformState;bottom.mFixedRotationTransformState=new LocalState();restart=param(bottom,null,false,0x1580);relaunch.before(restart);check(Boolean.FALSE.equals(restart.args[0]),@native state replacement invalidates preservation@);bottom.mFixedRotationTransformState=(FakeRotationActivity.State)original;
  outer.failed=true;update.after(outer);restart=param(bottom,null,false,0x1580);relaunch.before(restart);check(Boolean.FALSE.equals(restart.args[0]),@native method failure also closes preservation scope@);
  outer=param(bottom,null);update.before(outer);nativeDecision=param(bottom,false,0x1580,oldLayout);decide.after(nativeDecision);restart=param(bottom,null,false,0x1580);relaunch.before(restart);check(Boolean.FALSE.equals(restart.args[0])&&Boolean.FALSE.equals(nativeDecision.result),@applications handling configuration changes keep their native decision@);update.after(outer);
  refresh(independent);check(bottom.mFixedRotationTransformState==bottomState,@Back cancellation keeps original independent state@);
  bottom.task.top=bottom;independent.top=bottom;refresh(independent);check(bottom.mFixedRotationTransformState==bottomState&&bottom.starts==1,@Back commit reuses bottom portrait state@);
  foreground.finishing=true;foreground.closing=true;Object closingState=foreground.mFixedRotationTransformState;
  check(retain(foreground)&&retain(bottom),@CLOSE keeps the outgoing rotation before Shell chooses its target@);
  refresh(independent);check(foreground.mFixedRotationTransformState==closingState,@configuration refresh cannot release the closing surface@);
  call(@reconcileTopRotationTransitionsLocked@,new Class[]{Object.class},independent);
  check(foreground.mFixedRotationTransformState==closingState,@another transition ending cannot release an active close@);
  foreground.closing=false;call(@reconcileTopRotationTransitionsLocked@,new Class[]{Object.class},independent);
  check(foreground.mFixedRotationTransformState==null&&bottom.mFixedRotationTransformState==bottomState,@finished CLOSE releases only the outgoing rotation@);foreground.finishing=false;
  foreground.mRemovingFromDisplay=true;check(!retain(foreground),@removing independent page is not retained@);foreground.mRemovingFromDisplay=false;
  foreground.attached=false;check(!retain(foreground),@detached independent page is not retained@);foreground.attached=true;
  independent.mDisplayRotation.proposed=0;refresh(independent);check(bottom.mFixedRotationTransformState==null&&foreground.mFixedRotationTransformState==null&&bottom.config.densityDpi==480&&foreground.config.densityDpi==480,@portrait return restores every independent page@);
  // Both pages exist before sensor rotation: neither has a previous lease to enumerate.
  bottom.task.top=foreground;independent.top=foreground;independent.mDisplayRotation.proposed=1;refresh(independent);
  check(bottom.mFixedRotationTransformState!=null&&foreground.mFixedRotationTransformState!=null&&bottom.config.densityDpi==480&&foreground.config.densityDpi==480,@first rotation visits hidden pre-existing independent back stack and retains landscape density@);
  check(bottom.mFixedRotationTransformState!=foreground.mFixedRotationTransformState,@enumeration does not manufacture shared native associations@);
  independent.mDisplayRotation.proposed=3;refresh(independent);check(bottom.config.windowConfiguration.rotation==3&&foreground.config.windowConfiguration.rotation==3&&bottom.config.densityDpi==480&&foreground.config.densityDpi==480,@reverse turn updates every independent page at the original density@);
  Object ownBottom=bottom.mFixedRotationTransformState;bottom.mFixedRotationTransformState=new LocalState();Object foreignBottom=bottom.mFixedRotationTransformState;refresh(independent);check(bottom.mFixedRotationTransformState==foreignBottom&&!retain(bottom),@foreign back-stack state remains under native ownership@);bottom.mFixedRotationTransformState=(FakeRotationActivity.State)ownBottom;
  // The replaced lease was deliberately discarded; release its native state as native would.
  bottom.finishFixedRotationTransform(()->{});bottom.config=new Configuration(bottom.task.config);refresh(independent);
  bottom.task.mode=1;refresh(independent);check(!((Map<?,?>)f(@sTopRotationBounds@).get(null)).containsKey(bottom)&&!((Map<?,?>)f(@sTopRotationBounds@).get(null)).containsKey(foreground)&&bottom.config.densityDpi==480&&foreground.config.densityDpi==480,@role promotion returns every page from portrait TOP density@);
  call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@role cleanup@);
  bottom.task.mode=6;refresh(independent);f(@sZoomed@).set(null,false);f(@sFullscreenRotationActive@).set(null,false);
  call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@independent exit@);
  check(bottom.mFixedRotationTransformState==null&&foreground.mFixedRotationTransformState==null&&bottom.config.densityDpi==480&&foreground.config.densityDpi==480,@exit restores both independent pages and density@);
  call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@independent final cleanup@);
  check(MiStepRotationAnimation.start(bottom,1,0)&&((Map<?,?>)f(@sTaskRotations@).get(null)).isEmpty(),
        @return animation can outlive every fixed rotation state@);
  var last=bottom.mWmService.lastAnimator;var originalAdapter=last.current;
  last.current=new com.android.server.wm.LocalAnimationAdapter(null,null);
  call(@releaseAllTaskRotationsLocked@,new Class[]{String.class},@animation-only exit@);
  handler.pending.clear();call(@queueInactiveRotationCleanupLocked@,new Class[]{Object.class},independent);
  check(handler.pending.size()==1&&MiStepRotationAnimation.owns(bottom),@animation-only exit has bounded follow-up@);
  last.current=originalAdapter;handler.pending.remove(0).run();
  check(handler.pending.isEmpty()&&!MiStepRotationAnimation.owns(bottom),@animation-only exit cleans its carrier without a fixed state@);
  System.out.println(@PhoneWorkspace checks=@+checks+@ passed (production HostEntry plus registry; fake WM only)@);
 }
}'''.replace('@',chr(34))
files['android/os/Handler.java'] = files['android/os/Handler.java'].replace(
 'public boolean post(Runnable action)', 'public boolean postDelayed(Runnable action,long delay) {pending.add(action);return true;} public boolean post(Runnable action)')
sources=[]
for name,text in files.items():
 p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');sources.append(str(p))
cp=os.pathsep.join([str(OUT),os.environ['MISTEP_CLASSES'],os.environ['MISTEP_ANDROID_JAR'],str(ROOT/'libs/xposed-api-82.jar')])
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-cp',cp,'-d',str(OUT),*sources],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',cp,'com.jiusis.mistep.PhoneWorkspaceTest'],check=True)
