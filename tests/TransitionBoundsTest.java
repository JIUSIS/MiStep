package com.jiusis.mistep;

import java.util.Arrays;

public final class TransitionBoundsTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static boolean same(int[] expected, int[] actual) {
        return Arrays.equals(expected, actual);
    }

    public static void main(String[] args) {
        int[] full = {0, 0, 1080, 2460};
        int[] left = {0, 622, 807, 2460};
        int[] right = {273, 622, 1080, 2460};

        check(same(new int[]{0, 622, 807, 2460},
                        TransitionBounds.clampCoords(full, left)),
                "left animation rectangle stays in main");
        check(same(new int[]{273, 622, 1080, 2460},
                        TransitionBounds.clampCoords(full, right)),
                "right animation rectangle keeps its display offset");
        check(TransitionBounds.clampCoords(left, left) == left,
                "a rectangle inside main is unchanged");
        int[] above = {0, 0, 1080, 600};
        check(TransitionBounds.clampCoords(above, left) == above,
                "non-overlapping bounds are unchanged");
        check(TransitionBounds.clampCoords(null, left) == null,
                "null end bounds are ignored");
        check(TransitionBounds.clampCoords(full, null) == full,
                "null main bounds leave the animation untouched");
        int[] offsetEnd = {100, 200, 900, 2200};
        check(same(new int[]{100, 622, 807, 2200},
                        TransitionBounds.clampCoords(offsetEnd, left)),
                "animation intersections stay in display coordinates");
        check(same(new int[]{0, 0, 1080, 2460}, full), "input rectangle is not mutated");

        System.out.println("TransitionBoundsTest PASS " + checks);
    }
}
