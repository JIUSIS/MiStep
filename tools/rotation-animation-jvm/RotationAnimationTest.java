package com.jiusis.mistep;

import android.view.SurfaceControl;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;
import android.graphics.Rect;
import android.graphics.Matrix;
import com.android.server.wm.*;

public class RotationAnimationTest {
    static int checks;
    static void check(boolean v,String s) { checks++; if(!v) throw new AssertionError(s); }
    static FakeRotationActivity fresh() { AnimationUtils.extension=false; AnimationUtils.probe.reset(); return new FakeRotationActivity(); }
    static void cornersInside(Matrix matrix,int width,int height,String label) {
        float[] m=new float[9];matrix.getValues(m);
        for(int x:new int[]{0,width})for(int y:new int[]{0,height}) {
            float px=m[0]*x+m[1]*y+m[2],py=m[3]*x+m[4]*y+m[5];
            check(px>=-0.01f&&px<=width+0.01f&&py>=-0.01f&&py<=height+0.01f,label);
        }
    }
    static void checkRecordedAndIntermediateFrames() {
        FakeRotationActivity a=fresh();a.task.bounds=new Rect(0,0,3509,1933);
        // The original matrix is from SF layer571 at 22:03:44.6725106. Translate
        // is the native leash's display position divided by the task scale.
        AnimationUtils.probe.setValues(new float[]{0.52055305f,0.8538293f,1666.42f,
                -0.8538293f,0.52055305f,-1034.66f,0,0,1});
        check(MiStepRotationAnimation.start(a,1,0),"recorded native frame starts");
        WindowAnimationSpec spec=(WindowAnimationSpec)((LocalAnimationAdapter)a.mWmService.lastAnimator.current).spec;
        Transformation frame=new Transformation();
        check(spec.animation.getTransformation(100,frame),"native running result retained");
        cornersInside(frame.getMatrix(),3509,1933,"recorded rotation must stay in viewport");
        check(!spec.animation.getTransformation(600,frame),"native finish result retained");
        MiStepRotationAnimation.cancel(a);
        for(int[] dims:new int[][]{{3509,1933},{1200,2608},{1080,2460},{3000,1725}}) {
            for(int angle=-180;angle<=180;angle+=5) {
                a=fresh();int w=dims[0],h=dims[1];a.task.bounds=new Rect(0,0,w,h);
                double r=Math.toRadians(angle);float c=(float)Math.cos(r),s=(float)Math.sin(r);
                AnimationUtils.probe.setValues(new float[]{c,-s,w/2f-c*w/2f+s*h/2f,
                        s,c,h/2f-s*w/2f-c*h/2f,0,0,1});
                check(MiStepRotationAnimation.start(a,0,1),"direction sample starts");
                spec=(WindowAnimationSpec)((LocalAnimationAdapter)a.mWmService.lastAnimator.current).spec;
                spec.animation.getTransformation(100,frame);
                cornersInside(frame.getMatrix(),w,h,"every transformed corner inside physical task");
                if(angle==0) {
                    float[] m=new float[9];frame.getMatrix().getValues(m);
                    check(m[0]==1&&m[4]==1&&m[2]==0&&m[5]==0,"identity frame unchanged");
                }
                MiStepRotationAnimation.cancel(a);
            }
        }
    }
    public static void main(String[] args) {
        check(MiStepRotationAnimation.resource(0,0)==null,"unchanged direction");
        check(MiStepRotationAnimation.resource(0,1).equals("screen_rotate_minus_90_enter"),"quarter turn");
        check(MiStepRotationAnimation.resource(0,3).equals("screen_rotate_plus_90_enter"),"reverse turn");
        check(MiStepRotationAnimation.resource(0,2).equals("screen_rotate_180_enter"),"half turn");
        FakeRotationActivity a=fresh(); a.fixed=new SurfaceControl(); a.fixed.parent=a.parentSurface;
        check(MiStepRotationAnimation.start(a,0,1),"native animation started");
        SurfaceAnimator animator=a.mWmService.lastAnimator;
        SurfaceControl carrier=a.fixed, leash=animator.leash, clip=leash.parent;
        check(animator!=a.mSurfaceAnimator && a.window.mSurfaceAnimator.starts==0,"independent animator leaves container owners alone");
        check(carrier.parent==leash && clip.parent==a.parentSurface,"live fixed carrier wrapped under original task");
        check(a.surface.parent==a.parentSurface,"does not replace the app buffer");
        check(clip.width==3000&&clip.height==1725&&clip.x==0&&clip.y==0&&clip.layer==7&&clip.shown,"unrotated physical clip geometry");
        check(AnimationUtils.last.width==3000&&AnimationUtils.last.height==1725,"animation uses physical carrier not portrait viewport");
        check(AnimationUtils.last.getAnimations().size()==1,"screenshot crossfade removed");
        check(a.mWmService.index==1 && AnimationUtils.last.scale==1,"WM transition scale");
        check(animator.type==32&&!animator.hidden,"native rotation adapter without freezer");
        check(animator.animatable.getAnimationLeash()==leash,"proxy exposes actual leash");
        check(!animator.animatable.shouldDeferAnimationFinish(()->{}),"OS2 default contract");
        animator.finish();
        check(carrier.parent==a.parentSurface&&!clip.valid&&!leash.valid,"native finish restores live carrier before clip disposal");
        check(MiStepRotationAnimation.cancel(a)&&animator.cancels==0,"completed ownership cleared");
        a=fresh();check(MiStepRotationAnimation.start(a,0,3),"activity surface fallback");animator=a.mWmService.lastAnimator;clip=animator.leash.parent;
        check(a.surface.parent==animator.leash,"actual activity carrier selected");
        check(MiStepRotationAnimation.cancel(a)&&animator.cancels==1&&a.surface.parent==a.parentSurface&&!clip.valid,"exact cancel and reparent");
        a=fresh();MiStepRotationAnimation.start(a,0,1);animator=a.mWmService.lastAnimator;clip=animator.leash.parent;
        AnimationAdapter ours=animator.current;animator.current=new LocalAnimationAdapter(null,null);
        check(!MiStepRotationAnimation.cancel(a)&&clip.valid&&animator.cancels==0,"replaced adapter not cancelled");
        check(!MiStepRotationAnimation.start(a,1,3)&&a.mWmService.animators.size()==1,"foreign adapter prevents replacement");
        animator.current=ours;MiStepRotationAnimation.cancelAll();check(!clip.valid&&animator.cancels==1,"exit cancels owned adapter");
        a=fresh();a.mWmService.fail=true;boolean failed=false;try{MiStepRotationAnimation.start(a,0,1);}catch(IllegalStateException e){failed=true;}
        check(failed&&a.surface.parent==a.parentSurface&&a.mWmService.lastAnimator.cancels==1,"partial native failure restores carrier");
        a=fresh();a.window.visible=false;check(!MiStepRotationAnimation.start(a,0,1)&&a.mWmService.lastAnimator==null,"invisible window rejected");
        a=fresh();a.window.mSurfaceAnimator.current=new LocalAnimationAdapter(null,null);check(!MiStepRotationAnimation.start(a,0,1),"native window animation not stolen");
        a=fresh();a.mSurfaceAnimator.current=new LocalAnimationAdapter(null,null);check(!MiStepRotationAnimation.start(a,0,1),"native activity animation not stolen");
        a=fresh();a.task.transition=true;check(!MiStepRotationAnimation.start(a,0,1),"ancestor Shell transition respected");
        a=fresh();a.parentSurface.valid=false;check(!MiStepRotationAnimation.start(a,0,1),"invalid parent rejected");
        a=fresh();a.task.bounds.right=0;check(!MiStepRotationAnimation.start(a,0,1),"empty physical carrier rejected");
        a=fresh();a.mWmService.scale=0;check(!MiStepRotationAnimation.start(a,0,1),"disabled system animation respected");
        a=fresh();a.mWmService.scale=Float.NaN;check(!MiStepRotationAnimation.start(a,0,1),"invalid scale rejected");
        a=fresh();a.mWmService.mContext.resources.missing=true;check(!MiStepRotationAnimation.start(a,0,1),"missing system resource rejected");
        a=fresh();AnimationUtils.extension=true;check(!MiStepRotationAnimation.start(a,0,1)&&a.mWmService.lastAnimator==null,"edge screenshot extension rejected");
        a=fresh();FakeRotationActivity peer=fresh();FakeRotationActivity.State state=new FakeRotationActivity.State();
        state.mAssociatedTokens.add(a);state.mAssociatedTokens.add(peer);a.mFixedRotationTransformState=state;peer.mFixedRotationTransformState=state;
        MiStepRotationAnimation.finishLocalRotation(a);
        check(a.finishes==1&&a.finishCallback!=null&&a.mFixedRotationTransformState==null&&peer.mFixedRotationTransformState==null,"native finish nonnull callback clears shared state");
        check(a.configurations==1&&a.ensured==1&&peer.configurations==1&&peer.ensured==1,"all associated activity configurations restored");
        a=fresh();state=new FakeRotationActivity.State();state.mAssociatedTokens.add(a);state.mAssociatedTokens.add(peer);a.mFixedRotationTransformState=state;
        boolean rejected=false;try{MiStepRotationAnimation.finishLocalRotation(a);}catch(IllegalStateException e){rejected=true;}
        check(rejected&&a.finishes==0&&a.mFixedRotationTransformState==state,"foreign shared state refuses finish");
        a=fresh();MiStepRotationAnimation.finishLocalRotation(a);check(a.finishes==0,"already released native state untouched");
        checkRecordedAndIntermediateFrames();
        System.out.println("RotationAnimation checks="+checks+" passed (production bridge; native-shaped fakes, not visual acceptance)");
    }
}
