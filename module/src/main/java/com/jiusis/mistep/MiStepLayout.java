package com.jiusis.mistep;

/**
 * Mi Step的自适应几何（纯整数运算，不依赖任何 Android 类，便于离线验证）。
 *
 * 来源与两次更正：
 *   官方量测（报告六原始数值，R2 1080×2340）：满宽顶栏 592、主区 807 宽（自顶栏下沿至底）、
 *   官方侧栏容器 273（= 267 格宽 + 6 间隔）、格起始 y=592、格 267×579。
 *   2026-10-16 用户产品指令：**不要圆角**；2026-10-17 用户补充：**要一点缝隙区分窗口**（恢复官方 6px）。
 *
 * 主区和三个副任务均按当前屏幕宽高比等比显示。按三格总高度反求侧栏宽度，
 * 间隔按密度换算；顶栏内容适应剩余空间，不再以固定dp高度挤压应用画面。
 */
public final class MiStepLayout {

    /** 官方量测出的主区占比（= MODE_RIGHT_PERCENT = WIDTH_SCALE = 0.7472）。 */
    public static final float RATIO_MAIN = 0.7472f;

    /** 官方间隔 6px；2026-10-17 用户要求"稍微有点缝隙区分窗口"，恢复启用。 */
    public static final int GAP = 6;

    /**
     * 目标机 freeform/multi-window 任务的最小宽度预算（dp）。
     * v0.8.0 真机实测：格子 273px（≈99dp）时应用 appBounds 被撑到 550px（=200dp@440dpi）导致被裁一半，
     * 因此格子容器要按"最小宽不超过格子宽"反推密度；实际值在 200–220dp 之间，取 220 保守。
     */
    public static final int ASSUMED_MIN_APP_DP = 220;

    public static final int CELL_COUNT = 3;

    public static final float TOPBAR_HEADER_TOP_DP = 88f;
    public static final float TOPBAR_HEADER_HEIGHT_DP = 48f;
    public static final float TOPBAR_RAIL_TOP_DP = 142f;
    public static final float TOPBAR_RAIL_HEIGHT_DP = 56f;
    public static final float TOPBAR_CONTROL_BUTTON_DP = 48f;
    public static final float TOPBAR_CONTROL_SAFE_DP = 20f;

    public static float topBarTitleInsetDp(int buttonCount) {
        return TOPBAR_CONTROL_SAFE_DP + TOPBAR_CONTROL_BUTTON_DP * Math.max(0, buttonCount);
    }

    public final float mainRatio;
    public final int width;
    public final int height;
    /** The 0.7472 main pane can occupy either side while the cell column stays opposite. */
    public final boolean mainOnRight;
    /** 侧栏（副任务列）宽度 = 格子宽（无缝）。 */
    public final int sideWidth;
    /** 顶部横条高度（满宽，含标题栏与快捷栏）；主区与格子都从它的下沿开始。 */
    public final int topStrip;
    /** 窗口间隔（px）。 */
    public final int gap;
    public final int cellWidth;
    public final int[] cellHeight = new int[CELL_COUNT];

    /** 主区矩形（宿主视图布局区域）。 */
    public final int mainX;
    public final int mainY;
    public final int mainWidth;
    public final int mainHeight;

    /** 整屏内容等比放进主区后的实际缩放/居中显示矩形（不裁切）。 */
    public final float mainScale;
    public final int mainOffsetX;
    public final int mainOffsetY;
    public final int mainDispWidth;
    public final int mainDispHeight;

    /** 格子列 x（无缝：= 主区宽）。 */
    public final int cellX;
    /** 三个格子的起始 y，顺序为从上到下。 */
    public final int[] cellY = new int[CELL_COUNT];

    private MiStepLayout(int width, int height, boolean mainOnRight, float density) {
        this.width = width;
        this.height = height;
        this.mainOnRight = mainOnRight;

        this.gap = Math.max(2, Math.round(DESIGN_GAP_DP * density));
        // Fit one full-screen main task and three full-screen side tasks, all with
        // the display's aspect ratio. Solving their shared remaining height gives
        // S = W/4 + gap*(3/4 - W/(2H)). Density controls the gap, never task width.
        this.sideWidth = Math.round(width / 4f + gap * (0.75f - width / (2f * height)));
        this.topStrip = Math.round(this.sideWidth / (float) width * height);

        // 主区在顶栏下方；交换主副角色时整条主屏可移到右侧，侧栏同时移到左侧。
        this.mainX = mainOnRight ? sideWidth : 0;
        this.mainY = topStrip;
        this.mainWidth = width - sideWidth;
        this.mainRatio = mainWidth / (float) width;
        this.mainHeight = height - topStrip;
        float widthRatio = mainWidth / (float) width;
        float fitScale = Math.min(widthRatio, mainHeight / (float) height);
        this.mainScale = fitScale;
        this.mainDispWidth = Math.round(fitScale * width);
        this.mainDispHeight = Math.round(fitScale * height);
        this.mainOffsetX = mainX + (mainWidth - mainDispWidth) / 2;
        this.mainOffsetY = mainY + (mainHeight - mainDispHeight) / 2;

        // 格子宽 = 侧栏容器 − 一个间隔；格子列与主区之间也有一个间隔（官方几何）。
        this.cellWidth = sideWidth - gap;
        this.cellX = mainOnRight ? 0 : mainWidth + gap;

        // 3 格以 gap 间隔堆叠、填满到底：余数分给前几格。
        int usable = mainHeight - gap * (CELL_COUNT - 1);
        int base = usable / CELL_COUNT;
        int extra = usable % CELL_COUNT;
        int y = topStrip;
        for (int i = 0; i < CELL_COUNT; i++) {
            int h = base + (i < extra ? 1 : 0);
            cellHeight[i] = h;
            cellY[i] = y;
            y += h + gap;
        }
    }

