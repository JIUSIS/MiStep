package com.jiusis.mistep;

public final class ZoomCommandTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static boolean identity(float[] r) {
        return Float.compare(r[0], 1f) == 0 && Float.compare(r[1], 0f) == 0 && Float.compare(r[2], 0f) == 0;
    }
    public static void main(String[] args) {
        long now = 1_000_000L;
        long started = now - 60_000L;
        float[] fresh = ZoomCommand.read("0.71472394 744 22", now - 100, started, now);
        check(!identity(fresh), "fresh command accepted");
        check(Math.abs(fresh[0] - 0.71472394f) < 1e-6, "scale parsed");
        check(fresh[1] == 744f && fresh[2] == 22f, "offsets parsed");
        check(identity(ZoomCommand.read("1 0 0", now - 100, started, now)), "identity command");
        check(identity(ZoomCommand.read("0.7147 744 22", started - 1000, started, now)), "pre-boot stale rejected");
        check(identity(ZoomCommand.read("0.7147 744 22", now - 20_000, started, now)), "expired request rejected");
        check(identity(ZoomCommand.read("0 744 22", now - 100, started, now)), "zero scale rejected");
        check(identity(ZoomCommand.read("1.5 744 22", now - 100, started, now)), "scale > 1 rejected");
        check(identity(ZoomCommand.read("0.7 -1 22", now - 100, started, now)), "negative offsetY rejected");
        check(identity(ZoomCommand.read("0.7 744 20000", now - 100, started, now)), "huge offsetX rejected");
        check(identity(ZoomCommand.read("garbage", now - 100, started, now)), "malformed rejected");
        check(identity(ZoomCommand.read("", now - 100, started, now)), "empty rejected");
        check(identity(ZoomCommand.read(null, now - 100, started, now)), "null rejected");
        System.out.println("ZoomCommand: " + checks + " checks passed");
    }
}
