package com.jiusis.mistep;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.util.function.Consumer;

/** Gesture clock for two live faces. This view never paints or captures an application. */
final class MiStepTopRoll extends View {
    private final TopRollMotion motion=new TopRollMotion();
    private final Consumer<Boolean> settled;
    private final Consumer<TopRollMotion.Frame> render;
    private ValueAnimator animator;
    private boolean dragging,target;

    MiStepTopRoll(Context context,Consumer<Boolean> settled,Consumer<TopRollMotion.Frame> render) {
        super(context);this.settled=settled;this.render=render;setVisibility(INVISIBLE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    boolean rolling() {return animator!=null || dragging;}
    boolean showingTask() {return target;}
    private void frame() {render.accept(TopRollMotion.frame(motion.phase,getHeight()));}
    void renderFrame() {frame();}
    private void stopAnimator() {
        if(animator!=null) {ValueAnimator old=animator;animator=null;old.cancel();}
    }
    void reset(boolean task) {
        stopAnimator();dragging=false;target=task;motion.reset(task);frame();
    }
    void show(boolean task) {
        int nearest=Math.round(motion.phase);
        int destination=TopRollMotion.taskFace(nearest)==task?nearest:nearest+(task?1:-1);
        animateTo(destination);
    }
    void beginGesture() {
        stopAnimator();dragging=true;motion.begin(getHeight());frame();
    }
    void moveGesture(float dy,long time) {
        if(!dragging)return;
        motion.move(dy,time);frame();
    }
    void finishGesture(boolean cancelled) {
        if(!dragging)return;
        dragging=false;animateTo(motion.destination(cancelled));
    }
    private void animateTo(int quarter) {
        stopAnimator();target=TopRollMotion.taskFace(quarter);
        float remaining=Math.abs(motion.phase-quarter);
        ValueAnimator next=ValueAnimator.ofFloat(motion.phase,quarter);
        animator=next;
        next.setDuration(Math.max(90,Math.min(280,Math.round(260*remaining))));
        next.setInterpolator(new DecelerateInterpolator(1.4f));
        next.addUpdateListener(value->{motion.phase=(float)value.getAnimatedValue();frame();});
        next.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if(animator!=animation)return;
                animator=null;motion.reset(target);frame();settled.accept(target);
            }
        });
        next.start();
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
        super.onSizeChanged(w,h,oldW,oldH);frame();
    }
    @Override protected void onDetachedFromWindow() {reset(target);super.onDetachedFromWindow();}
}
