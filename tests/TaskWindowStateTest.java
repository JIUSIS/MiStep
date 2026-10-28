package com.jiusis.mistep;

/** Regression checks for independent per-task state and slot ownership. */
public final class TaskWindowStateTest {
    private static int checks;

    public static void main(String[] args) {
        TaskWindowState main = new TaskWindowState(101);
        TaskWindowState side = new TaskWindowState(202);
        main.update(0, 1, 1, 0, 1, true, true);
        side.update(1, 2, 0, 1, 6, false, true);

        check(main.active && main.fullscreen && !main.isSideSlot(),
                "main task keeps fullscreen ownership");
        check(main.isPortraitConfiguration() && !main.isLandscapeConfiguration(),
                "main task keeps portrait configuration");
        check(side.active && side.isSideSlot() && !side.fullscreen,
                "side task keeps independent windowing state");
        check(side.isLandscapeConfiguration() && side.resolvedRotation == 1,
                "side task keeps landscape rotation");
        check(main.generation == 1 && side.generation == 1,
                "initial state update has one generation");

        TaskWindowState freeform = new TaskWindowState(303);
        freeform.update(0, 2, 11, 3, 1, true, true, 3);
        freeform.update(-1, 1, 11, 0, 5, false, true, 0);
        check(!freeform.fullscreen && freeform.windowingMode == 5,
                "native freeform mode remains distinct from fullscreen");
        check(freeform.preferredRememberedRotation() == 3,
                "freeform keeps the task's prior fullscreen seascape");
        freeform.update(0, 1, 1, 0, 1, true, true, 0);
        check(freeform.preferredRememberedRotation() == -1,
                "an explicit portrait request clears remembered fullscreen landscape");
        freeform.update(0, 1, -1, 0, 1, true, true, 0);
        check(freeform.requestedOrientation == -1 && freeform.lastFullscreenRequestedOrientation == -1,
                "UNSPECIFIED is a real request and replaces the previous orientation");
        TaskWindowState adopted = new TaskWindowState(404);
        adopted.update(2, 1, -1, 0, 6, false, true, 3);
        check(adopted.preferredRememberedRotation() == 3,
                "an adopted landscape side task keeps its effective direction without fullscreen history");
        adopted.forgetRememberedRotation();
        check(adopted.preferredRememberedRotation() == -1,
                "a new application direction request invalidates old side/fullscreen direction");
        adopted.update(2, 2, -1, 0, 6, false, true, 0);
        check(adopted.preferredRememberedRotation() == -1,
                "stale landscape configuration cannot resurrect a cleared direction");

        side.update(0, 2, 8, 3, 1, true, true);
        check(side.slotIndex == 0 && side.fullscreen && side.resolvedRotation == 3,
                "same task state follows a main/side swap");
        check(main.slotIndex == 0 && main.fullscreen,
                "other task state is not overwritten by swap");
        side.clear();
        check(!side.active && side.slotIndex == -1, "clearing one task leaves it inactive");
        check(main.active && main.slotIndex == 0, "clearing side does not clear main");
        System.out.println("TaskWindowState checks=" + checks + " passed");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
