import os
"""Run the actual Host visibility/preparation methods across native ownership handoffs."""
from pathlib import Path
import subprocess,sys
root=Path(__file__).resolve().parents[2]
host=(root/'module/src/main/java/com/jiusis/mistep/MiStepHost.java').read_text(encoding='utf-8-sig')
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
def method(signature):
    a=host.index(signature);b=host.index('{',a)+1;depth=1
    while depth:
        depth+=(host[b]=='{')-(host[b]=='}');b+=1
    return host[a:b]
body=r'''
package com.jiusis.mistep;
import android.view.SurfaceControl;
import de.robv.android.xposed.XposedHelpers;
public class CellVisibilityRegression {
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 float workspaceAlpha=1f;Object cellMotion;Promotion promotion;boolean owns=true;
 static class Promotion{boolean nativeSwap=true,cancelled;}
 static class View{float alpha;void setAlpha(float a){alpha=a;}}
 static class Slot{boolean occupied,parked,promoting,launching;int taskId=42;Object taskToken=new Object();View view=new View();Object controller=new SurfaceControl();}
 Object getSurfaceField(Object c,String f){return c;}
 boolean controllerOwns(Slot s,int id,Object token){return owns;}
 static void closeSurfaceObject(Object o,String m){if(o!=null)XposedHelpers.callMethod(o,m);}
 static void log(String s){throw new AssertionError(s);}
 __METHODS__
 public static void main(String[]args){
  CellVisibilityRegression h=new CellVisibilityRegression();Slot s=new Slot();SurfaceControl surface=(SurfaceControl)s.controller;
  s.view.alpha=1;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==0&&surface.alpha==0&&!surface.shown,"settled empty still hides resize white board");
  SurfaceControl.ops.clear();h.prepareCellSurface(s);
  check(s.view.alpha==1&&surface.alpha==1,"destination hole and parent ready before native handoff");
  check(!surface.shown&&!SurfaceControl.ops.contains("show")&&!SurfaceControl.ops.contains("hide"),"preparation leaves native visibility ownership intact");
  SurfaceControl.ops.clear();s.launching=true;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&SurfaceControl.ops.isEmpty(),"pending launcher keeps preopened destination");
  s.launching=false;s.promoting=true;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&SurfaceControl.ops.isEmpty(),"adoption owns visibility until settled");
  s.promoting=false;h.promotion=new Promotion();h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&SurfaceControl.ops.isEmpty(),"native clear phase cannot hide just committed parent");
  s.occupied=true;h.owns=false;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&SurfaceControl.ops.isEmpty(),"temporarily borrowed peer is not mistaken for removed task");
  h.owns=true;h.promotion.cancelled=true;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==1&&surface.shown&&surface.alpha==1,"final binding reconciles visible task");
  check(!SurfaceControl.ops.contains("hide"),"clear-bind replacement emitted no intermediate hide");
  SurfaceControl.ops.clear();s.occupied=false;h.updateCellSurfaceVisibility(s);
  check(s.view.alpha==0&&!surface.shown,"retired empty destination hidden after native ownership ends");
  h.workspaceAlpha=.45f;h.prepareCellSurface(s);check(surface.alpha==.45f,"preparation retains workspace fade");
  SurfaceControl.ops.clear();surface.valid=false;s.view.alpha=0;h.prepareCellSurface(s);
  check(s.view.alpha==1&&SurfaceControl.ops.isEmpty(),"future surface can open hole without invalid native transaction");
  System.out.println("Actual Host cell visibility checks="+checks+" passed");
 }
}
'''.replace('__METHODS__','\n'.join(method(s) for s in ['    private void updateCellSurfaceVisibility(', '    private void prepareCellSurface(']))
files={
'com/jiusis/mistep/CellVisibilityRegression.java':body,
'android/view/SurfaceControl.java':r'''package android.view;import java.util.*;
public class SurfaceControl {public boolean valid=true,shown;public float alpha;public boolean isValid(){return valid;}public static final List<String> ops=new ArrayList<>();
 public static class Transaction implements AutoCloseable {
  public Transaction setAlpha(SurfaceControl s,float a){s.alpha=a;ops.add("alpha");return this;}
  public Transaction show(SurfaceControl s){s.shown=true;ops.add("show");return this;}
  public Transaction hide(SurfaceControl s){s.shown=false;ops.add("hide");return this;}
  public void apply(){ops.add("apply");}public void close(){}
 }}''',
'de/robv/android/xposed/XposedHelpers.java':r'''package de.robv.android.xposed;public class XposedHelpers{
 public static Object callMethod(Object o,String name,Object...args){try{for(java.lang.reflect.Method m:o.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new NoSuchMethodException(name);}catch(Exception e){throw new RuntimeException(e);}}
}'''}
for p,s in files.items():
    path=out/p;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(s,encoding='utf-8')
for sig,anchor in [('    private void startLiveCollect(','statusBar.runAfterHiddenFrame('),('    private void adoptExisting(Slot slot, LiveTask task, boolean collectFromMain,','if (!executeOnShell('),('    private void startSlot(','PendingIntent pi')]:
    source=method(sig)
    assert 'prepareCellSurface(slot);' in source
    assert source.index('prepareCellSurface(slot);')<source.index(anchor)
assert 'for (Slot cell : slots)' in method('    private void restoreLiveSwapVisual(')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','-d',str(out),*[str(out/p) for p in files]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.CellVisibilityRegression'],check=True)
print('Actual entry/retirement ordering checks=4 passed')
