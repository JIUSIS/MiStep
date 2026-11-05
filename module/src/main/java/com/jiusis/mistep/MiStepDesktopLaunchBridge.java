package com.jiusis.mistep;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import java.util.ArrayList;
import java.util.Arrays;
import de.robv.android.xposed.XposedHelpers;

/** Exact scene-scoped routing from Launcher to the existing live promotion. */
final class MiStepDesktopLaunchBridge {
    static final String ACTION = "com.jiusis.mistep.DESKTOP_SIDE_TASKS";
    static final String LAUNCH = "com.jiusis.mistep.DESKTOP_ICON_LAUNCH";
    static final String PREPARE_TOP = "com.jiusis.mistep.PREPARE_TOP_VIEWPORT";
    private static Registry current;
    private static PendingTop pendingTop;

    private static final class PendingTop {
        final IBinder owner, token;
        final int id, user;
        final android.graphics.Rect bounds;
        final long deadline;
        PendingTop(IBinder owner, IBinder token, int id, int user, android.graphics.Rect bounds) {
            this.owner=owner;this.token=token;this.id=id;this.user=user;
            this.bounds=new android.graphics.Rect(bounds.left,bounds.top,bounds.right,bounds.bottom);
            deadline=SystemClock.uptimeMillis()+5000L;
        }
    }

