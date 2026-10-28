package com.jianguo.mistep;

/** Only MiType's native landscape IME windows participate in this compatibility path. */
final class MiStepImeInputPolicy {
    static float[] mapRaw(float physicalX, float physicalY, float scale, float offsetX,
            float offsetY, int logicalWidth, int logicalHeight, int rotation) {
        if (!Float.isFinite(physicalX) || !Float.isFinite(physicalY)
                || !Float.isFinite(scale) || scale <= 0f || scale >= 1f
                || !Float.isFinite(offsetX) || !Float.isFinite(offsetY)
                || logicalWidth <= 0 || logicalHeight <= 0 || (rotation != 1 && rotation != 3)) return null;
        float x = (physicalX - offsetX) / scale;
        float y = (physicalY - offsetY) / scale;
        return rotation == 1 ? new float[]{y, logicalHeight - x}
                : new float[]{logicalWidth - y, x};
    }

    static boolean accepts(boolean active, int display, int type, String pkg,
            CharSequence title, int rotation) {
        return active && display == 0 && (rotation == 1 || rotation == 3)
                && "com.xiaomi.type".equals(pkg)
                && (type == 2011 || type == 2012 || type == 1000 && title != null
                        && title.toString().startsWith("PopupWindow:"));
    }
}
