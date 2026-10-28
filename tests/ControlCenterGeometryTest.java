package com.jiusis.mistep;

public final class ControlCenterGeometryTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        for (int width : new int[]{240, 672, 1036, 1152, 2560}) for (int height : new int[]{96, 230, 410, 440}) {
            ControlCenterGeometry g = new ControlCenterGeometry(width, height, 14);
            check(g.tileTop(0) == g.cardTop + g.panelPadding && g.tileTop(1) == g.cardTop + g.panelPadding);
            check(g.tileLeft(0) == g.tileLeft(2) && g.tileLeft(1) == g.tileLeft(3));
            check(g.tileLeft(1) >= g.tileWidth + g.innerGap);
            check(g.modelLeft >= g.tileLeft(1) + g.tileWidth + g.gap);
            check(g.volumeLeft >= g.modelLeft + g.modelWidth + g.gap);
            check(g.brightnessLeft >= g.volumeLeft + g.sliderWidth + g.gap);
            check(g.brightnessLeft + g.sliderWidth <= width);
            check(g.secondRow + g.tileHeight <= height);
            check(g.tileHeight > 0 && g.modelWidth > 0 && g.sliderWidth > 0);
            check(g.cardTop >= 0 && g.cardTop + g.networkWidth <= height && g.controlsHeight == height);
            check(Math.abs(2 * g.modelLeft + g.modelWidth - width) <= 1);
            check(Math.abs(2 * g.sliderWidth + g.gap - g.networkWidth) <= 1);
        }
        TopBarGeometry noInset = new TopBarGeometry(1200, 661, 0, 3f);
        TopBarGeometry hugeInset = new TopBarGeometry(1200, 661, 144, 3f);
        check(hugeInset.blankTop == 42 && hugeInset.blankBottom == noInset.blankBottom);
        check(hugeInset.headerTop == noInset.headerTop && hugeInset.railTop == noInset.railTop);
        check(ControlCenterGeometry.progressCapRadius(100, 0, 22) == 0);
        check(ControlCenterGeometry.progressCapRadius(100, 8, 22) == 4);
        check(ControlCenterGeometry.progressCapRadius(100, 100, 22) == 22);
        check(ControlCenterGeometry.progressCapRadius(20, 100, 22) == 10);
        System.out.println("ControlCenterGeometry " + checks + " checks passed");
    }
}
