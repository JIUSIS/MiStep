package com.jianguo.mistep;

/** Dense visible rows: preserve the 10px main gap and remove internal toolbar whitespace. */
final class TopBarGeometry {
    static final int MAIN_GAP_PX = 10;
    final int headerTop, headerHeight, railTop, railHeight;
    final int edge, buttonWidth, titleInset;
    final int blankTop, blankBottom;

    TopBarGeometry(int width, int height, int inset, float density) {
        density = density > 0f ? density : 1f;
        int usable = Math.max(3, height - MAIN_GAP_PX);
        float scale = Math.min(1f, usable / (63f * density));
        railHeight = Math.max(1, Math.round(34f * density * scale));
        int rowGap = Math.max(1, Math.round(1f * density * scale));
        headerHeight = Math.max(1, Math.min(usable - railHeight - rowGap,
                Math.round(28f * density * scale)));
        railTop = height - MAIN_GAP_PX - railHeight;
        headerTop = railTop - rowGap - headerHeight;
        edge = Math.max(2, Math.round(8f * density * scale));
        // A compact physical-corner margin, independent of the status-bar band.
        blankTop = Math.min(Math.max(0, headerTop - edge), Math.round(14f * density * scale));
        blankBottom = Math.max(blankTop, headerTop - edge);
        // Four controls per side, with a symmetric title reservation even at large font scales.
        int titleBudget = Math.max(1, Math.round(76f * density * scale));
        buttonWidth = Math.max(1, Math.min(Math.round(36f * density * scale),
                (width - 2 * edge - titleBudget) / 8));
        titleInset = edge + 4 * buttonWidth;
    }
}
