package com.jiusis.mistep;

import java.util.function.Consumer;

/** Preserve one native panel across a swap; callbacks, not a delay, admit the tasks. */
final class SwapPanelLease {
    interface Panel {
        Object identity();
        boolean expanded();
        void collapse();
        boolean collapsed();
        void expand();
    }
    private final Consumer<Throwable> errors;
    private Object owner;
    private Panel saved;
    private Runnable ready;
    private boolean failed;

    SwapPanelLease(Consumer<Throwable> errors) { this.errors = errors; }

    void begin(Object ticket, Panel panel) {
        if (ticket == null) throw new IllegalArgumentException("swap ticket is required");
        // A replacement swap inherits the original expanded state, not the
        // temporary collapsed state. Never reopen a destroyed/replaced controller.
        Panel previous = saved;
        owner = ticket;
        saved = null;
        ready = null;
        failed = false;
        try {
            if (previous != null && panel != null && previous.identity() == panel.identity())
                saved = previous;
            else if (panel != null && panel.expanded()) saved = panel;
            if (saved != null) saved.collapse();
        } catch (Throwable error) { failed = true; errors.accept(error); }
    }

    void whenCollapsed(Object ticket, Runnable action) {
        if (owner != ticket || failed) return;
        ready = action;
        changed();
    }

    void changed() {
        if (owner == null || ready == null || failed) return;
        try {
            if (saved != null && !saved.collapsed()) return;
        } catch (Throwable error) { failed = true; errors.accept(error); return; }
        Runnable action = ready;
        ready = null;
        action.run();
    }

    void finish(Object ticket, boolean restore) {
        if (owner != ticket) return;
        Panel panel = saved;
        cancel();
        if (restore && panel != null) {
            try { panel.expand(); }
            catch (Throwable error) { errors.accept(error); }
        }
    }

    void cancel() { owner = null; saved = null; ready = null; failed = false; }
}
