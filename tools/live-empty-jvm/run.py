import os
"""Run the actual Host empty-cell painter at both layout orientations and buffer scales."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
source = (src / 'MiStepHost.java').read_text(encoding='utf-8-sig')
def extract(name):
    start = source.index('    private void ' + name + '(')
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end]

method = extract('drawLiveEmptyHints')
canvas = r'''package android.graphics;
import java.util.*;
public class Canvas {
 public double x,y,sx=1,sy=1;public int clips,colors,lastColor,emptyPaints,plusLines,emptyTint;
 public ArrayList<double[]> stack=new ArrayList<>(),draws=new ArrayList<>();
 public int save(){int n=stack.size();stack.add(new double[]{x,y,sx,sy});return n;}
 public void restoreToCount(int n){while(stack.size()>n){double[] a=stack.remove(stack.size()-1);x=a[0];y=a[1];sx=a[2];sy=a[3];}}
 public void scale(float a,float b){sx*=a;sy*=b;}
 public void translate(float a,float b){x+=a*sx;y+=b*sy;}
 public void drawColor(int color){colors++;lastColor=color;}
 public void drawLine(float a,float b,float c,float d,Paint paint){plusLines++;}
 public boolean clipRect(int a,int b,int c,int d){if(c<=0||d<=0)throw new AssertionError("invalid empty crop");clips++;return true;}
}'''
body = r'''package com.jiusis.mistep;
import android.graphics.Canvas;
public class EmptyHostRegression {
 static class LiveSwapVisualLease {static void writeAlpha(View v,float a){v.setAlpha(a);}}
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static final int SLOT_COUNT=4;
 static class WallpaperCropDrawable extends android.graphics.drawable.Drawable {int x=-1,y=-1,tint;WallpaperCropDrawable(){}WallpaperCropDrawable(Object bitmap,int w,int h,int a,int b,int color){x=a;y=b;tint=color;}void setBounds(int a,int b,int c,int d){}void draw(Canvas c){c.emptyPaints++;c.emptyTint=tint;}void withScreenOrigin(int a,int b,Runnable draw){int ox=x,oy=y;try{x=a;y=b;draw.run();}finally{x=ox;y=oy;}}}
 static class HyperOsStyle {static int tint=0x1fffffff;static int topWallpaperTint(Object ctx){return tint;}static int textSecondary(Object ctx){return 0xff777777;}static float dp(Object ctx,float value){return value;}}
 Object ctx=new Object();
 static class View {static final int VISIBLE=0;int visibility,draws;float sx=1,sy=1,alpha=1;WallpaperCropDrawable background=new WallpaperCropDrawable();int getVisibility(){return visibility;}void setScaleX(float v){sx=v;}void setScaleY(float v){sy=v;}void setAlpha(float v){alpha=v;}android.graphics.drawable.Drawable getBackground(){return background;}void draw(Canvas c){draws++;c.draws.add(new double[]{c.x,c.y,c.sx,c.sy,background.x,background.y});}}
 static class Slot {boolean occupied;View hint=new View();}
 static class Bitmap {int w,h;Bitmap(int a,int b){w=a;h=b;}int getWidth(){return w;}int getHeight(){return h;}}
 Slot[] slots=new Slot[SLOT_COUNT];Bitmap wallpaperBackdrop;
 EmptyHostRegression(int w,int h){wallpaperBackdrop=new Bitmap(w,h);for(int i=1;i<SLOT_COUNT;i++)slots[i]=new Slot();slots[1].occupied=true;}
 __METHOD__
 __HIGHLIGHT__
 __PLUS__
 void run(boolean right){
  var a=MiStepLayout.of(1200,2608,right,2.75f);var b=MiStepLayout.of(1200,2608,!right,2.75f);Canvas c=new Canvas();
  drawLiveEmptyHints(c,a,b,0);
  check(slots[1].hint.draws==0,"occupied task content is never drawn into wallpaper");
  check(slots[2].hint.draws==2&&slots[3].hint.draws==2,"both empty controls exist at original and target columns");
  check(c.draws.size()==4&&c.clips==4,"each empty control has a bounded crop");
  check(c.colors==1&&c.lastColor==HyperOsStyle.tint,"temporary wallpaper gaps use the resting tint exactly once");
  for(int i=0;i<4;i++){
   int slot=2+i/2;int[] r=(i%2==0?a:b).cellBounds(slot-1);double[] d=c.draws.get(i);
   check(Math.abs(d[0]/d[2]-r[0])<.001&&Math.abs(d[1]/d[3]-r[1])<.001,"wallpaper buffer scale preserves exact Host empty positions");
   check(d[4]==r[0]&&d[5]==r[1],"each empty hint samples wallpaper at its actual screen destination");
  }
  check(c.stack.isEmpty()&&c.x==0&&c.y==0&&c.sx==1&&c.sy==1,"canvas state always restored");
  check(slots[2].hint.background.x==-1&&slots[3].hint.background.y==-1,"live View backgrounds retain their original origins");
  slots[2].hint.visibility=8;slots[3].occupied=true;c=new Canvas();drawLiveEmptyHints(c,a,b,0);
  check(c.draws.isEmpty(),"hidden hints and newly occupied cells never enter background");
 }
 void reveal(boolean right){
  var geometry=MiStepLayout.of(1200,2608,right,2.75f);
  for(int selected=1;selected<SLOT_COUNT;selected++){
   for(int i=1;i<SLOT_COUNT;i++){slots[i].occupied=i==selected;slots[i].hint.visibility=i==selected?8:0;slots[i].hint.draws=0;}
   Canvas c=new Canvas();drawLiveEmptyHints(c,geometry,geometry,1<<selected);
   check(c.colors==1&&c.lastColor==HyperOsStyle.tint,"collect/Home/compaction backdrop retains resting theme tint");
   check(c.draws.size()==2&&c.emptyPaints==1&&c.plusLines==2&&c.clips==3,"same-column transitions retain all three empty controls without duplicate painting");
   check(c.emptyTint==HyperOsStyle.tint,"revealed cell uses the actual wallpaper tint instead of its stale occupied background");
   for(int i=1;i<SLOT_COUNT;i++)check(slots[i].hint.draws==(i==selected?0:1),"occupied hint or app content never leaks into the empty-cell painter");
   check(slots[selected].hint.visibility==8&&slots[selected].occupied,"painting an empty affordance never changes real task occupancy or live visibility");
   check(c.stack.isEmpty()&&slots[selected].hint.background.x==-1,"temporary painting does not retain transforms or wallpaper origins");
   setDropHighlight(slots[selected],true);setDropHighlight(slots[selected],false);
   check(slots[selected].hint.alpha==1&&slots[selected].hint.sx==1&&slots[selected].hint.sy==1,"entering and leaving a drop target never pre-dims or enlarges a cell");
  }
  setDropHighlight(null,true);
 }
 public static void main(String[]args){for(int tint:new int[]{0x1fffffff,0x26000000}){HyperOsStyle.tint=tint;for(boolean right:new boolean[]{false,true})for(int[] wh:new int[][]{{1200,2608},{360,782}}){new EmptyHostRegression(wh[0],wh[1]).run(right);new EmptyHostRegression(wh[0],wh[1]).reveal(right);}}System.out.println("actual Host empty controls checks="+checks+" passed");}
}'''.replace('__METHOD__', method).replace('__HIGHLIGHT__', extract('setDropHighlight')).replace('__PLUS__', extract('drawEmptyCellPlus'))
cpath = out / 'android/graphics/Canvas.java'
cpath.parent.mkdir(parents=True, exist_ok=True)
cpath.write_text(canvas, encoding='utf8')
ppath = out / 'android/graphics/Paint.java'
ppath.write_text('package android.graphics; public class Paint {public static final int ANTI_ALIAS_FLAG=1;public enum Cap {ROUND}public Paint(int flags){}public void setColor(int color){}public void setStrokeWidth(float width){}public void setStrokeCap(Cap cap){}}', encoding='utf8')
dpath = out / 'android/graphics/drawable/Drawable.java'
dpath.parent.mkdir(parents=True, exist_ok=True)
dpath.write_text('package android.graphics.drawable; public class Drawable {}', encoding='utf8')
hpath = out / 'EmptyHostRegression.java'
hpath.write_text(body, encoding='utf8')
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                str(src/'MiStepLayout.java'), str(cpath), str(ppath), str(dpath), str(hpath)], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.EmptyHostRegression'], check=True)
