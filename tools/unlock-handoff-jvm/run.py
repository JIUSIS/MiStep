"""Execute production authenticated-unlock coordinator with controlled WM acknowledgements."""
from pathlib import Path
import os,subprocess,sys
root=Path(__file__).resolve().parents[2]
src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/os/Looper.java':'''package android.os;public class Looper {public static Object MAIN=new Object(),current=MAIN;public static Object myLooper(){return current;}}''',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {
 public Object getLooper(){return Looper.MAIN;}public boolean post(Runnable r){r.run();return true;}
 public List<Runnable> delayed=new ArrayList<>();public boolean postDelayed(Runnable r,long ms){delayed.add(r);return true;}
 public void timeout(){for(Runnable r:List.copyOf(delayed))r.run();delayed.clear();}}''',
'android/content/BroadcastReceiver.java':'''package android.content;public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}''',
'android/content/IntentFilter.java':'''package android.content;public class IntentFilter {public final String action;public IntentFilter(String a){action=a;}}''',
'android/content/Intent.java':'''package android.content;import java.util.*;public class Intent {
 public static final int FLAG_RECEIVER_REGISTERED_ONLY=1;public final String action;public String pkg;public int flags;public Map<String,Object> extras=new HashMap<>();
 public Intent(String a){action=a;}public Intent setPackage(String p){pkg=p;return this;}public Intent addFlags(int f){flags|=f;return this;}
 public Intent putExtra(String k,long v){extras.put(k,v);return this;}public Intent putExtra(String k,int v){extras.put(k,v);return this;}
 public Intent putExtra(String k,boolean v){extras.put(k,v);return this;}public Intent putExtra(String k,Object v){extras.put(k,v);return this;}
 public long getLongExtra(String k,long d){return extras.containsKey(k)?(Long)extras.get(k):d;}public int getIntExtra(String k,int d){return extras.containsKey(k)?(Integer)extras.get(k):d;}
 public boolean getBooleanExtra(String k,boolean d){return extras.containsKey(k)?(Boolean)extras.get(k):d;}public String getStringExtra(String k){return (String)extras.get(k);}
 public <T>T getParcelableExtra(String k,Class<T> t){return t.cast(extras.get(k));}}''',
'android/content/Context.java':'''package android.content;import java.util.*;import android.os.Handler;
 public class Context {public static final int RECEIVER_EXPORTED=2;public BroadcastReceiver receiver;public int registered,unregistered;public boolean failRegister,failSend;public List<String> events=new ArrayList<>();public Intent sent;public String permission;
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Handler h,int flags){if(failRegister)throw new IllegalStateException();receiver=r;permission=p;registered++;return null;}
 public void unregisterReceiver(BroadcastReceiver r){if(receiver==r){unregistered++;receiver=null;}}
 public void sendBroadcast(Intent i,String p){if(failSend)throw new IllegalStateException();sent=i;events.add(i.action);}
 public void deliver(Intent i){if(receiver!=null)receiver.onReceive(this,i);}}''',
