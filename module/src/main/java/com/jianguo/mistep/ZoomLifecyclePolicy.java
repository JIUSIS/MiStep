package com.jianguo.mistep;

final class ZoomLifecyclePolicy {
    private ZoomLifecyclePolicy() {}

    static boolean shouldReassertGeometry(boolean active, boolean rootVisible, boolean screenOn) {
        return active && rootVisible && screenOn;
    }

    static boolean shouldApplyDelayedWrite(long scheduledGeneration, long currentGeneration,
            boolean requestedZoomed, boolean zoomed) {
        return scheduledGeneration == currentGeneration && requestedZoomed == zoomed;
    }
}
