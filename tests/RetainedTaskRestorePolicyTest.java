package com.jiusis.mistep;

import java.util.Set;

public final class RetainedTaskRestorePolicyTest {
    static int checks;
    public static void main(String[] args) {
        Object a = new Object(), b = new Object(), c = new Object();
        // Three retained tasks; the user opened B from Home while Mi Step was closed.
        Set<Object> current = Set.of(b);
        check(RetainedTaskRestorePolicy.reclaim(true, true, a, current));
        check(!RetainedTaskRestorePolicy.reclaim(true, true, b, current));
        check(RetainedTaskRestorePolicy.reclaim(true, true, c, current));
        check(RetainedTaskRestorePolicy.reclaim(true, true, a, Set.of()));
        check(!RetainedTaskRestorePolicy.reclaim(false, true, a, Set.of()));
        check(!RetainedTaskRestorePolicy.reclaim(true, false, a, Set.of()));
        check(!RetainedTaskRestorePolicy.reclaim(true, true, null, Set.of()));
        for (boolean fullscreen : new boolean[]{false, true})
            for (boolean visible : new boolean[]{false, true})
                for (boolean focused : new boolean[]{false, true})
                    check(RetainedTaskRestorePolicy.preserveAsMain(fullscreen, visible, focused)
                            == (fullscreen && (visible || focused)));
        System.out.println("RetainedTaskRestorePolicy checks=" + checks + " passed");
    }
    static void check(boolean result) { checks++; if (!result) throw new AssertionError(); }
}
