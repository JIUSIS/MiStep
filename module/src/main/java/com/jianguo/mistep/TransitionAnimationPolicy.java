package com.jianguo.mistep;

final class TransitionAnimationPolicy {
    private static final int TRANSIT_OPEN = 1;
    private static final int MODE_OPEN = 1;

    private TransitionAnimationPolicy() {}

    static boolean shouldClampOpenBounds(boolean hostActive, boolean taskTransferBusy,
            int transitionType, int changeMode, boolean sideTask, int windowingMode,
            boolean rotating) {
        return hostActive && !taskTransferBusy && transitionType == TRANSIT_OPEN
                && changeMode == MODE_OPEN && !sideTask
                && windowingMode == FullscreenRotationPolicy.WINDOWING_MODE_FULLSCREEN
                && !rotating;
    }
}
