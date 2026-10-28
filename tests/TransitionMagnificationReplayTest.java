package com.jiusis.mistep;

public final class TransitionMagnificationReplayTest {
    private static int checks;

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args) throws Throwable {
        Object display = new Object();
        Object otherDisplay = new Object();
        Object transaction = new Object();
        Object[] calls = new Object[2];
        int[] count = {0};
        TransitionMagnificationReplay.Applier applier = (target, finish) -> {
            calls[0] = target;
            calls[1] = finish;
            count[0]++;
        };

        check(!TransitionMagnificationReplay.reapplyIfActive(false, display, true,
                new Object[]{display}, transaction, applier), "exit never restores zoom");
        check(!TransitionMagnificationReplay.reapplyIfActive(true, display, false,
                new Object[]{display}, transaction, applier), "replay requires WMS lock");
        check(!TransitionMagnificationReplay.reapplyIfActive(true, display, true,
                new Object[]{otherDisplay}, transaction, applier), "unrelated display is untouched");
        check(!TransitionMagnificationReplay.reapplyIfActive(true, display, true,
                new Object[]{display}, null, applier), "missing finish transaction is ignored");
        check(count[0] == 0, "rejected transitions do not apply a matrix");

        check(TransitionMagnificationReplay.reapplyIfActive(true, display, true,
                new Object[]{otherDisplay, display}, transaction, applier),
                "active default-display finish is replayed");
        check(count[0] == 1 && calls[0] == display && calls[1] == transaction,
                "replay uses the same display and final transaction");
        System.out.println("TransitionMagnificationReplay regression passed: " + checks + " checks");
    }
}
