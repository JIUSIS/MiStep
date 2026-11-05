package com.jiusis.mistep;

public final class TopGestureTest {
    static int checks;
    static void ok(boolean value) {checks++;if(!value)throw new AssertionError("check "+checks);}
    static TopTaskSwipe swipe=new TopTaskSwipe();
    static int e(int action,int count,float x,float y,float x1,float y1,long time) {
        return swipe.event(action,count,7,12,x,y,x1,y1,time,1080,622,2);
    }
    static void pair() {e(0,1,300,500,0,0,0);e(5,2,300,500,600,500,40);}
    public static void main(String[] args) {
        e(0,1,300,500,0,0,0);ok(e(2,1,300,100,0,0,100)==0);
        pair();ok(e(2,2,300,480,600,480,70)==0);
        ok(e(2,2,300,440,600,440,100)==TopTaskSwipe.ACCEPT);
        ok(e(2,2,300,100,600,100,200)==TopTaskSwipe.DRAG && swipe.displacement==-400);
        ok(e(2,2,300,300,600,300,300)==TopTaskSwipe.DRAG && swipe.displacement==-200);
        ok(e(6,2,300,300,600,300,310)==TopTaskSwipe.FINISH);
        ok(e(2,1,300,100,0,0,320)==0);
        pair();ok(e(2,2,300,560,600,560,100)==TopTaskSwipe.ACCEPT);
        ok(e(3,2,300,560,600,560,110)==TopTaskSwipe.CANCEL);
        pair();ok(e(2,2,240,500,660,500,100)==0);ok(!swipe.accepted());
        pair();ok(e(2,2,300,440,600,560,100)==0);ok(!swipe.accepted());
        pair();ok(e(2,2,360,440,660,440,100)==0);ok(!swipe.accepted());
        pair();ok(e(5,3,300,440,600,440,80)==0);ok(e(2,2,300,100,600,100,100)==0);
        pair();e(2,2,300,440,600,440,100);ok(e(5,3,300,430,600,430,120)==TopTaskSwipe.CANCEL);
        e(0,1,300,700,0,0,0);e(5,2,300,600,600,500,40);ok(e(2,2,300,100,600,0,100)==0);
        e(0,1,300,500,0,0,0);e(5,2,300,500,600,700,40);ok(e(2,2,300,100,600,300,100)==0);
        e(0,1,300,500,0,0,0);e(5,2,300,500,600,500,400);ok(e(2,2,300,100,600,100,450)==0);
        pair();ok(e(2,2,300,440,600,440,750)==0);
        pair();e(2,2,300,440,600,440,100);
        ok(swipe.event(2,2,12,7,600,300,300,300,130,1080,622,2)==TopTaskSwipe.DRAG);
        ok(swipe.displacement==-200);

        // Horizontal dismissal is opt-in for a currently visible, leased TOP task.
        pair();ok(e(2,2,400,500,700,500,100)==TopTaskSwipe.NONE);
        for(int sign:new int[]{-1,1}) {
            swipe.event(0,1,7,12,400,300,0,0,0,1080,622,2,true);
            swipe.event(5,2,7,12,400,300,650,300,40,1080,622,2,true);
            ok(swipe.event(2,2,7,12,400+sign*80,302,650+sign*80,302,100,1080,622,2,true)==TopTaskSwipe.ACCEPT);
            ok(swipe.horizontal()&&swipe.displacement==sign*80);
            ok(swipe.event(2,2,12,7,650+sign*120,50,400+sign*120,50,130,1080,622,2,true)==TopTaskSwipe.DRAG);
            ok(swipe.horizontal()&&swipe.displacement==sign*120); // axis stays horizontal despite later vertical drift
            ok(swipe.event(3,2,7,12,0,0,0,0,140,1080,622,2,true)==TopTaskSwipe.CANCEL);
        }
        swipe.event(0,1,7,12,400,300,0,0,0,1080,622,2,true);
        swipe.event(5,2,7,12,400,300,650,300,40,1080,622,2,true);
        ok(swipe.event(2,2,7,12,300,300,750,300,100,1080,622,2,true)==TopTaskSwipe.NONE); // pinch stays with app
        swipe.event(0,1,7,12,400,300,0,0,0,1080,622,2,true);
        swipe.event(5,2,7,12,400,300,650,300,40,1080,622,2,true);
        ok(swipe.event(2,2,7,12,400,200,650,200,100,1080,622,2,true)==TopTaskSwipe.ACCEPT&&!swipe.horizontal());
        TopRollMotion m=new TopRollMotion();m.reset(false);m.begin(600);m.move(-420,100);
        ok(Math.abs(m.phase-.7f)<.001 && m.destination(false)==1);
        // One face per gesture: a long drag clamps to the adjacent face and never shows a third.
        m.move(-1500,300);ok(m.phase==1f && m.destination(false)==1);
        m.move(600,500);ok(m.phase==-1 && m.destination(false)==-1);
        ok(m.destination(true)==0);
        m.reset(false);m.begin(600);m.move(-3000,100);m.move(-3600,110);
        ok(m.phase==1f && m.destination(false)==1);
        m.reset(true);m.begin(600);m.move(-600,100);ok(m.phase==2 && !TopRollMotion.taskFace(m.destination(false)));
        m.reset(false);m.begin(600);m.move(600,100);ok(m.phase==-1 && TopRollMotion.taskFace(m.destination(false)));
        m.phase=.52f;m.begin(600);m.move(-120,100);ok(Math.abs(m.phase-.72f)<.001);
        ok(TopRollMotion.shouldAnimateBackToMain(false));
        ok(!TopRollMotion.shouldAnimateBackToMain(true));
        for(int n=-100;n<=100;n++)ok(TopRollMotion.taskFace(n)!=TopRollMotion.taskFace(n+1));
        // Live adjacent faces cover the strip without drawing a replacement image.
        for(float phase:new float[]{-1.75f,-1f,-.75f,-.5f,-.25f,0f,.25f,.5f,.75f,1f,1.25f,1.75f,2f}) {
            TopRollMotion.Frame f=TopRollMotion.frame(phase,600);
            ok(f.frontScale>=0&&f.frontScale<=1&&f.taskScale>=0&&f.taskScale<=1);
            float frontEnd=f.frontTop+600*f.frontScale,taskEnd=f.taskTop+600*f.taskScale;
            ok(Math.min(f.frontTop,f.taskTop)<=.001f);
            ok(Math.max(frontEnd,taskEnd)>=599.999f);
            ok(Math.min(Math.abs(frontEnd-f.taskTop),Math.abs(taskEnd-f.frontTop))<.001f);
        }
        TopRollMotion.Frame controls=TopRollMotion.frame(0,600),task=TopRollMotion.frame(1,600);
        ok(controls.frontScale==1&&controls.frontTop==0&&controls.taskScale==0);
        ok(task.taskScale==1&&task.taskTop==0&&task.frontScale==0);
        System.out.println("TopGesture: "+checks+" checks passed");
    }
}
