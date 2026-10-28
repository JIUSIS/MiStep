package com.jiusis.mistep;

/** Native windows and their gesture monitors share the main region's WM transform. */
final class NativeFreeformPolicy {
    static String gestureKind(int displayId, String name, int uid, String[] packages) {
        if (displayId != 0) return null;
        if ("[Gesture Monitor] MultiTaskSwitch".equals(name))
            return uid == 1000 || uid >= 10000 && packages != null
                    && java.util.Arrays.asList(packages).contains("com.android.systemui")
                    ? "native-freeform" : null;
        if ("[Gesture Monitor] swipe-up".equals(name))
            return packages != null && java.util.Arrays.asList(packages).contains("com.miui.home")
                    ? "swipe-up" : null;
        if ("[Gesture Monitor] miui-gesture".equals(name)
                || "[Gesture Monitor] edge-swipe".equals(name)) return "return";
        return null;
    }

    static boolean omitFromTouchExclusion(boolean zoomed, boolean sameDisplay,
            boolean ownedRelativeLayer, boolean currentSurfaceAndAnchor) {
        return zoomed && sameDisplay && ownedRelativeLayer && currentSurfaceAndAnchor;
    }

    static boolean excludeFromZoom(boolean zoomed, boolean defaultDisplay, int windowingMode) {
        return false;
    }

    static float gestureScale(boolean nativeVisible, String kind, float mainScale) {
        return mainScale;
    }
}
