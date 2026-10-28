package com.jiusis.mistep;

/** Rotation policy for the portrait Mi Step workspace and its main task. */
final class FullscreenRotationPolicy {
    private static final int DISPLAY_PORTRAIT = 0;
    private static final int DISPLAY_LANDSCAPE = 1;
    private static final int DISPLAY_SEASCAPE = 3;

    private static final int ORIENTATION_LANDSCAPE = 0;
    private static final int ORIENTATION_PORTRAIT = 1;
    private static final int ORIENTATION_USER = 2;
    private static final int ORIENTATION_SENSOR = 4;
    private static final int ORIENTATION_SENSOR_LANDSCAPE = 6;
    private static final int ORIENTATION_SENSOR_PORTRAIT = 7;
    private static final int ORIENTATION_REVERSE_LANDSCAPE = 8;
    private static final int ORIENTATION_REVERSE_PORTRAIT = 9;
    private static final int ORIENTATION_FULL_SENSOR = 10;
    private static final int ORIENTATION_USER_LANDSCAPE = 11;
    private static final int ORIENTATION_USER_PORTRAIT = 12;
    private static final int ORIENTATION_FULL_USER = 13;
    private static final int ORIENTATION_LOCKED = 14;
    static final int WINDOWING_MODE_FULLSCREEN = 1;
    static final int WINDOWING_MODE_FREEFORM = 5;

    private FullscreenRotationPolicy() {}

    static boolean shouldHoldDisplay(boolean active, int displayId) {
        return active && displayId == 0;
    }

    static int portraitDisplayRotation() {
        return DISPLAY_PORTRAIT;
    }

    static int observedTaskRotation(int configurationOrientation, int requestedOrientation,
            int displayRotation) {
        if (configurationOrientation == 2) {
            if (displayRotation == DISPLAY_LANDSCAPE || displayRotation == DISPLAY_SEASCAPE) {
                return displayRotation;
            }
            return requestedOrientation == ORIENTATION_REVERSE_LANDSCAPE
                    ? DISPLAY_SEASCAPE : DISPLAY_LANDSCAPE;
        }
        if (configurationOrientation == 1) return DISPLAY_PORTRAIT;
        return displayRotation >= DISPLAY_PORTRAIT && displayRotation <= DISPLAY_SEASCAPE
                ? displayRotation : TaskWindowState.UNKNOWN_ROTATION;
    }

    static boolean usesSensorOrUserRotation(int overrideOrientation) {
        switch (overrideOrientation) {
            case ORIENTATION_USER:
            case ORIENTATION_SENSOR:
            case ORIENTATION_SENSOR_LANDSCAPE:
            case ORIENTATION_SENSOR_PORTRAIT:
            case ORIENTATION_FULL_SENSOR:
            case ORIENTATION_USER_LANDSCAPE:
            case ORIENTATION_USER_PORTRAIT:
            case ORIENTATION_FULL_USER:
            case -1:
                return true;
            default:
                return false;
        }
    }

    static boolean requestsLandscape(int orientation) {
        return orientation == ORIENTATION_LANDSCAPE
                || orientation == ORIENTATION_REVERSE_LANDSCAPE
                || orientation == ORIENTATION_SENSOR_LANDSCAPE
                || orientation == ORIENTATION_USER_LANDSCAPE;
    }

    /** The display hold controls the workspace; application direction stays per task. */
    static int taskRotation(int overrideOrientation, int resolvedRotation, boolean rotationLocked) {
        return taskRotation(overrideOrientation, resolvedRotation, rotationLocked,
                WINDOWING_MODE_FULLSCREEN, TaskWindowState.UNKNOWN_ROTATION);
    }

    static int taskRotation(int overrideOrientation, int resolvedRotation,
            boolean rotationLocked, int windowingMode, int rememberedFullscreenRotation) {
        switch (overrideOrientation) {
            case ORIENTATION_LANDSCAPE:
                return DISPLAY_LANDSCAPE;
            case ORIENTATION_REVERSE_LANDSCAPE:
                return DISPLAY_SEASCAPE;
            case ORIENTATION_SENSOR_LANDSCAPE:
            case ORIENTATION_USER_LANDSCAPE:
                if (isLandscape(resolvedRotation)) return resolvedRotation;
                return isLandscape(rememberedFullscreenRotation)
                        ? rememberedFullscreenRotation : DISPLAY_LANDSCAPE;
            case ORIENTATION_PORTRAIT:
            case ORIENTATION_SENSOR_PORTRAIT:
            case ORIENTATION_REVERSE_PORTRAIT:
            case ORIENTATION_USER_PORTRAIT:
                return DISPLAY_PORTRAIT;
            default:
                if (rotationLocked && usesSensorOrUserRotation(overrideOrientation)) {
                    return isLandscape(rememberedFullscreenRotation)
                            ? rememberedFullscreenRotation : DISPLAY_PORTRAIT;
                }
                if ((usesSensorOrUserRotation(overrideOrientation)
                        || overrideOrientation == ORIENTATION_LOCKED)
                        && (resolvedRotation == DISPLAY_LANDSCAPE
                                || resolvedRotation == DISPLAY_SEASCAPE)) {
                    return resolvedRotation;
                }
                return DISPLAY_PORTRAIT;
        }
    }

    private static boolean isLandscape(int rotation) {
        return rotation == DISPLAY_LANDSCAPE || rotation == DISPLAY_SEASCAPE;
    }

    static int workspaceWidth(int displayWidth, int displayHeight) {
        return Math.min(displayWidth, displayHeight);
    }

    static int workspaceHeight(int displayWidth, int displayHeight) {
        return Math.max(displayWidth, displayHeight);
    }
}
