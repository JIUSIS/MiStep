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

swap = member('    private void startLiveSwap(')
target_start = swap.index('        final MiStepLayout target = ')
target_declaration = swap[target_start:swap.index(';', target_start) + 1]

body = r'''
package com.jiusis.mistep;
public class LiveLayoutRegression {
 static int checks;
 static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
 static final int SLOT_COUNT=4,CELL_SLOT_COUNT=4,ACTIVE=1,RESUMING=2,SUSPENDED=4;
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
 static class Slot {int index;int[] rect;Object token=new Object(),controller=new Object();int applied,touch;}
 final Slot[] slots=new Slot[SLOT_COUNT];final View[] sideBackingViews=new View[SLOT_COUNT];
 int screenW=1200,screenH=2608,wallpapers,zoomWrites,topGeometryChanges,scene=ACTIVE;boolean mainOnRight,workspaceEntering;
 MiStepLayout layout=MiStepLayout.of(screenW,screenH,false,2.75f);FrameLayout root=new FrameLayout();Bar topBar=new Bar();
 LiveLayoutRegression(){for(int i=1;i<SLOT_COUNT;i++){slots[i]=new Slot();slots[i].index=i;sideBackingViews[i]=new View();slots[i].rect=layout.cellBounds(i-1);}}
 void log(String value){}void applyWallpaperBackdrops(){wallpapers++;}
 void writeZoom(boolean on){check(on,"side change requests workspace zoom only");zoomWrites++;}
 void updateTopTaskGeometry(){topGeometryChanges++;}
 MiStepLayout makeAdaptiveLayout(boolean right){return MiStepLayout.of(screenW,screenH,right,2.5f,layout.tabletLandscape);}
 MiStepLayout swapTarget(WorkspaceOptions options){
  MiStepLayout geometry=layout;
  __SWAP_TARGET__
  return target;
 }
 int sideSegmentTop(int index){return layout.cellY[index-1];}
 int sideSegmentHeight(int index){return layout.cellHeight[index-1];}
 void applyRect(Slot slot){slot.applied++;}void updateTouchRegion(Slot slot){slot.touch++;}
 int[] slotBounds(Slot slot){return layout.cellBounds(slot.index-1);}
 __METHOD__
 __SET_MAIN_SIDE__
 __LIVE_RECTS__
 static void tabletSurfaceRects(){
  for(boolean right:new boolean[]{false,true}){
  MiStepLayout l=MiStepLayout.of(3048,2032,right,2.5f,true);
  LiveSwapGeometry.Rect main=liveMainRect(l);
  check(main.left==(right?909:0) && main.top==606 && main.width==2139 && main.height==1426,
    "actual main transition rectangle matches the new workspace/input origin");
  for(int i=1;i<=4;i++){
   int[] panel=l.cellBounds(i-1);LiveSwapGeometry.Rect cell=liveCellRect(l,i);
   check(cell.left==panel[0] && cell.top==panel[1],
     "actual cell transition starts at its panel origin");
   check(Math.abs(cell.width-(panel[2]-panel[0]))<1.5
     &&Math.abs(cell.height-(panel[3]-panel[1]))<1.5,
     "actual cell transition cannot leave the former 147px backing strip");
  }
  }
 }
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
 void tabletDirection(boolean right){
  screenW=3048;screenH=2032;
  MiStepLayout target=MiStepLayout.of(screenW,screenH,right,2.5f,true);
  applyMainLayout(target);
  check(mainOnRight==right&&topBar.right==right,"tablet Host commit preserves the gesture side");
  check(topGeometryChanges==1,"reopening from the opposite corner also repositions the interactive task");
  for(int mode:new int[]{WorkspaceOptions.GROW_IN_PLACE,WorkspaceOptions.FIXED_MAIN})
   check(swapTarget(WorkspaceOptions.DEFAULT.withSwapMode(mode)).mainOnRight==right,
     "actual tablet swap target cannot reset the gesture side");
 }
 public static void main(String[]args){
  tabletSurfaceRects();
  new LiveLayoutRegression().oneDirection(true);new LiveLayoutRegression().oneDirection(false);
  new LiveLayoutRegression().tabletDirection(true);new LiveLayoutRegression().tabletDirection(false);
  for(boolean right:new boolean[]{false,true})for(int mode:new int[]{0,1}){
   LiveLayoutRegression phone=new LiveLayoutRegression();
   phone.layout=MiStepLayout.of(phone.screenW,phone.screenH,right,2.5f);
   check(phone.swapTarget(WorkspaceOptions.DEFAULT.withSwapMode(mode)).mainOnRight
     ==(mode==WorkspaceOptions.FIXED_MAIN?right:!right),"phone swap option behavior remains unchanged");
  }
  LiveLayoutRegression h=new LiveLayoutRegression();MiStepLayout old=h.layout;
  try{h.applyMainLayout(MiStepLayout.of(1080,2340,true,2.75f));throw new AssertionError("stale dimensions accepted");}
  catch(IllegalArgumentException expected){check(h.layout==old&&!h.mainOnRight&&h.root.layouts==0,"stale geometry rejected before mutation");}
  try{h.applyMainLayout(null);throw new AssertionError("null target accepted");}
  catch(IllegalArgumentException expected){check(h.layout==old,"null target rejected before mutation");}
  h.root=null;MiStepLayout target=MiStepLayout.of(h.screenW,h.screenH,true,2.75f);h.applyMainLayout(target);
  check(h.layout==target&&h.mainOnRight&&h.zoomWrites==0,"unattached layout preparation has no zoom side effect");
  for(int scene:new int[]{RESUMING,SUSPENDED}){
   h=new LiveLayoutRegression();h.scene=scene;h.setMainSide(true);
   check(h.mainOnRight&&h.layout.mainOnRight&&h.root.layouts==1,"pending entry still prepares opposite-side Host geometry");
   check(h.zoomWrites==0,"suspended or resuming side change leaves real main zoom to entry frame gate");
  }
  h=new LiveLayoutRegression();h.workspaceEntering=true;h.setMainSide(true);
  check(h.mainOnRight&&h.zoomWrites==0,"an active but pending entry cannot be preempted by a steady zoom write");
  h=new LiveLayoutRegression();h.setMainSide(true);
  check(h.mainOnRight&&h.zoomWrites==1,"settled active side change still updates native main geometry once");
  h.setMainSide(true);check(h.zoomWrites==1,"unchanged side never duplicates a zoom write");
  h=new LiveLayoutRegression();h.root=null;h.setMainSide(true);
  check(h.mainOnRight&&h.zoomWrites==0,"unattached side preparation remains free of native zoom writes");
  System.out.println("actual Host layout-only commit checks="+checks+" passed");
 }
}
'''.replace('__SWAP_TARGET__', target_declaration).replace('__METHOD__', member('    private void applyMainLayout(')).replace('__SET_MAIN_SIDE__', member('    private void setMainSide(')).replace(
    '__LIVE_RECTS__', member('    private static LiveSwapGeometry.Rect liveMainRect(')
    + '\n' + member('    private static LiveSwapGeometry.Rect liveCellRect('))

java = out / 'LiveLayoutRegression.java'
java.write_text(body, encoding='utf8')
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
               str(root/'module/src/main/java/com/jiusis/mistep/MiStepLayout.java'),
               str(root/'module/src/main/java/com/jiusis/mistep/WorkspaceOptions.java'),
               str(root/'module/src/main/java/com/jiusis/mistep/LiveSwapGeometry.java'), str(java)], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.LiveLayoutRegression'], check=True)
