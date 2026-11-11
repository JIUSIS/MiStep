"""Run actual Host lock/unlock methods with retained task and window doubles."""
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = Path(sys.argv[2]) if len(sys.argv) > 2 else root / 'module/src/main/java/com/jiusis/mistep'
source = (src / 'MiStepHost.java').read_text(encoding='utf-8-sig')
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)


def member(signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
        end += 1
    return source[start:end]


methods = '\n'.join(member(signature) for signature in [
    '    public static void onScreenState(', '    public static void onUserPresent(',
    '    public static void onNativeWake(',
    '    public static void onSystemNavigation(', '    private boolean setKeyguardWindowHidden(',
    '    public static void onLandscapeMode(',
    '    private boolean retainWorkspaceForLock(', '    private void leaveKeyguardPrivacy(',
    '    private void showResumingSession(', '    private void resumeNext(',
    '    private void writeZoomLine(String command, long issuedAt)'])
files = {
    'android/animation/ValueAnimator.java': '''package android.animation;public class ValueAnimator {
        public int cancels;public void cancel(){cancels++;}}''',
    'android/util/AtomicFile.java': '''package android.util;import java.io.*;
        public class AtomicFile {File f;public AtomicFile(File f){this.f=f;}
        public FileOutputStream startWrite()throws IOException{return new FileOutputStream(f);}
        public void finishWrite(FileOutputStream s)throws IOException{s.close();}
        public void failWrite(FileOutputStream s){} }''',
    'com/jiusis/mistep/MiStepHost.java': r'''package com.jiusis.mistep;
import java.util.*;
public class MiStepHost {
 static int checks;
 static void check(boolean ok,String m){checks++;if(!ok)throw new AssertionError(m);}
 static class View {static final int VISIBLE=0;int layouts;void requestLayout(){layouts++;}
  Object getWindowToken(){return this;}void setVisibility(int v){}void closeQuickAppPicker(){}void applyOptions(WorkspaceOptions o){}}
 static class WindowManager {
  static class LayoutParams {static final int FLAG_NOT_TOUCHABLE=16;float alpha=.8f;int flags=64;}
  int updates;boolean fail;void updateViewLayout(View root,LayoutParams params){
   if(fail)throw new IllegalStateException("window failure");
   if(sInstance.keyguardWindowHidden&&params.alpha>0&&!sInstance.retainedWindowPrepared)
    check(sInstance.restoredSurfaces&&sInstance.workspaceAlpha==1f&&sInstance.zoomed&&".75 620 0".equals(sInstance.lastCommand),"steady geometry published before retained window reveal");
   updates++;}}
 static class Context {java.io.File files;Context(java.io.File f){files=f;}java.io.File getFilesDir(){return files;}}
 static class Queue {List<Runnable> tasks=new ArrayList<>();void post(Runnable r){tasks.add(r);}
  Object getLooper(){return Looper.MAIN;}
  void postDelayed(Runnable r,long delay){tasks.add(r);}void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}}
 static class Looper {static final Object MAIN=new Object();static Object current=MAIN;static Object myLooper(){return current;}}
 static class MiStepEntryGuard {static boolean unlocked=true;static boolean canOpen(Context c){return unlocked;}}
 static class MiStepUnlockHandoff {boolean active(){return true;}void cancel(){}}
 MiStepUnlockHandoff unlockHandoff;
 static class MiStepRetainedWindow {static void request(Context c,Queue q,Object token,long generation,float s,float x,float y,java.util.function.Consumer<Boolean> ready){ready.accept(false);}}
 boolean retainedWindowPrepared;long retainedWindowGeneration;
 static class MiStepLayout {float mainScale=.75f,mainOffsetX,mainOffsetY=620;}
 MiStepLayout layout=new MiStepLayout();
 static class MiStepClipboard {static MiStepClipboard get(Context c){return new MiStepClipboard();}void close(){}}
 static class MiStepSwapTrace {void close(){}}
 static class NativeLandscapePolicy {static boolean shouldRestoreFromHome(boolean a,boolean b,String c){return false;}}
 static class SystemClock {static long uptimeMillis(){return 100;}}
 static class MiStepNativeRecents {static boolean show(){return false;}}
 static class DeviceProfile {static boolean isTablet(Context c){return false;}}
 static class StatusBar {void restore(){}}
 static class Slot {int taskId;Object token=new Object(),controller=new Object();boolean occupied=true,parked,promoting;}
 static class LiveTask {LiveTask(Slot s){}}
 static final int ACTIVE=0,SUSPENDING=1,SUSPENDED=2,RESUMING=3,SLOT_COUNT=6;
 static MiStepHost sInstance;
 Queue main=new Queue();View root=new View(),topBar=new View();
 WindowManager wm=new WindowManager();WindowManager.LayoutParams lp=new WindowManager.LayoutParams();
 WorkspaceOptions workspaceOptions=WorkspaceOptions.DEFAULT;Context ctx;
 Slot[]slots=new Slot[SLOT_COUNT];StatusBar statusBar=new StatusBar();MiStepSwapTrace swapTrace;
 boolean screenOn=true,keyguardLocked,retainedForLock,keyguardWindowHidden,keyguardWindowNotTouchable;
 boolean restoredSurfaces,traceNextSwap,workspaceEntering,zoomed=true,suspendRequested,landscapeSuspended,mirror=true;
 float keyguardWindowAlpha,workspaceAlpha=1f;int keyguardResumeIndex=-1,scene=ACTIVE,suspends,entries,repairs,reasserts,adoptions,zooms;
 long sceneEpoch=7,workspaceGeneration,zoomAnimUntil;String lastCommand;Runnable entryReady;
 final MiStepNavigationPolicy.EmptyMainGesture emptyMainGesture=new MiStepNavigationPolicy.EmptyMainGesture();
 boolean emptyMainHomeFocused(){return false;} // Native Home focus has its separate production-method suite.
 android.animation.ValueAnimator workspaceAnimator;
 MiStepHost(java.io.File f){ctx=new Context(f);for(int i=0;i<SLOT_COUNT;i++){slots[i]=new Slot();slots[i].taskId=200+i;}sInstance=this;}
 static void log(String s){}String describeTasks(){return "same live tasks";}
 void closeSwapPreview(){}void closeCellMovePreview(){}void publishDesktopSideTasks(){}void hideIme(){}
 void suspendSession(String reason){suspends++;scene=SUSPENDED;}
 void resumeSession(){}void updateTopBarState(){}void reapplyScale(Slot s){repairs++;}
 void scheduleSideVisibilityRepair(Slot s){}void setMirrorLayerVisible(boolean value){mirror=value;}
 void setZoomState(boolean value){zoomed=value;}
 void writeZoom(boolean value){writeZoomLine(value?".75 620 0":"1 0 0",100);}
 void broadcastZoom(String command,long at){lastCommand=command;zooms++;}
 void beginWorkspaceEntry(Runnable ready){entries++;entryReady=ready;}
 void refreshDisplayGeometry(){}void consumePendingMainSide(){}void setWorkspaceAlpha(float value){workspaceAlpha=value;}
 void collapseStatusBarPanels(){}void configureTabletTools(){}void requestCompaction(){}
 void refreshQuickApps(){}void finishPendingSuspend(){}void updateHint(Slot s){}
 void restoreRetainedWorkspaceSurfaces(){
 check(!keyguardLocked&&keyguardWindowHidden&&retainedForLock,"real parents restored only after authentication and before reveal");restoredSurfaces=true;}
 void reassertSideTaskViews(String reason){reasserts++;}
 void adoptExisting(Slot slot,LiveTask saved,Runnable ready,java.util.function.Consumer<String> failed){adoptions++;slot.parked=false;ready.run();}
 __METHODS__
 static MiStepHost fresh(java.io.File f){MiStepEntryGuard.unlocked=true;return new MiStepHost(f);}
 static void off(MiStepHost h){onScreenState(false);h.main.drain();}
 public static void main(String[]args)throws Exception{
  java.io.File f=new java.io.File(args[0]);
  MiStepHost h=fresh(f);off(h);
  check(h.suspends==1&&!h.retainedForLock,"default screen-off follows existing exit");
  h=fresh(f);h.scene=SUSPENDED;h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);off(h);
  check(!h.retainedForLock&&h.entries==0,"locking an exited workspace does not create a session");
  h=fresh(f);h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);
  View root=h.root;Object topToken=h.slots[5].token,topController=h.slots[5].controller;
  long epoch=h.sceneEpoch;
  h.workspaceEntering=true;h.workspaceAnimator=new android.animation.ValueAnimator();
  android.animation.ValueAnimator animator=h.workspaceAnimator;off(h);
  check(h.retainedForLock&&h.keyguardLocked&&h.scene==ACTIVE&&h.suspends==0,"keep retains active lifecycle without handback");
  check(h.root==root&&h.slots[5].token==topToken&&h.slots[5].controller==topController&&h.slots[5].taskId==205,"TOP identity/controller and attached root retained");
  check(h.sceneEpoch==epoch,"retaining does not invalidate in-flight task ownership");
  check(animator.cancels==1&&h.workspaceAnimator==null&&!h.workspaceEntering,"pending first frame and animator cancelled");
  check(h.lp.alpha==0&&(h.lp.flags&16)!=0&&h.keyguardWindowHidden,"entire window hidden and non-touchable without detaching views");
  check(!h.zoomed&&"1 0 0".equals(h.lastCommand)&&!h.mirror,"native lockscreen zoom and mirror hidden");
  h.writeZoomLine("anim .75 620 0 300",101);
  check("1 0 0".equals(h.lastCommand),"late zoom write cannot magnify lockscreen");
  onSystemNavigation("homekey");h.main.drain();check(h.suspends==0,"lockscreen navigation cannot discard retained tasks");
  onLandscapeMode(true);h.main.drain();check(h.suspends==0,"late native landscape event cannot discard retained tasks");
  MiStepEntryGuard.unlocked=false;onScreenState(true);h.main.drain();onUserPresent();h.main.drain();
  check(h.retainedForLock&&h.entries==0&&h.lp.alpha==0,"screen-on and premature user-present keep native locked screen");
  MiStepEntryGuard.unlocked=true;onUserPresent();h.main.drain();
  check(!h.retainedForLock&&!h.keyguardLocked&&!h.keyguardWindowHidden,"actual unlock releases lock gate");
  check(h.lp.alpha==.8f&&h.lp.flags==64,"original window alpha and input flags restored");
  check(h.entries==0&&h.entryReady==null&&h.mirror&&h.slots[5].token==topToken&&h.adoptions==0,"unlock reuses bound tasks without animated entry or readoption");
  check(h.reasserts==1&&h.workspaceAlpha==1f&&h.zoomed&&".75 620 0".equals(h.lastCommand),"unlock restores complete steady workspace directly");
  onUserPresent();h.main.drain();check(h.entries==0&&h.reasserts==1,"duplicate user-present does not replay workspace restoration");
  h.writeZoomLine(".75 620 0",102);check(".75 620 0".equals(h.lastCommand),"unlocked session permits normal zoom");
  h=fresh(f);h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);h.lp.flags|=16;off(h);
  onScreenState(true);h.main.drain();check((h.lp.flags&16)!=0,"restore preserves preexisting non-touchable flag");
  h=fresh(f);h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);h.wm.fail=true;off(h);
  check(!h.retainedForLock&&h.suspends==1&&h.lp.alpha==.8f&&h.lp.flags==64,"failed hiding follows existing cleanup and restores attributes");
  h=fresh(f);h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);h.scene=RESUMING;h.workspaceEntering=true;
  off(h);h.resumeNext(3,h.sceneEpoch);
  check(h.keyguardResumeIndex==3&&h.adoptions==0&&h.suspends==0,"in-flight restore pauses at exact next slot while locked");
  h.slots[3].parked=true;onScreenState(true);h.main.drain();
  check(h.adoptions==1&&h.scene==ACTIVE&&h.suspends==0&&h.entries==0,"unlock continues paused readoption once without entry animation");
  h=fresh(f);h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);h.scene=RESUMING;
  off(h);h.showResumingSession(h.sceneEpoch);check(h.retainedForLock&&h.keyguardResumeIndex==1&&h.entries==0,"late foreground read cannot discard locked restore");
  h=fresh(f);h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);off(h);
  int updates=h.wm.updates;off(h);check(h.wm.updates==updates&&h.retainedForLock,"duplicate screen-off preserves saved window state");
  h.workspaceAlpha=.2f;h.zoomAnimUntil=900;onScreenState(true);h.main.drain();
  check(h.workspaceAlpha==1f&&h.zoomAnimUntil==0&&h.entries==0,"interrupted entry restores final alpha and clears animation guard");
  off(h);h.wm.fail=true;onScreenState(true);h.main.drain();
  check(h.retainedForLock&&h.keyguardLocked&&!h.zoomed&&"1 0 0".equals(h.lastCommand),"failed reveal keeps privacy gate and native zoom for retry");
  h.wm.fail=false;onUserPresent();h.main.drain();
  check(!h.retainedForLock&&!h.keyguardLocked&&h.zoomed&&h.entries==0,"later unlock retries steady restoration without animation");
  h=fresh(f);h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);off(h);
  MiStepEntryGuard.unlocked=false;onUserPresent();h.main.drain();
  check(!h.screenOn&&h.retainedForLock&&h.keyguardLocked&&h.lp.alpha==0&&!h.zoomed,"late user-present cannot reveal a non-interactive or locked screen");
  MiStepEntryGuard.unlocked=true;onUserPresent();h.main.drain();
  check(h.screenOn&&!h.retainedForLock&&!h.keyguardLocked&&h.zoomed&&h.reasserts==1,"native unlock restores before delayed screen-on broadcast");
  check(h.entries==0&&h.lp.alpha==.8f&&h.slots[5].taskId==205,"early unlock keeps original task and steady window without animation");
  onScreenState(true);h.main.drain();onUserPresent();h.main.drain();
  check(h.reasserts==1&&h.entries==0,"delayed screen-on and duplicate unlock do not replay restoration");
  h=fresh(f);h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);off(h);
  MiStepEntryGuard.unlocked=false;onNativeWake();
  check(h.retainedForLock&&h.keyguardLocked&&!h.zoomed&&h.lp.alpha==0,"native wake cannot reveal locked display");
  MiStepEntryGuard.unlocked=true;onNativeWake();
  check(!h.retainedForLock&&h.screenOn&&h.zoomed&&h.main.tasks.isEmpty(),"native main callback restores immediately before first frame");
  onNativeWake();onScreenState(true);h.main.drain();
  check(h.reasserts==1&&h.entries==0,"native wake plus screen broadcast restore once");
  h=fresh(f);h.workspaceOptions=WorkspaceOptions.DEFAULT.withKeepOnLock(true);off(h);
  Looper.current=null;onNativeWake();
  check(h.retainedForLock&&h.main.tasks.size()==1,"off-main native callback marshals to UI thread");
  Looper.current=Looper.MAIN;h.main.drain();
  check(!h.retainedForLock&&h.reasserts==1,"marshalled callback restores attached task once");
  h=fresh(f);onNativeWake();check(h.reasserts==0&&h.entries==0,"native callbacks cannot open an exited session");
  System.out.println("production lock retention lifecycle checks="+checks+" passed");
 }
}'''
}
paths = []
for name, body in files.items():
    path = out / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body.replace('__METHODS__', methods), encoding='utf-8')
    paths.append(str(path))
paths.append(str(src / 'WorkspaceOptions.java'))
paths.append(str(src / 'MiStepNavigationPolicy.java'))
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17',
                '-d', str(out), *paths], check=True)
subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'com.jiusis.mistep.MiStepHost',
                str(out)], check=True)
