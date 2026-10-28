package com.jiusis.mistep;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/* JADX INFO: loaded from: classes.dex */
final class WallpaperCropDrawable extends Drawable {
    private final int screenHeight;
    private final int screenWidth;
    private int screenX;
    private int screenY;
    private final int tint;
    private final Bitmap wallpaper;
    private final Paint bitmapPaint = new Paint(7);
    private final Paint tintPaint = new Paint();
    private final Rect source = new Rect();

    WallpaperCropDrawable(Bitmap bitmap, int i, int i2, int i3, int i4, int i5) {
        this.wallpaper = bitmap;
        this.screenWidth = Math.max(1, i);
        this.screenHeight = Math.max(1, i2);
        this.screenX = i3;
        this.screenY = i4;
        this.tint = i5;
        this.tintPaint.setColor(i5);
    }

    /** Main-thread offscreen rendering only; retain the live View's source without invalidating it. */
    void withScreenOrigin(int x, int y, Runnable draw) {
        int oldX = screenX, oldY = screenY;
        try {
            screenX = x;
            screenY = y;
            draw.run();
        } finally {
            screenX = oldX;
            screenY = oldY;
        }
    }

    @Override // android.graphics.drawable.Drawable
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty() || this.wallpaper == null || this.wallpaper.isRecycled()) {
            return;
        }
        this.source.set(Math.round((this.screenX * this.wallpaper.getWidth()) / this.screenWidth), Math.round((this.screenY * this.wallpaper.getHeight()) / this.screenHeight), Math.round(((this.screenX + bounds.width()) * this.wallpaper.getWidth()) / this.screenWidth), Math.round(((this.screenY + bounds.height()) * this.wallpaper.getHeight()) / this.screenHeight));
        canvas.drawBitmap(this.wallpaper, this.source, new RectF(bounds), this.bitmapPaint);
        if ((this.tint >>> 24) != 0) {
            canvas.drawRect(bounds, this.tintPaint);
        }
    }

    @Override // android.graphics.drawable.Drawable
    public void setAlpha(int i) {
        this.bitmapPaint.setAlpha(i);
        this.tintPaint.setAlpha(Math.round(((this.tint >>> 24) * i) / 255.0f));
        invalidateSelf();
    }

    @Override // android.graphics.drawable.Drawable
    public void setColorFilter(ColorFilter colorFilter) {
        this.bitmapPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override // android.graphics.drawable.Drawable
    public int getOpacity() {
        return -3;
    }
}
