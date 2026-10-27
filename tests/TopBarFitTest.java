package com.jianguo.mistep;

public final class TopBarFitTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) {
        int[][] screens = {{1200,2608,144}, {1080,2460,122}, {1440,3200,144},
                {1080,1920,96}, {2608,1200,96}, {720,1600,72}, {240,640,20}};
        for (int[] screen : screens) for (float density : new float[]{1f,2f,2.75f,3f,4f}) {
            MiStepLayout layout = MiStepLayout.of(screen[0], screen[1], false, density);
            TopBarGeometry g = new TopBarGeometry(screen[0], layout.topStrip, screen[2], density);
            check(layout.topStrip - (g.railTop + g.railHeight) == 10, "main-to-rail gap is 10 physical pixels");
            int rowGap = g.railTop - (g.headerTop + g.headerHeight);
            check(rowGap > 0 && rowGap <= Math.max(1, Math.round(2f * density)), "rows have a small positive gap");
            float iconGap = rowGap + Math.max(0f, g.headerHeight - 23f * density) / 2f
                    + Math.max(0f, g.railHeight - 31f * density) / 2f;
            check(iconGap <= 8f * density, "visible icons stay close, including internal row whitespace");
            check(g.headerTop >= 0, "header stays inside top strip");
            check(g.headerHeight > 0 && g.railHeight > 0, "positive visible header and rail");
            check(2 * g.titleInset < screen[0], "title stays visible between the groups");
            check(g.edge + 4 * g.buttonWidth == g.titleInset, "four controls remain clear of centered title");
            check(g.blankTop >= 0 && g.blankBottom >= g.blankTop
                            && (g.blankBottom == 0 || g.blankTop > 0),
                    "visible controls leave a physical-corner margin; tiny strips may omit controls");
            check(g.blankBottom <= g.headerTop || g.blankBottom == g.blankTop,
                    "clipboard cannot cover header or rail");
        }
        TopBarGeometry target = new TopBarGeometry(1200, 661, 144, 3f);
        check(target.buttonWidth < 48 * 3, "button pitch is tighter than 56 baseline");
        check(target.headerTop + target.headerHeight < target.railTop, "no toolbar overlap on 17PM");
        System.out.println("TopBarFit: " + checks + " checks passed");
    }
}
