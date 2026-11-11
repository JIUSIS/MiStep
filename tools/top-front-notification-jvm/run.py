"""Replay production TOP/native front notifications and their installed WM hooks."""
import ast, os, subprocess, sys
from pathlib import Path
root=Path(__file__).resolve().parents[2];src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
tree=ast.parse((root/'tools/desktop-route-jvm/run.py').read_text(encoding='utf-8-sig'))
files=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='files' for t in n.targets))
del files['com/jiusis/mistep/DesktopRouteRegression.java']
files['de/robv/android/xposed/XC_MethodHook.java']="""package de.robv.android.xposed;
public class XC_MethodHook {protected void afterHookedMethod(MethodHookParam p){}public void after(MethodHookParam p){afterHookedMethod(p);}
public static class MethodHookParam {public Object thisObject;public Object[] args;public boolean failed;public boolean hasThrowable(){return failed;}}}"""
files['de/robv/android/xposed/XposedBridge.java']="""package de.robv.android.xposed;import java.util.*;
public class XposedBridge {public static Map<String,XC_MethodHook> hooks=new HashMap<>();
 public static void hookAllMethods(Class<?> c,String name,XC_MethodHook h){hooks.put(name,h);}}"""
files['com/android/server/wm/DisplayContent.java']="""package com.android.server.wm;
public class DisplayContent {public Object mCurrentFocus,mFocusedApp;public void updateFocusedWindowLocked(){}}"""
files['com/android/server/wm/TaskChangeNotificationController.java']="""package com.android.server.wm;
import com.jiusis.mistep.FrontTest;public class TaskChangeNotificationController {
 public int calls;public Object last;public boolean fail;public Runnable callback;
 public void notifyTaskMovedToFront(Object info){if(fail)throw new IllegalStateException("native failure");calls++;last=info;
 if(callback!=null)callback.run();FrontTest.nativeObserved(this,info);}}"""
