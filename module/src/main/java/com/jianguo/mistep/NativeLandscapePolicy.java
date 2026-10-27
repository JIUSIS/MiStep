package com.jianguo.mistep;

/** Coordinates temporary fullscreen landscape and restoration of the Mi Step workspace. */
final class NativeLandscapePolicy {
    static final int INACTIVE = 0;
    static final int SUSPENDING = 1;
    static final int FULLSCREEN = 2;
    static final int RESUMING = 3;

    private NativeLandscapePolicy() {}

    static int onLandscapeRequested(int state) {
        return state == INACTIVE ? SUSPENDING : state;
    }

    static int onZoom(int state, float scale) {
        if (scale != 1f) return INACTIVE;
        return state == SUSPENDING ? FULLSCREEN : state;
    }

    static boolean needsResume(int state, boolean displayLandscape, boolean home) {
        return state == FULLSCREEN && (home || !displayLandscape);
    }

    static int onResumeRequested(int state) {
        return state == FULLSCREEN ? RESUMING : state;
    }

    static boolean suppressesLocalRotation(int state, boolean sideTask, boolean home) {
        return !sideTask && !home && state != INACTIVE;
    }

    static boolean shouldUseNativeFullscreen(boolean workspaceVisible,
            boolean sideTask, boolean home) {
        return !workspaceVisible && !sideTask && !home;
    }

    static boolean shouldRestoreFromDisplay(boolean landscapeSuspended,
            boolean workspaceSuspended, int rotation) {
        return landscapeSuspended && workspaceSuspended && (rotation == 0 || rotation == 2);
    }

    static boolean shouldRestoreFromHome(boolean landscapeSuspended,
            boolean workspaceSuspended, String reason) {
        return landscapeSuspended && workspaceSuspended && "homekey".equals(reason);
    }
}
