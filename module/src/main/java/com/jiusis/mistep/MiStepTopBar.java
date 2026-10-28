package com.jiusis.mistep;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.List;

final class MiStepTopBar extends FrameLayout {
    private final Listener listener;
    private final ControlsHeader header;
    private final QuickAppRail quickRail;
    private final MiStepControlCenter controlCenter;
    private boolean mainOnRight;
    private int statusBarInset;
    private TopBarGeometry geometry;
    private Bitmap glassWallpaper;
    private int glassWidth, glassHeight;

    interface Listener {
        void onOpenSettings();
        void onOpenClipboard();
        void onToggleTorch();
        void onScreenshot();
        void onScreenRecord();
        void onDragEnd(MotionEvent event);
        void onDragMove(MotionEvent event);
        void onDragStart(MotionEvent event);
        void onExit();
        void onLaunch(QuickApp app);
    }

    MiStepTopBar(Context context, MiStepLayout layout, List<QuickApp> apps, Listener listener) {
        super(context);
        this.listener = listener;
        mainOnRight = layout.mainOnRight;
        setBackground(HyperOsStyle.topBackground(context));
        setClipChildren(false);
        setClipToPadding(false);
        header = new ControlsHeader(context);
        addView(header, new FrameLayout.LayoutParams(-1, 1));
        quickRail = new QuickAppRail(context, apps, listener::onLaunch);
        addView(quickRail, new FrameLayout.LayoutParams(-1, 1));
        controlCenter = new MiStepControlCenter(context);
        addView(controlCenter, new FrameLayout.LayoutParams(-1, 1));
    }

    void setMainOnRight(boolean right) {
        if (mainOnRight == right) return;
        mainOnRight = right;
        header.swap.setRight(right);
    }

    void stepApp(int direction) { quickRail.stepAndLaunch(direction); }

    void setQuickApps(List<QuickApp> apps) { quickRail.setApps(apps); }

