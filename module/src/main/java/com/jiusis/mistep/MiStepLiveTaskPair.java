package com.jiusis.mistep;

import android.graphics.Rect;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Task-specific ownership lease for the inspected 17PM Shell. Retains its original organizer. */
final class MiStepLiveTaskPair {
    private final Object organizer, taskViews, controller, lock, firstToken, secondToken;
    private final int firstId, secondId, userId;
    private final Object displayParent, sideParent;
    private final Rect logical;
    private final Consumer<String> log;
    private Object temporaryFirstOwner, firstCanonical, secondCanonical, finishWct;

    private static Object newWindowContainerTransaction() {
        try {
            return Class.forName("android.window.WindowContainerTransaction")
                    .getDeclaredConstructor().newInstance();
        } catch (Throwable error) {
            throw new IllegalStateException("WindowContainerTransaction unavailable", error);
        }
    }
    private boolean armed, transferred, detachedSecond, retiredSecond, closed;
    private boolean waitingCancel, originalInfoSeen, releaseOriginalWhenSeen;
    private boolean repositoryRegistered = true;

    MiStepLiveTaskPair(Object organizer, Object taskViews, Object controller,
            int firstId, Object firstToken, int secondId, Object secondToken, int userId,
            Object displayParent, Object sideParent, Rect logical, Consumer<String> log) {
        if (firstId < 0 || secondId < 0 || firstId == secondId || firstToken == null
                || secondToken == null || firstToken.equals(secondToken) || logical == null
                || logical.width() <= 0 || logical.height() <= 0)
            throw new IllegalArgumentException("invalid single swap identity");
        this.organizer = organizer; this.taskViews = taskViews; this.controller = controller;
        this.firstId = firstId; this.firstToken = firstToken; this.secondId = secondId;
        this.secondToken = secondToken; this.userId = userId;
        this.displayParent = displayParent; this.sideParent = sideParent;
        this.logical = new Rect(logical); this.log = log;
        lock = XposedHelpers.getObjectField(organizer, "mLock");
    }

