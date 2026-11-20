package com.jiusis.mistep;

public final class TopTaskReturnPolicyTest {
    public static void main(String[] args) {
        check(TopTaskReturnPolicy.keepUpperTask(true));
        check(!TopTaskReturnPolicy.keepUpperTask(false));
        check(!TopTaskReturnPolicy.liveCenterReveal(true, true, true));
        check(!TopTaskReturnPolicy.liveCenterReveal(false, false, true));
        check(!TopTaskReturnPolicy.liveCenterReveal(false, true, false));
        check(TopTaskReturnPolicy.liveCenterReveal(false, true, true));
        var centered = TopTaskReturnPolicy.centeredFrame(1080, 2460);
        check(centered.scaleX == .14f && centered.scaleY == .14f);
        check(centered.at(-1f) == centered && centered.at(2f) == TopTaskReturnPolicy.RevealFrame.FULL);
        for (float progress : new float[]{0f, .25f, .5f, 1f}) {
            var frame = centered.at(progress);
            near(frame.x + 1080 * frame.scaleX / 2f, 540f);
            near(frame.y + 2460 * frame.scaleY / 2f, 1230f);
        }
        for (boolean mainOnRight : new boolean[]{false, true}) {
            int mainWidth = 2139, mainX = mainOnRight ? 909 : 0, mainY = 606;
            float mainScale = mainWidth / 3048f;
            float left = mainOnRight ? 924f : 1224f;
            var start = TopTaskReturnPolicy.tabletPushFrame(left, mainY, 904, 1426,
                    mainX, mainY, mainScale, 3048, 2032);
            for (float progress : new float[]{0f, .25f, .5f, 1f}) {
                var frame = start.at(progress);
                float x = mainX + frame.x * mainScale;
                float width = 3048 * frame.scaleX * mainScale;
                near(x, left + (mainX - left) * progress);
                near(x + width, left + 904 + (mainX + mainWidth - left - 904) * progress);
                near(frame.y, 0f);
                near(2032 * frame.scaleY * mainScale, 1426f);
            }
        }
        System.out.println("TopTaskReturnPolicy: 48 checks passed");
    }

    private static void near(float actual, float expected) {
        check(Math.abs(actual - expected) < .002f);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("top return policy changed");
    }
}
