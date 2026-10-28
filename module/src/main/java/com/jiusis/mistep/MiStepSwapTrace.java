package com.jiusis.mistep;

import android.os.Handler;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Explicitly armed, passive one-swap trace. Never changes a transaction or task owner. */
final class MiStepSwapTrace implements AutoCloseable {
    static final long LIMIT_MS = 6000L;
    static final int LIMIT_EVENTS = 384;
    private final Handler main;
    private final Consumer<String> log;
    private final Consumer<MiStepSwapTrace> onClose;
    private final int[] ids;
    private final Object[] tokens;
    private final Object controller, organizer;
    private final File output;
    private final long started = SystemClock.uptimeMillis();
    private final List<String> rows = new ArrayList<>();
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private final IdentityHashMap<Object, String> surfaces = new IdentityHashMap<>();
    private final Set<Object> transactions = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Runnable timeout = this::close;
    private volatile boolean closed;

    MiStepSwapTrace(Handler main, File directory, Consumer<String> log, int firstId,
            Object firstToken, int secondId, Object secondToken, Object controller,
            Object organizer, String geometry, Consumer<MiStepSwapTrace> onClose) throws Exception {
        this.main = main; this.log = log; this.controller = controller; this.organizer = organizer;
        this.onClose = onClose;
        ids = new int[]{firstId, secondId}; tokens = new Object[]{firstToken, secondToken};
        directory.mkdirs();
        output = new File(directory, "swap-" + System.currentTimeMillis() + ".log");
        note("begin", "A=" + firstId + " B=" + secondId + " " + geometry
                + " javaIdentityIsNotLayerIdentity=true");
        try {
            installTransactionHooks();
            installControllerHooks();
            snapshot("initial");
            main.postDelayed(timeout, LIMIT_MS);
        } catch (Throwable error) {
            close();
            throw new IllegalStateException("passive swap trace unavailable", error);
        }
    }

    synchronized void note(String event, String detail) {
        if (closed) return;
        if (rows.size() >= LIMIT_EVENTS) { main.post(this::close); return; }
        rows.add((SystemClock.uptimeMillis() - started) + " " + event + " " + detail);
    }

