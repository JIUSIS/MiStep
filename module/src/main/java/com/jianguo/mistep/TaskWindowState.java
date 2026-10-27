package com.jianguo.mistep;

/**
 * Per-task orientation/windowing state shared by the four Mi Step slots.
 * The slot index is ownership metadata; the task token remains the identity.
 */
final class TaskWindowState {
    static final int UNKNOWN_ORIENTATION = 0;
    static final int UNKNOWN_ROTATION = -1;

    final int taskId;
    int slotIndex = -1;
    int configurationOrientation = UNKNOWN_ORIENTATION;
    int requestedOrientation = -1;
    int resolvedRotation = UNKNOWN_ROTATION;
    int appliedRotation = UNKNOWN_ROTATION;
    int windowingMode = -1;
    int lastFullscreenConfigurationOrientation = UNKNOWN_ORIENTATION;
    int lastFullscreenRequestedOrientation = -1;
    int lastFullscreenResolvedRotation = UNKNOWN_ROTATION;
    int lastFullscreenAppliedRotation = UNKNOWN_ROTATION;
    boolean fullscreen;
    boolean active;
    long generation;

    TaskWindowState(int taskId) {
        this.taskId = taskId;
    }

    void update(int slotIndex, int configurationOrientation, int requestedOrientation,
            int resolvedRotation, int windowingMode, boolean fullscreen, boolean active) {
        update(slotIndex, configurationOrientation, requestedOrientation, resolvedRotation,
                windowingMode, fullscreen, active, UNKNOWN_ROTATION);
    }

    void update(int slotIndex, int configurationOrientation, int requestedOrientation,
            int resolvedRotation, int windowingMode, boolean fullscreen, boolean active,
            int appliedRotation) {
        if (this.active && this.fullscreen && !fullscreen) rememberFullscreenState();
        this.slotIndex = slotIndex;
        if (configurationOrientation != UNKNOWN_ORIENTATION) {
            this.configurationOrientation = configurationOrientation;
        }
        this.requestedOrientation = requestedOrientation;
        if (resolvedRotation != UNKNOWN_ROTATION) {
            this.resolvedRotation = resolvedRotation;
        }
        if (appliedRotation != UNKNOWN_ROTATION) {
            this.appliedRotation = appliedRotation;
        }
        this.windowingMode = windowingMode;
        this.fullscreen = fullscreen;
        this.active = active;
        if (fullscreen) rememberFullscreenState();
        generation++;
    }

    private void rememberFullscreenState() {
        if (configurationOrientation != UNKNOWN_ORIENTATION) {
            lastFullscreenConfigurationOrientation = configurationOrientation;
        }
        lastFullscreenRequestedOrientation = requestedOrientation;
        if (resolvedRotation != UNKNOWN_ROTATION) {
            lastFullscreenResolvedRotation = resolvedRotation;
        }
        if (appliedRotation != UNKNOWN_ROTATION) {
            lastFullscreenAppliedRotation = appliedRotation;
        }
    }

    int preferredRememberedRotation() {
        if (windowingMode == 5 && configurationOrientation == 2) {
            if (appliedRotation == 1 || appliedRotation == 3) return appliedRotation;
            if (resolvedRotation == 1 || resolvedRotation == 3) return resolvedRotation;
            return 1;
        }
        if (windowingMode != 5) {
            if (appliedRotation == 0) return UNKNOWN_ROTATION;
            if (appliedRotation == 1 || appliedRotation == 3) return appliedRotation;
        }
        if (lastFullscreenAppliedRotation == 0) return UNKNOWN_ROTATION;
        if (lastFullscreenAppliedRotation == 1 || lastFullscreenAppliedRotation == 3) {
            return lastFullscreenAppliedRotation;
        }
        if (lastFullscreenResolvedRotation == 1 || lastFullscreenResolvedRotation == 3) {
            return lastFullscreenResolvedRotation;
        }
        if (lastFullscreenConfigurationOrientation == 2) return 1;
        return UNKNOWN_ROTATION;
    }

    void forgetRememberedRotation() {
        appliedRotation = 0;
        lastFullscreenConfigurationOrientation = UNKNOWN_ORIENTATION;
        lastFullscreenResolvedRotation = UNKNOWN_ROTATION;
        lastFullscreenAppliedRotation = 0;
    }

    void clear() {
        active = false;
        slotIndex = -1;
        generation++;
    }

    boolean isSideSlot() {
        return active && slotIndex > 0;
    }

    boolean isPortraitConfiguration() {
        return configurationOrientation == 1;
    }

    boolean isLandscapeConfiguration() {
        return configurationOrientation == 2;
    }
}
