package com.jiusis.mistep;

/** Verify that the main pane and the three-cell column exchange sides without gaps. */
public final class LayoutOrientationTest {
    private static int checks;

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args) {
        MiStepLayout left = MiStepLayout.of(1080, 2460, false);
        MiStepLayout right = MiStepLayout.of(1080, 2460, true);

        check(left.mainX == 0 && left.cellX == 813, "left main/cell columns");
        check(right.mainX == 273 && right.cellX == 0, "right main/cell columns");
        check(left.mainWidth == right.mainWidth, "main width is stable");
        check(left.cellWidth == right.cellWidth, "cell width is stable");
        check(left.topStrip == right.topStrip, "top strip is stable");
        check(left.fillsScreen() && right.fillsScreen(), "both orientations fill the screen");
        check(left.mainBounds()[3] == right.mainBounds()[3], "main bottoms align");
        for (int i = 0; i < MiStepLayout.CELL_COUNT; i++) {
            check(left.cellBounds(i)[1] == right.cellBounds(i)[1], "cell " + i + " top");
            check(left.cellBounds(i)[3] == right.cellBounds(i)[3], "cell " + i + " bottom");
        }
        int[] from = left.mainBounds();
        int[] to = right.cellBounds(0);
        check(from[0] == 0 && to[2] == 267, "shrinking window stays anchored at opposite edges");
        int[] sideFrom = left.cellBounds(0);
        int[] mainTo = right.mainBounds();
        check(sideFrom[2] == 1080 && mainTo[2] == 1080,
                "growing window stays anchored at opposite edges");
        System.out.println("Layout orientation regression passed: " + checks + " checks");
    }
}
