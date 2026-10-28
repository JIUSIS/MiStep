import os
"""Run the actual Host-only layout commit without issuing a WMS zoom write."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
source = (root / 'module/src/main/java/com/jiusis/mistep/MiStepHost.java').read_text(encoding='utf-8-sig')
out = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'build/tests/standalone'
out.mkdir(parents=True, exist_ok=True)


def member(signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[start:end]


body = r'''
package com.jiusis.mistep;
public class LiveLayoutRegression {
 static int checks;
 static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
 static final int SLOT_COUNT=4;
 static class MiStepStatusBarSampling{static int refreshes;static void geometryChanged(){refreshes++;}}
 static class View {
  Object params=new FrameLayout.LayoutParams();int layouts,invalidates;
  Object getLayoutParams(){return params;}void setLayoutParams(Object value){params=value;}
  void requestLayout(){layouts++;}void invalidate(){invalidates++;}
 }
 static class FrameLayout extends View {
  static class LayoutParams {int leftMargin,topMargin,width,height;}
 }
 static class Bar {boolean right;int changes;void setMainOnRight(boolean value){right=value;changes++;}}
 static class Slot {int[] rect;Object token=new Object(),controller=new Object();int applied,touch;}
 final Slot[] slots=new Slot[SLOT_COUNT];final View[] sideBackingViews=new View[SLOT_COUNT];
 int screenW=1200,screenH=2608,wallpapers,zoomWrites;boolean mainOnRight;
 MiStepLayout layout=MiStepLayout.of(screenW,screenH,false,2.75f);FrameLayout root=new FrameLayout();Bar topBar=new Bar();
 LiveLayoutRegression(){for(int i=1;i<SLOT_COUNT;i++){slots[i]=new Slot();sideBackingViews[i]=new View();slots[i].rect=layout.cellBounds(i-1);}}
 void log(String value){}void applyWallpaperBackdrops(){wallpapers++;}
 int sideSegmentTop(int index){return layout.cellY[index-1];}
 int sideSegmentHeight(int index){return layout.cellHeight[index-1];}
 void applyRect(Slot slot){slot.applied++;}void updateTouchRegion(Slot slot){slot.touch++;}
 __METHOD__
 void oneDirection(boolean right){
  Object[] tokens=new Object[SLOT_COUNT],controllers=new Object[SLOT_COUNT];
  for(int i=1;i<SLOT_COUNT;i++){tokens[i]=slots[i].token;controllers[i]=slots[i].controller;}
  int samples=MiStepStatusBarSampling.refreshes;
  MiStepLayout target=MiStepLayout.of(screenW,screenH,right,2.75f);applyMainLayout(target);
  check(MiStepStatusBarSampling.refreshes==samples+1,"layout commit retargets native sampling even when logical View bounds stay identical");
  check(layout==target&&mainOnRight==right&&topBar.right==right,"exact target layout/orientation retained");
  check(zoomWrites==0&&root.layouts==1&&root.invalidates==1,"host commit draws once without uncorrelated zoom");
  for(int i=1;i<SLOT_COUNT;i++){
   check(java.util.Arrays.equals(slots[i].rect,target.cellBounds(i-1)),"all peer rectangles follow target column");
   check(slots[i].token==tokens[i]&&slots[i].controller==controllers[i],"task token/controller identities retained");
   check(slots[i].applied==1&&slots[i].touch==1,"each side owner moves/input refreshes once");
   FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)sideBackingViews[i].getLayoutParams();
   check(p.leftMargin==(right?0:target.mainWidth)&&p.topMargin==target.cellY[i-1]
    &&p.width==target.sideWidth&&p.height==target.cellHeight[i-1],"matching background follows target column");
  }
 }
 public static void main(String[]args){
  new LiveLayoutRegression().oneDirection(true);new LiveLayoutRegression().oneDirection(false);
  LiveLayoutRegression h=new LiveLayoutRegression();MiStepLayout old=h.layout;
  try{h.applyMainLayout(MiStepLayout.of(1080,2340,true,2.75f));throw new AssertionError("stale dimensions accepted");}
  catch(IllegalArgumentException expected){check(h.layout==old&&!h.mainOnRight&&h.root.layouts==0,"stale geometry rejected before mutation");}
  try{h.applyMainLayout(null);throw new AssertionError("null target accepted");}
  catch(IllegalArgumentException expected){check(h.layout==old,"null target rejected before mutation");}
  h.root=null;MiStepLayout target=MiStepLayout.of(h.screenW,h.screenH,true,2.75f);h.applyMainLayout(target);
  check(h.layout==target&&h.mainOnRight&&h.zoomWrites==0,"unattached layout preparation has no zoom side effect");
  System.out.println("actual Host layout-only commit checks="+checks+" passed");
 }
}
'''.replace('__METHOD__', member('    private void applyMainLayout('))

java = out / 'LiveLayoutRegression.java'
java.write_text(body, encoding='utf8')
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
               str(root/'module/src/main/java/com/jiusis/mistep/MiStepLayout.java'), str(java)], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.LiveLayoutRegression'], check=True)
