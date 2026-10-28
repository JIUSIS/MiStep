package com.jiusis.mistep;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

/* JADX INFO: loaded from: classes.dex */
final class LiquidGlassButton extends TextView {
    private float energy;
    private ValueAnimator energyAnimator;
    private final LiquidGlassDrawable glass;

    LiquidGlassButton(final Context context, String str, float f, int i, final float f2, boolean z, boolean z2) {
        super(context);
        this.glass = new LiquidGlassDrawable(context, f2, z);
        setText(str);
        setTextColor(i);
        setTextSize(2, f);
        setGravity(17);
        if (z2) {
            setTypeface(Typeface.create("sans-serif-light", 0));
        }
        setBackground(null);
        setClickable(true);
        setFocusable(true);
        setElevation(HyperOsStyle.dp(context, 5.0f));
        setOutlineProvider(new ViewOutlineProvider() { // from class: com.jiusis.mistep.LiquidGlassButton.1
            @Override // android.view.ViewOutlineProvider
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), HyperOsStyle.dp(context, f2));
                outline.setAlpha(0.72f);
            }
        });
    }

    void setWallpaper(Bitmap bitmap, int i, int i2) {
        this.glass.setWallpaper(bitmap, i, i2);
        invalidate();
    }

    @Override // android.widget.TextView, android.view.View
    protected void onDraw(Canvas canvas) {
        int[] iArr = new int[2];
        getLocationOnScreen(iArr);
        this.glass.setScreenPosition(iArr[0], iArr[1]);
        this.glass.setPress(getWidth() * 0.5f, getHeight() * 0.48f, this.energy);
        this.glass.draw(canvas);
        super.onDraw(canvas);
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    @Override // android.widget.TextView, android.view.View
    public boolean onTouchEvent(MotionEvent motionEvent) {
        boolean zOnTouchEvent = super.onTouchEvent(motionEvent);
        switch (motionEvent.getActionMasked()) {
            case 0:
                this.glass.setPress(motionEvent.getX(), motionEvent.getY(), 0.35f);
                animateEnergy(1.0f);
                animate().cancel();
                animate().scaleX(0.94f).scaleY(0.94f).setDuration(80L).setInterpolator(new DecelerateInterpolator()).start();
                return zOnTouchEvent;
            case 1:
            case MiStepLayout.CELL_COUNT /* 3 */:
                animateEnergy(0.0f);
                animate().cancel();
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(170L).setInterpolator(new DecelerateInterpolator()).start();
                return zOnTouchEvent;
            case 2:
                this.glass.setPress(motionEvent.getX(), motionEvent.getY(), Math.max(0.35f, this.energy));
                return zOnTouchEvent;
            default:
                return zOnTouchEvent;
        }
    }

    private void animateEnergy(float f) {
        if (this.energyAnimator != null) {
            this.energyAnimator.cancel();
        }
        this.energyAnimator = ValueAnimator.ofFloat(this.energy, f);
        this.energyAnimator.setDuration(f > this.energy ? 90L : 210L);
        this.energyAnimator.setInterpolator(new DecelerateInterpolator());
        this.energyAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.jiusis.mistep.LiquidGlassButton.0
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                LiquidGlassButton.this.lambda$animateEnergy$0(valueAnimator);
            }
        });
        this.energyAnimator.start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$animateEnergy$0(ValueAnimator valueAnimator) {
        this.energy = ((Float) valueAnimator.getAnimatedValue()).floatValue();
        invalidate();
    }
}
