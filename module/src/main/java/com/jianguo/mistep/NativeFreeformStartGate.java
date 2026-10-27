package com.jianguo.mistep;

/** Access only under the WM lock. The native start transaction owns the handoff. */
final class NativeFreeformStartGate {
    private Object display, transaction;

    boolean begin(Object display, Object transaction) {
        if (display == null || transaction == null || this.transaction != null) return false;
        this.display = display; this.transaction = transaction;
        return true;
    }

    Object route(Object container, Object display, Object transaction, boolean finalSnapshot) {
        return !finalSnapshot && container == display && this.display == display
                && this.transaction != null ? this.transaction : transaction;
    }

    boolean finish(Object display, Object transaction) {
        if (this.display != display || this.transaction != transaction || transaction == null) return false;
        this.display = null; this.transaction = null;
        return true;
    }
}