    void toggleClipboard(Bitmap backdrop, int screenWidth, int screenHeight) {
        if (geometry == null) return;
        MiStepScanner.close();
        Rect area = new Rect(geometry.edge, geometry.blankTop,
                Math.max(geometry.edge, getWidth() - geometry.edge), geometry.blankBottom);
        MiStepClipboard.get(getContext()).toggle(this, area, backdrop, screenWidth, screenHeight);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw > 0 && (w != oldw || h != oldh)) {
            MiStepClipboard.get(getContext()).close();
            MiStepScanner.closeImmediately();
        }
    }

    @Override protected void onDetachedFromWindow() {
        header.swap.settle(mainOnRight);
        MiStepScanner.closeImmediately();
        MiStepClipboard.get(getContext()).close();
        super.onDetachedFromWindow();
    }

    void setStatusBarInset(int value) {
        int next = Math.max(0, value);
        if (next == statusBarInset) return;
        statusBarInset = next;
        MiStepClipboard.get(getContext()).close();
        requestLayout();
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        List<Rect> holes = android.os.Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null
                ? insets.getDisplayCutout().getBoundingRects() : java.util.Collections.emptyList();
        float density = getResources().getDisplayMetrics().density;
        controlCenter.setCutouts(holes, geometry == null ? Math.round(8 * density) : geometry.edge,
                geometry == null ? Math.round(14 * density) : geometry.blankTop);
        return insets;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = View.MeasureSpec.getSize(widthSpec);
        int height = View.MeasureSpec.getSize(heightSpec);
        geometry = new TopBarGeometry(width, height, Math.min(statusBarInset, height - 1),
                getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams h = (FrameLayout.LayoutParams) header.getLayoutParams();
        h.height = geometry.headerHeight;
        h.topMargin = geometry.headerTop;
        FrameLayout.LayoutParams r = (FrameLayout.LayoutParams) quickRail.getLayoutParams();
        r.height = geometry.railHeight;
        r.topMargin = geometry.railTop;
        r.leftMargin = r.rightMargin = geometry.edge;
        FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) controlCenter.getLayoutParams();
        c.width = Math.max(1, width - 2 * geometry.edge);
        c.height = Math.max(1, geometry.blankBottom - geometry.blankTop);
        c.leftMargin = geometry.edge;
        c.topMargin = geometry.blankTop;
        super.onMeasure(widthSpec, heightSpec);
    }

    void setWallpaperBackdrop(Bitmap bitmap, int width, int height) {
        if (bitmap == null || bitmap.isRecycled()) return;
        glassWallpaper = bitmap; glassWidth = width; glassHeight = height;
        controlCenter.setWallpaper(bitmap, width, height);
        setBackground(new LayerDrawable(new Drawable[]{
                new WallpaperCropDrawable(bitmap, width, height, 0, 0,
                        HyperOsStyle.topWallpaperTint(getContext())),
                HyperOsStyle.topWallpaperWash(getContext())}));
    }

    void scannerBubble(View anchor, String title, java.util.function.IntConsumer selected) {
        if (geometry == null) return;
        MiStepClipboard.get(getContext()).close();
        Rect area = controlCenter.codeBounds();
        area.offset(controlCenter.getLeft(), controlCenter.getTop());
        MiStepScanner.bubble(this, anchor, area, glassWallpaper,
                glassWidth, glassHeight, title, selected);
    }

    void coverCodeButtons(boolean covered, boolean animate) {
        controlCenter.coverCodeButtons(covered, animate);
    }

    private final class ControlsHeader extends FrameLayout {
        final TextView title;
        final MiStepIconButton exit, settings, clipboard, previous, next, torch, shot, record;
        final MiStepIconButton[] buttons;
        final TopBarSwapAnimation swap;
        private boolean layoutMainOnRight = mainOnRight;
        private Runnable afterSwapLayout;

        ControlsHeader(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
            title = new TextView(context);
            title.setText("Mi Step");
            title.setTextColor(HyperOsStyle.textPrimary(context));
            title.setGravity(android.view.Gravity.CENTER);
            title.setSingleLine(true);
            title.setIncludeFontPadding(false);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(title);
            exit = button(MiStepIconButton.EXIT, "退出 Mi Step", listener::onExit);
            settings = button(MiStepIconButton.SETTINGS, "打开系统设置", listener::onOpenSettings);
            clipboard = button(MiStepIconButton.CLIPBOARD, "剪贴板历史", listener::onOpenClipboard);
            previous = button(MiStepIconButton.PREVIOUS, "上一个快捷应用", () -> stepApp(-1));
            next = button(MiStepIconButton.NEXT, "下一个快捷应用", () -> stepApp(1));
            torch = button(MiStepIconButton.TORCH, "手电筒", listener::onToggleTorch);
            shot = button(MiStepIconButton.SCREENSHOT, "截图", listener::onScreenshot);
            record = button(MiStepIconButton.RECORD, "录屏", listener::onScreenRecord);
            buttons = new MiStepIconButton[]{exit, settings, clipboard, previous, next, torch, shot, record};
            exit.setExitLeft(mainOnRight);
            swap = new TopBarSwapAnimation(new TopBarSwapAnimation.Target() {
                public boolean ready() { return isAttachedToWindow() && isLaidOut(); }
                public void cancel() {
                    afterSwapLayout = null;
                    for (MiStepIconButton button : buttons) button.animate().cancel();
                }
                public void fade(float alpha, long duration, Runnable finished) {
                    for (int i = 0; i < buttons.length; i++) {
                        MiStepIconButton button = buttons[i];
                        button.setEnabled(false);
                        button.animate().alpha(alpha).setStartDelay(0).setDuration(duration)
                                .withEndAction(i == buttons.length - 1 ? finished : null).start();
                    }
                }
                public void move(boolean right, Runnable laidOut) {
                    layoutMainOnRight = right;
                    exit.setExitLeft(right);
                    afterSwapLayout = laidOut;
                    requestLayout();
                }
                public void finish() {
                    for (MiStepIconButton button : buttons) {
                        button.setAlpha(1f); button.setScaleX(1f); button.setScaleY(1f);
                        button.setEnabled(true);
                    }
                }
            }, mainOnRight);
        }

        private MiStepIconButton button(int icon, String label, Runnable action) {
            MiStepIconButton view = new MiStepIconButton(getContext(), icon, label);
            view.setOnClickListener(v -> action.run());
            addView(view);
            return view;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec);
            int height = View.MeasureSpec.getSize(heightSpec);
            int titleWidth = Math.max(1, width - 2 * geometry.titleInset);
            float preferred = 21f * getResources().getDisplayMetrics().scaledDensity;
            title.getPaint().setTextSize(preferred);
            float textWidth = title.getPaint().measureText("Mi Step");
            android.graphics.Paint.FontMetrics metrics = title.getPaint().getFontMetrics();
            float fit = Math.min(titleWidth / Math.max(1f, textWidth),
                    height / Math.max(1f, metrics.descent - metrics.ascent));
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    preferred * Math.min(1f, fit));
            title.measure(View.MeasureSpec.makeMeasureSpec(titleWidth, View.MeasureSpec.EXACTLY), heightSpec);
            for (MiStepIconButton button : buttons)
                button.measure(View.MeasureSpec.makeMeasureSpec(geometry.buttonWidth, View.MeasureSpec.EXACTLY), heightSpec);
            setMeasuredDimension(width, height);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, height = b - t;
            title.layout(geometry.titleInset, 0, width - geometry.titleInset, height);
            MiStepIconButton[] left = layoutMainOnRight
                    ? new MiStepIconButton[]{exit, settings, clipboard, shot}
                    : new MiStepIconButton[]{previous, next, torch, record};
            MiStepIconButton[] right = layoutMainOnRight
                    ? new MiStepIconButton[]{record, torch, previous, next}
                    : new MiStepIconButton[]{shot, clipboard, settings, exit};
            for (int i = 0; i < 4; i++) {
                int x = geometry.edge + i * geometry.buttonWidth;
                left[i].layout(x, 0, x + geometry.buttonWidth, height);
                x = width - geometry.edge - (4 - i) * geometry.buttonWidth;
                right[i].layout(x, 0, x + geometry.buttonWidth, height);
            }
            // Buttons move only at zero opacity. The fixed title never fades or gets crossed.
            Runnable laidOut = afterSwapLayout;
            afterSwapLayout = null;
            if (laidOut != null) laidOut.run();
        }
    }
}
