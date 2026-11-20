from pathlib import Path
import os
import subprocess
import sys


root = Path(__file__).resolve().parents[2]
src = root / "module/src/main/java/com/jiusis/mistep/MiStepHost.java"
source = src.read_text(encoding="utf-8-sig")
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
jdk = Path(os.environ["MISTEP_JAVA_BIN"])


def method(signature):
    start = source.index(signature)
    brace = source.index("{", start) + 1
    depth = 1
    end = brace
    while depth:
        if source[end] == "{":
            depth += 1
        elif source[end] == "}":
            depth -= 1
        end += 1
    return source[start:end]


members = "\n".join(method(signature) for signature in (
    "    private boolean canRetainBoundTasks() {",
    "    private void finishRetainedSuspend(long epoch) {",
    "    private void releaseForegroundRetainedTasks(long epoch,",
    "    private boolean hasBoundTasks() {",
    "    private void releaseRetainedTaskToMain(Slot slot) {",
    "    private void releaseRetainedTaskToMain(Slot slot, boolean nativeAlreadyFullscreen) {",
    "    private void preannounceDesktopLaunch(android.os.Bundle binding) {",
))
harness = r'''
import java.util.*;
import java.util.function.Consumer;

public final class WorkspaceRetentionTest {
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    static final int ACTIVE=0, SUSPENDING=1, SUSPENDED=2, RESUMING=3;
    static final int SLOT_MAIN=0, SLOT_TOP=3, CELL_SLOT_COUNT=3;
    static WorkspaceRetentionTest sInstance;
    static final class View {
        static final int VISIBLE=0, GONE=8;
        int visibility=VISIBLE;
        int getVisibility(){return visibility;}
        void setVisibility(int value){visibility=value;}
    }
    static final class TopRoll {boolean rolling;boolean rolling(){return rolling;}}
    static final class SideDismiss {boolean tracking;boolean isTracking(){return tracking;}}
    static final class OwnedTask {boolean recoveryCandidate;}
    static final class TaskWindowState {int slotIndex;boolean active;}
    static final class Slot {
        int index, taskId=-1, windowMode=6;
        Object taskToken;
        String pkg="app";
        long taskEpoch=12;
        boolean occupied, parked, frozen, promoting, launching, closing, nativeReleasePending, owned=true;
        int sideRow=-1;
        TaskWindowState windowState;
    }

    final Slot[] slots={new Slot(),new Slot(),new Slot(),new Slot()};
    final Map<Object,OwnedTask> ownedTasks=new HashMap<>();
    View root=new View();
    TopRoll topRoll;
    SideDismiss sideDismiss;
    Object topTransition, cellMotion;
    Object promotion;
    Object workspaceAnimator;
    boolean screenOn=true, retainedForLock, workspaceEntering, topSelectionPending, transferBusy, keyguardLocked;
    boolean retainedOnExit, mirrorVisible=true, failReturn, releasedBackdrop;
    boolean sideCompacting, sideDesktopRevealing, sideScrolling;
    int scene=SUSPENDING;
    long sceneEpoch=7,retainedExitSourceEpoch=-1;
    final List<Boolean> handoffFront=new ArrayList<>();
    final List<Boolean> nativeFullscreen=new ArrayList<>();
    int publishes, logs;
    int promotions;
    static final class SystemClock {static long uptimeMillis(){return 100;}}
    static final class Token {final android.os.IBinder binder=new android.os.IBinder(){};}
    static final class XposedHelpers {
        static Object callMethod(Object token,String method){return ((Token)token).binder;}
    }
    static final class Handler {
        final ArrayDeque<Runnable> pending=new ArrayDeque<>();
        void postDelayed(Runnable task,long delay){pending.add(task);}
    }
    final Handler main=new Handler();
    Object ctx=this;
    boolean tablet;
    int geometryRefreshes;
    static class DeviceProfile{static boolean isTablet(Object c){return ((WorkspaceRetentionTest)c).tablet;}}
    void scheduleDisplayGeometryRefresh(){geometryRefreshes++;}
    boolean mayWriteSideSurface(Slot slot){return true;}
    boolean sideRowInPlace(Slot slot){return true;}
    void revealSideTaskForDesktop(Slot slot,android.os.Bundle binding){throw new AssertionError("unexpected scrolling row");}
    void onSlotTapped(int index){promotions++;}
    void queueSidePromotion(Slot slot,boolean fromDesktop){promotions++;}

    WorkspaceRetentionTest(){
        for(int i=0;i<slots.length;i++) slots[i].index=i;
        sInstance=this;
    }
    boolean isActive(Slot slot){return root!=null&&sInstance==this&&slot.index>0&&slot.index<slots.length;}
    boolean controllerOwns(Slot slot,int id,Object token){return slot.owned&&slot.taskId==id&&token.equals(slot.taskToken);}
    void releaseWorkspaceBackdrop(){releasedBackdrop=true;}
    void releaseVisibleNativeRetainedTask(){}
    void setMirrorLayerVisible(boolean value){mirrorVisible=value;}
    void publishDesktopSideTasks(){publishes++;}
    String describeTasks(){return "tasks";}
    void log(String value){logs++;}
    void clearSlot(Slot slot){slot.occupied=false;slot.parked=false;slot.taskId=-1;slot.taskToken=null;slot.windowState=null;}
    void returnToSystem(Slot slot,boolean front,Runnable success,Consumer<String> failure){
        returnToSystem(slot,front,false,false,success,failure);
    }
    void returnToSystem(Slot slot,boolean front,boolean reveal,boolean nativeAlreadyFullscreen,
            Runnable success,Consumer<String> failure){
        handoffFront.add(front);
        nativeFullscreen.add(nativeAlreadyFullscreen);
        if(failReturn){failure.accept("fixture handoff failure");return;}
        success.run();
    }

    __METHODS__

    static Slot bind(WorkspaceRetentionTest host,int index,int id){
        Slot slot=host.slots[index];slot.occupied=true;slot.taskId=id;slot.taskToken="task-"+id;return slot;
    }

    static void eligibility(){
        WorkspaceRetentionTest host=new WorkspaceRetentionTest();
        bind(host,1,11);check(host.canRetainBoundTasks(),"stable live binding can be retained");
        host.slots[1].parked=true;check(!host.canRetainBoundTasks(),"parked task uses existing restore flow");
        host.slots[1].parked=false;host.slots[1].windowMode=1;
        check(!host.canRetainBoundTasks(),"fullscreen task is not retained as a side window");
        host.slots[1].windowMode=6;host.slots[1].owned=false;
        check(!host.canRetainBoundTasks(),"stale controller binding falls back safely");
        host.slots[1].owned=true;host.retainedForLock=true;
        check(!host.canRetainBoundTasks(),"lock handoff keeps its own lifecycle");
        host.retainedForLock=false;host.ownedTasks.put("orphan",new OwnedTask());
        host.ownedTasks.get("orphan").recoveryCandidate=true;
        check(!host.canRetainBoundTasks(),"unbound recovery task uses existing handback");
        host.ownedTasks.clear();host.promotion=new Object();
        check(!host.canRetainBoundTasks(),"in-flight promotion blocks retention");
        host.promotion=null;host.root.setVisibility(View.GONE);
        check(!host.canRetainBoundTasks(),"invisible host is not retained twice");
        host.root.setVisibility(View.VISIBLE);host.slots[1].occupied=false;
        check(!host.canRetainBoundTasks(),"empty workspace uses ordinary suspend");
    }

    static void hideAndRelease(){
        WorkspaceRetentionTest host=new WorkspaceRetentionTest();
        Slot first=bind(host,1,21),second=bind(host,2,22);
        host.scene=SUSPENDING;host.finishRetainedSuspend(host.sceneEpoch);
        check(host.retainedOnExit&&host.scene==SUSPENDED,"exit keeps the live session hidden");
        check(host.root.getVisibility()==View.GONE&&!host.mirrorVisible&&host.releasedBackdrop,
                "host hides and releases only workspace backing");
        host.releaseRetainedTaskToMain(first);
        check(host.handoffFront.equals(List.of(true)),"desktop task handoff keeps the selected app in front");
        check(!first.occupied&&second.occupied&&host.retainedOnExit,
                "one external launch releases only its own binding");
        host.releaseRetainedTaskToMain(second,true);
        check(!second.occupied&&!host.retainedOnExit,"last released binding clears retained state");
        check(host.nativeFullscreen.equals(List.of(false,true)),"native mode change uses cleanup-only handback");
    }

    static void foregroundOnResume(){
        WorkspaceRetentionTest host=new WorkspaceRetentionTest();
        Slot selected=bind(host,1,31),other=bind(host,2,32);
        host.scene=RESUMING;host.retainedOnExit=true;
        int[] complete={0};
        host.releaseForegroundRetainedTasks(host.sceneEpoch,Set.of(selected.taskToken),0,()->complete[0]++);
        check(!selected.occupied&&other.occupied&&host.retainedOnExit,
                "already-opened task remains in main while peer stays retained");
        check(host.handoffFront.equals(List.of(true))&&complete[0]==1,
                "foreground task handoff completes before workspace reveal");

        WorkspaceRetentionTest failure=new WorkspaceRetentionTest();
        Slot blocked=bind(failure,1,41);failure.scene=RESUMING;failure.retainedOnExit=true;failure.failReturn=true;
        int[] premature={0};
        failure.releaseForegroundRetainedTasks(failure.sceneEpoch,Set.of(blocked.taskToken),0,()->premature[0]++);
        check(failure.scene==SUSPENDED&&blocked.occupied&&premature[0]==0,
                "failed external handoff leaves host hidden and retries later");

        WorkspaceRetentionTest stale=new WorkspaceRetentionTest();
        Slot moved=bind(stale,1,51);moved.owned=false;stale.scene=RESUMING;stale.retainedOnExit=true;
        TaskWindowState state=new TaskWindowState();state.slotIndex=1;state.active=true;moved.windowState=state;
        stale.releaseForegroundRetainedTasks(stale.sceneEpoch,Set.of(moved.taskToken),0,()->{});
        check(!moved.occupied&&state.slotIndex==SLOT_MAIN&&state.active,
                "already-detached foreground task is never reclaimed");
    }

    static android.os.Bundle selection(WorkspaceRetentionTest host,Slot slot){
        android.os.Bundle b=new android.os.Bundle();
        b.putBoolean("hostRoute",true);b.putBoolean("retainedRoute",true);
        b.putLong("scene",host.sceneEpoch);b.putLong("binding",slot.taskEpoch);
        b.putInt("taskId",slot.taskId);b.putBinder("task",((Token)slot.taskToken).binder);
        b.putString("package",slot.pkg);b.putLong("deadline",1500);return b;
    }
    static void hiddenRecents(){
        WorkspaceRetentionTest host=new WorkspaceRetentionTest();
        Slot selected=bind(host,1,61),peer=bind(host,3,62);
        selected.taskToken=new Token();peer.taskToken=new Token();
        host.scene=SUSPENDED;host.retainedOnExit=true;
        android.os.Bundle b=selection(host,selected);
        b.putLong("scene",host.sceneEpoch-1);host.preannounceDesktopLaunch(b);
        check(host.handoffFront.isEmpty(),"stale scene cannot release a retained task");
        b=selection(host,selected);b.putLong("binding",selected.taskEpoch-1);
        host.preannounceDesktopLaunch(b);
        check(host.handoffFront.isEmpty(),"stale task binding cannot release a replacement");
        b=selection(host,selected);b.putBinder("task",new android.os.IBinder(){});
        host.preannounceDesktopLaunch(b);
        check(host.handoffFront.isEmpty(),"same task number with foreign token stays untouched");
        b=selection(host,selected);b.putLong("deadline",99);host.preannounceDesktopLaunch(b);
        check(host.handoffFront.isEmpty(),"expired Recents selection cannot reopen an app later");
        b=selection(host,selected);host.keyguardLocked=true;host.preannounceDesktopLaunch(b);
        check(host.handoffFront.isEmpty(),"locked host cannot release a selected retained task");
        host.keyguardLocked=false;host.preannounceDesktopLaunch(b);
        check(host.handoffFront.equals(List.of(true))&&!selected.occupied&&peer.occupied,
                "hidden Recents releases the exact live task to native foreground and retains its peer");
        check(host.promotions==0,"hidden Recents never enters workspace swap animation");
        host.preannounceDesktopLaunch(selection(host,peer));
        check(!host.retainedOnExit&&!peer.occupied,"retained TOP task uses the same native release path");
    }

    static void clickDuringExit(){
        WorkspaceRetentionTest host=new WorkspaceRetentionTest();
        Slot selected=bind(host,3,71),peer=bind(host,1,72);
        selected.taskToken=new Token();peer.taskToken=new Token();
        host.scene=ACTIVE;
        android.os.Bundle tap=selection(host,selected);
        tap.putBoolean("retainedRoute",false);
        host.retainedExitSourceEpoch=host.sceneEpoch++;
        host.retainedOnExit=true;host.scene=SUSPENDING;
        host.preannounceDesktopLaunch(tap);
        check(host.handoffFront.isEmpty()&&host.main.pending.size()==1,"same exit defers an exact Launcher tap during fade");
        host.finishRetainedSuspend(host.sceneEpoch);
        host.main.pending.removeFirst().run();
        check(!selected.occupied&&peer.occupied&&host.handoffFront.equals(List.of(true)),
                "desktop tap carried across exit releases only the exact hidden binding");
        check(host.promotions==0,"exit tap never starts the active workspace animation");
    }

    public static void main(String[] args){
        eligibility();hideAndRelease();foregroundOnResume();hiddenRecents();clickDuringExit();
        System.out.println("WorkspaceRetention checks="+checks+" passed");
    }
}
'''.replace("__METHODS__", members)

