import os
"""Exercise actual wallpaper sampling during an offscreen empty-control render."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
files = {
'android/graphics/Bitmap.java': 'package android.graphics; public class Bitmap { public int getWidth(){return 360;} public int getHeight(){return 782;} public boolean isRecycled(){return false;} }',
'android/graphics/ColorFilter.java': 'package android.graphics; public class ColorFilter {}',
'android/graphics/Paint.java': 'package android.graphics; public class Paint {public int color,alpha=255;public Paint(){}public Paint(int f){}public void setColor(int c){color=c;}public void setAlpha(int a){alpha=a;}public void setColorFilter(ColorFilter f){} }',
'android/graphics/Rect.java': 'package android.graphics; public class Rect { public int left,top,right,bottom;public Rect(){}public Rect(int a,int b,int c,int d){set(a,b,c,d);}public void set(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}public int width(){return right-left;}public int height(){return bottom-top;}public boolean isEmpty(){return width()<=0||height()<=0;} }',
'android/graphics/RectF.java': 'package android.graphics; public class RectF {public RectF(Rect r){} }',
'android/graphics/Canvas.java': 'package android.graphics; public class Canvas {public Rect source;public int tint,alpha;public void drawBitmap(Bitmap b,Rect r,RectF d,Paint p){source=new Rect(r.left,r.top,r.right,r.bottom);alpha=p.alpha;}public void drawRect(Rect r,Paint p){tint=p.color;} }',
'android/graphics/drawable/Drawable.java': 'package android.graphics.drawable; import android.graphics.*;public abstract class Drawable {private Rect bounds=new Rect();public int invalidations;public Rect getBounds(){return bounds;}public void setBounds(int a,int b,int c,int d){bounds.set(a,b,c,d);}public void invalidateSelf(){invalidations++;}public abstract void draw(Canvas c);public abstract void setAlpha(int a);public abstract void setColorFilter(ColorFilter f);public abstract int getOpacity(); }',
'WallpaperCropRegression.java': r'''package com.jiusis.mistep;
import android.graphics.*;
public class WallpaperCropRegression {
 static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static void at(WallpaperCropDrawable d,int x,int y,Runnable draw){
  try {d.getClass().getDeclaredMethod("withScreenOrigin",int.class,int.class,Runnable.class).invoke(d,x,y,draw);}
  catch(NoSuchMethodException e){draw.run();} // 87 draws the view with its unchanged old source.
  catch(java.lang.reflect.InvocationTargetException e){throw (RuntimeException)e.getCause();}
  catch(ReflectiveOperationException e){throw new AssertionError("offscreen wallpaper needs scoped screen origin",e);}
 }
 static void equal(Canvas a,Canvas b,String why){check(a.source.left==b.source.left&&a.source.top==b.source.top&&a.source.right==b.source.right&&a.source.bottom==b.source.bottom&&a.tint==b.tint&&a.alpha==b.alpha,why);}
 public static void main(String[]args){
  for(boolean right:new boolean[]{false,true})for(int slot=1;slot<3;slot++)for(int tint:new int[]{0x1fffffff,0x26000000}){
   var old=MiStepLayout.of(1200,2608,right,2.75f);var next=MiStepLayout.of(1200,2608,!right,2.75f);
   int[] a=old.cellBounds(slot),b=next.cellBounds(slot);Bitmap bitmap=new Bitmap();
   var d=new WallpaperCropDrawable(bitmap,1200,2608,a[0],a[1],tint);d.setBounds(0,0,a[2]-a[0],a[3]-a[1]);
   Canvas before=new Canvas();d.draw(before);
   Canvas transientFrame=new Canvas();at(d,b[0],b[1],()->d.draw(transientFrame));
   var steady=new WallpaperCropDrawable(bitmap,1200,2608,b[0],b[1],tint);steady.setBounds(0,0,b[2]-b[0],b[3]-b[1]);Canvas expected=new Canvas();steady.draw(expected);
   equal(transientFrame,expected,"target temporary frame samples exactly the target steady wallpaper/tint");
   Canvas after=new Canvas();d.draw(after);equal(before,after,"offscreen draw restores live hint source");
   check(before.source.left!=transientFrame.source.left,"the old column must not be copied to the target");
   try {at(d,b[0],b[1],()->{throw new IllegalStateException("draw failed");});throw new AssertionError("exception lost");}catch(IllegalStateException expectedError){}
   Canvas failed=new Canvas();d.draw(failed);equal(before,failed,"exception also restores live hint source");
   at(d,b[0],b[1],()->{at(d,a[0],a[1],()->{});Canvas nested=new Canvas();d.draw(nested);equal(expected,nested,"nested rendering restores its enclosing source");});
   check(d.invalidations==0,"offscreen origin changes must not schedule live Host frames");
  }
  System.out.println("actual wallpaper sampling checks="+checks+" passed");
 }
}'''
}
paths = []
for name, text in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf8')
    paths.append(str(path))
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                str(src/'WallpaperCropDrawable.java'), str(src/'MiStepLayout.java'), *paths], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.WallpaperCropRegression'], check=True)
