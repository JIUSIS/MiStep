import os
"""Exercise the actual scoped WindowState crop hook against the recorded 17PM geometry."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
source = (root/'module/src/main/java/com/jianguo/mistep/HostEntry.java').read_text(encoding='utf-8-sig')
start = source.index('    private static boolean cropFixedMainTouchableRegion(')
brace = source.index('{', start)
depth, end = 1, brace+1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
method = source[start:end]
rect = '''package android.graphics;
public class Rect {
 public int left,top,right,bottom;
 public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
 public int width(){return right-left;} public int height(){return bottom-top;}
 public boolean isEmpty(){return width()<=0||height()<=0;}
 public boolean equals(Object o){return o instanceof Rect r&&left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}
}'''
region = '''package android.graphics;
public class Region {
 public enum Op{INTERSECT,DIFFERENCE}
 private final java.awt.geom.Area area;
 public Region(Rect r){area=new java.awt.geom.Area(new java.awt.Rectangle(r.left,r.top,r.width(),r.height()));}
 public boolean op(Rect r,Op op){var a=new java.awt.geom.Area(new java.awt.Rectangle(r.left,r.top,r.width(),r.height()));if(op==Op.INTERSECT)area.intersect(a);else area.subtract(a);return !area.isEmpty();}
 public boolean contains(int x,int y){return area.contains(x,y);}
}'''
body = r'''package com.jianguo.mistep;
import android.graphics.*;
import java.util.*;
public class FixedTouchRegression {
 static int checks;
 static boolean legacy;
 static boolean sZoomed=true,sFullscreenRotationActive=true;
 static Object sDisplayContent,sMiStepFixedRotationApp;
 static int sHeldDisplayRotation;
 static final Map<Object,Integer>sTaskRotations=new HashMap<>();
 static ZoomTarget sZoomTarget;
 static class ZoomTarget{Object display,globalLock=new Object();ZoomTarget(Object d){display=d;}}
 public static class Display{Rect bounds=new Rect(0,0,1200,2608);public Rect getBounds(){return bounds;}}
 public static class Activity{Rect fixed=new Rect(0,0,2608,1200);public Rect getFixedRotationTransformDisplayBounds(){return fixed;}}
 public static class Task{int mode=1;Task root=this;Rect bounds=new Rect(0,0,1200,2608);public int getWindowingMode(){return mode;}public Task getRootTask(){return root;}public Rect getBounds(){return bounds;}}
 public static class Window{Object mActivityRecord;Object display;Task task=new Task();public Object getDisplayContent(){return display;}public Task getTask(){return task;}}
 static class XposedHelpers{
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static Object callMethod(Object o,String n,Object...a){try{return o.getClass().getMethod(n).invoke(o);}catch(Exception e){throw new RuntimeException(e);}}
 }
 static void log(String s){}
 __METHOD__
 static void check(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
 static Window reset(){sZoomed=true;sFullscreenRotationActive=true;sHeldDisplayRotation=0;var d=new Display();sDisplayContent=d;sZoomTarget=new ZoomTarget(d);var a=new Activity();sMiStepFixedRotationApp=a;sTaskRotations.clear();sTaskRotations.put(a,1);var w=new Window();w.display=d;w.mActivityRecord=a;return w;}
 static Region all(){return new Region(new Rect(-100,-100,2800,1400));}
 static boolean apply(Window w,Region r){synchronized(sZoomTarget.globalLock){if(legacy){r.op(w.task.bounds,Region.Op.INTERSECT);return true;}return cropFixedMainTouchableRegion(w,r);}}
 static void refused(Window w,String reason){Region r=all();check(!apply(w,r),reason);check(r.contains(2600,1100),reason+" unchanged");}
 public static void main(String[]args){
  legacy=args.length>0;
  Window w=reset();Region r=all();check(apply(w,r),"recorded main crop admitted");
  check(r.contains(600,600),"recorded left tap receives");check(r.contains(2061,603),"recorded right tap receives");
  check(!r.contains(-5,600)&&!r.contains(2700,600),"root clips in rotated coordinates");
  w=reset();r=all();r.op(new Rect(1500,300,1800,700),Region.Op.DIFFERENCE);apply(w,r);
  check(!r.contains(1600,400)&&r.contains(2200,400),"nonrectangular exclusion preserved");
  w=reset();r=new Region(new Rect(200,100,2400,1100));apply(w,r);check(!r.contains(50,500)&&r.contains(2300,1000),"letterbox not expanded");
  w=reset();sTaskRotations.put(sMiStepFixedRotationApp,3);r=all();check(apply(w,r)&&r.contains(2500,600),"reverse landscape");
  w=reset();sZoomed=false;refused(w,"unzoomed");
  w=reset();sFullscreenRotationActive=false;refused(w,"inactive workspace");
  w=reset();w.display=new Display();refused(w,"other display");
  w=reset();sZoomTarget.display=new Display();refused(w,"stale display target");
  w=reset();w.mActivityRecord=new Activity();refused(w,"unowned token");
  w=reset();sTaskRotations.clear();refused(w,"released token");
  w=reset();sTaskRotations.put(sMiStepFixedRotationApp,0);refused(w,"portrait token");
  w=reset();sTaskRotations.put(sMiStepFixedRotationApp,2);refused(w,"half turn unchanged dimensions");
  w=reset();w.task.mode=5;refused(w,"native freeform");
  w=reset();w.task.mode=6;refused(w,"side task");
  w=reset();w.task.bounds=new Rect(0,0,600,2608);refused(w,"partial root");
  w=reset();w.task.root=new Task();w.task.root.mode=6;refused(w,"organized multiwindow root");
  w=reset();((Activity)w.mActivityRecord).fixed=null;refused(w,"no fixed bounds");
  w=reset();((Activity)w.mActivityRecord).fixed=new Rect(0,0,1200,2608);refused(w,"invalid fixed dimensions");
  w=reset();check(!cropFixedMainTouchableRegion(w,all()),"requires WM lock");
  w=reset();sZoomTarget=null;check(!cropFixedMainTouchableRegion(w,all()),"no target");
  System.out.println("actual fixed-main crop checks="+checks+" passed");
 }
}'''.replace('__METHOD__',method)
files = {'android/graphics/Rect.java':rect,'android/graphics/Region.java':region,'FixedTouchRegression.java':body}
for name,text in files.items():
    path=out/name
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text,encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*[str(out/n)for n in files]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.FixedTouchRegression',*sys.argv[2:]],check=True)
