package com.jiusis.mistep;

/** Axis locking keeps the pager away from vertical sliders and the native music seek bar. */
final class ControlCenterPages {
    private float downX, downY, width, travel, slop, origin;
    private int page;
    boolean dragging, vertical, blocked;
    void begin(float x, float y, int page, float width, float slop, boolean blocked) {
        begin(x, y, page, width, 0, slop, blocked);
    }
    static float span(float width, float gap) {
        return Math.max(1, width) + Math.max(0, gap);
    }
    void begin(float x, float y, int page, float width, float gap, float slop, boolean blocked) {
        downX=x; downY=y; this.page=page; this.width=Math.max(1, width);
        travel=span(width, gap);
        this.slop=Math.max(1, slop); this.blocked=blocked;
        origin=page*travel; dragging=vertical=false;
    }
    float move(float x, float y) {
        float dx=x-downX, dy=y-downY;
        if (!dragging && !vertical && !blocked) {
            if (Math.abs(dy)>slop && Math.abs(dy)>=Math.abs(dx)) vertical=true;
            else if (Math.abs(dx)>slop && Math.abs(dx)>Math.abs(dy)*1.25f) dragging=true;
        }
        return dragging ? Math.max(0, Math.min(travel, origin-dx)) : origin;
    }
    int finish(float x, float velocity, float minimumVelocity, boolean cancel) {
        if (cancel || !dragging) return page;
        float dx=x-downX;
        if (Math.abs(dx)>slop*2 && Math.abs(velocity)>minimumVelocity)
            return velocity<0 ? 1 : 0;
        if (Math.abs(dx)>width*.18f) return dx<0 ? 1 : 0;
        return page;
    }
}
