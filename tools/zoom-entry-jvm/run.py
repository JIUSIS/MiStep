"""Run actual zoom publication around synchronous orientation callbacks and failure."""
import os,ast
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = Path(sys.argv[2]) if len(sys.argv)>2 else root/'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
source = (src/'HostEntry.java').read_text(encoding='utf8')
start = source.index('    private void applyDisplayZoomLocked(ZoomTarget target, float scale,\n            float offsetY, float offsetX, ZoomCommit commit)')
native = source.index('    private void applyDisplayZoomLocked(ZoomTarget target, float scale,\n            float offsetY, float offsetX, ZoomCommit commit, boolean nativeVisibility)')
pos = source.index('{', native)+1; depth = 1
while depth:
    depth += (source[pos]=='{')-(source[pos]=='}'); pos += 1
actual = source[start:pos]
body = r'''package com.jiusis.mistep;
public class ZoomEntryRegression {
 static boolean sZoomed,sFullscreenRotationActive,sZoomFrameApply;static int sNativeLandscapeState;static long topWorkspaceGeneration;static float sZoomScale=1,sZoomOffsetX,sZoomOffset;
 static ZoomTarget sZoomTarget;static Object sDisplayContent;static ZoomCommit sLiveZoomCommit;static int callbacks,nativeSuspensions,refreshes;static boolean failRotation;
 static class Looper {static final Object MAIN=new Object();static Object current=MAIN;static Object myLooper(){return current;}}
 static class Handler {Object getLooper(){return Looper.MAIN;}void post(Runnable r){r.run();}}
 static class ZoomTarget {Handler handler=new Handler();Object globalLock=new Object();Display display=new Display();}
 static class Spec {float scale,offsetX,offsetY;}
 static class Rect {int width(){return 1080;}int height(){return 2460;}}
 static class Display {Spec mMagnificationSpec;boolean fail;public void applyMagnificationSpec(Spec s){if(fail)throw new IllegalStateException("apply failed");mMagnificationSpec=s;}public Object getPendingTransaction(){return new Object();}public Rect getBounds(){return new Rect();}public int getRotation(){return 0;}public Object getWindowingLayer(){return null;}}
 static class Zoom {boolean sameTuple(float a,float b,float c){return true;}}
 static class ZoomCommit {long transactionId;float actualScale,actualX,actualY;boolean applied;Zoom zoom=new Zoom();void finish(boolean success,String reason){}}
 static class MiStepWallpaperScale {static void refresh(Object dc){refreshes++;}}
 static class MiStepRecentsInput {static void geometryChanged(Object tx,float s,float y,float x){}}
 static class NativeRawInputBridge {static void update(float s,float y,float x,int w,int h,int r){}}
 static class XposedHelpers {
  static Class<?> findClass(String n,ClassLoader cl){return Spec.class;}
  static void setFloatField(Object o,String n,float v){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.setFloat(o,v);}catch(Exception e){throw new RuntimeException(e);}}
  static float getFloatField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.getFloat(o);}catch(Exception e){throw new RuntimeException(e);}}
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static Object callMethod(Object o,String n,Object...args){for(var m:o.getClass().getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)try{m.setAccessible(true);return m.invoke(o,args);}catch(java.lang.reflect.InvocationTargetException e){throw new RuntimeException(e.getCause());}catch(Exception e){throw new RuntimeException(e);}throw new IllegalStateException(n);}
 }
 void beginFixedMainRotationLocked(Object dc){sFullscreenRotationActive=true;callbacks++;if(!sZoomed)nativeSuspensions++;if(failRotation)throw new IllegalStateException("orientation failure");}
 void endFixedMainRotationLocked(Object dc){sFullscreenRotationActive=false;}
 void placeHostBelowNativeFreeforms(Object d,Object tx){}void restoreExitedMagnificationLocked(ZoomTarget t){}void applyGestureSurfacesLocked(ZoomTarget t,float s,float y,float x){}void log(String s){}
 ACTUAL
 static MiStepKeyguardZoom sKeyguardZoom=new MiStepKeyguardZoom();
 static int checks;static void check(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
 static ZoomTarget reset(){sKeyguardZoom=new MiStepKeyguardZoom();Looper.current=Looper.MAIN;sZoomed=false;sFullscreenRotationActive=false;sNativeLandscapeState=0;topWorkspaceGeneration=0;sZoomScale=1;sZoomOffsetX=sZoomOffset=0;callbacks=nativeSuspensions=refreshes=0;failRotation=false;sZoomTarget=new ZoomTarget();sDisplayContent=sZoomTarget.display;return sZoomTarget;}
 static void apply(ZoomTarget t,float s)throws Throwable{synchronized(t.globalLock){new ZoomEntryRegression().applyDisplayZoomLocked(t,s,s==1?0:622,0,null);}}
 public static void main(String[] args)throws Throwable{
  var t=reset();apply(t,.747f);check(callbacks==1&&nativeSuspensions==0,"entry orientation callback sees incoming workspace instead of native fullscreen");check(sZoomed&&sFullscreenRotationActive&&topWorkspaceGeneration==1,"entry published once");check(t.display.mMagnificationSpec.scale==.747f&&sZoomScale==.747f,"applied state retained");
  apply(t,.747f);check(topWorkspaceGeneration==1&&nativeSuspensions==0,"animation frame keeps same workspace generation");apply(t,1);check(!sZoomed&&!sFullscreenRotationActive&&sZoomScale==1&&refreshes==2,"exit restores normal display state");
  t=reset();t.display.fail=true;try{apply(t,.747f);throw new AssertionError("failure swallowed");}catch(RuntimeException expected){}check(!sZoomed&&!sFullscreenRotationActive&&sNativeLandscapeState==0&&sZoomScale==1,"failed magnification rolls back publication and rotation");
  t=reset();failRotation=true;try{apply(t,.747f);throw new AssertionError("orientation failure swallowed");}catch(RuntimeException expected){}check(!sZoomed&&!sFullscreenRotationActive&&sNativeLandscapeState==0,"synchronous orientation failure rolls back too");
  t=reset();sFullscreenRotationActive=true;t.display.fail=true;try{apply(t,.747f);throw new AssertionError("failure swallowed");}catch(RuntimeException expected){}check(!sZoomed&&sFullscreenRotationActive,"existing rotation ownership survives apply failure");
  t=reset();var other=new ZoomTarget();apply(other,.747f);check(!sZoomed&&callbacks==0,"stale display target has no effects");
  t=reset();try{new ZoomEntryRegression().applyDisplayZoomLocked(t,.747f,622,0,null);throw new AssertionError("missing lock accepted");}catch(IllegalStateException expected){}check(!sZoomed&&callbacks==0,"WM lock remains required");
  t=reset();Looper.current=null;synchronized(t.globalLock){try{new ZoomEntryRegression().applyDisplayZoomLocked(t,.747f,622,0,null);throw new AssertionError("ordinary off-handler apply accepted");}catch(IllegalStateException expected){}}check(!sZoomed,"ordinary request still requires WM handler");
  synchronized(t.globalLock){new ZoomEntryRegression().applyDisplayZoomLocked(t,.747f,622,0,null,true);}check(sZoomed&&sZoomScale==.747f,"authenticated native visibility path must execute in native-held WM transaction");
  t=reset();try{new ZoomEntryRegression().applyDisplayZoomLocked(t,.747f,622,0,null,true);throw new AssertionError("native path missing lock accepted");}catch(IllegalStateException expected){}check(!sZoomed,"native visibility never bypasses WM lock");
  t=reset();apply(t,.747f);final ZoomTarget locked=t;
  sKeyguardZoom.onShown(0,true,false,()->apply(locked,1));check(!sZoomed&&sZoomScale==1,"native lock entry restores physical geometry");
  apply(t,.747f);check(!sZoomed&&sZoomScale==1&&sZoomOffset==0,"late ordinary frame cannot resize keyguard");
  synchronized(t.globalLock){new ZoomEntryRegression().applyDisplayZoomLocked(t,.747f,622,0,null,true);}check(!sZoomed&&sZoomScale==1,"late native visibility cannot resize locked display");
  sKeyguardZoom.onShown(0,false,false,()->{throw new AssertionError("unlock must not auto restore");});check(!sZoomed,"unlock alone never opens workspace");
  apply(t,.747f);check(sZoomed,"authenticated handoff can restore geometry once native keyguard clears");
  sKeyguardZoom.onShown(0,true,false,()->apply(locked,1));
  check(!sKeyguardZoom.onAuthenticatedReveal(false),"unverified native handoff denied");apply(t,.747f);check(!sZoomed,"unverified handoff leaves barrier enforced");
  check(sKeyguardZoom.onAuthenticatedReveal(true),"authenticated visible Host releases stale native showing latch");
  synchronized(t.globalLock){new ZoomEntryRegression().applyDisplayZoomLocked(t,.747f,622,0,null,true);}check(sZoomed&&sZoomScale==.747f,"authenticated handoff really applies retained geometry rather than logging clamped success");
  System.out.println("actual fullscreen zoom entry checks="+checks+" passed");
 }
}'''.replace('ACTUAL', actual)
target=out/'ZoomEntryRegression.java';target.write_text(body,encoding='utf8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
tree=ast.parse((root/'tools/keyguard-zoom-jvm/run.py').read_text(encoding='utf-8-sig'))
fixtures=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='files' for t in n.targets))
extra=[]
for name,code in fixtures.items():
 if not name.startswith('de/'):continue
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code,encoding='utf-8');extra.append(str(p))
extra.append(str(src/'MiStepKeyguardZoom.java'))
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(target),str(src/'NativeLandscapePolicy.java'),*extra],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.ZoomEntryRegression'],check=True)
