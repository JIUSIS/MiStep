package com.jianguo.mistep;

import android.view.WindowManager;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Invoke the real reflection bridge; fake objects cannot validate SF rendering/input order. */
public final class NativeFreeformLayerBridgeTest {
    static int checks;
    static class Surface { boolean valid = true; boolean isValid() { return valid; } }
    static class Task {
        int mTaskId = System.identityHashCode(this);
        Object mTransitionController;
        int mode; boolean visible = true, requested = true; Surface surface = new Surface(); Task root = this;
        Task(int mode) { this.mode = mode; }
        int getWindowingMode() { return mode; }
        Task getRootTask() { return root; }
        boolean isVisible() { return visible; }
        boolean isVisibleRequested() { return requested; }
        Surface getSurfaceControl() { return surface; }
    }
    static class Window {
        WindowManager.LayoutParams mAttrs = paramsWithoutAndroidConstructor();
        boolean visible = true, force, canAssign = true;
        int calls, layer, restores, mLastLayer = 10;
        Object tx, anchor, mLastRelativeToLayer;
        Surface surface = new Surface();
        List<Object> restoredTransactions = new ArrayList<>();
        Window(String title) { mAttrs.title=title; mAttrs.packageName="com.android.systemui"; mAttrs.type=2038; }
        boolean isVisible() { return visible; }
        Surface getSurfaceControl() { return surface; }
        void assignRelativeLayer(Object tx, Object anchor, int layer, boolean force) {
            calls++; this.tx=tx; this.anchor=anchor; this.layer=layer; this.force=force;
            mLastLayer=layer; mLastRelativeToLayer=anchor;
        }
        void assignLayer(Object tx, int layer) {
            if (!canAssign) return; // Actual WM transition-layer gate.
            setLayer(tx, layer); mLastLayer=layer; mLastRelativeToLayer=null;
        }
        void setLayer(Object tx, int layer) {
            restores++; this.tx=tx; this.layer=layer; anchor=null;
            restoredTransactions.add(tx);
        }
        void setRelativeLayer(Object tx, Object anchor, int layer) {
            restores++; this.tx=tx; this.layer=layer; this.anchor=anchor;
            restoredTransactions.add(tx);
        }
    }
    static WindowManager.LayoutParams paramsWithoutAndroidConstructor() {
        try {
            java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (WindowManager.LayoutParams) ((sun.misc.Unsafe) field.get(null))
                    .allocateInstance(WindowManager.LayoutParams.class);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    static class Display {
        List<Window> windows = new ArrayList<>(); List<Task> tasks = new ArrayList<>();
        int zoomReplays; Object zoomTransaction;
        Object pending = new Object();
        Object getPendingTransaction() { return pending; }
        void reapplyMagnificationSpec(Object tx) { zoomReplays++; zoomTransaction=tx; }
        void forAllWindows(Consumer<Object> action, boolean topToBottom) {
            check(!topToBottom); windows.forEach(action);
        }
        void forAllTasks(Consumer<Object> action, boolean topToBottom) {
            check(!topToBottom); tasks.forEach(action);
        }
    }
    static class PinManager {
        java.util.Set<Integer> foreground = new java.util.HashSet<>();
        boolean getMiuiFreeformIsForegroundPin(int taskId) { return foreground.contains(taskId); }
    }
    public static void main(String[] args) throws Exception {
        zoom(true);
        Display display = new Display(); Object transaction = new Object();
        Window host = new Window("MiStepHost"), bridge = new Window("MiStepTransition");
        Window mirror = new Window("MiStepMirrorLayer"), clipboard = new Window("MiStepClipboard");
        Window foreign = new Window("MiStepHost"); foreign.mAttrs.packageName="another.app";
        Window hidden = new Window("MiStepHost"); hidden.visible=false;
        display.windows.addAll(List.of(host, bridge, mirror, clipboard, foreign, hidden));
        Task main = new Task(1), side = new Task(6), low = new Task(5), high = new Task(5);
        Task child = new Task(5); child.root=low;
        display.tasks.addAll(List.of(main, side, child, low, high));
        apply(display, transaction);
        check(host.calls==1 && host.anchor==low.surface && host.layer==-4 && host.force);
        check(bridge.calls==1 && bridge.anchor==low.surface && bridge.layer==-2);
        check(mirror.calls==1 && mirror.anchor==low.surface && mirror.layer==-3);
        check(clipboard.calls==1 && clipboard.anchor==low.surface && clipboard.layer==-1);
        check(host.tx==transaction && bridge.tx==transaction
                && mirror.tx==transaction && clipboard.tx==transaction);
        check(display.zoomReplays==1 && display.zoomTransaction==transaction);
        check(foreign.calls==0 && hidden.calls==0);
        low.visible=false; apply(display, transaction); check(host.anchor==high.surface);
        // Closing task still has a valid, visible animation leash. Normal WM layering
        // is blocked during finish construction, so the old implementation stays black.
        high.requested=false;
        host.canAssign=false;
        host.assignLayer(transaction, 10);
        check(host.anchor==high.surface);
        Object finish = new Object();
        apply(display, finish);
        check(host.anchor==null && host.mLastRelativeToLayer==null && host.mLastLayer==10);
        check(host.layer==10 && host.restoredTransactions.contains(finish)
                && host.restoredTransactions.contains(display.pending));
        check(bridge.anchor==null && mirror.anchor==null && clipboard.anchor==null);
        check(host.calls==2 && display.zoomReplays==3);
        display.tasks.clear(); display.tasks.add(side); apply(display, transaction);
        check(host.calls==2 && display.zoomReplays==3);

        // A later normal layer assignment replaces the saved order; a retained OEM
        // relative anchor must also be restored, rather than flattened to zero.
        high.requested=true; display.tasks.add(high); apply(display, transaction);
        Object originalAnchor = new Surface();
        host.setRelativeLayer(transaction, originalAnchor, 23);
        host.mLastRelativeToLayer=originalAnchor; host.mLastLayer=23;
        apply(display, transaction);
        high.visible=false; apply(display, transaction);
        check(host.anchor==originalAnchor && host.layer==23 && host.mLastLayer==23);
        check(host.mLastRelativeToLayer==originalAnchor);

        // Leaving Mi Step with an open native float must release our layer as well.
        high.visible=true; apply(display, transaction); zoom(false); apply(display, transaction);
        check(host.anchor==originalAnchor && bridge.anchor==null);

        // Hidden/recreated host windows cannot keep a native-task visibility dependency.
        zoom(true); apply(display, transaction); host.visible=false;
        apply(display, transaction); check(host.anchor==originalAnchor);
        host.visible=true; apply(display, transaction);
        host.surface.valid=false; apply(display, transaction);
        high.visible=false; apply(display, transaction);
        check(bridge.anchor==null && foreign.calls==0 && hidden.calls==0);

        // Real foreground pin retains BOTH WM visibility flags. No child-layer
        // assignment follows the decision before Shell hides the canonical task.
        Display pinDisplay = new Display(); PinManager manager = new PinManager();
        Window pinHost = new Window("MiStepHost"), pinMirror = new Window("MiStepMirrorLayer");
        pinDisplay.windows.addAll(List.of(pinHost, pinMirror));
        Task pinTask = new Task(5); pinDisplay.tasks.add(pinTask);
        setStatic("sNativeFreeformPinManager", manager);
        apply(pinDisplay, new Object());
        check(pinHost.anchor==pinTask.surface && pinMirror.anchor==pinTask.surface);
        manager.foreground.add(pinTask.mTaskId);
        Object lock = new Object(), target = fakeTarget(pinDisplay, lock);
        setStatic("sZoomTarget", target);
        Method release = HostEntry.class.getDeclaredMethod(
                "releaseNativeForegroundPinLayers", Object.class, int.class);
        release.setAccessible(true); release.invoke(null, manager, pinTask.mTaskId);
        check(pinTask.visible && pinTask.requested);
        check(pinHost.anchor==null && pinMirror.anchor==null);
        check(pinHost.mLastRelativeToLayer==null && pinHost.mLastLayer==10);
        android.view.SurfaceControl.Transaction immediate = android.view.SurfaceControl.Transaction.latest;
        check(immediate.applies==1 && immediate.closes==1);
        check(pinHost.restoredTransactions.contains(immediate)
                && pinHost.restoredTransactions.contains(pinDisplay.pending));
        // Later layer traversals must not reattach the hidden foreground pin.
        apply(pinDisplay, new Object()); check(pinHost.anchor==null);
        // Another visible native task may still be the anchor.
        Task other = new Task(5); pinDisplay.tasks.add(other);
        apply(pinDisplay, new Object()); check(pinHost.anchor==other.surface);
        other.visible=false; manager.foreground.clear();
        apply(pinDisplay, new Object()); check(pinHost.anchor==pinTask.surface);
        // Background pin keeps its established WM visibility gate.
        pinTask.visible=false; apply(pinDisplay, new Object()); check(pinHost.anchor==null);
        setStatic("sZoomTarget", null); setStatic("sNativeFreeformPinManager", null);
        System.out.println("NativeFreeformLayerBridge checks="+checks+" passed (fake WM; rendering/input pending)");
    }
    static Object fakeTarget(Display display, Object lock) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        Class<?> type = Class.forName("com.jianguo.mistep.HostEntry$ZoomTarget");
        Object target = unsafe.allocateInstance(type);
        for (var entry : java.util.Map.<String,Object>of("display", display,
                "globalLock", lock, "handler", new android.os.Handler()).entrySet()) {
            var member = type.getDeclaredField(entry.getKey()); member.setAccessible(true);
            member.set(target, entry.getValue());
        }
        return target;
    }
    static void setStatic(String name, Object value) throws Exception {
        var field = HostEntry.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    static void apply(Display display, Object tx) throws Exception {
        Method method = HostEntry.class.getDeclaredMethod("placeHostBelowNativeFreeforms", Object.class, Object.class);
        method.setAccessible(true); method.invoke(null, display, tx);
    }
    static void check(boolean value) { checks++; if (!value) throw new AssertionError(); }
    static void zoom(boolean enabled) throws Exception {
        java.lang.reflect.Field field = HostEntry.class.getDeclaredField("sZoomed");
        field.setAccessible(true); field.setBoolean(null, enabled);
    }
}
