import os
"""Compile the actual pair ownership class against bounded, source-shaped Android/Shell fakes."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'module/src/main/java/com/jiusis/mistep'
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'build/tests/standalone'
OUT.mkdir(parents=True, exist_ok=True)
JDK = Path(os.environ["MISTEP_JAVA_BIN"])
FILES = {
'android/graphics/Rect.java': r'''package android.graphics;
public class Rect {public int left,top,right,bottom;public Rect(){}public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}public int width(){return right-left;}public int height(){return bottom-top;}}
''',
'android/view/WindowInsets.java': r'''package android.view; public class WindowInsets {public static class Type {public static int captionBar(){return 4;}}}''',
'android/graphics/Insets.java': r'''package android.graphics;public class Insets {public static Insets of(int l,int t,int r,int b){return new Insets();}}''',
'android/window/WindowContainerTransaction.java': r'''package android.window;
import java.util.*;public class WindowContainerTransaction {
 public final List<String> ops=new ArrayList<>();
 private void op(String n,Object t,Object v){ops.add(n+":"+t+":"+v);}
 public void setWindowingMode(Object t,int v){op("mode",t,v);}public void setBounds(Object t,android.graphics.Rect r){op("bounds",t,r);}
 public void setInterceptBackPressedOnTaskRoot(Object t,boolean v){op("back",t,v);}public void setTaskForceExcludedFromRecents(Object t,boolean v){op("excluded",t,v);}
 public void setDisablePip(Object t,boolean v){op("pip",t,v);}public void setDisableLaunchAdjacent(Object t,boolean v){op("adjacent",t,v);}
 public void setAlwaysOnTop(Object t,boolean v){op("top",t,v);}public void setFocusable(Object t,boolean v){op("focusable",t,v);}
 public void setForceTranslucent(Object t,boolean v){op("translucent",t,v);}public void setHidden(Object t,boolean v){op("hidden",t,v);}
 public void setTaskTrimmableFromRecents(Object t,boolean v){op("trim",t,v);}public void reorder(Object t,boolean v){op("reorder",t,v);}
 public void removeInsetsSource(Object t,Object o,int i,int type){op("insets",t,type);}
 public void addInsetsSource(Object t,Object o,int i,int type,android.graphics.Insets v,Object frames,int flags){op("addInsets",t,type);}
}''',
'de/robv/android/xposed/XposedHelpers.java': r'''package de.robv.android.xposed;
import java.lang.reflect.*;public class XposedHelpers {
 public static Object getObjectField(Object o,String n){try{Class<?> c=o.getClass();while(c!=null){try{Field f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new NoSuchFieldException(n);}catch(Exception e){throw new IllegalStateException(n,e);}}
 public static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
 public static Object callMethod(Object o,String n,Object...args){try{Class<?> c=o.getClass();while(c!=null){for(Method m:c.getDeclaredMethods()){if(!m.getName().equals(n)||m.getParameterCount()!=args.length)continue;boolean match=true;Class<?>[] p=m.getParameterTypes();for(int i=0;i<p.length;i++){Class<?> t=p[i];if(t==int.class)t=Integer.class;if(t==boolean.class)t=Boolean.class;if(t==float.class)t=Float.class;if(args[i]!=null&&!t.isInstance(args[i]))match=false;}if(match){m.setAccessible(true);return m.invoke(o,args);}}c=c.getSuperclass();}throw new NoSuchMethodException(n);}catch(InvocationTargetException e){throw new IllegalStateException(n,e.getCause());}catch(Exception e){throw new IllegalStateException(n,e);}}
}''',
'com/android/wm/shell/ShellTaskOrganizer.java': r'''package com.android.wm.shell;
public class ShellTaskOrganizer {public interface TaskListener {
 default boolean onNewTaskListenerDetected(Object info,Object next){return true;}
 default void onTaskAppeared(Object info,Object leash){}default void onTaskVanished(Object info){}
 default void onTaskInfoChanged(Object info){}default void attachChildSurfaceToTask(int id,Object builder){}
 default void reparentChildSurfaceToTask(int id,Object surface,Object tx){}
}}''',
'com/jiusis/mistep/MiStepLiveSwapSession.java': r'''package com.jiusis.mistep;
final class MiStepLiveSwapSession {static final class Ready {Ready(Object a,Object b,Object p,int x,int y,LiveSwapGeometry.Affine t,LiveSwapGeometry.Affine u,LiveSwapGeometry.Affine v){}}}''',
'com/jiusis/mistep/LiveTaskSwapDriver.java': r'''package com.jiusis.mistep;
import android.graphics.Rect;
final class LiveTaskSwapDriver {static final class Home {
 final LiveSwapGeometry.Task app;final int homeId=11,userId=0;final Object homeToken="A",hostRoot=new LivePairRegression.Surface(900);final Rect logical=new Rect(0,0,1200,2608);
 Home(){app=new LiveSwapGeometry.Task(12,"B",1200,2608,new LiveSwapGeometry.Rect(900,600,300,652),new LiveSwapGeometry.Rect(0,600,900,1956));}
}}''',
'com/jiusis/mistep/LiveHomeRegression.java': r'''package com.jiusis.mistep;
import java.util.*;import com.jiusis.mistep.LivePairRegression.*;
public class LiveHomeRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static class Change {Info task;Surface leash;Change(Info t,Surface l){task=t;leash=l;}public Info getTaskInfo(){return task;}public Surface getLeash(){return leash;}}
 public static class Transition {List<Change> changes=new ArrayList<>();public List<Change> getChanges(){return changes;}}
 static class HomeCase {LivePairRegression.Fixture f=new LivePairRegression.Fixture();MiStepLiveHomeTask h;Transition t=new Transition();Info snapshot=new Info(12,"B",1);
  HomeCase(){f.a.type=2;h=new MiStepLiveHomeTask(f.o,f.views,f.c,new Surface(1),f.c.mSurfaceControl,new LiveTaskSwapDriver.Home());t.changes.add(new Change(snapshot,new Surface(12)));t.changes.add(new Change(f.a,new Surface(11)));}
 }
 static void rejected(HomeCase c,String why){try{c.h.resolve(c.t);throw new AssertionError(why);}catch(IllegalStateException expected){checks++;}}
 public static void main(String[] args)throws Exception {
  HomeCase c=new HomeCase();check(c.h.preflight(),"valid Home preflight rejected");check(c.h.resolve(c.t)!=null,"stale canonical role or Home bookkeeping rejected");
  check(c.f.b.mode==6,"test lost stale callback case");c.h.prepare();check(c.f.c.vanished==1&&c.f.c.mTaskToken==null,"Home side owner not retired");
  check(((Appeared)c.f.o.mTasks.get(12)).leash.releases==0,"Home canonical leash released");
  c.f.b.mode=1;Tx tx=new Tx();c.h.finish(tx,false,.25f);check(tx.ops.contains("parent:12:1"),"promoted app not returned to display");
  check(tx.ops.contains("hide:500"),"retired TaskView resize background remains visible after Home promotion");
  Tx preparing=new Tx();c.h.stageContainer(preparing,false);check(preparing.ops.contains("hide:500"),"Home real-task start does not hide empty source container");
  check(tx.ops.stream().noneMatch(s->s.contains(":11:")),"Home surface was modified");
  List<String> wct=((android.window.WindowContainerTransaction)c.h.windowTransaction(false)).ops;check(wct.stream().allMatch(s->s.contains(":B:")),"Home entered app WCT");
  HomeCase role=new HomeCase();role.snapshot.mode=6;rejected(role,"non-fullscreen transition admitted");
  HomeCase user=new HomeCase();user.snapshot.userId=999;rejected(user,"foreign transition user admitted");
  HomeCase display=new HomeCase();display.snapshot.displayId=1;rejected(display,"foreign transition display admitted");
  HomeCase id=new HomeCase();id.snapshot.taskId=13;rejected(id,"reused token wrong id admitted");
  HomeCase parent=new HomeCase();parent.snapshot.parent=true;rejected(parent,"nested task admitted");
  HomeCase type=new HomeCase();type.snapshot.type=2;rejected(type,"Home promoted as app");
  HomeCase layer=new HomeCase();layer.t.changes.get(0).leash=new Surface(99);rejected(layer,"noncanonical layer admitted");
  HomeCase missing=new HomeCase();missing.t.changes.remove(0);rejected(missing,"missing app change admitted");
  HomeCase dup=new HomeCase();dup.t.changes.add(dup.t.changes.get(0));rejected(dup,"duplicate app change admitted");
  HomeCase stale=new HomeCase();stale.f.b.token="reused";rejected(stale,"reused canonical id admitted");
  System.out.println("actual live Home transition checks="+checks+" passed");
 }
}''',
'com/jiusis/mistep/LivePairRegression.java': r'''package com.jiusis.mistep;
import java.util.*;import android.graphics.Rect;import com.android.wm.shell.ShellTaskOrganizer.TaskListener;
public class LivePairRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 public static class Info {int taskId,userId=0,displayId=0,mode,type=1;Object token;boolean parent;Info(int id,Object t,int m){taskId=id;token=t;mode=m;}public boolean hasParentTask(){return parent;}public int getWindowingMode(){return mode;}public int getActivityType(){return type;}}
 public static class Surface {int id,releases;boolean valid=true;Surface(int id){this.id=id;}public boolean isValid(){return valid;}public int getLayerId(){return id;}public void release(){releases++;valid=false;}}
 public static class Appeared {Info info;Surface leash;Appeared(Info i){info=i;leash=new Surface(i.taskId);}public Info getTaskInfo(){return info;}public Surface getLeash(){return leash;}}
 public static class IntMap {Map<Integer,Object> map=new LinkedHashMap<>();public Object get(int k){return map.get(k);}public void put(int k,Object v){map.put(k,v);}public void remove(int k){map.remove(k);}public int size(){return map.size();}public Object valueAt(int i){return new ArrayList<>(map.values()).get(i);}public int keyAt(int i){return new ArrayList<>(map.keySet()).get(i);}}
 public static class Fallback implements TaskListener {int appeared,vanished;public void onTaskAppeared(Object i,Object s){appeared++;}public void onTaskVanished(Object i){vanished++;}}
 public static class Controller implements TaskListener {Object mTaskOrganizer;boolean mTaskNotFound;int resets;public Object getPendingInfo(){return mPendingInfo;}public void resetTaskInfo(){resets++;mTaskToken=mTaskInfo=mPendingInfo=null;mTaskLeash=null;mTaskNotFound=false;}Object mTaskToken,mTaskInfo,mPendingInfo,mCaptionInsetsOwner;Rect mCaptionInsets;boolean mSurfaceCreated=true;Surface mTaskLeash,mSurfaceControl=new Surface(500);int vanished,appeared,failVanish;boolean registered=true;public void onTaskAppeared(Object i,Object s){appeared++;mPendingInfo=i;}public void onTaskVanished(Object raw){if(failVanish-->0)throw new IllegalStateException("injected vanish failure");Info i=(Info)raw;if(mTaskToken!=null&&mTaskToken.equals(i.token)){vanished++;mTaskLeash.release();mTaskLeash=null;mTaskInfo=null;mTaskToken=null;mPendingInfo=null;}}}
 public static class Organizer {Object mLock=new Object();IntMap mTasks=new IntMap(),mTaskListeners=new IntMap();Fallback fullscreen=new Fallback();int failRemove;public Object getTaskListener(Object raw,boolean launch){Info i=(Info)raw;Object explicit=mTaskListeners.get(i.taskId);return explicit!=null?explicit:(i.mode==1?fullscreen:null);}
  public void addListenerForTaskId(TaskListener listener,int id){if(mTaskListeners.get(id)!=null)throw new IllegalStateException("occupied");Appeared a=(Appeared)mTasks.get(id);TaskListener old=(TaskListener)getTaskListener(a.info,false);mTaskListeners.put(id,listener);updateTaskListenerIfNeeded(a.info,a.leash,old,listener);}
  public boolean updateTaskListenerIfNeeded(Info info,Surface leash,TaskListener old,TaskListener next){if(old==next)return false;if(old!=null&&!old.onNewTaskListenerDetected(info,next)){if(next!=null)next.onTaskVanished(info);mTaskListeners.put(info.taskId,old);return false;}if(old!=null)old.onTaskVanished(info);if(next!=null)next.onTaskAppeared(info,leash);return true;}
  public void removeListener(TaskListener old){List<Appeared> affected=new ArrayList<>();for(Object value:mTasks.map.values()){Appeared a=(Appeared)value;if(getTaskListener(a.info,false)==old)affected.add(a);}mTaskListeners.map.values().removeIf(v->v==old);if(failRemove-->0)throw new IllegalStateException("injected remove failure after mapping change");for(Appeared a:affected)updateTaskListenerIfNeeded(a.info,a.leash,null,(TaskListener)getTaskListener(a.info,false));}
 }
 public static class Tx {List<String> ops=new ArrayList<>();public Tx reparent(Object s,Object p){ops.add("parent:"+((Surface)s).id+":"+((Surface)p).id);return this;}public Tx setMatrix(Object s,float a,float b,float c,float d){ops.add("matrix:"+((Surface)s).id+":"+a);return this;}public Tx setPosition(Object s,float x,float y){return this;}public Tx setAlpha(Object s,float a){return this;}public Tx setWindowCrop(Object s,Rect r){return this;}public Tx show(Object s){ops.add("show:"+((Surface)s).id);return this;}public Tx hide(Object s){ops.add("hide:"+((Surface)s).id);return this;}}
 public static class TaskViews {List<Object> mPending=new ArrayList<>();int prepares,registers,unregisters,failRegister,failPrepare;boolean falsePrepare,visible=true;public void updateVisibilityState(Controller c,boolean v){visible=v;}public void registerTaskView(Controller c){if(failRegister-->0)throw new IllegalStateException("injected register failure");c.registered=true;registers++;}public void unregisterTaskView(Controller c){c.registered=false;unregisters++;}public boolean prepareOpenAnimation(Controller c,boolean fresh,Tx start,Tx finish,Info info,Surface canonical,Object wct){prepares++;check(c.mTaskToken==null||c.mTaskToken.equals(info.token),"overwrote occupied controller");c.mTaskInfo=info;c.mTaskToken=info.token;c.mTaskLeash=new Surface(canonical.id);if(failPrepare-->0)throw new IllegalStateException("injected prepare after binding");if(falsePrepare){((android.window.WindowContainerTransaction)wct).setHidden(info.token,true);visible=false;return false;}return true;}}
 static class Fixture {Organizer o=new Organizer();TaskViews views=new TaskViews();Controller c=new Controller();Info a=new Info(11,"A",1),b=new Info(12,"B",6);Surface oldCopy=new Surface(12);MiStepLiveTaskPair pair;Fixture(){c.mTaskOrganizer=o;o.mTaskListeners.put(-2,o.fullscreen);o.mTasks.put(11,new Appeared(a));o.mTasks.put(12,new Appeared(b));o.mTaskListeners.put(12,c);c.mTaskToken=b.token;c.mTaskInfo=b;c.mTaskLeash=oldCopy;pair=new MiStepLiveTaskPair(o,views,c,11,"A",12,"B",0,new Surface(1),c.mSurfaceControl,new Rect(0,0,1200,2608),s->{});}void arm()throws Exception{pair.arm(LivePairRegression.class.getClassLoader());}void commit(){a.mode=6;b.mode=1;}}
 public static void main(String[]args)throws Exception {
  Fixture f=new Fixture();check(f.pair.preflight(),"valid pair rejected");Object wct=f.pair.windowTransaction(null);List<String> ops=((android.window.WindowContainerTransaction)wct).ops;check(ops.contains("mode:A:6")&&ops.contains("mode:B:0"),"one WCT lacks both roles");check(ops.contains("focusable:A:true")&&ops.contains("focusable:B:true"),"both visible roles must retain multi-resume eligibility");f.arm();check(f.o.getTaskListener(f.a,false)!=f.o.fullscreen&&f.c.mTaskToken.equals("B"),"A lease disturbed B");check(f.o.fullscreen.vanished==1,"native A owner was not retired once");f.commit();Tx start=new Tx(),finish=new Tx();f.pair.prepare(start,finish);check(f.c.vanished==1&&f.oldCopy.releases==1,"B copied handle not retired exactly once");check(((Appeared)f.o.mTasks.get(12)).leash.releases==0,"canonical B released");check(f.pair.sideOwned()&&f.c.mTaskToken.equals("A"),"same controller did not own A");check(f.views.registers==1&&f.views.unregisters==1&&f.views.prepares==1,"TaskView repository lifecycle missing");f.pair.finish(finish,.2473f);check(finish.ops.contains("parent:12:1")&&finish.ops.contains("parent:11:500"),"finish real parents wrong");check(finish.ops.contains("matrix:12:1.0")&&finish.ops.contains("matrix:11:0.2473"),"finish matrices wrong");check(f.pair.finishWindowTransaction()!=null,"finish WCT lost caption/bounds state");f.pair.recover(new Tx(),.2473f);check(f.c.vanished==1,"completed pair recovered twice");
  Fixture abort=new Fixture();abort.arm();abort.pair.recover(new Tx(),.2473f);check(abort.o.getTaskListener(abort.a,false)==abort.o.fullscreen,"pre-WCT A owner not returned");check(abort.c.mTaskToken.equals("B")&&abort.c.vanished==0,"pre-WCT abort retired B");
  Fixture consumed=new Fixture();consumed.arm();consumed.commit();Tx recovery=new Tx();consumed.pair.recover(recovery,.2473f);check(consumed.pair.sideOwned()&&consumed.c.vanished==1,"consumed committed pair not finalized");check(recovery.ops.contains("parent:12:1"),"consumed B left under stale TaskView");
  Fixture stale=new Fixture();stale.a.token="newA";check(!stale.pair.preflight(),"reused A id admitted");
  Fixture wrongOwner=new Fixture();wrongOwner.o.mTaskListeners.put(13,wrongOwner.c);check(!wrongOwner.pair.preflight(),"multi-task controller removeListener admitted");
  Fixture pending=new Fixture();pending.views.mPending.add(new Object());check(!pending.pair.preflight(),"existing TaskView transition raced new handler");
  Fixture foreign=new Fixture();foreign.a.userId=999;check(!foreign.pair.preflight(),"different user pair admitted");
  Fixture home=new Fixture();home.a.type=2;check(!home.pair.preflight(),"Home admitted into pair");
  Fixture noSurface=new Fixture();noSurface.c.mSurfaceCreated=false;check(!noSurface.pair.preflight(),"destroyed SurfaceView admitted");
  Fixture falseOpen=new Fixture();falseOpen.arm();falseOpen.commit();falseOpen.views.falsePrepare=true;try{falseOpen.pair.prepare(new Tx(),new Tx());throw new AssertionError("false native prepare accepted");}catch(IllegalStateException expected){}falseOpen.pair.recover(new Tx(),.2473f);check(falseOpen.pair.sideOwned()&&falseOpen.views.visible,"native false-prepare visibility was not restored");List<String> hidden=((android.window.WindowContainerTransaction)falseOpen.pair.finishWindowTransaction()).ops;check(hidden.get(hidden.size()-1).equals("hidden:A:false"),"finish WCT still hides A after false prepare");
  for(int stage=0;stage<4;stage++){Fixture broken=new Fixture();broken.arm();broken.commit();if(stage==0)broken.o.failRemove=1;if(stage==1)broken.c.failVanish=1;if(stage==2)broken.views.failRegister=1;if(stage==3)broken.views.failPrepare=1;try{broken.pair.prepare(new Tx(),new Tx());throw new AssertionError("injected preparation failure not thrown");}catch(IllegalStateException expected){}Tx recovered=new Tx();broken.pair.recover(recovered,.2473f);check(broken.pair.sideOwned()&&broken.c.registered,"partial lifecycle failure stranded native owner at "+stage);check(broken.oldCopy.releases==1,"copied B handle released twice at "+stage);check(recovered.ops.contains("parent:12:1"),"partial lifecycle recovery did not restore B at "+stage);}
  Tx retryFinish=new Tx();f.pair.recover(retryFinish,.2473f);check(retryFinish.ops.contains("parent:12:1")&&retryFinish.ops.contains("parent:11:500"),"staged closed finish prevented failure transaction recovery");
  Fixture reverse=new Fixture();reverse.arm();reverse.commit();List<String> inverse=((android.window.WindowContainerTransaction)reverse.pair.reverseWindowTransaction()).ops;check(inverse.contains("focusable:A:true")&&inverse.contains("focusable:B:true"),"inverse swap must also retain side multi-resume eligibility");reverse.a.mode=1;reverse.b.mode=6;check(!reverse.pair.originalRoles(),"stale original role object mistaken for callback acknowledgement");TaskListener held=(TaskListener)reverse.o.getTaskListener(reverse.a,false);held.onTaskInfoChanged(reverse.a);check(reverse.pair.originalRoles(),"original role callback was not acknowledged");reverse.pair.recover(new Tx(),.2473f);check(reverse.o.getTaskListener(reverse.a,false)==reverse.o.fullscreen,"confirmed inverse roles did not return fullscreen listener");
  Fixture late=new Fixture();late.arm();late.commit();late.pair.reverseWindowTransaction();late.pair.releaseOriginalLeaseWhenSeen();TaskListener lateOwner=(TaskListener)late.o.getTaskListener(late.a,false);lateOwner.onTaskInfoChanged(late.a);check(late.o.getTaskListener(late.a,false)==lateOwner,"unconfirmed mode6 prematurely lost temporary owner");late.a.mode=1;late.b.mode=6;lateOwner.onTaskInfoChanged(late.a);check(late.o.getTaskListener(late.a,false)==late.o.fullscreen,"late original role callback leaked task listener");
  System.out.println("actual live pair ownership checks="+checks+" passed");
 }
}'''
}
paths=[]
for relative, text in FILES.items():
    path=OUT/relative
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text,encoding='utf8')
    paths.append(str(path))
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),
                str(SRC/'LiveSwapGeometry.java'),str(SRC/'MiStepLiveTaskPair.java'),str(SRC/'MiStepLiveHomeTask.java'),*paths],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.LivePairRegression'],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.LiveHomeRegression'],check=True)
