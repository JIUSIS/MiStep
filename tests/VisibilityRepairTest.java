package com.jianguo.mistep;
public final class VisibilityRepairTest {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) {
        VisibilityRepair repair = new VisibilityRepair();
        check(!repair.admit(10, null), "no foreground identity cannot authorize repair");
        check(repair.admit(10, new String("task-A")), "first foreground may repair");
        for (int i = 0; i < 100; i++)
            check(!repair.admit(10, new String("task-A")), "same binder identity never loops after failure or success");
        check(repair.admit(10, "task-B"), "actual foreground change admits one repair");
        check(!repair.admit(10, "task-B"), "new foreground is also bounded");
        check(repair.admit(11, "task-B"), "explicit session change can repair again");
        repair.reset();
        check(repair.admit(11, "task-B"), "new task binding resets lease");
        System.out.println("VisibilityRepair: " + checks + " checks passed");
    }
}
