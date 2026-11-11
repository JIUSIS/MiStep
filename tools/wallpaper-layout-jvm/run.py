"""Run actual Host wallpaper refresh across display capacity changes."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jiusis/mistep'
source = (src / 'MiStepHost.java').read_text(encoding='utf-8-sig')
start = source.index('    private void applyWallpaperBackdrops() {')
brace = source.index('{', start)
depth, end = 1, brace + 1
while depth:
    depth += (source[end] == '{') - (source[end] == '}')
    end += 1
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
body = r'''
package com.jiusis.mistep;
public class WallpaperLayoutRegression {
 static int checks;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static class Bitmap{boolean isRecycled(){return false;}}
 static class View{
  static final int IMPORTANT_FOR_ACCESSIBILITY_NO=2;
  Object background,params;float alpha;
  View(){}View(Object ctx){}
  void setBackground(Object b){background=b;}
  void setImportantForAccessibility(int n){}
  void setLayoutParams(Object p){params=p;}
  void setAlpha(float a){alpha=a;}
 }
 static class FrameLayout extends View{
  void addView(View v){}void addView(View v,int at){}void removeView(View v){}
  static class LayoutParams{int leftMargin,topMargin;LayoutParams(int w,int h){}}
 }
 static class WallpaperCropDrawable{
  int width,height,x,y,tint;
  WallpaperCropDrawable(Bitmap b,int w,int h,int x,int y,int t){width=w;height=h;this.x=x;this.y=y;tint=t;}
 }
 static class HyperOsStyle{
  static Object topBackground(Object c){return new Object();}
  static int topWallpaperTint(Object c){return 0x1fffffff;}
 }
 static class Bar extends View{void setWallpaperBackdrop(Bitmap b,int w,int h){}}
 static class Slot{boolean occupied;View hint=new View();int[]rect;}
 static class Geometry{int left,top;Geometry(int[]b){left=b[0];top=b[1];}}
 static final int SLOT_TOP=5;
 int screenW,screenH,CELL_SLOT_COUNT;
 Object ctx=new Object();FrameLayout root=new FrameLayout();MiStepLayout layout;
 View mainGapBacking,topStripBacking=new View();View[]sideBackingViews=new View[6];
 Slot[]slots=new Slot[6];Bar topBar=new Bar();Bitmap wallpaperBackdrop=new Bitmap();
 void geometry(int w,int h){
  screenW=w;screenH=h;layout=MiStepLayout.of(w,h,false,2.5f,true);CELL_SLOT_COUNT=1+layout.cellCount;
  for(int i=1;i<CELL_SLOT_COUNT;i++)slots[i].rect=layout.cellBounds(i-1);
 }
 WallpaperLayoutRegression(){for(int i=1;i<6;i++)slots[i]=new Slot();}
 void updateWorkspaceBackdropVisibility(){}
 Geometry topGeometry(){return new Geometry(layout.interactiveBounds());}
 int sideSegmentTop(int i){return layout.tabletLandscape?layout.cellBounds(i-1)[1]:i<=1?layout.topStrip:layout.cellBounds(i-2)[3];}
 __METHOD__
 static void crop(View view,int w,int h,int[]rect,String why){
  check(view.background instanceof WallpaperCropDrawable,why+" has wallpaper");
  WallpaperCropDrawable b=(WallpaperCropDrawable)view.background;
  check(b.width==w&&b.height==h&&b.x==rect[0]&&b.y==rect[1],why+" uses current display coordinates");
 }
 public static void main(String[]args){
  WallpaperLayoutRegression h=new WallpaperLayoutRegression();h.geometry(2032,3048);
  for(int i=1;i<4;i++)h.sideBackingViews[i]=new View();
  h.slots[4].hint.background=new WallpaperCropDrawable(h.wallpaperBackdrop,2032,3048,0,0,0);
  h.geometry(3048,2032);h.applyWallpaperBackdrops();
  crop(h.slots[4].hint,3048,2032,h.slots[4].rect,"fourth empty slot without a backing after rotation");
  check(h.sideBackingViews[4]==null,"refresh supports independently created hints");
  for(int i=1;i<5;i++)crop(h.slots[i].hint,3048,2032,h.slots[i].rect,"landscape slot "+i);
  for(int i=1;i<4;i++)crop(h.sideBackingViews[i],3048,2032,h.layout.cellBounds(i-1),"existing backing "+i);
  Object original=h.slots[2].hint.background;h.slots[2].occupied=true;
  h.sideBackingViews[1]=null;h.geometry(2032,3048);h.applyWallpaperBackdrops();
  crop(h.slots[1].hint,2032,3048,h.slots[1].rect,"portrait empty hint without backing");
  check(h.slots[2].hint.background==original,"occupied hint remains owned by its task path");
  h.geometry(3048,2032);h.slots[2].occupied=false;h.slots[3].hint=null;h.applyWallpaperBackdrops();
  crop(h.slots[2].hint,3048,2032,h.slots[2].rect,"retired task refreshed while peer hint absent");
  original=h.slots[4].hint.background;h.wallpaperBackdrop=null;h.applyWallpaperBackdrops();
  check(h.slots[4].hint.background==original,"unavailable wallpaper preserves existing drawing");
  System.out.println("actual Host wallpaper layout checks="+checks+" passed");
 }
}
'''.replace('__METHOD__', source[start:end])
java = out / 'WallpaperLayoutRegression.java'
java.write_text(body, encoding='utf-8')
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                str(src/'MiStepLayout.java'), str(java)], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.WallpaperLayoutRegression'], check=True)
