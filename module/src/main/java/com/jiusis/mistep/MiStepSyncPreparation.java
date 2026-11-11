package com.jiusis.mistep;

import android.view.SurfaceControl;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Use the controller's existing draw sync; readiness follows its committed transaction. */
final class MiStepSyncPreparation {
    private static final IdentityHashMap<Object, Object> providerQueues = new IdentityHashMap<>();

    private MiStepSyncPreparation() {}

    /** Borrow only the queue supplied with this organizer by the native TaskView provider. */
    static boolean bindProvider(Object organizer, Object queue) {
        if (!hasType(organizer, "com.android.wm.shell.ShellTaskOrganizer")
                || !hasType(queue, "com.android.wm.shell.common.SyncTransactionQueue")) return false;
        synchronized (providerQueues) { providerQueues.put(organizer, queue); }
        return true;
    }

    private static boolean hasType(Object object, String name) {
        for (Class<?> type = object == null ? null : object.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals(name)) return true;
        return false;
    }

    private static Object queueFor(Object controller) {
        Object queue;
        try { queue = XposedHelpers.getObjectField(controller, "mSyncQueue"); }
        catch (NoSuchFieldError absent) {
            Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
            synchronized (providerQueues) { queue = providerQueues.get(organizer); }
            if (queue == null) throw new IllegalStateException("Native background sync queue is not bound to this organizer", absent);
        }
        if (!hasType(queue, "com.android.wm.shell.common.SyncTransactionQueue"))
            throw new IllegalStateException("Native background sync queue is unavailable");
        return queue;
    }

    static void submit(Object controller, Object wct, Executor ui, Runnable ready,
            Consumer<Throwable> failed) throws ClassNotFoundException {
        Object queue = queueFor(controller);
        ClassLoader loader = queue.getClass().getClassLoader();
        Class<?> callback = Class.forName(
                "com.android.wm.shell.common.SyncTransactionQueue$TransactionRunnable", false, loader);
        AtomicBoolean completed = new AtomicBoolean();
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[] { callback }, (proxy, method, args) -> {
            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
            if ("equals".equals(method.getName())) return args != null && args[0] == proxy;
            if ("toString".equals(method.getName())) return "MiStepBackgroundSync";
            if ("runWithTransaction".equals(method.getName())) {
                try {
                    ((SurfaceControl.Transaction) args[0]).addTransactionCommittedListener(ui, () -> {
                        if (completed.compareAndSet(false, true)) ready.run();
                    });
                } catch (Throwable error) {
                    ui.execute(() -> { if (completed.compareAndSet(false, true)) failed.accept(error); });
                }
            }
            return null;
        });
        Object lock = XposedHelpers.getObjectField(queue, "mQueue");
        synchronized (lock) {
            // runInSync belongs to the current in-flight sync, never a later queued WCT.
            if (XposedHelpers.getObjectField(queue, "mInFlight") != null
                    || !((java.util.List<?>) lock).isEmpty())
                throw new IllegalStateException("Native background sync is busy");
            XposedHelpers.callMethod(queue, "queue", wct);
            XposedHelpers.callMethod(queue, "runInSync", listener);
        }
    }
}
