package com.jiusis.mistep;

/** Project a native-density app into the upper rectangle at the selected interface size. */
final class TopTaskGeometry {
    final int logicalWidth, logicalHeight;
    final int left, top, right, bottom;
    final float scale;
    final int densityDpi;
    static final int DISPLAY_DPI = 242;
    TopTaskGeometry(int[] region, float density) {
        left=region[0];top=region[1];right=region[2];bottom=region[3];
        densityDpi=Math.max(1,Math.round((Float.isFinite(density)&&density>0f?density:1f)*160f));
        scale=Math.min(.9f,DISPLAY_DPI/(float)densityDpi);
        logicalWidth=Math.max(1,Math.round((right-left)/scale));
        logicalHeight=Math.max(1,Math.round((bottom-top)/scale));
    }
    TopTaskGeometry(int width, int height, int strip, int inset, float density) {
        left=top=0;right=Math.max(1,width);bottom=Math.max(1,strip);
        int longEdge=Math.max(1,Math.max(width,height));
        int shortEdge=Math.max(1,Math.min(width,height));
        float fit=Math.min(1f,Math.min(right/(float)longEdge,bottom/(float)shortEdge));
        densityDpi=Math.max(1,Math.round((Float.isFinite(density)&&density>0f?density:1f)*160f));
        // Keep existing and newly inflated Views on the same resource density.
        // Move the selected density ratio into the one surface/input transform;
        // this preserves the prior dp viewport and correctly resized control size.
        scale=(float)Math.sqrt(fit)*DISPLAY_DPI/densityDpi;
        // The logical viewport has the available rectangle's aspect, not the display's.
        // App bounds and resource dimensions must use this same logical viewport.
        logicalWidth=Math.max(1,Math.round(right/scale));
        logicalHeight=Math.max(1,Math.round(bottom/scale));
    }
    int[] bounds() { return new int[]{left, top, right, bottom}; }
}
