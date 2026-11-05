"""Exercise the actual TOP IME resolver against native object identities and focus changes."""
import ast, os, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'build/tests/ime-focus-jvm'
JDK=Path(os.environ['MISTEP_JAVA_BIN'])
tree=ast.parse((ROOT/'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
shared=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='FILES' for t in n.targets))
FILES={
'de/robv/android/xposed/XposedHelpers.java':shared['de/robv/android/xposed/XposedHelpers.java'],
'com/jiusis/mistep/ImeFocusTest.java':r'''package com.jiusis.mistep;
import android.graphics.Rect;
public class ImeFocusTest {
 static int checks;
 static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
 static class Task { Display display; Display getDisplayContent(){return display;} int mode=6;boolean translucent=true,fail;Rect bounds=new Rect(0,0,2460,1080);Object getWindowingMode(){if(fail)throw new IllegalStateException("native read failure");return mode;}boolean isForceTranslucent(){return translucent;}Rect getBounds(){return bounds;} }
 static class App {Task task;App(Task t){task=t;} Object getTask(){return task;} Object getDisplayContent(){return task.display;}}
 static class Display { Window mCurrentFocus;Window oldInputTarget;Object mFocusedApp; }
 static class Window { Display display;Task task;Window(Display d,Task t){display=d;task=t;}Display getDisplayContent(){return display;}Task getTask(){return task;} }
 public static void main(String[]args){
  Display d=new Display();Task top=new Task();Window editor=new Window(d,top);d.mCurrentFocus=editor;top.display=d;d.mFocusedApp=new App(top);
  check(MiStepImePolicy.isFocusedTopWindow(editor,d),"focused TOP excluded");
  check(MiStepImePolicy.isFocusedTopTask(top,d),"focused TOP root fallback excluded");
  d.oldInputTarget=new Window(d,new Task());check(MiStepImePolicy.isFocusedTopWindow(editor,d),"old input target prevented new TOP layering bootstrap");
  Window dialog=new Window(d,top);d.mCurrentFocus=dialog;check(MiStepImePolicy.isFocusedTopWindow(editor,d),"same TOP child window lost task identity");
  Window another=new Window(d,new Task());d.mCurrentFocus=another;check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"passive TOP stole IME");
  d.mCurrentFocus=editor;check(!MiStepImePolicy.isFocusedTopWindow(editor,new Display()),"foreign display admitted");
  d.mCurrentFocus=new Window(new Display(),top);check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"foreign focus admitted");
  d.mCurrentFocus=null;check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"null focus admitted");d.mCurrentFocus=editor;
  top.mode=1;check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"main task changed by TOP rule");top.mode=6;
  top.translucent=false;check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"unowned multiwindow admitted");top.translucent=true;
  top.bounds=new Rect(0,0,1080,2460);check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"portrait side task admitted");
  top.bounds=new Rect(1,0,2461,1080);check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"offset OEM freeform admitted");
  top.bounds=new Rect(0,0,2460,0);check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"zero-height task admitted");
  top.bounds=null;check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"unknown bounds admitted");top.bounds=new Rect(0,0,2460,1080);
  editor.task=null;check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"non-task window admitted");editor.task=top;
  check(!MiStepImePolicy.isFocusedTopWindow(null,d)&&!MiStepImePolicy.isFocusedTopWindow(editor,null),"null target admitted");
  Task side=new Task();side.display=d;side.bounds=new Rect(0,0,1080,2460);Window cell=new Window(d,side);
  java.util.function.Predicate<Object> lease=t->t==side;
  check(MiStepImePolicy.isPassiveSideWindow(cell,d,lease),"exact leased portrait cell stole native window focus");
  check(!MiStepImePolicy.isPassiveSideWindow(cell,d,t->false),"expired lease blocked native window");
  check(!MiStepImePolicy.isPassiveSideWindow(cell,d,t->t==top),"different task lease blocked native window");
  check(!MiStepImePolicy.isPassiveSideWindow(cell,new Display(),lease),"foreign display cell blocked native focus");
  side.mode=1;check(!MiStepImePolicy.isPassiveSideWindow(cell,d,lease),"main fullscreen blocked native focus");side.mode=6;
  side.translucent=false;check(!MiStepImePolicy.isPassiveSideWindow(cell,d,lease),"unowned mode6 blocked native focus");side.translucent=true;
  d.mCurrentFocus=null;side.bounds=new Rect(0,0,2460,1080);check(!MiStepImePolicy.isPassiveSideWindow(cell,d,lease),"TOP could not bootstrap focus before editor target");
  side.bounds=new Rect(20,20,2480,1100);check(!MiStepImePolicy.isPassiveSideWindow(cell,d,lease),"landscape native role blocked native focus");
  side.bounds=null;check(!MiStepImePolicy.isPassiveSideWindow(cell,d,lease),"unknown bounds blocked native focus");
  side.bounds=new Rect(0,0,0,2460);check(!MiStepImePolicy.isPassiveSideWindow(cell,d,lease),"empty bounds blocked native focus");
  check(!MiStepImePolicy.isPassiveSideWindow(null,d,lease),"null window blocked native focus");
  side.bounds=new Rect(0,0,1080,2460);side.fail=true;boolean sideFailed=false;try{MiStepImePolicy.isPassiveSideWindow(cell,d,lease);}catch(RuntimeException e){sideFailed=true;}check(sideFailed,"passive native lookup failure swallowed");
  d.mFocusedApp=null;check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"stale TOP window with no focused app stole input");d.mFocusedApp=new App(new Task());check(!MiStepImePolicy.isFocusedTopWindow(editor,d),"stale TOP window overrode main app focus");d.mFocusedApp=new App(top);
  top.fail=true;boolean failed=false;try{MiStepImePolicy.isFocusedTopWindow(editor,d);}catch(RuntimeException e){failed=true;}check(failed,"native lookup failure swallowed");
  System.out.println("actual TOP IME focus/task resolver checks="+checks+" passed");
 }
}'''
}
sources=[]
for name,body in FILES.items():
 p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');sources.append(str(p))
sources.append(str(ROOT/'tools/test-stubs/android/graphics/Rect.java'))
for name in ['MiStepImePolicy','MiStepSideRoleChange','ZoomChannelState','ZoomCommand','ZoomAnimCommand']:
 sources.append(str(ROOT/f'module/src/main/java/com/jiusis/mistep/{name}.java'))
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),*sources],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.ImeFocusTest'],check=True)