    private boolean matches(Object info) {
        if (info == null) return false;
        try {
            int id = XposedHelpers.getIntField(info, "taskId");
            Object token = XposedHelpers.getObjectField(info, "token");
            for (int i = 0; i < ids.length; i++)
                if (ids[i] == id && tokens[i] != null && tokens[i].equals(token)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    synchronized void surface(Object surface, String role) {
        if (surface == null || closed || surfaces.containsKey(surface)) return;
        String nativeId = "unavailable";
        try { nativeId = String.valueOf(XposedHelpers.callMethod(surface, "getLayerId")); }
        catch (Throwable ignored) {}
        surfaces.put(surface, role + "/java@" + Integer.toHexString(System.identityHashCode(surface))
                + "/layer=" + nativeId);
        note("surface", surfaces.get(surface) + " valid=" + valid(surface));
    }

    private synchronized String label(Object surface) { return surfaces.get(surface); }

    private boolean valid(Object surface) {
        try { return Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid")); }
        catch (Throwable ignored) { return false; }
    }

    void snapshot(String stage) {
        if (closed) return;
        try {
            Object lock = XposedHelpers.getObjectField(organizer, "mLock");
            synchronized (lock) {
                Object tasks = XposedHelpers.getObjectField(organizer, "mTasks");
                for (int i = 0; i < ids.length; i++) {
                    if (ids[i] < 0) continue;
                    Object appeared = XposedHelpers.callMethod(tasks, "get", ids[i]);
                    if (appeared == null) { note(stage, "task=" + ids[i] + " canonicalAbsent=true"); continue; }
                    Object info = XposedHelpers.callMethod(appeared, "getTaskInfo");
                    if (!matches(info)) { note(stage, "task=" + ids[i] + " identityChanged=true"); continue; }
                    Object leash = XposedHelpers.callMethod(appeared, "getLeash");
                    surface(leash, "canonical-task=" + ids[i]);
                    Object owner = XposedHelpers.callMethod(organizer, "getTaskListener", info, false);
                    note(stage, "task=" + ids[i] + " mode=" + XposedHelpers.callMethod(info, "getWindowingMode")
                            + " visible=" + XposedHelpers.getBooleanField(info, "isVisible")
                            + " focused=" + XposedHelpers.getBooleanField(info, "isFocused")
                            + " owner=" + identity(owner) + " canonical=" + label(leash));
                }
                Object copy = XposedHelpers.getObjectField(controller, "mTaskLeash");
                Object parent = XposedHelpers.getObjectField(controller, "mSurfaceControl");
                surface(copy, "controller-copy"); surface(parent, "controller-parent");
                note(stage, "controller=" + identity(controller) + " copy=" + label(copy)
                        + " copyValid=" + valid(copy) + " parent=" + label(parent)
                        + " token=" + identity(XposedHelpers.getObjectField(controller, "mTaskToken")));
            }
        } catch (Throwable error) { note(stage, "snapshotUnavailable=" + error.getClass().getSimpleName()); }
    }

    void transition(Object info, Object start, Object finish) {
        if (closed) return;
        try {
            List<?> changes = (List<?>) XposedHelpers.callMethod(info, "getChanges");
            boolean matched = false;
            for (Object change : changes) {
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (!matches(task)) continue;
                matched = true;
                int id = XposedHelpers.getIntField(task, "taskId");
                Object leash = XposedHelpers.callMethod(change, "getLeash");
                surface(leash, "transition-task=" + id);
                note("change", "task=" + id + " leash=" + label(leash)
                        + " mode=" + XposedHelpers.callMethod(change, "getMode")
                        + " startBounds=" + XposedHelpers.callMethod(change, "getStartAbsBounds")
                        + " endBounds=" + XposedHelpers.callMethod(change, "getEndAbsBounds")
                        + " endParentOffset=" + XposedHelpers.callMethod(change, "getEndRelOffset"));
            }
            if (matched) {
                synchronized (this) { transactions.add(start); transactions.add(finish); }
                note("transition-ready", "type=" + XposedHelpers.callMethod(info, "getType")
                        + " start=" + identity(start) + " finish=" + identity(finish));
                snapshot("ready-owners");
            }
        } catch (Throwable error) { note("transition", "unavailable=" + error.getClass().getSimpleName()); }
    }

    private void installTransactionHooks() throws Exception {
        Class<?> type = Class.forName("android.view.SurfaceControl$Transaction");
        for (String name : new String[]{"reparent", "setMatrix", "setPosition", "setWindowCrop",
                "setCrop", "setAlpha", "show", "hide", "setLayer"}) {
            hooks.addAll(XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    synchronized (MiStepSwapTrace.this) {
                        if (closed || p.hasThrowable() || p.args.length == 0
                                || !surfaces.containsKey(p.args[0])) return;
                        transactions.add(p.thisObject);
                        StringBuilder detail = new StringBuilder(identity(p.thisObject))
                                .append(" ").append(surfaces.get(p.args[0]));
                        for (int i = 1; i < p.args.length; i++) {
                            Object value = p.args[i];
                            detail.append(" arg").append(i).append("=");
                            if (value != null && "android.view.SurfaceControl".equals(value.getClass().getName())) {
                                surface(value, "written-parent"); detail.append(surfaces.get(value));
                            } else if (value instanceof float[] values) {
                                detail.append(java.util.Arrays.toString(values));
                            } else if (value instanceof android.graphics.Matrix matrix) {
                                float[] values = new float[9]; matrix.getValues(values);
                                detail.append(java.util.Arrays.toString(values));
                            } else detail.append(String.valueOf(value));
                        }
                        note(name, detail.toString());
                    }
                }
            }));
        }
        for (String name : new String[]{"apply", "merge", "close"}) {
            hooks.addAll(XposedBridge.hookAllMethods(type, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    synchronized (MiStepSwapTrace.this) {
                        if (closed) return;
                        if ("merge".equals(name) && p.args.length > 0 && transactions.contains(p.args[0])) {
                            transactions.add(p.thisObject);
                            note(name, identity(p.thisObject) + " from=" + identity(p.args[0]));
                        } else if (transactions.contains(p.thisObject))
                            note(name, identity(p.thisObject) + " submissionOnly=true");
                        if ("close".equals(name)) transactions.remove(p.thisObject);
                    }
                }
            }));
        }
    }

    private void installControllerHooks() {
        for (String method : new String[]{"prepareOpen", "resetTaskInfo", "onTaskAppeared", "onTaskVanished"}) {
            hooks.addAll(XposedBridge.hookAllMethods(controller.getClass(), method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.thisObject == controller) snapshot("before-" + method);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject == controller) snapshot("after-" + method);
                }
            }));
        }
    }

    private static String identity(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + "@"
                + Integer.toHexString(System.identityHashCode(value));
    }

    @Override public synchronized void close() {
        if (closed) return;
        note("end", "elapsed=" + (SystemClock.uptimeMillis() - started) + " events=" + rows.size());
        closed = true;
        main.removeCallbacks(timeout);
        for (XC_MethodHook.Unhook hook : hooks) {
            try { hook.unhook(); } catch (Throwable ignored) {}
        }
        hooks.clear(); surfaces.clear(); transactions.clear();
        try {
            Files.write(output.toPath(), rows, StandardCharsets.UTF_8);
            log.accept("passive swap trace saved=" + output.getAbsolutePath() + " events=" + rows.size());
        } catch (Throwable error) { log.accept("passive swap trace save failed: " + error); }
        rows.clear();
        onClose.accept(this);
    }
}
