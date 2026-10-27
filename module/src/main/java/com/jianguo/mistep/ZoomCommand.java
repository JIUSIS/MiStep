package com.jianguo.mistep;

/** Reject stale active requests after boot or loss of the SystemUI host. */
final class ZoomCommand {
    static final long ACTIVE_TIMEOUT_MS = 15_000L;

    private ZoomCommand() {}

    static float[] read(String line, long modified, long started, long now) {
        if (line == null || line.trim().isEmpty()) return identity();
        try {
            String[] parts = line.trim().split("\\s+");
            float scale = Float.parseFloat(parts[0]);
            float offsetY = parts.length > 1 ? Float.parseFloat(parts[1]) : 0f;
            float offsetX = parts.length > 2 ? Float.parseFloat(parts[2]) : 0f;
            if (!Float.isFinite(scale) || !Float.isFinite(offsetY) || !Float.isFinite(offsetX)
                    || scale <= 0f || scale > 1f
                    || offsetY < 0f || offsetY > 10000f
                    || offsetX < 0f || offsetX > 10000f) {
                return identity();
            }
            if (scale == 1f) return identity();
            if (modified < started || now - modified > ACTIVE_TIMEOUT_MS) {
                return identity();
            }
            return new float[]{scale, offsetY, offsetX};
        } catch (NumberFormatException invalid) {
            return identity();
        }
    }

    private static float[] identity() {
        return new float[]{1f, 0f, 0f};
    }
}
