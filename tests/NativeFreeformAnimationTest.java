package com.jiusis.mistep;

import android.graphics.Rect;

public final class NativeFreeformAnimationTest {
    static int checks;
    static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Rect full = new Rect(0, 0, 1080, 2460);
        float[] left = {.75f, 615f, 0f}, right = {.75f, 615f, 270f};
        check(new Rect(0,615,810,2460).equals(NativeFreeformAnimation.startBounds(full,1080,2460,left)), "left visible source");
        check(new Rect(270,615,1080,2460).equals(NativeFreeformAnimation.startBounds(full,1080,2460,right)), "right visible source");
        check(full.equals(new Rect(0,0,1080,2460)), "shared logical source preserved");
        check(new Rect(270,690,1080,2385).equals(NativeFreeformAnimation.startBounds(new Rect(0,100,1080,2360),1080,2460,right)), "status and navigation insets are transformed, not clipped");
        check(NativeFreeformAnimation.startBounds(new Rect(100,200,700,1700),1080,2460,right)==null, "existing physical freeform source rejected");
        check(NativeFreeformAnimation.startBounds(new Rect(270,615,1080,2460),1080,2460,right)==null, "already transformed source rejected");
        check(NativeFreeformAnimation.startBounds(full,1080,2460,new float[]{1,0,0})==null, "inactive zoom rejected");
        check(NativeFreeformAnimation.startBounds(full,1080,2460,new float[]{Float.NaN,0,0})==null, "invalid scale rejected");
        check(NativeFreeformAnimation.startBounds(full,1080,2460,new float[]{.75f,Float.POSITIVE_INFINITY,0})==null, "invalid offset rejected");
        check(NativeFreeformAnimation.startBounds(full,1080,2460,null)==null, "no workspace transform rejected");
        check(NativeFreeformAnimation.startBounds(new Rect(0,0,2460,1080),1080,2460,right)==null, "rotated source rejected");
        check(NativeFreeformAnimation.startBounds(null,1080,2460,right)==null, "missing source rejected");
        System.out.println("NativeFreeformAnimation: "+checks+" checks passed");
    }
}
