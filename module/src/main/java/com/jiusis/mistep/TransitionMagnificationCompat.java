package com.jiusis.mistep;

import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** Preserve the ROM's own start/finish transaction and participant identities. */
final class TransitionMagnificationCompat {
    private TransitionMagnificationCompat() {}

    static boolean isStart(Object[] args) {
        // OS2's four-argument wrapper invokes the five-argument implementation.
        // Hook only the implementation, once; OS3/4 add a sixth argument.
        return args != null && (args.length == 5 || args.length == 6)
                && args[0] instanceof Integer && args[1] instanceof Integer
                && args[2] instanceof List<?> && args[3] != null;
    }

    static Object[] finishDisplays(Object transition, Object[] args) {
        if (args == null || args.length < 2 || args[0] == null || args[1] == null) return null;
        if (args.length == 3) return args[2] instanceof Object[] ? (Object[]) args[2] : null;
        if (args.length != 2 || transition == null) return null;
        Object targets = XposedHelpers.getObjectField(transition, "mTargets");
        if (!(targets instanceof List<?>)) return null;
        List<Object> displays = new ArrayList<>();
        for (Object target : (List<?>) targets) {
            Object container = XposedHelpers.getObjectField(target, "mContainer");
            if (container == null) return null;
            Object display = XposedHelpers.callMethod(container, "getDisplayContent");
            if (display != null && !displays.contains(display)) displays.add(display);
        }
        return displays.toArray();
    }
}
