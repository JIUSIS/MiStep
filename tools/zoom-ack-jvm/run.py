import os
"""Run actual ZoomCommit code against deferred in-process broadcast dispatch."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jianguo/mistep'
out = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'build/tests/zoom-ack-jvm'
out.mkdir(parents=True, exist_ok=True)
source = (src / 'HostEntry.java').read_text(encoding='utf8')
start = source.index('    private static final class ZoomCommit {')
end = source.index('    /** Snapshot operations only.', start)
actual = source[start:end].replace('private static final class ZoomCommit', 'static final class ZoomCommit')
wrapper = r'''package com.jianguo.mistep;
import java.util.*;
public final class ZoomAckRegression {
 static final String SYSTEMUI="com.android.systemui";
 static ZoomTarget sZoomTarget;static ZoomCommit sLiveZoomCommit;
 static float sZoomScale=.747f,sZoomOffsetX=273,sZoomOffset=622;
 static int checks,created;static Replay latest;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void log(String ignored){}
 static Object createMagnificationFinishReplay(Object display){created++;return latest=new Replay();}
 static class ZoomTarget {Object globalLock=new Object(),handler=new Object(),display=new Object();}
 static class Intent {
  Map<String,Object> extras=new HashMap<>();Intent(String action){}Intent setPackage(String p){return this;}
  void putExtra(String k,Object v){extras.put(k,v);}
 }
 abstract static class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}
 static class Context {
  Intent pending;BroadcastReceiver result;int sends;boolean failSend;Replay received;
  void sendOrderedBroadcast(Intent i,String permission,BroadcastReceiver result,Object handler,int code,String data,Object extras){if(failSend)throw new IllegalStateException("dispatch failed");sends++;pending=i;this.result=result;}
  void sendBroadcast(Intent i,String permission){if(failSend)throw new IllegalStateException("dispatch failed");sends++;pending=i;}
  void dispatchLater(){Replay sender=(Replay)pending.extras.get(LiveSwapZoomProtocol.FINISH_REPLAY);if(sender!=null){check(sender.mNativeObject!=0,"sender closed before AMS serialized transaction");received=new Replay();}if(result!=null)result.onReceive(this,pending);}
 }
 static class Replay implements AutoCloseable,android.os.Parcelable {
  long mNativeObject=123;int closes;
  public void close(){closes++;mNativeObject=0;}
  public int describeContents(){return 0;}public void writeToParcel(android.os.Parcel p,int flags){}
 }
 ACTUAL
 static ZoomCommit commit(Context context){ZoomTarget target=new ZoomTarget();sZoomTarget=target;LiveSwapZoom.Zoom z=new LiveSwapZoom.Zoom(7,.747f,273,622);ZoomCommit c=new ZoomCommit(context,target,z);sLiveZoomCommit=c;c.transactionId=99;c.actualScale=.747f;c.actualX=273;c.actualY=622;return c;}
 public static void main(String[] args){
  Context context=new Context();ZoomCommit c=commit(context);c.finish(true,null);Replay sender=latest;
  check(sender.mNativeObject!=0&&sender.closes==0,"native snapshot released at send return");
  check(context.sends==1&&context.result!=null,"no completion-scoped ownership");
  context.dispatchLater();check(sender.closes==1&&LiveSwapZoom.replayOpen(context.received),"sender lifetime did not span deferred parcel creation");
  c.finish(true,null);check(context.sends==1&&sender.closes==1,"acknowledgement completed twice");
  Context failed=new Context();failed.failSend=true;ZoomCommit sendFailure=commit(failed);sendFailure.finish(true,null);
  check(latest.closes==1,"failed dispatch leaked native transaction");
  Context changed=new Context();ZoomCommit changedCommit=commit(changed);sZoomOffsetX=0;int before=created;changedCommit.finish(true,null);
  check(created==before&&!Boolean.TRUE.equals(changed.pending.extras.get(LiveSwapZoomProtocol.COMMITTED)),"changed tuple produced a successful snapshot");
  changed.dispatchLater();sZoomOffsetX=273;
  Context rejected=new Context();ZoomCommit failure=commit(rejected);before=created;failure.finish(false,"not committed");
  check(created==before&&!rejected.pending.extras.containsKey(LiveSwapZoomProtocol.FINISH_REPLAY),"uncommitted result created native operations");
  rejected.dispatchLater();
  System.out.println("actual deferred WMS acknowledgement checks="+checks+" passed");
 }
}'''.replace('ACTUAL', actual)
target = out / 'ZoomAckRegression.java'
target.write_text(wrapper, encoding='utf8')
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
android = Path(os.environ["MISTEP_ANDROID_JAR"])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-cp', str(android),
                '-d', str(out), str(src / 'LiveSwapZoom.java'), str(src / 'LiveSwapZoomProtocol.java'), str(target)], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out) + ';' + str(android),
                'com.jianguo.mistep.ZoomAckRegression'], check=True)
