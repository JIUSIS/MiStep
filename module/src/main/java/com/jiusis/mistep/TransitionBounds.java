package com.jiusis.mistep;

import android.graphics.Rect;

/** Clamp MIUI's app-transition animation to the active Mi Step main pane. */
final class TransitionBounds {
    private TransitionBounds() {}

    static Rect clamp(Rect animation, Rect main) {
        if (animation == null || animation.isEmpty() || main == null || main.isEmpty()) {
            return animation;
        }
        int[] input = {animation.left, animation.top, animation.right, animation.bottom};
        int[] clipped = clampCoords(input, new int[]{main.left, main.top, main.right, main.bottom});
        return clipped == input ? animation
                : new Rect(clipped[0], clipped[1], clipped[2], clipped[3]);
    }

    /** Pure geometry for the animation rectangle only; never a SurfaceControl crop. */
    static int[] clampCoords(int[] end, int[] main) {
        if (end == null || end.length != 4 || main == null || main.length != 4
                || end[2] <= end[0] || end[3] <= end[1]
                || main[2] <= main[0] || main[3] <= main[1]) return end;
        int left = Math.max(end[0], main[0]);
        int top = Math.max(end[1], main[1]);
        int right = Math.min(end[2], main[2]);
        int bottom = Math.min(end[3], main[3]);
        if (right <= left || bottom <= top
                || (left == end[0] && top == end[1]
                && right == end[2] && bottom == end[3])) return end;
        return new int[]{left, top, right, bottom};
    }
}
