import os
"""Run actual HostEntry surface ownership/identity cleanup against late-finish replay."""
from pathlib import Path
import subprocess
import sys

root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
text=(root/'module/src/main/java/com/jianguo/mistep/HostEntry.java').read_text(encoding='utf-8-sig')
def member(signature):
    start=text.index(signature);brace=text.index('{',start);depth,end=1,brace+1
    while depth:
        depth+=(text[end]=='{')-(text[end]=='}');end+=1
    return text[start:end]
methods='\n'.join(member(s)for s in ['    private static void rememberMagnifiedSurface(', '    private static int restoreExitedMagnificationLocked('])
hook_start=text.index('XposedBridge.hookAllMethods(transition, "finishTransition"')
hook_signature='                @Override protected void afterHookedMethod(MethodHookParam p)'
start=text.index(hook_signature,hook_start);brace=text.index('{',start);depth,end=1,brace+1
while depth:
    depth+=(text[end]=='{')-(text[end]=='}');end+=1
hook=text[start:end].replace('@Override protected void afterHookedMethod','static void onFinish')
body=r'''package com.jianguo.mistep;
import java.util.*;
public class ExitSurfaceRegression {
 static int checks,nativeLayerReconciliations;static boolean sZoomed;static Object sDisplayContent;static ZoomTarget sZoomTarget;
 static final Map<Object,Object>sMagnifiedSurfaces=new WeakHashMap<>();
 static final Map<Object,Object>sMagnificationFinishReplays=new WeakHashMap<>();
 static class Queue{ArrayDeque<Runnable>queue=new ArrayDeque<>();void post(Runnable r){queue.add(r);}void flush(){while(!queue.isEmpty())queue.remove().run();}}
 static class ZoomTarget{Object display,globalLock=new Object();Queue handler=new Queue();ZoomTarget(Object d){display=d;}}
 static class MethodHookParam{Object thisObject;boolean error;boolean hasThrowable(){return error;}MethodHookParam(Object o){thisObject=o;}}
 public static class Position{int x=0,y=0;}
 public static class Surface{boolean valid=true;float scale=1,x,y;public boolean isValid(){return valid;}}
 public static class Container{Object display,mLastMagnificationSpec;int mode=1;Surface mSurfaceControl=new Surface();Position mLastSurfacePosition=new Position();public Object getDisplayContent(){return display;}public int getWindowingMode(){return mode;}public Object asTask(){return this;}}
 public static class Display{Object mMagnificationSpec;Transaction transaction=new Transaction();int replays;public Transaction getPendingTransaction(){return transaction;}public void reapplyMagnificationSpec(Object t){replays++;}}
 public static class Transaction{
  int applies,writes;ArrayList<Runnable>ops=new ArrayList<>();
  public Transaction setMatrix(Object o,float a,float b,float c,float d){var s=(Surface)o;writes++;ops.add(()->s.scale=a);return this;}
  public Transaction setPosition(Object o,float x,float y){var s=(Surface)o;ops.add(()->{s.x=x;s.y=y;});return this;}
  public void apply(){applies++;for(var op:ops)op.run();ops.clear();}
 }
 static class XposedHelpers{
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
  static Object callMethod(Object o,String n,Object...args){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
 }
 static void log(String s){}
 // Layer ordering is exercised against the actual bridge in rotation-jvm.
 // Here verify the late-finish callback forwards the current display/transaction.
 static void placeHostBelowNativeFreeforms(Object d,Object t){
  if(d!=sDisplayContent||t!=((Display)d).transaction)throw new AssertionError("native layer reconciliation target");
  nativeLayerReconciliations++;
 }
 __METHODS__
 __HOOK__
 static void check(boolean b,String name){checks++;if(!b)throw new AssertionError(name);}
 static Display reset(){sMagnifiedSurfaces.clear();sMagnificationFinishReplays.clear();nativeLayerReconciliations=0;var d=new Display();sDisplayContent=d;sZoomTarget=new ZoomTarget(d);sZoomed=true;d.mMagnificationSpec=new Object();return d;}
 static Container owned(Display d){var c=new Container();c.display=d;c.mLastMagnificationSpec=d.mMagnificationSpec;synchronized(sZoomTarget.globalLock){rememberMagnifiedSurface(c,c.mLastMagnificationSpec);}return c;}
 static void exit(Display d,Container c){sZoomed=false;d.mMagnificationSpec=null;c.mLastMagnificationSpec=null;}
 static int restore(){synchronized(sZoomTarget.globalLock){return restoreExitedMagnificationLocked(sZoomTarget);}}
 static void stale(Surface s){s.scale=.8996371f;s.x=120.37956f;s.y=261.74637f;}
 public static void main(String[]args){
  boolean legacy=args.length>0;
  Display d=reset();Container c=owned(d);check(sMagnifiedSurfaces.size()==1,"only written owner remembered");
  Surface original=c.mSurfaceControl;exit(d,c);stale(original);
  int count=legacy?0:restore();check(count==1&&original.scale==1&&original.x==0&&original.y==0,"late finish cannot leave wallpaper/status at animation midpoint");
  stale(original);check(restore()==1&&original.scale==1,"another old finish also restored");
  c.mLastSurfacePosition.x=12;c.mLastSurfacePosition.y=34;restore();check(original.x==12&&original.y==34,"current WM position used");
  Container foreign=new Container();foreign.display=d;stale(foreign.mSurfaceControl);restore();check(foreign.mSurfaceControl.scale<1,"untracked native surface untouched");
  sZoomed=true;d.mMagnificationSpec=new Object();stale(original);int writes=d.transaction.writes;check(restore()==0&&d.transaction.writes==writes&&original.scale<1,"new session never reset by old exit");
  sZoomed=false;check(restore()==0,"other active WM magnification preserved");
  d.mMagnificationSpec=null;c.mLastMagnificationSpec=new Object();check(restore()==0,"container now magnified by another owner skipped");
  c.mLastMagnificationSpec=null;c.mSurfaceControl=new Surface();stale(c.mSurfaceControl);check(restore()==0&&sMagnifiedSurfaces.isEmpty()&&c.mSurfaceControl.scale<1,"recreated surface not overwritten");
  d=reset();c=owned(d);exit(d,c);c.mode=5;stale(c.mSurfaceControl);check(restore()==0&&sMagnifiedSurfaces.isEmpty()&&c.mSurfaceControl.scale<1,"native freeform animation retains leash ownership");
  d=reset();c=owned(d);exit(d,c);c.mSurfaceControl.valid=false;check(restore()==0&&sMagnifiedSurfaces.isEmpty(),"invalid surface pruned");
  d=reset();c=owned(d);exit(d,c);c.display=new Display();check(restore()==0,"moved display untouched");
  d=reset();c=owned(d);exit(d,c);check(restoreExitedMagnificationLocked(sZoomTarget)==0,"requires WM lock");
  ZoomTarget old=sZoomTarget;sZoomTarget=new ZoomTarget(d);synchronized(old.globalLock){check(restoreExitedMagnificationLocked(old)==0,"stale target rejected");}
  d=reset();c=owned(d);exit(d,c);sDisplayContent=new Display();check(restore()==0,"replaced display rejected");
  d=reset();c=new Container();c.display=d;c.mLastMagnificationSpec=new Object();synchronized(sZoomTarget.globalLock){rememberMagnifiedSurface(c,c.mLastMagnificationSpec);}check(sMagnifiedSurfaces.isEmpty(),"non-current spec not claimed");
  c.mLastMagnificationSpec=d.mMagnificationSpec;rememberMagnifiedSurface(c,c.mLastMagnificationSpec);check(sMagnifiedSurfaces.isEmpty(),"record requires lock");
  sZoomed=false;synchronized(sZoomTarget.globalLock){rememberMagnifiedSurface(c,c.mLastMagnificationSpec);}check(sMagnifiedSurfaces.isEmpty(),"inactive module claims nothing");
  d=reset();c=owned(d);Container other=owned(d);exit(d,c);exit(d,other);c.mLastSurfacePosition.x=9;stale(c.mSurfaceControl);stale(other.mSurfaceControl);int applies=d.transaction.applies;check(restore()==2&&d.transaction.applies==applies+1&&c.mSurfaceControl.scale==1&&other.mSurfaceControl.scale==1,"one native transaction for both recorded owners");
  d=reset();c=owned(d);exit(d,c);stale(c.mSurfaceControl);var finish=new MethodHookParam(new Object());sMagnificationFinishReplays.put(finish.thisObject,d);synchronized(sZoomTarget.globalLock){onFinish(finish);}check(sZoomTarget.handler.queue.size()==1&&c.mSurfaceControl.scale<1,"finish reconciliation posted to WM handler");sZoomTarget.handler.flush();check(c.mSurfaceControl.scale==1&&sMagnificationFinishReplays.isEmpty(),"actual finish hook restores after exit");
  d=reset();c=owned(d);exit(d,c);stale(c.mSurfaceControl);finish=new MethodHookParam(new Object());sMagnificationFinishReplays.put(finish.thisObject,d);synchronized(sZoomTarget.globalLock){onFinish(finish);}sZoomed=true;d.mMagnificationSpec=new Object();sZoomTarget.handler.flush();check(d.replays==1&&c.mSurfaceControl.scale<1,"old finish replays current active spec after reentry, no identity");check(nativeLayerReconciliations==1,"active finish also reconciles native freeform layer dependency");
  d=reset();c=owned(d);exit(d,c);finish=new MethodHookParam(new Object());synchronized(sZoomTarget.globalLock){onFinish(finish);}check(sZoomTarget.handler.queue.isEmpty(),"unrelated transition untouched");
  sMagnificationFinishReplays.put(finish.thisObject,d);onFinish(finish);check(sZoomTarget.handler.queue.isEmpty(),"finish requires WM lock");
  d=reset();c=owned(d);exit(d,c);stale(c.mSurfaceControl);finish=new MethodHookParam(new Object());sMagnificationFinishReplays.put(finish.thisObject,d);synchronized(sZoomTarget.globalLock){onFinish(finish);}ZoomTarget previous=sZoomTarget;sZoomTarget=new ZoomTarget(d);previous.handler.flush();check(c.mSurfaceControl.scale<1&&d.transaction.applies==0,"queued callback from replaced target inert");
  d=reset();finish=new MethodHookParam(new Object());finish.error=true;sMagnificationFinishReplays.put(finish.thisObject,d);synchronized(sZoomTarget.globalLock){onFinish(finish);}check(sZoomTarget.handler.queue.isEmpty(),"failed native finish untouched");
  System.out.println("actual exit surface checks="+checks+" passed");
 }
}'''.replace('__METHODS__',methods).replace('__HOOK__',hook)
p=out/'ExitSurfaceRegression.java';p.write_text(body,encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(p)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.ExitSurfaceRegression',*sys.argv[2:]],check=True)
