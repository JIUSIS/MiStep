"""Replay the production compositor-coordinate lease against a borrowed transaction."""
from pathlib import Path
import os, subprocess, sys
root=Path(__file__).resolve().parents[2];out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/view/SurfaceControl.java':r"""package android.view;
public class SurfaceControl {
 public boolean valid=true;public static int built;public String name;public boolean hidden,container;
 public boolean isValid(){return valid;}
 public static class Builder{
  SurfaceControl s=new SurfaceControl();
  public Builder setName(String v){s.name=v;return this;}
  public Builder setContainerLayer(){s.container=true;return this;}
  public Builder setHidden(boolean v){s.hidden=v;return this;}
  public SurfaceControl build(){built++;return s;}
 }
}""",
'de/robv/android/xposed/XposedHelpers.java':r"""package de.robv.android.xposed;
public class XposedHelpers {
 public static Object newInstance(Class<?> c,Object...args){try{return c.getConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String name,Object...args){
  for(var m:o.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length)
   try{return m.invoke(o,args);}catch(Exception e){throw new RuntimeException(e);}
  throw new IllegalArgumentException(name);
 }
}""",
'com/jiusis/mistep/PassBlurCoordinatesRegression.java':r"""package com.jiusis.mistep;
import android.view.SurfaceControl;
public class PassBlurCoordinatesRegression {
 static int checks;static void ok(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 public static class Display{int id;Display(int id){this.id=id;}public int getDisplayId(){return id;}}
 public static class Tx {
  SurfaceControl surface;float scale,x,y;int matrix,position;
  public void setMatrix(SurfaceControl s,float a,float b,float c,float d){surface=s;scale=a;matrix++;ok(a==d&&b==0&&c==0,"uniform coordinates");}
  public void setPosition(SurfaceControl s,float a,float b){ok(s==surface,"same owned layer");x=a;y=b;position++;}
  // No apply/close/reparent/show methods: production must borrow this transaction.
 }
 public static void main(String[]args){
  var lease=new MiStepPassBlurCoordinates();var d=new Display(0);var tx=new Tx();
  ok(!lease.stage(d,tx,1,0,0)&&SurfaceControl.built==0,"inactive does not allocate");
  ok(!lease.stage(new Display(2),tx,.747f,0,622),"other displays excluded");
  ok(!lease.stage(null,tx,.747f,0,622),"missing owner");
  ok(!lease.stage(d,null,.747f,0,622),"missing transaction");
  for(float v:new float[]{Float.NaN,Float.POSITIVE_INFINITY,0,-1,1.1f})ok(!lease.stage(d,tx,v,0,622),"invalid scale rejected");
  ok(!lease.stage(d,tx,.747f,Float.NaN,622),"invalid x rejected");
  ok(!lease.stage(d,tx,.747f,0,Float.NaN),"invalid y rejected");
  ok(lease.stage(d,tx,.747f,0,622),"workspace coordinates staged");
  var first=tx.surface;ok(first.hidden&&first.container&&first.name.equals("MiStep/FullscreenMagnification:0/AppZoomOut:0"),"native recognition on hidden empty owner");
  ok(tx.scale==.747f&&tx.x==0&&tx.y==622,"actual tuple");
  var next=new Tx();ok(lease.stage(d,next,.7f,273,606),"updated side and geometry");
  ok(next.surface==first&&SurfaceControl.built==1&&next.x==273,"one owned layer reused");
  ok(!lease.stage(new Display(0),new Tx(),.7f,0,606),"different display identity refused");
  ok(!lease.stage(d,next,1,0,1),"malformed reset refused");
  var reset=new Tx();ok(lease.stage(d,reset,1,0,0),"exit staged in caller transaction");
  ok(reset.surface==first&&reset.scale==1&&reset.x==0&&reset.y==0&&first.valid,"reset retained until native commit");
  first.valid=false;ok(!lease.stage(d,new Tx(),.7f,0,606),"invalid handle cannot publish");
  System.out.println("production passblur coordinate checks="+checks+" passed");
 }
}"""}
for n,text in files.items():
 p=out/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8')
production=root/'module/src/main/java/com/jiusis/mistep/MiStepPassBlurCoordinates.java'
jdk=Path(os.environ['MISTEP_JAVA_BIN']);java=[str(out/n) for n in files]+[str(production)]
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*java],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.PassBlurCoordinatesRegression'],check=True)