host=(src/'HostEntry.java').read_text(encoding='utf-8-sig')
a=host.index('    private void installWorkspaceTopFrontNotifications(ClassLoader cl) {')
b=host.index('\n    private void ',a+10)
files['com/jiusis/mistep/HostEntry.java']="""package com.jiusis.mistep;import de.robv.android.xposed.*;
class HostEntry {static boolean sZoomed=true;static Object sDisplayContent;
 static class ZoomTarget {Object display,globalLock=new Object();ZoomTarget(Object d){display=d;}}
 static ZoomTarget sZoomTarget;final MiStepTopFocusNotification topFocusNotification=new MiStepTopFocusNotification();
 static void log(String s){}static void install(){new HostEntry().installWorkspaceTopFrontNotifications(HostEntry.class.getClassLoader());}
"""+host[a:b]+"\n}"
files['com/jiusis/mistep/MiStepDesktopLaunchBridge.java']="""package com.jiusis.mistep;
class MiStepDesktopLaunchBridge {static Object owned;static boolean alive=true;
static boolean ownsTopTask(Object t){return t==owned&&alive;}}"""
files['com/jiusis/mistep/FrontTest.java']=r"""package com.jiusis.mistep;
import android.os.*;import android.graphics.Rect;import com.android.server.wm.*;import de.robv.android.xposed.*;
public class FrontTest {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 public static class Token {IBinder value=new Binder();public IBinder asBinder(){return value;}}
 public static class Info {public int taskId,userId,displayId;public Token token=new Token();}
 public static class Atms {TaskChangeNotificationController controller=new TaskChangeNotificationController();
 public Object getTaskChangeNotificationController(){return controller;}}
 public static class Task {public Atms mAtmService;Info info=new Info();int mode=6;Object display;boolean visible=true,translucent=true;
 Rect bounds=new Rect(0,0,1494,2357);Task(int id,int mode,Object d,Atms a){info.taskId=id;this.mode=mode;display=d;mAtmService=a;}
 public Object getDisplayContent(){return display;}public int getWindowingMode(){return mode;}public Rect getBounds(){return bounds;}
 public boolean isForceTranslucent(){return translucent;}public boolean isVisible(){return visible;}public Object getTaskInfo(){return info;}}
 public static class Focus {Task task;Object display;Focus(Task t,Object d){task=t;display=d;}
 public Task getTask(){return task;}public Object getDisplayContent(){return display;}}
 DisplayContent display=new DisplayContent();Atms atms=new Atms();Task top=new Task(87,6,display,atms),main=new Task(2,1,display,atms),side=new Task(80,6,display,atms);
 FrontTest(){HostEntry.sZoomed=true;HostEntry.sDisplayContent=display;HostEntry.sZoomTarget=new HostEntry.ZoomTarget(display);
 MiStepDesktopLaunchBridge.owned=top;MiStepDesktopLaunchBridge.alive=true;HostEntry.install();}
 void focus(Task t){display.mCurrentFocus=new Focus(t,display);display.mFocusedApp=new Focus(t,display);}
 boolean update(boolean lock,boolean failed){var p=new XC_MethodHook.MethodHookParam();p.thisObject=display;p.args=new Object[]{0,true,-1};p.failed=failed;int n=atms.controller.calls;
 if(lock)synchronized(HostEntry.sZoomTarget.globalLock){XposedBridge.hooks.get("updateFocusedWindowLocked").after(p);}
 else XposedBridge.hooks.get("updateFocusedWindowLocked").after(p);
 return atms.controller.calls!=n;}
 public static void nativeObserved(Object c,Object info){var p=new XC_MethodHook.MethodHookParam();p.thisObject=c;p.args=new Object[]{info};
 XposedBridge.hooks.get("notifyTaskMovedToFront").after(p);}
 public static void main(String[] args){
 var h=new FrontTest();h.focus(h.main);check(!h.update(true,false),"ordinary native main unchanged before TOP");
 h.focus(h.top);check(h.update(true,false)&&h.atms.controller.last==h.top.info,"confirmed TOP announces real native info once");
 check(!h.update(true,false)&&h.atms.controller.calls==1,"repeated scans/DOWN never duplicate");
 h.focus(h.main);check(h.update(true,false)&&h.atms.controller.last==h.main.info,"real main focus restores native cache");
 check(!h.update(true,false),"repeated main no duplicate");h.focus(h.top);check(h.update(true,false),"new real focus change announces TOP again");
 h=new FrontTest();h.focus(h.top);h.atms.controller.notifyTaskMovedToFront(h.top.info);
 check(!h.update(true,false)&&h.atms.controller.calls==1,"native moved event deduplicates our focus event");
 h.focus(h.main);h.atms.controller.notifyTaskMovedToFront(h.main.info);check(!h.update(true,false),"native main restore deduplicated");
 h=new FrontTest();h.focus(h.top);h.display.mFocusedApp=new Focus(h.main,h.display);check(!h.update(true,false),"mismatched app/window rejected");
 h=new FrontTest();h.focus(h.top);h.display.mCurrentFocus=null;check(!h.update(true,false),"missing current window rejected");
 h=new FrontTest();h.focus(h.top);h.display.mFocusedApp=null;check(!h.update(true,false),"missing focused app rejected");
 h=new FrontTest();h.focus(h.top);h.display.mCurrentFocus=new Focus(h.top,new Object());check(!h.update(true,false),"wrong window display rejected");
 h=new FrontTest();h.focus(h.top);h.display.mFocusedApp=new Focus(h.top,new Object());check(!h.update(true,false),"wrong app display rejected");
 h=new FrontTest();h.focus(h.top);h.top.display=new Object();check(!h.update(true,false),"wrong task display rejected");
 h=new FrontTest();h.focus(h.top);h.top.info.displayId=1;check(!h.update(true,false),"info secondary display rejected");
 h=new FrontTest();h.focus(h.top);h.top.info.userId=-1;check(!h.update(true,false),"invalid user rejected");
 h=new FrontTest();h.focus(h.top);h.top.info.token.value=null;check(!h.update(true,false),"missing exact token rejected");
 h=new FrontTest();h.focus(h.top);h.top.visible=false;check(!h.update(true,false),"hidden task rejected");
 h=new FrontTest();h.focus(h.top);h.top.bounds.bottom=0;check(!h.update(true,false),"empty task bounds rejected");
 h=new FrontTest();h.focus(h.top);h.top.translucent=false;check(!h.update(true,false),"wrong native mode state rejected");
 h=new FrontTest();h.focus(h.top);MiStepDesktopLaunchBridge.alive=false;check(!h.update(true,false),"dead lease rejected");
 h=new FrontTest();h.focus(h.side);check(!h.update(true,false),"passive ordinary side never announced");
 h=new FrontTest();h.focus(h.top);check(!h.update(false,false),"outside WM lock never broadcasts");
 check(!h.update(true,true),"failed native focus update never announces");
 h=new FrontTest();h.focus(h.top);HostEntry.sDisplayContent=new Object();check(!h.update(true,false),"different target display rejected");
 h=new FrontTest();h.focus(h.top);HostEntry.sZoomed=false;check(!h.update(true,false),"inactive scene cleared");
 HostEntry.sZoomed=true;check(h.update(true,false),"new active scene can resynchronize");
 h=new FrontTest();h.focus(h.top);h.atms.controller.fail=true;check(!h.update(true,false),"failed native notification not counted as success");
 h.atms.controller.fail=false;check(h.update(true,false),"later genuine focus update can retry after failure");
 h=new FrontTest();h.focus(h.top);check(h.update(true,false),"first TOP");
 h.top.info.token.value=new Binder();check(h.update(true,false),"replacement token never deduplicated as old identity");
 h.focus(h.side);check(!h.update(true,false),"passive side cannot restore main after TOP");
 h.focus(h.main);check(h.update(true,false),"main restoration remains pending across passive focus");
 var nested=new FrontTest();nested.focus(nested.top);
 nested.atms.controller.callback=()->{nested.atms.controller.callback=null;nested.focus(nested.main);nested.update(true,false);};
 nested.update(true,false);check(nested.atms.controller.calls==2&&nested.atms.controller.last==nested.main.info,
 "synchronous native local focus callback is reconciled after broadcast");
 var inactive=new FrontTest();inactive.focus(inactive.top);inactive.update(true,false);HostEntry.sZoomed=false;
 inactive.focus(inactive.top);check(!inactive.update(true,false),"inactive TOP never republishes or loses pending main restoration");
 inactive.focus(inactive.main);check(inactive.update(true,false)&&inactive.atms.controller.last==inactive.main.info,
 "exit restores real main even after workspace became inactive");
 check(!inactive.update(true,false),"inactive restoration is sent only once");
 var nativeExit=new FrontTest();nativeExit.focus(nativeExit.top);nativeExit.update(true,false);HostEntry.sZoomed=false;
 nativeExit.atms.controller.notifyTaskMovedToFront(nativeExit.main.info);nativeExit.focus(nativeExit.main);
 check(!nativeExit.update(true,false)&&nativeExit.atms.controller.calls==2,"native inactive main notification deduplicates pending restoration");
 var nestedExit=new FrontTest();nestedExit.focus(nestedExit.top);
 nestedExit.atms.controller.callback=()->{nestedExit.atms.controller.callback=null;HostEntry.sZoomed=false;nestedExit.focus(nestedExit.main);nestedExit.update(true,false);};
 nestedExit.update(true,false);check(nestedExit.atms.controller.calls==2&&nestedExit.atms.controller.last==nestedExit.main.info,
 "notification callback may deactivate workspace before real main focus");
 var oscillation=new FrontTest();oscillation.focus(oscillation.top);
 oscillation.atms.controller.callback=()->{oscillation.focus(((Focus)oscillation.display.mCurrentFocus).task==oscillation.top?oscillation.main:oscillation.top);oscillation.update(true,false);};
 oscillation.update(true,false);check(oscillation.atms.controller.calls==2,"reentrant focus oscillation bounded to two notifications per native update");
 oscillation.atms.controller.callback=null;check(oscillation.update(true,false)&&oscillation.atms.controller.calls==3,
 "next genuine stable update reconciles state after bounded oscillation");
 System.out.println("actual TOP front-notification class + installed WM hooks checks="+checks+" passed");
 }
}"""
for name,data in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data,encoding='utf-8')
production=['MiStepTopFocusNotification.java','MiStepImePolicy.java','MiStepSideRoleChange.java','ZoomChannelState.java','ZoomCommand.java','ZoomAnimCommand.java']
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*[str(out/name) for name in files],*[str(src/name) for name in production]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.FrontTest'],check=True)
