import os
"""Run the real native-consumer and native status-sampler hooks with transaction probes."""
from pathlib import Path
import subprocess, sys

root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
files = {
'android/graphics/Point.java': 'package android.graphics;public class Point{public int x,y;public Point(int a,int b){x=a;y=b;}}',
'android/graphics/Rect.java': '''package android.graphics;public class Rect{
 public int left,top,right,bottom;public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
 public boolean isEmpty(){return left>=right||top>=bottom;}public boolean equals(Object o){return o instanceof Rect&&left==((Rect)o).left&&top==((Rect)o).top&&right==((Rect)o).right&&bottom==((Rect)o).bottom;}
}''',
'android/os/Looper.java': 'package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java': 'package android.os;public class Handler{public Handler(Looper l){}public void post(Runnable r){r.run();}}',
'android/view/View.java': 'package android.view;public class View{}',
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;public abstract class XC_MethodHook{
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
 public void before(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}public void after(MethodHookParam p)throws Throwable{afterHookedMethod(p);}
 public static class MethodHookParam{public Object thisObject,result;public Object[]args;public Object getResult(){return result;}public void setResult(Object o){result=o;}public boolean hasThrowable(){return false;}}
}''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;public class XposedBridge{
 public static void log(String s){}public static void hookAllMethods(Class<?>c,String n,XC_MethodHook h){XposedHelpers.hooks.put(c.getName()+"."+n,h);}
 public static void hookAllConstructors(Class<?>c,XC_MethodHook h){hookAllMethods(c,"<init>",h);}
}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;import java.util.*;public class XposedHelpers{
 public static Map<String,XC_MethodHook>hooks=new HashMap<>();public static Class<?>findClass(String n,ClassLoader l){try{return Class.forName(n);}catch(Exception e){throw new RuntimeException(e);}}
 public static void findAndHookMethod(Class<?>c,String n,Object...args){hooks.put(c.getName()+"."+n,(XC_MethodHook)args[args.length-1]);}
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static int getIntField(Object o,String n){return(Integer)getObjectField(o,n);}
 public static Object callMethod(Object o,String n,Object...args){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
}''',
'com/android/server/wm/InputConsumerImpl.java': '''package com.android.server.wm;import android.graphics.*;
public class InputConsumerImpl{public String mName="recents_animation_input_consumer";public Point mOldPosition=new Point(0,0);public Handle mWindowHandle=new Handle();public Surface mInputSurface=new Surface();
 public static class Handle{public int displayId;}public static class Surface{public boolean valid=true;public boolean isValid(){return valid;}}
}''',
'com/android/wm/shell/shared/handles/RegionSamplingHelper.java': '''package com.android.wm.shell.shared.handles;
public class RegionSamplingHelper{public Object mCallback;public int updates;public boolean enabled;
 public void updateSamplingRect(){updates++;} }
''',
'com/miui/systemui/statusbar/core/StatusBarRegionSamplingInteractor$regionSamplingHelper$1.java': '''package com.miui.systemui.statusbar.core;import android.graphics.Rect;
public class StatusBarRegionSamplingInteractor$regionSamplingHelper$1{public Owner this$0=new Owner();public static class Owner{public Rect samplingBounds=new Rect(0,0,1080,100);}}
''',
'com/jiusis/mistep/MiStepHost.java': '''package com.jiusis.mistep;public class MiStepHost{public static float[]geometry;static float[]activeStatusBarTransform(){return geometry;}}''',
'com/jiusis/mistep/HandoffRegression.java': r'''package com.jiusis.mistep;
import android.graphics.*;import de.robv.android.xposed.*;import com.android.server.wm.*;
import com.android.wm.shell.shared.handles.RegionSamplingHelper;
import com.miui.systemui.statusbar.core.StatusBarRegionSamplingInteractor$regionSamplingHelper$1;
public class HandoffRegression{
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 public static class Tx{public int writes;public Object surface;public float scale,x,y;
  public void setMatrix(Object s,float a,float b,float c,float d){surface=s;scale=a;writes++;if(a!=d||b!=0||c!=0)throw new AssertionError("skew");}
  public void setPosition(Object s,float a,float b){if(surface!=s)throw new AssertionError("different surface");x=a;y=b;writes++;}}
 static XC_MethodHook.MethodHookParam invoke(String key,Object target,Object result,Object...args)throws Throwable{
  var p=new XC_MethodHook.MethodHookParam();p.thisObject=target;p.result=result;p.args=args;var h=XposedHelpers.hooks.get(key);h.before(p);h.after(p);return p;}
 public static void main(String[]args)throws Throwable{
  float[][] geometry={null};MiStepRecentsInput.install(HandoffRegression.class.getClassLoader(),()->geometry[0]);
  String consumer="com.android.server.wm.InputConsumerImpl.";var c=new InputConsumerImpl();Tx tx=new Tx();
  invoke(consumer+"layout",c,null,tx,new Rect(0,0,1080,2460));check(tx.writes==0,"outside workspace native geometry untouched");
  geometry[0]=new float[]{.75f,600,270};invoke(consumer+"show",c,null,tx,new Object());
  check(tx.scale==.75f&&tx.x==270&&tx.y==600,"native show publishes correct physical scale and both offsets in its own transaction");
  check(c.mOldPosition.x==0&&c.mOldPosition.y==0,"native layout cache stays logical");
  // Invert the exact published surface transform, as InputDispatcher does.
  check((495-tx.x)/tx.scale==300&&(1800-tx.y)/tx.scale==1600,"early icon touch resolves to the visible logical point");
  c.mOldPosition=new Point(12,30);tx=new Tx();invoke(consumer+"layout",c,null,tx,new Rect(12,30,1080,2460));
  check(tx.x==279&&tx.y==622.5f,"native nonzero layout origin composed once");
  int writes=tx.writes;invoke(consumer+"show",c,null,tx,new Object());check(tx.writes==writes+2&&tx.x==279,"repeated show does not accumulate scale/position");
  var other=new InputConsumerImpl();other.mName="pip_input_consumer";Tx foreign=new Tx();invoke(consumer+"show",other,null,foreign,new Object());check(foreign.writes==0,"PiP and other native consumers unchanged");
  other.mName=c.mName;other.mWindowHandle.displayId=2;invoke(consumer+"show",other,null,foreign,new Object());check(foreign.writes==0,"external display unchanged");
  tx=new Tx();MiStepRecentsInput.geometryChanged(tx,.8f,500,0);check(tx.scale==.8f&&tx.x==9.6f&&tx.y==524,"already live consumer follows display zoom in the same supplied transaction");
  geometry[0]=null;tx=new Tx();MiStepRecentsInput.geometryChanged(tx,1,0,0);check(tx.scale==1&&tx.x==12&&tx.y==30,"exit restores exact native origin and identity");
  tx=new Tx();invoke(consumer+"show",c,null,tx,new Object());check(tx.writes==0,"restored consumer no longer overridden outside workspace");
  geometry[0]=new float[]{.75f,600,0};invoke(consumer+"show",c,null,tx,new Object());c.mInputSurface.valid=false;tx=new Tx();MiStepRecentsInput.geometryChanged(tx,1,0,0);check(tx.writes==0,"destroyed consumer surface never touched");

  MiStepStatusBarSampling.install(HandoffRegression.class.getClassLoader());
  String cb="com.miui.systemui.statusbar.core.StatusBarRegionSamplingInteractor$regionSamplingHelper$1";
  var callback=new StatusBarRegionSamplingInteractor$regionSamplingHelper$1();Rect logical=callback.this$0.samplingBounds;
  var helper=new RegionSamplingHelper();helper.mCallback=callback;
  invoke("com.android.wm.shell.shared.handles.RegionSamplingHelper.<init>",helper,null);
  MiStepHost.geometry=new float[]{.75f,600,270};
  Rect sample=(Rect)invoke(cb+".getSampledRegion",callback,logical,new android.view.View()).result;
  check(sample.equals(new Rect(270,600,1080,675)),"native night sampler reads the real magnified status bar");
  check(logical.equals(new Rect(0,0,1080,100))&&logical!=sample,"native icon-area bounds remain logical and unmodified");
  var started=invoke("com.android.wm.shell.shared.handles.RegionSamplingHelper.start",helper,null,logical);
  check(started.args[0].equals(sample),"native delayed resample also starts with physical bounds");
  started=invoke("com.android.wm.shell.shared.handles.RegionSamplingHelper.start",helper,null,sample);
  check(started.args[0]==sample,"an already physical sampler start is never transformed twice");
  var unrelated=new RegionSamplingHelper();unrelated.mCallback=new Object();started=invoke("com.android.wm.shell.shared.handles.RegionSamplingHelper.start",unrelated,null,logical);
  check(started.args[0]==logical,"navigation and unrelated native samplers untouched");
  MiStepStatusBarSampling.geometryChanged();check(helper.updates==1&&!helper.enabled,"refresh retargets native helper without enabling a paused sampler");
  MiStepHost.geometry=null;sample=(Rect)invoke(cb+".getSampledRegion",callback,logical,new android.view.View()).result;
  check(sample==logical,"exit/keyguard returns native coordinates");
  check(MiStepStatusBarSampling.physical(logical,new float[]{Float.NaN,0,0})==logical,"invalid geometry rejected");
  check(MiStepStatusBarSampling.physical(new Rect(1,1,1,1),new float[]{.75f,600,0}).isEmpty(),"empty sampling region remains empty");
  System.out.println("actual recents input/native status sampling hook checks="+checks+" passed");
 }
}'''
}
for name, text in files.items():
    p = out/name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text, encoding='utf-8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out)]+
               [str(out/name) for name in files]+[str(src/name) for name in
                ['MiStepRecentsInput.java','MiStepStatusBarSampling.java']],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.HandoffRegression'],check=True)
