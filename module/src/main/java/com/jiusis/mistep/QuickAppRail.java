package com.jiusis.mistep;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;
import java.util.Collections;
import java.util.List;

/* JADX INFO: loaded from: classes.dex */
final class QuickAppRail extends View {
    private int activeIndex;
    private List<QuickApp> apps;
    private final RectF bounds;
    private final Path clip;
    private boolean down;
    private float downX;
    private float downY;
    private final float iconSize;
    private final Listener listener;
    private final Runnable longPress;
    private final float padding;
    private float scrollOffset;
    private final OverScroller scroller;
    private final int minimumVelocity;
    private final int maximumVelocity;
    private VelocityTracker velocity;
    private float lastX;
    private boolean scrolling;
    private boolean moved;
    private boolean vertical;
    private boolean editing;
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
        void onCustomize();
    }

    QuickAppRail(Context context, List<QuickApp> list, Listener listener) {
        super(context);
        this.clip = new Path();
        this.bounds = new RectF();
        this.longPress = new Runnable() { // from class: com.jiusis.mistep.QuickAppRail.2
            @Override // java.lang.Runnable
            public final void run() {
                QuickAppRail.this.openAppPicker();
            }
        };
        this.activeIndex = -1;
        this.apps = list == null ? Collections.emptyList() : Collections.unmodifiableList(new java.util.ArrayList<>(list));
        this.listener = listener;
        float f = context.getResources().getDisplayMetrics().density;
        this.tileWidth = 48.0f * f;
        this.padding = 10.0f * f;
        this.iconSize = 31.0f * f;
        ViewConfiguration config = ViewConfiguration.get(context);
        this.touchSlop = config.getScaledTouchSlop();
        this.minimumVelocity = config.getScaledMinimumFlingVelocity();
        this.maximumVelocity = config.getScaledMaximumFlingVelocity();
        this.scroller = new OverScroller(context);
        setLayerType(2, null);
        setBackground(null);
        setForeground(null);
        setElevation(0f);
        setClickable(true);
        setFocusable(true);
        setContentDescription("快捷应用栏，点击打开，长按图标自定义应用");
        setOnLongClickListener(view -> {
            if (this.listener == null) return false;
            this.listener.onCustomize();
            return true;
        });
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
        scroller.forceFinished(true);
        float left = tileCenter(target) - tileWidth * .5f;
        if (left < scrollOffset) setOffset(left);
        else if (left + tileWidth > scrollOffset + getWidth())
            setOffset(left + tileWidth - getWidth());
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
                : Collections.unmodifiableList(new java.util.ArrayList<>(next));
        selectedIndex = stackFromIndex = stackToIndex = -1;
        stackProgress = 1f;
        scroller.forceFinished(true);
        scrollOffset = 0f;
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
        int first = Math.max(0, (int) Math.floor((scrollOffset - padding) / tileWidth) - 1);
        int end = Math.min(apps.size(),
                (int) Math.ceil((scrollOffset + getWidth() - padding) / tileWidth) + 1);
        for (int i = first; i < end; i++) {
            if (!stacking || (i != stackFromIndex && i != stackToIndex)) {
                float scale = i == this.activeIndex && this.down && !this.editing
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

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                resetTouch();
                if (stackAnimator != null && stackAnimator.isRunning()) {
                    stackGeneration++;
                    stackAnimator.cancel();
                    stackProgress = 1f;
                    stackFromIndex = stackToIndex = -1;
                }
                // A tap can stop a moving row without opening the icon below it.
                moved = !scroller.isFinished();
                scroller.forceFinished(true);
                down = true;
                downX = lastX = event.getX();
                downY = event.getY();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(event);
                if (!moved) {
                    activeIndex = indexAt(downX);
                    if (activeIndex >= 0) postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!down || editing) return true;
                velocity.addMovement(event);
                followFinger(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                if (down && !editing) {
                    velocity.addMovement(event);
                    followFinger(event.getX(), event.getY());
                    if (scrolling) {
                        velocity.computeCurrentVelocity(1000, maximumVelocity);
                        float speed = -velocity.getXVelocity(event.getPointerId(0));
                        if (Math.abs(speed) >= minimumVelocity) {
                            scroller.fling(Math.round(scrollOffset), 0, Math.round(speed), 0,
                                    0, Math.round(maxScroll()), 0, 0);
                            postInvalidateOnAnimation();
                        }
                    } else if (!moved && indexAt(event.getX()) == activeIndex) {
                        launchActive();
                    }
                }
                resetTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                resetTouch();
                return true;
            default:
                return true;
        }
    }

    private void followFinger(float x, float y) {
        float dx = x - downX, dy = y - downY;
        if (!scrolling && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop && activeIndex >= 0) {
            removeCallbacks(longPress);
            activeIndex = -1;
            invalidate();
        }
        if (!scrolling && !vertical && Math.max(Math.abs(dx), Math.abs(dy)) > touchSlop) {
            moved = true;
            if (maxScroll() > 0f && Math.abs(dx) > Math.abs(dy)) {
                scrolling = true;
                lastX = downX + Math.copySign(touchSlop, dx);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            } else {
                vertical = true;
            }
        }
        if (scrolling) {
            setOffset(scrollOffset + lastX - x);
            lastX = x;
        }
    }

    private void setOffset(float value) {
        float next = clamp(value, 0f, maxScroll());
        if (next == scrollOffset) return;
        scrollOffset = next;
        invalidate();
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            setOffset(scroller.getCurrX());
            postInvalidateOnAnimation();
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (w != oldW) scroller.forceFinished(true);
        setOffset(scrollOffset);
    }

    /* JADX INFO: Access modifiers changed from: private */
    private void openAppPicker() {
        if (!this.down || this.scrolling || this.editing || this.activeIndex < 0) return;
        this.editing = true;
        this.activeIndex = -1;
        performLongClick();
        invalidate();
    }

    private void launchActive() {
        if (this.activeIndex >= 0 && this.activeIndex < this.apps.size() && this.listener != null) {
            this.selectedIndex = this.activeIndex;
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
        removeCallbacks(longPress);
        if (scrolling && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        down = editing = scrolling = moved = vertical = false;
        activeIndex = -1;
        invalidate();
    }

    private int indexAt(float f) {
        if (this.apps.isEmpty() || f < 0 || f >= getWidth()) {
            return -1;
        }
        int index = (int) Math.floor(((f + this.scrollOffset) - this.padding) / this.tileWidth);
        return index >= 0 && index < this.apps.size() ? index : -1;
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(longPress);
        ++stackGeneration;
        if (stackAnimator != null) stackAnimator.cancel();
        resetTouch();
        scroller.forceFinished(true);
        super.onDetachedFromWindow();
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
