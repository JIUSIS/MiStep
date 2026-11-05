package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Visual bridge only for main <-> upper task. Native task ownership stays with Host. */
final class MiStepTopTaskTransition {
    private final FrameLayout host;
    private final WindowManager windows;
    private final WindowManager.LayoutParams params;
    private final Face face;
    private final int width, height;
    private final BooleanSupplier current;
    private final Runnable handoff;
    private final Consumer<MiStepTopTaskTransition> finished;
    private final Consumer<String> log;
    private Object liveSurface;
    private View collectionView;
    private BooleanSupplier collectionSourceCurrent;
    private float collectionPivotX, collectionPivotY;
    private float collectionScaleX, collectionScaleY;
    private float collectionFrameScale=-1f;
    private Runnable nativeRestore;
    private boolean liveSurfaceAtEnd;
    private ValueAnimator animator;
    private boolean attached, closed, firstDraw, collapsed, revealing;
    private final Runnable timeout=this::close;
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) {close();}
    };

    MiStepTopTaskTransition(FrameLayout host,int width,int height,
            BooleanSupplier current,Runnable handoff,
            Consumer<MiStepTopTaskTransition> finished,Consumer<String> log) throws Throwable {
        this.host=host;this.width=width;this.height=height;
        this.current=current;this.handoff=handoff;this.finished=finished;this.log=log;
        face=new Face();
        windows=(WindowManager)host.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        params=new WindowManager.LayoutParams(-1,-1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        params.windowAnimations=0;
        params.setTitle("MiStepUpperTransition");
        HostWindowPolicy.configure(params);
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        face.setOnTouchListener((view,event)->true);
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void start() {
        if(closed || attached)return;
        if(!current.getAsBoolean()) {close();return;}
        windows.addView(face,params);attached=true;
        log.accept("upper live center start");
        host.addOnAttachStateChangeListener(attachment);
        // A failed/late native handoff must never leave an input-blocking overlay behind.
        host.postDelayed(timeout,5000L);
    }

    void setLiveSurface(Object surface) {
        liveSurface=surface;
    }

    void collect(Object surface,BooleanSupplier sourceCurrent,View destination) {
        liveSurface=surface;
        collectionSourceCurrent=sourceCurrent;
        collectionView=destination;
        collectionPivotX=destination.getPivotX();collectionPivotY=destination.getPivotY();
        collectionScaleX=destination.getScaleX();collectionScaleY=destination.getScaleY();
    }

    void setNativeRestore(Runnable restore) {
        if(closed)restore.run();
        else nativeRestore=restore;
    }

    /** Keep geometry refreshes from exposing the destination before native admission. */
    void applyCollectionFrame(View destination) {
        if(!closed && collectionView==destination && collectionFrameScale>=0f)
            applyCollectionScale(collectionFrameScale);
    }

    private void handoff() {
        if(closed || collapsed)return;
        collapsed=true;
        log.accept("upper roll finished; live main reveal handoff");
        if(!current.getAsBoolean()) {close();return;}
        try {handoff.run();}
        catch(Throwable error) {log.accept("upper center handoff: "+error);close();}
    }

    /** Called after the existing adoption/return callback confirms task ownership. */
    void reveal() {
        if(closed || !collapsed || revealing)return;
        revealing=true;
        if(collectionView!=null) {
            // Shell now owns the task matrix; animate only its original SurfaceView parent.
            animate(0f,1f,180L,()->{
                log.accept("upper live collection expanded");
                close();
            });
            return;
        }
        if(!validLiveSurface()) {
            log.accept("upper live main surface unavailable; skip center animation");
            close();return;
        }
        animate(0f,1f,180L,()->{
            applyLiveScale(1f);
            log.accept("upper live main surface expanded");
            close();
        });
    }

    private void animate(float from,float to,long duration,Runnable end) {
        if(closed)return;
        ValueAnimator next=ValueAnimator.ofFloat(from,to);
        animator=next;
        next.setDuration(duration);
        next.setInterpolator(new AccelerateDecelerateInterpolator());
        next.addUpdateListener(value->{
            if(!current.getAsBoolean()) {close();return;}
            float fraction=(Float)value.getAnimatedValue();
            if(collectionView!=null && revealing) {
                // Zero until admission, then start moving immediately. A .14 hold
                // exposed a miniature task during the asynchronous native handoff.
                applyCollectionScale(fraction);
            } else if(!applyLiveScale(TopTaskReturnPolicy.revealScale(fraction))) {
                    log.accept("upper live main surface transform failed; restoring full frame");
                close();return;
            }
            face.invalidate();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(closed || animator!=animation)return;
                animator=null;end.run();
            }
        });
        next.start();
    }

    void close() {
        if(closed)return;
        closed=true;
        if(!liveSurfaceAtEnd)applyLiveScale(1f);
        if(collectionView!=null) {
            collectionView.setPivotX(collectionPivotX);collectionView.setPivotY(collectionPivotY);
            collectionView.setScaleX(collectionScaleX);collectionView.setScaleY(collectionScaleY);
        }
        Runnable restore=nativeRestore;nativeRestore=null;
        if(restore!=null)try {restore.run();}
        catch(Throwable error) {log.accept("upper live main state restore: "+error);}
        ValueAnimator old=animator;animator=null;
        if(old!=null)old.cancel();
        host.removeCallbacks(timeout);host.removeOnAttachStateChangeListener(attachment);
        if(attached) {
            face.setVisibility(View.GONE);
            try {windows.removeViewImmediate(face);}
            catch(Throwable error) {log.accept("upper transition removal: "+error);}
            attached=false;
        }
        finished.accept(this);
    }

    private boolean validLiveSurface() {
        try {
            return (collectionSourceCurrent==null || collectionSourceCurrent.getAsBoolean())
                    && liveSurface!=null && Boolean.TRUE.equals(XposedHelpers.callMethod(liveSurface,"isValid"));
        } catch(Throwable ignored) {return false;}
    }

    private void applyCollectionScale(float scale) {
        collectionFrameScale=scale;
        collectionView.setPivotX(collectionView.getWidth()*.5f);
        collectionView.setPivotY(collectionView.getHeight()*.5f);
        collectionView.setScaleX(scale);collectionView.setScaleY(scale);
    }

    private boolean applyLiveScale(float scale) {
        if(!validLiveSurface())return false;
        Object transaction=null;
        try {
            transaction=Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            float x=TopTaskReturnPolicy.centeredOffset(width,scale);
            float y=TopTaskReturnPolicy.centeredOffset(height,scale);
            XposedHelpers.callMethod(transaction,"setPosition",liveSurface,x,y);
            XposedHelpers.callMethod(transaction,"setMatrix",liveSurface,scale,0f,0f,scale);
            XposedHelpers.callMethod(transaction,"setWindowCrop",liveSurface,new Rect(0,0,width,height));
            XposedHelpers.callMethod(transaction,"apply");
            liveSurfaceAtEnd=scale>=1f;
            return true;
        } catch(Throwable error) {
            log.accept("upper live main surface transaction: "+error);
            return false;
        } finally {
            if(transaction!=null)try {XposedHelpers.callMethod(transaction,"close");}
            catch(Throwable ignored) {}
        }
    }

    private final class Face extends View {
        Face() {super(host.getContext());}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if(!firstDraw) {
                firstDraw=true;postOnAnimation(()->{
                    if(closed)return;
                    if(collectionView==null)handoff();
                    else MiStepTopTaskTransition.this.animate(1f,0f,160L,()->{
                        applyCollectionScale(0f);
                        log.accept("upper live main collapsed; collection handoff");
                        handoff();
                    });
                });
            }
        }
    }
}
