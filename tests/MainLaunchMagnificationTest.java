package com.jianguo.mistep;

public class MainLaunchMagnificationTest {
    private static int checks;

    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        Object display = new Object();
        check(MainLaunchMagnification.shouldApply(true, display, display, true, true, 1, 1),
                "visible fullscreen task opening on the main display");
        check(MainLaunchMagnification.shouldApply(true, display, display, true, true, 1, 3),
                "visible fullscreen task returning to front");
        check(!MainLaunchMagnification.shouldApply(false, display, display, true, true, 1, 1),
                "workspace zoom is inactive");
        check(!MainLaunchMagnification.shouldApply(true, null, display, true, true, 1, 1),
                "default display is missing");
        check(!MainLaunchMagnification.shouldApply(true, display, new Object(), true, true, 1, 1),
                "foreign display rejected");
        check(!MainLaunchMagnification.shouldApply(true, display, display, false, true, 1, 1),
                "nested task rejected");
        check(!MainLaunchMagnification.shouldApply(true, display, display, true, false, 1, 1),
                "hidden task rejected");
        check(!MainLaunchMagnification.shouldApply(true, display, display, true, true, 5, 1),
                "native freeform rejected");
        check(!MainLaunchMagnification.shouldApply(true, display, display, true, true, 6, 1),
                "side task rejected");
        check(!MainLaunchMagnification.shouldApply(true, display, display, true, true, 1, 2),
                "closing transition rejected");
        check(!MainLaunchMagnification.shouldApply(true, display, display, true, true, 1, 4),
                "background transition rejected");
        check(!MainLaunchMagnification.shouldApply(true, display, display, true, true, 1, 6),
                "ordinary bounds change rejected");
        System.out.println("Main launch transition gate checks=" + checks + " passed");
    }
}
