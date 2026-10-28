package com.jiusis.mistep;

public final class FitScaleTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        // The failing 17PM, different densities/aspect ratios and both column sides.
        int[][] screens = {{1200,2608}, {1080,2460}, {1440,3200}, {1080,2400},
                {1220,2712}, {1080,1920}, {1200,2000}, {2608,1200}};
        for (int[] screen : screens) for (float density : new float[]{2f, 2.75f, 3f, 4f}) {
            for (boolean right : new boolean[]{false, true}) {
                MiStepLayout l = MiStepLayout.of(screen[0], screen[1], right, density);
                String tag = screen[0] + "x" + screen[1] + "@" + density + "/" + right;
                check(l.fillsScreen(), "fills screen " + tag);
                check(l.mainWidth - l.mainDispWidth <= 1, "no main side bands " + tag);
                check(l.mainHeight - l.mainDispHeight <= 1, "no main bottom band " + tag);
                check(l.mainOffsetX == l.mainX, "main anchored to pane " + tag);
                for (int i = 0; i < 3; i++) {
                    float k = l.cellScale(i);
                    check(k * l.width <= l.cellWidth + 0.001f, "side width unclipped " + tag);
                    check(k * l.height <= l.cellHeight[i] + 0.001f, "side height unclipped " + tag);
                    check(l.cellWidth - k * l.width < 2.1f, "side fills width " + tag);
                    check(l.cellHeight[i] - k * l.height < 4.1f, "side fills height " + tag);
                }
            }
        }
        MiStepLayout pm = MiStepLayout.of(1200, 2608, false, 3f);
        check(pm.mainOffsetX == 0 && pm.mainDispWidth == pm.mainWidth,
                "17PM formerly had 22px black edges");
        check(pm.topStrip < 744, "toolbar cannot impose old 744px floor");
        System.out.println("FitScale: " + checks + " checks passed");
    }
}
