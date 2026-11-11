package com.jiusis.mistep;

/** Keep phone portrait behavior while letting a tablet enter in its current direction. */
final class WorkspaceDisplayPolicy {
    private WorkspaceDisplayPolicy() {}

    static boolean isTablet(int width, int height, int densityDpi) {
        return width > 0 && height > 0 && densityDpi > 0
                && Math.min(width, height) * 160L >= 600L * densityDpi;
    }

    static int rotation(boolean tablet, int current) {
        return tablet && current >= 0 && current <= 3 ? current : 0;
    }

    static int width(boolean tablet, int width, int height) {
        return tablet ? width : Math.min(width, height);
    }

    static int height(boolean tablet, int width, int height) {
        return tablet ? height : Math.max(width, height);
    }
}
