package com.jiusis.mistep;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** Equivalent operations for the inspected OS3 Shell, retaining its existing organizer. */
final class ShellCompat {
    static volatile Object rootTaskDisplayArea;

    private ShellCompat() {}

    static boolean hasMethod(Class<?> type, String name, int count) {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            for (Method method : c.getDeclaredMethods())
                if (method.getName().equals(name) && method.getParameterCount() == count) return true;
        return false;
    }

    static boolean hasMethod(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { c.getDeclaredMethod(name, parameters); return true; }
            catch (NoSuchMethodException absent) { }
        }
        return false;
    }

    static void loadConstraints(Object set, Object context, int xml) {
        Object[] args = {context, xml};
        Method load = method(set, "load", args);
        if (load == null) {
            args = new Object[]{xml, context};
            load = method(set, "load", args);
        }
        if (load == null) throw new IllegalStateException("Native constraint loader unavailable");
        invoke(load, set, args);
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == float.class) return Float.class;
        if (type == long.class) return Long.class;
        return type;
    }

    private static Method method(Object receiver, String name, Object[] args) {
        for (Class<?> c = receiver.getClass(); c != null; c = c.getSuperclass()) {
            for (Method candidate : c.getDeclaredMethods()) {
                if (!candidate.getName().equals(name) || candidate.getParameterCount() != args.length) continue;
                Class<?>[] types = candidate.getParameterTypes();
                boolean matches = true;
                for (int i = 0; i < types.length; i++)
                    if (args[i] == null ? types[i].isPrimitive() : !boxed(types[i]).isInstance(args[i])) matches = false;
                if (matches) { candidate.setAccessible(true); return candidate; }
            }
        }
        return null;
    }

    private static Object invoke(Method method, Object receiver, Object[] args) {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException(method.getName(), cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(method.getName(), error);
        }
    }

    static Object rootArea(Object organizer) {
        try { return XposedHelpers.getObjectField(organizer, "mRootTaskDisplayAreaOrganizer"); }
        catch (NoSuchFieldError absent) {
            Object captured = rootTaskDisplayArea;
            if (captured == null) throw new IllegalStateException("Native root task display area not captured", absent);
            return captured;
        }
    }

    static Object call(Object receiver, String name, Object... args) {
        Method nativeMethod = method(receiver, name, args);
        if (nativeMethod != null) {
            Object result = invoke(nativeMethod, receiver, args);
            if (!name.equals("prepareOpenAnimation") || nativeMethod.getReturnType() != void.class) return result;
            Object controller = args[0], task = args[4];
            if (!XposedHelpers.getObjectField(task, "token").equals(XposedHelpers.getObjectField(controller, "mTaskToken"))
                    || !Boolean.TRUE.equals(XposedHelpers.getObjectField(controller, "mSurfaceCreated"))
                    || XposedHelpers.getObjectField(controller, "mTaskInfo") != task)
                throw new IllegalStateException("Native void prepareOpenAnimation did not bind the live task");
            return true;
        }
        switch (name) {
            case "startRootTask": {
                // The OS4 helper is absent in OS3. Keep its native prepare/open
                // and CHANGE transition sequence with a locally owned transaction.
                Object tx;
                try { tx = construct(Class.forName("android.view.SurfaceControl$Transaction", false,
                        receiver.getClass().getClassLoader())); }
                catch (ClassNotFoundException error) { throw new IllegalStateException(error); }
                try {
                    call(receiver, "prepareOpenAnimation", args[0], true, tx, null, args[1], args[2], args[3]);
                    call(receiver, "updateVisibilityState", args[0], true);
                    XposedHelpers.callMethod(tx, "apply");
                    XposedHelpers.callMethod(XposedHelpers.getObjectField(receiver, "mTransitions"),
                            "startTransition", 6, args[3], null);
                    return null;
                } finally { XposedHelpers.callMethod(tx, "close"); }
            }
            case "updateVisibilityState": {
                Object states = XposedHelpers.getObjectField(receiver, "mTaskViews");
                Object state = XposedHelpers.callMethod(states, "get", args[0]);
                if (state != null) XposedHelpers.setBooleanField(state, "mVisible", (Boolean) args[1]);
                return null;
            }
            case "reapplyMagnificationSpec": {
                // OS3's no-argument replay writes exactly this spec to its pending
                // transaction. Replay into the caller's finish transaction instead.
                Object spec = XposedHelpers.getObjectField(receiver, "mMagnificationSpec");
                if (spec != null) XposedHelpers.callMethod(receiver, "applyMagnificationSpec", args[0], spec);
                return null;
            }
            case "getDisplayAreaLeash":
                return XposedHelpers.callMethod(XposedHelpers.getObjectField(receiver, "mLeashes"), "get", args[0]);
            case "addListenerForTaskId":
                addListener(receiver, args[0], (Integer) args[1]); return null;
            case "removeListener":
                removeListener(receiver, args[0]); return null;
            case "updateTaskListenerIfNeeded":
                return transfer(args[0], args[1], args[2], args[3]);
            case "lambda$moveTaskViewToFullscreen$5":
                enqueueFullscreen(receiver, args[0], args[1]); return null;
            default: throw new IllegalStateException("Unsupported native Shell operation: " + name);
        }
    }

    private static boolean transfer(Object task, Object leash, Object oldOwner, Object newOwner) {
        if (oldOwner == newOwner) return false;
        if (oldOwner != null) XposedHelpers.callMethod(oldOwner, "onTaskVanished", task);
        if (newOwner != null) XposedHelpers.callMethod(newOwner, "onTaskAppeared", task, leash);
        return true;
    }

    private static void addListener(Object organizer, Object listener, int id) {
        if (id < 0) throw new IllegalArgumentException("Only exact task identities may be adopted");
        synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
            Object listeners = XposedHelpers.getObjectField(organizer, "mTaskListeners");
            if (XposedHelpers.callMethod(listeners, "get", id) != null)
                throw new IllegalArgumentException("Task already has an explicit listener: " + id);
            Object appeared = XposedHelpers.callMethod(XposedHelpers.getObjectField(organizer, "mTasks"), "get", id);
            if (appeared == null) throw new IllegalArgumentException("Task disappeared: " + id);
            Object task = XposedHelpers.callMethod(appeared, "getTaskInfo");
            Object old = XposedHelpers.callMethod(organizer, "getTaskListener", task, false);
            XposedHelpers.callMethod(listeners, "put", id, listener);
            transfer(task, XposedHelpers.callMethod(appeared, "getLeash"), old, listener);
        }
    }

    private static void removeListener(Object organizer, Object listener) {
        synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
            Object listeners = XposedHelpers.getObjectField(organizer, "mTaskListeners");
            Object tasks = XposedHelpers.getObjectField(organizer, "mTasks");
            List<Object> associated = new ArrayList<>();
            for (int i = (Integer) XposedHelpers.callMethod(tasks, "size") - 1; i >= 0; i--) {
                Object appeared = XposedHelpers.callMethod(tasks, "valueAt", i);
                Object task = XposedHelpers.callMethod(appeared, "getTaskInfo");
                if (XposedHelpers.callMethod(organizer, "getTaskListener", task, false) == listener) associated.add(appeared);
            }
            // Validate the whole set before changing any registration.
            for (int i = (Integer) XposedHelpers.callMethod(listeners, "size") - 1; i >= 0; i--)
                if (XposedHelpers.callMethod(listeners, "valueAt", i) == listener
                        && (Integer) XposedHelpers.callMethod(listeners, "keyAt", i) < 0)
                    throw new IllegalStateException("Refuse to remove a global task listener");
            boolean removed = false;
            for (int i = (Integer) XposedHelpers.callMethod(listeners, "size") - 1; i >= 0; i--) {
                if (XposedHelpers.callMethod(listeners, "valueAt", i) != listener) continue;
                int key = (Integer) XposedHelpers.callMethod(listeners, "keyAt", i);
                if (key < 0) throw new IllegalStateException("Refuse to remove a global task listener");
                XposedHelpers.callMethod(listeners, "removeAt", i); removed = true;
            }
            if (!removed) return;
            for (Object appeared : associated) {
                Object task = XposedHelpers.callMethod(appeared, "getTaskInfo");
                Object next = XposedHelpers.callMethod(organizer, "getTaskListener", task, false);
                transfer(task, XposedHelpers.callMethod(appeared, "getLeash"), null, next);
            }
        }
    }

    static Object transaction(Object organizer, Object transaction, String name, Object token, Object value) {
        if (hasMethod(transaction.getClass(), name, 2)) return call(transaction, name, token, value);
        if (name.equals("setInterceptBackPressedOnTaskRoot")) {
            call(organizer, name, token, value); return transaction;
        }
        // OS3 has no persistent per-task PIP-disable/exclude flags. A false reset on a
        // task proven to be an ordinary non-bubble root therefore needs no inverse.
        if (Boolean.FALSE.equals(value)
                && (name.equals("setDisablePip") || name.equals("setTaskForceExcludedFromRecents"))) return transaction;
        throw new IllegalStateException("Unsupported WindowContainerTransaction operation: " + name);
    }


    static Object construct(Class<?> type, Object... args) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length != args.length) continue;
            boolean matches = true;
            for (int i = 0; i < types.length; i++)
                if (args[i] == null ? types[i].isPrimitive() : !boxed(types[i]).isInstance(args[i])) matches = false;
            if (!matches) continue;
            try { constructor.setAccessible(true); return constructor.newInstance(args); }
            catch (InvocationTargetException error) { throw new IllegalStateException(type.getName(), error.getCause()); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(type.getName(), error); }
        }
        throw new IllegalStateException("Native constructor unavailable: " + type.getName());
    }

    static void createTaskView(Object factory, Object context, java.util.concurrent.Executor callback,
            java.util.function.Consumer<Object> consumer) {
        Method nativeMethod = method(factory, "create", new Object[]{context, callback, consumer});
        if (nativeMethod != null) { invoke(nativeMethod, factory, new Object[]{context, callback, consumer}); return; }
        // Exact inlined OS3 factory body, reusing all four native dependencies.
        Object owner = XposedHelpers.getObjectField(factory, "this$0");
        java.util.concurrent.Executor shell = (java.util.concurrent.Executor) XposedHelpers.getObjectField(owner, "mShellExecutor");
        shell.execute(() -> {
            try {
                ClassLoader loader = factory.getClass().getClassLoader();
                Object transitions = XposedHelpers.getObjectField(owner, "mTaskViewController");
                Object controller = construct(Class.forName("com.android.wm.shell.taskview.TaskViewTaskController", false, loader),
                        context, XposedHelpers.getObjectField(owner, "mTaskOrganizer"), transitions,
                        XposedHelpers.getObjectField(owner, "mSyncQueue"));
                Object view = construct(Class.forName("com.android.wm.shell.taskview.TaskView", false, loader),
                        context, transitions, controller);
                callback.execute(() -> consumer.accept(view));
            } catch (ClassNotFoundException error) { throw new IllegalStateException("Native task view unavailable", error); }
        });
    }

    private static void enqueueFullscreen(Object taskViews, Object wct, Object controller) {
        try {
            Class<?> type = Class.forName(taskViews.getClass().getName() + "$PendingTransition", false,
                    taskViews.getClass().getClassLoader());
            Constructor<?> constructor = null;
            for (Constructor<?> candidate : type.getDeclaredConstructors())
                if (candidate.getParameterCount() == 4) constructor = candidate;
            if (constructor == null) throw new IllegalStateException("Native pending transition constructor missing");
            constructor.setAccessible(true);
            Object pending = constructor.newInstance(6, wct, controller, null);
            @SuppressWarnings("unchecked") List<Object> queue = (List<Object>) XposedHelpers.getObjectField(taskViews, "mPending");
            queue.add(pending);
            XposedHelpers.callMethod(taskViews, "startNextTransition");
            Object task = XposedHelpers.getObjectField(controller, "mTaskInfo");
            if (task != null) XposedHelpers.callMethod(controller, "notifyTaskRemovalStarted", task);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Native fullscreen transition unavailable", error);
        }
    }
}