'android/view/SurfaceControl.java':'''package android.view;public class SurfaceControl {public static class Transaction implements AutoCloseable {public long mNativeObject=11;public int closes,merges,applies;public Transaction merge(Object other){merges++;return this;}public void apply(){applies++;}public void close(){closes++;mNativeObject=0;}}}''',
'com/jiusis/mistep/UnlockHandoffRegression.java':r'''package com.jiusis.mistep;
import android.content.*;import android.os.Handler;import android.view.SurfaceControl.Transaction;import java.util.*;
public class UnlockHandoffRegression {
 static int checks;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static Context ctx;static Handler handler;static int ready,hides;static MiStepUnlockHandoff current;static LiveSwapZoom.Ack received;
 static void fresh(){ctx=new Context();handler=new Handler();ready=0;hides=0;received=null;current=new MiStepUnlockHandoff(ctx,handler,.7f,0,606,a->{ready++;received=a;check(hides==0,"native shade hidden before retained Host was ready");},()->{hides++;ctx.events.add("NATIVE_HIDE");});}
 static Intent ack(long generation,boolean committed,float scale,Transaction replay){return new Intent(LiveSwapZoomProtocol.ACK)
  .putExtra(LiveSwapZoomProtocol.GENERATION,generation).putExtra(LiveSwapZoomProtocol.TRANSACTION,99L)
  .putExtra(LiveSwapZoomProtocol.SCALE,Float.floatToIntBits(scale)).putExtra(LiveSwapZoomProtocol.X,0)
  .putExtra(LiveSwapZoomProtocol.Y,Float.floatToIntBits(606)).putExtra(LiveSwapZoomProtocol.COMMITTED,committed)
  .putExtra(LiveSwapZoomProtocol.FINISH_REPLAY,replay);}
 static long generation(){return ctx.sent.getLongExtra(LiveSwapZoomProtocol.GENERATION,0);}
 public static void main(String[] args){
  fresh();current.start();long generation=generation();
  check(current.active()&&hides==0&&ready==0,"native hide proceeds before WMS commit");
  check(ctx.permission.equals(LiveSwapZoomProtocol.PERMISSION)&&ctx.sent.pkg.equals("android"),"bridge must be scoped and permission protected");
  check(ctx.sent.getBooleanExtra(MiStepUnlockHandoff.PHYSICAL_KEYGUARD,false),"native shade must retain physical geometry while WM prepares workspace");
  ctx.deliver(ack(generation+1,true,.7f,new Transaction()));check(ready==0&&hides==0,"wrong generation grants reveal");
  Transaction replay=new Transaction();ctx.deliver(ack(generation,true,.7f,replay));
  check(ready==1&&hides==0&&current.active(),"WMS commit is not the existing Host hardware frame");
  check(received.transactionId==99&&received.committed&&replay.closes==1,"exact WM operations consumed and closed");
  ctx.deliver(ack(generation,true,.7f,new Transaction()));check(ready==1,"duplicate WM acknowledgement delivered twice");
  current.finish();check(hides==1&&!current.active()&&ctx.unregistered==1,"Host frame releases native hide once");
  check(ctx.events.get(ctx.events.size()-2).equals("NATIVE_HIDE")&&ctx.events.get(ctx.events.size()-1).equals(MiStepUnlockHandoff.RELEASE),"native hide precedes physical-shade release");
  current.finish();handler.timeout();check(hides==1&&ctx.unregistered==1,"late watchdog repeats native hide");
  for(int failure=0;failure<5;failure++){
   fresh();current.start();long g=generation();Transaction t=new Transaction();if(failure==3)t.mNativeObject=0;
   Intent response=ack(g,failure!=0,failure==1?.9f:.7f,failure==2?null:t);
   if(failure==4)response.putExtra(LiveSwapZoomProtocol.ERROR,"WM mismatch");ctx.deliver(response);
   check(ready==0&&hides==1&&!current.active(),"failed WM commit cannot reveal retained Host");
  }
  fresh();current.start();handler.timeout();check(ready==0&&hides==1&&!current.active(),"missing WM acknowledgement must release native system");
  fresh();current.start();ctx.deliver(ack(generation(),true,.7f,new Transaction()));handler.timeout();
  check(ready==1&&hides==1,"missing Host frame cannot leave native hide blocked forever");
  fresh();current.start();current.cancel();handler.timeout();check(hides==0&&!current.active()&&ctx.unregistered==1,"relock cancels stale native hide without revealing previous session");
  fresh();ctx.failRegister=true;current.start();check(hides==1&&!current.active(),"receiver registration failure cannot block native hide");
  fresh();ctx.failSend=true;current.start();check(hides==1&&!current.active(),"dispatch failure cannot block native hide");
  System.out.println("Production authenticated unlock handoff: "+checks+" checks passed");
 }
}'''
}
host_source=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
start=host_source.index('    static boolean handoffNativeUnlock(')
end=host_source.index('{',start)+1;depth=1
while depth:
 depth+=(host_source[end]=='{')-(host_source[end]=='}');end+=1
