"""Run actual Host wallpaper refresh across display capacity changes."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jiusis/mistep'
source = (src / 'MiStepHost.java').read_text(encoding='utf-8-sig')


def method(signature):
    start = source.find(signature)
    if start < 0:
        return ''
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
body = r'''
package com.jiusis.mistep;
public class WallpaperLayoutRegression {
 static int checks;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static class Bitmap{boolean isRecycled(){return false;}}
 static class View{
  static final int VISIBLE=0,GONE=8,IMPORTANT_FOR_ACCESSIBILITY_NO=2;
  Object background,params;float alpha;int visibility;
  View(){}View(Object ctx){}
  Object getBackground(){return background;}
  void setBackground(Object b){background=b;}
  void setImportantForAccessibility(int n){}
  void setLayoutParams(Object p){params=p;}
  void setAlpha(float a){alpha=a;}
  void setVisibility(int v){visibility=v;}
  void setContentDescription(String desc){}
 }
 static class TextView extends View{void setText(String s){}void setTextSize(int units,float size){}void setTextColor(int c){}}
 static class FrameLayout extends View{
  void addView(View v){}void addView(View v,int at){}void removeView(View v){}
  static class LayoutParams{int leftMargin,topMargin;LayoutParams(int w,int h){}}
 }
 static class WallpaperCropDrawable{
  Bitmap wallpaper;
  int width,height,x,y,tint;
  WallpaperCropDrawable(Bitmap b,int w,int h,int x,int y,int t){wallpaper=b;width=w;height=h;this.x=x;this.y=y;tint=t;}
  boolean matchesWallpaper(Bitmap b,int w,int h,int t){return wallpaper==b&&width==w&&height==h&&tint==t;}
  void setScreenOrigin(int x,int y){this.x=x;this.y=y;}
  void setMainHole(Object hole){}
 }
 static class HyperOsStyle{
  static Object topBackground(Object c){return new Object();}
  static int topWallpaperTint(Object c){return 0x1fffffff;}
  static int accent(Object c){return 0;}static int textSecondary(Object c){return 0;}
 }
 static class Bar extends View{void setWallpaperBackdrop(Bitmap b,int w,int h){}}
 static class Slot{
  final int index;int sideRow=-1;boolean occupied,parked,sideShown;
  View hint=new TextView();int[]rect;
  Slot(int index){this.index=index;}
 }
 static class LiveSwapVisualLease{static void writeAlpha(View view,float alpha){if(view!=null)view.setAlpha(alpha);}}
 static class TopTaskPane{void occupied(boolean value){}}
 static class Geometry{int left,top;Geometry(int[]b){left=b[0];top=b[1];}}
 static final int SLOT_MAIN=0,SLOT_TOP=5;
 int screenW,screenH,CELL_SLOT_COUNT;
 Object ctx=new Object();FrameLayout root=new FrameLayout();MiStepLayout layout;
 View mainGapBacking,topStripBacking=new View();View[]sideBackingViews=new View[6];
 Slot[]slots=new Slot[8];Bar topBar=new Bar();Bitmap wallpaperBackdrop=new Bitmap();
 TopTaskPane topTaskPane;boolean taskCellsCovered,sideCompacting;float workspaceAlpha=1f;
 void geometry(int w,int h){
  screenW=w;screenH=h;layout=MiStepLayout.of(w,h,false,2.5f,true);CELL_SLOT_COUNT=1+layout.cellCount;
  for(int i=1;i<CELL_SLOT_COUNT;i++)slots[i].rect=layout.cellBounds(i-1);
 }
 WallpaperLayoutRegression(){for(int i=1;i<6;i++)slots[i]=new Slot(i);}
 void updateWorkspaceBackdropVisibility(){}
 Object workspaceMainHole(){return null;}
 void updateCellSurfaceVisibility(Slot slot){}void ensureCellBackground(Slot slot){}
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

  h.geometry(1200,2608);h.wallpaperBackdrop=new Bitmap();
  Slot promoted=new Slot(6);
  h.slots[6]=promoted;
  promoted.sideRow=4;promoted.sideShown=true;promoted.rect=h.layout.cellBounds(2);
  promoted.occupied=true;
  Object fallback=new Object();promoted.hint.setBackground(fallback);
  h.applyWallpaperBackdrops();
  check(promoted.hint.background==fallback,"occupied task retains its hidden hint");
  promoted.occupied=false;
  h.updateHint(promoted);
  check(promoted.hint.visibility==View.VISIBLE,"promoting a task exposes its add hint");
  crop(promoted.hint,1200,2608,promoted.rect,"promoted scrolling task exposes wallpaper in its first empty frame");
  Object visible=promoted.hint.background;
  promoted.rect=h.layout.cellBounds(1);
  h.applyWallpaperBackdrops();
  crop(promoted.hint,1200,2608,promoted.rect,"visible scrolling row beyond the three fixed slots");
  check(promoted.hint.background==visible,"reuse the same drawable as a scrolling row moves");
  h.wallpaperBackdrop=new Bitmap();h.applyWallpaperBackdrops();
  check(((WallpaperCropDrawable)promoted.hint.background).wallpaper==h.wallpaperBackdrop,
    "visible tail hint uses the new wallpaper");
  System.out.println("actual Host wallpaper layout checks="+checks+" passed");
 }
}
'''.replace('__METHOD__', '\n'.join(map(method, (
    '    private void applyWallpaperBackdrops() {',
    '    private void refreshEmptyHintBackdrop(Slot slot) {',
    '    private void updateHint(Slot slot) {',
    '    private boolean presentsSlot(Slot slot) {',
))))
java = out / 'WallpaperLayoutRegression.java'
java.write_text(body, encoding='utf-8')
typed_value = out / 'android/util/TypedValue.java'
typed_value.parent.mkdir(parents=True, exist_ok=True)
typed_value.write_text(
    'package android.util; public class TypedValue {public static final int COMPLEX_UNIT_SP=2;}',
    encoding='utf-8')
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                str(src/'MiStepLayout.java'), str(java), str(typed_value)], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.WallpaperLayoutRegression'], check=True)
