package com.jiusis.mistep;

import android.graphics.Rect;

/** The OEM animation starts at the fullscreen app's visible workspace rectangle. */
final class NativeFreeformAnimation {
    private NativeFreeformAnimation() {}

    static Rect startBounds(Rect logical, int width, int height, float[] transform) {
        if (logical == null || logical.isEmpty() || width <= 0 || height <= 0
                || transform == null || transform.length != 3) return null;
        float scale = transform[0], y = transform[1], x = transform[2];
        if (!Float.isFinite(scale) || !Float.isFinite(x) || !Float.isFinite(y)
                || scale <= 0f || scale >= 1f
                || logical.left < 0 || logical.top < 0
                || logical.right > width || logical.bottom > height
                || logical.width() < width * .9f || logical.height() < height * .8f)
            return null;
        return new Rect(Math.round(logical.left * scale + x),
                Math.round(logical.top * scale + y),
                Math.round(logical.right * scale + x),
                Math.round(logical.bottom * scale + y));
    }
}
