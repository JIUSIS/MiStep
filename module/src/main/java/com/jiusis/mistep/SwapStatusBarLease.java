package com.jiusis.mistep;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A bounded drawing lease whose expiry first ends the matching visual bridge. */
final class SwapStatusBarLease {
    static final long TIMEOUT_MS = 5000L;

    interface Window {
        Object identity();
        float transitionAlpha();
        void transitionAlpha(float value);
    }
    interface Timer {
        void postDelayed(Runnable action, long delayMs);
        void remove(Runnable action);
    }
    private record Saved(Window window, float alpha) {}
    private final Map<Object, Saved> saved = new IdentityHashMap<>();
    private final Timer timer;
    private final Consumer<Throwable> errors;
    private Object owner;
    private Runnable timeout;
    private Runnable cleanup;

    SwapStatusBarLease(Timer timer, Consumer<Throwable> errors) {
        this.timer = timer;
        this.errors = errors;
    }

    void begin(Object ticket, Runnable cleanup, Runnable expire) {
        if (ticket == null) throw new IllegalArgumentException("swap ticket is required");
        restore();
        owner = ticket;
        this.cleanup = cleanup;
        timeout = () -> {
            if (!owns(ticket)) return;
            try { expire.run(); }
            catch (Throwable error) { errors.accept(error); }
            finally { finish(ticket); }
        };
        timer.postDelayed(timeout, TIMEOUT_MS);
    }

    boolean owns(Object ticket) { return ticket != null && owner == ticket; }

    void include(Object ticket, List<? extends Window> windows) {
        if (!owns(ticket)) return;
        for (Window window : windows) {
            try {
                Object key = window.identity();
                if (key == null || saved.containsKey(key)) continue;
                float alpha = window.transitionAlpha();
                if (!Float.isFinite(alpha)) continue;
                // A setter can throw after applying its value: save first.
                saved.put(key, new Saved(window, alpha));
                window.transitionAlpha(0f);
            } catch (Throwable error) { errors.accept(error); }
        }
    }

    void finish(Object ticket) { if (owns(ticket)) restore(); }

    void restore() {
        owner = null;
        if (timeout != null) timer.remove(timeout);
        timeout = null;
        Runnable end = cleanup;
        cleanup = null;
        try {
            for (Saved entry : saved.values()) {
                try { entry.window.transitionAlpha(entry.alpha); }
                catch (Throwable error) { errors.accept(error); }
            }
        } finally {
            saved.clear();
            if (end != null) {
                try { end.run(); }
                catch (Throwable error) { errors.accept(error); }
            }
        }
    }
}
