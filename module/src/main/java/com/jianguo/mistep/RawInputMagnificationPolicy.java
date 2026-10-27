package com.jianguo.mistep;

/** Maps screen raw coordinates back through the active main-area surface transform. */
final class RawInputMagnificationPolicy {
    static final class Transform {
        final float scale;
        final float offsetY;
        final float offsetX;
        final long modified;

        Transform(float scale, float offsetY, float offsetX, long modified) {
            this.scale = scale;
            this.offsetY = offsetY;
            this.offsetX = offsetX;
            this.modified = modified;
        }
    }

    private RawInputMagnificationPolicy() {}

    static Transform parseLease(String encoded, long now) {
        if (encoded == null) return null;
        int separator = encoded.indexOf('|');
        if (separator < 1) return null;
        final long modified;
        try {
            modified = Long.parseLong(encoded.substring(0, separator));
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (modified > now || now - modified > ZoomCommand.ACTIVE_TIMEOUT_MS) return null;

        String command = encoded.substring(separator + 1).trim();
        if (command.isEmpty() || command.startsWith("anim")) return null;
        String[] values = command.split("\\s+");
        if (values.length < 1 || values.length > 3) return null;
        try {
            float scale = Float.parseFloat(values[0]);
            float offsetY = values.length > 1 ? Float.parseFloat(values[1]) : 0f;
            float offsetX = values.length > 2 ? Float.parseFloat(values[2]) : 0f;
            if (!Float.isFinite(scale) || scale <= 0f || scale >= 1f
                    || !Float.isFinite(offsetY) || offsetY < 0f || offsetY > 10000f
                    || !Float.isFinite(offsetX) || offsetX < 0f || offsetX > 10000f) return null;
            return new Transform(scale, offsetY, offsetX, modified);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static boolean mapsToDisplay(float rawX, float rawY, Transform transform,
            int displayWidth, int displayHeight) {
        if (transform == null || displayWidth <= 0 || displayHeight <= 0
                || !Float.isFinite(rawX) || !Float.isFinite(rawY)) return false;
        float x = (rawX - transform.offsetX) / transform.scale;
        float y = (rawY - transform.offsetY) / transform.scale;
        return x >= 0f && x < displayWidth && y >= 0f && y < displayHeight;
    }

    static float mapRawX(float rawX, Transform transform) {
        return (rawX - transform.offsetX) / transform.scale;
    }

    static float mapRawY(float rawY, Transform transform) {
        return (rawY - transform.offsetY) / transform.scale;
    }

    static boolean isActive(Transform transform, long now) {
        return transform != null && now >= transform.modified
                && now - transform.modified <= ZoomCommand.ACTIVE_TIMEOUT_MS;
    }
}