    private Object info(int id, Object token, int mode) {
        Object appeared = XposedHelpers.callMethod(XposedHelpers.getObjectField(organizer, "mTasks"), "get", id);
        if (appeared == null) throw new IllegalStateException("canonical task absent " + id);
        Object info = XposedHelpers.callMethod(appeared, "getTaskInfo");
        if (XposedHelpers.getIntField(info, "taskId") != id
                || !token.equals(XposedHelpers.getObjectField(info, "token"))
                || XposedHelpers.getIntField(info, "userId") != userId
                || XposedHelpers.getIntField(info, "displayId") != 0
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getActivityType"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                || (mode >= 0 && !Integer.valueOf(mode).equals(XposedHelpers.callMethod(info, "getWindowingMode"))))
            throw new IllegalStateException("canonical identity/role changed " + id);
        return info;
    }
    private Object canonical(int id) {
        Object appeared = XposedHelpers.callMethod(XposedHelpers.getObjectField(organizer, "mTasks"), "get", id);
        Object leash = appeared == null ? null : XposedHelpers.callMethod(appeared, "getLeash");
        valid(leash); return leash;
    }
    private static void valid(Object surface) {
        if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")))
            throw new IllegalStateException("real task parent/leash unavailable");
    }
    private Object owner(Object info) { return XposedHelpers.callMethod(organizer, "getTaskListener", info, false); }
    private Object listeners() { return XposedHelpers.getObjectField(organizer, "mTaskListeners"); }

    boolean preflight() {
        synchronized (lock) {
            if (closed || armed) return false;
            try {
                valid(displayParent); valid(sideParent);
                if (!Boolean.TRUE.equals(XposedHelpers.getObjectField(controller, "mSurfaceCreated"))) return false;
                Object a = info(firstId, firstToken, 1), b = info(secondId, secondToken, 6);
                Object fallback = XposedHelpers.callMethod(listeners(), "get", -2);
                if (fallback == null || owner(a) != fallback || owner(b) != controller
                        || XposedHelpers.callMethod(listeners(), "get", firstId) != null
                        || XposedHelpers.callMethod(listeners(), "get", secondId) != controller
                        || !secondToken.equals(XposedHelpers.getObjectField(controller, "mTaskToken"))) return false;
                List<?> pending = (List<?>) XposedHelpers.getObjectField(taskViews, "mPending");
                if (!pending.isEmpty()) return false;
                // removeListener(controller) is allowed only when it owns exactly B.
                int size = (Integer) XposedHelpers.callMethod(listeners(), "size");
                for (int i = 0; i < size; i++) {
                    if (XposedHelpers.callMethod(listeners(), "valueAt", i) == controller
                            && !Integer.valueOf(secondId).equals(XposedHelpers.callMethod(listeners(), "keyAt", i))) return false;
                }
                firstCanonical = canonical(firstId); secondCanonical = canonical(secondId);
                return true;
            } catch (Throwable error) { log.accept("live pair preflight refused: " + error); return false; }
        }
    }

    /** Holds A through its mode1 -> mode6 callback; does not create/register a new organizer. */
    void arm(ClassLoader loader) throws Exception {
        synchronized (lock) {
            if (!preflight()) throw new IllegalStateException("live pair preflight changed");
            Class<?> type = Class.forName("com.android.wm.shell.ShellTaskOrganizer$TaskListener", false, loader);
            temporaryFirstOwner = Proxy.newProxyInstance(loader, new Class<?>[]{type}, (p, m, args) -> {
                switch (m.getName()) {
                    case "onNewTaskListenerDetected": return true;
                    case "onTaskInfoChanged":
                        try {
                            if (waitingCancel && args != null && args.length > 0
                                    && firstToken.equals(XposedHelpers.getObjectField(args[0], "token"))
                                    && Integer.valueOf(1).equals(XposedHelpers.callMethod(args[0], "getWindowingMode"))) {
                                originalInfoSeen = true;
                                if (releaseOriginalWhenSeen) releaseOriginalLease();
                            }
                        } catch (Throwable error) { log.accept("late original-role lease handoff: " + error); }
                        return null;
                    case "onTaskVanished":
                        try {
                            if (args != null && args.length > 0
                                    && firstToken.equals(XposedHelpers.getObjectField(args[0], "token"))
                                    && XposedHelpers.callMethod(listeners(), "get", firstId) == temporaryFirstOwner)
                                XposedHelpers.callMethod(organizer, "removeListener", temporaryFirstOwner);
                        } catch (Throwable error) { log.accept("vanished temporary lease cleanup: " + error); }
                        return null;
                    case "attachChildSurfaceToTask":
                        if (Integer.valueOf(firstId).equals(args[0])) XposedHelpers.callMethod(args[1], "setParent", firstCanonical);
                        return null;
                    case "reparentChildSurfaceToTask":
                        if (Integer.valueOf(firstId).equals(args[0])) XposedHelpers.callMethod(args[2], "reparent", args[1], firstCanonical);
                        return null;
                    case "toString": return "MiStepLiveTaskLease(" + firstId + ")";
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == args[0];
                    default:
                        if (m.getReturnType() == boolean.class) return false;
                        if (m.getReturnType() == int.class) return 0;
                        return null;
                }
            });
            armed = true;
            XposedHelpers.callMethod(organizer, "addListenerForTaskId", temporaryFirstOwner, firstId);
            if (owner(info(firstId, firstToken, 1)) != temporaryFirstOwner)
                throw new IllegalStateException("A lease was rejected by native owner");
        }
    }

    Object windowTransaction(Object[] otherSideTokens) throws Exception {
        Object wct = Class.forName("android.window.WindowContainerTransaction").getDeclaredConstructor().newInstance();
        set(wct, "setWindowingMode", secondToken, 0);
        set(wct, "setInterceptBackPressedOnTaskRoot", secondToken, false);
        set(wct, "setTaskForceExcludedFromRecents", secondToken, false);
        set(wct, "setDisablePip", secondToken, false);
        set(wct, "setDisableLaunchAdjacent", secondToken, false);
        set(wct, "setAlwaysOnTop", secondToken, false);
        set(wct, "setFocusable", secondToken, true);
        set(wct, "setBounds", secondToken, new Rect());
        set(wct, "setForceTranslucent", secondToken, false);
        set(wct, "setHidden", secondToken, false);
        set(wct, "setTaskTrimmableFromRecents", secondToken, true);
        set(wct, "reorder", secondToken, true);
        Object caption = XposedHelpers.getObjectField(controller, "mCaptionInsetsOwner");
        if (caption != null) XposedHelpers.callMethod(wct, "removeInsetsSource", secondToken,
                caption, 0, android.view.WindowInsets.Type.captionBar());
        set(wct, "setWindowingMode", firstToken, 6);
        set(wct, "setBounds", firstToken, new Rect(logical));
        set(wct, "setForceTranslucent", firstToken, true);
        set(wct, "setAlwaysOnTop", firstToken, true);
        set(wct, "setFocusable", firstToken, true);
        set(wct, "setHidden", firstToken, false);
        set(wct, "setInterceptBackPressedOnTaskRoot", firstToken, true);
        set(wct, "setTaskTrimmableFromRecents", firstToken, false);
        set(wct, "reorder", firstToken, true);
        if (otherSideTokens != null) for (Object side : otherSideTokens) {
            if (side == null || side.equals(firstToken) || side.equals(secondToken)) continue;
            set(wct, "setForceTranslucent", side, true);
            set(wct, "setAlwaysOnTop", side, true);
            set(wct, "setFocusable", side, true);
            set(wct, "reorder", side, true);
        }
        return wct;
    }

    private static void set(Object wct, String name, Object token, Object value) {
        XposedHelpers.callMethod(wct, name, token, value);
    }

    /** Explicit inverse roles for a pending cancellation; no replacement organizer is registered. */
    Object reverseWindowTransaction() throws Exception {
        waitingCancel = true;
        originalInfoSeen = false;
        Object wct = Class.forName("android.window.WindowContainerTransaction").getDeclaredConstructor().newInstance();
        set(wct, "setWindowingMode", firstToken, 0);
        set(wct, "setBounds", firstToken, new Rect());
        set(wct, "setForceTranslucent", firstToken, false);
        set(wct, "setAlwaysOnTop", firstToken, false);
        set(wct, "setFocusable", firstToken, true);
        set(wct, "setHidden", firstToken, false);
        set(wct, "setInterceptBackPressedOnTaskRoot", firstToken, false);
        set(wct, "setTaskTrimmableFromRecents", firstToken, true);
        set(wct, "reorder", firstToken, true);
        set(wct, "setWindowingMode", secondToken, 6);
        set(wct, "setBounds", secondToken, new Rect(logical));
        set(wct, "setForceTranslucent", secondToken, true);
        set(wct, "setAlwaysOnTop", secondToken, true);
        set(wct, "setFocusable", secondToken, true);
        set(wct, "setHidden", secondToken, false);
        set(wct, "setInterceptBackPressedOnTaskRoot", secondToken, true);
        set(wct, "setTaskTrimmableFromRecents", secondToken, false);
        set(wct, "reorder", secondToken, true);
        Object caption = XposedHelpers.getObjectField(controller, "mCaptionInsetsOwner");
        Object captionFrame = XposedHelpers.getObjectField(controller, "mCaptionInsets");
        if (caption != null && captionFrame instanceof Rect)
            XposedHelpers.callMethod(wct, "addInsetsSource", secondToken, caption, 0,
                    android.view.WindowInsets.Type.captionBar(),
                    android.graphics.Insets.of(0, ((Rect) captionFrame).height(), 0, 0), null, 20);
        return wct;
    }

    boolean originalRoles() {
        synchronized (lock) {
            try {
                info(firstId, firstToken, 1); info(secondId, secondToken, 6);
                return !waitingCancel || originalInfoSeen;
            }
            catch (Throwable ignored) { return false; }
        }
    }

    /** Used only after Ready proved A6/B1; a new canonical A1/B6 cannot be the pre-WCT snapshot. */
    boolean rollbackRolesReady() {
        synchronized (lock) {
            try { info(firstId, firstToken, 1); info(secondId, secondToken, 6); return true; }
            catch (Throwable ignored) { return false; }
        }
    }

    /** Restore the original B controller after a running transition's inverse WCT was confirmed. */
    void restoreOriginal(Object tx, float sideScale) {
        synchronized (lock) {
            Object a = info(firstId, firstToken, 1), b = info(secondId, secondToken, 6);
            Object token = XposedHelpers.getObjectField(controller, "mTaskToken");
            if (firstToken.equals(token)) {
                repositoryRegistered = false;
                XposedHelpers.callMethod(taskViews, "unregisterTaskView", controller);
                try {
                    if (owner(a) != controller) throw new IllegalStateException("rollback A owner changed");
                    XposedHelpers.callMethod(organizer, "removeListener", controller);
                    XposedHelpers.callMethod(controller, "onTaskVanished", a);
                } finally {
                    XposedHelpers.callMethod(taskViews, "registerTaskView", controller);
                    repositoryRegistered = true;
                }
            }
            if (owner(a) == temporaryFirstOwner) XposedHelpers.callMethod(organizer, "removeListener", temporaryFirstOwner);
            token = XposedHelpers.getObjectField(controller, "mTaskToken");
            if (!secondToken.equals(token)) {
                if (token != null || XposedHelpers.callMethod(listeners(), "get", secondId) != null)
                    throw new IllegalStateException("rollback B owner occupied");
                XposedHelpers.callMethod(organizer, "addListenerForTaskId", controller, secondId);
            }
            if (owner(b) != controller) throw new IllegalStateException("rollback B claim rejected");
            if (finishWct == null)
                finishWct = newWindowContainerTransaction();
            // prepareOpen(false) on this ROM may append hidden=true even after onTaskAppeared.
            XposedHelpers.callMethod(taskViews, "prepareOpenAnimation", controller,
                    true, tx, tx, b, canonical(secondId), finishWct);
            set(finishWct, "setHidden", firstToken, false);
            set(finishWct, "setHidden", secondToken, false);
            XposedHelpers.callMethod(taskViews, "updateVisibilityState", controller, true);
            finalSurface(tx, canonical(firstId), displayParent, 1f, false);
            finalSurface(tx, canonical(secondId), sideParent, sideScale, true);
            retiredSecond = false; detachedSecond = false; transferred = false;
            originalInfoSeen = true; closed = true;
        }
    }

    void releaseOriginalLeaseWhenSeen() {
        synchronized (lock) { releaseOriginalWhenSeen = true; if (originalInfoSeen) releaseOriginalLease(); }
    }

    private void releaseOriginalLease() {
        if (!retiredSecond && owner(info(firstId, firstToken, 1)) == temporaryFirstOwner)
            XposedHelpers.callMethod(organizer, "removeListener", temporaryFirstOwner);
    }

    /** Exact Change == canonical layer evidence is rechecked for each submitted pair. */
    MiStepLiveSwapSession.Ready resolve(Object transitionInfo, LiveSwapGeometry.Affine parentToPhysical) {
        synchronized (lock) {
            if (!armed || closed) return null;
            try {
                Object a = null, b = null;
                Object firstChangeInfo = null, secondChangeInfo = null;
                for (Object change : (List<?>) XposedHelpers.callMethod(transitionInfo, "getChanges")) {
                    Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                    if (task == null) continue;
                    Object token = XposedHelpers.getObjectField(task, "token");
                    if (firstToken.equals(token)) {
                        firstChangeInfo = task;
                        a = XposedHelpers.callMethod(change, "getLeash");
                    }
                    if (secondToken.equals(token)) {
                        secondChangeInfo = task;
                        b = XposedHelpers.callMethod(change, "getLeash");
                    }
                }
                if (a == null || b == null || firstChangeInfo == null || secondChangeInfo == null) {
                    log.accept("live pair transition missing A/B Change leash or task info");
                    return null;
                }
                // TransitionInfo is the authoritative role snapshot at this callback. The
                // organizer's cached TaskInfo can still describe the pre-WCT roles for one
                // main-thread turn, which used to reject the animation before frame 0.
                if (!firstToken.equals(XposedHelpers.getObjectField(firstChangeInfo, "token"))
                        || !secondToken.equals(XposedHelpers.getObjectField(secondChangeInfo, "token"))
                        || XposedHelpers.getIntField(firstChangeInfo, "taskId") != firstId
                        || XposedHelpers.getIntField(secondChangeInfo, "taskId") != secondId
                        || XposedHelpers.getIntField(firstChangeInfo, "displayId") != 0
                        || XposedHelpers.getIntField(secondChangeInfo, "displayId") != 0
                        || XposedHelpers.getIntField(firstChangeInfo, "userId") != userId
                        || XposedHelpers.getIntField(secondChangeInfo, "userId") != userId
                        || !Integer.valueOf(6).equals(XposedHelpers.callMethod(firstChangeInfo, "getWindowingMode"))
                        || !Integer.valueOf(1).equals(XposedHelpers.callMethod(secondChangeInfo, "getWindowingMode"))) {
                    log.accept("live pair transition role mismatch A="
                            + XposedHelpers.callMethod(firstChangeInfo, "getWindowingMode")
                            + " B=" + XposedHelpers.callMethod(secondChangeInfo, "getWindowingMode"));
                    return null;
                }
                // Identity/layer checks use canonical surfaces, but do not re-read the old
                // role from the organizer cache during the same callback.
                info(firstId, firstToken, -1);
                info(secondId, secondToken, -1);
                valid(a); valid(b); valid(displayParent);
                if (!XposedHelpers.callMethod(a, "getLayerId").equals(XposedHelpers.callMethod(canonical(firstId), "getLayerId"))
                        || !XposedHelpers.callMethod(b, "getLayerId").equals(XposedHelpers.callMethod(canonical(secondId), "getLayerId"))) return null;
                return new MiStepLiveSwapSession.Ready(a, b, displayParent, 2, 3,
                        parentToPhysical, LiveSwapGeometry.Affine.identity(), LiveSwapGeometry.Affine.identity());
            } catch (Throwable error) { log.accept("live pair resolve refused: " + error); return null; }
        }
    }

    void prepare(Object start, Object finish) throws Exception {
        synchronized (lock) {
            // Role modes are established by the transition callback. During this method the
            // organizer cache may still lag one callback, so identity/owner checks are enough.
            Object a = info(firstId, firstToken, -1), b = info(secondId, secondToken, -1);
            if (!repositoryRegistered) {
                XposedHelpers.callMethod(taskViews, "registerTaskView", controller);
                repositoryRegistered = true;
            }
            if (!retiredSecond) {
                try {
                    if (!detachedSecond) {
                        if (owner(a) != temporaryFirstOwner || owner(b) != controller)
                            throw new IllegalStateException("pair ownership changed before claim");
                        repositoryRegistered = false;
                        XposedHelpers.callMethod(taskViews, "unregisterTaskView", controller);
                        try { XposedHelpers.callMethod(organizer, "removeListener", controller); }
                        finally { detachedSecond = owner(b) != controller; }
                    }
                    // Native removeListener never retires the old copied task handle.
                    try { XposedHelpers.callMethod(controller, "onTaskVanished", b); }
                    finally { retiredSecond = !secondToken.equals(XposedHelpers.getObjectField(controller, "mTaskToken")); }
                } finally {
                    // Even partially failed native retirement must not strand the repository entry.
                    XposedHelpers.callMethod(taskViews, "registerTaskView", controller);
                    repositoryRegistered = true;
                }
            }
            if (!transferred) {
                if (XposedHelpers.getObjectField(controller, "mTaskToken") != null)
                    throw new IllegalStateException("retired controller still occupied");
                // Atomic per-task transfer avoids a mode6 fallback gap between remove/add calls.
                if (XposedHelpers.callMethod(listeners(), "get", firstId) != temporaryFirstOwner)
                    throw new IllegalStateException("A lease mapping changed");
                XposedHelpers.callMethod(listeners(), "put", firstId, controller);
                try {
                    XposedHelpers.callMethod(organizer, "updateTaskListenerIfNeeded", a, canonical(firstId),
                            temporaryFirstOwner, controller);
                } finally { transferred = owner(a) == controller; }
                if (!transferred) throw new IllegalStateException("A native claim rejected");
            }
            if (finishWct == null)
                finishWct = Class.forName("android.window.WindowContainerTransaction").getDeclaredConstructor().newInstance();
            if (!Boolean.TRUE.equals(XposedHelpers.callMethod(taskViews, "prepareOpenAnimation", controller,
                    true, start, finish, a, canonical(firstId), finishWct)))
                throw new IllegalStateException("A task view preparation failed");
        }
    }

    Object finishWindowTransaction() { return finishWct; }

    boolean sideOwned() {
        synchronized (lock) {
            try {
                return owner(info(firstId, firstToken, 6)) == controller
                        && owner(info(secondId, secondToken, 1)) != controller
                        && firstToken.equals(XposedHelpers.getObjectField(controller, "mTaskToken"));
            } catch (Throwable ignored) { return false; }
        }
    }

    /** Append final real-parent operations. Never applies or closes a Shell-owned transaction. */
    void finish(Object tx, float sideScale) {
        synchronized (lock) {
            Object a = info(firstId, firstToken, 6), b = info(secondId, secondToken, 1);
            if (!transferred || owner(a) != controller || owner(b) == controller)
                throw new IllegalStateException("pair ownership changed at finish");
            if (!Boolean.TRUE.equals(XposedHelpers.getObjectField(controller, "mSurfaceCreated")))
                throw new IllegalStateException("side SurfaceView disappeared before finish");
            finalSurface(tx, canonical(secondId), displayParent, 1f, false);
            finalSurface(tx, canonical(firstId), sideParent, sideScale, true);
            if (finishWct != null) set(finishWct, "setHidden", firstToken, false);
            XposedHelpers.callMethod(taskViews, "updateVisibilityState", controller, true);
            closed = true;
        }
    }

    private void finalSurface(Object tx, Object leash, Object parent, float scale, boolean side) {
        valid(leash); valid(parent);
        XposedHelpers.callMethod(tx, "reparent", leash, parent);
        XposedHelpers.callMethod(tx, "setMatrix", leash, scale, 0f, 0f, scale);
        XposedHelpers.callMethod(tx, "setPosition", leash, 0f, 0f);
        XposedHelpers.callMethod(tx, "setAlpha", leash, 1f);
        XposedHelpers.callMethod(tx, "setWindowCrop", leash, side ? new Rect(logical) : null);
        XposedHelpers.callMethod(tx, "show", leash);
    }

    /** Bounded consumed/rejected/start failure cleanup follows the current canonical roles. */
    void recover(Object tx, float sideScale) {
        synchronized (lock) {
            Object a = info(firstId, firstToken, -1), b = info(secondId, secondToken, -1);
            int modeA = (Integer) XposedHelpers.callMethod(a, "getWindowingMode");
            int modeB = (Integer) XposedHelpers.callMethod(b, "getWindowingMode");
            if (modeA == 6 && modeB == 1) {
                if (!transferred || !firstToken.equals(XposedHelpers.getObjectField(controller, "mTaskToken"))) {
                    try { prepare(tx, tx); }
                    catch (Exception error) { throw new IllegalStateException("pair abort claim failed", error); }
                }
                finish(tx, sideScale);
                return;
            }
            if (modeA == 1 && modeB == 6 && !retiredSecond) {
                if (owner(a) == temporaryFirstOwner) XposedHelpers.callMethod(organizer, "removeListener", temporaryFirstOwner);
                finalSurface(tx, canonical(firstId), displayParent, 1f, false);
                finalSurface(tx, canonical(secondId), sideParent, sideScale, true);
                closed = true;
                return;
            }
            if (modeA == 1 && modeB == 6) { restoreOriginal(tx, sideScale); return; }
            throw new IllegalStateException("pair aborted into changed external roles " + modeA + "/" + modeB);
        }
    }
}
