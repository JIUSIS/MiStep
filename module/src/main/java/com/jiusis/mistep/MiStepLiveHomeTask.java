package com.jiusis.mistep;

import android.graphics.Rect;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** One existing side task covers an exact Home identity. Home is never in our WCT/surface writes. */
final class MiStepLiveHomeTask {
    private final Object organizer, taskViews, controller, displayParent, sideParent, lock;
    private final LiveTaskSwapDriver.Home request;
    private Object leash, finishWct, transitionApp;
    private boolean retired;
    MiStepLiveHomeTask(Object organizer, Object taskViews, Object controller, Object displayParent,
            Object sideParent, LiveTaskSwapDriver.Home request) {
        this.organizer = organizer; this.taskViews = taskViews; this.controller = controller;
        this.displayParent = displayParent; this.sideParent = sideParent; this.request = request;
        lock = XposedHelpers.getObjectField(organizer, "mLock");
    }
    private Object info(int id, Object token, int mode, int activityType) {
        Object entry = XposedHelpers.callMethod(XposedHelpers.getObjectField(organizer, "mTasks"), "get", id);
        if (entry == null) throw new IllegalStateException("Home/app canonical absent");
        Object task = XposedHelpers.callMethod(entry, "getTaskInfo");
        if (XposedHelpers.getIntField(task, "taskId") != id
                || !token.equals(XposedHelpers.getObjectField(task, "token"))
                || XposedHelpers.getIntField(task, "displayId") != 0
                || XposedHelpers.getIntField(task, "userId") != request.userId
                || (mode >= 0 && !Integer.valueOf(mode).equals(XposedHelpers.callMethod(task, "getWindowingMode")))
                || !Integer.valueOf(activityType).equals(XposedHelpers.callMethod(task, "getActivityType"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(task, "hasParentTask")))
            throw new IllegalStateException("Home/app canonical identity changed");
        return task;
    }
    private Object app(int mode) { return info(request.app.id, request.app.token, mode, 1); }
    private void home() { info(request.homeId, request.homeToken, 1, 2); }
    private Object owner(Object task) { return XposedHelpers.callMethod(organizer, "getTaskListener", task, false); }
    private Object canonical() {
        Object entry = XposedHelpers.callMethod(XposedHelpers.getObjectField(organizer, "mTasks"), "get", request.app.id);
        return XposedHelpers.callMethod(entry, "getLeash");
    }
    boolean preflight() {
        synchronized (lock) {
            try {
                home();
                if (owner(app(6)) != controller || !request.app.token.equals(XposedHelpers.getObjectField(controller, "mTaskToken"))
                        || !Boolean.TRUE.equals(XposedHelpers.getObjectField(controller, "mSurfaceCreated"))) return false;
                List<?> pending = (List<?>) XposedHelpers.getObjectField(taskViews, "mPending");
                if (!pending.isEmpty()) return false;
                leash = canonical();
                return valid(leash) && valid(displayParent) && valid(sideParent) && valid(request.hostRoot);
            } catch (Throwable ignored) { return false; }
        }
    }
    static boolean valid(Object surface) {
        return surface != null && Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"));
    }
    private void set(Object wct, String method, Object value) { XposedHelpers.callMethod(wct, method, request.app.token, value); }
    Object windowTransaction(boolean reverse) throws Exception {
        Object wct = Class.forName("android.window.WindowContainerTransaction").getDeclaredConstructor().newInstance();
        set(wct, "setWindowingMode", reverse ? 6 : 0);
        set(wct, "setBounds", reverse ? new Rect(request.logical) : new Rect());
        set(wct, "setForceTranslucent", reverse);
        set(wct, "setAlwaysOnTop", reverse);
        set(wct, "setFocusable", true); set(wct, "setHidden", false);
        set(wct, "setInterceptBackPressedOnTaskRoot", reverse);
        set(wct, "setTaskTrimmableFromRecents", !reverse);
        if (!reverse) {
            set(wct, "setTaskForceExcludedFromRecents", false);
            set(wct, "setDisablePip", false); set(wct, "setDisableLaunchAdjacent", false);
            Object caption = XposedHelpers.getObjectField(controller, "mCaptionInsetsOwner");
            if (caption != null) XposedHelpers.callMethod(wct, "removeInsetsSource", request.app.token,
                    caption, 0, android.view.WindowInsets.Type.captionBar());
        }
        set(wct, "reorder", true);
        return wct;
    }
    Object resolve(Object transition) {
        synchronized (lock) {
            home(); app(-1);
            Object match = null;
            for (Object change : (List<?>) XposedHelpers.callMethod(transition, "getChanges")) {
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (task == null) continue;
                Object token = XposedHelpers.getObjectField(task, "token");
                // Home visibility bookkeeping belongs to Shell; never mutate its leash or WCT.
                if (request.homeToken.equals(token)) {
                    checkTransitionTask(task, request.homeId, request.homeToken, 1, 2);
                } else if (request.app.token.equals(token)) {
                    if (match != null) throw new IllegalStateException("duplicate Home app change");
                    checkTransitionTask(task, request.app.id, request.app.token, 1, 1);
                    transitionApp = task;
                    match = XposedHelpers.callMethod(change, "getLeash");
                }
            }
            if (!valid(match) || !XposedHelpers.callMethod(match, "getLayerId")
                    .equals(XposedHelpers.callMethod(canonical(), "getLayerId")))
                throw new IllegalStateException("Home app transition leash differs");
            leash = match; return match;
        }
    }
    private void checkTransitionTask(Object task, int id, Object token, int mode, int type) {
        if (XposedHelpers.getIntField(task, "taskId") != id
                || !token.equals(XposedHelpers.getObjectField(task, "token"))
                || XposedHelpers.getIntField(task, "displayId") != 0
                || XposedHelpers.getIntField(task, "userId") != request.userId
                || !Integer.valueOf(mode).equals(XposedHelpers.callMethod(task, "getWindowingMode"))
                || !Integer.valueOf(type).equals(XposedHelpers.callMethod(task, "getActivityType"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(task, "hasParentTask")))
            throw new IllegalStateException("Home/app transition identity or role changed");
    }
    void prepare() {
        synchronized (lock) {
            // TransitionInfo precedes the organizer's role callback on some Shell versions.
            Object b = app(-1); home();
            if (transitionApp == null) throw new IllegalStateException("Home app transition not resolved");
            if (retired) return;
            XposedHelpers.callMethod(taskViews, "unregisterTaskView", controller);
            try {
                if (owner(b) != controller) throw new IllegalStateException("Home app owner changed");
                XposedHelpers.callMethod(organizer, "removeListener", controller);
                try { XposedHelpers.callMethod(controller, "onTaskVanished", transitionApp); }
                finally { retired = !request.app.token.equals(XposedHelpers.getObjectField(controller, "mTaskToken")); }
            } finally { XposedHelpers.callMethod(taskViews, "registerTaskView", controller); }
        }
    }
    boolean roles(boolean original) {
        synchronized (lock) {
            try { home(); app(original ? 6 : 1); return true; }
            catch (Throwable ignored) { return false; }
        }
    }
    void finish(Object tx, boolean original, float sideScale) {
        synchronized (lock) {
            Object b = app(original ? 6 : 1); home();
            if (original) {
                Object occupied = XposedHelpers.getObjectField(controller, "mTaskToken");
                if (!request.app.token.equals(occupied)) {
                    if (occupied != null) throw new IllegalStateException("Home rollback controller occupied");
                    Object listeners = XposedHelpers.getObjectField(organizer, "mTaskListeners");
                    if (XposedHelpers.callMethod(listeners, "get", request.app.id) != null)
                        throw new IllegalStateException("Home rollback listener occupied");
                    XposedHelpers.callMethod(organizer, "addListenerForTaskId", controller, request.app.id);
                }
                if (owner(b) != controller) throw new IllegalStateException("Home rollback rejected");
                try {
                    if (finishWct == null) finishWct = Class.forName("android.window.WindowContainerTransaction").getDeclaredConstructor().newInstance();
                } catch (Exception error) { throw new IllegalStateException(error); }
                XposedHelpers.callMethod(taskViews, "prepareOpenAnimation", controller, true, tx, tx, b, canonical(), finishWct);
                set(finishWct, "setHidden", false);
                XposedHelpers.callMethod(taskViews, "updateVisibilityState", controller, true);
            } else {
                if (!retired) prepare();
                Object fallback = XposedHelpers.callMethod(XposedHelpers.getObjectField(organizer, "mTaskListeners"), "get", -2);
                if (fallback == null || owner(b) != fallback)
                    throw new IllegalStateException("Home fullscreen fallback not confirmed");
            }
            Object current = canonical();
            XposedHelpers.callMethod(tx, "reparent", current, original ? sideParent : displayParent);
            float scale = original ? sideScale : 1f;
            XposedHelpers.callMethod(tx, "setMatrix", current, scale, 0f, 0f, scale);
            XposedHelpers.callMethod(tx, "setPosition", current, 0f, 0f);
            XposedHelpers.callMethod(tx, "setWindowCrop", current, original ? new Rect(request.logical) : null);
            XposedHelpers.callMethod(tx, "setAlpha", current, 1f);
            XposedHelpers.callMethod(tx, "show", current);
            stageContainer(tx, original);
        }
    }
    void stageContainer(Object tx, boolean visible) {
        if (valid(sideParent)) XposedHelpers.callMethod(tx, visible ? "show" : "hide", sideParent);
    }
    Object finishWindowTransaction() { return finishWct; }
    boolean sideOwned() {
        synchronized (lock) {
            try { return owner(app(6)) == controller && request.app.token.equals(XposedHelpers.getObjectField(controller, "mTaskToken")); }
            catch (Throwable ignored) { return false; }
        }
    }
}
