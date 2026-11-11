"""Compile the actual native sync bridge; callbacks and transactions remain independent."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
entry = (root / 'module/src/main/java/com/jiusis/mistep/HostEntry.java').read_text(encoding='utf-8')
provider_start = entry.index('Object[] args = param.args;', entry.index('else if ("provideTaskViewFactoryController".equals(n))'))
provider_end = entry.index('} else if ("provideTaskViewFactory".equals(n)', provider_start)
provider_capture = entry[provider_start:provider_end]
files = {
    'android/view/SurfaceControl.java': '''package android.view;
public class SurfaceControl {
 public interface TransactionCommittedListener {void onTransactionCommitted();}
 public static class Transaction {
  public boolean fail;public int applies,closes;public Runnable callback;
  public Transaction addTransactionCommittedListener(java.util.concurrent.Executor e,TransactionCommittedListener c){
   if(fail)throw new IllegalStateException("commit unsupported");callback=()->e.execute(c::onTransactionCommitted);return this;}
  public void apply(){applies++;}public void close(){closes++;}
 }
}''',
    'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public class XposedHelpers {
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(NoSuchFieldException e){throw new NoSuchFieldError(o.getClass().getName()+"#"+n);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...args){
  for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)
   try{return m.invoke(o,args);}catch(Exception e){throw new RuntimeException(e);}
  throw new IllegalStateException(n);
 }
}''',
    'com/android/wm/shell/ShellTaskOrganizer.java': 'package com.android.wm.shell;public class ShellTaskOrganizer {}',
    'com/android/wm/shell/common/SyncTransactionQueue.java': '''package com.android.wm.shell.common;
public class SyncTransactionQueue {
 public interface TransactionRunnable {void runWithTransaction(android.view.SurfaceControl.Transaction tx);}
 public Object wct,mInFlight;public java.util.List<Object> mQueue=new java.util.ArrayList<>();public boolean failQueue;public TransactionRunnable callback;
 public java.util.List<String> order=new java.util.ArrayList<>();
 public void queue(Object w){order.add("queue");if(failQueue)throw new IllegalStateException("queue failed");wct=w;}
 public void runInSync(TransactionRunnable c){order.add("runInSync");callback=c;}
}''',
    'com/jiusis/mistep/SyncPreparationRegression.java': '''package com.jiusis.mistep;
import android.view.SurfaceControl;import com.android.wm.shell.common.SyncTransactionQueue;
public class SyncPreparationRegression {
 public static class Controller {public Object mTaskOrganizer;public SyncTransactionQueue mSyncQueue=new SyncTransactionQueue();}
 public static class Os4Controller {public Object mTaskOrganizer;Os4Controller(Object organizer){mTaskOrganizer=organizer;}}
 public static class EqualOrganizer extends com.android.wm.shell.ShellTaskOrganizer {
  public boolean equals(Object other){return other instanceof EqualOrganizer;}public int hashCode(){return 1;}}
 public static class InvalidController {public Object mTaskOrganizer,mSyncQueue;InvalidController(Object organizer,Object queue){mTaskOrganizer=organizer;mSyncQueue=queue;}}
 public static class Providers {
  public static Object nativeProvider(com.android.wm.shell.ShellTaskOrganizer organizer,java.util.concurrent.Executor executor,SyncTransactionQueue queue,Object transitions){return new Object();}
  public static Object wrongShape(Object organizer,java.util.concurrent.Executor executor,Object queue,Object transitions){return new Object();}
 }
 static class Ui implements java.util.concurrent.Executor {java.util.List<Runnable> pending=new java.util.ArrayList<>();
  public void execute(Runnable r){pending.add(r);}void drain(){var copy=new java.util.ArrayList<>(pending);pending.clear();copy.forEach(Runnable::run);}}
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static void capture(Object organizer,SyncTransactionQueue queue)throws Exception {
  ProviderHook.capture(Providers.class.getMethod("nativeProvider",com.android.wm.shell.ShellTaskOrganizer.class,java.util.concurrent.Executor.class,SyncTransactionQueue.class,Object.class),
   new ProviderHook.Param(new Object[]{organizer,new Ui(),queue,new Object()},new Object(),false));
 }
 static boolean rejects(Object controller)throws Exception {
  try{MiStepSyncPreparation.submit(controller,new Object(),new Ui(),()->{},e->{});return false;}
  catch(IllegalStateException|NoSuchFieldError expected){return true;}
 }
 public static void main(String[]args)throws Exception {
  Object organizer=new com.android.wm.shell.ShellTaskOrganizer();SyncTransactionQueue os4Queue=new SyncTransactionQueue();
  capture(organizer,os4Queue);Ui os4Ui=new Ui();int[] os4Ready={0},os4Failed={0};
  Object os4Wct=new Object();MiStepSyncPreparation.submit(new Os4Controller(organizer),os4Wct,os4Ui,()->os4Ready[0]++,e->os4Failed[0]++);
  check(os4Queue.wct==os4Wct,"OS4 controller without mSyncQueue uses the original provider queue");
  check(os4Queue.order.equals(java.util.List.of("queue","runInSync")),"OS4 callback follows the original WCT in the borrowed queue");
  check(os4Ready[0]==0&&os4Failed[0]==0,"OS4 submission is not readiness");
  SurfaceControl.Transaction os4Tx=new SurfaceControl.Transaction();os4Queue.callback.runWithTransaction(os4Tx);
  check(os4Ready[0]==0&&os4Ui.pending.isEmpty()&&os4Tx.applies==0&&os4Tx.closes==0,"OS4 sync reply neither commits nor takes ownership of the native transaction");
  os4Tx.callback.run();check(os4Ready[0]==0&&os4Ui.pending.size()==1,"OS4 readiness is delivered on UI after commit");
  os4Ui.drain();os4Tx.callback.run();os4Ui.drain();check(os4Ready[0]==1&&os4Failed[0]==0,"OS4 repeated native commits enable exactly one transition");
  check(rejects(new Os4Controller(new com.android.wm.shell.ShellTaskOrganizer())),"an unbound organizer cannot borrow another organizer queue");
  EqualOrganizer equalA=new EqualOrganizer(),equalB=new EqualOrganizer();capture(equalA,new SyncTransactionQueue());
  check(equalA.equals(equalB)&&rejects(new Os4Controller(equalB)),"organizer identity is required even if equals claims a match");
  check(rejects(new Object())&&rejects(new Os4Controller(null)),"missing organizer capability and null organizer fail closed");
  check(!MiStepSyncPreparation.bindProvider(new Object(),new SyncTransactionQueue())&&!MiStepSyncPreparation.bindProvider(new com.android.wm.shell.ShellTaskOrganizer(),new Object()),"unrecognized provider argument types are rejected");
  check(rejects(new InvalidController(organizer,null))&&rejects(new InvalidController(organizer,new Object())),"present but unusable controller queue never falls through to a provider queue");
  Object notBound=new com.android.wm.shell.ShellTaskOrganizer();SyncTransactionQueue untouched=new SyncTransactionQueue();
  ProviderHook.capture(Providers.class.getMethod("wrongShape",Object.class,java.util.concurrent.Executor.class,Object.class,Object.class),
   new ProviderHook.Param(new Object[]{notBound,new Ui(),untouched,new Object()},new Object(),false));
  check(rejects(new Os4Controller(notBound))&&untouched.wct==null,"a provider with different declared argument types cannot bind a queue");
  var nativeProvider=Providers.class.getMethod("nativeProvider",com.android.wm.shell.ShellTaskOrganizer.class,java.util.concurrent.Executor.class,SyncTransactionQueue.class,Object.class);
  ProviderHook.capture(nativeProvider,new ProviderHook.Param(new Object[]{notBound,new Ui(),untouched,new Object()},null,false));
  check(rejects(new Os4Controller(notBound)),"a provider without a returned factory cannot bind a queue");
  ProviderHook.capture(nativeProvider,new ProviderHook.Param(new Object[]{notBound,new Ui(),untouched,new Object()},new Object(),true));
  check(rejects(new Os4Controller(notBound)),"a failed provider cannot bind a queue");
  for(int busy=0;busy<2;busy++){
   Object busyOrganizer=new com.android.wm.shell.ShellTaskOrganizer();SyncTransactionQueue queue=new SyncTransactionQueue();capture(busyOrganizer,queue);
   if(busy==0)queue.mInFlight=new Object();else queue.mQueue.add(new Object());
   check(rejects(new Os4Controller(busyOrganizer))&&queue.wct==null&&queue.callback==null,"busy OS4 provider queue rejects before submission "+busy);
  }
  Controller preferred=new Controller();preferred.mTaskOrganizer=organizer;Object preferredWct=new Object();
  MiStepSyncPreparation.submit(preferred,preferredWct,new Ui(),()->{},e->{});
  check(preferred.mSyncQueue.wct==preferredWct&&os4Queue.wct==os4Wct,"OS2 controller queue takes priority over a matching provider binding");
  Controller c=new Controller();Ui ui=new Ui();Object wct=new Object();int[] ready={0},failed={0};
  MiStepSyncPreparation.submit(c,wct,ui,()->ready[0]++,e->failed[0]++);
  check(c.mSyncQueue.wct==wct,"existing queue receives the original WCT instance");
  check(c.mSyncQueue.order.equals(java.util.List.of("queue","runInSync")),"sync callback is enrolled after its original WCT");
  check(ready[0]==0&&failed[0]==0,"submission is not readiness");
  Object cb=c.mSyncQueue.callback;check(cb.equals(cb)&&!cb.equals(new Object())&&cb.hashCode()==System.identityHashCode(cb),"proxy Object methods stay safe");
  SurfaceControl.Transaction tx=new SurfaceControl.Transaction();c.mSyncQueue.callback.runWithTransaction(tx);
  check(ready[0]==0&&tx.applies==0&&tx.closes==0,"queue callback never claims commit or applies/closes its borrowed transaction");
  tx.callback.run();check(ready[0]==0&&ui.pending.size()==1,"native commit dispatches through the UI executor");
  ui.drain();check(ready[0]==1&&failed[0]==0,"first committed callback enables the transition");
  tx.callback.run();ui.drain();check(ready[0]==1,"duplicate commit cannot start twice");
  c=new Controller();Ui ui2=new Ui();final int[] done={0},errors={0};
  MiStepSyncPreparation.submit(c,new Object(),ui2,()->done[0]++,e->errors[0]++);
  tx=new SurfaceControl.Transaction();tx.fail=true;c.mSyncQueue.callback.runWithTransaction(tx);ui2.drain();
  check(done[0]==0&&errors[0]==1,"commit-listener failure rejects readiness on UI");
  tx=new SurfaceControl.Transaction();c.mSyncQueue.callback.runWithTransaction(tx);tx.callback.run();ui2.drain();
  check(done[0]==0&&errors[0]==1,"late commit after failure does not enable shrinking");
  c=new Controller();c.mSyncQueue.failQueue=true;boolean rejected=false;
  try{MiStepSyncPreparation.submit(c,new Object(),ui,()->{},e->{});}catch(RuntimeException expected){rejected=true;}
  check(rejected&&c.mSyncQueue.callback==null,"rejected WCT does not register a fabricated readiness callback");
  for(int busy=0;busy<2;busy++){
   c=new Controller();if(busy==0)c.mSyncQueue.mInFlight=new Object();else c.mSyncQueue.mQueue.add(new Object());
   rejected=false;try{MiStepSyncPreparation.submit(c,new Object(),ui,()->{},e->{});}catch(IllegalStateException expected){rejected=true;}
   check(rejected&&c.mSyncQueue.wct==null&&c.mSyncQueue.callback==null,"busy native queue cannot attribute a previous sync to this WCT "+busy);
  }
  System.out.println("Production native background sync checks="+checks+" passed (native draw evidence still required)");
 }
}''',
}
files['com/jiusis/mistep/ProviderHook.java'] = '''package com.jiusis.mistep;
import java.util.concurrent.Executor;
class ProviderHook {
 static Executor sShellExecutor;static void log(String message){}
 static class Param {Object[] args;Object result;boolean failed;
  Param(Object[] args,Object result,boolean failed){this.args=args;this.result=result;this.failed=failed;}
  Object getResult(){return result;}boolean hasThrowable(){return failed;}}
 static void capture(java.lang.reflect.Method m,Param param){Object r=param.getResult();
''' + provider_capture + '\n}\n}\n'
paths = []
for name, text in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')
    paths.append(str(path))
paths.append(str(root / 'module/src/main/java/com/jiusis/mistep/MiStepSyncPreparation.java'))
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'com.jiusis.mistep.SyncPreparationRegression'], check=True)
