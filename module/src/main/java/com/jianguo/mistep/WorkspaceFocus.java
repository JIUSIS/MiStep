package com.jianguo.mistep;

import java.util.function.Predicate;
import de.robv.android.xposed.XposedHelpers;

/** The native focus scan skips exact side leases without changing their lifecycle flags. */
final class WorkspaceFocus {
    private WorkspaceFocus() {}

    interface Tree {
        int size(Object area);
        Object child(Object area, int index);
        Object area(Object child);
        Object task(Object child);
        boolean eligible(Object task);
        int mode(Object task);
    }

    static Object next(Tree tree, Predicate<Object> side, Object area, Object ignored,
            boolean ignoreMode, int excludedMode) {
        for (int i = tree.size(area) - 1; i >= 0; i--) {
            Object child = tree.child(area, i);
            Object nested = tree.area(child);
            if (nested != null) {
                Object result = next(tree, side, nested, ignored, ignoreMode, excludedMode);
                if (result != null) return result;
                continue;
            }
            Object task = tree.task(child);
            if (task != null && task != ignored && !side.test(task)
                    && (!ignoreMode || tree.mode(task) != excludedMode) && tree.eligible(task)) return task;
        }
        return null;
    }

    static Object next(Object area, Object ignored, boolean ignoreMode, int excludedMode,
            Predicate<Object> side) {
        return next(new Tree() {
            public int size(Object a) { return (Integer) XposedHelpers.callMethod(a, "getChildCount"); }
            public Object child(Object a, int i) { return XposedHelpers.callMethod(a, "getChildAt", i); }
            public Object area(Object c) { return XposedHelpers.callMethod(c, "asTaskDisplayArea"); }
            public Object task(Object c) { return XposedHelpers.callMethod(c, "asTask"); }
            public boolean eligible(Object t) {
                return Boolean.TRUE.equals(XposedHelpers.callMethod(t, "isFocusableAndVisible"));
            }
            public int mode(Object t) { return (Integer) XposedHelpers.callMethod(t, "getWindowingMode"); }
        }, side, area, ignored, ignoreMode, excludedMode);
    }
}
