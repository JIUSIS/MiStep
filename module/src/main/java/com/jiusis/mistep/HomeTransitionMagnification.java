package com.jiusis.mistep;

import de.robv.android.xposed.XposedHelpers;

/** Keep Home and wallpaper in workspace coordinates while Shell owns their leashes. */
final class HomeTransitionMagnification {
    private static Object activeInfo, activeDisplay, activeRoot;
    private static boolean nativeReturn;
    private static final java.util.List<Object> activeOwners = new java.util.ArrayList<>();
    private static final java.util.Map<Object, Object> activeSurfaces = new java.util.IdentityHashMap<>();
    private HomeTransitionMagnification() {}

    static boolean owns(Object container, Object display) {
        // WMS releases this process's Root handle after sending TransitionInfo.
        // Shell still owns the live layer. Only the matching transition finish
        // retires this lease; local SurfaceControl validity cannot do that.
        if (activeInfo == null || activeDisplay != display || container == null)
            return false;
        for (Object cursor = container; cursor != null && cursor != display;
                cursor = XposedHelpers.callMethod(cursor, "getParent")) {
            for (Object owner : activeOwners) if (cursor == owner) return true;
        }
        return false;
    }

    static boolean hasInfo(Object info) { return info != null && info == activeInfo; }
    static boolean activeOn(Object display) { return activeInfo != null && activeDisplay == display; }

