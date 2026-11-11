"""Actual target handoff order, transaction identity, reentrancy and failure release."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
files = {
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public class XposedHelpers {
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...args){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
}''',
'com/jiusis/mistep/MiStepImeViewport.java': '''package com.jiusis.mistep;import java.util.function.Predicate;
class MiStepImeViewport {static void refreshLayering(Object o,Predicate<Object> p){((ImeTargetRegression.Display)o).steps.add("layering");}}''',
'com/jiusis/mistep/ShellCompat.java': '''package com.jiusis.mistep;class ShellCompat {static boolean hasMethod(Class<?> c,String n,int count){for(var m:c.getMethods())if(m.getName().equals(n)&&m.getParameterCount()==count)return true;return false;}static Object call(Object o,String n,Object...a){return de.robv.android.xposed.XposedHelpers.callMethod(o,n,a);}}''',
'com/jiusis/mistep/ImeTargetRegression.java': '''package com.jiusis.mistep;import java.util.*;
public class ImeTargetRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static class Window {Object display;Window(Object d){display=d;}public Object getDisplayContent(){return display;}}
 public static class Display {
  public Object mInputMethodWindow=new Window(this);Object tx=new Object();List<String> steps=new ArrayList<>();Runnable parent;
  public Object getPendingTransaction(){steps.add("pending");return tx;}
  public void reapplyMagnificationSpec(Object t){check(t==tx,"uses the native pending WM transaction");steps.add("magnification");}
  public void updateImeParent(){steps.add("parent");if(parent!=null)parent.run();}
 }
 public static class Modern {public Object ime=new Window(this);public Object getImeWindow(){return ime;}}
 public static void main(String[]a){
  var modern=new Modern();check(MiStepImeWindow.current(modern)==modern.ime,"getter-only display without old field");modern.ime=null;check(MiStepImeWindow.current(modern)==null,"getter null is valid and does not probe missing legacy field");modern.ime=new Window(new Modern());try{MiStepImeWindow.current(modern);throw new AssertionError("foreign display IME accepted");}catch(IllegalStateException expected){checks++;}
  var h=new MiStepImeTargetRefresh();var d=new Display();
  h.update(d,t->true,w->{check(w==d.mInputMethodWindow,"actual IME window");d.steps.add("viewport/process");});
  check(d.steps.equals(List.of("layering","viewport/process","pending","magnification","parent")),"new viewport precedes matrix and parent in the native transaction");
  d.steps.clear();d.parent=()->h.update(d,t->true,w->{throw new AssertionError("recursive target refresh");});
  h.update(d,t->true,w->d.steps.add("viewport/process"));check(d.steps.size()==5,"native parent recursion is bounded");
  d.steps.clear();d.mInputMethodWindow=null;h.update(d,t->true,w->{throw new AssertionError("missing IME");});check(d.steps.equals(List.of("layering")),"absent IME keeps native behavior");
  d.mInputMethodWindow=new Window(d);d.steps.clear();
  try{h.update(d,t->true,w->{throw new IllegalStateException("configuration failed");});throw new AssertionError("failure lost");}catch(IllegalStateException expected){}
  check(d.steps.equals(List.of("layering")),"no stale matrix or parent after failed configuration");
  d.steps.clear();h.update(d,t->true,w->d.steps.add("viewport/process"));check(d.steps.size()==5,"failure releases recursion guard");
  h.update(null,t->true,w->{throw new AssertionError("null display");});
  System.out.println("actual IME target handoff checks="+checks+" passed");
 }
}'''
}
paths = []
for name, text in files.items():
    p = out/name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text, encoding='utf-8'); paths.append(str(p))
paths.extend(str(root/'module/src/main/java/com/jiusis/mistep'/n) for n in ['MiStepImeTargetRefresh.java','MiStepImeWindow.java'])
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), *paths], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.ImeTargetRegression'], check=True)
