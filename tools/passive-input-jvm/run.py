"""Exercise the production channel adapter and resource ownership with JVM fakes."""
from pathlib import Path
import os
import subprocess
import sys

root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]); out.mkdir(parents=True,exist_ok=True)
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
files={
'android/os/Binder.java':'package android.os; public class Binder {}',
'android/os/InputConfig.java':'package android.os; public class InputConfig {public static int DO_NOT_PILFER=1048576;}',
'android/view/SurfaceControl.java':'''package android.view;
public class SurfaceControl {public static class Transaction {
 public static Transaction last;public static boolean fail;public int applied,closed;public Object surface,handle;
 public Transaction(){last=this;}public void setInputWindowInfo(Object s,Object h){surface=s;handle=h;if(fail)throw new IllegalStateException();}
 public void apply(){applied++;}public void close(){closed++;}}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;
import java.lang.reflect.*;
public class XposedHelpers {
 public static Object callMethod(Object o,String n,Object...a){
  for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)
   try{return m.invoke(o,a);}catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}
   catch(Exception e){throw new RuntimeException(e);}
  throw new NoSuchMethodError(n);
 }
 public static Class<?> findClass(String n,ClassLoader l){try{return Class.forName(n,true,l);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object newInstance(Class<?> c,Object...a){try{return c.getConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
 public static int getStaticIntField(Class<?> c,String n){try{return c.getField(n).getInt(null);}catch(Exception e){throw new RuntimeException(e);}}
 public static void setIntField(Object o,String n,int v){try{o.getClass().getField(n).setInt(o,v);}catch(Exception e){throw new RuntimeException(e);}}
}''',
'com/jiusis/mistep/PassiveInputRegression.java':'''package com.jiusis.mistep;
import java.util.*;import android.view.SurfaceControl.Transaction;
public class PassiveInputRegression {
 static int checks;static ClassLoader loader=PassiveInputRegression.class.getClassLoader();
 static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 public static class Channel {public int disposed;public Object getToken(){return this;}public void dispose(){disposed++;}}
 public static class Monitor {public Channel input=new Channel();public int disposed;public Channel getInputChannel(){return input;}public void dispose(){disposed++;input.dispose();}}
 public static class Handle {public int inputConfig=16388;}
 public static class Spy {public Handle mWindowHandle=new Handle();public Object mInputSurface=new Object();}
 public static class Modern {
  public Map<Object,Spy> mInputMonitors=new HashMap<>();public Monitor monitor;public Spy spy;public String name;public boolean missing;public int calls;
  public Monitor monitorGestureInput(android.os.Binder token,String n,int display){
   calls++;name=n;monitor=new Monitor();spy=new Spy();if(!missing)mInputMonitors.put(monitor.input,spy);return monitor;}
 }
 public static class Legacy extends Modern {public Channel legacy=new Channel();public Channel monitorInput(String n,int d){return legacy;}}
 public static class Broken extends Modern {public Channel monitorInput(String n,int d){throw new IllegalStateException("existing API failed");}}
 public static void main(String[]args){
  Legacy old=new Legacy();var a=MiStepPassiveInputChannel.open(old,"old",loader);
  check(a.input==old.legacy,"legacy channel retained");check(old.calls==0,"no new monitor on old ROM");
  a.close();a.close();check(old.legacy.disposed==1,"legacy close once");
  Modern modern=new Modern();Spy foreign=new Spy();modern.mInputMonitors.put("foreign",foreign);
  var b=MiStepPassiveInputChannel.open(modern,"MiStepBottomReplay",loader);
  check(b.input==modern.monitor.input,"actual new input channel");check(modern.calls==1,"one owned monitor");
  check(modern.name.equals("MiStepBottomReplay"),"own name");check(modern.spy.mWindowHandle.inputConfig==1064964,"preserve bits plus passive");
  check(foreign.mWindowHandle.inputConfig==16388,"foreign window untouched");
  check(Transaction.last.surface==modern.spy.mInputSurface&&Transaction.last.handle==modern.spy.mWindowHandle,"only own window published");
  check(Transaction.last.applied==1&&Transaction.last.closed==1,"transaction applied and closed");
  b.close();b.close();check(modern.monitor.disposed==1&&modern.monitor.input.disposed==1,"new owner closed once");
  Broken broken=new Broken();try{MiStepPassiveInputChannel.open(broken,"broken",loader);throw new AssertionError();}
  catch(IllegalStateException expected){check(broken.calls==0,"runtime error not mistaken for missing method");}
  Modern missing=new Modern();missing.missing=true;
  try{MiStepPassiveInputChannel.open(missing,"missing",loader);throw new AssertionError();}
  catch(IllegalStateException expected){check(missing.monitor.disposed==1,"missing handle closes owner");}
  Modern failed=new Modern();Transaction.fail=true;
  try{MiStepPassiveInputChannel.open(failed,"failure",loader);throw new AssertionError();}
  catch(IllegalStateException expected){check(failed.monitor.disposed==1,"publication failure closes owner");check(Transaction.last.closed==1,"failed transaction closed");}
  Transaction.fail=false;
  System.out.println("Passive input adapter checks passed: "+checks);
 }
}'''
}
for path,text in files.items():
    dest=out/path; dest.parent.mkdir(parents=True,exist_ok=True); dest.write_text(text,encoding='utf8')
source=root/'module/src/main/java/com/jiusis/mistep/MiStepPassiveInputChannel.java'
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),
                *[str(out/p) for p in files],str(source)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.PassiveInputRegression'],check=True)
