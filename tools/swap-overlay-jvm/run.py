"""Replay actual native overlay selection and same-frame matrix/finish/release."""
from pathlib import Path
import os,subprocess,sys
root=Path(__file__).resolve().parents[2];out=Path(sys.argv[1]);src=root/'module/src/main/java/com/jiusis/mistep'
files={
'android/view/WindowManager.java':r'''package android.view;public interface WindowManager{class LayoutParams{public String packageName;public int type;public String title;public CharSequence getTitle(){return title;}}}''',
'de/robv/android/xposed/XposedHelpers.java':r'''package de.robv.android.xposed;import java.lang.reflect.*;public class XposedHelpers{
 public static Object getObjectField(Object o,String n){for(Class<?>c=o.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException e){}catch(Exception e){throw new IllegalStateException(e);}throw new IllegalStateException(n);}
 public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
 public static Object callMethod(Object o,String n,Object...a){for(Class<?>c=o.getClass();c!=null;c=c.getSuperclass())for(var m:c.getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)try{m.setAccessible(true);return m.invoke(o,a);}catch(Exception e){throw new IllegalStateException(e);}throw new IllegalStateException(n);}}
''',
'com/jiusis/mistep/LiveTaskSwapDriver.java':'package com.jiusis.mistep;class LiveTaskSwapDriver{interface Overlays{void paint(Object tx,double f);void finish(Object tx,boolean original);void close();}}',
'com/jiusis/mistep/OverlayRegression.java':r'''package com.jiusis.mistep;
import java.util.*;import android.view.WindowManager;
public class OverlayRegression{
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}static void near(float a,float b,String why){check(Math.abs(a-b)<.001,why+" "+a+"/"+b);}
 static class Surface{int id,releases;boolean valid=true;Surface(int i){id=i;}boolean isValid(){return valid;}int getLayerId(){return id;}void release(){releases++;}}
 static class Pos{int x,y;Pos(int x,int y){this.x=x;this.y=y;}}
 static class Spec{float scale=.7472f,offsetX=0,offsetY=622;}
 static class Owner{Object mLastMagnificationSpec;Surface mSurfaceControl=new Surface(1);Pos mLastSurfacePosition=new Pos(0,0);}
 static class Window extends Owner{WindowManager.LayoutParams mAttrs=new WindowManager.LayoutParams();int mOwnerUid=1000;Object display,mToken=new Owner();boolean visible=true;
 Object getDisplayContent(){return display;}boolean isVisible(){return visible;}}
 static class Service{Map<Object,Object>mWindowMap=new LinkedHashMap<>();}
 static class Display{Spec mMagnificationSpec=new Spec();Service mWmService=new Service();}
 static Window window(Display d,int type,String title,int id){Window w=new Window();w.display=d;w.mAttrs.packageName="com.miui.securitycenter";w.mAttrs.type=type;w.mAttrs.title=title;((Owner)w.mToken).mLastMagnificationSpec=d.mMagnificationSpec;((Owner)w.mToken).mSurfaceControl=new Surface(id);d.mWmService.mWindowMap.put(w,w);return w;}
 static class Tx{Map<Surface,float[]>values=new HashMap<>();int matrices,positions;void setMatrix(Surface s,float a,float b,float c,float d){near(b,0,"skew");near(c,0,"skew");near(a,d,"nonuniform");matrices++;values.computeIfAbsent(s,k->new float[3])[0]=a;}void setPosition(Surface s,float x,float y){positions++;float[]v=values.computeIfAbsent(s,k->new float[3]);v[1]=x;v[2]=y;}}
 public static void main(String[]args){
  var old=new LiveSwapZoom.Zoom(1,.7472f,0,622);var target=new LiveSwapZoom.Zoom(2,.7472f,273,622);Display d=new Display();
  Window panel=window(d,2003,"com.miui.securitycenter/.FloatingWindow",11);((Owner)panel.mToken).mLastSurfacePosition=new Pos(7,13);
  Window trigger=window(d,2026,"DockAssistantView",12);trigger.mLastMagnificationSpec=d.mMagnificationSpec;((Owner)trigger.mToken).mLastMagnificationSpec=null;trigger.mLastSurfacePosition=new Pos(0,770);
  var rows=MiStepSwapOverlaySelection.capture(d,old);check(rows.length==2&&rows[0].surface==((Owner)panel.mToken).mSurfaceControl,"native token owner not selected");check(rows[1].surface==trigger.mSurfaceControl,"native window owner not selected");
  Window foreign=window(d,2003,"com.miui.securitycenter/.FloatingWindow",99);foreign.mAttrs.packageName="other";check(MiStepSwapOverlaySelection.capture(d,old).length==2,"other package altered");foreign.mAttrs.packageName="com.miui.securitycenter";foreign.mOwnerUid=10001;check(MiStepSwapOverlaySelection.capture(d,old).length==2,"other UID altered");foreign.mOwnerUid=1000;foreign.display=new Display();check(MiStepSwapOverlaySelection.capture(d,old).length==2,"other display altered");foreign.display=d;foreign.visible=false;check(MiStepSwapOverlaySelection.capture(d,old).length==2,"hidden stale window altered");foreign.visible=true;foreign.mAttrs.title="other alert";check(MiStepSwapOverlaySelection.capture(d,old).length==2,"unrelated alert altered");
  ((Owner)panel.mToken).mLastMagnificationSpec=null;boolean rejected=false;try{MiStepSwapOverlaySelection.capture(d,old);}catch(IllegalStateException e){rejected=true;}check(rejected,"shared unknown ancestor borrowed");((Owner)panel.mToken).mLastMagnificationSpec=d.mMagnificationSpec;
  d.mMagnificationSpec.offsetX=273;rejected=false;try{MiStepSwapOverlaySelection.capture(d,old);}catch(IllegalStateException e){rejected=true;}check(rejected,"stale native zoom accepted");d.mMagnificationSpec.offsetX=0;
  Surface copy=new Surface(11);var lease=new MiStepSwapOverlayFrames(new MiStepSwapOverlayFrames.Row[]{new MiStepSwapOverlayFrames.Row(copy,7,13)},old,target);
  Tx start=new Tx();lease.paint(start,0);near(start.values.get(copy)[1],7,"first frame jumped");near(start.values.get(copy)[2],635,"native position added incorrectly");
  Tx mid=new Tx();lease.paint(mid,.5);near(mid.values.get(copy)[1],143.5f,"overlay skipped task midpoint");near(mid.values.get(copy)[0],.7472f,"native scale applied twice");check(mid.matrices==1&&mid.positions==1,"frame not appended to supplied transaction");
  Tx finish=new Tx();lease.finish(finish,false);near(finish.values.get(copy)[1],280,"target finish pose");Tx cancel=new Tx();lease.finish(cancel,true);near(cancel.values.get(copy)[1],7,"cancel failed to restore original pose");
  lease.close();lease.close();check(copy.releases==1&&((Owner)panel.mToken).mSurfaceControl.releases==0,"released server owner or leaked client reference");
  var reverse=new MiStepSwapOverlayFrames(new MiStepSwapOverlayFrames.Row[]{new MiStepSwapOverlayFrames.Row(new Surface(33),0,0)},target,old);Tx rev=new Tx();reverse.paint(rev,.5);near(rev.values.values().iterator().next()[1],136.5f,"reverse midpoint jumped");reverse.close();
  System.out.println("actual toolbox native owner/same-frame/cancel checks="+checks+" passed");
 }
}'''}
for n,s in files.items():
 p=out/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*[str(out/n) for n in files],*[str(src/(n+'.java')) for n in ['MiStepSwapOverlayFrames','MiStepSwapOverlaySelection','LiveSwapZoom']]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.OverlayRegression'],check=True)