    /** A WM pending transaction can predate prepare() and still contain the old scale. */
    static int appendPendingGeometry(Object display, Object transaction) {
        if (nativeReturn || transaction == null || !activeOn(display)
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(activeRoot, "isValid"))) return 0;
        int count = 0;
        for (Object owner : activeOwners) {
            Object surface = activeSurfaces.get(owner);
            if (XposedHelpers.callMethod(owner, "getDisplayContent") != display
                    || XposedHelpers.getObjectField(owner, "mSurfaceControl") != surface
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) continue;
            Object task = XposedHelpers.callMethod(owner, "asTask");
            if (task != null && !Integer.valueOf(1).equals(
                    XposedHelpers.callMethod(task, "getWindowingMode"))) continue;
            Object position = XposedHelpers.getObjectField(owner, "mLastSurfacePosition");
            XposedHelpers.callMethod(transaction, "setMatrix", surface, 1f, 0f, 0f, 1f);
            XposedHelpers.callMethod(transaction, "setPosition", surface,
                    (float) XposedHelpers.getIntField(position, "x"),
                    (float) XposedHelpers.getIntField(position, "y"));
            count++;
        }
        return count;
    }

    static void finish(Object info) {
        if (!hasInfo(info)) return;
        activeInfo = null; activeDisplay = null; activeRoot = null; activeOwners.clear(); activeSurfaces.clear();
        nativeReturn = false;
    }

    static boolean prepare(Object info, java.util.List<?> targets, Object display,
            Object start, float scale, float x, float y) {
        if (activeInfo != null || info == null || targets == null || display == null || start == null
                || !(scale > 0f && scale < 1f)
                || !Float.isFinite(x) || !Float.isFinite(y)
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getRootCount")))
            return false;
        int transitionType = (Integer) XposedHelpers.callMethod(info, "getType");
        // Exact ROM Transitions.TRANSIT_START_RECENTS_TRANSITION, also used for
        // bottom-swipe CLOSE_TO_HOME. Changes below must still identify Home opening.
        Object nativeChange = transitionType == 11108 ? NativeFreeformReturn.change(info) : null;
        if (transitionType != 3 && transitionType != 1021 && transitionType != 6
                && nativeChange == null) return false;
        Object nativeContainer = null;
        if (nativeChange != null) {
            int id = XposedHelpers.getIntField(XposedHelpers.callMethod(nativeChange, "getTaskInfo"), "taskId");
            for (Object target : targets) {
                Object container = XposedHelpers.getObjectField(target, "mContainer");
                Object task = XposedHelpers.callMethod(container, "asTask");
                if (task != null && XposedHelpers.getIntField(task, "mTaskId") == id
                        && XposedHelpers.getIntField(target, "mWindowingMode") == 5
                        && Boolean.TRUE.equals(XposedHelpers.getObjectField(target, "mVisible")))
                    nativeContainer = container;
            }
            if (nativeContainer == null) return false;
        }
        Object collectedTask = null, collectedContainer = null;
        if (transitionType == 6) {
            // A real collection changes one visible fullscreen app into our
            // force-translucent side task. Other multi-window scenes stay excluded.
            for (Object target : targets) {
                Object container = XposedHelpers.getObjectField(target, "mContainer");
                Object task = XposedHelpers.callMethod(container, "asTask");
                if (task == null || !Integer.valueOf(6).equals(
                        XposedHelpers.callMethod(task, "getWindowingMode"))) continue;
                if (collectedTask != null
                        || XposedHelpers.getIntField(target, "mWindowingMode") != 1
                        || !Boolean.TRUE.equals(XposedHelpers.getObjectField(target, "mVisible"))
                        || XposedHelpers.callMethod(container, "getDisplayContent") != display
                        || !Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isForceTranslucent")))
                    return false;
                collectedTask = task;
                collectedContainer = container;
            }
            if (collectedTask == null) return false;
        }
        Object root = XposedHelpers.callMethod(info, "getRoot", 0);
        if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(root, "getDisplayId")))
            return false;
        Object leash = XposedHelpers.callMethod(root, "getLeash");
        if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid")))
            return false;
        boolean home = nativeChange != null, collected = collectedTask == null;
        for (Object change : (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges")) {
            if (!Integer.valueOf(0).equals(XposedHelpers.callMethod(change, "getStartDisplayId"))
                    || !Integer.valueOf(0).equals(XposedHelpers.callMethod(change, "getEndDisplayId"))
                    || !XposedHelpers.callMethod(change, "getStartRotation")
                            .equals(XposedHelpers.callMethod(change, "getEndRotation")))
                return false;
            Object task = XposedHelpers.callMethod(change, "getTaskInfo");
            if (task == null) {
                int flags = (Integer) XposedHelpers.callMethod(change, "getFlags");
                if ((flags & 2) == 0) return false; // FLAG_IS_WALLPAPER
                continue;
            }
            if (XposedHelpers.getIntField(task, "displayId") != 0
                    || Boolean.TRUE.equals(XposedHelpers.callMethod(task, "hasParentTask")))
                return false;
            int type = (Integer) XposedHelpers.callMethod(task, "getActivityType");
            int mode = (Integer) XposedHelpers.callMethod(change, "getMode");
            if (nativeChange != null) continue; // Entire exact scene already checked above.
            if (Integer.valueOf(6).equals(XposedHelpers.callMethod(task, "getWindowingMode"))) {
                if (collectedTask == null || collected || type != 1 || mode != 6
                        || XposedHelpers.getIntField(task, "taskId")
                                != XposedHelpers.getIntField(collectedTask, "mTaskId")) return false;
                collected = true;
                continue;
            }
            if (!Integer.valueOf(1).equals(XposedHelpers.callMethod(task, "getWindowingMode")))
                return false;
            if (type == 2 && mode == 3 && !home) home = true;
            else if (type != 1 || (mode != 2 && mode != 4)) return false;
        }
        if (!home || !collected) return false;
        java.util.List<Object> owners = new java.util.ArrayList<>();
        for (Object target : targets) {
            Object container = XposedHelpers.getObjectField(target, "mContainer");
            Object task = XposedHelpers.callMethod(container, "asTask");
            // Shell moves this app out of Root into the physical Host in the
            // same start transaction. Its scale belongs to the collection animator.
            if (collectedTask != null && task == collectedTask) continue;
            if (XposedHelpers.callMethod(container, "getDisplayContent") != display
                    || (task == null && XposedHelpers.callMethod(container, "asWallpaperToken") == null)
                    || (task != null && !Integer.valueOf(1).equals(
                            XposedHelpers.callMethod(task, "getWindowingMode"))))
                return false;
            owners.add(container);
        }
        if (owners.isEmpty()) return false;
        Object offset = XposedHelpers.callMethod(root, "getOffset");
        float parentScale = 1f, parentX = 0f, parentY = 0f;
        Object rootReference = nativeContainer != null ? nativeContainer : collectedContainer;
        if (rootReference == null) {
            for (Object owner : owners) {
                Object task = XposedHelpers.callMethod(owner, "asTask");
                if (task != null && Integer.valueOf(2).equals(
                        XposedHelpers.callMethod(task, "getActivityType"))) {
                    rootReference = owner;
                    break;
                }
            }
            if (rootReference == null) return false;
        }
        {
            // With a native mini, WM magnifies individual tasks. After its last
            // mini closes, WM can magnify the display area above this Root instead.
            // This also applies to native Recents: its root shares Home's parent.
            // Express the Root in that parent's coordinates, not physical ones.
            for (Object parent = XposedHelpers.callMethod(rootReference, "getParent");
                    parent != null && parent != display;
                    parent = XposedHelpers.callMethod(parent, "getParent")) {
                Object spec = XposedHelpers.getObjectField(parent, "mLastMagnificationSpec");
                if (spec == null) continue;
                float inherited = ((Number) XposedHelpers.getObjectField(spec, "scale")).floatValue();
                float inheritedX = ((Number) XposedHelpers.getObjectField(spec, "offsetX")).floatValue();
                float inheritedY = ((Number) XposedHelpers.getObjectField(spec, "offsetY")).floatValue();
                if (!(inherited > 0f) || !Float.isFinite(inherited)
                        || !Float.isFinite(inheritedX) || !Float.isFinite(inheritedY)) return false;
                parentX = inherited * parentX + inheritedX;
                parentY = inherited * parentY + inheritedY;
                parentScale *= inherited;
            }
            if (!(parentScale > 0f) || !Float.isFinite(parentScale)
                    || !Float.isFinite(parentX) || !Float.isFinite(parentY)) return false;
        }
        float rootScale = scale / parentScale;
        float rootX = (x - parentX) / parentScale + rootScale * XposedHelpers.getIntField(offset, "x");
        float rootY = (y - parentY) / parentScale + rootScale * XposedHelpers.getIntField(offset, "y");
        // Shell writes identity to Home's own task during this animation. Move the
        // workspace transform above it and remove old child specs in the SAME start.
        // Existing finish replay restores normal task magnification as Root is removed.
        for (Object owner : owners) XposedHelpers.callMethod(owner, "clearMagnificationSpec", start);
        XposedHelpers.callMethod(start, "setMatrix", leash, rootScale, 0f, 0f, rootScale);
        XposedHelpers.callMethod(start, "setPosition", leash, rootX, rootY);
        for (Object owner : owners) {
            Object task = XposedHelpers.callMethod(owner, "asTask");
            if (nativeChange != null || task == null || Integer.valueOf(2).equals(
                    XposedHelpers.callMethod(task, "getActivityType"))) {
                activeOwners.add(owner);
                activeSurfaces.put(owner, XposedHelpers.getObjectField(owner, "mSurfaceControl"));
            }
        }
        activeDisplay = display; activeRoot = leash; activeInfo = info;
        nativeReturn = nativeChange != null;
        return true;
    }
}
