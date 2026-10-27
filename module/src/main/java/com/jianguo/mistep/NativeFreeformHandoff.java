package com.jianguo.mistep;

import de.robv.android.xposed.XposedHelpers;

/** Keep the old visible main transform until the native transition takes ownership. */
final class NativeFreeformHandoff {
    private static final java.util.Map<Object, Boolean> prepared = new java.util.WeakHashMap<>();
    private NativeFreeformHandoff() {}

    static boolean keepMainTransform(Object task, boolean finalSnapshot) {
        if (task == null || finalSnapshot) return false;
        Object controller = XposedHelpers.getObjectField(task, "mTransitionController");
        if (controller == null) return false;
        if (wasVisibleFullscreen(XposedHelpers.getObjectField(controller,
                "mCollectingTransition"), task)) return true;
        for (Object transition : (Iterable<?>) XposedHelpers.getObjectField(controller,
                "mPlayingTransitions"))
            if (wasVisibleFullscreen(transition, task)) return true;
        return false;
    }

    static boolean keepNativeTransform(Object task, boolean finalSnapshot) {
        if (task == null || finalSnapshot) return false;
        Object controller = XposedHelpers.getObjectField(task, "mTransitionController");
        if (controller == null) return false;
        if (wasVisibleNative(XposedHelpers.getObjectField(controller,
                "mCollectingTransition"), task)) return true;
        for (Object transition : (Iterable<?>) XposedHelpers.getObjectField(controller,
                "mPlayingTransitions")) if (wasVisibleNative(transition, task)) return true;
        return false;
    }

    private static boolean wasVisibleNative(Object transition, Object task) {
        if (transition == null || prepared.containsKey(transition)
                || XposedHelpers.getIntField(transition, "mType") != 11108) return false;
        Object change = XposedHelpers.callMethod(
                XposedHelpers.getObjectField(transition, "mChanges"), "get", task);
        return change != null && XposedHelpers.getIntField(change, "mWindowingMode") == 5
                && XposedHelpers.getIntField(change, "mDisplayId") == 0
                && Boolean.TRUE.equals(XposedHelpers.getObjectField(change, "mVisible"));
    }

    static boolean markNativePrepared(Object task) {
        Object controller = XposedHelpers.getObjectField(task, "mTransitionController");
        Object collecting = XposedHelpers.getObjectField(controller, "mCollectingTransition");
        if (wasVisibleNative(collecting, task)) {
            prepared.put(collecting, Boolean.TRUE); return true;
        }
        for (Object transition : (Iterable<?>) XposedHelpers.getObjectField(controller,
                "mPlayingTransitions")) if (wasVisibleNative(transition, task)) {
            prepared.put(transition, Boolean.TRUE); return true;
        }
        return false;
    }

    private static boolean wasVisibleFullscreen(Object transition, Object task) {
        if (transition == null || prepared.containsKey(transition)) return false;
        Object changes = XposedHelpers.getObjectField(transition, "mChanges");
        Object change = XposedHelpers.callMethod(changes, "get", task);
        return change != null
                && XposedHelpers.getIntField(change, "mWindowingMode") == 1
                && XposedHelpers.getIntField(change, "mDisplayId") == 0
                && Boolean.TRUE.equals(XposedHelpers.getObjectField(change, "mVisible"));
    }

    static boolean markPrepared(Object task) {
        Object controller = XposedHelpers.getObjectField(task, "mTransitionController");
        Object collecting = XposedHelpers.getObjectField(controller, "mCollectingTransition");
        if (wasVisibleFullscreen(collecting, task)) {
            prepared.put(collecting, Boolean.TRUE); return true;
        }
        for (Object transition : (Iterable<?>) XposedHelpers.getObjectField(controller,
                "mPlayingTransitions")) {
            if (wasVisibleFullscreen(transition, task)) {
                prepared.put(transition, Boolean.TRUE); return true;
            }
        }
        return false;
    }
}
