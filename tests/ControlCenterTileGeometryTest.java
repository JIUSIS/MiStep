package com.jiusis.mistep;

public final class ControlCenterTileGeometryTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }
    public static void main(String[] args) {
        int reproducedClips = 0;
        for (int widthDp : new int[]{280, 320, 360, 393, 444, 600})
            for (float density : new float[]{1f, 2.75f, 3f, 3.5f})
                for (float fontScale : new float[]{1f, 1.3f, 1.5f, 2f}) {
                    ControlCenterGeometry parent = new ControlCenterGeometry(
                            Math.round(widthDp * density), Math.round(160 * density), Math.round(8 * density));
                    int icon = Math.round(36 * density);
                    int line = Math.round(Math.max(14, 12 * fontScale) * density);
                    if (parent.codeWidth < icon || parent.tileHeight < Math.round(54 * density))
                        reproducedClips++;
                    ControlCenterTileGeometry g = new ControlCenterTileGeometry(
                            parent.codeWidth, parent.tileHeight, icon, line, Math.round(4 * density));
                    check(g.iconSize > 0 && g.iconSize <= parent.codeWidth);
                    check(g.iconSize <= icon);
                    check(g.iconTop >= 0 && g.iconTop + g.iconSize <= g.captionTop);
                    check(g.captionTop + g.captionHeight <= parent.tileHeight);
                    check(g.captionHeight > 0 && g.captionHeight <= line);
                    check(Math.abs(g.iconTop - (parent.tileHeight - g.captionTop - g.captionHeight)) <= 1);
                }
        check(reproducedClips > 0);
        // Preserve the established layout when the complete 36 + 4 + 14 dp fits.
        ControlCenterTileGeometry normal = new ControlCenterTileGeometry(48, 64, 36, 14, 4);
        check(normal.iconSize == 36 && normal.iconTop == 5 && normal.captionTop == 45 && normal.captionHeight == 14);
        // Pathological cutout allocations remain bounded rather than spilling into another control.
        for (int width = 1; width <= 55; width++) for (int height = 1; height <= 70; height++) {
            ControlCenterTileGeometry g = new ControlCenterTileGeometry(width, height, 36, 14, 4);
            check(g.iconSize >= 0 && g.iconSize <= width);
            check(g.iconTop >= 0 && g.captionTop >= g.iconTop + g.iconSize);
            check(g.captionTop + g.captionHeight <= height);
        }
        System.out.println("ControlCenterTileGeometry " + checks + " checks passed; "
                + reproducedClips + " formerly clipped screen/font combinations");
    }
}
