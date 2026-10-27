package com.jianguo.mistep;

/** Main-pane crop for the full-resolution wallpaper mirror behind native Recents. */
final class RecentsWallpaperCrop {
    final int left, top, right, bottom;

    private RecentsWallpaperCrop(int left, int top, int right, int bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }

    static RecentsWallpaperCrop mainPane(int left, int top, int right, int bottom,
                                         int fullWidth, int fullHeight) {
        if (fullWidth <= 0 || fullHeight <= 0) return null;
        left = clamp(left, 0, fullWidth);
        top = clamp(top, 0, fullHeight);
        right = clamp(right, 0, fullWidth);
        bottom = clamp(bottom, 0, fullHeight);
        if (right <= left || bottom <= top) return null;
        return new RecentsWallpaperCrop(left, top, right, bottom);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override public String toString() {
        return "[" + left + "," + top + "][" + right + "," + bottom + "]";
    }
}
