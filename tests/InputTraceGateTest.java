package com.jianguo.mistep;

public final class InputTraceGateTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        InputTraceGate gate = new InputTraceGate();
        check(!gate.take(100), "default cannot observe input");
        gate.arm(100, false);
        check(!gate.active(101), "locked cannot arm");
        long first = gate.arm(1000, true);
        check(!gate.take(999), "backward time cannot extend capture");
        check(gate.take(1000), "first frame admitted");
        check(gate.take(6999), "last frame before deadline admitted");
        check(!gate.take(7000), "deadline ends capture without input");
        long second = gate.arm(7100, true);
        check(!gate.stop(first), "stale timeout cannot close newer capture");
        check(gate.take(7101), "new capture remains active");
        check(gate.stop(second) && !gate.take(7102), "screen lock immediately ends capture");
        gate.arm(8000, true);
        int count = 0;
        while (gate.take(8001)) count++;
        check(count == InputTraceGate.MAX_LINES, "event flood bounded independently of time");
        check(!gate.active(8002), "exhausted observation is inactive");
        System.out.println("InputTraceGate passed: " + checks + " checks");
    }
}
