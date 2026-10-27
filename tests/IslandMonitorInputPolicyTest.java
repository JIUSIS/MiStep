package com.jianguo.mistep;

public final class IslandMonitorInputPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        float[] left = {.74654907f, 661f, 0f};
        float[] normalized = map(740f, 872f, left);
        near(991.22754f, normalized[0]);
        near(282.6338f, normalized[1]);
        // Retained 17PM expanded region: an actual button DOWN must not post an outside collapse.
        check(!expanded(740f, 872f));
        check(expanded(normalized[0], normalized[1]));
        // Outside taps remain outside, including the host band above the scaled island.
        float[] outside = map(1190f, 1400f, left);
        check(!expanded(outside[0], outside[1]));
        outside = map(450f, 300f, left);
        check(!expanded(outside[0], outside[1]));
        float[] right = {.74654907f, 661f, 304f};
        normalized = map(1044f, 872f, right);
        near(991.22754f, normalized[0]);
        near(282.6338f, normalized[1]);
        check(expanded(normalized[0], normalized[1]));
        check(IslandMonitorInputPolicy.logicalDown(false, 0, 0, 740, 872, 740, 872, left) == null);
        check(IslandMonitorInputPolicy.logicalDown(true, 1, 0, 740, 872, 740, 872, left) == null);
        check(IslandMonitorInputPolicy.logicalDown(true, 0, 1, 740, 872, 740, 872, left) == null);
        check(IslandMonitorInputPolicy.logicalDown(true, 0, 0, 991, 282, 740, 872, left) == null);
        check(map(740, 872, null) == null);
        check(map(740, 872, new float[]{1, 0, 0}) == null);
        check(map(740, 872, new float[]{0, 0, 0}) == null);
        check(map(Float.NaN, 872, left) == null);
        check(map(740, 872, new float[]{.75f, Float.POSITIVE_INFINITY, 0}) == null);
        System.out.println("IslandMonitorInputPolicyTest PASS " + checks);
    }

    private static float[] map(float x, float y, float[] zoom) {
        return IslandMonitorInputPolicy.logicalDown(true, 0, 0, x, y, x, y, zoom);
    }

    private static boolean expanded(float x, float y) {
        return x >= 56 && x < 1143 && y >= 156 && y < 407;
    }

    private static void near(float expected, float actual) {
        check(Math.abs(expected - actual) < .001f);
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }
}
