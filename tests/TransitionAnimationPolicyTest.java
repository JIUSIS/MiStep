package com.jiusis.mistep;

public final class TransitionAnimationPolicyTest {
    public static void main(String[] args) {
        expect(true, true, false, 1, 1, false, 1, false);
        expect(false, false, false, 1, 1, false, 1, false);
        expect(false, true, true, 1, 1, false, 1, false);
        expect(false, true, false, 2, 1, false, 1, false);
        expect(false, true, false, 1, 2, false, 1, false);
        expect(false, true, false, 1, 1, true, 1, false);
        expect(false, true, false, 1, 1, false, 1, true);
        expect(false, true, false, 1, 1, false, 5, false);
        expect(false, true, false, 1, 1, false, 6, false);
        System.out.println("TransitionAnimationPolicyTest PASS 9");
    }

    private static void expect(boolean expected, boolean active, boolean busy,
            int transition, int mode, boolean side, int windowingMode, boolean rotating) {
        boolean actual = TransitionAnimationPolicy.shouldClampOpenBounds(
                active, busy, transition, mode, side, windowingMode, rotating);
        if (actual != expected)
            throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
