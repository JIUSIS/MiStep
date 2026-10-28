package com.jiusis.mistep;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** Toolbar icons keep their touch target without painting a button plate. */
final class MiStepIconButton extends View {
    static final int SETTINGS = 0, EXIT = 1, PREVIOUS = 2, NEXT = 3, CLIPBOARD = 4,
            TORCH = 5, SCREENSHOT = 6, RECORD = 7;
    private final int icon;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean exitLeft;

    MiStepIconButton(Context context, int icon, String description) {
        super(context);
        this.icon = icon;
        paint.setColor(HyperOsStyle.textPrimary(context));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setBackground(null);
        setClickable(true);
        setFocusable(true);
        setContentDescription(description);
    }

    void setExitLeft(boolean left) {
        exitLeft = left;
        setContentDescription(left ? "退出 Mi Step，方向朝左上" : "退出 Mi Step，方向朝右上");
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        float size = Math.min(HyperOsStyle.dp(getContext(), 23f), Math.min(getWidth(), getHeight()) * .84f);
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(HyperOsStyle.textPrimary(getContext()));
        paint.setStrokeWidth(1.8f);
        if (icon == CLIPBOARD) {
            // A folded page and a small clamp, all at the same light stroke weight.
            Path page = new Path();
            page.moveTo(8, 4); page.lineTo(5.5f, 4); page.quadTo(4, 4, 4, 5.5f);
            page.lineTo(4, 20); page.quadTo(4, 21.5f, 5.5f, 21.5f);
            page.lineTo(18.5f, 21.5f); page.quadTo(20, 21.5f, 20, 20);
            page.lineTo(20, 5.5f); page.quadTo(20, 4, 18.5f, 4); page.lineTo(16, 4);
            canvas.drawPath(page, paint);
            canvas.drawRoundRect(8, 2.5f, 16, 6.5f, 1.4f, 1.4f, paint);
            canvas.drawLine(8, 11, 16, 11, paint);
            canvas.drawLine(8, 15.5f, 13.5f, 15.5f, paint);
        } else if (icon == TORCH) {
            // Rounded flashlight head and grip; the short rays stay inside the icon box.
            Path body = new Path();
            body.moveTo(6.5f, 7); body.lineTo(17.5f, 7);
            body.quadTo(18, 7, 17.5f, 8); body.lineTo(15, 11.5f);
            body.lineTo(15, 20); body.quadTo(15, 21.5f, 13.5f, 21.5f);
            body.lineTo(10.5f, 21.5f); body.quadTo(9, 21.5f, 9, 20);
            body.lineTo(9, 11.5f); body.lineTo(6.5f, 8);
            body.quadTo(6, 7, 6.5f, 7); body.close();
            canvas.drawPath(body, paint);
            canvas.drawLine(9, 11.5f, 15, 11.5f, paint);
            canvas.drawLine(12, 14.5f, 12, 17, paint);
            canvas.drawLine(7.5f, 3.5f, 6.5f, 2.5f, paint);
            canvas.drawLine(12, 3, 12, 1.5f, paint);
            canvas.drawLine(16.5f, 3.5f, 17.5f, 2.5f, paint);
        } else if (icon == SCREENSHOT) {
            // A clear capture frame, without a second tiny camera outline inside it.
            Path frame = new Path();
            frame.moveTo(8, 3.5f); frame.lineTo(5, 3.5f); frame.quadTo(3.5f, 3.5f, 3.5f, 5); frame.lineTo(3.5f, 8);
            frame.moveTo(16, 3.5f); frame.lineTo(19, 3.5f); frame.quadTo(20.5f, 3.5f, 20.5f, 5); frame.lineTo(20.5f, 8);
            frame.moveTo(3.5f, 16); frame.lineTo(3.5f, 19); frame.quadTo(3.5f, 20.5f, 5, 20.5f); frame.lineTo(8, 20.5f);
            frame.moveTo(20.5f, 16); frame.lineTo(20.5f, 19); frame.quadTo(20.5f, 20.5f, 19, 20.5f); frame.lineTo(16, 20.5f);
            canvas.drawPath(frame, paint);
            canvas.drawCircle(12, 12, 3.5f, paint);
        } else if (icon == RECORD) {
            // Same theme paint as its neighbours; every edge is an open stroke.
            canvas.drawLine(4, 6, 14, 6, paint);
            canvas.drawLine(4, 18, 14, 18, paint);
            canvas.drawLine(2.5f, 7.5f, 2.5f, 16.5f, paint);
            canvas.drawLine(15.5f, 7.5f, 15.5f, 16.5f, paint);
            Path corners = new Path();
            corners.moveTo(2.5f, 7.5f); corners.quadTo(2.5f, 6, 4, 6);
            corners.moveTo(14, 6); corners.quadTo(15.5f, 6, 15.5f, 7.5f);
            corners.moveTo(15.5f, 16.5f); corners.quadTo(15.5f, 18, 14, 18);
            corners.moveTo(4, 18); corners.quadTo(2.5f, 18, 2.5f, 16.5f);
            canvas.drawPath(corners, paint);
            canvas.drawLine(15.5f, 10, 21.5f, 6.5f, paint);
            canvas.drawLine(21.5f, 6.5f, 21.5f, 17.5f, paint);
            canvas.drawLine(21.5f, 17.5f, 15.5f, 14, paint);
        } else if (icon == SETTINGS) {
            Path gear = new Path();
            for (int i = 0; i < 32; i++) {
                double angle = Math.PI * 2 * i / 32 - Math.PI / 2;
                float radius = (i % 4 == 0 || i % 4 == 3) ? 10f : 8f;
                float x = 12 + (float) Math.cos(angle) * radius;
                float y = 12 + (float) Math.sin(angle) * radius;
                if (i == 0) gear.moveTo(x, y); else gear.lineTo(x, y);
            }
            gear.close();
            canvas.drawPath(gear, paint);
            canvas.drawCircle(12, 12, 3.5f, paint);
        } else if (icon == EXIT) {
            if (exitLeft) canvas.scale(-1, 1, 12, 12);
            canvas.drawLine(5, 19, 19, 5, paint);
            canvas.drawLine(9, 5, 19, 5, paint);
            canvas.drawLine(19, 5, 19, 15, paint);
        } else {
            if (icon == NEXT) canvas.scale(-1, 1, 12, 12);
            Path chevron = new Path();
            chevron.moveTo(15, 5);
            chevron.lineTo(8, 12);
            chevron.lineTo(15, 19);
            canvas.drawPath(chevron, paint);
        }
        canvas.restoreToCount(save);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return super.onTouchEvent(event);
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            animate().cancel();
            animate().alpha(0.55f).scaleX(0.94f).scaleY(0.94f).setDuration(70).start();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            animate().cancel();
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start();
        }
        return handled;
    }
}
