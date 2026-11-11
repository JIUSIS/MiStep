"""Run the production tablet IME viewport owner with native-state fakes."""
import ast
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "build/tests/ime-viewport-jvm"
JDK = Path(os.environ["MISTEP_JAVA_BIN"])
tree = ast.parse((ROOT / "tools/live-swap-jvm/run.py").read_text(encoding="utf-8-sig"))
shared = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign)
              and any(isinstance(t, ast.Name) and t.id == "FILES" for t in n.targets))
helper = shared["de/robv/android/xposed/XposedHelpers.java"]
helper = helper[:-1] + r'''
 public static Class<?> findClass(String n,ClassLoader l){try{return Class.forName(n,true,l);}catch(Exception e){throw new RuntimeException(e);}}
 public static void setIntField(Object o,String n,int v){try{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.setInt(o,v);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object newInstance(Class<?> c,Object...a){try{for(var k:c.getDeclaredConstructors())if(k.getParameterCount()==a.length){k.setAccessible(true);return k.newInstance(a);}throw new IllegalStateException("constructor");}catch(Exception e){throw new RuntimeException(e);}}
}'''
FILES = {
    "de/robv/android/xposed/XposedHelpers.java": helper,
    "android/graphics/Rect.java": (ROOT / "tools/test-stubs/android/graphics/Rect.java").read_text().replace(
        "public int width()", "public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);} public int width()"),
    "android/content/res/Configuration.java": r'''package android.content.res;
import android.graphics.Rect;
public class Configuration {
 public int seq=27,orientation=1;public WindowConfiguration windowConfiguration=new WindowConfiguration();
 public Configuration(){}public Configuration(Configuration c){seq=c.seq;orientation=c.orientation;windowConfiguration=new WindowConfiguration(c.windowConfiguration);}
 public static class WindowConfiguration{public int mode=6;public Rect max=new Rect(0,0,3048,2032);WindowConfiguration(){}WindowConfiguration(WindowConfiguration w){mode=w.mode;max=new Rect(w.max);}public void setWindowingMode(int m){mode=m;}public void setMaxBounds(Rect b){max=new Rect(b);}}
 public boolean equals(Object o){return o instanceof Configuration c&&orientation==c.orientation&&seq==c.seq&&windowConfiguration.mode==c.windowConfiguration.mode&&windowConfiguration.max.equals(c.windowConfiguration.max);}
}''',
    "android/view/InsetsState.java": "package android.view;public class InsetsState {}",
    "com/android/server/wm/DisplayFrames.java": r'''package com.android.server.wm;
public class DisplayFrames{public Object info;public DisplayFrames(Object insets,Object i,Object cutout,Object corners,Object privacy,Object shape){info=i;}}''',
    "com/jiusis/mistep/ImeViewportTest.java": r'''package com.jiusis.mistep;
import android.graphics.Rect;import android.content.res.Configuration;
public class ImeViewportTest{
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class Info{public int logicalWidth=3048,logicalHeight=2032,appWidth=3048,appHeight=2032,logicalDensityDpi=400;public Object displayCutout=new Object(),roundedCorners=new Object(),privacyIndicatorBounds=new Object(),displayShape=new Object();Info(){}Info(Info i){logicalWidth=i.logicalWidth;logicalHeight=i.logicalHeight;logicalDensityDpi=i.logicalDensityDpi;}}
 static class Container{boolean organized;boolean isOrganized(){return organized;}}
 static class Display{Info info=new Info();Window target,mCurrentFocus,mImeLayeringTarget;App mFocusedApp;Container mImeContainer=new Container();int scans;Object getDisplayInfo(){return info;}Window getImeInputTarget(){return target;}Object computeImeLayeringTarget(boolean update){scans++;if(update)mImeLayeringTarget=target;return target;}}
 static class Task{Display display;int mode=6;boolean translucent=true;Rect bounds=new Rect(0,0,1494,2357);Object getDisplayContent(){return display;}int getWindowingMode(){return mode;}boolean isForceTranslucent(){return translucent;}Rect getBounds(){return bounds;}}
 static class App{Task task;Configuration configuration=new Configuration();App(Task t){task=t;}Task getTask(){return task;}Object getDisplayContent(){return task.display;}Configuration getConfiguration(){return configuration;}}
 static class State{Configuration configuration;State(Configuration c){configuration=c;}}
 static class Token{Object mFixedRotationTransformState,parent=new Object();int applies,finishes;Info info;Object getParent(){return parent;}void applyFixedRotationTransform(Info i,Object f,Configuration c){applies++;info=i;mFixedRotationTransformState=new State(c);}void finishFixedRotationTransform(){finishes++;mFixedRotationTransformState=null;}}
 static class Attrs{int type;Attrs(int t){type=t;}}
 static class Window{Display display;Task task;App mActivityRecord;Token mToken=new Token();Attrs mAttrs;boolean mIsImWindow;Window(Display d,Task t,int type){display=d;task=t;mAttrs=new Attrs(type);mIsImWindow=type==2011;}Object getDisplayContent(){return display;}Task getTask(){return task;}Window getWindowState(){return this;}}
 public static void main(String[]a){
  Display d=new Display();Task top=new Task();top.display=d;App app=new App(top);Window editor=new Window(d,top,1);editor.mActivityRecord=app;d.target=d.mCurrentFocus=d.mImeLayeringTarget=editor;d.mFocusedApp=app;
  Window ime=new Window(d,null,2011);MiStepImeViewport owner=new MiStepImeViewport();
  Task mainTask=new Task();mainTask.display=d;mainTask.mode=1;Window mainWindow=new Window(d,mainTask,1);
  check(MiStepImeViewport.skipOtherTask(mainWindow,d,t->t==top),"main Z-order masked focused interactive IME");
  check(!MiStepImeViewport.skipOtherTask(editor,d,t->t==top),"native TOP candidate excluded");
  check(!MiStepImeViewport.skipOtherTask(mainWindow,d,t->false),"stale lease changed native layering");
  d.mImeLayeringTarget=mainWindow;MiStepImeViewport.refreshLayering(d,t->t==top);
  check(d.scans==1&&d.mImeLayeringTarget==editor,"input-target switch did not rerun native layering scan");
  MiStepImeViewport.refreshLayering(d,t->t==top);check(d.scans==1,"stable layering rescanned unnecessarily");
  check(MiStepImeViewport.target(d,t->t==top)==app,"focused portrait tablet lease rejected");
  check(owner.update(ime,d,t->t==top,s->{})==app,"viewport did not acquire native state");
  check(ime.mToken.info.logicalWidth==1494&&ime.mToken.info.logicalHeight==2357,"IME retained physical landscape bounds");
  check(d.info.logicalWidth==3048&&d.info.logicalHeight==2032,"physical display was mutated");
  check(app.configuration.windowConfiguration.mode==6&&app.configuration.seq==27,"task configuration was mutated");
  State installed=(State)ime.mToken.mFixedRotationTransformState;
  check(installed.configuration.windowConfiguration.mode==1&&installed.configuration.windowConfiguration.max.equals(top.bounds),"IME configuration retained split role or physical max bounds");
  owner.update(ime,d,t->true,s->{});check(ime.mToken.applies==1,"stable native lease restarted");
  app.configuration.seq++;owner.update(ime,d,t->true,s->{});check(ime.mToken.applies==1,"sequence-only update restarted native viewport");
  d.mFocusedApp=null;check(owner.update(ime,d,t->true,s->{})==null&&ime.mToken.finishes==1,"main focus did not restore native IME");
  d.mFocusedApp=app;owner.update(ime,d,t->true,s->{});check(ime.mToken.applies==2,"return to side did not reconfigure");
  ime.mToken.mFixedRotationTransformState=new Object();owner.clear(s->{});check(ime.mToken.finishes==1,"cleanup finished a foreign native owner");
  ime.mToken.mFixedRotationTransformState=null;owner.update(ime,d,t->true,s->{});owner.clear(s->{});check(ime.mToken.finishes==2,"normal exit failed to restore owned state");
  owner.clear(s->{});check(ime.mToken.finishes==2,"cleanup was not idempotent");
  d.info.logicalWidth=1080;d.info.logicalHeight=2400;check(MiStepImeViewport.target(d,t->true)==null,"portrait phone admitted");d.info.logicalWidth=3048;d.info.logicalHeight=2032;
  d.info.logicalDensityDpi=800;check(MiStepImeViewport.target(d,t->true)==null,"small landscape phone admitted");d.info.logicalDensityDpi=400;
  check(MiStepImeViewport.target(d,t->false)==null,"stale TOP lease admitted");
  d.mImeContainer.organized=true;check(MiStepImeViewport.target(d,t->true)==null,"native organized IME was replaced");d.mImeContainer.organized=false;
  d.mImeLayeringTarget=null;check(MiStepImeViewport.target(d,t->true)==null,"unknown layering target admitted");d.mImeLayeringTarget=editor;
  Window popup=new Window(d,top,2);popup.mActivityRecord=app;d.mCurrentFocus=popup;check(MiStepImeViewport.target(d,t->true)==app,"same-task native popup rejected");
  top.mode=1;check(MiStepImeViewport.target(d,t->true)==null,"returned main task retained viewport");top.mode=6;
  Window status=new Window(d,null,2000);check(owner.update(status,d,t->true,s->{})==null&&status.mToken.applies==0,"system bar received IME viewport");
  check(owner.update(ime,new Display(),t->true,s->{})==null,"foreign display admitted");
  ime.mToken.parent=null;check(owner.update(ime,d,t->true,s->{})==null,"detached token admitted");
  System.out.println("production native IME viewport checks="+checks+" passed");
 }
}'''
}
sources = []
for name, content in FILES.items():
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    sources.append(str(path))
for name in ["MiStepImeViewport", "MiStepImePolicy", "MiStepSideRoleChange",
             "ZoomChannelState", "ZoomCommand", "ZoomAnimCommand"]:
    sources.append(str(ROOT / f"module/src/main/java/com/jiusis/mistep/{name}.java"))
subprocess.run([str(JDK / "javac.exe"), "-encoding", "UTF-8", "--release", "17",
                "-d", str(OUT), *sources], check=True)
subprocess.run([str(JDK / "java.exe"), "-cp", str(OUT),
                "com.jiusis.mistep.ImeViewportTest"], check=True)
