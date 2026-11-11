"""Exercise the actual OS2 TaskView/controller compatibility branches."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
files = {
    'android/content/Context.java': 'package android.content;public class Context {}',
    'android/graphics/Rect.java': 'package android.graphics;public class Rect {public int left,top,right,bottom;public Rect(){}public Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}public void set(Rect r){left=r.left;top=r.top;right=r.right;bottom=r.bottom;}public int width(){return right-left;}public int height(){return bottom-top;}public boolean equals(Object o){if(!(o instanceof Rect))return false;Rect r=(Rect)o;return left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}}',
    'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public class XposedHelpers {
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(NoSuchFieldException e){throw new NoSuchFieldError(n);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object newInstance(Class<?> c,Object...a){try{for(var x:c.getDeclaredConstructors())if(x.getParameterCount()==a.length){x.setAccessible(true);return x.newInstance(a);}throw new NoSuchMethodException(c.getName());}catch(Exception e){throw new RuntimeException(e);}}
 public static void setBooleanField(Object o,String n,boolean v){try{o.getClass().getField(n).setBoolean(o,v);}catch(Exception e){throw new RuntimeException(e);}}
}''',
    'android/window/WindowContainerTransaction.java': 'package android.window;public class WindowContainerTransaction {}',
    'android/view/SurfaceControl.java': 'package android.view;public class SurfaceControl {public static class Transaction extends com.jiusis.mistep.Os2ShellRegression.Tx {public void apply(){}public void close(){}}}',
    'com/android/wm/shell/common/SyncTransactionQueue.java': 'package com.android.wm.shell.common;public class SyncTransactionQueue {}',
    'com/android/wm/shell/ShellTaskOrganizer.java': 'package com.android.wm.shell;public class ShellTaskOrganizer {}',
    'com/android/wm/shell/common/ShellExecutor.java': 'package com.android.wm.shell.common;public interface ShellExecutor extends java.util.concurrent.Executor {}',
    'com/jiusis/mistep/Os2ShellRegression.java': r'''package com.jiusis.mistep;
import java.util.*;import java.util.concurrent.Executor;import android.content.Context;import android.graphics.Rect;import android.window.WindowContainerTransaction;
public class Os2ShellRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static class Controller {public String task;public Controller(String t){task=t;}}
 public static class Info {}
 public static class Canonical {}
 public static class NativeTransitions {int starts,type;Object wct,handler;public Object startTransition(int t,Object w,Object h){starts++;type=t;wct=w;handler=h;return new Object();}}
 public static class Os2Transitions extends BindingViews {public List<Object>mPending=new ArrayList<>();public NativeTransitions mTransitions=new NativeTransitions();public void startNextTransition(){if(mPending.isEmpty())return;PendingTransition p=(PendingTransition)mPending.get(0);check(((BindingController)p.mController).mTaskToken!=null,"OS2 CHANGE queued before live task binding");p.mClaimed=mTransitions.startTransition(p.mType,p.mWct,this);}public static class PendingTransition{public int mType;public Object mWct,mController,mClaimed;public PendingTransition(int t,Object w,Object c,Object claim){mType=t;mWct=w;mController=c;mClaimed=claim;}}}
 public static class FactoryController {public Executor mShellExecutor;public Os2Transitions mTaskViewTransitions;public com.android.wm.shell.ShellTaskOrganizer mTaskOrganizer=new com.android.wm.shell.ShellTaskOrganizer();public com.android.wm.shell.common.SyncTransactionQueue mSyncQueue=new com.android.wm.shell.common.SyncTransactionQueue();}
 public static class FactoryImpl {public FactoryController this$0;}
 public static class InitialInfo {public int taskId;public Object token;InitialInfo(int id,Object t){taskId=id;token=t;}}
 public static class Appeared {InitialInfo info;public Object leash=new Object();Appeared(InitialInfo i){info=i;}public Object getTaskInfo(){return info;}public Object getLeash(){return leash;}}
 public static class Organizer {public Object mLock=new Object();public Map<Integer,Object>mTasks=new HashMap<>();}
 public static class InfoController {public Object mTaskToken,mTaskOrganizer;}
 public static class Track {public Object mActiveTransition;public List<Object>mReadyTransitions=new ArrayList<>();}
 public static class LegacyShell {public List<Object>mPendingTransitions=new ArrayList<>(),mReadyDuringSync=new ArrayList<>();public List<Track>mTracks=new ArrayList<>();public List<Runnable>mRunWhenIdleQueue=new ArrayList<>();}
 public static class ModernShell {int calls;public boolean isIdle(){calls++;return false;}public void runOnIdle(Runnable r){calls+=10;}}
 public static class LegacyArea {public Map<Integer,Object>mDisplayAreasInfo=new HashMap<>();}
 public static class ModernArea extends LegacyArea {public Object own=new Object();public Object getDisplayAreaInfo(int display){return own;}}
 public static class BindingViews {public Map<Object,Object>mTaskViews=new HashMap<>();}
 public static class RequestedState {public Rect mBounds=new Rect();public boolean mVisible;}
 public static class Surface {int id;boolean valid=true;public Surface(int i){id=i;}public Surface(Surface s,String label){id=s.id;}public int getLayerId(){return id;}public boolean isValid(){return valid;}}
 public static class BindingInfo {public Object token,baseActivity=new Object(),taskDescription;public int taskId=46;BindingInfo(Object t){token=t;}}
 public static class BindingBase {public Rect bounds=new Rect(0,0,1080,2460);public Rect getCurrentBoundsOnScreen(){return bounds;}}
 public static class BindingOrganizer {int backs;public void setInterceptBackPressedOnTaskRoot(Object t,boolean v){backs++;}}
 public static class BindingListener {int created,visible;Object activity;public void onTaskCreated(int id,Object a){created++;activity=a;check(id==46,"wrong callback task");}public void onTaskVisibilityChanged(int id,boolean v){visible++;}}
 public static class BindingController {public Object mTaskToken,mTaskInfo,mPendingInfo,mTaskLeash,mSurfaceControl=new Object();public boolean mSurfaceCreated=true;public BindingBase mTaskViewBase=new BindingBase();public BindingOrganizer mTaskOrganizer=new BindingOrganizer();public BindingListener mListener=new BindingListener();public Executor mListenerExecutor;int captions;public void applyCaptionInsetsIfNeeded(){captions++;}}
 public static class Tx {int parents,shows,crops;Object parent;public void reparent(Object s,Object p){parents++;parent=p;}public void show(Object s){shows++;}public void setPosition(Object s,float x,float y){}public void setWindowCrop(Object s,int w,int h){crops++;check(w==1080&&h==2460,"native crop dimensions lost");}}
 public static class BindingWct {Object token;Rect bounds;boolean hidden;public void setBounds(Object t,Rect b){token=t;bounds=b;}public void setHidden(Object t,boolean h){token=t;hidden=h;}}
 public static void main(String[]args){
  Os2Transitions trans=new Os2Transitions();BindingController controller=new BindingController();controller.mListenerExecutor=Runnable::run;BindingWct wct=new BindingWct();Object rootToken=new Object();BindingInfo rootInfo=new BindingInfo(rootToken);trans.mTaskViews.put(controller,new RequestedState());
  Object result=ShellCompat.call(trans,"startRootTask",controller,rootInfo,new Surface(1),wct);
  check(result==null&&trans.mPending.size()==1&&trans.mTransitions.starts==1,"OS2 existing task queues exactly one transition");
  Os2Transitions.PendingTransition pending=(Os2Transitions.PendingTransition)trans.mPending.get(0);
  check(pending.mType==6&&pending.mWct==wct&&pending.mController==controller,"OS2 CHANGE carries the exact WCT/controller");
  check(controller.mTaskToken==rootToken&&controller.mTaskInfo==rootInfo&&controller.mListener.created==1&&wct.token==rootToken,"OS2 existing task CHANGE lost initial binding/notification");
  FactoryController owner=new FactoryController();owner.mShellExecutor=r->r.run();owner.mTaskViewTransitions=trans;FactoryImpl factory=new FactoryImpl();factory.this$0=owner;Context context=new Context();Object[] made={null};
  ShellCompat.createTaskView(factory,context,r->r.run(),v->made[0]=v);
  check(made[0]!=null&&made[0].getClass().getName().equals("com.android.wm.shell.taskview.TaskView"),"OS2 factory fallback uses the two-argument TaskView constructor");
  check(ShellCompat.taskViewController(made[0])!=null,"OS2 controller is read from mTaskViewTaskController without a getter");
  Object emptyWct=new Object(),token=new Object();
  check(ShellCompat.transaction(new Object(),emptyWct,"setDisableLaunchAdjacent",token,false)==emptyWct,"absent OS2 launch-adjacent inverse rejected");
  check(ShellCompat.transaction(new Object(),emptyWct,"setTaskTrimmableFromRecents",token,true)==emptyWct,"absent OS2 fullscreen recents inverse rejected");
  check(ShellCompat.transaction(new Object(),emptyWct,"setTaskTrimmableFromRecents",token,false)==emptyWct,"absent OS2 side recents bit rejected");
  try{ShellCompat.transaction(new Object(),emptyWct,"setDisableLaunchAdjacent",token,true);throw new AssertionError("unsupported true flag silently accepted");}catch(IllegalStateException expected){checks++;}
  Organizer org=new Organizer();InfoController ctl=new InfoController();ctl.mTaskOrganizer=org;ctl.mTaskToken=token;
  InitialInfo initial=new InitialInfo(38,token);org.mTasks.put(38,new Appeared(initial));
  check(ShellCompat.currentTaskInfo(ctl,38)==initial,"OS2 initial callback lost canonical task state");
  check(ShellCompat.taskLeash(org,38)==((Appeared)org.mTasks.get(38)).leash,"OS2 lost canonical leash by task ID");check(ShellCompat.taskLeash(org,39)==null,"OS2 fabricated absent task leash");
  check(ShellCompat.currentTaskInfo(ctl,39)==null,"absent task produced initial state");
  ctl.mTaskToken=new Object();check(ShellCompat.currentTaskInfo(ctl,38)==null,"foreign controller token accepted");
  ctl.mTaskToken=token;initial.taskId=39;check(ShellCompat.currentTaskInfo(ctl,38)==null,"stale task ID accepted");
  LegacyShell shell=new LegacyShell();Track track=new Track();shell.mTracks.add(track);
  check(Boolean.TRUE.equals(ShellCompat.call(shell,"isIdle")),"OS2 empty tracks not idle");
  shell.mPendingTransitions.add(token);check(Boolean.FALSE.equals(ShellCompat.call(shell,"isIdle")),"OS2 pending transition falsely idle");shell.mPendingTransitions.clear();
  shell.mReadyDuringSync.add(token);check(Boolean.FALSE.equals(ShellCompat.call(shell,"isIdle")),"OS2 sync-ready transition falsely idle");shell.mReadyDuringSync.clear();
  track.mActiveTransition=token;check(Boolean.FALSE.equals(ShellCompat.call(shell,"isIdle")),"OS2 active track falsely idle");track.mActiveTransition=null;
  Track other=new Track();shell.mTracks.add(other);other.mReadyTransitions.add(token);check(Boolean.FALSE.equals(ShellCompat.call(shell,"isIdle")),"OS2 second ready track falsely idle");
  int[] ran={0};Runnable work=()->ran[0]++;ShellCompat.call(shell,"runOnIdle",work);check(ran[0]==0&&shell.mRunWhenIdleQueue.size()==1&&shell.mRunWhenIdleQueue.get(0)==work,"OS2 callback bypassed native idle queue");
  other.mReadyTransitions.clear();shell.mRunWhenIdleQueue.remove(0).run();check(ran[0]==1,"native queue lost callback");ShellCompat.call(shell,"runOnIdle",work);check(ran[0]==2&&shell.mRunWhenIdleQueue.isEmpty(),"already idle work not run exactly once");
  ModernShell modern=new ModernShell();check(Boolean.FALSE.equals(ShellCompat.call(modern,"isIdle")),"modern idle result overwritten");ShellCompat.call(modern,"runOnIdle",work);check(modern.calls==11&&ran[0]==2,"modern native callback overridden");
  LegacyArea area=new LegacyArea();area.mDisplayAreasInfo.put(0,initial);check(ShellCompat.call(area,"getDisplayAreaInfo",0)==initial,"OS2 lost canonical display area");check(ShellCompat.call(area,"getDisplayAreaInfo",1)==null,"absent display area fabricated");ModernArea nativeArea=new ModernArea();check(ShellCompat.call(nativeArea,"getDisplayAreaInfo",0)==nativeArea.own,"modern area getter overridden");
  BindingViews views=new BindingViews();BindingController binding=new BindingController();RequestedState state=new RequestedState();views.mTaskViews.put(binding,state);state.mVisible=true;state.mBounds.set(new Rect(2,3,4,5));
  ShellCompat.call(views,"unregisterTaskView",binding);check(views.mTaskViews.isEmpty(),"OS2 temporary unregister left native view visible");ShellCompat.call(views,"registerTaskView",binding);check(views.mTaskViews.get(binding)==state&&state.mVisible&&state.mBounds.left==2,"OS2 lost original native state on register");ShellCompat.call(views,"registerTaskView",binding);check(views.mTaskViews.size()==1,"OS2 duplicate native registration");
  List<Runnable> callbacks=new ArrayList<>();binding.mListenerExecutor=callbacks::add;BindingInfo info=new BindingInfo(token);Surface canonicalSurface=new Surface(7);Tx start=new Tx(),finish=new Tx();BindingWct finishWct=new BindingWct();binding.mPendingInfo=info;
  check(Boolean.TRUE.equals(ShellCompat.call(views,"prepareOpenAnimation",binding,true,start,finish,info,canonicalSurface,finishWct)),"visible OS2 prepare rejected");
  check(binding.mTaskToken==token&&binding.mTaskInfo==info&&binding.mPendingInfo==null,"OS2 binding lost canonical task identity");
  check(binding.mTaskLeash!=canonicalSurface&&((Surface)binding.mTaskLeash).id==7,"OS2 must retain an independent live handle to the same task layer");
  check(start.parents==1&&start.shows==1&&finish.parents==1&&finish.crops==1&&finish.parent==binding.mSurfaceControl,"OS2 native parent/crop not queued in borrowed transactions");
  check(state.mVisible&&state.mBounds.equals(binding.mTaskViewBase.bounds)&&finishWct.token==token&&finishWct.bounds==binding.mTaskViewBase.bounds&&binding.captions==1,"OS2 native bounds/caption state lost");
  check(binding.mTaskOrganizer.backs==1&&binding.mListener.created==0&&callbacks.size()==1,"OS2 callback bypassed native executor");callbacks.remove(0).run();check(binding.mListener.created==1&&binding.mListener.activity==info.baseActivity&&binding.mListener.visible==0,"OS2 initial listener callbacks differ from native");
  Object retained=binding.mTaskLeash;ShellCompat.call(views,"prepareOpenAnimation",binding,false,start,null,info,canonicalSurface,new BindingWct());check(binding.mTaskLeash==retained&&binding.mTaskOrganizer.backs==1,"repeat prepare leaked a native task handle");callbacks.remove(0).run();check(binding.mListener.created==1&&binding.mListener.visible==1,"non-fresh OS2 prepare repeated created callback");
  binding.mSurfaceCreated=false;BindingWct hiddenWct=new BindingWct();check(Boolean.FALSE.equals(ShellCompat.call(views,"prepareOpenAnimation",binding,true,start,null,info,canonicalSurface,hiddenWct))&&hiddenWct.hidden&&!state.mVisible,"OS2 hidden native view falsely accepted as ready");callbacks.remove(0).run();check(binding.mListener.visible==2,"OS2 hidden callback missing");
  try{ShellCompat.call(views,"prepareOpenAnimation",binding,true,start,finish,new BindingInfo(new Object()),canonicalSurface,new BindingWct());throw new AssertionError("occupied controller overwritten");}catch(IllegalStateException expected){checks++;}check(binding.mTaskToken==token,"foreign prepare mutated task identity");
  System.out.println("OS2 Shell/TaskView compatibility checks="+checks+" passed");
 }
}''',
    'com/android/wm/shell/taskview/TaskViewTaskController.java': 'package com.android.wm.shell.taskview;public class TaskViewTaskController {public TaskViewTaskController(android.content.Context c,com.android.wm.shell.ShellTaskOrganizer o,Object t,com.android.wm.shell.common.SyncTransactionQueue q){}}',
    'com/android/wm/shell/taskview/TaskView.java': 'package com.android.wm.shell.taskview;public class TaskView {public TaskViewTaskController mTaskViewTaskController;public TaskView(android.content.Context c,TaskViewTaskController ctl){mTaskViewTaskController=ctl;}}',
}
paths = []
for name, text in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')
    paths.append(str(path))
paths.append(str(root / 'module/src/main/java/com/jiusis/mistep/ShellCompat.java'))
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'com.jiusis.mistep.Os2ShellRegression'], check=True)
