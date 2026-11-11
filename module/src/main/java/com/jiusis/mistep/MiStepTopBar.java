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
    private ControlsHeader tabletHeader;
    private WorkspaceOptions options = WorkspaceOptions.DEFAULT;
    private final QuickAppRail quickRail;
    private final QuickAppPicker appPicker = new QuickAppPicker();
    private List<QuickApp> availableApps;
    private final MiStepControlCenter controlCenter;
    private boolean mainOnRight;
    private int statusBarInset;
    private TopBarGeometry geometry;
    private List<Rect> cutoutBounds = java.util.Collections.emptyList();
    private Bitmap glassWallpaper;
    private int glassWidth, glassHeight;
    private int tabletHeaderHeight;
    private android.animation.ValueAnimator tabletToolsAnimator;
    private float tabletToolsProgress = 1f;
    private boolean tabletToolsExpanded;
    private boolean taskCellsCovered;

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
        boolean canCustomizeApps();
        void onOpenLandscape();
        default void onTaskCellsCovered(boolean covered) {}
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
        availableApps = new java.util.ArrayList<>(apps);
        quickRail = new QuickAppRail(context, QuickAppPins.arrange(context, availableApps), new QuickAppRail.Listener() {
            public void onLaunch(QuickApp app) { listener.onLaunch(app); }
            public void onCustomize() {
                if (!listener.canCustomizeApps()) return;
                MiStepClipboard.get(getContext()).close();
                MiStepScanner.closeImmediately();
                try {
                    appPicker.show(getContext(), availableApps, glassWallpaper, glassWidth, glassHeight,
                            () -> quickRail.setApps(QuickAppPins.arrange(getContext(), availableApps)));
                } catch (Throwable unavailable) {
                    appPicker.close();
                    android.widget.Toast.makeText(getContext(), "暂时无法打开应用选择框",
                            android.widget.Toast.LENGTH_SHORT).show();
                    android.util.Log.w("MiStepProbe", "quick app picker unavailable", unavailable);
                }
            }
        });
        addView(quickRail, new FrameLayout.LayoutParams(-1, 1));
        controlCenter = new MiStepControlCenter(context);
        addView(controlCenter, new FrameLayout.LayoutParams(-1, 1));
    }

    void setMainOnRight(boolean right) {
        if (mainOnRight == right) return;
        mainOnRight = right;
        header.swap.setRight(right);
        if (tabletHeader != null) tabletHeader.swap.setRight(right);
    }

    void applyOptions(WorkspaceOptions options) {
        this.options = options;
        applyHeaderOptions(header);
        if (tabletHeader != null) applyHeaderOptions(tabletHeader);
        controlCenter.applyOptions(options);
    }

    private void applyHeaderOptions(ControlsHeader header) {
        header.swap.settle(mainOnRight);
        for (int i = 0; i < header.buttons.length; i++)
            header.buttons[i].setVisibility(options.visible(WorkspaceOptions.HEADER_IDS[i]) ? View.VISIBLE : View.GONE);
        header.requestLayout();
    }

    FrameLayout createTabletHeader(int height, Runnable tools) {
        clearTabletHeader();
        tabletHeaderHeight = Math.max(0, height);
        setBackground(null);
        header.setVisibility(View.GONE);
        tabletHeader = new ControlsHeader(getContext(), tools);
        applyHeaderOptions(tabletHeader);
        requestLayout();
        return tabletHeader;
    }

    void clearTabletHeader() {
        cancelTabletToolsAnimation();
        if (tabletHeader != null) tabletHeader.swap.settle(mainOnRight);
        tabletHeader = null;
        tabletHeaderHeight = 0;
        tabletToolsProgress = 1f;
        if (glassWallpaper != null) setWallpaperBackdrop(glassWallpaper, glassWidth, glassHeight);
        else setBackground(HyperOsStyle.topBackground(getContext()));
        renderTabletTools();
        header.setVisibility(View.VISIBLE);
        requestLayout();
    }

    private void cancelTabletToolsAnimation() {
        android.animation.ValueAnimator old = tabletToolsAnimator;
        tabletToolsAnimator = null;
        if (old != null) old.cancel();
    }

    void setTabletToolsExpanded(boolean expanded, boolean animate) {
        cancelTabletToolsAnimation();
        tabletToolsExpanded = expanded;
        float target = expanded ? 1f : 0f;
        if (!animate || !isAttachedToWindow() || !isLaidOut()
                || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            tabletToolsProgress = target;
            renderTabletTools();
            setVisibility(expanded ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        setVisibility(View.VISIBLE);
        android.animation.ValueAnimator next = android.animation.ValueAnimator.ofFloat(tabletToolsProgress, target);
        tabletToolsAnimator = next;
        next.setDuration(220);
        next.setInterpolator(new android.view.animation.DecelerateInterpolator());
        next.addUpdateListener(value -> {
            if (tabletToolsAnimator != value) return;
            tabletToolsProgress = (float) value.getAnimatedValue();
            renderTabletTools();
        });
        next.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (tabletToolsAnimator != animation) return;
                tabletToolsAnimator = null;
                tabletToolsProgress = target;
                renderTabletTools();
                setVisibility(expanded ? View.VISIBLE : View.INVISIBLE);
            }
        });
        next.start();
    }

    private void renderTabletTools() {
        boolean covered = tabletHeaderHeight > 0 && (tabletToolsExpanded || tabletToolsProgress > 0f);
        if (taskCellsCovered != covered) {
            taskCellsCovered = covered;
            listener.onTaskCellsCovered(covered);
        }
        if (tabletHeaderHeight == 0) {
            setClipBounds(null);
            controlCenter.setAlpha(1f);
            quickRail.setAlpha(1f);
            return;
        }
        int top = Math.min(tabletHeaderHeight, getHeight());
        int bottom = top + Math.round(Math.max(0, getHeight() - top) * tabletToolsProgress);
        setClipBounds(new Rect(0, top, getWidth(), bottom));
        controlCenter.setAlpha(tabletToolsProgress);
        quickRail.setAlpha(tabletToolsProgress);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (tabletHeader != null && (!tabletToolsExpanded || tabletToolsAnimator != null)) return true;
        return super.dispatchTouchEvent(event);
    }

    void stepApp(int direction) { quickRail.stepAndLaunch(direction); }

    void setQuickApps(List<QuickApp> apps) {
        availableApps = new java.util.ArrayList<>(apps);
        quickRail.setApps(QuickAppPins.arrange(getContext(), availableApps));
    }

    void closeQuickAppPicker() { appPicker.close(); }

    void toggleClipboard(Bitmap backdrop, int screenWidth, int screenHeight) {
        if (geometry == null) return;
        MiStepScanner.close();
        Rect area = new Rect(geometry.edge, geometry.blankTop,
                Math.max(geometry.edge, getWidth() - geometry.edge), geometry.blankBottom);
        MiStepClipboard.get(getContext()).toggle(this, area, backdrop, screenWidth, screenHeight);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        renderTabletTools();
        if (oldw > 0 && (w != oldw || h != oldh)) {
            appPicker.close();
            MiStepClipboard.get(getContext()).close();
            MiStepScanner.closeImmediately();
        }
    }

    @Override protected void onDetachedFromWindow() {
        appPicker.close();
        cancelTabletToolsAnimation();
        if (tabletHeader != null) tabletHeader.swap.settle(mainOnRight);
        header.swap.settle(mainOnRight);
        MiStepScanner.closeImmediately();
        MiStepClipboard.get(getContext()).close();
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (appPicker != null && !isShown()) appPicker.close();
    }

    void setStatusBarInset(int value) {
        int next = Math.max(0, value);
        if (next == statusBarInset) return;
        statusBarInset = next;
        MiStepClipboard.get(getContext()).close();
        requestLayout();
    }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        cutoutBounds = android.os.Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null
                ? insets.getDisplayCutout().getBoundingRects() : java.util.Collections.emptyList();
        FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) controlCenter.getLayoutParams();
        controlCenter.setCutouts(cutoutBounds, c.leftMargin, c.topMargin);
        return insets;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = View.MeasureSpec.getSize(widthSpec);
        int height = View.MeasureSpec.getSize(heightSpec);
        geometry = new TopBarGeometry(width, height, Math.min(statusBarInset, height - 1),
                getResources().getDisplayMetrics().density, DeviceProfile.isTablet(getContext()), tabletHeaderHeight);
        FrameLayout.LayoutParams h = (FrameLayout.LayoutParams) header.getLayoutParams();
        h.height = geometry.headerHeight;
        h.topMargin = geometry.headerTop;
        FrameLayout.LayoutParams r = (FrameLayout.LayoutParams) quickRail.getLayoutParams();
        r.height = geometry.railHeight;
        r.topMargin = geometry.railTop;
        r.leftMargin = r.rightMargin = geometry.edge;
        FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) controlCenter.getLayoutParams();
        c.width = geometry.controlWidth;
        c.height = Math.max(1, geometry.blankBottom - geometry.blankTop);
        c.leftMargin = (width - c.width) / 2;
        c.topMargin = geometry.blankTop;
        controlCenter.setCutouts(cutoutBounds, c.leftMargin, c.topMargin);
        super.onMeasure(widthSpec, heightSpec);
    }

    void setWallpaperBackdrop(Bitmap bitmap, int width, int height) {
        if (bitmap == null || bitmap.isRecycled()) return;
        glassWallpaper = bitmap; glassWidth = width; glassHeight = height;
        controlCenter.setWallpaper(bitmap, width, height);
        if (tabletHeaderHeight > 0) { setBackground(null); return; }
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
        final MiStepIconButton[] buttons, swapButtons;
        final MiStepIconButton tools;
        final TopBarSwapAnimation swap;
        private final boolean compact;
        private int edge, buttonWidth, titleInset;
        private boolean layoutMainOnRight = mainOnRight;
        private Runnable afterSwapLayout;

        ControlsHeader(Context context) {
            this(context, null);
        }

        ControlsHeader(Context context, Runnable toggleTools) {
            super(context);
            compact = toggleTools != null;
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
            title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            title.setContentDescription(compact ? "Mi Step，点按选择竖向窗口应用"
                    : "Mi Step，双指上下滑动翻转顶部，点按打开横屏窗口");
            title.setOnClickListener(view->listener.onOpenLandscape());
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
            tools = compact ? button(MiStepIconButton.TOOLS, "展开或收起工具", toggleTools) : null;
            swapButtons = compact
                    ? new MiStepIconButton[]{exit, settings, clipboard, previous, next, torch, shot, record, tools}
                    : buttons;
            exit.setExitLeft(mainOnRight);
            swap = new TopBarSwapAnimation(new TopBarSwapAnimation.Target() {
                public boolean ready() { return isAttachedToWindow() && isLaidOut(); }
                public void cancel() {
                    afterSwapLayout = null;
                    for (MiStepIconButton button : swapButtons) button.animate().cancel();
                }
                public void fade(float alpha, long duration, Runnable finished) {
                    java.util.ArrayList<MiStepIconButton> visible = new java.util.ArrayList<>();
                    for (MiStepIconButton button : swapButtons) if (button.getVisibility() == View.VISIBLE) visible.add(button);
                    if (visible.isEmpty()) { finished.run(); return; }
                    for (int i = 0; i < visible.size(); i++) {
                        MiStepIconButton button = visible.get(i);
                        button.setEnabled(false);
                        button.animate().alpha(alpha).setStartDelay(0).setDuration(duration)
                                .withEndAction(i == visible.size() - 1 ? finished : null).start();
                    }
                }
                public void move(boolean right, Runnable laidOut) {
                    layoutMainOnRight = right;
                    exit.setExitLeft(right);
                    afterSwapLayout = laidOut;
                    requestLayout();
                }
                public void finish() {
                    for (MiStepIconButton button : swapButtons) {
                        button.setAlpha(1f); button.setScaleX(1f); button.setScaleY(1f);
                        button.setEnabled(true);
                    }
                }
            }, mainOnRight);
        }

        private MiStepIconButton button(int icon, String label, Runnable action) {
            MiStepIconButton view = new MiStepIconButton(getContext(), icon, label);
            view.setTooltipText(label);
            view.setOnClickListener(v -> action.run());
            addView(view);
            return view;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec);
            int height = View.MeasureSpec.getSize(heightSpec);
            if (compact) {
                float density = getResources().getDisplayMetrics().density;
                edge = Math.max(2, Math.round(8f * density));
                buttonWidth = Math.max(1, Math.min(Math.round(44f * density),
                        (width - 2 * edge - Math.round(100f * density)) / 10));
                titleInset = edge + 5 * buttonWidth;
            } else {
                edge = geometry.edge;
                buttonWidth = geometry.buttonWidth;
                titleInset = geometry.titleInset;
            }
            int titleWidth = Math.max(1, width - 2 * titleInset);
            float preferred = 21f * getResources().getDisplayMetrics().scaledDensity;
            title.getPaint().setTextSize(preferred);
            float textWidth = title.getPaint().measureText("Mi Step");
            android.graphics.Paint.FontMetrics metrics = title.getPaint().getFontMetrics();
            float fit = Math.min(titleWidth / Math.max(1f, textWidth),
                    height / Math.max(1f, metrics.descent - metrics.ascent));
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    preferred * Math.min(1f, fit));
            title.measure(View.MeasureSpec.makeMeasureSpec(titleWidth, View.MeasureSpec.EXACTLY), heightSpec);
            for (MiStepIconButton button : buttons) if (button.getVisibility() != View.GONE)
                button.measure(View.MeasureSpec.makeMeasureSpec(buttonWidth, View.MeasureSpec.EXACTLY), heightSpec);
            if (compact) tools.measure(View.MeasureSpec.makeMeasureSpec(buttonWidth, View.MeasureSpec.EXACTLY), heightSpec);
            setMeasuredDimension(width, height);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, height = b - t;
            title.layout(titleInset, 0, width - titleInset, height);
            MiStepIconButton[] left = compact
                    ? layoutMainOnRight
                    ? new MiStepIconButton[]{exit, settings, clipboard, shot}
                    : new MiStepIconButton[]{tools, previous, next, torch, record}
                    : layoutMainOnRight
                    ? new MiStepIconButton[]{exit, settings, clipboard, shot}
                    : new MiStepIconButton[]{previous, next, torch, record};
            MiStepIconButton[] right = compact
                    ? layoutMainOnRight
                    ? new MiStepIconButton[]{record, torch, previous, next, tools}
                    : new MiStepIconButton[]{shot, clipboard, settings, exit}
                    : layoutMainOnRight
                    ? new MiStepIconButton[]{record, torch, previous, next}
                    : new MiStepIconButton[]{shot, clipboard, settings, exit};
            int x = edge;
            for (MiStepIconButton button : left) if (button.getVisibility() != View.GONE) {
                button.layout(x, 0, x + buttonWidth, height); x += buttonWidth;
            }
            int visibleRight = 0;
            for (MiStepIconButton button : right) if (button.getVisibility() != View.GONE) visibleRight++;
            x = width - edge - visibleRight * buttonWidth;
            for (MiStepIconButton button : right) if (button.getVisibility() != View.GONE) {
                button.layout(x, 0, x + buttonWidth, height); x += buttonWidth;
            }
            // Buttons move only at zero opacity. The fixed title never fades or gets crossed.
            Runnable laidOut = afterSwapLayout;
            afterSwapLayout = null;
            if (laidOut != null) laidOut.run();
        }
    }
}
