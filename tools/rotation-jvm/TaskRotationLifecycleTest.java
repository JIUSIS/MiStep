package com.jiusis.mistep;

import java.lang.reflect.*;
import java.util.Map;

/** Actual HostEntry lifecycle against native cancellation/configuration callbacks. */
public class TaskRotationLifecycleTest {
    static int checks;
    static final HostEntry host = new HostEntry();
    static class Rotation {
        boolean isRotationFrozen() { return true; }
        int getRotation() { return 1; }
        int rotationForOrientation(int request, int previous) { return request == 1 ? 0 : 1; }
        boolean updateRotationUnchecked(boolean force) { return false; }
    }
    static class Task {
        int mode = 1; Activity top;
        Activity topRunningActivity() { return top; }
        int getWindowingMode() { return mode; }
        boolean isForceTranslucent() { return mode == 6; }
    }
    static class Activity {
        static class Configuration { int orientation = 1; }
        int request = 1, starts, finishes, configurations;
        boolean finishing, mRemovingFromDisplay, attached = true, failFinish;
        Task task = new Task(); Display mDisplayContent;
        Object mFixedRotationTransformState;
        Runnable onConfiguration;
        Activity(Display display) { mDisplayContent = display; task.top = this; }
        boolean isActivityTypeHome() { return false; }
        boolean inMultiWindowMode() { return task.mode == 6 || task.mode == 5; }
        int getOverrideOrientation() { return request; }
        boolean hasFixedRotationTransform() { return mFixedRotationTransformState != null; }
        Object getParent() { return attached ? task : null; }
        Task getTask() { return task; }
        Activity asActivityRecord() { return this; }
        Display getDisplayContent() { return mDisplayContent; }
        Configuration getConfiguration() { return new Configuration(); }
        int getRootTaskId() { return System.identityHashCode(task); }
        void finishFixedRotationTransform() {
            if (failFinish) throw new IllegalStateException("deliberate finish failure");
            if (mFixedRotationTransformState == null) return;
            finishes++; mFixedRotationTransformState = null; configurations++;
            if (onConfiguration != null) onConfiguration.run();
        }
    }
    static class Display {
        static class Info { int logicalWidth = 2880, logicalHeight = 1800, logicalDensityDpi = 320; }
        Rotation mDisplayRotation = new Rotation(); Activity top;
        Info getDisplayInfo() { return new Info(); }
        Activity topRunningActivity() { return top; }
        boolean updateOrientation() { return false; }
        void sendNewConfiguration() {}
        void startFixedRotationTransform(Activity activity, int rotation) {
            activity.starts++; activity.mFixedRotationTransformState = new Object();
        }
    }

