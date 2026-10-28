package com.jiusis.mistep;

/**
 * 动画缩放命令的解析与插值（纯逻辑，可离线回归）。
 *
 * 一行内容："anim <scale> <offsetY> <offsetX> <durationMs>"，例如
 * "anim 0.2472 1237 0 300"。它只描述目标状态与时长；起点由系统侧读取当前
 * 生效的 MagnificationSpec（sZoomScale/Y/X），与 R2 ZoomAnimController 从
 * 当前真实 scale/offset 建 from、复制目标 spec 为 to 的做法一致。
 *
 * 行内不含起始时间：POC 只验证缩放动画本身；与宿主桥接动画的同步另做。
 */
final class ZoomAnimCommand {

    /** 过短的动画在 WM 队列里可能只剩一两帧，没有意义。 */
    static final long MIN_DURATION_MS = 40L;
    /** 超过 2 秒的整屏缩放动画不属于产品交互范围，拒绝以免误用。 */
    static final long MAX_DURATION_MS = 2000L;

    static final class Plan {
        final float scale;
        final float offsetY;
        final float offsetX;
        final long durationMs;

        Plan(float scale, float offsetY, float offsetX, long durationMs) {
            this.scale = scale;
            this.offsetY = offsetY;
            this.offsetX = offsetX;
            this.durationMs = durationMs;
        }

        String describe() {
            return "scale=" + scale + " offsetY=" + offsetY
                    + " offsetX=" + offsetX + " duration=" + durationMs + "ms";
        }
    }

    private ZoomAnimCommand() {}

    /**
     * 返回动画计划；不是 anim 行、字段非法、或超出宿主租约时返回 null。
     * 租约判定与 {@link ZoomCommand} 相同：写入早于进程启动、或超过
     * 15 秒没有新写入（宿主死亡）都不执行，交给普通状态请求回退。
     */
    static Plan parse(String line, long modified, long started, long now) {
        if (line == null) return null;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || !trimmed.startsWith("anim")) return null;
        if (modified < started || now - modified > ZoomCommand.ACTIVE_TIMEOUT_MS) return null;
        String[] parts = trimmed.split("\\s+");
        if (parts.length != 5 || !"anim".equals(parts[0])) return null;
        try {
            float scale = Float.parseFloat(parts[1]);
            float offsetY = Float.parseFloat(parts[2]);
            float offsetX = Float.parseFloat(parts[3]);
            long duration = Long.parseLong(parts[4]);
            if (!Float.isFinite(scale) || !Float.isFinite(offsetY) || !Float.isFinite(offsetX)) {
                return null;
            }
            if (scale <= 0f || scale > 1f) return null;
            if (offsetY < 0f || offsetY > 10000f || offsetX < 0f || offsetX > 10000f) {
                return null;
            }
            if (duration < MIN_DURATION_MS || duration > MAX_DURATION_MS) return null;
            return new Plan(scale, offsetY, offsetX, duration);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /** R2 ZoomAnimController.applyAnim 的同一算法：逐分量线性插值。 */
    static float valueAt(float from, float to, float fraction) {
        float clamped = fraction <= 0f ? 0f : Math.min(fraction, 1f);
        return from + (to - from) * clamped;
    }

    /** 供离线回归直接验证三个分量。 */
    static float[] stateAt(float fromScale, float fromOffsetY, float fromOffsetX,
            Plan plan, float fraction) {
        return new float[]{
                valueAt(fromScale, plan.scale, fraction),
                valueAt(fromOffsetY, plan.offsetY, fraction),
                valueAt(fromOffsetX, plan.offsetX, fraction)};
    }
}
