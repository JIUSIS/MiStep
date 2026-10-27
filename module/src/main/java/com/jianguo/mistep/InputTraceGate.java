package com.jianguo.mistep;

/** A single explicitly armed input observation; never grants permission to dispatch input. */
final class InputTraceGate {
    static final long DURATION_MS = 6000;
    static final int MAX_LINES = 128;
    private long generation;
    private long until;
    private int remaining;

    synchronized long arm(long now, boolean unlocked) {
        generation++;
        until = unlocked ? now + DURATION_MS : 0;
        remaining = unlocked ? MAX_LINES : 0;
        return generation;
    }

    synchronized boolean active(long now) {
        return remaining > 0 && now >= until - DURATION_MS && now < until;
    }

    synchronized boolean take(long now) {
        if (!active(now)) return false;
        remaining--;
        return true;
    }

    synchronized boolean stop(long ticket) {
        if (ticket != generation) return false;
        until = 0;
        remaining = 0;
        return true;
    }

    synchronized long generation() { return generation; }
}
