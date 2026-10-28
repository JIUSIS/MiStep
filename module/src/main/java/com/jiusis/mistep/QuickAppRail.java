package com.jiusis.mistep;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import java.util.Collections;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
final class QuickAppRail extends View {
    private int activeIndex;
    private List<QuickApp> apps;
    private final RectF bounds;
    private final RectF bubble;
    private final Paint bubblePaint;
    private final Paint bubbleStroke;
    private final Path clip;
    private boolean down;
    private float downX;
    private float downY;
    private float energy;
    private ValueAnimator energyAnimator;
    private final Paint fadePaint;
    private final Paint haloPaint;
    private final float iconSize;
    private final Paint iconTint;
    private ValueAnimator lensAnimator;
    private float lensX;
    private final Listener listener;
    private final Runnable longPress;
    private final float padding;
    private float pressX;
    private float pressY;
    private float scrollOffset;
    private float scrollStart;
    private boolean scrolling;
    private boolean selecting;
    private final float tileWidth;
    private final float touchSlop;
    private int selectedIndex = -1;
    private int stackFromIndex = -1;
    private int stackToIndex = -1;
    private int stackDirection = 1;
    private int stackGeneration;
    private float stackProgress = 1f;
    private ValueAnimator stackAnimator;

    interface Listener {
        void onLaunch(QuickApp quickApp);
    }

    QuickAppRail(Context context, List<QuickApp> list, Listener listener) {
        super(context);
        this.iconTint = new Paint(1);
        this.bubblePaint = new Paint(1);
        this.bubbleStroke = new Paint(1);
        this.haloPaint = new Paint(1);
        this.fadePaint = new Paint(1);
        this.bubble = new RectF();
        this.clip = new Path();
        this.bounds = new RectF();
        this.longPress = new Runnable() { // from class: com.jiusis.mistep.QuickAppRail.2
            @Override // java.lang.Runnable
            public final void run() {
                QuickAppRail.this.beginDragSelection();
            }
        };
        this.lensX = -1.0f;
        this.activeIndex = -1;
        this.apps = list == null ? Collections.emptyList() : Collections.unmodifiableList(list.subList(0, Math.min(18, list.size())));
        this.listener = listener;
        float f = context.getResources().getDisplayMetrics().density;
        this.tileWidth = 48.0f * f;
        this.padding = 10.0f * f;
        this.iconSize = 31.0f * f;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.bubblePaint.setStyle(Paint.Style.FILL);
        this.bubbleStroke.setStyle(Paint.Style.STROKE);
        this.bubbleStroke.setColor(1694498815);
        this.bubbleStroke.setStrokeWidth(Math.max(1.0f, 0.9f * f));
        this.iconTint.setFilterBitmap(true);
        this.fadePaint.setStyle(Paint.Style.FILL);
        setLayerType(2, null);
        setBackground(null);
        setForeground(null);
        setElevation(0f);
        setClickable(true);
        setFocusable(true);
        setContentDescription("快捷应用栏，长按后拖动可选择应用");
    }

