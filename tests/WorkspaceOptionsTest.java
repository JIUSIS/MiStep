package com.jiusis.mistep;

public final class WorkspaceOptionsTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static LiveSwapGeometry.Rect main(MiStepLayout layout) {
        return new LiveSwapGeometry.Rect(layout.mainOffsetX, layout.mainOffsetY,
                layout.width * layout.mainScale, layout.height * layout.mainScale);
    }
    private static LiveSwapGeometry.Rect side(MiStepLayout layout, int slot) {
        int[] b = layout.cellBounds(slot);
        return new LiveSwapGeometry.Rect(b[0], b[1], b[2] - b[0], b[3] - b[1]);
    }
    public static void main(String[] args) {
        WorkspaceOptions defaults = WorkspaceOptions.DEFAULT;
        for (int id = 0; id <= WorkspaceOptions.SCREEN_RECORD; id++) {
            check(defaults.visible(id), "existing buttons must stay visible by default");
            WorkspaceOptions hidden = defaults.withVisible(id, false);
            for (int other = 0; other <= WorkspaceOptions.SCREEN_RECORD; other++)
                check(hidden.visible(other) == (other != id), "one switch changed another control");
            check(hidden.withVisible(id, true).hiddenButtons == 0, "restore did not persist all-visible state");
            check(!WorkspaceOptions.label(id).isEmpty(), "setting has no label");
        }
        check(new WorkspaceOptions(1 << 28, 99).hiddenButtons == 0, "unknown bit leaked into options");
        check(new WorkspaceOptions(0, -1).swapMode == WorkspaceOptions.GROW_IN_PLACE, "invalid mode changed existing behavior");
        for (boolean right : new boolean[]{false, true}) for (int mode : new int[]{0, 1}) {
            WorkspaceOptions options = defaults.withVisible(WorkspaceOptions.WIFI, false).withSwapMode(mode);
            check(!options.visible(WorkspaceOptions.WIFI), "mode reset display settings");
            check(options.mainOnRightAfterSwap(right) == (mode == WorkspaceOptions.FIXED_MAIN ? right : !right), "wrong target main side");
            for (int width : new int[]{720, 1080, 1200, 1440}) {
                int height = width * 22 / 10;
                MiStepLayout before = MiStepLayout.of(width, height, right, width / 393f);
                MiStepLayout after = MiStepLayout.of(width, height, options.mainOnRightAfterSwap(right), width / 393f);
                for (int slot = 0; slot < MiStepLayout.CELL_COUNT; slot++) {
                    LiveSwapGeometry.Task a = new LiveSwapGeometry.Task(1, "A", width, height, main(before), side(after, slot));
                    LiveSwapGeometry.Task b = new LiveSwapGeometry.Task(2, "B", width, height, side(before, slot), main(after));
                    LiveSwapGeometry.Task peer = new LiveSwapGeometry.Task(3, "C", width, height, side(before, (slot + 1) % 3), side(after, (slot + 1) % 3));
                    for (double fraction : new double[]{0, .25, .5, .75, 1}) {
                        LiveSwapGeometry.Frame[] frames = LiveSwapGeometry.frames(new LiveSwapGeometry.Task[]{a, b, peer},
                                LiveSwapGeometry.Affine.identity(), new LiveSwapGeometry.Affine[]{LiveSwapGeometry.Affine.identity(),
                                        LiveSwapGeometry.Affine.identity(), LiveSwapGeometry.Affine.identity()}, fraction);
                        for (LiveSwapGeometry.Frame frame : frames)
                            check(Double.isFinite(frame.physical.left) && frame.physical.width > 0, "real task path became invalid");
                        if (mode == WorkspaceOptions.FIXED_MAIN)
                            check(Math.abs(frames[2].physical.left - peer.start.left) < .00001, "fixed mode moved uninvolved peer");
                        if (fraction == 1) {
                            check(Math.abs(frames[1].physical.left - after.mainOffsetX) < .00001, "new main missed target");
                            check(Math.abs(frames[0].physical.left - a.end.left) < .00001, "old main missed side slot");
                        }
                    }
                }
            }
        }
        System.out.println("WorkspaceOptions checks=" + checks + " passed (both modes, both sides, real task geometry)");
    }
}
