package com.jiusis.mistep;

import android.graphics.Rect;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Exact fullscreen task -> idle TaskView. The existing organizer retains every other task. */
final class MiStepLiveCollectTask {
    private final Object organizer, taskViews, controller, lock, displayParent, sideParent, token, homeToken;
    private final int id, userId;
    private final Rect logical;
    private final Consumer<String> log;
    private Object lease, leash, finishWct;
    private boolean waitingCancel, originalInfoSeen, releaseWhenSeen, repositoryRegistered = true;

    MiStepLiveCollectTask(Object organizer, Object taskViews, Object controller, int id, Object token,
            Object homeToken, int userId, Rect logical, Object displayParent, Consumer<String> log) {
        this.organizer = organizer; this.taskViews = taskViews; this.controller = controller;
        this.id = id; this.token = token; this.homeToken = homeToken; this.userId = userId;
        this.logical = new Rect(logical); this.displayParent = displayParent; this.log = log;
        sideParent = XposedHelpers.getObjectField(controller, "mSurfaceControl");
        lock = XposedHelpers.getObjectField(organizer, "mLock");
    }
    private Object listeners() { return XposedHelpers.getObjectField(organizer, "mTaskListeners"); }
    private Object info(int mode) {
        Object entry = XposedHelpers.callMethod(XposedHelpers.getObjectField(organizer, "mTasks"), "get", id);
        if (entry == null) throw new IllegalStateException("collect canonical task absent");
        Object task = XposedHelpers.callMethod(entry, "getTaskInfo");
        check(task, mode);
        return task;
    }
    private void check(Object task, int mode) {
        if (XposedHelpers.getIntField(task, "taskId") != id
                || !token.equals(XposedHelpers.getObjectField(task, "token"))
                || XposedHelpers.getIntField(task, "userId") != userId
                || XposedHelpers.getIntField(task, "displayId") != 0
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task, "getActivityType"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(task, "hasParentTask"))
                || (mode >= 0 && !Integer.valueOf(mode).equals(XposedHelpers.callMethod(task, "getWindowingMode"))))
            throw new IllegalStateException("collect task identity/role changed expected=" + id
                    + "/u" + userId + "/m" + mode + " actual=" + describe(task));
    }
    private String describe(Object task) {
        try { return XposedHelpers.getIntField(task, "taskId") + "/u"
                + XposedHelpers.getIntField(task, "userId") + "/d"
                + XposedHelpers.getIntField(task, "displayId") + "/m"
                + XposedHelpers.callMethod(task, "getWindowingMode") + "/type"
                + XposedHelpers.callMethod(task, "getActivityType") + "/parent="
                + XposedHelpers.callMethod(task, "hasParentTask"); }
        catch (Throwable unavailable) { return "unavailable:" + unavailable; }
    }
    private Object owner(Object task) { return XposedHelpers.callMethod(organizer, "getTaskListener", task, false); }
    private Object canonical() {
        Object entry = XposedHelpers.callMethod(XposedHelpers.getObjectField(organizer, "mTasks"), "get", id);
        Object surface = XposedHelpers.callMethod(entry, "getLeash");
        if (!MiStepLiveHomeTask.valid(surface)) throw new IllegalStateException("collect canonical leash gone");
        return surface;
    }
    boolean preflight() {
        synchronized (lock) {
            try {
                Object task = info(1);
                Object fallback = XposedHelpers.callMethod(listeners(), "get", -2);
                if (fallback == null || owner(task) != fallback
                        || XposedHelpers.callMethod(listeners(), "get", id) != null
                        || XposedHelpers.getObjectField(controller, "mTaskToken") != null
                        || !Boolean.TRUE.equals(XposedHelpers.getObjectField(controller, "mSurfaceCreated"))
                        || !((List<?>) XposedHelpers.getObjectField(taskViews, "mPending")).isEmpty()) return false;
                int count = (Integer) XposedHelpers.callMethod(listeners(), "size");
                for (int i = 0; i < count; i++)
                    if (XposedHelpers.callMethod(listeners(), "valueAt", i) == controller) return false;
                leash = canonical();
                return MiStepLiveHomeTask.valid(displayParent) && MiStepLiveHomeTask.valid(sideParent);
            } catch (Throwable error) { log.accept("live collect preflight refused: " + error); return false; }
        }
    }
    void arm(ClassLoader loader) throws Exception {
        synchronized (lock) {
            if (!preflight()) throw new IllegalStateException("collect preflight changed");
            Class<?> type = Class.forName("com.android.wm.shell.ShellTaskOrganizer$TaskListener", false, loader);
            lease = Proxy.newProxyInstance(loader, new Class<?>[]{type}, (p, method, args) -> {
                switch (method.getName()) {
                    case "onNewTaskListenerDetected": return true;
                    case "onTaskInfoChanged":
                        if (waitingCancel && args != null && args.length > 0) {
                            try {
                                check(args[0], 1); originalInfoSeen = true;
                                if (releaseWhenSeen) releaseOriginal();
                            } catch (Throwable ignored) { /* mode6 is not an inverse acknowledgement. */ }
                        }
                        return null;
                    case "onTaskVanished":
                        if (args != null && args.length > 0
                                && token.equals(XposedHelpers.getObjectField(args[0], "token"))
                                && XposedHelpers.callMethod(listeners(), "get", id) == lease)
                            XposedHelpers.callMethod(organizer, "removeListener", lease);
                        return null;
                    case "attachChildSurfaceToTask":
                        if (Integer.valueOf(id).equals(args[0])) XposedHelpers.callMethod(args[1], "setParent", leash);
                        return null;
                    case "reparentChildSurfaceToTask":
                        if (Integer.valueOf(id).equals(args[0])) XposedHelpers.callMethod(args[2], "reparent", args[1], leash);
                        return null;
                    case "toString": return "MiStepCollectLease(" + id + ")";
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == args[0];
                    default:
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                }
            });
            XposedHelpers.callMethod(organizer, "addListenerForTaskId", lease, id);
            if (owner(info(1)) != lease) throw new IllegalStateException("collect exact lease rejected");
        }
    }
    private void set(Object wct, String name, Object value) { XposedHelpers.callMethod(wct, name, token, value); }
    Object windowTransaction(boolean reverse, boolean pending) throws Exception {
        if (reverse && pending) { waitingCancel = true; originalInfoSeen = false; }
        Object wct = Class.forName("android.window.WindowContainerTransaction").getDeclaredConstructor().newInstance();
        set(wct, "setWindowingMode", reverse ? 0 : 6);
        set(wct, "setBounds", reverse ? new Rect() : new Rect(logical));
        set(wct, "setForceTranslucent", !reverse); set(wct, "setAlwaysOnTop", !reverse);
        // A side task remains visible, but cannot receive Back when the main activity finishes.
        // The inverse restores the original fullscreen task's ability to receive focus.
        set(wct, "setFocusable", true); set(wct, "setHidden", false);
        set(wct, "setInterceptBackPressedOnTaskRoot", !reverse);
        set(wct, "setTaskTrimmableFromRecents", reverse);
        if (!reverse) XposedHelpers.callMethod(wct, "reorder", homeToken, true);
        else {
            Object caption = XposedHelpers.getObjectField(controller, "mCaptionInsetsOwner");
            if (caption != null) XposedHelpers.callMethod(wct, "removeInsetsSource", token, caption, 0,
                    android.view.WindowInsets.Type.captionBar());
        }
        set(wct, "reorder", true);
        return wct;
    }
    Object resolve(Object transition) {
        synchronized (lock) {
            info(-1);
            Object found = null;
            for (Object change : (List<?>) XposedHelpers.callMethod(transition, "getChanges")) {
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (task != null) log.accept("collect transition candidate " + describe(task)
                        + " exactToken=" + token.equals(XposedHelpers.getObjectField(task, "token")));
                if (task == null || !token.equals(XposedHelpers.getObjectField(task, "token"))) continue;
                check(task, 6);
                if (found != null) throw new IllegalStateException("duplicate collect task change");
                found = XposedHelpers.callMethod(change, "getLeash");
            }
            if (!MiStepLiveHomeTask.valid(found) || !XposedHelpers.callMethod(found, "getLayerId")
                    .equals(XposedHelpers.callMethod(canonical(), "getLayerId")))
                throw new IllegalStateException("collect exact Change leash missing found=" + found
                        + " canonical=" + canonical());
            return found;
        }
    }
    void prepare(Object start, Object finish) throws Exception {
        synchronized (lock) {
            Object task = info(-1);
            if (owner(task) == lease) {
                if (XposedHelpers.getObjectField(controller, "mTaskToken") != null)
                    throw new IllegalStateException("collect controller became occupied");
                XposedHelpers.callMethod(listeners(), "put", id, controller);
                XposedHelpers.callMethod(organizer, "updateTaskListenerIfNeeded", task, canonical(), lease, controller);
            }
            if (owner(task) != controller) throw new IllegalStateException("collect native claim rejected");
            if (finishWct == null) finishWct = Class.forName("android.window.WindowContainerTransaction")
                    .getDeclaredConstructor().newInstance();
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(taskViews, "prepareOpenAnimation", controller,
                    true, start, finish, task, canonical(), finishWct)))
                throw new IllegalStateException("collect TaskView prepare failed after possible binding");
            XposedHelpers.callMethod(start, "hide", sideParent);
        }
    }
    boolean originalRoles() {
        synchronized (lock) {
            try { info(1); return !waitingCancel || originalInfoSeen; }
            catch (Throwable ignored) { return false; }
        }
    }
    boolean sideOwned() {
        synchronized (lock) {
            try { return owner(info(6)) == controller && token.equals(XposedHelpers.getObjectField(controller, "mTaskToken")); }
            catch (Throwable ignored) { return false; }
        }
    }
    boolean originalSettled() {
        synchronized (lock) {
            try {
                return originalRoles() && owner(info(1)) == XposedHelpers.callMethod(listeners(), "get", -2)
                        && XposedHelpers.getObjectField(controller, "mTaskToken") == null && repositoryRegistered;
            } catch (Throwable ignored) { return false; }
        }
    }
    void releaseOriginalWhenSeen() {
        synchronized (lock) { releaseWhenSeen = true; if (originalRoles()) releaseOriginal(); }
    }
    private void releaseOriginal() {
        if (owner(info(1)) == lease) XposedHelpers.callMethod(organizer, "removeListener", lease);
    }
    Object finishWindowTransaction() { return finishWct; }
    void settle(Object tx, float scale) {
        synchronized (lock) {
            Object task = info(-1);
            boolean side = Integer.valueOf(6).equals(XposedHelpers.callMethod(task, "getWindowingMode"));
            if (side) {
                if (!sideOwned()) try { prepare(tx, tx); }
                catch (Exception error) { throw new IllegalStateException("collect recovery claim failed", error); }
                if (!Boolean.TRUE.equals(XposedHelpers.getObjectField(controller, "mSurfaceCreated")))
                    throw new IllegalStateException("collect destination surface disappeared");
                XposedHelpers.callMethod(taskViews, "updateVisibilityState", controller, true);
            } else {
                if (!originalRoles()) throw new IllegalStateException("collect inverse not confirmed");
                if (token.equals(XposedHelpers.getObjectField(controller, "mTaskToken"))) {
                    repositoryRegistered = false;
                    try {
                        XposedHelpers.callMethod(taskViews, "unregisterTaskView", controller);
                        if (owner(task) == controller) XposedHelpers.callMethod(organizer, "removeListener", controller);
                        XposedHelpers.callMethod(controller, "onTaskVanished", task);
                    } finally {
                        XposedHelpers.callMethod(taskViews, "registerTaskView", controller);
                        repositoryRegistered = true;
                    }
                } else {
                    if (!repositoryRegistered) {
                        XposedHelpers.callMethod(taskViews, "registerTaskView", controller);
                        repositoryRegistered = true;
                    }
                    releaseOriginal();
                }
            }
            Object surface = canonical();
            XposedHelpers.callMethod(tx, "reparent", surface, side ? sideParent : displayParent);
            XposedHelpers.callMethod(tx, "setMatrix", surface, side ? scale : 1f, 0f, 0f, side ? scale : 1f);
            XposedHelpers.callMethod(tx, "setPosition", surface, 0f, 0f);
            XposedHelpers.callMethod(tx, "setWindowCrop", surface, side ? new Rect(logical) : null);
            XposedHelpers.callMethod(tx, "setAlpha", surface, 1f);
            XposedHelpers.callMethod(tx, side ? "show" : "hide", sideParent);
            XposedHelpers.callMethod(tx, "show", surface);
            if (finishWct != null) set(finishWct, "setHidden", false);
        }
    }
}
