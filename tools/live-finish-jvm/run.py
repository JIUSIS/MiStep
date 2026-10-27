import os
"""Exercise the actual Run.finish method, preserving transaction ordering and ownership."""
from pathlib import Path
import ast
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'build/tests/live-finish-jvm'
out.mkdir(parents=True, exist_ok=True)
src = root / 'module/src/main/java/com/jianguo/mistep'
source = (src / 'MiStepLiveSwap64.java').read_text(encoding='utf8')
start = source.index('        @Override public void finish(Object tx, boolean failed)')
end = source.index('        @Override public Object finishWindowTransaction()', start)
method = source[start:end].replace('@Override ', '')
fixture = ast.parse((root / 'tools/live-swap-jvm/run.py').read_text(encoding='utf8'))
files = next(ast.literal_eval(n.value) for n in fixture.body
             if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'FILES' for t in n.targets))
helper = out / 'de/robv/android/xposed/XposedHelpers.java'
helper.parent.mkdir(parents=True, exist_ok=True)
helper.write_text(files['de/robv/android/xposed/XposedHelpers.java'], encoding='utf8')
wrapper = r'''package com.jianguo.mistep;
import java.util.*;
import de.robv.android.xposed.XposedHelpers;
public class LiveFinishRegression {
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static class Tx implements AutoCloseable {
  List<String> ops=new ArrayList<>();float displayX;int applies,closes;long mNativeObject=1;
  public void merge(Object other){Tx replay=(Tx)other;check(replay.closes==0,"closed replay merged");displayX=replay.displayX;ops.addAll(replay.ops);replay.ops.clear();}
  public void apply(){applies++;} public void close(){closes++;mNativeObject=0;}
 }
 static class Owner {void finish(Object tx,float scale){((Tx)tx).ops.add("task-parents");}void finish(Object tx,boolean original,float scale){finish(tx,scale);}void restoreOriginal(Object tx,float scale){finish(tx,scale);}}
 static class Run {
  boolean uncertain,rollback,waitingCancel,resolved=true;
  Object flip=new Object(),finishReplay;Owner pair=new Owner(),single=new Owner();float oldSideScale=.25f,targetSideScale=.25f;
  void peersFinish(Object tx,boolean original){((Tx)tx).ops.add("peers");}
  void background(Object tx,boolean show){((Tx)tx).ops.add("background");}
  METHOD
 }
 static Tx replay(float x){Tx tx=new Tx();tx.displayX=x;tx.ops.add("WMS:"+x);return tx;}
 public static void main(String[] args){
  Run forward=new Run();Tx finish=new Tx(),right=replay(273);finish.ops.add("old-WMS:0");forward.finishReplay=right;
  forward.finish(finish,false);
  check(finish.displayX==273&&finish.ops.get(finish.ops.size()-1).equals("WMS:273.0"),"old finish offset won over current right-main tuple");
  check(finish.ops.indexOf("task-parents")<finish.ops.indexOf("WMS:273.0"),"current WMS operations did not follow owner handoff");
  check(finish.applies==0&&right.applies==0,"handoff split into another apply");
  check(right.closes==1&&forward.finishReplay==null,"merged replay was retained or leaked");
  Run reverse=new Run();reverse.rollback=true;Tx restored=new Tx(),left=replay(0);reverse.finishReplay=left;reverse.finish(restored,true);
  check(restored.displayX==0&&restored.ops.get(restored.ops.size()-1).equals("WMS:0.0"),"rollback retained right-main replay");
  Run home=new Run();home.flip=null;home.pair=null;Tx covered=new Tx();home.finish(covered,false);
  check(covered.ops.equals(List.of("task-parents","peers","background")),"Home required changing main magnification");
  Run absent=new Run();try{absent.finish(new Tx(),false);throw new AssertionError("missing replay accepted");}catch(IllegalStateException expected){checks++;}
  Run closed=new Run();Tx invalid=replay(273),safe=new Tx();invalid.close();closed.finishReplay=invalid;
  try{closed.finish(safe,false);throw new AssertionError("closed native replay merged");}catch(IllegalStateException expected){check(safe.ops.isEmpty(),"closed replay changed ownership before rejection");}
  Run uncertain=new Run();uncertain.uncertain=true;Tx untouched=new Tx();try{uncertain.finish(untouched,true);throw new AssertionError("uncertain state committed");}catch(IllegalStateException expected){check(untouched.ops.isEmpty(),"uncertain state changed surfaces");}
  System.out.println("actual Shell finish ordering checks="+checks+" passed");
 }
}'''.replace('METHOD', method)
target = out / 'com/jianguo/mistep/LiveFinishRegression.java'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(wrapper, encoding='utf8')
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                str(src / 'LiveSwapZoom.java'), str(helper), str(target)], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'com.jianguo.mistep.LiveFinishRegression'], check=True)
