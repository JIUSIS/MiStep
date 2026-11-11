"""Run actual Host compaction visual leases with an independent TOP pane."""
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
host = (root / 'module/src/main/java/com/jiusis/mistep/MiStepHost.java').read_text(encoding='utf-8-sig')
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)


def member(signature):
    start = host.index(signature)
    brace = host.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        if host[end] == '{':
            depth += 1
        elif host[end] == '}':
            depth -= 1
        end += 1
    return host[start:end]


methods = '\n'.join(member(s) for s in [
    '    private static final class LiveSwapVisualLease',
    '    private static final class CellMovePlan',
    '    private static int compactionHintMask(',
    '    private void animateCellMoves(',
]).replace('MiStepHost::log', 'CompactionChromeRegression::log')
body = r'''
package com.jiusis.mistep;
import java.util.*;
import java.util.function.*;
public class CompactionChromeRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class View {
  static final int GONE=8;float alpha=1;int visibility;
  float getAlpha(){return alpha;}void setAlpha(float v){alpha=v;}
  int getVisibility(){return visibility;}void invalidate(){}
 }
 static class FrameLayout extends View {}
 static class Rect {int left,top;Rect(int l,int t){left=l;top=t;}}
 static class Slot {
  int index,taskId;long taskEpoch;Object taskToken=new Object(),controller=new Object();
  View view=new View(),hint=new View();int[]rect={0,100,100,200};boolean occupied;
  Slot(int i){index=i;taskId=20+i;}
 }
 static class MiStepLayout {}
 static class XposedHelpers {static Object callMethod(Object o,String n,Object...args){return true;}}
 static class MiStepLiveBackdrop {
  static boolean fail;static MiStepLiveBackdrop latest;int closes;
  static MiStepLiveBackdrop create(Object s,Object b,int w,int h,Rect a,Rect z,Consumer<Object> draw,Consumer<String> log){
   if(fail)throw new IllegalStateException("backdrop");
   return latest=new MiStepLiveBackdrop();
  }
  void closeAfterFrame(View v){closes++;}
 }
 static class MiStepCellMotion {
  record Move(View view,int x,int y,int toX,int toY,BooleanSupplier current){}
  Runnable commit,release;boolean started;
  MiStepCellMotion(FrameLayout root,List<Move> moves,Runnable c,Runnable r,Consumer<String> log){commit=c;release=r;}
  void start(){started=true;}
  void finish(){commit.run();release.run();}
 }
 static final int SLOT_MAIN=0,SLOT_COUNT=6;int CELL_SLOT_COUNT=4;
 FrameLayout root=new FrameLayout();long sceneEpoch=7;Slot[]slots=new Slot[6];
 View[]sideBackingViews=new View[6];MiStepLayout layout=new MiStepLayout();
 Object wallpaperBackdrop=new Object();int screenW=1080,screenH=2460;
 MiStepCellMotion cellMotion;boolean transferBusy,compactPending;int layouts;
 CompactionChromeRegression(){for(int i=0;i<6;i++){slots[i]=new Slot(i);sideBackingViews[i]=new View();}}
 Object getSurfaceField(Object c,String n){return c;}
 boolean canRender(Slot s){return true;}
 boolean cellMoveCurrent(Slot s,FrameLayout r,long e,long b,int id,Object t,Object c,View v,Object surface){return true;}
 Object liveHostRootSurface(){return new Object();}
 Rect liveSideStrip(MiStepLayout l){return new Rect(0,100);}
 void drawLiveEmptyHints(Object c,MiStepLayout a,MiStepLayout b,int mask){}
 void finishCellLayout(){layouts++;}
 void updateCellSurfaceVisibility(Slot s){}
 void compactCells(){compactPending=false;}
 void finishPendingSuspend(){}
 static void log(String message){}
 __METHODS__
 void checkRange(int cells,boolean occupiedTop){
  CELL_SLOT_COUNT=cells;slots[5].occupied=occupiedTop;slots[1].occupied=true;
  sideBackingViews[5].alpha=.73f;slots[5].hint.alpha=.61f;
  sideBackingViews[4].alpha=.42f;slots[4].hint.alpha=.32f;
  animateCellMoves(List.of(new CellMovePlan(slots[1],new Rect(0,300))),1<<1);
  check(cellMotion!=null&&cellMotion.started&&transferBusy,"ordinary compaction started");
  check(sideBackingViews[5].alpha==.73f&&slots[5].hint.alpha==.61f,
    "ordinary compaction must never hide independent TOP wallpaper or empty hint");
  if(cells==4)check(sideBackingViews[4].alpha==.42f&&slots[4].hint.alpha==.32f,
    "inactive fourth cell is outside phone compaction");
  for(int i=1;i<cells;i++)check(sideBackingViews[i].alpha==0&&slots[i].hint.alpha==0,
    "ordinary strip has one temporary wallpaper/hint owner");
  LiveSwapVisualLease.writeAlpha(slots[1].hint,.35f);
  MiStepCellMotion running=cellMotion;running.finish();
  check(cellMotion==null&&!transferBusy&&layouts==1,"commit and release settle once");
  check(slots[1].hint.alpha==.35f,"restoration keeps the latest workspace fade");
  check(sideBackingViews[5].alpha==.73f&&slots[5].hint.alpha==.61f,"TOP remains unchanged after commit");
  check(MiStepLiveBackdrop.latest.closes==2,"temporary wallpaper retirement is requested at both lifecycle gates");
 }
 public static void main(String[] args){
  for(int cells:new int[]{4,5})for(boolean top:new boolean[]{false,true})
   new CompactionChromeRegression().checkRange(cells,top);
  var h=new CompactionChromeRegression();MiStepLiveBackdrop.fail=true;
  h.animateCellMoves(List.of(new CellMovePlan(h.slots[1],new Rect(0,300))),0);
  check(h.cellMotion==null&&!h.transferBusy&&h.layouts==1,"failed backdrop retains live owner layout");
  check(h.sideBackingViews[5].alpha==1&&h.slots[5].hint.alpha==1,"failure leaves TOP untouched");
  System.out.println("actual compaction chrome checks="+checks+" passed");
 }
}
'''.replace('__METHODS__', methods)
source = out / 'CompactionChromeRegression.java'
source.write_text(body, encoding='utf-8')
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), str(source)], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'com.jiusis.mistep.CompactionChromeRegression'], check=True)
