package com.jiusis.mistep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** SystemUI boot warmup. No host windows, tasks, magnification, activity or service launches. */
final class MiStepWarmup {
    private static final Object LOCK = new Object();
    private static final String USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ScheduledExecutorService WORK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "MiStepBootWarmup");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<PendingApps> pendingApps = new ArrayList<>();
    private static List<QuickApp> apps = Collections.emptyList();
    private static boolean appsLoaded, wallpaperPrepared;
    private static final long APPS_REFRESH_MILLIS = 10L * 60L * 1000L;
    private static long appsPreparedAt;
    private static int cacheUser = -1;
    private static long generation;
    private static Bitmap wallpaper;
    private static int wallpaperWidth, wallpaperHeight;
    private static boolean started, classesPrepared;
    private static ClassLoader loader;
    private static ScheduledFuture<?> scheduled;
    private static int failuresRemaining = 3;

    private MiStepWarmup() {}

    /** Install at SystemUIService.onCreate; first work waits 12s and for user unlock. */
    static void start(Context context, ClassLoader classLoader) {
        if (context == null) return;
        MAIN.post(() -> {
            synchronized (LOCK) {
                if (started) return;
                started = true;
                loader = classLoader;
            }
            BroadcastReceiver userEvents = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    String action = intent.getAction();
                    if (USER_SWITCHED.equals(action) || Intent.ACTION_WALLPAPER_CHANGED.equals(action))
                        invalidate();
                    schedule(context, 800L);
                }
            };
            IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
            userFilter.addAction(Intent.ACTION_USER_PRESENT);
            userFilter.addAction(USER_SWITCHED);
            userFilter.addAction(Intent.ACTION_WALLPAPER_CHANGED);
            try {
                context.registerReceiver(userEvents, userFilter, Context.RECEIVER_NOT_EXPORTED);
            } catch (Throwable error) { failed("user receiver", error); }
            BroadcastReceiver contentEvents = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    invalidate();
                    schedule(context, 1200L);
                }
            };
            IntentFilter packageFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packageFilter.addDataScheme("package");
            try {
                context.registerReceiver(contentEvents, packageFilter, Context.RECEIVER_NOT_EXPORTED);
            } catch (Throwable error) { failed("package receiver", error); }
            schedule(context, 12000L);
        });
    }

    /** Cache only; a cold open never enumerates packages or loads icons on its UI thread. */
    static List<QuickApp> quickApps(Context context) {
        int user = currentUser();
        if (user < 0) return Collections.emptyList();
        synchronized (LOCK) {
            return cacheUser == user && appsLoaded ? apps : Collections.emptyList();
        }
    }

    /** One main-thread delivery. A missing boot cache is populated on the worker. */
    static void whenQuickAppsReady(Context context, Consumer<List<QuickApp>> ready) {
        if (context == null || ready == null) return;
        int user = currentUser();
        if (user < 0) return;
        List<QuickApp> cached;
        synchronized (LOCK) {
            cached = cacheUser == user && appsLoaded
                    && SystemClock.elapsedRealtime() - appsPreparedAt < APPS_REFRESH_MILLIS ? apps : null;
            if (cached == null) pendingApps.add(new PendingApps(user, ready));
        }
        if (cached != null) deliver(context, new PendingApps(user, ready), cached);
        else schedule(context, 0L);
    }

    /** Call only on the host's wallpaper worker. A hit transfers bitmap ownership to the host. */
    static Bitmap wallpaper(Context context, int width, int height) throws Exception {
        int user = currentUser();
        if (user < 0) return null;
        synchronized (LOCK) {
            if (cacheUser == user && wallpaper != null && !wallpaper.isRecycled()
                    && wallpaperWidth == width && wallpaperHeight == height) {
                Bitmap ready = wallpaper;
                wallpaper = null;
                return ready;
            }
        }
        return HyperOsStyle.blurredWallpaper(userContext(context, user), width, height);
    }

    private static void schedule(Context context, long delayMillis) {
        synchronized (LOCK) {
            if (scheduled != null) scheduled.cancel(false);
            scheduled = WORK.schedule(() -> prepare(context), delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private static void prepare(Context base) {
        try {
            preloadClasses();
            int user = currentUser();
            if (user < 0) return;
            Context context = userContext(base, user);
            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (userManager == null || !userManager.isUserUnlocked()) return;
            long request;
            boolean needApps, needWallpaper;
            synchronized (LOCK) {
                request = generation;
                needApps = cacheUser != user || !appsLoaded
                        || SystemClock.elapsedRealtime() - appsPreparedAt >= APPS_REFRESH_MILLIS;
                needWallpaper = cacheUser != user || !wallpaperPrepared;
            }
            List<QuickApp> loaded = needApps
                    ? Collections.unmodifiableList(new ArrayList<>(QuickApp.load(context))) : null;
            List<PendingApps> callbacks = new ArrayList<>();
            List<QuickApp> ready;
            int observedUser = currentUser();
            synchronized (LOCK) {
                if (request != generation || user != observedUser) return;
                cacheUser = user;
                if (loaded != null) {
                    apps = loaded;
                    appsLoaded = true;
                    appsPreparedAt = SystemClock.elapsedRealtime();
                }
                ready = apps;
                if (appsLoaded) {
                    for (int i = pendingApps.size() - 1; i >= 0; i--) {
                        PendingApps pending = pendingApps.get(i);
                        if (pending.user == user) callbacks.add(pendingApps.remove(i));
                    }
                }
            }
            // Shortcut delivery never waits for the heavier wallpaper blur.
            for (PendingApps pending : callbacks) deliver(base, pending, ready, request);
            Bitmap blurred = null;
            int width = 0, height = 0;
            if (needWallpaper) {
                WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (windows != null) {
                    Point size = new Point();
                    windows.getDefaultDisplay().getRealSize(size);
                    width = size.x;
                    height = size.y;
                    if (width > 0 && height > 0 && (long) width * height <= 5000000L) {
                        blurred = HyperOsStyle.blurredWallpaper(context, width, height);
                    }
                }
            }
            observedUser = currentUser();
            synchronized (LOCK) {
                if (request != generation || user != observedUser) {
                    if (blurred != null && !blurred.isRecycled()) blurred.recycle();
                    return;
                }
                if (blurred != null) {
                    if (wallpaper != null && !wallpaper.isRecycled()) wallpaper.recycle();
                    wallpaper = blurred;
                    wallpaperPrepared = true;
                    wallpaperWidth = width;
                    wallpaperHeight = height;
                }
            }
        } catch (Throwable error) { failed("background cache", error); }
    }

    private static void deliver(Context context, PendingApps pending, List<QuickApp> ready) {
        long request;
        synchronized (LOCK) { request = generation; }
        deliver(context, pending, ready, request);
    }

    private static void deliver(Context context, PendingApps pending, List<QuickApp> ready, long request) {
        MAIN.post(() -> {
            if (pending.user != currentUser()) return;
            boolean stale;
            synchronized (LOCK) { stale = request != generation; }
            if (stale) {
                whenQuickAppsReady(context, pending.callback);
                return;
            }
            try {
                UserManager users = (UserManager) userContext(context, pending.user)
                        .getSystemService(Context.USER_SERVICE);
                if (users != null && users.isUserUnlocked()) pending.callback.accept(ready);
                else synchronized (LOCK) { pendingApps.add(pending); }
            } catch (Throwable error) { failed("shortcut delivery", error); }
        });
    }

    private static void invalidate() {
        int user = currentUser();
        synchronized (LOCK) {
            generation++;
            apps = Collections.emptyList();
            appsLoaded = false;
            appsPreparedAt = 0L;
            wallpaperPrepared = false;
            cacheUser = -1;
            if (wallpaper != null && !wallpaper.isRecycled()) wallpaper.recycle();
            wallpaper = null;
            pendingApps.removeIf(pending -> pending.user != user);
        }
    }

    private static void preloadClasses() {
        if (classesPrepared || loader == null) return;
        classesPrepared = true;
        String[] types = {
            "com.android.wm.shell.taskview.TaskView",
            "com.android.wm.shell.taskview.TaskViewTaskController",
            "com.android.wm.shell.taskview.TaskViewTransitions"
        };
        for (String name : types) {
            try { Class.forName(name, false, loader); }
            catch (Throwable ignored) { /* Capability capture remains the authority at open. */ }
        }
    }

    private static int currentUser() {
        try {
            return (Integer) XposedHelpers.callStaticMethod(android.app.ActivityManager.class,
                    "getCurrentUser");
        } catch (Throwable unavailable) { return -1; }
    }

    private static Context userContext(Context base, int user) {
        if (user < 0) throw new IllegalStateException("current user unavailable");
        try {
            Object handle = XposedHelpers.callStaticMethod(UserHandle.class, "of", user);
            return (Context) XposedHelpers.callMethod(base, "createContextAsUser", handle, 0);
        } catch (Throwable unavailable) {
            throw new IllegalStateException("current user context unavailable", unavailable);
        }
    }

    private static void failed(String operation, Throwable error) {
        synchronized (LOCK) {
            if (failuresRemaining-- <= 0) return;
        }
        android.util.Log.i("MiStepProbe", "boot warmup " + operation + " unavailable: "
                + error.getClass().getSimpleName());
    }

    private static final class PendingApps {
        final int user;
        final Consumer<List<QuickApp>> callback;
        PendingApps(int user, Consumer<List<QuickApp>> callback) {
            this.user = user;
            this.callback = callback;
        }
    }
}
