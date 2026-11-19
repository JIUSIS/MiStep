package com.android.server.wm;

import android.view.SurfaceControl;

public class SurfaceAnimator {
    public static final int ANIMATION_TYPE_SCREEN_ROTATION = 32;
    public interface Animatable {
        void commitPendingTransaction();
        SurfaceControl getAnimationLeash();
        SurfaceControl getAnimationLeashParent();
        SurfaceControl getParentSurfaceControl();
        SurfaceControl.Transaction getPendingTransaction();
        SurfaceControl getSurfaceControl();
        int getSurfaceHeight();
        int getSurfaceWidth();
        SurfaceControl.Transaction getSyncTransaction();
        SurfaceControl.Builder makeAnimationLeash();
        void onAnimationLeashCreated(SurfaceControl.Transaction t, SurfaceControl leash);
        void onAnimationLeashLost(SurfaceControl.Transaction t);
        void onLeashAnimationStarting(SurfaceControl.Transaction t, SurfaceControl leash);
        default boolean shouldDeferAnimationFinish(Runnable finish) { return false; }
    }
    public interface OnAnimationFinishedCallback { void onAnimationFinished(int type, AnimationAdapter adapter); }
    public AnimationAdapter current;
    public int starts, cancels, type;
    public boolean hidden, fail;
    public Animatable animatable;
    public SurfaceControl leash;
    public SurfaceControl.Transaction transaction;
    public SurfaceAnimator() {}
    public SurfaceAnimator(Animatable a, OnAnimationFinishedCallback callback, WindowManagerService service) {
        animatable=a; service.lastAnimator=this; service.animators.add(this); fail=service.fail;
    }
    public AnimationAdapter getAnimation() { return current; }
    public void startAnimation(SurfaceControl.Transaction t, AnimationAdapter adapter, boolean hidden, int type) {
        this.transaction=t; this.hidden=hidden; this.type=type; current=adapter; starts++;
        leash=animatable.makeAnimationLeash().setParent(animatable.getAnimationLeashParent()).build();
        t.setWindowCrop(leash, animatable.getSurfaceWidth(), animatable.getSurfaceHeight());
        t.reparent(animatable.getSurfaceControl(), leash);
        animatable.onAnimationLeashCreated(t, leash);
        animatable.onLeashAnimationStarting(t, leash);
        if (fail) throw new IllegalStateException("native failure after reparent");
    }
    public void cancelAnimation() { cancels++; finish(); }
    public void finish() {
        current=null;
        if (animatable == null || leash == null) return;
        SurfaceControl.Transaction t=animatable.getSyncTransaction();
        t.reparent(animatable.getSurfaceControl(), animatable.getParentSurfaceControl());
        t.remove(leash);
        leash=null;
        animatable.onAnimationLeashLost(t);
        animatable.commitPendingTransaction();
    }
}
