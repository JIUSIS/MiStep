import os
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[2];out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
body=r"""package com.jiusis.mistep;
import java.util.*;
public class NativeSideReleaseTest {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 public static class Info{public int taskId=42,displayId=0,mode=5;public Object token=new Object();public boolean child;public int getWindowingMode(){return mode;}public boolean hasParentTask(){return child;}}
 public static class Surface{boolean valid=true;int releases;public boolean isValid(){return valid;}}
 public static class Appeared{Info info;Surface leash=new Surface();Appeared(Info i){info=i;}public Info getTaskInfo(){return info;}public Surface getLeash(){return leash;}}
 public static class MapLike{LinkedHashMap<Integer,Object> map=new LinkedHashMap<>();public Object get(int k){return map.get(k);}public int size(){return map.size();}public Object valueAt(int i){return new ArrayList<>(map.values()).get(i);}public int keyAt(int i){return new ArrayList<>(map.keySet()).get(i);}}
 public static class Organizer{public Object mLock=new Object();public MapLike mTasks=new MapLike(),mTaskListeners=new MapLike();int removes,nativeAppeared;boolean fail;public Object getTaskListener(Info i,boolean b){return mTaskListeners.map.getOrDefault(i.taskId,mTaskListeners.get(-5));}public void removeListener(Object c){removes++;mTaskListeners.map.values().removeIf(v->v==c);if(!fail)nativeAppeared++;else mTaskListeners.map.put(42,new Object());}}
 public static class Controller{public Organizer mTaskOrganizer;public Object mTaskToken;public Surface mTaskLeash=new Surface();int vanished;Controller(Organizer o,Info i){mTaskOrganizer=o;mTaskToken=i.token;}public void onTaskVanished(Info i){check(mTaskToken==i.token,"native callback exact token");vanished++;mTaskToken=null;mTaskLeash.releases++;mTaskLeash=null;}}
 public static class Transitions{int unreg,reg;public void unregisterTaskView(Object c){unreg++;}public void registerTaskView(Object c){reg++;}}
 Info info=new Info();Organizer o=new Organizer();Controller c=new Controller(o,info);Transitions t=new Transitions();Object fallback=new Object();Appeared appeared=new Appeared(info);
 NativeSideReleaseTest(){o.mTasks.map.put(42,appeared);o.mTaskListeners.map.put(42,c);o.mTaskListeners.map.put(-5,fallback);}
 boolean release(){return MiStepNativeSideRelease.release(c,t,42,info.token);}
 static void rejected(NativeSideReleaseTest h,String s){check(!h.release(),s);check(h.o.removes==0&&h.t.unreg==0&&h.c.vanished==0,"rejection is read only");}
 public static void main(String[]args){
  var h=new NativeSideReleaseTest();check(h.release(),"native5 releases side owner");check(h.o.getTaskListener(h.info,false)==h.fallback&&h.o.nativeAppeared==1,"existing mode5 fallback owns canonical task");check(h.c.vanished==1&&h.c.mTaskToken==null&&h.appeared.leash.releases==0,"only controller copy released; native canonical lives");check(h.t.unreg==1&&h.t.reg==1,"repository detached then idle controller registered");check(h.release()&&h.o.removes==1,"late repeat leaves settled native task alone");
  h=new NativeSideReleaseTest();h.info.mode=6;rejected(h,"side6 stays owned");
  h=new NativeSideReleaseTest();h.info.mode=1;rejected(h,"fullscreen promotion stays separate");
  h=new NativeSideReleaseTest();h.info.displayId=1;rejected(h,"foreign display untouched");
  h=new NativeSideReleaseTest();h.info.child=true;rejected(h,"nested task untouched");
  h=new NativeSideReleaseTest();h.c.mTaskToken=new Object();rejected(h,"replacement binding untouched");
  h=new NativeSideReleaseTest();h.o.mTaskListeners.map.remove(-5);rejected(h,"missing native owner untouched");
  h=new NativeSideReleaseTest();h.o.mTaskListeners.map.put(99,h.c);rejected(h,"shared controller registration untouched");
  h=new NativeSideReleaseTest();h.c.mTaskLeash=h.appeared.leash;rejected(h,"canonical handle never released");
  h=new NativeSideReleaseTest();h.appeared.leash.valid=false;rejected(h,"invalid canonical untouched");
  h=new NativeSideReleaseTest();h.o.mTaskListeners.map.put(42,new Object());rejected(h,"third party owner untouched");
  h=new NativeSideReleaseTest();h.o.mTasks.map.clear();rejected(h,"vanished task ignored");
  h=new NativeSideReleaseTest();h.o.mTaskListeners.map.remove(42);check(h.release()&&h.o.removes==0&&h.c.vanished==1,"native already owns but stale controller still cleared");
  h=new NativeSideReleaseTest();h.o.fail=true;boolean failed=false;try{h.release();}catch(IllegalStateException e){failed=true;}check(failed&&h.t.reg==1&&h.c.vanished==0,"failed owner handoff restores repository and retains copy");
  System.out.println("Native side release checks="+checks+" passed");
 }
}"""
helper=r"""package de.robv.android.xposed;import java.lang.reflect.*;
public class XposedHelpers{public static Object getObjectField(Object o,String n){try{Field f=o.getClass().getField(n);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}public static Object callMethod(Object o,String n,Object...a){for(Method m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)try{return m.invoke(o,a);}catch(Exception e){throw new RuntimeException(e);}throw new IllegalArgumentException(n);}}
"""
files={'com/jiusis/mistep/NativeSideReleaseTest.java':body,'de/robv/android/xposed/XposedHelpers.java':helper}
for n,s in files.items():p=out/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s,encoding='utf-8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*[str(out/n) for n in files],str(root/'module/src/main/java/com/jiusis/mistep/MiStepNativeSideRelease.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.NativeSideReleaseTest'],check=True)
