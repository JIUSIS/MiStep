"""Exercise actual TOP state isolation, physical sources, IME retention and stale leases."""
import ast, os, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'build/tests/top-insets-jvm'
JDK=Path(os.environ['MISTEP_JAVA_BIN'])
tree=ast.parse((ROOT/'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
shared=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='FILES' for t in n.targets))
helpers=shared['de/robv/android/xposed/XposedHelpers.java'].replace('public class XposedHelpers {',r'''public class XposedHelpers {
 public static Class<?> findClass(String n,ClassLoader l){try{return Class.forName(n);}catch(Exception e){throw new IllegalStateException(e);}}
 public static Object getStaticObjectField(Class<?> c,String n){try{return c.getField(n).get(null);}catch(Exception e){throw new IllegalStateException(e);}}
 public static Object newInstance(Class<?> c,Object...a){try{for(Constructor<?> k:c.getDeclaredConstructors())if(k.getParameterCount()==a.length){k.setAccessible(true);return k.newInstance(a);}throw new NoSuchMethodException();}catch(Exception e){throw new IllegalStateException(e);}}
''')
FILES={
'de/robv/android/xposed/XposedHelpers.java':helpers,
'android/view/DisplayCutout.java':'package android.view; public class DisplayCutout{public static final Object NO_CUTOUT=new Object();}',
'android/view/RoundedCorners.java':'package android.view; public class RoundedCorners{public static final Object NO_ROUNDED_CORNERS=new Object();}',
'com/jiusis/mistep/TopInsetsTest.java':r'''package com.jiusis.mistep;
import android.graphics.Rect;import java.util.*;
public class TopInsetsTest{
 static int checks;static void check(boolean v,String reason){checks++;if(!v)throw new AssertionError(reason);}
 static class WC{Rect bounds=new Rect(0,0,2964,1707);Rect getAppBounds(){return bounds;}}
 static class Config{WC windowConfiguration=new WC();}
 static class Task{int mode=6;boolean translucent=true;Rect bounds=new Rect(0,0,2964,1707);Config config=new Config();
  int getWindowingMode(){return mode;}boolean isForceTranslucent(){return translucent;}Rect getBounds(){return bounds;}Config getConfiguration(){return config;}}
 static class Window{Object display;Task task;int notifications;Window(Object d,Task t){display=d;task=t;}Object getDisplayContent(){return display;}Object getTask(){return task;}void notifyInsetsChanged(){notifications++;}}
 static class Display{Window[] windows;void forAllWindows(java.util.function.Consumer<Object> c,boolean top){for(Window w:windows)c.accept(w);}}
 static class Source{int id,type;boolean visible=true;Source(int i,int t){id=i;type=t;}int getType(){return type;}int getId(){return id;}}
 static class State{
  ArrayList<Source> sources=new ArrayList<>();Rect frame=new Rect(0,0,1080,2460),roundFrame;Object cutout=new Object(),round=new Object();
  State(){}State(State s){sources.addAll(s.sources);frame=s.frame;cutout=s.cutout;round=s.round;}
  int sourceSize(){return sources.size();}Source sourceAt(int i){return sources.get(i);}void removeSource(int id){sources.removeIf(s->s.id==id);}
  void setDisplayFrame(Rect f){frame=f;}void setDisplayCutout(Object c){cutout=c;}void setRoundedCorners(Object r){round=r;}void setRoundedCornerFrame(Rect f){roundFrame=f;}
 }
 static Object adjust(Window w,Display d,State s,Task owned){return MiStepTopInsets.adjust(w,d,s,t->t==owned);}
 public static void main(String[]args){
  Display d=new Display();Task top=new Task();Window w=new Window(d,top);State source=new State();
  for(int i=0;i<8;i++)source.sources.add(new Source(i,1<<i));source.sources.add(new Source(8,1));
  Source ime=source.sources.get(3);Object cutout=source.cutout,round=source.round;Rect frame=source.frame;
  State result=(State)adjust(w,d,source,top);
  check(result!=source,"shared InsetsState returned");check(result.sources.size()==1&&result.sources.get(0)==ime,"native IME source or visibility changed");
  check(source.sources.size()==9&&source.frame==frame&&source.cutout==cutout&&source.round==round,"physical display state mutated");
  check(result.frame.equals(top.bounds)&&result.frame!=top.bounds,"virtual frame absent or shared");
  check(result.cutout==android.view.DisplayCutout.NO_CUTOUT&&result.round==android.view.RoundedCorners.NO_ROUNDED_CORNERS,"phone cutout or corners leaked");
  check(result.roundFrame.equals(top.bounds),"rounded frame not virtual");
  check(adjust(w,d,source,new Task())==source,"stale token accepted");check(adjust(w,new Display(),source,top)==source,"foreign display accepted");
  top.mode=1;check(adjust(w,d,source,top)==source,"returned main altered");top.mode=6;
  top.translucent=false;check(adjust(w,d,source,top)==source,"native multiwindow altered");top.translucent=true;
  top.bounds=new Rect(0,0,1251,2519);top.config.windowConfiguration.bounds=top.bounds;
  State portrait=(State)adjust(w,d,source,top);check(portrait!=source&&portrait.sources.size()==1&&portrait.sources.get(0)==ime&&portrait.frame.equals(top.bounds),"portrait exact interactive lease retains native IME and its viewport");
  check(adjust(w,d,source,new Task())==source,"unleased portrait task untouched");
  top.bounds=new Rect(1,0,2965,1707);check(adjust(w,d,source,top)==source,"offset freeform accepted");top.bounds=new Rect(0,0,2964,1707);
  top.config.windowConfiguration.bounds=null;check(adjust(w,d,source,top)==source,"non-full viewport accepted");top.config.windowConfiguration.bounds=top.bounds;
  check(MiStepTopInsets.adjust(null,d,source,t->true)==source,"null window changed state");check(MiStepTopInsets.adjust(w,d,null,t->true)==null,"null state changed");
  Task main=new Task();main.mode=1;Window m=new Window(d,main);d.windows=new Window[]{w,m};MiStepTopInsets.refresh(d,t->t==top);
  check(w.notifications==1&&m.notifications==0,"post-admission refresh touched main");MiStepTopInsets.refresh(d,t->false);check(w.notifications==1,"expired lease refreshed");
  System.out.println("actual TOP InsetsState isolation/IME/lifecycle checks="+checks+" passed");
 }
}'''}
sources=[]
for name,body in FILES.items():
 p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf8');sources.append(str(p))
sources.append(str(ROOT/'tools/test-stubs/android/graphics/Rect.java'))
for n in ['MiStepTopInsets','MiStepSideRoleChange']:sources.append(str(ROOT/f'module/src/main/java/com/jiusis/mistep/{n}.java'))
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),*sources],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.TopInsetsTest'],check=True)
