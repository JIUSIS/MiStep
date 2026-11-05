"""Replay real top bounds/return/focus methods; no claim of Android rendering."""
from pathlib import Path
import os,subprocess,sys
root=Path(__file__).resolve().parents[2]
src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
def member(signature):
 start=source.index(signature);end=source.index('{',start)+1;depth=1
 while depth:
  depth+=(source[end]=='{')-(source[end]=='}');end+=1
 return source[start:end]
body='''package com.jiusis.mistep;
import java.util.*;import android.graphics.Rect;
public class TopConfigRegression {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static final int SLOT_TOP=4,ACTIVE=1;int scene=ACTIVE;long sceneEpoch=1;
 boolean suspendRequested,keyguardLocked,screenOn=true,defer;
 List<Runnable>queue=new ArrayList<>();
 static class Slot {int index=4,taskId=42;Object taskToken="upper",controller=new Object();String pkg="test.app";boolean promoting;}
 static class Promotion {Slot slot=new Slot();Object token="upper";int taskId=42;boolean background=true,liveMainReveal;List<Object>otherSideTokens=List.of("side");}
 static class Info {boolean isFocused,isVisible=true;int mode,id;Object token;Info(int i,int m,Object t,boolean focus){id=i;mode=m;token=t;isFocused=focus;}}
 static class LiveTask {int id;Object token;LiveTask(int i,Object t){id=i;token=t;}LiveTask(int i,Object t,String pkg){this(i,t);}}
 List<Info>infos=new ArrayList<>();LiveTask current,home=new LiveTask(1,"home");
 static class Service {int calls,last=-1;void setFocusedTask(int id){calls++;last=id;}}
 Service service=new Service();Object applied;
 static TopConfigRegression active;
 static class ShellCompat {
  static Object transaction(Object organizer,Object wct,String name,Object token,Object value){return XposedHelpers.callMethod(wct,name,token,value);}
 }
 static class XposedHelpers {
  static Object callStaticMethod(Class<?>c,String name,Object...args){return active.service;}
  static Object getObjectField(Object o,String name){if(name.equals("mTaskOrganizer"))return active.service;if(name.equals("mCaptionInsetsOwner"))return null;return ((Info)o).token;}
  static boolean getBooleanField(Object o,String n){Info i=(Info)o;return n.equals("isFocused")?i.isFocused:i.isVisible;}
  static Object callMethod(Object o,String n,Object...a){
   if(o instanceof android.window.WindowContainerTransaction){((android.window.WindowContainerTransaction)o).calls.add(n+":"+a[0]+":"+(a.length>1?a[1]:"")+(a.length>2?":"+a[2]:""));return o;}
   if(o instanceof Service){((Service)o).setFocusedTask((Integer)a[0]);return null;}throw new AssertionError(n);
  }
 }
 boolean executeOnShell(Runnable r){if(defer)queue.add(r);else r.run();return true;}
 boolean canRender(Slot s){return true;}boolean holdsLiveFlipZoom(){return false;}boolean mayWriteSideSurface(Slot s){return true;}
 boolean controllerOwns(Slot s,int id,Object token){return true;}Object getSurfaceField(Object c,String n){return new Object();}
 TopTaskGeometry topGeometry(){return new TopTaskGeometry(1080,2460,622,0,2.75f);}
 Rect logicalBounds(Slot s){TopTaskGeometry g=topGeometry();return s.index==4?new Rect(0,0,g.logicalWidth,g.logicalHeight):new Rect(0,0,1080,2460);}
 boolean applyWct(String why,Object wct){applied=wct;return true;}static void log(String s){}
 Iterable<Info>rootInfos(){return infos;}int windowModeOf(Object o){return ((Info)o).mode;}
 boolean isSideToken(Object token){return "upper".equals(token)||"side".equals(token);}
 LiveTask findMainTask(){return current;}LiveTask existingHomeTask(){return home;}
 Object rootInfo(LiveTask task){return infos.stream().filter(i->i.id==task.id&&i.token.equals(task.token)).findFirst().orElse(null);}
  boolean ordinaryRoot(Object i,int m){return i instanceof Info&&((Info)i).mode==m;}
'''+member('    private void applyTopConfiguration(')+member('    private void applyFullscreenBounds(')+member('    private Object fullscreenExitTransaction(') +member('    private void restoreTopMainOpacity(')+member('    private void preferMainAfterTopChange(')+'''
 static TopConfigRegression host(){
  var h=new TopConfigRegression();active=h;h.infos.add(new Info(1,1,"home",false));h.infos.add(new Info(42,6,"upper",true));return h;
 }
 public static void main(String[]args)throws Exception{
  var h=host();var slot=new Slot();h.applyFullscreenBounds(slot);
  var w=(android.window.WindowContainerTransaction)h.applied;
  check(w.calls.contains("setDensityDpi:upper:0"),"top inherits native density to keep retained and new View pixels consistent");
  check(w.calls.contains("setAppBounds:upper:0,0,2964,1707"),"app metrics use the full logical viewport, beyond physical width");
  check(w.calls.contains("setScreenSizeDp:upper:1078:621"),"resources match the logical viewport and selected density");
  check(w.calls.contains("setSmallestScreenWidthDp:upper:621"),"smallest width belongs to the top viewport");
  slot.index=1;h.applyFullscreenBounds(slot);w=(android.window.WindowContainerTransaction)h.applied;
  check(w.calls.stream().noneMatch(s->s.startsWith("setDensityDpi")),"other side density unchanged");
  check(w.calls.stream().noneMatch(s->s.startsWith("setAppBounds")||s.startsWith("setScreenSizeDp")||s.startsWith("setSmallestScreenWidthDp")),"ordinary side tasks acquire no top resource overrides");
  var p=new Promotion();w=(android.window.WindowContainerTransaction)h.fullscreenExitTransaction(p);
  check(w.calls.contains("setDensityDpi:upper:0"),"top native return clears density override");
  check(w.calls.contains("setAppBounds:upper:null")&&w.calls.contains("setScreenSizeDp:upper:0:0")&&w.calls.contains("setSmallestScreenWidthDp:upper:0"),"return clears all top metrics in the same native transaction");
  check(w.calls.contains("setFocusable:upper:true")&&w.calls.contains("reorder:side:true"),"return keeps native focusability and side visibility");
  check(w.calls.contains("setForceTranslucent:upper:false"),"ordinary return restores opaque native policy");
  p.liveMainReveal=true;w=(android.window.WindowContainerTransaction)h.fullscreenExitTransaction(p);
  check(w.calls.contains("setForceTranslucent:upper:true"),"live return keeps the real underlying Home/app visible");
  p.liveMainReveal=false;
  p.slot.index=1;w=(android.window.WindowContainerTransaction)h.fullscreenExitTransaction(p);
  check(w.calls.stream().noneMatch(s->s.startsWith("setDensityDpi")),"side return does not acquire top density override");
  check(w.calls.stream().noneMatch(s->s.startsWith("setAppBounds")||s.startsWith("setScreenSizeDp")||s.startsWith("setSmallestScreenWidthDp")),"ordinary side return leaves resource policy alone");
  h=host();h.preferMainAfterTopChange();check(h.service.calls==1&&h.service.last==1,"admitted top defaults to visible Home");
  h=host();h.infos.add(new Info(3,1,"main",false));h.current=new LiveTask(3,"main");h.preferMainAfterTopChange();
  check(h.service.calls==1&&h.service.last==3,"existing main app wins over top and Home");
  for(int state=0;state<7;state++){
   h=host();switch(state){case 0:h.suspendRequested=true;break;case 1:h.screenOn=false;break;case 2:h.keyguardLocked=true;break;
    case 3:h.scene=2;break;case 4:h.infos.add(new Info(9,5,"foreign",true));break;case 5:h.infos.get(0).isVisible=false;break;
    case 6:h.defer=true;break;}
   h.preferMainAfterTopChange();if(state==6){h.sceneEpoch++;h.queue.remove(0).run();}
   check(h.service.calls==0,"do not steal focus across unsafe/stale state "+state);
  }
  h=host();p=new Promotion();h.infos.set(1,new Info(42,1,"upper",true));h.restoreTopMainOpacity(p);
  check(((android.window.WindowContainerTransaction)h.applied).calls.contains("setForceTranslucent:upper:false"),
   "same fullscreen task restores native opacity after actual-surface reveal");
  h=host();h.restoreTopMainOpacity(new Promotion());check(h.applied==null,"late cleanup cannot modify readopted freeform");
  h=host();h.infos.set(1,new Info(42,1,"replacement",true));h.restoreTopMainOpacity(new Promotion());
  check(h.applied==null,"reused task id with different token is not touched");
  h=host();h.defer=true;h.infos.set(1,new Info(42,1,"upper",true));h.restoreTopMainOpacity(new Promotion());
  h.infos.set(1,new Info(42,6,"upper",true));h.queue.remove(0).run();
  check(h.applied==null,"queued restore rechecks current ownership at execution");
  System.out.println("Production top density/return/default-focus checks="+checks+" passed");
 }
}'''
files={'com/jiusis/mistep/TopConfigRegression.java':body,
 'android/window/WindowContainerTransaction.java':'package android.window;public class WindowContainerTransaction {public java.util.List<String>calls=new java.util.ArrayList<>();}',
 'android/view/WindowInsets.java':'package android.view;public class WindowInsets {public static class Type {public static int captionBar(){return 32;}}}',
 'android/graphics/Rect.java':'package android.graphics;public class Rect {int a,b,c,d;public Rect(){}public Rect(int a,int b,int c,int d){this.a=a;this.b=b;this.c=c;this.d=d;}public String toString(){return a+","+b+","+c+","+d;}}',
 'android/app/ActivityTaskManager.java':'package android.app;public class ActivityTaskManager {}'}
paths=[]
for name,text in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths,str(src/'TopTaskGeometry.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TopConfigRegression'],check=True)