    /** Protected ordered request, acknowledged before the first TOP WCT is submitted. */
    static boolean prepareTop(Object service, Intent intent) {
        try {
            Bundle row=intent.getBundleExtra("state");
            if(row==null)return false;
            IBinder owner=row.getBinder("owner"), token=row.getBinder("task");
            int id=row.getInt("taskId",-1),user=row.getInt("userId",-1);
            android.graphics.Rect bounds=row.getParcelable("bounds",android.graphics.Rect.class);
            if(owner==null || !owner.isBinderAlive() || token==null || id<0 || user<0
                    || bounds==null || bounds.left!=0 || bounds.top!=0 || bounds.height()<=0
                    || bounds.width()<=bounds.height())return false;
            Class<?> type=XposedHelpers.findClass("com.android.server.wm.WindowContainer",service.getClass().getClassLoader());
            Object task=XposedHelpers.callStaticMethod(type,"fromBinder",token);
            Object info=XposedHelpers.callMethod(task,"getTaskInfo");
            if(XposedHelpers.getIntField(info,"taskId")!=id || XposedHelpers.getIntField(info,"userId")!=user
                    || XposedHelpers.getIntField(info,"displayId")!=0
                    || !Integer.valueOf(1).equals(XposedHelpers.callMethod(task,"getWindowingMode"))
                    || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info,"getActivityType"))
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(info,"hasParentTask")))return false;
            synchronized(MiStepDesktopLaunchBridge.class) {pendingTop=new PendingTop(owner,token,id,user,bounds);}
            return true;
        } catch(Throwable unavailable) {return false;}
    }

    /** Early lease affects only exact requested landscape bounds after the native role change. */
    static boolean ownsTopViewport(Object task) {
        if(ownsTopTask(task))return true;
        PendingTop p;
        synchronized(MiStepDesktopLaunchBridge.class) {p=pendingTop;}
        if(p==null || task==null || !p.owner.isBinderAlive() || SystemClock.uptimeMillis()>p.deadline)return false;
        try {
            Object info=XposedHelpers.callMethod(task,"getTaskInfo");
            Object binder=XposedHelpers.callMethod(XposedHelpers.getObjectField(info,"token"),"asBinder");
            return XposedHelpers.getIntField(info,"taskId")==p.id && XposedHelpers.getIntField(info,"userId")==p.user
                    && XposedHelpers.getIntField(info,"displayId")==0 && p.token.equals(binder)
                    && MiStepSideRoleChange.isSide(task) && p.bounds.equals(XposedHelpers.callMethod(task,"getBounds"));
        } catch(Throwable stale) {return false;}
    }

    private static final class Registry implements IBinder.DeathRecipient {
        final IBinder owner;
        final long sequence;
        final ArrayList<Bundle> rows;
        final ArrayList<Bundle> recentsRows;
        Bundle topInput;
        long lastAt;
        int lastTask = -1;
        Registry(IBinder owner, long sequence, ArrayList<Bundle> rows, ArrayList<Bundle> recentsRows) {
            this.owner = owner; this.sequence = sequence; this.rows = rows; this.recentsRows = recentsRows;
        }
        public void binderDied() {
            synchronized (MiStepDesktopLaunchBridge.class) {
                if (current == this) {
                    current = null;
                    NativeRawInputBridge.updateTop(-1, 1);
                }
            }
        }
    }

    /** Called only from the existing WRITE_SECURE_SETTINGS-protected system receiver. */
    static synchronized boolean update(Intent intent) {
        try {
            Bundle state = intent.getBundleExtra("state");
            if (state == null) return false;
            IBinder owner = state.getBinder("owner");
            long sequence = state.getLong("sequence", -1L);
            ArrayList<Bundle> rows = state.getParcelableArrayList("rows", Bundle.class);
            ArrayList<Bundle> recents = state.getParcelableArrayList("recents", Bundle.class);
            if (recents == null) recents = rows;
            if (owner == null || !owner.isBinderAlive() || sequence < 0
                    || rows == null || rows.size() > 3 || recents == null || recents.size() > 3) return false;
            if (current != null && current.owner.equals(owner) && sequence <= current.sequence) return false;
            Registry next = new Registry(owner, sequence, new ArrayList<>(rows), new ArrayList<>(recents));
            next.topInput = state.getBundle("topInput");
            owner.linkToDeath(next, 0);
            Registry old = current;
            current = next;
            if (old != null) old.owner.unlinkToDeath(old, 0);
            return true;
        } catch (Throwable unavailable) { /* Reject an incomplete registration. */ return false; }
    }

    /** Reuse the protected scene lease; never put TOP in desktop promotion rows. */
    static void refreshTopInput(Object service) {
        Registry state;
        synchronized (MiStepDesktopLaunchBridge.class) { state = current; }
        int uid = -1;
        float scale = 1;
        try {
            synchronized (XposedHelpers.getObjectField(service, "mGlobalLock")) {
                if (state != null && state.owner.isBinderAlive() && state.topInput != null) {
                    uid = MiStepTopRawInput.uid(state.topInput, service.getClass().getClassLoader());
                    if (uid >= 10000) scale = state.topInput.getFloat("scale", 1);
                }
            }
        } catch (Throwable stale) { uid = -1; }
        synchronized (MiStepDesktopLaunchBridge.class) {
            if (current != state || state == null || !state.owner.isBinderAlive()) {
                // A newer receiver owns its own update; do not erase it from an older call.
                if (current != state) return;
                uid = -1;
            }
            NativeRawInputBridge.updateTop(uid, scale);
        }
    }

    /** The existing protected registry also identifies recents exceptions by exact token. */
    static Object recentsLease(Object task) {
        if (task == null) return null;
        Registry state;
        synchronized (MiStepDesktopLaunchBridge.class) { state = current; }
        if (state == null || !state.owner.isBinderAlive()) return null;
        try {
            if (!Integer.valueOf(6).equals(XposedHelpers.callMethod(task, "getWindowingMode"))) return null;
            Object info = XposedHelpers.callMethod(task, "getTaskInfo");
            int id = XposedHelpers.getIntField(info, "taskId");
            int user = XposedHelpers.getIntField(info, "userId");
            int display = XposedHelpers.getIntField(info, "displayId");
            Object token = XposedHelpers.getObjectField(info, "token");
            Object binder = XposedHelpers.callMethod(token, "asBinder");
            if (display != 0) return null;
            for (Bundle row : state.recentsRows) {
                if (row.getInt("taskId", -1) == id && row.getInt("userId", -1) == user
                        && row.getInt("displayId", -1) == display && binder.equals(row.getBinder("task")))
                    return state;
            }
        } catch (Throwable stale) { /* Native filtering is the safe default for stale registrations. */ }
        return null;
    }

    static synchronized boolean recentsLeaseAlive(Object lease) {
        return current == lease && current != null && current.owner.isBinderAlive();
    }

    /** Resolve only the current visible TOP registration for a physical pointer DOWN. */
    static Object topTaskAt(Object display, float x, float y) {
        Registry state;
        synchronized (MiStepDesktopLaunchBridge.class) { state = current; }
        if (state == null || !state.owner.isBinderAlive()
                || !MiStepTopPointerFocus.contains(state.topInput, x, y)) return null;
        try {
            Object task = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                    "com.android.server.wm.WindowContainer", display.getClass().getClassLoader()),
                    "fromBinder", state.topInput.getBinder("task"));
            if (task == null || XposedHelpers.callMethod(task, "getDisplayContent") != display
                    || MiStepTopRawInput.uid(state.topInput, display.getClass().getClassLoader()) < 10000)
                return null;
            synchronized (MiStepDesktopLaunchBridge.class) {
                return current == state && state.owner.isBinderAlive() ? task : null;
            }
        } catch (Throwable stale) { return null; }
    }

    static boolean ownsTopTask(Object task) {
        Registry state;
        synchronized (MiStepDesktopLaunchBridge.class) { state=current; }
        if(state==null || !state.owner.isBinderAlive() || state.topInput==null || task==null)return false;
        try {
            Bundle row=state.topInput;
            Object info=XposedHelpers.callMethod(task,"getTaskInfo");
            Object token=XposedHelpers.getObjectField(info,"token");
            boolean exact=XposedHelpers.getIntField(info,"taskId")==row.getInt("taskId",-1)
                    && XposedHelpers.getIntField(info,"userId")==row.getInt("userId",-1)
                    && XposedHelpers.getIntField(info,"displayId")==0
                    && row.getBinder("task")!=null
                    && row.getBinder("task").equals(XposedHelpers.callMethod(token,"asBinder"));
            synchronized(MiStepDesktopLaunchBridge.class) {return exact && current==state && state.owner.isBinderAlive();}
        } catch(Throwable stale) {return false;}
    }

    static boolean before(Context context, Object atms, Object[] args) {
        return before(context, atms, args, (Integer) XposedHelpers.callStaticMethod(
                UserHandle.class, "getUserId", Binder.getCallingUid()));
    }

    static boolean before(Context context, Object atms, Object[] args, int launchUser) {
        if (context == null || atms == null || args == null) return false;
        if (launchUser < 0) return false;
        try {
            int uid = Binder.getCallingUid();
            String[] callers = context.getPackageManager().getPackagesForUid(uid);
            if (callers == null || !Arrays.asList(callers).contains("com.miui.home")) return false;
            Intent launch = null;
            String caller = null;
            for (Object arg : args) {
                if (arg instanceof Intent) launch = (Intent) arg;
                else if ("com.miui.home".equals(arg)) caller = (String) arg;
            }
            String pkg = DesktopIconLaunchPolicy.packageForLauncherEntry(caller,
                    launch == null ? null : launch.getAction(),
                    launch != null && launch.hasCategory(Intent.CATEGORY_LAUNCHER),
                    launch == null || launch.getComponent() == null ? null
                            : launch.getComponent().getPackageName());
            if (pkg == null) return false;
            Registry state;
            synchronized (MiStepDesktopLaunchBridge.class) { state = current; }
            if (state == null || !state.owner.isBinderAlive()) return false;
            Bundle found = null;
            for (Bundle row : state.rows) {
                if (!pkg.equals(row.getString("package"))
                        || row.getInt("userId", -1) != launchUser
                        || row.getInt("displayId", -1) != 0) continue;
                if (found != null) return false;
                found = row;
            }
            if (found == null) return false;
            IBinder binder = found.getBinder("task");
            int taskId = found.getInt("taskId", -1);
            Object lock = XposedHelpers.getObjectField(atms, "mGlobalLock");
            synchronized (lock) {
                Object root = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
                Object focused = XposedHelpers.callMethod(root, "getTopDisplayFocusedRootTask");
                if (focused == null || !Integer.valueOf(2).equals(
                        XposedHelpers.callMethod(focused, "getActivityType"))) return false;
                Class<?> container = XposedHelpers.findClass(
                        "com.android.server.wm.WindowContainer", atms.getClass().getClassLoader());
                Object task = XposedHelpers.callStaticMethod(container, "fromBinder", binder);
                if (task == null || XposedHelpers.getIntField(task, "mTaskId") != taskId
                        || !Integer.valueOf(6).equals(XposedHelpers.callMethod(task, "getWindowingMode"))) return false;
                Object info = XposedHelpers.callMethod(task, "getTaskInfo");
                Object token = XposedHelpers.getObjectField(info, "token");
                android.content.ComponentName component = (android.content.ComponentName)
                        XposedHelpers.getObjectField(info, "baseActivity");
                if (XposedHelpers.getIntField(info, "displayId") != 0
                        || XposedHelpers.getIntField(info, "userId") != found.getInt("userId")
                        || !binder.equals(XposedHelpers.callMethod(token, "asBinder"))
                        || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                        || component == null || !pkg.equals(component.getPackageName())) return false;
            }
            return dispatch(context, state, found);
        } catch (Throwable unavailable) { /* A rejected binding retains the ordinary native launch. */ return false; }
    }

    /** Recents selects an exact task ID, including when its package has multiple tasks. */
    static boolean beforeRecents(Context context, Object atms, Object[] args) {
        if (context == null || atms == null || args == null || args.length == 0
                || !(args[0] instanceof Integer)) return false;
        try {
            int uid = Binder.getCallingUid(), taskId = (Integer) args[0];
            String[] callers = context.getPackageManager().getPackagesForUid(uid);
            if (callers == null || !Arrays.asList(callers).contains("com.miui.home")) return false;
            Registry state;
            synchronized (MiStepDesktopLaunchBridge.class) { state = current; }
            if (state == null || !state.owner.isBinderAlive()) return false;
            Bundle found = null;
            int user = (Integer) XposedHelpers.callStaticMethod(UserHandle.class, "getUserId", uid);
            for (Bundle row : state.rows) {
                if (row.getInt("taskId", -1) != taskId || row.getInt("userId", -1) != user
                        || row.getInt("displayId", -1) != 0) continue;
                if (found != null) return false;
                found = row;
            }
            if (found == null) return false;
            synchronized (XposedHelpers.getObjectField(atms, "mGlobalLock")) {
                Class<?> container = XposedHelpers.findClass("com.android.server.wm.WindowContainer",
                        atms.getClass().getClassLoader());
                Object task = XposedHelpers.callStaticMethod(container, "fromBinder", found.getBinder("task"));
                if (task == null || XposedHelpers.getIntField(task, "mTaskId") != taskId
                        || recentsLease(task) != state) return false;
                Object info = XposedHelpers.callMethod(task, "getTaskInfo");
                android.content.ComponentName base = (android.content.ComponentName)
                        XposedHelpers.getObjectField(info, "baseActivity");
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                        || base == null || !base.getPackageName().equals(found.getString("package"))) return false;
            }
            // Broadcast outside WMS's global lock. Host rechecks scene, token and binding epoch.
            return dispatch(context, state, found);
        } catch (Throwable stale) { return false; }
    }

    private static boolean dispatch(Context context, Registry state, Bundle found) {
        int taskId = found.getInt("taskId", -1);
        String pkg = found.getString("package");
            long now = SystemClock.uptimeMillis();
            synchronized (MiStepDesktopLaunchBridge.class) {
                if (current != state) return false;
                if (state.lastTask == taskId && now - state.lastAt < 350L) return true;
                state.lastTask = taskId; state.lastAt = now;
            }
            Bundle handoff = new Bundle(found);
            handoff.putBoolean("hostRoute", true);
            handoff.putLong("deadline", now + 1500L);
            Intent signal = new Intent(LAUNCH).setPackage("com.android.systemui")
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("package", pkg).putExtra("before", true)
                    .putExtra("binding", handoff);
            // Enqueue only after exact task/Home validation. The hook reports TASK_TO_FRONT
            // and prevents a competing fullscreen Launcher transition for this owned task.
            try { context.sendBroadcast(signal, android.Manifest.permission.WRITE_SECURE_SETTINGS); }
            catch (RuntimeException failed) {
                synchronized (MiStepDesktopLaunchBridge.class) {
                    if (state.lastTask == taskId && state.lastAt == now) state.lastTask = -1;
                }
                return false;
            }
            return true;
    }
}
