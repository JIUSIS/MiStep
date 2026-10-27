package com.jianguo.mistep;

/** Apply the active workspace transform with the opening main task's first transition frame. */
final class MainLaunchMagnification {
    private MainLaunchMagnification() {}

    static boolean shouldApply(boolean zoomed, Object defaultDisplay, Object taskDisplay,
            boolean rootTask, boolean visibleRequested, int windowingMode, int transitMode) {
        return zoomed && defaultDisplay != null && taskDisplay == defaultDisplay
                && rootTask && visibleRequested && windowingMode == 1
                && (transitMode == 1 || transitMode == 3);
    }
}
