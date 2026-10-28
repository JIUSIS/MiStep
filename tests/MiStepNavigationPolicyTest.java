package com.jiusis.mistep;

public final class MiStepNavigationPolicyTest {
    private static int checks;

    private static void expect(boolean expected, String reason) {
        checks++;
        boolean actual = MiStepNavigationPolicy.shouldSuspend(reason);
        if (actual != expected) {
            throw new AssertionError("reason=" + reason
                    + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        expect(true, "homekey");
        expect(true, "recentapps");
        expect(false, "gestureNav");
        expect(false, "back");
        expect(false, null);
        expect(false, "HOMEKEY");
        System.out.println("MiStepNavigationPolicy: " + checks + " checks passed");
    }
}
