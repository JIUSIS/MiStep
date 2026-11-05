package com.jiusis.mistep;

/** One quarter-turn per gesture; only the final visible face determines task ownership. */
final class TopRollMotion {
    float phase, origin, velocity, previous;
    private long previousTime;
    private float distance;
    private int base;
    static boolean shouldAnimateBackToMain(boolean mainOccupied) { return !mainOccupied; }
    void reset(boolean task) {phase=task?1:0;origin=phase;base=Math.round(phase);velocity=0;}
    void begin(float height) {
        origin=phase;base=Math.round(origin);
        distance=Math.max(1,height);previous=0;previousTime=0;velocity=0;
    }
    void move(float dy,long time) {
        if(previousTime>0 && time>previousTime) {
            float measured=-(dy-previous)*1000f/(distance*(time-previousTime));
            velocity=velocity*.35f+Math.max(-5,Math.min(5,measured))*.65f;
        }
        // Clamp to the adjacent faces so a long or slow drag never reveals a third face.
        previous=dy;previousTime=time;
        phase=Math.max(base-1,Math.min(base+1,origin-dy/distance));
    }
    int destination(boolean cancelled) {
        if(cancelled)return base;
        int next=Math.round(phase+Math.max(-.35f,Math.min(.35f,velocity*.08f)));
        return Math.max(base-1,Math.min(base+1,next));
    }
    /** Affine projection of the two adjacent live faces, clipped by the upper strip. */
    static final class Frame {
        final float frontScale, frontTop, taskScale, taskTop;
        Frame(float frontScale,float frontTop,float taskScale,float taskTop) {
            this.frontScale=frontScale;this.frontTop=frontTop;
            this.taskScale=taskScale;this.taskTop=taskTop;
        }
    }
    static Frame frame(float phase,float height) {
        int whole=(int)Math.floor(phase);
        float fraction=phase-whole;
        double angle=fraction*Math.PI/2;
        float outgoing=(float)Math.cos(angle),incoming=(float)Math.sin(angle);
        float half=Math.max(1,height)*.5f;
        float outgoingTop=half*(1-outgoing-incoming);
        float incomingTop=half*(1+outgoing-incoming);
        return taskFace(whole)
                ?new Frame(incoming,incomingTop,outgoing,outgoingTop)
                :new Frame(outgoing,outgoingTop,incoming,incomingTop);
    }
    static boolean taskFace(int quarter) {return (quarter&1)!=0;}
}
