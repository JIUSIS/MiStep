package com.jiusis.mistep;

public final class TabletSixWindowTest {
    private static int checks;
    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        for (int h : new int[]{800,1200,1600,2032,2400})
            for (float ratio : new float[]{1.2f,1.5f,1.6f,1.8f,2f})
                for (float density : new float[]{1,1.5f,2,2.5f,3})
                    for (boolean right : new boolean[]{false,true}) {
                    int w=Math.round(h*ratio);
                    MiStepLayout l=MiStepLayout.of(w,h,right,density,true);
                    check(l.tabletLandscape && l.mainOnRight==right && l.cellCount==4,
                            "landscape preserves the main side selected by the entry gesture");
                    check(l.gap==Math.max(2,Math.round(MiStepLayout.DESIGN_GAP_DP*density)),
                            "tablet uses the same physical gap rule as portrait");
                    check(l.fillsScreen(),"regions reach display edges");
                    int end=0;
                    for(int i=0;i<4;i++) {
                        int[] b=l.cellBounds(i);
                        check(b[0]==(i==0?0:end+l.gap) && b[1]==l.toolHeight+l.gap
                                && b[2]<=w && b[3]==l.topStrip-l.gap,"top cells preserve shared gutters");
                        check(b[2]-b[0]>b[3]-b[1],"landscape panel");
                        check(l.cellScale(i)>0 && l.cellScale(i)<l.mainScale,"full display viewport fits cell");
                        float shownWidth=w*l.cellScale(i), shownHeight=h*l.cellScale(i);
                        check(Math.abs(shownWidth-(b[2]-b[0]))<=1.5f,
                                "cell "+i+" must not expose a horizontal backing strip");
                        check(Math.abs(shownHeight-(b[3]-b[1]))<=1.5f,
                                "cell "+i+" must not expose a vertical backing strip");
                        end=b[2];
                    }
                    int[] r=l.interactiveBounds();
                    check(r[0]==(right?0:l.mainWidth+l.gap) && r[2]==(right?l.cellWidth:w)
                            && r[1]==l.mainY && r[3]==h,"interactive task follows the entry edge");
                    check(right?r[2]+l.gap==l.mainX:l.mainWidth+l.gap==r[0],
                            "main and interactive task retain exactly one shared gutter");
                    int[] gap=l.mainGapBounds();
                    check(gap[0]==(right?r[2]:l.mainWidth) && gap[2]-gap[0]==l.gap
                            && gap[1]==l.mainY && gap[3]==h,
                            "wallpaper gutter follows the mirrored main and interactive task");
                    // Real tablet admission requires a short edge of at least 600dp.
                    if (h>=600*density) check(r[2]-r[0]<r[3]-r[1],"right panel portrait");
                    TopTaskGeometry g=new TopTaskGeometry(r,density);
                    check(g.left==r[0] && g.top==r[1],"right app configuration uses the panel origin");
                    if (h>=600*density) check(g.logicalWidth<g.logicalHeight,"portrait app configuration");
                    check(Math.abs(g.logicalWidth*g.scale-(r[2]-r[0]))<=.5f,"one horizontal transform");
                    check(Math.abs(g.logicalHeight*g.scale-(r[3]-r[1]))<=.5f,"one vertical transform");
                    MiStepLayout old=MiStepLayout.of(w,h,false,density);
                    check(old.cellCount==3 && !old.tabletLandscape,"legacy caller unchanged");
                    check(l.mainScale<old.mainScale && l.mainOffsetY>old.mainOffsetY,
                            "toolbar height is included in the tablet task layout");
                    check(Math.abs(l.mainDispWidth-l.mainWidth)<=1
                            && Math.abs(l.mainDispHeight-l.mainHeight)<=1,
                            "main task retains display aspect without exposed backing");
                    check(l.mainOffsetX==(right?l.sideWidth:0) && l.mainOffsetY==l.mainY,
                            "main surface and input use the same origin");
                }
        MiStepLayout pad=MiStepLayout.of(3048,2032,false,2.5f,true);
        check(pad.topStrip==606 && pad.mainWidth==2139 && pad.mainHeight==1426,
                "Pad toolbar and proportional row determine the remaining main rectangle");
        check(pad.cellBounds(0)[2]==759 && pad.cellBounds(0)[3]-pad.cellBounds(0)[1]==506,
                "Pad top task fills 759 by 506 instead of 612 by 408");
        MiStepLayout leftEntry=MiStepLayout.of(3048,2032,true,2.5f,true);
        check(java.util.Arrays.equals(leftEntry.mainBounds(),new int[]{909,606,3048,2032})
                && java.util.Arrays.equals(leftEntry.interactiveBounds(),new int[]{0,606,904,2032}),
                "left corner opens the portrait task at left and real main at right");
        for(int i=0;i<4;i++)check(java.util.Arrays.equals(pad.cellBounds(i),leftEntry.cellBounds(i)),
                "entry edge leaves the four top cells in place");
        check(!MiStepLayout.of(1200,2608,false,2.75f,true).tabletLandscape,"portrait keeps legacy layout");
        check(!MiStepLayout.of(4000,1200,false,2,true).tabletLandscape,"ultrawide outside supported geometry");
        System.out.println("TabletSixWindow: "+checks+" checks passed");
    }
}
