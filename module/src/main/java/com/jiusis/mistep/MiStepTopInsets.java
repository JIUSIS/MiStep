package com.jiusis.mistep;

import android.graphics.Rect;
import java.util.function.Predicate;
import de.robv.android.xposed.XposedHelpers;

/** A leased TOP viewport has no physical phone bars or display cutout. IME stays native. */
final class MiStepTopInsets {
    private MiStepTopInsets() {}

    static void refresh(Object display, Predicate<Object> lease) {
        java.util.function.Consumer<Object> changed = window -> {
            Object task = XposedHelpers.callMethod(window, "getTask");
            if (MiStepSideRoleChange.isSide(task) && lease.test(task))
                XposedHelpers.callMethod(window, "notifyInsetsChanged");
        };
        XposedHelpers.callMethod(display, "forAllWindows", changed, false);
    }

    static Object adjust(Object window, Object display, Object original, Predicate<Object> lease) {
        if (window == null || display == null || original == null || lease == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return original;
        Object task = XposedHelpers.callMethod(window, "getTask");
        if (!MiStepSideRoleChange.isSide(task) || !lease.test(task)) return original;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if (bounds == null || bounds.left != 0 || bounds.top != 0
                || bounds.height() <= 0 || bounds.width() <= 0) return original;
        Object config = XposedHelpers.callMethod(task, "getConfiguration");
        Object wc = XposedHelpers.getObjectField(config, "windowConfiguration");
        if (!bounds.equals(XposedHelpers.callMethod(wc, "getAppBounds"))) return original;

        // Never mutate the shared display state or a retained IME source.
        Object state = XposedHelpers.newInstance(original.getClass(), original);
        // WindowInsets.Type: status/nav/caption, system/mandatory gestures, tappable, cutout.
        final int physicalTypes = 1 | 2 | 4 | 16 | 32 | 64 | 128;
        for (int i = ((Number) XposedHelpers.callMethod(state, "sourceSize")).intValue() - 1; i >= 0; i--) {
            Object source = XposedHelpers.callMethod(state, "sourceAt", i);
            if ((((Number) XposedHelpers.callMethod(source, "getType")).intValue() & physicalTypes) != 0)
                XposedHelpers.callMethod(state, "removeSource", XposedHelpers.callMethod(source, "getId"));
        }
        ClassLoader loader = original.getClass().getClassLoader();
        XposedHelpers.callMethod(state, "setDisplayFrame", new Rect(bounds.left, bounds.top, bounds.right, bounds.bottom));
        XposedHelpers.callMethod(state, "setDisplayCutout", XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("android.view.DisplayCutout", loader), "NO_CUTOUT"));
        XposedHelpers.callMethod(state, "setRoundedCorners", XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("android.view.RoundedCorners", loader), "NO_ROUNDED_CORNERS"));
        XposedHelpers.callMethod(state, "setRoundedCornerFrame", new Rect(bounds.left, bounds.top, bounds.right, bounds.bottom));
        return state;
    }
}