files['com/jiusis/mistep/MiStepHost.java']=r'''package com.jiusis.mistep;
import android.content.Context;import android.os.*;import android.view.SurfaceControl.Transaction;import java.util.*;
public class MiStepHost {
 static final int ACTIVE=2,RESUMING=1;static MiStepHost sInstance;Context ctx=new Context();Handler main=new Handler();
 boolean retainedForLock=true,keyguardWindowHidden=true,keyguardLocked=true,screenOn,workspaceEntering=true,zoomed;
 int scene=ACTIVE;long sceneEpoch=7,workspaceGeneration,zoomAnimUntil=800;float alpha;
 MiStepUnlockHandoff unlockHandoff;MiStepLayout layout=new MiStepLayout();Root root=new Root();int parents,closes;
 static class MiStepLayout {float mainScale=.7f,mainOffsetX,mainOffsetY=606;}
 static class Root {Runnable frame;Root getViewTreeObserver(){return this;}void registerFrameCommitCallback(Runnable r){frame=r;}void invalidate(){}void commit(){Runnable r=frame;frame=null;if(r!=null)r.run();}}
 static class MiStepEntryGuard {static boolean unlocked=true;static boolean canOpen(Context c){return unlocked;}}
 static class XposedHelpers {static Object callMethod(Object o,String n,Object...args)throws Exception{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new IllegalStateException(n);}}
 static void log(String s){}void setWorkspaceAlpha(float a){alpha=a;}
 void appendWorkspaceSurfaces(Object tx,List<Long> epochs){check(((Transaction)tx).merges==1,"WM geometry and real task parents are in the same native transaction");check(epochs.contains(sceneEpoch),"scene identity lost");parents++;}
 void closeSurfaceObject(Object tx,String m){if(tx!=null){((Transaction)tx).close();closes++;}}
 void setZoomState(boolean value){zoomed=value;}boolean setKeyguardWindowHidden(boolean hidden){keyguardWindowHidden=hidden;return true;}
 ACTUAL
 static int checks,hides;static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static MiStepHost fresh(){MiStepEntryGuard.unlocked=true;Looper.current=Looper.MAIN;hides=0;sInstance=new MiStepHost();return sInstance;}
 static android.content.Intent ack(MiStepHost h){long g=h.ctx.sent.getLongExtra(LiveSwapZoomProtocol.GENERATION,0);return UnlockHandoffRegression.ack(g,true,.7f,new Transaction());}
 public static void main(String[]args){
  MiStepHost h=fresh();check(handoffNativeUnlock(()->hides++),"retained authenticated hide not deferred");
  check(hides==0&&h.keyguardLocked&&h.keyguardWindowHidden,"WM request alone revealed workspace");
  h.ctx.deliver(ack(h));check(!h.keyguardLocked&&!h.keyguardWindowHidden&&h.zoomed&&h.alpha==1&&h.parents==1&&h.closes==1,"committed WMS tuple must prepare real original Host");
  check(hides==0&&h.root.frame!=null,"WMS ack cannot replace actual Host frame commit");
  h.root.commit();check(hides==1&&h.unlockHandoff==null,"Host frame must release native hide once");
  for(int gate=0;gate<7;gate++){
   h=fresh();if(gate==0)MiStepEntryGuard.unlocked=false;if(gate==1)h.retainedForLock=false;
   if(gate==2)h.keyguardWindowHidden=false;if(gate==3)h.root=null;if(gate==4)h.layout=null;if(gate==5)h.scene=0;if(gate==6)Looper.current=null;
   check(!handoffNativeUnlock(()->hides++)&&h.ctx.sent==null&&hides==0,"native hide changed without exact admission and retained owner");
  }
  h=fresh();handoffNativeUnlock(()->hides++);h.sceneEpoch++;h.ctx.deliver(ack(h));check(h.parents==0&&h.keyguardWindowHidden&&hides==1,"stale scene cannot reveal original Host");
  h=fresh();handoffNativeUnlock(()->hides++);MiStepEntryGuard.unlocked=false;h.ctx.deliver(ack(h));check(h.parents==0&&h.keyguardWindowHidden&&hides==0,"relock after request cannot run stale native hide or reveal workspace");
  h=fresh();handoffNativeUnlock(()->hides++);h.ctx.deliver(ack(h));MiStepEntryGuard.unlocked=false;h.root.commit();check(hides==0,"relock between WM commit and Host frame cannot dismiss new keyguard");
  System.out.println("Production Host authenticated handoff: "+checks+" checks passed");
 }
}'''.replace('ACTUAL',host_source[start:end])
paths=[]
for rel,body in files.items():
 p=out/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf8');paths.append(str(p))
for name in ['MiStepUnlockHandoff','LiveSwapZoom','LiveSwapZoomProtocol']:paths.append(str(src/(name+'.java')))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.UnlockHandoffRegression'],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.MiStepHost'],check=True)
