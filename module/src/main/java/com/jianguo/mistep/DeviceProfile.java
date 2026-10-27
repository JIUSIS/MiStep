package com.jianguo.mistep;

import android.content.Context;
import android.os.Build;

/**
 * 机型识别 + 可选覆盖表。
 *
 * 屏幕、密度、状态栏、挖孔、手势条都在运行时实测，所以表里只放“实测拿不准、或该机型需要特别
 * 处理”的覆盖值；未收录机型按品牌/设备名兜底，不需要改代码。
 * 收录规则：键 = Build.DEVICE（ro.product.device）；值 = 侧栏宽 dp / 角部区 dp / 覆盖项。
 */
final class DeviceProfile {
    /** 侧栏设计宽（dp）。 */
    final float sideWidthDp;
    /** 角部滑动起手区（dp）。 */
    final float cornerRegionDp;
    /** 顶栏额外下移（px，0 = 用实测状态栏高度）。 */
    final int topInsetPx;
    /** 备注（日志用）。 */
    final String note;

    private DeviceProfile(float sideWidthDp, float cornerRegionDp, int topInsetPx, String note) {
        this.sideWidthDp = sideWidthDp;
        this.cornerRegionDp = cornerRegionDp;
        this.topInsetPx = topInsetPx;
        this.note = note;
    }

    private static final DeviceProfile DEFAULT = new DeviceProfile(99.3f, 116f, 0, "default");

    /**
     * 已核实的机型表。数值只覆盖设计偏好；分辨率/密度/insets 一律运行时读。
     * 新增机型时按“设备代号 + 备注”追加，屏幕参数不需要填。
     */
    private static DeviceProfile lookup(String device) {
        switch (device) {
            // 小米 17 系列
            case "popsicle": return new DeviceProfile(99.3f, 116f, 0, "Xiaomi 17 Pro Max");
            // 红米 Note 11T 系列及之后
            case "xaga": return new DeviceProfile(99.3f, 116f, 0, "Redmi Note 11T Pro");
            default: return null;
        }
    }

    static DeviceProfile of(Context context) {
        String device = Build.DEVICE == null ? "" : Build.DEVICE.toLowerCase(java.util.Locale.ROOT);
        DeviceProfile known = lookup(device);
        if (known != null) return known;
        return DEFAULT;
    }

    /** 自动识别结果：品牌/机型/设备名/屏幕/密度/状态栏，用于日志与后续补表。 */
    static String describe(Context context) {
        DeviceProfile profile = of(context);
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int statusBar = statusBarHeightPx(context);
        return "brand=" + Build.BRAND + " model=" + Build.MODEL + " device=" + Build.DEVICE
                + " screen=" + metrics.widthPixels + "x" + metrics.heightPixels
                + " density=" + metrics.density + " statusBarPx=" + statusBar
                + " sideDp=" + profile.sideWidthDp + " cornerDp=" + profile.cornerRegionDp
                + " note=" + profile.note;
    }

    /** 实测状态栏高度（资源），失败时用 24dp 兜底。 */
    static int statusBarHeightPx(Context context) {
        try {
            int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                int px = context.getResources().getDimensionPixelSize(id);
                if (px > 0) return px;
            }
        } catch (Throwable ignored) {
        }
        return Math.round(24f * context.getResources().getDisplayMetrics().density);
    }
}
