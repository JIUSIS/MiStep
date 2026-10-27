import os
"""Run the actual host selection and side-surface admission methods with deterministic roots."""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[2]
src=root/'module/src/main/java/com/jianguo/mistep'
out=Path(sys.argv[1]) if len(sys.argv)>1 else root/'build/tests/standalone'
out.mkdir(parents=True,exist_ok=True)
def method(text,signature):
 start=text.index(signature);brace=text.index('{',start);depth=1;end=brace+1
 while depth:
  if text[end]=='{':depth+=1
  elif text[end]=='}':depth-=1
  end+=1
 return text[start:end]
host=(src/'MiStepHost.java').read_text(encoding='utf-8-sig')
methods='\n'.join(method(host,s) for s in ['    private boolean ordinaryRoot(', '    private boolean isSideToken(', '    private LiveTask findMainTask(', '    private boolean isHomeRoot(', '    private boolean mayWriteSideSurface('])
body=r"""
package com.jianguo.mistep;
import java.util.*;
public class HomeAndSideSurfaceRegression {
 static int checks;
 static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class ComponentName{final String pkg;ComponentName(String p){pkg=p;}String getPackageName(){return pkg;}}
 static class Info {
  int taskId,userId=0,displayId=0,mode=1,type=1;Object token=new Object();
  boolean isVisible=true,isFocused,hasParent;
  ComponentName baseActivity=new ComponentName("app");
  boolean hasParentTask(){return hasParent;}int getWindowingMode(){return mode;}int getActivityType(){return type;}
 }
 static class XposedHelpers {
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
  static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}
  static Object callMethod(Object o,String n,Object...args){try{for(var m:o.getClass().getDeclaredMethods()){if(m.getName().equals(n)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(o,args);}}throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
 }
 static class LiveTask{int id;Object token;LiveTask(Object o){id=XposedHelpers.getIntField(o,"taskId");token=XposedHelpers.getObjectField(o,"token");}}
 static class Appeared{Info info;Appeared(Info i){info=i;}Info getTaskInfo(){return info;}}
 static class TaskMap{Appeared entry;Object get(int id){return entry;}}
 static class Organizer{Object mLock=new Object();TaskMap mTasks=new TaskMap();}
 static class Controller{Info mTaskInfo;Organizer mTaskOrganizer=new Organizer();Controller(Info i){mTaskInfo=i;mTaskOrganizer.mTasks.entry=new Appeared(i);}}
 static class PendingSidePromotion{boolean desktopLaunch;PendingSidePromotion(boolean d){desktopLaunch=d;}}
 static class Slot{int taskId;Object taskToken;boolean parked;int windowMode=6;Object controller;PendingSidePromotion pendingSidePromotion;}
 Slot[] slots={new Slot(),new Slot()}; List<Info> roots=new ArrayList<>();
 List<?> rootInfos(){return roots;}int currentUserId(){return 0;}Iterable<Integer> supportedTaskUsers(){return List.of(0);}
 boolean supportsTaskUser(int id){return id==0;}void updateTaskWindowState(int a,Object b,int c,boolean d){}
 __METHODS__
 static Info root(int id,int type,int mode,boolean focused){Info i=new Info();i.taskId=id;i.type=type;i.mode=mode;i.isFocused=focused;return i;}
 static void throwsScene(HomeAndSideSurfaceRegression h,String why)throws Exception{boolean rejected=false;try{h.findMainTask();}catch(IllegalStateException e){rejected=true;}check(rejected,why);}
 public static void main(String[] args)throws Exception{
  var h=new HomeAndSideSurfaceRegression();Info home=root(2,2,1,true),app=root(8,1,1,false);
  h.roots.add(home);h.roots.add(app);
  check(h.findMainTask()==null,"focused Home must exclude visible departing application");
  app.isFocused=true;throwsScene(h,"contradictory focused roots must keep current tasks");
  home.isFocused=false;check(h.findMainTask().id==8,"visible stale Home cannot override focused app");
  home.isVisible=false;home.isFocused=true;check(h.findMainTask().id==8,"invisible Home must not override foreground app");
  app.isFocused=false;check(h.findMainTask().id==8,"single visible ordinary root remains eligible");
  Info other=root(9,1,1,false);h.roots.add(other);throwsScene(h,"two unfocused visible ordinary roots are ambiguous");
  other.isFocused=true;check(h.findMainTask().id==9,"focused ordinary root wins over departing visible root");
  h.slots[1].taskToken=other.token;check(h.findMainTask().id==8,"owned side root never becomes main");
  h.slots[1].parked=true;check(h.findMainTask().id==9,"parked foreground task is no longer an owned side");
  h.roots.clear();Info fake=root(10,1,1,true);fake.baseActivity=new ComponentName("com.miui.home");h.roots.add(fake);
  check(h.findMainTask()==null,"launcher package never becomes ordinary app even if OEM type is standard");
  fake.baseActivity=new ComponentName("app");fake.mode=5;check(h.findMainTask()==null,"native freeform cannot enter fullscreen swap");
  fake.mode=1;fake.displayId=1;check(h.findMainTask()==null,"secondary display cannot enter swap");
  fake.displayId=0;fake.userId=10;check(h.findMainTask()==null,"unsupported user cannot enter swap");
  var slot=new Slot();Info cell=root(11,1,6,false);slot.controller=new Controller(cell);slot.taskId=cell.taskId;slot.taskToken=cell.token;
  check(h.mayWriteSideSurface(slot),"confirmed mode6 side may receive matrix");
  ((Controller)slot.controller).mTaskInfo=root(11,1,1,false);
  check(h.mayWriteSideSurface(slot),"stale controller info cannot hide canonical confirmed mode6");
  Object original=cell.token;cell.token=new Object();
  check(!h.mayWriteSideSurface(slot),"reused task id with new token cannot receive delayed transform");
  cell.token=original;
  cell.mode=1;check(!h.mayWriteSideSurface(slot),"canonical fullscreen state defeats stale slot mode6");
  cell.mode=6;slot.windowMode=1;check(!h.mayWriteSideSurface(slot),"slot fullscreen state blocks delayed matrix");
  slot.windowMode=6;slot.pendingSidePromotion=new PendingSidePromotion(true);
  check(!h.mayWriteSideSurface(slot),"Launcher selection blocks old cell matrix before mode callback");
  slot.pendingSidePromotion=null;((Controller)slot.controller).mTaskOrganizer.mTasks.entry=null;
  check(!h.mayWriteSideSurface(slot),"canonical task disappearance cannot be resurrected by late finish");
  slot.controller=null;check(!h.mayWriteSideSurface(slot),"missing controller has no surface ownership");
  System.out.println("actual Home/side-surface checks="+checks+" passed");
 }
}
""".replace('__METHODS__',methods)
file=out/'HomeAndSideSurfaceRegression.java';file.write_text(body,encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(src/'TaskUserPolicy.java'),str(file)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.HomeAndSideSurfaceRegression'],check=True)