file = out / "WorkspaceRetentionTest.java"
file.write_text(harness, encoding="utf-8")
support = {
    "android/os/IBinder.java": "package android.os; public interface IBinder {}",
    "android/os/Bundle.java": r"""package android.os; import java.util.*;
public class Bundle {
    final Map<String,Object> values=new HashMap<>();
    public void putBoolean(String k,boolean v){values.put(k,v);}
    public boolean getBoolean(String k,boolean d){return (boolean)values.getOrDefault(k,d);}
    public void putLong(String k,long v){values.put(k,v);}
    public long getLong(String k,long d){return (long)values.getOrDefault(k,d);}
    public void putInt(String k,int v){values.put(k,v);}
    public int getInt(String k,int d){return (int)values.getOrDefault(k,d);}
    public void putBinder(String k,IBinder v){values.put(k,v);}
    public IBinder getBinder(String k){return (IBinder)values.get(k);}
    public void putString(String k,String v){values.put(k,v);}
    public String getString(String k){return (String)values.get(k);}
}""",
}
for name, text in support.items():
    target=out/name
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(text,encoding="utf-8")
subprocess.run([str(jdk / "javac.exe"), "-encoding", "UTF-8", "--release", "17",
                "-d", str(out), str(file)]+[str(out/name) for name in support], check=True)
subprocess.run([str(jdk / "java.exe"), "-cp", str(out), "WorkspaceRetentionTest"], check=True)
