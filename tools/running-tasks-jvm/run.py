"""Replay the actual Launcher query hook and protected bridge, not OEM gestures."""
import ast
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'module/src/main/java/com/jiusis/mistep'
OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
tree = ast.parse((ROOT / 'tools/desktop-route-jvm/run.py').read_text(encoding='utf-8-sig'))
files = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign)
             and any(isinstance(t, ast.Name) and t.id == 'files' for t in n.targets))
del files['com/jiusis/mistep/DesktopRouteRegression.java']
files['android/os/Bundle.java'] = files['android/os/Bundle.java'].replace(
    'Map<String,Object>m=', 'public Bundle getBundle(String k){return (Bundle)m.get(k);}'
    'public float getFloat(String k,float d){return m.containsKey(k)?(Float)m.get(k):d;} Map<String,Object>m=')
files['com/jiusis/mistep/NativeRawInputBridge.java'] = 'package com.jiusis.mistep;class NativeRawInputBridge {static void updateTop(int u,float s){}static void updateTop(int u,float s,float l,float t){}}'
files['com/jiusis/mistep/MiStepTopRawInput.java'] = 'package com.jiusis.mistep;import android.os.Bundle;class MiStepTopRawInput {static int uid(Bundle b,ClassLoader l){return -1;}}'
files['android/content/pm/PackageManager.java'] = '''package android.content.pm;
public class PackageManager {public boolean fail;public String[] getPackagesForUid(int uid){
 if(fail)throw new IllegalStateException();return new String[]{uid==100?"com.miui.home":"other"};}}
'''
files['de/robv/android/xposed/XC_MethodHook.java'] = '''package de.robv.android.xposed;
public class XC_MethodHook {
 protected void beforeHookedMethod(MethodHookParam p){}protected void afterHookedMethod(MethodHookParam p){}
 public void before(MethodHookParam p){beforeHookedMethod(p);}public void after(MethodHookParam p){afterHookedMethod(p);}
 public static class MethodHookParam {public Object[] args;public Object thisObject,result;public boolean skip;
  java.util.Map<String,Object> extras=new java.util.HashMap<>();
  public void setObjectExtra(String k,Object v){extras.put(k,v);}public Object getObjectExtra(String k){return extras.get(k);}
  public void setResult(Object value){result=value;skip=true;}}
}
'''
files['de/robv/android/xposed/XposedBridge.java'] = '''package de.robv.android.xposed;
public class XposedBridge {public static java.util.Map<String,XC_MethodHook> hooks=new java.util.HashMap<>();
 public static void hookMethod(java.lang.reflect.Method m,XC_MethodHook h){hooks.put(m.getName(),h);}}
'''
files['de/robv/android/xposed/XposedHelpers.java'] = files['de/robv/android/xposed/XposedHelpers.java'].replace(
    'public class XposedHelpers {', '''public class XposedHelpers {
 public static void findAndHookMethod(Class<?> c,String n,Class<?> arg,XC_MethodHook h){XposedBridge.hooks.put(n,h);}
''')
files['com/android/server/wm/Task.java'] = '''package com.android.server.wm;
import android.os.*;import com.jiusis.mistep.RunningTasksTest;
public class Task {
 public int mode=6;public Object display;public Info info=new Info();public boolean fail;public Runnable onInfo;
 public boolean translucent=true;public android.graphics.Rect bounds=new android.graphics.Rect(0,0,1494,2357);
 public static class Token {public IBinder value=new Binder();public IBinder asBinder(){return value;}}
 public static class Info {public int taskId,userId,displayId;public Token token=new Token();}
 public Task(int id,int mode,Object display){info.taskId=id;this.mode=mode;this.display=display;}
 public int getWindowingMode(){return mode;}public Object getDisplayContent(){return display;}
 public boolean isForceTranslucent(){return translucent;}public android.graphics.Rect getBounds(){return bounds;}
 public Info getTaskInfo(){if(onInfo!=null)onInfo.run();if(fail)throw new IllegalStateException();return info;}
}
'''
# Ordered candidates model the focused/visible order. No OEM implementation is claimed here.
files['com/android/server/wm/RunningTasks.java'] = '''package com.android.server.wm;
import java.util.*;import de.robv.android.xposed.*;
public class RunningTasks {
 public List<Task> candidates=new ArrayList<>(),accepted=new ArrayList<>();public Runnable nested;public boolean fail;
 public void getTasks(int max,List<Task> out,int flags,Object recent,Object root,int uid,Set<Integer> users){
  if(nested!=null)nested.run();if(fail)throw new IllegalStateException();accepted.clear();
  for(Task task:candidates){var p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{task};p.thisObject=this;
   XposedBridge.hooks.get("accept").before(p);if(!p.skip)accept(task);}
  out.addAll(accepted.subList(0,Math.min(Math.max(0,max),accepted.size())));
 }
 public void accept(Task task){accepted.add(task);}
}
'''
files['com/android/server/wm/ActivityTaskManagerServiceImpl.java'] = '''package com.android.server.wm;
import java.util.*;import com.jiusis.mistep.RunningTasksTest;
public class ActivityTaskManagerServiceImpl {
 public Atms mAtmService;public List<Task> cached;public int reads;
 public ActivityTaskManagerServiceImpl(RunningTasksTest host,List<Task> tasks){mAtmService=new Atms(host);cached=tasks;}
 public List<Task> getTasksUnlock(int max,int display){reads++;return cached.subList(0,Math.min(max,cached.size()));}
 public static class Atms {public RunningTasksTest host;public boolean fail;public int reads,display=-1;
  public Atms(RunningTasksTest h){host=h;}
  public List<Task> getTasks(int max,boolean visible,boolean extras,int displayId){
   reads++;display=displayId;if(fail)throw new IllegalStateException();return host.query(BinderUid(),max);}
  int BinderUid(){return android.os.Binder.getCallingUid();}
 }
}
'''
host = (SRC / 'HostEntry.java').read_text(encoding='utf-8-sig')
start = host.index('    private void installWorkspaceRunningTasks(ClassLoader cl) {')
end = host.index('\n    private void ', start + 10)
files['com/jiusis/mistep/HostEntry.java'] = '''package com.jiusis.mistep;
import android.content.Context;import android.os.Binder;import de.robv.android.xposed.*;
class HostEntry {static boolean sZoomed=true;static Object sDisplayContent;
 static Object sSystemContext=new Context();static ThreadLocal<Boolean> sWorkspaceLauncherTaskQuery=new ThreadLocal<>();
 static void log(String s){}static void install(){new HostEntry().installWorkspaceRunningTasks(HostEntry.class.getClassLoader());}
''' + host[start:end] + '\n}'
files['com/jiusis/mistep/RunningTasksTest.java'] = '''package com.jiusis.mistep;
import android.os.*;import android.content.*;import com.android.server.wm.*;import de.robv.android.xposed.*;import java.util.*;
public class RunningTasksTest {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 public static class Display {public Object mCurrentFocus,mFocusedApp;}
 public static class Focus {Task task;Object display;Focus(Task t,Object d){task=t;display=d;}
  public Task getTask(){return task;}public Object getDisplayContent(){return display;}}
 Display display=new Display();Binder owner=new Binder();long seq;
 Task top=new Task(26,6,display),side=new Task(24,6,display),main=new Task(25,1,display),floating=new Task(23,5,display);
 Bundle topRow=row(top),sideRow=row(side);RunningTasks scan=new RunningTasks();
 RunningTasksTest(){HostEntry.sDisplayContent=display;HostEntry.sZoomed=true;Binder.uid=100;
  ((Context)HostEntry.sSystemContext).pm.fail=false;HostEntry.sWorkspaceLauncherTaskQuery.remove();
  scan.candidates.addAll(List.of(top,side,main));register(true,true);}
 static Bundle row(Task task){var b=new Bundle();b.putInt("taskId",task.info.taskId);b.putInt("userId",task.info.userId);
  b.putInt("displayId",task.info.displayId);b.putBinder("task",task.info.token.value);return b;}
 void register(boolean withTop,boolean withSide){var b=new Bundle();b.putBinder("owner",owner);b.putLong("sequence",++seq);
  b.putParcelableArrayList("rows",new ArrayList<Bundle>());
  b.putParcelableArrayList("recents",new ArrayList<Bundle>(withSide?List.of(sideRow):List.of()));
  if(withTop)b.putParcelable("topInput",topRow);
  check(MiStepDesktopLaunchBridge.update(new Intent("state").putExtra("state",b)),"protected registration accepted");}
 public List<Task> query(int uid,int max){var out=new ArrayList<Task>();var p=new XC_MethodHook.MethodHookParam();
  p.args=new Object[]{max,out,2,null,display,uid,new HashSet<Integer>()};p.thisObject=scan;
  var hook=XposedBridge.hooks.get("getTasks");hook.before(p);
  try{scan.getTasks(max,out,2,null,display,uid,new HashSet<>());}finally{hook.after(p);}return out;}
 void first(Task expected,String message){var result=query(100,1);check(result.size()==1&&result.get(0)==expected,message);}
 @SuppressWarnings("unchecked")
 List<Task> vendor(ActivityTaskManagerServiceImpl service,int max,int display){
  var p=new XC_MethodHook.MethodHookParam();p.thisObject=service;p.args=new Object[]{max,display};
  var hook=XposedBridge.hooks.get("getTasksUnlock");if(hook!=null)hook.before(p);
  return p.skip?(List<Task>)p.result:service.getTasksUnlock(max,display);
 }
 ActivityTaskManagerServiceImpl vendor(){return new ActivityTaskManagerServiceImpl(this,List.of(top,side,main));}
 void focus(Task task){display.mCurrentFocus=new Focus(task,display);display.mFocusedApp=new Focus(task,display);}
 public static void main(String[] args){HostEntry.install();check(XposedBridge.hooks.size()>=2,"actual query and accept hooks installed");
  var focused=new RunningTasksTest();focused.focus(focused.top);
  focused.first(focused.top,"native gesture query must retain the exact window-and-app-focused TOP");
  check(focused.query(100,5).equals(List.of(focused.top,focused.main)),"focused TOP retained before truncation, ordinary cells still filtered");
  check(focused.vendor(focused.vendor(),1,0).equals(List.of(focused.top)),"vendor native traversal also retains focused TOP");
  focused=new RunningTasksTest();focused.focus(focused.top);focused.display.mFocusedApp=null;
  focused.first(focused.main,"old focused window alone cannot expose inactive TOP");
  focused=new RunningTasksTest();focused.focus(focused.top);focused.display.mCurrentFocus=null;
  focused.first(focused.main,"focused app alone cannot expose inactive TOP");
  focused=new RunningTasksTest();focused.focus(focused.top);focused.display.mFocusedApp=new Focus(focused.main,focused.display);
  focused.first(focused.main,"window and app must agree on exact TOP");
  focused=new RunningTasksTest();focused.focus(focused.top);focused.display.mCurrentFocus=new Focus(focused.top,new Object());
  focused.first(focused.main,"focused window on a different display cannot exempt TOP");
  focused=new RunningTasksTest();focused.focus(focused.top);focused.display.mFocusedApp=new Focus(focused.top,new Object());
  focused.first(focused.main,"focused app on a different display cannot exempt TOP");
  focused=new RunningTasksTest();focused.focus(focused.side);
  focused.first(focused.main,"ordinary leased side task is still filtered even if native focus is stale");
  focused=new RunningTasksTest();focused.focus(focused.top);focused.top.translucent=false;
  focused.first(focused.main,"non-translucent task is not an active TOP focus exception");
  focused=new RunningTasksTest();focused.focus(focused.top);focused.top.bounds.left=1;
  focused.first(focused.main,"noncanonical task geometry cannot exempt TOP");
  focused=new RunningTasksTest();focused.focus(focused.top);focused.top.bounds.bottom=0;
  focused.first(focused.main,"empty TOP bounds cannot exempt task filtering");
  focused=new RunningTasksTest();focused.focus(focused.top);focused.register(false,true);
  focused.first(focused.top,"unowned focused task remains native without a TOP exception");
  var cached=new RunningTasksTest();var service=cached.vendor();
  check(cached.vendor(service,1,0).equals(List.of(cached.main)),"OS3 cached Launcher query must reach real main before maxNum");
  check(service.reads==0&&service.mAtmService.reads==1&&service.mAtmService.display==0,"use native default-display traversal without mutating cache");
  check(cached.vendor(service,8,0).equals(List.of(cached.main)),"vendor path shares ordinary and TOP exact lease filter");
  check(service.cached.equals(List.of(cached.top,cached.side,cached.main)),"shared OEM cache remains unchanged");
  check(cached.vendor(service,0,0).isEmpty(),"vendor maxNum zero preserved");
  cached=new RunningTasksTest();service=cached.vendor();HostEntry.sZoomed=false;
  check(cached.vendor(service,1,0).equals(List.of(cached.top))&&service.mAtmService.reads==0,"inactive workspace keeps OEM cache");
  cached=new RunningTasksTest();service=cached.vendor();Binder.uid=200;
  check(cached.vendor(service,1,0).equals(List.of(cached.top))&&service.mAtmService.reads==0,"other callers keep OEM cache");
  cached=new RunningTasksTest();service=cached.vendor();
  check(cached.vendor(service,1,1).equals(List.of(cached.top))&&service.mAtmService.reads==0,"external display keeps OEM path");
  cached=new RunningTasksTest();service=cached.vendor();((Context)HostEntry.sSystemContext).pm.fail=true;
  check(cached.vendor(service,1,0).equals(List.of(cached.top))&&service.mAtmService.reads==0,"uncertain caller keeps OEM path");
  cached=new RunningTasksTest();service=cached.vendor();service.mAtmService.fail=true;
  check(cached.vendor(service,1,0).equals(List.of(cached.top))&&service.reads==1,"failed native query falls through to OEM path");
  cached=new RunningTasksTest();service=cached.vendor();cached.owner.alive=false;
  check(cached.vendor(service,1,0).equals(List.of(cached.top)),"dead lease remains native through vendor route");
  cached=new RunningTasksTest();service=cached.vendor();cached.top.mode=5;
  check(cached.vendor(service,1,0).equals(List.of(cached.top)),"native floating task preserved through vendor route");
  cached=new RunningTasksTest();service=cached.vendor();cached.top.info.token.value=new Binder();
  check(cached.vendor(service,1,0).equals(List.of(cached.top)),"replaced token preserved through vendor route");
  var h=new RunningTasksTest();h.topRow.putInt("width",1494);h.topRow.putInt("height",2357);
  h.first(h.main,"tablet portrait TOP must not replace main in Launcher maxNum=1");
  check(h.query(100,8).equals(List.of(h.main)),"ordinary cells and TOP filtered before truncation");
  check(MiStepDesktopLaunchBridge.ownsTopTask(h.top),"TOP keeps its separate active input identity");
  check(MiStepDesktopLaunchBridge.recentsLease(h.top)==null,"TOP not changed into a passive IME/recents cell");
  check(!MiStepDesktopLaunchBridge.beforeRecents((Context)HostEntry.sSystemContext,new Object(),new Object[]{26,null}),"TOP not a desktop promotion row");
  h=new RunningTasksTest();h.topRow.putInt("width",3048);h.topRow.putInt("height",1720);h.first(h.main,"phone landscape TOP shares the exact query filter");
  h=new RunningTasksTest();h.register(false,true);h.first(h.top,"unregistered TOP stays native");
  h=new RunningTasksTest();check(h.query(200,1).equals(List.of(h.top)),"unrelated caller retains native order");
  h=new RunningTasksTest();Binder.uid=200;h.first(h.main,"original caller arg5 used, not cleared Binder identity");
  h=new RunningTasksTest();HostEntry.sZoomed=false;h.first(h.top,"outside workspace untouched");
  h=new RunningTasksTest();h.owner.alive=false;h.first(h.top,"dead scene not filtered");
  h=new RunningTasksTest();h.top.mode=5;h.first(h.top,"native freeform is never captured by TOP lease");
  h=new RunningTasksTest();h.top.mode=1;h.first(h.top,"returned fullscreen task is never filtered");
  h=new RunningTasksTest();h.top.info.token.value=new Binder();h.first(h.top,"replaced TOP token rejected");
  h=new RunningTasksTest();h.top.info.taskId=27;h.first(h.top,"reused task identity rejected");
  h=new RunningTasksTest();h.top.info.userId=999;h.first(h.top,"different user rejected");
  h=new RunningTasksTest();h.top.info.displayId=1;h.first(h.top,"external display info rejected");
  h=new RunningTasksTest();h.topRow.putInt("displayId",1);h.first(h.top,"invalid TOP registration display rejected");
  h=new RunningTasksTest();h.top.display=new Object();h.first(h.top,"different physical display rejected");
  h=new RunningTasksTest();h.top.fail=true;h.first(h.top,"unreadable native task stays candidate");
  h=new RunningTasksTest();((Context)HostEntry.sSystemContext).pm.fail=true;h.first(h.top,"caller lookup failure stays native");
  h=new RunningTasksTest();h.scan.candidates.add(0,h.floating);h.first(h.floating,"unrelated native floating task remains first");
  h=new RunningTasksTest();check(h.query(100,0).isEmpty(),"maxNum zero remains empty");
  h=new RunningTasksTest();h.scan.candidates=List.of(h.top,h.side);check(h.query(100,1).isEmpty(),"no main means empty, not an arbitrary replacement");
  h=new RunningTasksTest();var sideLease=MiStepDesktopLaunchBridge.recentsLease(h.side);h.register(true,true);
  check(!MiStepDesktopLaunchBridge.recentsLeaseAlive(sideLease),"new scene invalidates old query lease");
  h=new RunningTasksTest();HostEntry.sWorkspaceLauncherTaskQuery.set(true);check(h.query(200,1).get(0)==h.top,"nested unrelated query not filtered");
  check(Boolean.TRUE.equals(HostEntry.sWorkspaceLauncherTaskQuery.get()),"nested query restores outer scope");
  h=new RunningTasksTest();h.scan.fail=true;try{h.query(100,1);throw new AssertionError("native failure swallowed");}catch(IllegalStateException expected){}
  check(HostEntry.sWorkspaceLauncherTaskQuery.get()==null,"native exception restores query scope");
  h=new RunningTasksTest();var nestedHost=h;h.scan.nested=()->{
   var rt=new RunningTasks();rt.candidates.add(nestedHost.top);var outer=nestedHost.scan;nestedHost.scan=rt;
   try{check(nestedHost.query(200,1).get(0)==nestedHost.top,"nested caller keeps native TOP");}finally{nestedHost.scan=outer;}};
  h.first(h.main,"outer Launcher query resumes filtering after nested query");
  h=new RunningTasksTest();var staleHost=h;h.top.onInfo=()->{staleHost.top.onInfo=null;staleHost.register(false,false);};
  h.first(h.top,"registration replaced during native read cannot filter a stale TOP");
  System.out.println("actual RunningTasks hook + production bridge checks="+checks+" passed");
 }
}
'''
for name, source in files.items():
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding='utf-8')
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
production = ['MiStepDesktopLaunchBridge.java', 'DesktopIconLaunchPolicy.java', 'MiStepSideRoleChange.java',
              'MiStepImePolicy.java', 'ZoomChannelState.java', 'ZoomCommand.java', 'ZoomAnimCommand.java']
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(OUT)]
               + [str(OUT / name) for name in files] + [str(SRC / name) for name in production], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(OUT), 'com.jiusis.mistep.RunningTasksTest'], check=True)
