package com.jiusis.mistep;

final class FullscreenRotationPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        check(FullscreenRotationPolicy.shouldHoldDisplay(true, 0), "active default display held");
        check(!FullscreenRotationPolicy.shouldHoldDisplay(false, 0), "exit releases display");
        check(!FullscreenRotationPolicy.shouldHoldDisplay(true, 1), "remote/secondary display untouched");
        check(FullscreenRotationPolicy.portraitDisplayRotation() == 0, "workspace portrait");

        // Automatic direction cannot create landscape from a portrait task under lock.
        int[] automatic = {-1, 2, 4, 10, 13};
        for (int request : automatic) {
            check(FullscreenRotationPolicy.usesSensorOrUserRotation(request), "automatic " + request);
            for (int resolved = -1; resolved <= 3; resolved++) {
                check(rotation(request, resolved, true) == 0,
                        "locked request=" + request + " resolved=" + resolved);
            }
        }
        for (int request : new int[]{6, 11}) {
            for (boolean locked : new boolean[]{false, true}) {
                check(rotation(request, 1, locked) == 1, "landscape request stays landscape");
                check(rotation(request, 3, locked) == 3, "landscape request follows WM seascape");
                check(rotation(request, 0, locked) == 1, "portrait resolver cannot violate landscape request");
            }
        }
        for (int resolved = -1; resolved <= 3; resolved++) {
            check(rotation(0, resolved, true) == 1, "explicit landscape supported while locked");
            check(rotation(8, resolved, true) == 3, "explicit reverse landscape supported");
            for (int request : new int[]{1, 7, 9, 12}) {
                check(rotation(request, resolved, false) == 0, "portrait request wins");
            }
        }
        for (int request : new int[]{-1, 2, 4, 10, 13}) {
            check(rotation(request, 1, false) == 1, "WM landscape accepted when unlocked");
            check(rotation(request, 3, false) == 3, "WM seascape accepted when unlocked");
            check(rotation(request, 0, false) == 0, "WM portrait clears old transform");
            check(rotation(request, -1, false) == 0, "unknown policy cannot create a rotation");
        }
        for (int request : new int[]{-2, 3, 5, 99}) {
            check(rotation(request, 1, false) == 0, "no stale configuration fallback");
        }
        check(rotation(14, 3, true) == 3, "application LOCKED retains its existing direction");
        check(rotation(14, 0, true) == 0, "application LOCKED initially portrait");
        check(rotation(11, 3, false) == 3, "user landscape before lock");
        check(rotation(11, 3, true) == 3, "locked display does not override application landscape");
        check(rotation(11, 0, false) == 1, "landscape request remains within landscape rotations");
        check(rotation(11, 0, true, 5, 1) == 1,
                "native freeform preserves its recorded fullscreen landscape under lock");
        check(rotation(11, 0, true, 5, 3) == 3,
                "native freeform preserves its recorded fullscreen seascape under lock");
        check(rotation(2, 0, true, 5, 0) == 0,
                "automatic task without recorded landscape stays portrait under lock");
        for (int mode : new int[]{1, 5, 6}) {
            check(rotation(-1, 0, true, mode, 3) == 3,
                    "recorded fullscreen direction survives the windowing mode change " + mode);
            for (int request : new int[]{1, 7, 9, 12}) {
                check(rotation(request, 3, true, mode, 3) == 0,
                        "new explicit portrait request wins over history " + mode);
            }
        }
        check(FullscreenRotationPolicy.observedTaskRotation(2, 11, 0) == 1,
                "pre-step landscape configuration is captured before display hold");
        check(FullscreenRotationPolicy.observedTaskRotation(2, 8, 0) == 3,
                "pre-step reverse landscape configuration is preserved");

        for (int[] size : new int[][]{{1200, 2608}, {1080, 2460}, {1440, 3200}}) {
            for (boolean right : new boolean[]{false, true}) {
                MiStepLayout portrait = workspace(size[0], size[1], right);
                MiStepLayout pendingLandscape = workspace(size[1], size[0], right);
                check(portrait.describe().equals(pendingLandscape.describe()),
                        "pending physical rotation cannot replace portrait workspace");
                check(pendingLandscape.fillsScreen(), "four regions still fill portrait workspace");
                for (int i = 0; i < MiStepLayout.CELL_COUNT; i++) {
                    int[] cell = pendingLandscape.cellBounds(i);
                    check(cell[2] > cell[0] && cell[3] > cell[1], "side slot retained " + i);
                    check(right ? cell[2] <= pendingLandscape.mainX
                                    : cell[0] >= pendingLandscape.mainWidth,
                            "side slot remains outside main " + i);
                }
            }
        }
        System.out.println("FullscreenRotationPolicy checks=" + checks + " passed");
    }

    private static int rotation(int request, int resolved, boolean locked) {
        return FullscreenRotationPolicy.taskRotation(request, resolved, locked);
    }

    private static int rotation(int request, int resolved, boolean locked,
            int windowingMode, int rememberedRotation) {
        return FullscreenRotationPolicy.taskRotation(request, resolved, locked,
                windowingMode, rememberedRotation);
    }

    private static MiStepLayout workspace(int width, int height, boolean right) {
        return MiStepLayout.of(FullscreenRotationPolicy.workspaceWidth(width, height),
                FullscreenRotationPolicy.workspaceHeight(width, height), right);
    }

    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
