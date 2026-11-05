package com.jiusis.mistep;

import java.util.ArrayList;

public final class SwapPanelLeaseTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Panel implements SwapPanelLease.Panel {
        boolean expanded = true, settled, failCollapse, failRead;
        int collapses, expands;
        public Object identity() { return this; }
        public boolean expanded() { return expanded; }
        public void collapse() { collapses++; expanded = false; if (failCollapse) throw new IllegalStateException(); }
        public boolean collapsed() { if (failRead) throw new IllegalStateException(); return settled; }
        public void expand() { expands++; expanded = true; }
    }
    public static void main(String[] args) {
        var errors = new ArrayList<Throwable>();
        var lease = new SwapPanelLease(errors::add);
        Object a = new Object(), b = new Object();
        int[] started = {0};
        Panel p = new Panel();
        lease.begin(a, p);
        check(p.collapses == 1 && p.expands == 0, "expanded native panel closes before tasks move");
        lease.whenCollapsed(a, () -> started[0]++);
        check(started[0] == 0, "collapse request is not a completed collapse");
        p.settled = true; lease.changed(); lease.changed();
        check(started[0] == 1, "one settled native callback admits once");
        lease.finish(b, true); check(p.expands == 0, "stale ticket cannot reopen panel");
        lease.finish(a, true); lease.finish(a, true);
        check(p.expands == 1, "finish restores native panel exactly once");
        p.expanded = false; lease.begin(a, p); lease.whenCollapsed(a, () -> started[0]++); lease.finish(a, true);
        check(started[0] == 2 && p.expands == 1 && p.collapses == 1, "closed panel stays closed");
        p.expanded = true; p.settled = false; lease.begin(a, p);
        lease.whenCollapsed(a, () -> started[0]++); lease.cancel(); p.settled = true; lease.changed(); lease.finish(a, true);
        check(started[0] == 2 && p.expands == 1, "exit or lock cancels admission and reopening");
        p.expanded = true; lease.begin(a, p); lease.begin(b, p); lease.finish(a, true);
        check(p.expands == 1, "replacement owner retains original expanded state without flashing");
        lease.finish(b, true); check(p.expands == 2, "replacement restores original expanded state");
        p.expanded = true; p.failCollapse = true; lease.begin(a, p); lease.whenCollapsed(a, () -> started[0]++);
        check(started[0] == 2 && errors.size() == 1, "partially failed collapse cannot admit tasks");
        lease.finish(a, true); check(p.expands == 3, "failed collapse still restores saved native state");
        p.failCollapse = false; p.failRead = true; lease.begin(a, p); lease.whenCollapsed(a, () -> started[0]++);
        check(errors.size() == 2 && started[0] == 2, "unknown native state does not authorize swap");
        lease.finish(a, false); check(p.expands == 3, "cancel can leave native panel safely closed");
        lease.begin(a, null); lease.whenCollapsed(a, () -> started[0]++); lease.finish(a, true);
        check(started[0] == 3, "absent panel adds no fabricated state or delay");
        System.out.println("Native panel lease checks=" + checks + " passed");
    }
}
