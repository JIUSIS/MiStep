package com.jiusis.mistep;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Independent 24-unit action glyphs, with no OEM tile background or animation layers. */
final class MiStepCodeIcon extends Drawable {
    /** Draw action strokes directly; the OEM ImageView applies its own image effects. */
    static final class GlyphView extends android.view.View {
        private final MiStepCodeIcon glyph;
        GlyphView(android.content.Context context, boolean transit) {
            super(context); glyph = new MiStepCodeIcon(transit);
            glyph.setBounds(0, 0, 24, 24);
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int save = canvas.save();
            float size = Math.min(getWidth(), getHeight()) * .68f;
            canvas.translate((getWidth()-size)/2f, (getHeight()-size)/2f);
            canvas.scale(size/24f, size/24f);
            glyph.draw(canvas);
            canvas.restoreToCount(save);
        }
    }
    private final boolean transit;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    MiStepCodeIcon(boolean transit) {
        this.transit = transit;
        paint.setColor(0xffffffff);
        paint.setStrokeWidth(1.65f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        float size = Math.min(getBounds().width(), getBounds().height());
        canvas.translate(getBounds().exactCenterX()-size/2f, getBounds().exactCenterY()-size/2f);
        canvas.scale(size/24f, size/24f);
        paint.setStyle(Paint.Style.STROKE);
        if (transit) {
            rect.set(5, 3, 19, 18); canvas.drawRoundRect(rect, 3, 3, paint);
            canvas.drawLine(7, 8, 17, 8, paint);
            canvas.drawLine(12, 8, 12, 14, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(8.5f, 19.5f, 1.15f, paint);
            canvas.drawCircle(15.5f, 19.5f, 1.15f, paint);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(4, 21, 9, 21, paint);
            canvas.drawLine(15, 21, 20, 21, paint);
        } else {
            // Payment card with an unmistakable barcode strip.
            rect.set(3, 5, 21, 19); canvas.drawRoundRect(rect, 2.5f, 2.5f, paint);
            canvas.drawLine(3.5f, 8.5f, 20.5f, 8.5f, paint);
            canvas.drawLine(7, 11.5f, 7, 16, paint);
            canvas.drawLine(10, 11.5f, 10, 16, paint);
            paint.setStrokeWidth(2.2f);
            canvas.drawLine(13, 11.5f, 13, 16, paint);
            paint.setStrokeWidth(1.3f);
            canvas.drawLine(16.5f, 11.5f, 16.5f, 16, paint);
            canvas.drawLine(18.5f, 11.5f, 18.5f, 16, paint);
        }
        canvas.restoreToCount(save);
    }
    @Override public int getIntrinsicWidth() { return 24; }
    @Override public int getIntrinsicHeight() { return 24; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
