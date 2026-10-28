package com.jiusis.mistep;

/** Physical outward displacement; independent of the main area's magnification. */
final class MiStepSideDismissGesture {
    private MiStepSideDismissGesture() {}

    static boolean starts(boolean sideOnLeft, float dx, float dy, int touchSlop) {
        float outward = sideOnLeft ? -dx : dx;
        return Float.isFinite(dx) && Float.isFinite(dy)
                && outward > touchSlop && outward > Math.abs(dy) * 1.5f;
    }

    static boolean accepts(boolean sideOnLeft, float dx, float dy, float width,
            float density, int touchSlop, long elapsed) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(width)
                || !Float.isFinite(density) || width <= 0 || density <= 0
                || elapsed < 0) return false;
        float outward = sideOnLeft ? -dx : dx;
        float threshold = Math.max(width * .28f, Math.max(32f * density, touchSlop * 3f));
        return outward >= threshold && outward >= Math.abs(dy) * 1.5f;
    }
}
