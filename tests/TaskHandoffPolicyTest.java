package com.jiusis.mistep;

public final class TaskHandoffPolicyTest {
    public static void main(String[] args) {
        Object token = new Object(), controller = new Object(), nativeOwner = new Object();
        check(TaskHandoffPolicy.owner(token, token, controller, controller, nativeOwner)
                == TaskHandoffPolicy.Owner.OUR_CONTROLLER);
        // WM/Shell's callback won the race; it is still a successful return.
        check(TaskHandoffPolicy.owner(token, null, nativeOwner, controller, nativeOwner)
                == TaskHandoffPolicy.Owner.NATIVE_FULLSCREEN);
        check(TaskHandoffPolicy.owner(token, new Object(), nativeOwner, controller, nativeOwner)
                == TaskHandoffPolicy.Owner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, null, new Object(), controller, nativeOwner)
                == TaskHandoffPolicy.Owner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, token, nativeOwner, controller, nativeOwner)
                == TaskHandoffPolicy.Owner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, null, controller, controller, nativeOwner)
                == TaskHandoffPolicy.Owner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, token, controller, controller, controller)
                == TaskHandoffPolicy.Owner.UNAVAILABLE);
        check(TaskHandoffPolicy.owner(token, token, controller, controller, null)
                == TaskHandoffPolicy.Owner.UNAVAILABLE);
        System.out.println("TaskHandoffPolicy checks=8 passed");
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
