package com.jianguo.mistep;

/** At most one visibility repair per foreground root and session; a failure never spins. */
final class VisibilityRepair {
    private long scene = Long.MIN_VALUE;
    private Object foreground;
    synchronized boolean admit(long scene, Object foreground) {
        if (foreground == null) return false;
        if (this.scene == scene && foreground.equals(this.foreground)) return false;
        this.scene = scene;
        this.foreground = foreground;
        return true;
    }
    synchronized void reset() { scene = Long.MIN_VALUE; foreground = null; }
}

