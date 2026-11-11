package com.jiusis.mistep;

/** One-finger return from the interactive tablet pane's inner edge only. */
final class TopEdgeBackSwipe {
    static final int NONE = 0, CLAIM = 1, BACK = 2;
    private Object token;
    private int pointer, direction;
    private float x, y, density;
    private long down;
    private boolean claimed;

    int event(int action, int count, int id, float px, float py, long time,
            int width, int height, float dpi, int outward, Object owner) {
        if (action == 0) {
            reset();
            if (owner == null || count != 1 || width <= 0 || height <= 0
                    || dpi <= 0 || !Float.isFinite(dpi) || (outward != 1 && outward != -1)
                    || !Float.isFinite(px) || !Float.isFinite(py)
                    || py < 24 * dpi || py >= height - 24 * dpi
                    || px < 0 || px >= width
                    || (outward > 0 ? px > 18 * dpi : px < width - 18 * dpi)) return NONE;
            token = owner; pointer = id; x = px; y = py; down = time;
            direction = outward; density = dpi;
            return NONE;
        }
        if (token == null) return NONE;
        if (!token.equals(owner) || count != 1 || pointer != id || time < down
                || time - down > 600 || action == 3 || action == 5 || action == 6
                || !Float.isFinite(px) || !Float.isFinite(py)) { reset(); return NONE; }
        float dx = (px - x) * direction, dy = Math.abs(py - y);
        if (dx < -8 * density || dy > 32 * density || dy > Math.max(8 * density, dx)) {
            reset(); return NONE;
        }
        if (action == 1) {
            boolean back = claimed && dx >= 58 * density && dx > dy * 1.5f;
            reset();
            return back ? BACK : NONE;
        }
        if (action == 2 && !claimed && dx >= 18 * density && dx > dy * 1.5f) {
            claimed = true; return CLAIM;
        }
        return NONE;
    }

    boolean claimed() { return claimed; }
    void reset() { token = null; claimed = false; }
}
