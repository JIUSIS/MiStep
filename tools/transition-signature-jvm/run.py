"""Replay actual WM signature adapter against both ROM transaction shapes."""
from pathlib import Path
import os, shutil, subprocess, sys
root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
sources=[]
def put(name,text):
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');sources.append(str(p))
for name in ['TransitionMagnificationCompat','TransitionMagnificationReplay']:
    p=out/f'com/jiusis/mistep/{name}.java';p.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(root/f'module/src/main/java/com/jiusis/mistep/{name}.java',p);sources.append(str(p))
put('de/robv/android/xposed/XposedHelpers.java','''package de.robv.android.xposed;
public class XposedHelpers {
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new IllegalStateException(e);}}
 public static Object callMethod(Object o,String n,Object...a){try{return o.getClass().getMethod(n).invoke(o);}catch(Exception e){throw new IllegalStateException(e);}}
}''')
put('com/jiusis/mistep/TransitionSignatureRegression.java','''package com.jiusis.mistep;
import java.util.*;
public class TransitionSignatureRegression {
 static int checks,applied;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static class Container {final Object display;Container(Object d){display=d;}public Object getDisplayContent(){return display;}}
 public static class Change {public Object mContainer;Change(Object d){mContainer=new Container(d);}}
 public static class Transition {public Object mTargets;Transition(Object t){mTargets=t;}}
 public static void main(String[]a)throws Throwable{
  Object tx=new Object(),info=new Object(),display=new Object(),other=new Object();var targets=new ArrayList<Object>();
  for(int size:new int[]{5,6}){
   Object[] args=new Object[size];args[0]=3;args[1]=0;args[2]=targets;args[3]=tx;args[4]=12;
   check(TransitionMagnificationCompat.isStart(args),"core signature admitted");
   check(args[2]==targets&&args[3]==tx,"original list and start transaction preserved");
   for(int i=0;i<4;i++){var bad=args.clone();bad[i]=null;check(!TransitionMagnificationCompat.isStart(bad),"incomplete start rejected");}
  }
  check(!TransitionMagnificationCompat.isStart(new Object[]{3,0,targets,tx}),"wrapper excluded to prevent double apply");
  check(!TransitionMagnificationCompat.isStart(null),"null start excluded");
  Object[] displays={other,display};check(TransitionMagnificationCompat.finishDisplays(null,new Object[]{tx,info,displays})==displays,"modern native display array preserved");
  var transition=new Transition(Arrays.asList(new Change(other),new Change(display),new Change(display),new Change(null)));
  Object[] actual=TransitionMagnificationCompat.finishDisplays(transition,new Object[]{tx,info});
  check(actual.length==2&&actual[0]==other&&actual[1]==display,"legacy displays derived from actual targets with identity preserved");
  check(TransitionMagnificationReplay.reapplyIfActive(true,display,true,actual,tx,(d,t)->{check(d==display&&t==tx,"same display and native finish transaction");applied++;}),"legacy finish replays");
  check(applied==1,"duplicate display replays only once");
  check(!TransitionMagnificationReplay.reapplyIfActive(true,new Object(),true,actual,tx,(d,t)->{throw new AssertionError();}),"unrelated display excluded");
  check(!TransitionMagnificationReplay.reapplyIfActive(true,display,false,actual,tx,(d,t)->{throw new AssertionError();}),"global lock gate retained");
  check(TransitionMagnificationCompat.finishDisplays(transition,new Object[]{tx,info,new Object()})==null,"malformed modern display array rejected");
  check(TransitionMagnificationCompat.finishDisplays(null,new Object[]{tx,info})==null,"missing legacy owner rejected");
  check(TransitionMagnificationCompat.finishDisplays(new Transition(new Object()),new Object[]{tx,info})==null,"unknown target layout rejected");
  var incomplete=new Change(display);incomplete.mContainer=null;
  check(TransitionMagnificationCompat.finishDisplays(new Transition(Arrays.asList(incomplete)),new Object[]{tx,info})==null,"incomplete participants rejected");
  check(TransitionMagnificationCompat.finishDisplays(new Transition(List.of()),new Object[]{tx,info}).length==0,"empty participants do not assume default display");
  for(Object[] invalid:new Object[][]{null,{}, {tx}, {null,info},{tx,null}})check(TransitionMagnificationCompat.finishDisplays(transition,invalid)==null,"invalid finish shape rejected");
  System.out.println("actual WM transition signature checks="+checks+" passed");
 }
}''')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*sources],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TransitionSignatureRegression'],check=True)
