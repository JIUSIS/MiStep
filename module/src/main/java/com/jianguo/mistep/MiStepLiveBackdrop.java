package com.jianguo.mistep;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Wallpaper and empty Host controls below real task leashes; never app snapshots. */
final class MiStepLiveBackdrop implements AutoCloseable {
    Object surface;
    private Surface producer;
    private final Consumer<String> log;
    private boolean closed;
    private View retiringRoot;
    private View.OnAttachStateChangeListener detachListener;
    private Rect fullCrop, originalCrop, targetCrop;
    private SurfaceControl.Transaction pendingRemoval;
    private boolean removedWithDraw;

    private MiStepLiveBackdrop(Consumer<String> log) { this.log = log; }

    static MiStepLiveBackdrop create(Object parent, Bitmap wallpaper, int width, int height,
            Consumer<String> log) throws Exception {
        return create(parent, wallpaper, width, height, null, null, log);
    }

    static MiStepLiveBackdrop create(Object parent, Bitmap wallpaper, int width, int height,
            Rect originalSide, Rect targetSide, Consumer<String> log) throws Exception {
        return create(parent, wallpaper, width, height, originalSide, targetSide, null, log);
    }

    static MiStepLiveBackdrop create(Object parent, Bitmap wallpaper, int width, int height,
            Rect originalSide, Rect targetSide, Consumer<Canvas> emptyControls, Consumer<String> log) throws Exception {
        if (parent == null || wallpaper == null || wallpaper.isRecycled() || width <= 0 || height <= 0)
            throw new IllegalStateException("live wallpaper unavailable");
        MiStepLiveBackdrop lease = new MiStepLiveBackdrop(log);
        lease.fullCrop = new Rect(0, 0, wallpaper.getWidth(), wallpaper.getHeight());
        lease.originalCrop = bufferCrop(originalSide, wallpaper, width, height);
        lease.targetCrop = bufferCrop(targetSide, wallpaper, width, height);
        Object tx = null;
        try {
            Object builder = Class.forName("android.view.SurfaceControl$Builder").getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(builder, "setName", "MiStepLiveWallpaper");
            XposedHelpers.callMethod(builder, "setParent", parent);
            XposedHelpers.callMethod(builder, "setBufferSize", wallpaper.getWidth(), wallpaper.getHeight());
            XposedHelpers.callMethod(builder, "setFormat", PixelFormat.RGBA_8888);
            lease.surface = XposedHelpers.callMethod(builder, "build");
            lease.producer = (Surface) Surface.class.getDeclaredConstructor(Class.forName("android.view.SurfaceControl"))
                    .newInstance(lease.surface);
            Canvas canvas = lease.producer.lockCanvas(null);
            try {
                canvas.drawBitmap(wallpaper, null, new Rect(0, 0, wallpaper.getWidth(), wallpaper.getHeight()),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                if (emptyControls != null) emptyControls.accept(canvas);
            } finally { lease.producer.unlockCanvasAndPost(canvas); }
            tx = transaction();
            XposedHelpers.callMethod(tx, "setMatrix", lease.surface,
                    (float) width / wallpaper.getWidth(), 0f, 0f, (float) height / wallpaper.getHeight());
            XposedHelpers.callMethod(tx, "setPosition", lease.surface, 0f, 0f);
            XposedHelpers.callMethod(tx, "setWindowCrop", lease.surface,
                    lease.originalCrop);
            XposedHelpers.callMethod(tx, "setLayer", lease.surface, -100);
            // Cover SurfaceView holes before Host hides its cell drawings.
            XposedHelpers.callMethod(tx, "show", lease.surface);
            XposedHelpers.callMethod(tx, "apply");
            return lease;
        } catch (Throwable error) {
            lease.close();
            throw new IllegalStateException("live wallpaper preparation failed", error);
        } finally { lease.release(tx, "close"); }
    }

    private static Object transaction() throws Exception {
        return Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
    }

    private static Rect bufferCrop(Rect side, Bitmap bitmap, int width, int height) {
        if (side == null) return new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        double x = (double) bitmap.getWidth() / width, y = (double) bitmap.getHeight() / height;
        return new Rect((int) Math.floor(side.left * x), (int) Math.floor(side.top * y),
                (int) Math.ceil(side.right * x), (int) Math.ceil(side.bottom * y));
    }

    void stage(Object tx, boolean finishing, boolean rollback, boolean flip) {
        if (closed || surface == null) return;
        // Only temporarily reparented real tasks sit above this Host child. Once
        // tasks return to the display area, cover side holes without covering main.
        Rect crop = finishing ? (rollback ? originalCrop : targetCrop) : (flip ? fullCrop : originalCrop);
        XposedHelpers.callMethod(tx, "setWindowCrop", surface, crop);
        XposedHelpers.callMethod(tx, "show", surface);
    }

    private void release(Object object, String method) {
        if (object == null) return;
        try { XposedHelpers.callMethod(object, method); }
        catch (Throwable error) { log.accept("live wallpaper " + method + ": " + error); }
    }

    void closeAfterFrame(View view) {
        if (closed || retiringRoot != null) return;
        if (view == null || !view.isAttachedToWindow() || !view.isHardwareAccelerated()) {
            close(); return;
        }
        retiringRoot = view;
        detachListener = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { close(); }
        };
        try {
            view.addOnAttachStateChangeListener(detachListener);
            pendingRemoval = new SurfaceControl.Transaction();
            XposedHelpers.callMethod(pendingRemoval, "remove", surface);
            pendingRemoval.addTransactionCommittedListener(command -> view.post(command), () -> {
                removedWithDraw = true;
                close();
            });
            if (view.getRootSurfaceControl() == null
                    || !view.getRootSurfaceControl().applyTransactionOnDraw(pendingRemoval))
                throw new IllegalStateException("Host draw transaction refused");
            view.invalidate();
        } catch (Throwable error) {
            log.accept("live wallpaper Host frame unavailable: " + error);
            close();
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (retiringRoot != null && detachListener != null)
            retiringRoot.removeOnAttachStateChangeListener(detachListener);
        retiringRoot = null; detachListener = null;
        Object tx = null;
        try {
            if (surface != null && !removedWithDraw) {
                tx = transaction();
                XposedHelpers.callMethod(tx, "remove", surface);
                XposedHelpers.callMethod(tx, "apply");
            }
        } catch (Throwable error) { log.accept("live wallpaper removal: " + error); }
        finally {
            release(pendingRemoval, "close"); pendingRemoval = null;
            release(tx, "close"); release(surface, "release"); surface = null;
            if (producer != null) { producer.release(); producer = null; }
        }
    }
}
