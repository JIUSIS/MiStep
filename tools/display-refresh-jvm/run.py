import os
"""Exercise the actual Host display refresh gate with controlled display samples."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
source = (root / 'module/src/main/java/com/jianguo/mistep/MiStepHost.java').read_text(encoding='utf-8-sig')
out = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'build/tests/standalone'
out.mkdir(parents=True, exist_ok=True)
start = source.index('    private void refreshDisplayGeometry() {')
brace = source.index('{', start)
depth, end = 1, brace + 1
while depth:
    if source[end] == '{':
        depth += 1
    elif source[end] == '}':
        depth -= 1
    end += 1

body = r'''
public class DisplayRefreshRegression {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static class View {
  static final int VISIBLE=0;
  void setLayoutParams(Object p){} void requestLayout(){} void invalidate(){}
  int getVisibility(){return VISIBLE;}
 }
 static class FrameLayout {static class LayoutParams {int leftMargin,topMargin;LayoutParams(int w,int h){}}}
 static class Bar extends View {void setStatusBarInset(int value){}}
 static class Promotion {boolean nativeSwap=true;}
 static class CellMotion {int finishes;void finish(){finishes++;}}
 CellMotion cellMotion;
 static class Slot {int index;int[] rect;boolean carrier,promoting,launching,occupied;View carrierTapView;}
 static class Layout {
  int topStrip,sideWidth,mainWidth,mainDispWidth,mainDispHeight,mainOffsetX,mainOffsetY;
  boolean mainOnRight;int[] cellBounds(int i){return new int[4];}String describe(){return "layout";}
 }
 static class Queue {void postDelayed(Runnable r,long delay){}}
 static class Manager {void updateViewLayout(Object r,Object p){}}
 static class FullscreenRotationPolicy {static int portraitDisplayRotation(){return 0;}}
 static class DeviceProfile {static int statusBarHeightPx(Object c){return 0;}}
 static class ZoomLifecyclePolicy {static boolean shouldReassertGeometry(boolean a,boolean b,boolean c){return a&&b&&c;}}
  static class NativeLandscapePolicy {static boolean shouldRestoreFromDisplay(boolean landscape,boolean suspended,int rotation){return landscape&&suspended&&(rotation==0||rotation==2);}}
  static final int SLOT_COUNT=4,SLOT_MAIN=0,ACTIVE=0,SUSPENDED=4;
 int screenW=1200,screenH=2608,displayRotation=0;
 int sampleW=screenW,sampleH=screenH,sampleRotation=displayRotation,reads,cancels,rebuilds,writes;
  int geometryRefreshDeferrals,scene,carrierSlotIndex=-1,resumes;boolean geometryRefreshAfterLive,landscapeSuspended,transferBusy,mainOnRight,screenOn=true;
 View root=new View(),topStripBacking,carrierBackdrop;Object displayManager=new Object(),swapPreview,cellMovePreview,ctx,lp;
 Bar topBar;Manager wm;Layout layout=new Layout();Queue main=new Queue();
 Slot[] slots={new Slot(),new Slot(),new Slot(),new Slot()};View[] sideBackingViews=new View[SLOT_COUNT];
 Promotion promotion=new Promotion();String cancelReason;
 void readDisplayGeometry(){reads++;screenW=sampleW;screenH=sampleH;displayRotation=sampleRotation;}
 void failPromotion(Promotion p,String reason){cancels++;cancelReason=reason;}
  void resumeSession(){resumes++;}
 void scheduleDisplayGeometryRefresh(){}void log(String value){}
 Layout makeAdaptiveLayout(boolean right){rebuilds++;return new Layout();}
 void applyRect(Slot s){}void updateTouchRegion(Slot s){}void updateHint(Slot s){}
 int sideSegmentTop(int i){return 0;}int sideSegmentHeight(int i){return 0;}
 void applyWallpaperBackdrops(){}void reassertCarrier(){}void writeZoom(boolean value){writes++;}
 boolean executeOnShell(Runnable r){return true;}void applyFullscreenBounds(Slot s){}void reapplyScale(Slot s){}
 __METHOD__
 static DisplayRefreshRegression live(){return new DisplayRefreshRegression();}
 public static void main(String[] args){
  DisplayRefreshRegression same=live();same.refreshDisplayGeometry();
  check(same.reads==1,"sample real geometry before deciding to cancel");
  check(same.cancels==0&&!same.geometryRefreshAfterLive,"same-size display notifications must not cancel a live swap");
  check(same.rebuilds==0&&same.writes==0,"no layout or zoom mutation during live swap");
  check(same.screenW==1200&&same.screenH==2608&&same.displayRotation==0,"current live geometry retained");
  for(int change=0;change<3;change++){
   DisplayRefreshRegression h=live();if(change==0)h.sampleW=1080;else if(change==1)h.sampleH=2400;else h.sampleRotation=1;
   h.refreshDisplayGeometry();check(h.cancels==1&&h.geometryRefreshAfterLive,"real size/rotation change cancels and defers refresh");
   check(h.screenW==1200&&h.screenH==2608&&h.displayRotation==0,"rollback retains original geometry until completion");
   check(h.rebuilds==0&&h.writes==0,"changed display must not mutate live layout or write unrelated zoom");
  }
  DisplayRefreshRegression compact=live();compact.promotion=null;compact.cellMotion=new CellMotion();compact.refreshDisplayGeometry();
  check(compact.reads==1&&compact.cellMotion.finishes==0&&compact.rebuilds==0,"unchanged display notifications must preserve an active real cell motion");
  for(int change=0;change<3;change++){DisplayRefreshRegression motion=live();motion.promotion=null;motion.cellMotion=new CellMotion();if(change==0)motion.sampleW=1080;else if(change==1)motion.sampleH=2400;else motion.sampleRotation=1;motion.refreshDisplayGeometry();check(motion.cellMotion.finishes==1&&motion.rebuilds==0&&motion.screenW==1200&&motion.screenH==2608&&motion.displayRotation==0,"actual geometry change ends motion before rebuilding, retaining old coordinate space");}
  DisplayRefreshRegression normal=live();normal.promotion=null;normal.refreshDisplayGeometry();
  check(normal.reads==1&&normal.cancels==0&&normal.rebuilds==0,"ordinary unchanged geometry remains a no-op");
  DisplayRefreshRegression resize=live();resize.promotion=null;resize.sampleH=2400;resize.refreshDisplayGeometry();
  check(resize.screenH==2400&&resize.rebuilds==1&&resize.writes==1,"real geometry refresh still rebuilds after swap");
  DisplayRefreshRegression detached=live();detached.root=null;detached.refreshDisplayGeometry();
  check(detached.reads==0&&detached.cancels==0,"detached Host does not read or cancel");
   DisplayRefreshRegression restored=live();restored.promotion=null;restored.displayRotation=1;restored.sampleRotation=0;restored.landscapeSuspended=true;restored.scene=SUSPENDED;restored.refreshDisplayGeometry();
   check(restored.resumes==1&&restored.rebuilds==0,"portrait return restores landscape-suspended workspace before rebuilding");
   DisplayRefreshRegression fullscreen=live();fullscreen.promotion=null;fullscreen.displayRotation=0;fullscreen.sampleRotation=1;fullscreen.landscapeSuspended=true;fullscreen.scene=SUSPENDED;fullscreen.refreshDisplayGeometry();
   check(fullscreen.resumes==0&&fullscreen.rebuilds==0,"landscape display does not restore workspace early");
  System.out.println("actual Host display refresh checks="+checks+" passed");
 }
}
'''.replace('__METHOD__', source[start:end])
java = out / 'DisplayRefreshRegression.java'
java.write_text(body, encoding='utf8')
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), str(java)], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'DisplayRefreshRegression'], check=True)
