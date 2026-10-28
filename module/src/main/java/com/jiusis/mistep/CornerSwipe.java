package com.jiusis.mistep;

/** Pure recognizer. False means no authority to cancel the original pointer stream. */
final class CornerSwipe {
    private boolean candidate;
    private int pointer, width, height, rotation, sign, sweepPx, maxVerticalDriftPx;
    private float x, y;
    private long time;
    void down(int pointer, int count, float x, float y, long time,
            int width, int height, int rotation, int regionPx, int sweepPx,
            int maxVerticalDriftPx) {
        reset();
        if (count != 1 || width < 640 || height <= 320 || x < 0 || x >= width
                || y < 0 || y >= regionPx) return;
        if (x < regionPx) sign = 1;
        else if (x >= width - regionPx) sign = -1;
        else return;
        this.pointer = pointer; this.x = x; this.y = y; this.time = time;
        this.width = width; this.height = height; this.rotation = rotation;
        this.sweepPx = sweepPx;
        this.maxVerticalDriftPx = maxVerticalDriftPx;
        candidate = true;
    }
    boolean move(int pointer, int count, float x, float y, long time,
            int width, int height, int rotation) {
        if (!candidate) return false;
        if (pointer != this.pointer || count != 1 || width != this.width
                || height != this.height || rotation != this.rotation
                || time < this.time || time - this.time > 500) {
            reset();
            return false;
        }
        int sweep = this.sweepPx > 0 ? this.sweepPx : Math.round(58f * (this.width / 1080f));
        int maxVerticalDrift = this.maxVerticalDriftPx > 0 ? this.maxVerticalDriftPx
                : Math.round(24f * (this.width / 1080f));
        float verticalDrift = Math.abs(y - this.y);
        if (verticalDrift > maxVerticalDrift) {
            reset();
            return false;
        }
        if ((x - this.x) * sign < sweep) return false;
        reset();
        return true;
    }
    boolean isCandidate() { return candidate; }
    void reset() { candidate = false; }
    /** 起始角：1=左角，-1=右角（用于决定打开一步时主区在哪边）。 */
    int sign() { return sign; }
}
