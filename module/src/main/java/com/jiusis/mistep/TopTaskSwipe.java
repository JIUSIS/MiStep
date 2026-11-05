package com.jiusis.mistep;

/** Passive parallel two-finger recognition; the first accepted axis stays locked. */
final class TopTaskSwipe {
    static final int NONE=0, ACCEPT=1, DRAG=2, FINISH=3, CANCEL=4;
    static final int VERTICAL=1, HORIZONTAL=2;
    private int first=-1, second=-1;
    private float firstX,firstY,x0,y0,x1,y1,threshold;
    private long downTime,pairTime;
    private boolean eligible,paired,accepted,blocked,allowHorizontal;
    private int axis;
    float displacement;

    void reset() { first=second=-1; eligible=paired=accepted=blocked=allowHorizontal=false; displacement=0;axis=0; }
    boolean accepted() { return accepted; }
    boolean horizontal() { return axis==HORIZONTAL; }
    private boolean inside(float x,float y,int width,int height) {
        return x>=0 && x<width && y>=0 && y<height;
    }
    int event(int action,int count,int id0,int id1,float a,float b,float c,float d,
              long time,int width,int height,float density) {
        return event(action,count,id0,id1,a,b,c,d,time,width,height,density,false);
    }
    int event(int action,int count,int id0,int id1,float a,float b,float c,float d,
              long time,int width,int height,float density,boolean horizontalEligible) {
        if(action==0) {
            reset(); first=id0;firstX=a;firstY=b;downTime=time;
            allowHorizontal=horizontalEligible;
            threshold=Math.max(1,18*Math.max(.1f,density));
            eligible=count==1 && inside(a,b,width,height); return NONE;
        }
        if(action==3 || count>2) {
            boolean was=accepted;reset();blocked=true;return was?CANCEL:NONE;
        }
        if(action==1 || action==6) {
            boolean was=accepted;accepted=paired=eligible=false;blocked=true;
            return was?FINISH:NONE;
        }
        if(blocked || !eligible)return NONE;
        if(action==5) {
            if(count!=2 || paired || time-downTime>350 || id0!=first
                    || !inside(a,b,width,height) || !inside(c,d,width,height)
                    || Math.hypot(a-firstX,b-firstY)>threshold) {
                eligible=false;return NONE;
            }
            second=id1;x0=a;y0=b;x1=c;y1=d;pairTime=time;paired=true;return NONE;
        }
        if(action!=2)return NONE;
        if(!paired) {
            if(Math.hypot(a-firstX,b-firstY)>threshold)eligible=false;
            return NONE;
        }
        if(count!=2) {boolean was=accepted;reset();blocked=true;return was?CANCEL:NONE;}
        if(id0==second && id1==first) {float x=a,y=b;a=c;b=d;c=x;d=y;}
        else if(id0!=first || id1!=second) {boolean was=accepted;reset();blocked=true;return was?CANCEL:NONE;}
        float dx0=a-x0,dy0=b-y0,dx1=c-x1,dy1=d-y1;
        displacement=axis==HORIZONTAL?(dx0+dx1)/2:(dy0+dy1)/2;
        if(accepted)return DRAG;
        if(time-pairTime>650 || Math.hypot(dx0-dx1,dy0-dy1)>threshold*1.5f) {
            eligible=false;return NONE;
        }
        if(dy0*dy1>0 && Math.min(Math.abs(dy0),Math.abs(dy1))>=threshold
                && Math.abs(displacement)>Math.max(Math.abs(dx0),Math.abs(dx1))*1.5f) {
            axis=VERTICAL;accepted=true;return ACCEPT;
        }
        float dx=(dx0+dx1)/2;
        if(allowHorizontal && dx0*dx1>0 && Math.min(Math.abs(dx0),Math.abs(dx1))>=threshold
                && Math.abs(dx)>Math.max(Math.abs(dy0),Math.abs(dy1))*1.5f) {
            axis=HORIZONTAL;displacement=dx;accepted=true;return ACCEPT;
        }
        return NONE;
    }
}
