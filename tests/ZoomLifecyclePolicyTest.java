package com.jianguo.mistep;

public final class ZoomLifecyclePolicyTest {
    private static int checks;

    private static void check(boolean expected, boolean actual, String name) {
        if (expected != actual) throw new AssertionError(name);
        checks++;
    }

    public static void main(String[] args) {
        check(true, ZoomLifecyclePolicy.shouldReassertGeometry(true, true, true),
                "active visible host renews its geometry zoom");
        check(false, ZoomLifecyclePolicy.shouldReassertGeometry(false, false, true),
                "suspended host cannot reapply zoom");
        check(false, ZoomLifecyclePolicy.shouldReassertGeometry(true, false, true),
                "hidden host cannot reapply zoom");
        check(false, ZoomLifecyclePolicy.shouldReassertGeometry(true, true, false),
                "screen-off host cannot reapply zoom");
        check(true, ZoomLifecyclePolicy.shouldApplyDelayedWrite(4, 4, false, false),
                "current exit completion is accepted");
        check(false, ZoomLifecyclePolicy.shouldApplyDelayedWrite(4, 5, true, true),
                "old enter completion cannot overwrite a newer command");
        check(false, ZoomLifecyclePolicy.shouldApplyDelayedWrite(5, 5, false, true),
                "exit completion cannot overwrite a resumed host");
        System.out.println("ZoomLifecyclePolicy: " + checks + " checks passed");
    }
}
