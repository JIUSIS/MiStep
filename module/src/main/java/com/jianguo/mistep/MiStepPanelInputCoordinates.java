package com.jianguo.mistep;

/** Native panel raw coordinates for one root-dispatched sample, including its copies. */
final class MiStepPanelInputCoordinates {
    static boolean accepts(boolean active, int display, int type, String pkg,
            CharSequence title, int rotation) {
        return active && display == 0 && rotation >= 0 && rotation < 4
                && "com.android.systemui".equals(pkg)
                && (type == 2000 || type == 2040 || type == 2017 && title != null
                        && "NotificationModalWindowManager".contentEquals(title));
    }

    final long downTime, eventTime;
    final int device, source, display;
    private final int[] ids;
    private final float[] x, y;

    MiStepPanelInputCoordinates(long downTime, long eventTime, int device, int source,
            int display, int[] ids, float[] x, float[] y) {
        this.downTime = downTime; this.eventTime = eventTime;
        this.device = device; this.source = source; this.display = display;
        this.ids = ids.clone(); this.x = x.clone(); this.y = y.clone();
    }

    Float raw(long down, long time, int dev, int src, int screen, int pointerId,
            boolean horizontal) {
        if (down != downTime || time != eventTime || dev != device
                || src != source || screen != display) return null;
        for (int i = 0; i < ids.length; i++) if (ids[i] == pointerId)
            return horizontal ? x[i] : y[i];
        return null;
    }
}