    void stepAndLaunch(int direction) {
        if (apps.isEmpty() || listener == null) return;
        int step = direction < 0 ? -1 : 1;
        int target = selectedIndex < 0
                ? (step > 0 ? 0 : apps.size() - 1)
                : Math.floorMod(selectedIndex + step, apps.size());
        if (selectedIndex == target) {
            listener.onLaunch(apps.get(target));
            return;
        }
        int generation = ++stackGeneration;
        if (stackAnimator != null) stackAnimator.cancel();
        stackFromIndex = selectedIndex < 0
                ? Math.floorMod(target - step, apps.size()) : selectedIndex;
        stackToIndex = target;
        stackDirection = step;
        selectedIndex = target;
        activeIndex = -1;
        lensX = tileCenter(target);
        stackProgress = 0f;
        stackAnimator = ValueAnimator.ofFloat(0f, 1f);
        stackAnimator.setDuration(230L);
        stackAnimator.setInterpolator(new DecelerateInterpolator(1.3f));
        stackAnimator.addUpdateListener(animator -> {
            stackProgress = (Float) animator.getAnimatedValue();
            invalidate();
        });
        stackAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (generation != stackGeneration) return;
                stackProgress = 1f;
                stackFromIndex = stackToIndex = -1;
                listener.onLaunch(apps.get(target));
                invalidate();
            }
        });
        stackAnimator.start();
    }

    void setApps(List<QuickApp> next) {
        ++stackGeneration;
        if (stackAnimator != null) stackAnimator.cancel();
        removeCallbacks(longPress);
        resetTouch();
        apps = next == null ? Collections.emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<>(next.subList(0, Math.min(18, next.size()))));
        selectedIndex = stackFromIndex = stackToIndex = -1;
        stackProgress = 1f;
        scrollOffset = scrollStart = 0f;
        lensX = -1f;
        invalidate();
    }

    @Override // android.view.View
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float f = getResources().getDisplayMetrics().density * 26.0f;
        this.bounds.set(0.0f, 0.0f, getWidth(), getHeight());
        this.clip.reset();
        this.clip.addRoundRect(this.bounds, f, f, Path.Direction.CW);
        int iSave = canvas.save();
        canvas.clipPath(this.clip);
        canvas.translate(-this.scrollOffset, 0.0f);
        float iconY = getHeight() * 0.5f;
        boolean stacking = stackProgress < 1f && stackFromIndex >= 0 && stackToIndex >= 0;
        for (int i = 0; i < apps.size(); i++) {
            if (!stacking || (i != stackFromIndex && i != stackToIndex)) {
                float scale = i == this.activeIndex && (this.selecting || this.down)
                        ? 1.18f : 1.0f;
                drawIcon(canvas, i, tileCenter(i), iconY, scale, 255);
            }
        }
        if (stacking) {
            float progress = stackProgress;
            float travel = tileWidth * stackDirection;
            drawIcon(canvas, stackFromIndex,
                    tileCenter(stackFromIndex) + travel * progress,
                    iconY, 1f - 0.24f * progress,
                    Math.round(255f * (1f - progress)));
            drawIcon(canvas, stackToIndex,
                    tileCenter(stackToIndex) - travel * (1f - progress),
                    iconY, 0.76f + 0.24f * progress,
                    Math.round(255f * progress));
        }
        canvas.restoreToCount(iSave);
    }

    private void drawActiveHalo(Canvas canvas) {
        int index = this.activeIndex >= 0 ? this.activeIndex : this.selectedIndex;
        if (index < 0 || index >= this.apps.size()) {
            return;
        }
        float fTileCenter = this.lensX >= 0.0f ? this.lensX : tileCenter(index);
        float height = getHeight() * 0.5f;
        float f = getResources().getDisplayMetrics().density * 27.0f;
        this.haloPaint.setShader(new RadialGradient(fTileCenter, height, f, new int[]{1392508927, 536870911, 16777215}, new float[]{0.0f, 0.62f, 1.0f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(fTileCenter, height, f, this.haloPaint);
        float f2 = 0.92f * f;
        this.bubble.set(fTileCenter - (this.tileWidth * 0.45f), height - f2, fTileCenter + (this.tileWidth * 0.45f), height + f2);
        this.bubblePaint.setShader(new LinearGradient(this.bubble.left, this.bubble.top, this.bubble.right, this.bubble.bottom, new int[]{1124073471, 419430399, 685237226}, (float[]) null, Shader.TileMode.CLAMP));
        float f3 = f * 0.58f;
        canvas.drawRoundRect(this.bubble, f3, f3, this.bubblePaint);
        this.bubblePaint.setShader(null);
        canvas.drawRoundRect(this.bubble, f3, f3, this.bubbleStroke);
    }

    private void drawEdgeFades(Canvas canvas) {
        Canvas canvas2;
        float f = getResources().getDisplayMetrics().density * 9.0f;
        if (this.scrollOffset <= 0.5f) {
            canvas2 = canvas;
        } else {
            this.fadePaint.setShader(new LinearGradient(0.0f, 0.0f, f, 0.0f, 922746879, 16777215, Shader.TileMode.CLAMP));
            canvas2 = canvas;
            canvas2.drawRect(0.0f, 0.0f, f, getHeight(), this.fadePaint);
        }
        if (this.scrollOffset < maxScroll() - 0.5f) {
            this.fadePaint.setShader(new LinearGradient(getWidth() - f, 0.0f, getWidth(), 0.0f, 16777215, 721420287, Shader.TileMode.CLAMP));
            canvas2.drawRect(getWidth() - f, 0.0f, getWidth(), getHeight(), this.fadePaint);
        }
        this.fadePaint.setShader(null);
    }

    @Override // android.view.View
    public boolean onTouchEvent(MotionEvent motionEvent) {
        switch (motionEvent.getActionMasked()) {
            case 0:
                if (stackAnimator != null && stackAnimator.isRunning()) {
                    stackGeneration++;
                    stackAnimator.cancel();
                    stackProgress = 1f;
                    stackFromIndex = stackToIndex = -1;
                }
                this.down = true;
                this.selecting = false;
                this.scrolling = false;
                this.downX = motionEvent.getX();
                this.downY = motionEvent.getY();
                this.scrollStart = this.scrollOffset;
                this.activeIndex = indexAt(this.downX);
                this.lensX = this.activeIndex >= 0 ? tileCenter(this.activeIndex) : -1.0f;
                this.pressX = this.downX;
                this.pressY = this.downY;
                animateEnergy(1.0f, 100L);
                removeCallbacks(this.longPress);
                postDelayed(this.longPress, 170L);
                invalidate();
                break;
            case 1:
                removeCallbacks(this.longPress);
                float x = motionEvent.getX() - this.downX;
                float y = motionEvent.getY() - this.downY;
                if (this.selecting) {
                    updateDragSelection(motionEvent.getX());
                    launchActive();
                } else if (!this.scrolling && Math.abs(x) < this.touchSlop && Math.abs(y) < this.touchSlop) {
                    this.activeIndex = indexAt(motionEvent.getX());
                    this.lensX = this.activeIndex >= 0 ? tileCenter(this.activeIndex) : -1.0f;
                    launchActive();
                }
                resetTouch();
                break;
            case 2:
                this.pressX = motionEvent.getX();
                this.pressY = motionEvent.getY();
                float x2 = motionEvent.getX() - this.downX;
                float y2 = motionEvent.getY() - this.downY;
                if (this.selecting) {
                    updateDragSelection(motionEvent.getX());
                } else if (Math.abs(x2) > this.touchSlop || Math.abs(y2) > this.touchSlop) {
                    removeCallbacks(this.longPress);
                    if (!this.scrolling && Math.abs(x2) >= Math.abs(y2)) {
                        this.scrolling = true;
                        this.activeIndex = -1;
                    }
                    if (this.scrolling) {
                        this.scrollOffset = clamp(this.scrollStart - x2, 0.0f, maxScroll());
                    }
                }
                invalidate();
                break;
            case MiStepLayout.CELL_COUNT /* 3 */:
                removeCallbacks(this.longPress);
                resetTouch();
                break;
        }
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void beginDragSelection() {
        String str;
        if (!this.down || this.scrolling) {
            return;
        }
        this.selecting = true;
        this.activeIndex = indexAt(this.pressX);
        this.lensX = this.activeIndex >= 0 ? tileCenter(this.activeIndex) : -1.0f;
        performHapticFeedback(0);
        if (this.activeIndex >= 0) {
            str = "已选择 " + this.apps.get(this.activeIndex).label;
        } else {
            str = "快捷应用选择";
        }
        announceForAccessibility(str);
        invalidate();
    }

    private void updateDragSelection(float f) {
        float f2 = getResources().getDisplayMetrics().density * 22.0f;
        if (f < f2) {
            this.scrollOffset = clamp(this.scrollOffset - (f2 * 0.12f), 0.0f, maxScroll());
        } else if (f > getWidth() - f2) {
            this.scrollOffset = clamp(this.scrollOffset + (f2 * 0.12f), 0.0f, maxScroll());
        }
        int iIndexAt = indexAt(f);
        if (iIndexAt != this.activeIndex && iIndexAt >= 0) {
            this.activeIndex = iIndexAt;
            moveLensTo(tileCenter(iIndexAt));
            performHapticFeedback(4);
            announceForAccessibility(this.apps.get(iIndexAt).label);
        }
        invalidate();
    }

    private void launchActive() {
        if (this.activeIndex >= 0 && this.activeIndex < this.apps.size() && this.listener != null) {
            this.selectedIndex = this.activeIndex;
            this.lensX = tileCenter(this.selectedIndex);
            this.listener.onLaunch(this.apps.get(this.activeIndex));
        }
    }

    private void drawIcon(Canvas canvas, int index, float centerX, float centerY,
            float scale, int alpha) {
        QuickApp app = apps.get(index);
        if (app == null || app.icon == null || alpha <= 0) return;
        int save = canvas.save();
        canvas.translate(centerX, centerY);
        canvas.scale(scale, scale);
        int half = Math.round(Math.min(iconSize, getHeight() / 1.18f) * 0.5f);
        app.icon.setBounds(-half, -half, half, half);
        app.icon.setAlpha(alpha);
        app.icon.draw(canvas);
        app.icon.setAlpha(255);
        canvas.restoreToCount(save);
    }

    private void resetTouch() {
        if (this.lensAnimator != null) {
            this.lensAnimator.cancel();
            this.lensAnimator = null;
        }
        this.down = false;
        this.selecting = false;
        this.scrolling = false;
        this.activeIndex = -1;
        this.lensX = -1.0f;
        animateEnergy(0.0f, 220L);
        invalidate();
    }

    private void moveLensTo(float f) {
        if (this.lensAnimator != null) {
            this.lensAnimator.cancel();
        }
        this.lensAnimator = ValueAnimator.ofFloat(this.lensX < 0.0f ? f : this.lensX, f);
        this.lensAnimator.setDuration(110L);
        this.lensAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        this.lensAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.jiusis.mistep.QuickAppRail.0
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                QuickAppRail.this.lambda$moveLensTo$0(valueAnimator);
            }
        });
        this.lensAnimator.start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$moveLensTo$0(ValueAnimator valueAnimator) {
        this.lensX = ((Float) valueAnimator.getAnimatedValue()).floatValue();
        invalidate();
    }

    private void animateEnergy(float f, long j) {
        if (this.energyAnimator != null) {
            this.energyAnimator.cancel();
        }
        this.energyAnimator = ValueAnimator.ofFloat(this.energy, f);
        this.energyAnimator.setDuration(j);
        this.energyAnimator.setInterpolator(new DecelerateInterpolator());
        this.energyAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.jiusis.mistep.QuickAppRail.3
            @Override // android.animation.ValueAnimator.AnimatorUpdateListener
            public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                QuickAppRail.this.lambda$animateEnergy$1(valueAnimator);
            }
        });
        this.energyAnimator.start();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$animateEnergy$1(ValueAnimator valueAnimator) {
        this.energy = ((Float) valueAnimator.getAnimatedValue()).floatValue();
        invalidate();
    }

    private int indexAt(float f) {
        if (this.apps.isEmpty()) {
            return -1;
        }
        return Math.max(0, Math.min(this.apps.size() - 1, (int) Math.floor(((f + this.scrollOffset) - this.padding) / this.tileWidth)));
    }

    private float tileCenter(int i) {
        return this.padding + (this.tileWidth * (i + 0.5f));
    }

    private float contentWidth() {
        return (this.padding * 2.0f) + (this.tileWidth * this.apps.size());
    }

    private float maxScroll() {
        return Math.max(0.0f, contentWidth() - getWidth());
    }

    private static float clamp(float f, float f2, float f3) {
        return Math.max(f2, Math.min(f3, f));
    }
}
