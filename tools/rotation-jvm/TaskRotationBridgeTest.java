package com.jianguo.mistep;
import java.lang.reflect.*;
import java.util.Map;
import de.robv.android.xposed.XposedBridge;

/** Exercises the actual HostEntry bridge with fake WM objects, not Android rendering. */
public class TaskRotationBridgeTest {
    static int checks;
    static HostEntry host = new HostEntry();
    static class Rotation {
        boolean frozen = true;
        int proposed = 3, physical = 1, forced, resolutions;
        boolean lastForce;
        boolean isRotationFrozen() { return frozen; }
        int getRotation() { return physical; }
        int rotationForOrientation(int request, int previous) { resolutions++; return proposed; }
        boolean updateRotationUnchecked(boolean force) throws Exception {
            forced++; lastForce = force;
            int desired = (Boolean) field("sFullscreenRotationActive").get(null) ? 0 : proposed;
            boolean changed = physical != desired; physical = desired; return changed;
        }
    }
    static class Task {
        int mode = 1;
        int getWindowingMode() { return mode; }
        boolean isForceTranslucent() { return mode == 6; }
    }
    static class Activity {
        static class Configuration { int orientation = 1; }
        int request, rotation, starts, finishes;
        boolean home, transformed;
        Task task = new Task();
        Configuration configuration = new Configuration();
        Display mDisplayContent;
        Activity(int request) { this.request = request; }
        boolean isActivityTypeHome() { return home; }
        boolean inMultiWindowMode() { return task.mode == 6 || task.mode == 5; }
        int getOverrideOrientation() { return request; }
        boolean hasFixedRotationTransform() { return transformed; }
        Task getTask() { return task; }
        Configuration getConfiguration() { return configuration; }
        int getRootTaskId() { return System.identityHashCode(task); }
        void finishFixedRotationTransform() { finishes++; transformed = false; rotation = 0; }
    }
    static class Display {
        Rotation mDisplayRotation = new Rotation();
        Activity top;
        int configurations, launches;
        boolean failStart;
        boolean updateOrientation() { return false; } // same top orientation: old early-return bug
        void sendNewConfiguration() { configurations++; }
        Activity topRunningActivity() { return top; }
        void startFixedRotationTransform(Activity activity, int rotation) {
            if (failStart) throw new IllegalStateException("simulated transform failure");
            activity.starts++; activity.rotation = rotation; activity.transformed = true;
        }
        void setFixedRotationLaunchingApp(Activity activity, int rotation) { launches++; }
        void setFixedRotationLaunchingAppUnchecked(Activity activity) { launches++; }
    }
    public static void main(String[] args) throws Exception {
        Display display = new Display();
        field("sDisplayContent").set(null, display);
        field("sTaskRotationResolver").set(null,
                Rotation.class.getDeclaredMethod("rotationForOrientation", int.class, int.class));
        call("beginFixedMainRotationLocked", new Class[]{Object.class}, display);
        check(display.mDisplayRotation.physical == 0, "enter from landscape forces portrait");
        check(display.mDisplayRotation.lastForce, "same orientation still forces native rotation update");
        check(display.configurations == 1, "changed rotation sends configuration");

        Activity user = new Activity(2); display.top = user;
        check(Boolean.FALSE.equals(apply(display, user)), "automatic portrait task obeys system lock");
        check(user.starts == 0 && display.mDisplayRotation.resolutions == 0,
                "locked request cannot sample native sensor policy");
        display.mDisplayRotation.frozen = false;
        check(Boolean.FALSE.equals(apply(display, user)),
                "landscape-resolved main task did not switch to native fullscreen");
        check(user.starts == 0 && owned().isEmpty(),
                "main landscape retained a workspace-local transform");
        display.mDisplayRotation.frozen = true;
        user.request = 1; apply(display, user);
        check(owned().isEmpty(), "portrait main has no stale transform");

        field("sZoomed").set(null, true);
        Activity containedLandscape = new Activity(0); display.top = containedLandscape;
        check(Boolean.TRUE.equals(apply(display, containedLandscape))
                        && containedLandscape.starts == 1,
                "landscape app opened in the active main area must stay inside Mi Step");
        call("endFixedMainRotationLocked", new Class[]{Object.class}, display);
        call("beginFixedMainRotationLocked", new Class[]{Object.class}, display);
        field("sZoomed").set(null, false);

        Activity exitingMain = new Activity(0); display.top = exitingMain;
        check(Boolean.FALSE.equals(apply(display, exitingMain)),
                "explicit landscape main switched to native fullscreen");
        Activity launcher = new Activity(1); launcher.home = true; display.top = launcher;
        check(Boolean.FALSE.equals(apply(display, launcher)), "home does not acquire app transform");
        check(!exitingMain.transformed && exitingMain.finishes == 0 && owned().isEmpty(),
                "native fullscreen main never acquired workspace ownership");
        check(field("sMiStepFixedRotationApp").get(null) == null,
                "home transition clears stale main rotation owner");

        Activity side = new Activity(0); side.task.mode = 6; display.top = side;
        check(Boolean.TRUE.equals(apply(display, side)), "side landscape keeps local rotation");
        side.task.mode = 6;
        Activity home = new Activity(1); home.home = true;
        apply(display, home);
        check(side.transformed, "home handoff preserves side token");
        check(field("sMiStepFixedRotationApp").get(null) == null,
                "home handoff clears main pointer without releasing promoted side token");
        Activity main = new Activity(8); display.top = main;
        apply(display, main);
        check(side.transformed && !main.transformed && owned().size() == 1,
                "landscape main leaves the side token under its retained owner");
        main.request = 1;
        apply(display, main);
        check(!main.transformed && side.transformed, "portrait main remains unowned");
        check(owned().size() == 1, "side rotation ownership retained");
        side.request = 1; display.top = home;
        call("refreshMiStepTaskRotationLocked", new Class[]{Object.class}, display);
        check(!side.transformed, "an explicit portrait change clears a non-top side token");

        main.request = 0; display.top = main; apply(display, main);
        main.request = 8; apply(display, main);
        check(main.starts == 0 && owned().isEmpty(), "native fullscreen owns main direction");
        side.request = 0; side.task.mode = 1; apply(display, side); side.task.mode = 6;
        main.request = 0; apply(display, main);
        check(owned().isEmpty(), "no workspace-owned tokens before exit");
        display.mDisplayRotation.proposed = 1;
        call("endFixedMainRotationLocked", new Class[]{Object.class}, display);
        check(owned().isEmpty() && !main.transformed && !side.transformed,
                "exit releases all owned main and side transforms");
        check(!(Boolean) field("sFullscreenRotationActive").get(null), "exit releases display hold");
        check(display.mDisplayRotation.physical == 1, "native rotation resumes after exit");
        check(apply(display, main) == null, "inactive session does not intercept app rotation");

        Activity fullscreenVideo = new Activity(-1);
        fullscreenVideo.configuration.orientation = 2;
        display.top = fullscreenVideo;
        display.mDisplayRotation.physical = 3;
        call("beginFixedMainRotationLocked", new Class[]{Object.class}, display);
        check(Boolean.FALSE.equals(apply(display, fullscreenVideo))
                        && !fullscreenVideo.transformed,
                "pre-entry fullscreen landscape uses native fullscreen");
        fullscreenVideo.task.mode = 6;
        fullscreenVideo.configuration.orientation = 1;
        apply(display, fullscreenVideo);
        check(fullscreenVideo.rotation == 3 && fullscreenVideo.starts == 1,
                "portrait workspace configuration does not erase a side task's fullscreen direction");
        Activity replacement = new Activity(-1); replacement.task = fullscreenVideo.task;
        apply(display, replacement);
        check(replacement.rotation == 3, "rotation state follows the task across activity replacement");
        replacement.task.mode = 5;
        check(apply(display, replacement) == null && !replacement.transformed,
                "native mode5 returns to HyperOS and releases only our local transform");
        Activity nativeFreeform = new Activity(11); nativeFreeform.task.mode = 5;
        check(apply(display, nativeFreeform) == null && nativeFreeform.starts == 0,
                "unrelated native freeform orientation is not intercepted");
        Activity manualVideo = new Activity(11); display.top = manualVideo;
        check(Boolean.FALSE.equals(apply(display, manualVideo)) && !manualVideo.transformed,
                "manual USER_LANDSCAPE main uses native fullscreen");
        manualVideo.mDisplayContent = display;
        call("beforeApplicationOrientationRequest", new Class[]{Object.class, int.class}, manualVideo, -1);
        manualVideo.request = -1;
        check(Boolean.FALSE.equals(apply(display, manualVideo)) && !manualVideo.transformed,
                "application exit to UNSPECIFIED overrides the remembered fullscreen direction");
        manualVideo.task.mode = 6; manualVideo.configuration.orientation = 2;
        apply(display, manualVideo);
        check(!manualVideo.transformed, "a stale side configuration cannot restore an exited fullscreen direction");
        call("endFixedMainRotationLocked", new Class[]{Object.class}, display);

        call("beginFixedMainRotationLocked", new Class[]{Object.class}, display);
        Activity nativeToken = new Activity(0); nativeToken.transformed = true;
        apply(display, nativeToken);
        check(nativeToken.starts == 0 && nativeToken.finishes == 0,
                "foreign native transform remains with its owner");
        check(apply(new Display(), main) == null, "secondary display ignored");
        display.failStart = true;
        Activity failing = new Activity(0); failing.task.mode = 6; apply(display, failing);
        check(owned().isEmpty() && !failing.transformed, "failed transform rolls back ownership");
        check(XposedBridge.errors == 1, "only deliberate failure reached exception path");
        check(!(Boolean) field("sUpdatingTaskRotation").get(null), "reentrancy guard released");
        check(display.launches == 0, "bridge never emits a display fixed-rotation launch");
        System.out.println("TaskRotationBridge checks=" + checks + " passed (fake WM; no device rendering)");
    }
    static Object apply(Display display, Activity activity) throws Exception {
        return call("applyMiStepFixedRotationLocked", new Class[]{Object.class, Object.class, String.class},
                display, activity, "jvm-test");
    }
    static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = HostEntry.class.getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(host, args);
    }
    static Field field(String name) throws Exception {
        Field field = HostEntry.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    static Map<?,?> owned() throws Exception { return (Map<?,?>) field("sTaskRotations").get(null); }
    static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}
