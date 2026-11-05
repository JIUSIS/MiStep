"""Exercise actual compatibility code against missing OS3 methods and native failures."""
from pathlib import Path
import os,subprocess,sys
r=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([sys.executable,'-X','utf8',str(r/'tools/live-collect-jvm/run.py'),str(out)],check=True)
source=out/'com/jiusis/mistep/Os3ShellRegression.java'
source.write_text(r'''package com.jiusis.mistep;
import java.util.*;import android.graphics.Rect;
import com.jiusis.mistep.LivePairRegression.*;
import com.android.wm.shell.ShellTaskOrganizer.TaskListener;
public class Os3ShellRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 interface Action {void run();}
 static void rejects(Action action,String why){try{action.run();throw new AssertionError(why);}catch(IllegalStateException|IllegalArgumentException expected){checks++;}}
 public static class Table {
  final TreeMap<Integer,Object> values=new TreeMap<>();
  public Object get(int id){return values.get(id);}public void put(int id,Object v){values.put(id,v);}
  public int size(){return values.size();}public int keyAt(int i){return new ArrayList<>(values.keySet()).get(i);}
  public Object valueAt(int i){return values.get(keyAt(i));}public void removeAt(int i){values.remove(keyAt(i));}
 }
 public static class Os3Organizer {
  Object mLock=new Object();Table mTaskListeners=new Table(),mTasks=new Table();Fallback fullscreen=new Fallback();
  int intercepts;boolean lastIntercept;
  public Object getTaskListener(Object raw,boolean launch){Info info=(Info)raw;Object own=mTaskListeners.get(info.taskId);return own!=null?own:info.mode==1?fullscreen:null;}
  public void setInterceptBackPressedOnTaskRoot(Object token,boolean value){intercepts++;lastIntercept=value;}
 }
 public static class Os3Views {
  Map<Object,State>mTaskViews=new IdentityHashMap<>();public static class State {boolean mVisible;}
  NativeTransitions mTransitions=new NativeTransitions();
  List<Object>mPending=new ArrayList<>();int starts;boolean hidden;
  public void startNextTransition(){starts++;}
  public void prepareOpenAnimation(Controller c,boolean fresh,Tx start,Tx finish,Info info,Surface leash,Object wct){c.mTaskInfo=info;c.mTaskToken=info.token;c.mTaskLeash=new Surface(leash.id);if(hidden)c.mSurfaceCreated=false;}
  public static class PendingTransition {int type;Object wct,owner,cookie;PendingTransition(int t,Object w,Object o,Object c){type=t;wct=w;owner=o;cookie=c;}}
 }
 public static class ClosingController {Object mTaskInfo=new Object();int removals;public void notifyTaskRemovalStarted(Object info){removals++;}}
 public static class Display {Object mMagnificationSpec,replayed,spec;int pendingApplied;public void applyMagnificationSpec(Object transaction,Object value){replayed=transaction;spec=value;}public void reapplyMagnificationSpec(){pendingApplied++;}}
 public static class NativeDisplay extends Display {int nativeCalls;public void reapplyMagnificationSpec(Object transaction){nativeCalls++;}}
 public static class NativeTransitions {int type,calls;Object wct;public Object startTransition(int t,Object w,Object handler){type=t;wct=w;calls++;return null;}}
 public static class OldConstraints {Object context;int xml;public void load(int xml,Object context){this.context=context;this.xml=xml;}}
 public static class NewConstraints {Object context;int xml;public void load(Object context,int xml){this.context=context;this.xml=xml;}}
 public static class Area {Table mLeashes=new Table();}
 public static class LegacyOrganizer extends Os3Organizer {public void addListenerForTaskId(Object owner,int id){throw new IllegalStateException("native refused");}}
 public static class BooleanViews {public boolean prepareOpenAnimation(Object...ignored){return false;}public boolean prepareOpenAnimation(Object a,boolean b,Object c,Object d,Object e,Object f,Object g){return false;}}
 public static void main(String[]args)throws Exception {
  Os3Organizer o=new Os3Organizer();Info a=new Info(11,"A",1),b=new Info(12,"B",1);Appeared aa=new Appeared(a),bb=new Appeared(b);o.mTasks.put(11,aa);o.mTasks.put(12,bb);o.mTaskListeners.put(-2,o.fullscreen);
  Fallback owner=new Fallback();ShellCompat.call(o,"addListenerForTaskId",owner,11);
  check(o.getTaskListener(a,false)==owner&&owner.appeared==1&&o.fullscreen.vanished==1,"exact adoption missed lifecycle");
  check(o.mTaskListeners.get(-2)==o.fullscreen&&o.getTaskListener(b,false)==o.fullscreen,"global or unrelated owner changed");
  rejects(()->ShellCompat.call(o,"addListenerForTaskId",new Fallback(),11),"replaced explicit owner");
  rejects(()->ShellCompat.call(o,"addListenerForTaskId",owner,99),"unknown task admitted");
  rejects(()->ShellCompat.call(o,"addListenerForTaskId",owner,-2),"global registration admitted");
  ShellCompat.call(o,"removeListener",owner);check(o.getTaskListener(a,false)==o.fullscreen&&o.fullscreen.appeared==1&&owner.vanished==0,"remove must restore fallback without retiring old copy");
  check(aa.leash.releases==0&&bb.leash.releases==0,"canonical surface released");
  o.mTaskListeners.put(11,owner);o.mTaskListeners.put(-6,owner);rejects(()->ShellCompat.call(o,"removeListener",owner),"global listener removed");
  check(o.mTaskListeners.get(11)==owner&&o.mTaskListeners.get(-6)==owner,"validation partially mutated registrations");
  LegacyOrganizer legacy=new LegacyOrganizer();legacy.mTasks.put(11,aa);rejects(()->ShellCompat.call(legacy,"addListenerForTaskId",owner,11),"native refusal bypassed");check(legacy.mTaskListeners.size()==0,"native failure retried through fallback");
  Controller c=new Controller();Os3Views views=new Os3Views();Object wct=new Object();
  check(Boolean.TRUE.equals(ShellCompat.call(views,"prepareOpenAnimation",c,true,new Tx(),new Tx(),a,aa.leash,wct)),"void native binding falsely rejected");
  check(c.mTaskInfo==a&&c.mTaskToken.equals(a.token)&&c.mTaskLeash.id==aa.leash.id,"void native task binding damaged");
  views.hidden=true;rejects(()->ShellCompat.call(views,"prepareOpenAnimation",c,true,new Tx(),new Tx(),a,aa.leash,wct),"hidden native binding accepted");
  check(Boolean.FALSE.equals(ShellCompat.call(new BooleanViews(),"prepareOpenAnimation",c,true,new Tx(),new Tx(),a,aa.leash,wct)),"native false overwritten");
  Object tx=new Object();check(ShellCompat.transaction(o,tx,"setInterceptBackPressedOnTaskRoot",a.token,true)==tx&&o.intercepts==1&&o.lastIntercept,"OS3 back interception missing");
  ShellCompat.transaction(o,tx,"setDisablePip",a.token,false);ShellCompat.transaction(o,tx,"setTaskForceExcludedFromRecents",a.token,false);
  rejects(()->ShellCompat.transaction(o,tx,"setDisablePip",a.token,true),"unsupported positive flag ignored");
  Area area=new Area();area.mLeashes.put(0,aa.leash);check(ShellCompat.call(area,"getDisplayAreaLeash",0)==aa.leash,"native RootArea leash lost");
  ClosingController close=new ClosingController();ShellCompat.call(views,"lambda$moveTaskViewToFullscreen$5",tx,close);Os3Views.PendingTransition pending=(Os3Views.PendingTransition)views.mPending.get(0);
  check(views.starts==1&&close.removals==1&&pending.type==6&&pending.wct==tx&&pending.owner==close&&pending.cookie==null,"fullscreen handoff did not use native pending transition");
  Object nativeTx=new Object(),spec=new Object();Display display=new Display();display.mMagnificationSpec=spec;
  ShellCompat.call(display,"reapplyMagnificationSpec",nativeTx);check(display.replayed==nativeTx&&display.spec==spec&&display.pendingApplied==0,"finish zoom replay escaped the exact transaction");
  display.mMagnificationSpec=null;ShellCompat.call(display,"reapplyMagnificationSpec",new Object());check(display.replayed==nativeTx,"inactive display replay mutated surfaces");
  NativeDisplay nativeDisplay=new NativeDisplay();ShellCompat.call(nativeDisplay,"reapplyMagnificationSpec",nativeTx);check(nativeDisplay.nativeCalls==1&&nativeDisplay.replayed==null,"OS4 native replay was bypassed");
  Os3Views.State state=new Os3Views.State();views.mTaskViews.put(c,state);ShellCompat.call(views,"updateVisibilityState",c,true);check(state.mVisible,"visible task remains hidden in native repository");
  ShellCompat.call(views,"updateVisibilityState",new Object(),false);check(views.mTaskViews.size()==1&&state.mVisible,"missing task visibility changed another controller");
  views.hidden=false;c.mSurfaceCreated=true;ShellCompat.call(views,"startRootTask",c,a,aa.leash,wct);
  check(views.mTransitions.calls==1&&views.mTransitions.type==6&&views.mTransitions.wct==wct&&state.mVisible,"resume did not bind existing task before native CHANGE");
  check(android.view.SurfaceControl.Transaction.applies==1&&android.view.SurfaceControl.Transaction.closes==1,"resume did not commit and close only its owned transaction");
  Object ctx=new Object();OldConstraints oldConstraints=new OldConstraints();NewConstraints newConstraints=new NewConstraints();ShellCompat.loadConstraints(oldConstraints,ctx,42);ShellCompat.loadConstraints(newConstraints,ctx,43);check(oldConstraints.context==ctx&&oldConstraints.xml==42&&newConstraints.context==ctx&&newConstraints.xml==43,"constraint XML loader changed resources or argument order");
  // Run the actual two-task ownership implementation with absent organizer APIs and void native open.
  o=new Os3Organizer();a=new Info(11,"A",1);b=new Info(12,"B",6);aa=new Appeared(a);bb=new Appeared(b);o.mTasks.put(11,aa);o.mTasks.put(12,bb);o.mTaskListeners.put(-2,o.fullscreen);c=new Controller();c.mTaskOrganizer=o;c.mTaskInfo=b;c.mTaskToken=b.token;c.mTaskLeash=new Surface(12);o.mTaskListeners.put(12,c);
  Os3PairViews pairViews=new Os3PairViews();MiStepLiveTaskPair pair=new MiStepLiveTaskPair(o,pairViews,c,11,"A",12,"B",0,new Surface(1),c.mSurfaceControl,new Rect(0,0,1200,2608),s->{});
  check(pair.preflight(),"OS3 actual pair preflight refused");pair.arm(Os3ShellRegression.class.getClassLoader());a.mode=6;b.mode=1;pair.prepare(new Tx(),new Tx());check(pair.sideOwned()&&o.getTaskListener(a,false)==c,"OS3 actual pair failed ownership transfer");pair.finish(new Tx(),.25f);check(o.mTaskListeners.get(-2)==o.fullscreen&&aa.leash.releases==0&&bb.leash.releases==0,"OS3 pair damaged organizer/canonical surfaces");
  System.out.println("actual OS3 missing-interface/lifecycle checks="+checks+" passed");
 }
 public static class Os3PairViews extends Os3Views {public void registerTaskView(Controller c){c.registered=true;}public void unregisterTaskView(Controller c){c.registered=false;}public void updateVisibilityState(Controller c,boolean value){}}
}
''',encoding='utf8')
# Java lambdas require final references; scope mutable setup separately from rejection captures.
s=source.read_text(encoding='utf8')
s=s.replace('  LegacyOrganizer legacy=', '  LegacyOrganizer legacy=')
# Use final aliases for values that are reassigned for the integrated scenario.
s=s.replace('  Fallback owner=', '  final Os3Organizer first=o; final Info firstInfo=a; final Appeared firstAppeared=aa;\n  Fallback owner=')
s=s.replace('()->ShellCompat.call(o,','()->ShellCompat.call(first,').replace('a.token,true),"unsupported','firstInfo.token,true),"unsupported')
s=s.replace('  views.hidden=true;', '  final Controller firstController=c; final Info firstTask=a;final Appeared firstSurface=aa;\n  views.hidden=true;')
s=s.replace('()->ShellCompat.call(views,"prepareOpenAnimation",c,true,new Tx(),new Tx(),a,aa.leash,wct)', '()->ShellCompat.call(views,"prepareOpenAnimation",firstController,true,new Tx(),new Tx(),firstTask,firstSurface.leash,wct)')
s=s.replace('()->ShellCompat.transaction(o,tx,','()->ShellCompat.transaction(first,tx,')
s=s.replace('  System.out.println("actual OS3', r'''  Object context=new Object();FactoryOwner ownerFactory=new FactoryOwner();Factory factory=new Factory(ownerFactory);List<Runnable> callbacks=new ArrayList<>();List<Object> created=new ArrayList<>();
  ShellCompat.createTaskView(factory,context,callbacks::add,created::add);
  check(created.isEmpty()&&ownerFactory.queue.size()==1,"factory escaped native shell executor");ownerFactory.queue.remove(0).run();
  check(created.isEmpty()&&callbacks.size()==1,"factory callback executor bypassed");callbacks.remove(0).run();
  com.android.wm.shell.taskview.TaskView made=(com.android.wm.shell.taskview.TaskView)created.get(0);
  check(made.context==context&&made.transitions==ownerFactory.mTaskViewController&&made.controller.organizer==ownerFactory.mTaskOrganizer&&made.controller.sync==ownerFactory.mSyncQueue,"factory lost existing native dependencies");
  System.out.println("actual OS3''')
