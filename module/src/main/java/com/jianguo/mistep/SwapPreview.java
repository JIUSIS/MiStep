package com.jianguo.mistep;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import de.robv.android.xposed.XposedHelpers;

/** Short visual bridge while the original tasks exchange owners. Never owns a task/leash. */
final class SwapPreview {
    static final int EXCHANGE = 0, COLLECT = 1, PROMOTE = 2, LIVE_EXCHANGE = 3;
    static final long DURATION_MS = 300L;
    private final FrameLayout parent;
    private final FrameLayout overlayRoot;
    private final WindowManager overlayWindowManager;
    private final WindowManager.LayoutParams overlayParams;
    private final BridgeView overlay;
    private final Bitmap main, side;
    private final Bitmap mainBackdrop;
    private final Bitmap transitionBackdrop;
    private final Bitmap sideColumn;
    private final int sideColumnFromX;
    private final int sideColumnToX;
    private final int sideColumnTop;
    private final int sideColumnHeight;
    private final ValueAnimator animator;
    private Runnable firstFrameAction;
    private boolean firstFrameDrawn;
    private boolean started;
    private boolean closed;
    private boolean removalFailed;

    /** Target Android 17 moved capture APIs to *Internal; WMS resolves display 0. */
    static Bitmap capture(ClassLoader cl, int width, int height) throws Throwable {
        Class<?> builder = XposedHelpers.findClass(
                "android.window.ScreenCaptureInternal$CaptureArgs$Builder", cl);
        Object args = XposedHelpers.newInstance(builder);
        XposedHelpers.callMethod(args, "setFrameScale", 1f);
        XposedHelpers.callMethod(args, "setIncludeSystemOverlays", true);
        Class<?> capture = XposedHelpers.findClass("android.window.ScreenCaptureInternal", cl);
        Object listener = XposedHelpers.callStaticMethod(capture, "createSyncCaptureListener");
        Class<?> global = XposedHelpers.findClass("android.view.WindowManagerGlobal", cl);
        Object wm = XposedHelpers.callStaticMethod(global, "getWindowManagerService");
        XposedHelpers.callMethod(wm, "captureDisplay", 0,
                XposedHelpers.callMethod(args, "build"), listener);
        Object result = XposedHelpers.callMethod(listener, "getBuffer");
        if (result == null) throw new IllegalStateException("display capture unavailable");
        Object hardware = XposedHelpers.callMethod(result, "getHardwareBuffer");
        try {
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(result, "containsSecureLayers")))
                throw new IllegalStateException("secure scene has no preview");
            Bitmap bitmap = (Bitmap) XposedHelpers.callMethod(result, "asBitmap");
            if (bitmap == null) throw new IllegalStateException("capture has no bitmap");
            // Copy once: the overlay must not retain a hardware buffer or a whole screen.
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            bitmap.recycle();
            if (copy == null || copy.getWidth() != width || copy.getHeight() != height)
                throw new IllegalStateException("capture geometry changed");
            return copy;
        } finally {
            if (hardware != null) XposedHelpers.callMethod(hardware, "close");
        }
    }

    SwapPreview(FrameLayout parent, Bitmap screen, Rect mainRect, Rect sideRect) {
        this(parent, screen, mainRect, sideRect, EXCHANGE);
    }

    SwapPreview(FrameLayout parent, Bitmap screen, Rect mainRect, Rect sideRect, int mode) {
        this(parent, screen, mainRect, sideRect, mode, null);
    }

    SwapPreview(FrameLayout parent, Bitmap screen, Rect mainRect, Rect sideRect, int mode,
            Bitmap mainBackdrop) {
        this(parent, screen, mainRect, sideRect, mainRect, sideRect, mode, mainBackdrop);
    }

    SwapPreview(FrameLayout parent, Bitmap screen, Rect mainFrom, Rect sideFrom,
            Rect mainTo, Rect sideTo, int mode) {
        this(parent, screen, mainFrom, sideFrom, mainTo, sideTo, mode, null);
    }

    SwapPreview(FrameLayout parent, Bitmap screen, Rect mainFrom, Rect sideFrom,
            Rect mainTo, Rect sideTo, int mode, Bitmap mainBackdrop) {
        this(parent, screen, mainFrom, sideFrom, mainTo, sideTo, mode,
                mainBackdrop, null, 0, 0, 0, 0, false);
    }

    SwapPreview(FrameLayout parent, Bitmap screen, Rect mainFrom, Rect sideFrom,
            Rect mainTo, Rect sideTo, int mode, Bitmap mainBackdrop,
            Bitmap sideColumn, int sideColumnFromX, int sideColumnToX,
            int sideColumnTop, int sideColumnHeight) {
        this(parent, screen, mainFrom, sideFrom, mainTo, sideTo, mode,
                mainBackdrop, sideColumn, sideColumnFromX, sideColumnToX,
                sideColumnTop, sideColumnHeight, false);
    }

    /**
     * @param liveMain true 时不再绘制主窗截图，而是在桥上给主窗挖洞：下面的真实主任务
     *                 表面由宿主按同一时长逐帧缩进格子（view 级主窗动画），桥上只保留
     *                 侧栏/背景，避免重复画面。
     */
    SwapPreview(FrameLayout parent, Bitmap screen, Rect mainFrom, Rect sideFrom,
            Rect mainTo, Rect sideTo, int mode, Bitmap mainBackdrop,
            Bitmap sideColumn, int sideColumnFromX, int sideColumnToX,
            int sideColumnTop, int sideColumnHeight, boolean liveMain) {
        this(parent, screen, mainFrom, sideFrom, mainTo, sideTo, mode, mainBackdrop,
                sideColumn, sideColumnFromX, sideColumnToX, sideColumnTop,
                sideColumnHeight, liveMain, null);
    }

    SwapPreview(FrameLayout parent, Bitmap screen, Rect mainFrom, Rect sideFrom,
            Rect mainTo, Rect sideTo, int mode, Bitmap mainBackdrop,
            Bitmap sideColumn, int sideColumnFromX, int sideColumnToX,
            int sideColumnTop, int sideColumnHeight, boolean liveMain,
            Bitmap transitionBackdrop) {
        this.parent = parent;
        this.mainBackdrop = mainBackdrop;
        this.transitionBackdrop = transitionBackdrop;
        this.sideColumn = sideColumn;
        this.sideColumnFromX = sideColumnFromX;
        this.sideColumnToX = sideColumnToX;
        this.sideColumnTop = sideColumnTop;
        this.sideColumnHeight = sideColumnHeight;
        // Keep the current main image underneath every transition. PROMOTE previously
        // used no main bitmap while also painting an opaque background, producing a black
        // main pane and a visibly cropped app fragment during side→main promotion.
        main = crop(screen, mainFrom);
        side = mode == COLLECT ? null : crop(screen, sideFrom);
        overlay = new BridgeView(parent.getContext(),
                mainFrom, sideFrom, mainTo, sideTo, mode == LIVE_EXCHANGE,
                liveMain, transitionBackdrop);
        overlay.setElevation(1000f);
        overlay.setOnTouchListener((v, event) -> true);
        overlay.setBackdrop(mode == COLLECT ? mainBackdrop : null);
        // TaskView uses a child SurfaceControl; normal sibling View elevation does not
        // reliably cover it. A separate top-level overlay owns the screen coordinates
        // and prevents the old/new task surfaces showing through during the handoff.
        overlayRoot = new FrameLayout(parent.getContext());
        overlayRoot.setClipChildren(false);
        overlayRoot.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlayWindowManager = (WindowManager) parent.getContext()
                .getSystemService(Context.WINDOW_SERVICE);
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.setTitle("MiStepTransition");
        // A freshly added overlay otherwise inherits MIUI's enter animation; its
        // translucent/transformed first frames expose the live TaskViews underneath.
        overlayParams.windowAnimations = 0;
        HostWindowPolicy.configure(overlayParams);
        try { overlayParams.setFitInsetsTypes(0); } catch (Throwable ignored) {}
        try {
            overlayParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } catch (Throwable ignored) {}
        overlayWindowManager.addView(overlayRoot, overlayParams);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            overlay.setFraction(f);
        });
    }

    /** Start only after the opaque bridge has actually reached the screen. */
    void runAfterFirstFrame(Runnable action) {
        if (closed) return;
        if (firstFrameDrawn) overlayRoot.post(action);
        else firstFrameAction = action;
    }

    void start() {
        if (!closed && !started) {
            started = true;
            animator.start();
            // TaskView may reflow its child surface midway through the same transition.
            // Keep the official geometry, but replace the live cutout with the last
            // captured side image before the compositor can expose an empty surface.
            if (overlay.liveSelected && !overlay.liveMain)
                overlayRoot.postDelayed(this::seal, 120L);
        }
    }

    /** Replace the live-selected cutout with its final snapshot during task handoff. */
    void seal() {
        if (closed) return;
        overlay.sealSnapshot();
    }

    static Bitmap crop(Bitmap screen, Rect rect) {
        return Bitmap.createBitmap(screen, rect.left, rect.top, rect.width(), rect.height());
    }

    /** A movable snapshot of the whole side column with the promoted cell blanked out. */
    static Bitmap sideColumn(Bitmap screen, Rect column, Rect selectedCell) {
        Bitmap out = Bitmap.createBitmap(column.width(), column.height(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(screen, column, new Rect(0, 0, out.getWidth(), out.getHeight()), paint);
        Paint blank = new Paint();
        blank.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        int top = Math.max(0, selectedCell.top - column.top);
        int bottom = Math.min(out.getHeight(), selectedCell.bottom - column.top);
        canvas.drawRect(0, top, out.getWidth(), bottom, blank);
        return out;
    }

    private static RectF interpolate(Rect from, Rect to, float fraction) {
        return new RectF(
                from.left + (to.left - from.left) * fraction,
                from.top + (to.top - from.top) * fraction,
                from.right + (to.right - from.right) * fraction,
                from.bottom + (to.bottom - from.bottom) * fraction);
    }

    private final class BridgeView extends View {
        private final Rect mainFrom;
        private final Rect sideFrom;
        private final Rect mainTo;
        private final Rect sideTo;
        private final boolean liveSelected;
        private final boolean liveMain;
        private final Bitmap transitionBackdrop;
        private final Rect backgroundRect = new Rect();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint background = new Paint();
        private float fraction;
        private boolean sealed;

        BridgeView(android.content.Context context, Rect mainFrom, Rect sideFrom,
                Rect mainTo, Rect sideTo, boolean liveSelected, boolean liveMain,
                Bitmap transitionBackdrop) {
            super(context);
            this.mainFrom = new Rect(mainFrom);
            this.sideFrom = new Rect(sideFrom);
            this.mainTo = new Rect(mainTo);
            this.sideTo = new Rect(sideTo);
            this.liveSelected = liveSelected;
            this.liveMain = liveMain;
            this.transitionBackdrop = transitionBackdrop;
            backgroundRect.set(
                    Math.min(Math.min(mainFrom.left, mainTo.left),
                            Math.min(sideFrom.left, sideTo.left)),
                    Math.min(Math.min(mainFrom.top, mainTo.top),
                            Math.min(sideFrom.top, sideTo.top)),
                    Math.max(Math.max(mainFrom.right, mainTo.right),
                            Math.max(sideFrom.right, sideTo.right)),
                    Math.max(Math.max(mainFrom.bottom, mainTo.bottom),
                            Math.max(sideFrom.bottom, sideTo.bottom)));
            background.setColor(0xFF171B18);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        void setBackdrop(Bitmap bitmap) {
            invalidate();
        }

        void setFraction(float value) {
            fraction = value;
            invalidate();
        }

        void sealSnapshot() {
            sealed = true;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Hide the live windows below while the two captured frames morph. Without
            // this opaque base the old and new TaskViews remain visible underneath the
            // moving snapshots and produce duplicate frames, dark gaps and false motion.
            int checkpoint = canvas.save();
            if (liveSelected && !sealed) {
                // The selected TaskView is a real live surface below this overlay.
                canvas.clipOutRect(interpolate(sideFrom, mainTo, fraction));
            }
            if (liveMain) {
                // 真实主任务表面由宿主逐帧缩进格子；整块主区都挖洞：主任务缩走后
                // 露出的就是它下面的 Home，而不是桥的深色底（避免交换时大面积发黑）。
                canvas.clipOutRect(mainFrom);
            }
            if (transitionBackdrop != null && !transitionBackdrop.isRecycled()) {
                canvas.drawBitmap(transitionBackdrop, null, backgroundRect, paint);
            } else {
                canvas.drawRect(backgroundRect, background);
            }

            if (mainBackdrop != null && !mainBackdrop.isRecycled()) {
                canvas.drawBitmap(mainBackdrop, null, mainTo, paint);
            }
            if (sideColumn != null && !sideColumn.isRecycled()) {
                // Official Mi Step flips the side guideline; keep the full column on
                // that same path rather than snapping or fading individual cells.
                float left = sideColumnFromX + (sideColumnToX - sideColumnFromX) * fraction;
                RectF destination = new RectF(left, sideColumnTop,
                        left + sideColumn.getWidth(),
                        sideColumnTop + sideColumnHeight);
                canvas.drawBitmap(sideColumn, null, destination, paint);
            }
            if (!liveMain && main != null && !main.isRecycled()) {
                canvas.drawBitmap(main, null, interpolate(mainFrom, sideTo, fraction), paint);
            }
            // The growing destination is painted last, matching its role as the new main
            // window and keeping the two live applications distinct throughout the bridge.
            if (side != null && !side.isRecycled() && (!liveSelected || sealed)) {
                canvas.drawBitmap(side, null, interpolate(sideFrom, mainTo, fraction), paint);
            }
            canvas.restoreToCount(checkpoint);
            if (!firstFrameDrawn) {
                firstFrameDrawn = true;
                Runnable action = firstFrameAction;
                firstFrameAction = null;
                if (action != null) overlayRoot.post(action);
                else start();
            }
        }
    }

    void close() {
        close(null);
    }

    /** Completion means the bridge root detached, rather than only removeView being queued. */
    void close(Runnable onDetached) {
        final class Completion implements View.OnAttachStateChangeListener, Runnable {
            boolean delivered;
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { run(); }
            @Override public void run() {
                if (delivered) return;
                delivered = true;
                overlayRoot.removeOnAttachStateChangeListener(this);
                onDetached.run();
            }
        }
        Completion completion = onDetached == null ? null : new Completion();
        if (completion != null) {
            if (!overlayRoot.isAttachedToWindow() || removalFailed) completion.run();
            else overlayRoot.addOnAttachStateChangeListener(completion);
        }
        if (closed) return;
        closed = true;
        animator.cancel();
        try { overlayWindowManager.removeView(overlayRoot); }
        catch (Throwable error) {
            // Release the caller's drawing lease on a failed removal too; do not
            // claim this fallback proves that the compositor has removed the window.
            removalFailed = true;
            android.util.Log.w("MiStepProbe", "bridge window removal failed", error);
            if (completion != null) completion.run();
        }
        // RenderThread may still own the last display list: let Bitmap references retire
        // with the removed bridge view rather than recycling beneath an in-flight frame.
    }

}