    public static void main(String[] args) throws Exception {
        Display display = new Display();
        field("sDisplayContent").set(null, display);
        field("sTaskRotationResolver").set(null,
                Rotation.class.getDeclaredMethod("rotationForOrientation", int.class, int.class));
        call("beginFixedMainRotationLocked", new Class[]{Object.class}, display);
        field("sZoomed").set(null, true);
        Activity app = new Activity(display); display.top = app;
        app.onConfiguration = () -> {
            try { apply(display, app); } catch (Exception error) { throw new RuntimeException(error); }
        };
        apply(display, app);
        Object state = app.mFixedRotationTransformState;
        for (int i = 0; i < 59; i++) { nativeFinish(app, false); apply(display, app); }
        check(app.starts == 1 && app.finishes == 0 && app.configurations == 0,
                "same-direction transition completion recreated rotation " + app.starts + " times");
        check(app.mFixedRotationTransformState == state, "native state identity changed");

        app.task.mode = 6; display.top = new Activity(display); apply(display, app);
        for (int i = 0; i < 59; i++) nativeFinish(app, false);
        check(app.starts == 1 && app.mFixedRotationTransformState == state,
                "collection lost its real token transform");
        app.task.mode = 1; display.top = app; apply(display, app);
        check(retain(app), "main promotion lost ownership");

        call("releaseTaskRotationLocked", new Class[]{Object.class, String.class}, app, "explicit test release");
        check(app.mFixedRotationTransformState == null && app.starts == 1 && owned().isEmpty(),
                "release callback reinstalled or orphaned a transform");
        check(!(Boolean) field("sUpdatingTaskRotation").get(null), "release guard leaked");

        apply(display, app); app.request = 13; apply(display, app);
        check(app.mFixedRotationTransformState == null && owned().isEmpty(),
                "real automatic/landscape request did not release portrait rotation");
        app.request = 1; apply(display, app);
        Object foreign = new Object(); app.mFixedRotationTransformState = foreign;
        check(!retain(app), "foreign replacement was treated as our native state");
        int startsBeforeNativeTakeover = app.starts;
        apply(display, app);
        check(app.starts == startsBeforeNativeTakeover && owned().isEmpty(),
                "configuration callback overwrote a native replacement transform");
        call("releaseTaskRotationLocked", new Class[]{Object.class, String.class}, app, "foreign replacement");
        check(app.mFixedRotationTransformState == foreign && owned().isEmpty(),
                "cleanup finished a foreign replacement state");
        app.mFixedRotationTransformState = null; apply(display, app);

        app.finishing = true; check(!retain(app), "finishing task blocked native cleanup"); app.finishing = false;
        app.mRemovingFromDisplay = true; check(!retain(app), "removing task blocked native cleanup");
        app.mRemovingFromDisplay = false;
        app.attached = false; check(!retain(app), "detached task blocked native cleanup"); app.attached = true;
        app.task.top = new Activity(display); check(!retain(app), "former top blocked native cleanup"); app.task.top = app;
        app.task.mode = 5; check(!retain(app), "native freeform blocked cleanup"); app.task.mode = 1;
        app.mDisplayContent = new Display(); check(!retain(app), "other display blocked cleanup"); app.mDisplayContent = display;
        field("sNativeLandscapeState").set(null, NativeLandscapePolicy.FULLSCREEN);
        check(!retain(app), "native fullscreen handoff blocked cleanup");
        field("sNativeLandscapeState").set(null, NativeLandscapePolicy.INACTIVE);
        check(!nativeFinish(app, true), "actual display rotation was suppressed");
        check(app.mFixedRotationTransformState == null, "actual rotation left a native state");
        app.onConfiguration = null; apply(display, app);
        app.failFinish = true;
        check(Boolean.FALSE.equals(call("releaseTaskRotationLocked", new Class[]{Object.class, String.class},
                app, "deliberate finish failure")), "cleanup failure was reported as success");
        check(!(Boolean) field("sUpdatingTaskRotation").get(null) && owned().containsKey(app),
                "cleanup failure leaked guard or lost retry ownership");
        app.failFinish = false;
        call("endFixedMainRotationLocked", new Class[]{Object.class}, display);
        check(owned().isEmpty() && app.mFixedRotationTransformState == null, "exit did not release native state");
        check(!retain(app), "inactive session blocked native cleanup");
        check(((Map<?,?>) field("sTaskRotationTransforms").get(null)).isEmpty(), "native state lease leaked");
        System.out.println("TaskRotationLifecycle checks=" + checks + " passed (fake WM callbacks; no device rendering)");
    }
    static boolean nativeFinish(Activity app, boolean actualDisplayRotation) throws Exception {
        boolean retained = !actualDisplayRotation && retain(app);
        if (!retained) {
            Runnable callback = app.onConfiguration;
            if (actualDisplayRotation) app.onConfiguration = null;
            try { app.finishFixedRotationTransform(); }
            finally { app.onConfiguration = callback; }
        }
        return retained;
    }
    static boolean retain(Activity app) throws Exception {
        try { return Boolean.TRUE.equals(call("retainTaskRotationOnNativeFinishLocked", new Class[]{Object.class}, app)); }
        catch (NoSuchMethodException oldImplementation) { return false; }
    }
    static Object apply(Display display, Activity app) throws Exception {
        return call("applyMiStepFixedRotationLocked", new Class[]{Object.class, Object.class, String.class},
                display, app, "lifecycle-regression");
    }
    static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = HostEntry.class.getDeclaredMethod(name, types); method.setAccessible(true);
        return method.invoke(host, args);
    }
    static Field field(String name) throws Exception {
        Field result = HostEntry.class.getDeclaredField(name); result.setAccessible(true); return result;
    }
    static Map<?,?> owned() throws Exception { return (Map<?,?>) field("sTaskRotations").get(null); }
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
