package com.jiusis.mistep;

import android.view.View;
import android.view.inspector.WindowInspector;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Window root notifications on both API36 and API37, without a polling loop. */
final class WindowRootsCompat {
    private static final Map<Consumer<List<View>>, Executor> listeners = new IdentityHashMap<>();
    private static final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private static Method nativeAdd, nativeRemove;
    static {
        try {
            nativeAdd = WindowInspector.class.getMethod("addGlobalWindowViewsListener", Executor.class, Consumer.class);
            nativeRemove = WindowInspector.class.getMethod("removeGlobalWindowViewsListener", Consumer.class);
        } catch (NoSuchMethodException absent) { nativeAdd = nativeRemove = null; }
    }
    private WindowRootsCompat() {}

    static synchronized void add(Executor executor, Consumer<List<View>> listener) {
        if (nativeAdd != null) {
            try { nativeAdd.invoke(null, executor, listener); return; }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        if (hooks.isEmpty()) {
            try {
                Class<?> manager = Class.forName("android.view.WindowManagerGlobal");
                XC_MethodHook changed = new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!p.hasThrowable()) dispatch();
                    }
                };
                hooks.addAll(XposedBridge.hookAllMethods(manager, "addView", changed));
                hooks.addAll(XposedBridge.hookAllMethods(manager, "doRemoveView", changed));
                if (hooks.isEmpty()) throw new IllegalStateException("Native window root notifications unavailable");
            } catch (Throwable error) {
                for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
                hooks.clear();
                throw new IllegalStateException(error);
            }
        }
        listeners.put(listener, executor);
    }

    static synchronized void remove(Consumer<List<View>> listener) {
        if (nativeRemove != null) {
            try { nativeRemove.invoke(null, listener); return; }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            hooks.clear();
        }
    }

    private static void dispatch() {
        Map<Consumer<List<View>>, Executor> snapshot;
        synchronized (WindowRootsCompat.class) { snapshot = new IdentityHashMap<>(listeners); }
        for (Map.Entry<Consumer<List<View>>, Executor> entry : snapshot.entrySet()) {
            entry.getValue().execute(() -> {
                synchronized (WindowRootsCompat.class) {
                    if (!listeners.containsKey(entry.getKey())) return;
                }
                entry.getKey().accept(WindowInspector.getGlobalWindowViews());
            });
        }
    }
}
