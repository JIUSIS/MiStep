package com.jiusis.mistep;

import de.robv.android.xposed.XposedHelpers;

/** Animate the existing WM magnification owner; never borrow or replace its buffer. */
final class MiStepSwapOverlayFrames implements LiveTaskSwapDriver.Overlays {
    static final class Row {
        final Object surface;
        final float x,y;
        Row(Object surface,float x,float y) {this.surface=surface;this.x=x;this.y=y;}
    }
    private final Row[] rows;
    private final LiveSwapZoom.Zoom start,end;
    private boolean closed;
    MiStepSwapOverlayFrames(Row[] rows,LiveSwapZoom.Zoom start,LiveSwapZoom.Zoom end) {
        this.rows=rows.clone();this.start=start;this.end=end;
        for(Row row:rows)if(row==null || row.surface==null || !Float.isFinite(row.x) || !Float.isFinite(row.y))
            throw new IllegalArgumentException("invalid overlay surface/position");
    }
    @Override public void paint(Object transaction,double fraction) {
        if(closed)throw new IllegalStateException("closed overlay lease");
        if(!Double.isFinite(fraction))throw new IllegalArgumentException("invalid overlay progress");
        float f=(float)Math.max(0,Math.min(1,fraction));
        float scale=start.scale+(end.scale-start.scale)*f;
        float x=start.x+(end.x-start.x)*f,y=start.y+(end.y-start.y)*f;
        for(Row row:rows) {
            if(!Boolean.TRUE.equals(XposedHelpers.callMethod(row.surface,"isValid")))continue;
            XposedHelpers.callMethod(transaction,"setMatrix",row.surface,scale,0f,0f,scale);
            XposedHelpers.callMethod(transaction,"setPosition",row.surface,row.x+x,row.y+y);
        }
    }
    @Override public void finish(Object transaction,boolean original) {paint(transaction,original?0:1);}
    @Override public void close() {
        if(closed)return;closed=true;
        // These are parcel-created client references, not the server's owning SurfaceControls.
        for(Row row:rows)try {XposedHelpers.callMethod(row.surface,"release");}catch(Throwable ignored) {}
    }
}