    public static MiStepLayout of(int width, int height) {
        return of(width, height, false);
    }

    /** Fit the existing 200dp toolbar design inside the space above the tasks. */
    static float topBarScale(int height, int inset, float density) {
        return Math.min(1f, Math.max(1, height - Math.max(0, inset))
                / (200f * (density > 0f ? density : 2.75f)));
    }

    /** 设计基准间隔 2.18dp（R2 440dpi 下 6px）。 */
    public static final float DESIGN_GAP_DP = 2.18f;

    public static MiStepLayout of(int width, int height, boolean mainOnRight) {
        return of(width, height, mainOnRight, 440f / 160f);
    }

    public static MiStepLayout of(int width, int height, boolean mainOnRight, float density) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad screen size " + width + "x" + height);
        }
        return new MiStepLayout(width, height, mainOnRight, density <= 0f ? 2.75f : density);
    }

    public float cellScale(int i) {
        if (i < 0 || i >= CELL_COUNT) throw new IllegalArgumentException("cell index " + i);
        return Math.min(cellWidth / (float) width, cellHeight[i] / (float) height);
    }

    /** 第 i 个格子的矩形，顺序即 [左, 上, 右, 下]。 */
    public int[] cellBounds(int i) {
        if (i < 0 || i >= CELL_COUNT) {
            throw new IllegalArgumentException("cell index " + i);
        }
        return new int[]{cellX, cellY[i], cellX + cellWidth, cellY[i] + cellHeight[i]};
    }

    public int[] mainBounds() {
        return new int[]{mainX, mainY, mainX + mainWidth, mainY + mainHeight};
    }

    /** 布局是否铺满且不越界（用于离线自检；无缝版应恰好到屏幕底边）。 */
    public boolean fillsScreen() {
        int[] last = cellBounds(CELL_COUNT - 1);
        boolean sideFits = mainOnRight
                ? cellX == 0 && mainX + mainWidth == width
                : cellX + cellWidth == width && mainX == 0;
        return sideFits && last[3] == height
                && mainWidth + sideWidth == width
                && mainY + mainHeight == height
                && mainY == topStrip;
    }

    /**
     * 让给定像素宽度不触发应用最小宽夹取的最大密度（dpi）。
     * 例：格子 273px、假定最小 220dp → floor(273×160/220) = 198dpi；
     * 主区 807px → 586dpi，因此在原密度 440dpi 下主区不受限。
     */
    public static int densityForWidth(int widthPx, int nativeDensity) {
        int max = (int) Math.floor(widthPx * 160f / ASSUMED_MIN_APP_DP);
        max = Math.max(120, max);
        return Math.min(nativeDensity, max);
    }

    /** 副格容器密度（用于 WCT setDensityDpi）。 */
    public int cellDensityFor(int nativeDensity) {
        return densityForWidth(cellWidth, nativeDensity);
    }

    /** 主区容器密度；当前屏幕尺寸下等于原生密度（不需要改）。 */
    public int mainDensityFor(int nativeDensity) {
        return densityForWidth(mainWidth, nativeDensity);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("screen=").append(width).append('x').append(height)
                .append(" mainOnRight=").append(mainOnRight)
                .append(" main=").append(java.util.Arrays.toString(mainBounds()))
                .append(" sideWidth=").append(sideWidth)
                .append(" topStrip=").append(topStrip)
                .append(" gap=").append(gap)
                .append(" cell=").append(cellWidth).append("x")
                .append(java.util.Arrays.toString(cellHeight));
        for (int i = 0; i < CELL_COUNT; i++) {
            sb.append(" cell").append(i).append('=').append(java.util.Arrays.toString(cellBounds(i)));
        }
        return sb.toString();
    }
}
