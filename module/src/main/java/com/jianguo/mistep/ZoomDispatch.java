package com.jianguo.mistep;

import java.util.function.Consumer;

/** Coalesce zoom state on the WM queue and touch its shared tree only under its real lock. */
final class ZoomDispatch {
    interface Poster { boolean post(Runnable runnable); }
    interface Applier {
        void apply(Object target, float scale, float offsetY, float offsetX) throws Throwable;
    }

    private static final class Request {
        final Object target;
        final float scale;
        final float offsetY;
        final float offsetX;
        Request(Object target, float scale, float offsetY, float offsetX) {
            this.target = target;
            this.scale = scale;
            this.offsetY = offsetY;
            this.offsetX = offsetX;
        }
        boolean same(Request other) {
            return other != null && target == other.target
                    && Float.compare(scale, other.scale) == 0
                    && Float.compare(offsetY, other.offsetY) == 0
                    && Float.compare(offsetX, other.offsetX) == 0;
        }
    }

    private final Object stateLock = new Object();
    private final Object wmLock;
    private final Poster poster;
    private final Applier applier;
    private final Consumer<Throwable> failure;
    private Request pending;
    private Request lastAttempt;
    private boolean queued;

    ZoomDispatch(Poster poster, Object wmLock, Applier applier, Consumer<Throwable> failure) {
        this.poster = java.util.Objects.requireNonNull(poster);
        this.wmLock = java.util.Objects.requireNonNull(wmLock);
        this.applier = java.util.Objects.requireNonNull(applier);
        this.failure = java.util.Objects.requireNonNull(failure);
    }

    void submit(Object target, float scale, float offsetY) {
        submit(target, scale, offsetY, 0f);
    }

    void submit(Object target, float scale, float offsetY, float offsetX) {
        if (target == null || !Float.isFinite(scale) || !Float.isFinite(offsetY)
                || !Float.isFinite(offsetX)) {
            throw new IllegalArgumentException("invalid zoom target or state");
        }
        Request next = new Request(target, scale, offsetY, offsetX);
        boolean schedule = false;
        synchronized (stateLock) {
            if (next.same(pending) || (pending == null && next.same(lastAttempt))) return;
            pending = next;
            if (!queued) {
                queued = true;
                schedule = true;
            }
        }
        if (schedule) post();
    }

    /**
     * 缩放动画会绕过本调度器直接改变真实状态；开始动画时清掉 lastAttempt，
     * 否则动画结束后的“恢复原值”请求会被去重逻辑当成重复请求吞掉。
     */
    void invalidateLastAttempt() {
        synchronized (stateLock) {
            lastAttempt = null;
        }
    }

    /** A correlated WMS handoff replaces requests queued before it, under the same WM lock. */
    void takeOver(Object target, float scale, float offsetY, float offsetX) {
        if (!Thread.holdsLock(wmLock)) throw new IllegalStateException("takeover needs WM lock");
        synchronized (stateLock) {
            pending = null;
            lastAttempt = new Request(target, scale, offsetY, offsetX);
        }
    }

    private void post() {
        try {
            if (poster.post(this::drain)) return;
            throw new IllegalStateException("WM handler rejected zoom request");
        } catch (Throwable error) {
            synchronized (stateLock) {
                // A dying handler cannot safely be replaced by an inline/background call.
                lastAttempt = pending;
                pending = null;
                queued = false;
            }
            report(error);
        }
    }

    private void drain() {
        Throwable error = null;
        try {
            synchronized (wmLock) {
                final Request request;
                synchronized (stateLock) {
                    // Pick the latest request AFTER obtaining the WM lock. A queued active
                    // request may have expired or been superseded by exit while waiting.
                    request = pending != null && !pending.same(lastAttempt) ? pending : null;
                    pending = null;
                    if (request != null) lastAttempt = request;
                }
                if (request != null) {
                    applier.apply(request.target, request.scale, request.offsetY, request.offsetX);
                }
            }
        } catch (Throwable failure) {
            error = failure;
        } finally {
            boolean schedule = false;
            synchronized (stateLock) {
                queued = false;
                if (pending != null && pending.same(lastAttempt)) pending = null;
                if (pending != null) {
                    queued = true;
                    schedule = true;
                }
            }
            if (error != null) report(error);
            if (schedule) post();
        }
    }

    private void report(Throwable error) {
        try { failure.accept(error); } catch (Throwable ignored) { /* never crash the WM handler */ }
    }
}
