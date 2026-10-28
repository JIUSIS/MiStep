package com.jiusis.mistep;

import java.util.Arrays;
import java.util.Collections;

public final class TaskUserPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(TaskUserPolicy.allows(0, 0, Arrays.asList(0, 999)), "owner task allowed");
        check(TaskUserPolicy.allows(999, 0, Arrays.asList(0, 999)), "associated clone allowed");
        check(!TaskUserPolicy.allows(10, 0, Arrays.asList(0, 999)), "unrelated user rejected");
        check(TaskUserPolicy.allows(10, 10, Arrays.asList(10, 999)), "non-owner current user allowed");
        check(TaskUserPolicy.allows(999, 10, Arrays.asList(10, 999)), "clone of non-owner user allowed");
        check(!TaskUserPolicy.allows(999, 0, null), "unknown profiles fail closed");
        check(TaskUserPolicy.isCurrentUser(0, 0), "quick launch owner matches current user");
        check(!TaskUserPolicy.isCurrentUser(999, 0), "quick launch does not select clone implicitly");
        check(!TaskUserPolicy.allows(10, 0, Collections.emptyList()), "empty profile list rejects other users");
        check(TaskUserPolicy.mayBeMainTask(0, 0, Arrays.asList(0, 999), true,
                false, false), "visible owner task can be main");
        check(TaskUserPolicy.mayBeMainTask(999, 0, Arrays.asList(0, 999), true,
                false, false), "visible clone task can be main");
        check(TaskUserPolicy.mayBeMainTask(999, 0, Arrays.asList(0, 999), false,
                true, false), "focused clone survives stale visibility flag");
        check(!TaskUserPolicy.mayBeMainTask(999, 0, Arrays.asList(0, 999), false,
                false, false), "hidden unfocused clone is rejected");
        check(!TaskUserPolicy.mayBeMainTask(10, 0, Arrays.asList(0, 999), true,
                true, false), "unrelated visible user is rejected");
        check(!TaskUserPolicy.mayBeMainTask(0, 0, Arrays.asList(0, 999), false,
                true, false), "hidden owner task is rejected");
        check(!TaskUserPolicy.mayBeMainTask(999, 0, Arrays.asList(0, 999), true,
                true, true), "side task is never a main task");
        System.out.println("TaskUserPolicy: " + checks + " checks passed");
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
}
