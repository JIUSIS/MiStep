package com.jianguo.mistep;

/** Only the untransformed display monitor feeds this boundary; window events never do. */
final class IslandMonitorInputPolicy {
    private IslandMonitorInputPolicy() {}

    static float[] logicalDown(boolean monitorSource, int action, int display,
            float localX, float localY, float rawX, float rawY, float[] zoom) {
        if (!monitorSource || action != 0 || display != 0 || zoom == null || zoom.length != 3)
            return null;
        float scale = zoom[0], offsetY = zoom[1], offsetX = zoom[2];
        if (!Float.isFinite(scale) || scale <= 0f || scale >= 1f
                || !Float.isFinite(offsetX) || !Float.isFinite(offsetY)
                || !Float.isFinite(localX) || !Float.isFinite(localY)
                || !Float.isFinite(rawX) || !Float.isFinite(rawY)
                || Math.abs(localX - rawX) > 0.01f || Math.abs(localY - rawY) > 0.01f)
            return null;
        float x = (localX - offsetX) / scale;
        float y = (localY - offsetY) / scale;
        return Float.isFinite(x) && Float.isFinite(y) ? new float[]{x, y} : null;
    }
}
