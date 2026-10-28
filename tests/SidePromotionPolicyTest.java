package com.jiusis.mistep;

public final class SidePromotionPolicyTest {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        Object token = new Object();
        check(SidePromotionPolicy.sameBinding(8, token, 4, 9, "pkg",
                8, token, 4, 9, "pkg"), "current task binding matches");
        check(!SidePromotionPolicy.sameBinding(8, token, 4, 9, "pkg",
                9, token, 4, 9, "pkg"), "stale task id rejected");
        check(!SidePromotionPolicy.sameBinding(8, token, 4, 9, "pkg",
                8, new Object(), 4, 9, "pkg"), "stale token rejected");
        check(!SidePromotionPolicy.sameBinding(8, token, 4, 9, "pkg",
                8, token, 5, 9, "pkg"), "old scene rejected");
        check(!SidePromotionPolicy.sameBinding(8, token, 4, 9, "pkg",
                8, token, 4, 10, "pkg"), "rebound slot rejected");
        check(!SidePromotionPolicy.sameBinding(8, token, 4, 9, "pkg",
                8, token, 4, 9, "other"), "changed package rejected");
        check(SidePromotionPolicy.retryDelay(100, 1000, 100, false) == 0,
                "ready request runs immediately");
        check(SidePromotionPolicy.retryDelay(100, 1000, 350, false) == 200,
                "mode guard is polled with a bounded delay");
        check(SidePromotionPolicy.retryDelay(100, 1000, 100, true) == 120,
                "busy transfer is retried");
        check(SidePromotionPolicy.retryDelay(1000, 1000, 1000, true) == -1,
                "expired request stops");
        System.out.println("SidePromotionPolicy: " + checks + " checks passed");
    }
}