s=s.replace(' public static class Os3PairViews', r''' public static class FactoryOwner {List<Runnable>queue=new ArrayList<>();Object mTaskOrganizer=new Object(),mTaskViewController=new Object(),mSyncQueue=new Object();java.util.concurrent.Executor mShellExecutor=queue::add;}
 public static class Factory {Object this$0;Factory(Object owner){this$0=owner;}}
 public static class Os3PairViews''')
source.write_text(s,encoding='utf8')
additional=[]
file=out/'android/view/SurfaceControl.java';file.parent.mkdir(parents=True,exist_ok=True);file.write_text('package android.view;public class SurfaceControl {public static class Transaction extends com.jiusis.mistep.LivePairRegression.Tx {public static int applies,closes;public void apply(){applies++;}public void close(){closes++;}}}',encoding='utf8');additional.append(str(file))
for name,body in {
 'TaskViewTaskController': 'public final Object organizer,sync;public TaskViewTaskController(Object context,Object o,Object transitions,Object s){organizer=o;sync=s;}',
 'TaskView': 'public final Object context,transitions;public final TaskViewTaskController controller;public TaskView(Object c,Object t,TaskViewTaskController own){context=c;transitions=t;controller=own;}'
}.items():
 file=out/('com/android/wm/shell/taskview/'+name+'.java');file.parent.mkdir(parents=True,exist_ok=True);file.write_text('package com.android.wm.shell.taskview;public class '+name+'{'+body+'}',encoding='utf8');additional.append(str(file))
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(out),'-d',str(out),str(source),*additional],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.Os3ShellRegression'],check=True)
