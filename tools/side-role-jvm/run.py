import os
"""Actual role scope against the target ROM's mode-before-translucency ordering."""
from pathlib import Path
import ast,subprocess,sys
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT / 'build/tests/side-role-jvm'
JDK=Path(os.environ["MISTEP_JAVA_BIN"])
tree=ast.parse((ROOT/'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
files=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='FILES' for t in n.targets))
fake=OUT/'de/robv/android/xposed/XposedHelpers.java';fake.parent.mkdir(parents=True,exist_ok=True);fake.write_text(files['de/robv/android/xposed/XposedHelpers.java'],encoding='utf8')
test=OUT/'com/jianguo/mistep/SideRoleRegression.java';test.parent.mkdir(parents=True,exist_ok=True)
test.write_text(r'''package com.jianguo.mistep;
public class SideRoleRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class Task {Object display;int mode=1;boolean force;Task(Object d){display=d;}public Object getDisplayContent(){return display;}public int getWindowingMode(){return mode;}public boolean isForceTranslucent(){return force;}}
 static class Change {int mode=6,mask=64;boolean force=true;public int getWindowingMode(){return mode;}public int getChangeMask(){return mask;}public boolean getForceTranslucent(){return force;}}
 public static void main(String[] args)throws Exception{
  Object display=new Object();Task task=new Task(display),peer=new Task(display);Change change=new Change();
  check(!MiStepSideRoleChange.isSide(task),"fullscreen task was already classified side");
  Object lease=MiStepSideRoleChange.enter(task,change,display);
  task.mode=6;check(MiStepSideRoleChange.isSide(task),"mode callback lost target side role before force bit");
  check(!MiStepSideRoleChange.isSide(peer),"another task inherited the WCT role");
  boolean[] isolated={false};Thread worker=new Thread(()->isolated[0]=!MiStepSideRoleChange.isSide(task));worker.start();worker.join();check(isolated[0],"role escaped native call thread");
  Object nested=MiStepSideRoleChange.enter(task,change,display);MiStepSideRoleChange.exit(nested);check(MiStepSideRoleChange.isSide(task),"nested call released outer role");
  task.force=true;MiStepSideRoleChange.exit(lease);check(MiStepSideRoleChange.isSide(task),"committed native side role lost");
  task.force=false;lease=MiStepSideRoleChange.enter(task,change,display);try{throw new IllegalStateException();}catch(Exception expected){}finally{MiStepSideRoleChange.exit(lease);}
  check(!MiStepSideRoleChange.isSide(task),"exception left pending side role");
  check(MiStepSideRoleChange.enter(task,change,new Object())==null,"foreign display acquired role");
  change.mask=0;check(MiStepSideRoleChange.enter(task,change,display)==null,"absent translucency change acquired role");change.mask=64;
  change.force=false;check(MiStepSideRoleChange.enter(task,change,display)==null,"inverse change acquired side role");change.force=true;
  change.mode=5;check(MiStepSideRoleChange.enter(task,change,display)==null,"native freeform acquired side role");
  change.mode=1;check(MiStepSideRoleChange.enter(task,change,display)==null,"fullscreen change acquired side role");
  MiStepSideRoleChange.exit(null);MiStepSideRoleChange.exit(task);check(!MiStepSideRoleChange.isSide(null),"null task classified side");
  System.out.println("actual native side-role ordering/scope checks="+checks+" passed");
 }
}''',encoding='utf8')
source=ROOT/'module/src/main/java/com/jianguo/mistep/MiStepSideRoleChange.java'
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),str(fake),str(test),str(source)],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jianguo.mistep.SideRoleRegression'],check=True)
