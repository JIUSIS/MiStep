package com.jianguo.mistep;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class ZoomChannelStateTest {
    private static int checks;
    private static void check(boolean result, String name) {
        if (!result) throw new AssertionError(name);
        checks++;
    }

    private static void dispatch(ZoomChannelState state, ZoomDispatch dispatcher,
            Object target, long now) {
        ZoomChannelState.Request request = state.current(now);
        float[] parsed = ZoomCommand.read(request.command, request.modified, 1000, now);
        dispatcher.submit(target, parsed[0], parsed[1], parsed[2]);
    }

    public static void main(String[] args) {
        ZoomChannelState state = new ZoomChannelState(1000);
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        List<Float> applied = new ArrayList<>();
        Object target = new Object();
        ZoomDispatch dispatcher = new ZoomDispatch(task -> { queue.add(task); return true; },
                new Object(), (identity, scale, y, x) -> applied.add(scale),
                failure -> { throw new AssertionError(failure); });

        check(state.offerEncoded(ZoomChannelState.encode("0.71472394 744 22", 2000), 2000),
                "broadcast carries original lease");
        dispatch(state, dispatcher, target, 2000);
        queue.removeFirst().run();
        for (int tick = 1; tick <= 16; tick++) {
            long now = 2000 + tick * 250;
            check(!state.offer("", 0, now), "missing or unreadable file is not an exit request");
            dispatch(state, dispatcher, target, now);
        }
        check(queue.isEmpty() && applied.size() == 1 && applied.get(0) < 1f,
                "250ms file fallback cannot undo working broadcast between heartbeats");
        check(!state.offer("1 0 0", 1500, 6000), "older file cannot replace newer bridge state");
        check(!state.offerEncoded("0.7147 744 22", 6000), "legacy Settings has no renewable lease");
        check(!state.offerEncoded("bad|0.7 744 22", 6000), "malformed timestamp ignored");
        check(!state.offerEncoded("900|0.7 744 22", 6000), "pre-boot Settings ignored");
        check(!state.offerEncoded("6001|0.7 744 22", 6000), "future timestamp ignored");
        check(!state.offer("NaN 744 22", 6000, 6000), "invalid transport does not erase active state");
        check(state.offerEncoded(ZoomChannelState.encode("0.71472394 744 22", 6000), 6000),
                "same-state heartbeat renews lease");
        dispatch(state, dispatcher, target, 6000);
        check(queue.isEmpty(), "heartbeat does not repeat native transaction");
        check(state.current(21000).command.startsWith("0.714"), "lease boundary remains active");
        dispatch(state, dispatcher, target, 21001);
        queue.removeFirst().run();
        check(applied.size() == 2 && applied.get(1) == 1f, "host death restores after 15 seconds");
        check(!state.offerEncoded("6000|0.71472394 744 22", 21002),
                "polling persistent Settings cannot renew a dead host");

        ZoomChannelState pending = new ZoomChannelState(1000);
        check(pending.offer("0.7 744 22", 3000, 3000), "request accepted before target exists");
        dispatch(pending, dispatcher, new Object(), 3100);
        queue.removeFirst().run();
        check(applied.get(2) == .7f, "new target replays cached request");
        check(pending.offer("1 0 0", 3200, 3200), "explicit exit accepted");
        check(!pending.offer("0.7 744 22", 3100, 3300), "delayed active cannot undo newer exit");
        dispatch(pending, dispatcher, target, 3300);
        queue.removeFirst().run();
        check(applied.get(3) == 1f, "exit wins across transports");

        ZoomChannelState fileOnly = new ZoomChannelState(1000);
        check(fileOnly.offer("0.7472 622 0", 4000, 4000), "legacy raw file remains supported");
        check(fileOnly.current(4100).command.equals("0.7472 622 0"), "file-only host still works");
        check(fileOnly.offerEncoded("5000|anim 1 0 0 300", 5000), "bridge preserves animation");
        String animationKey = fileOnly.current(5000).animationKey;
        check(fileOnly.offer("anim 1 0 0 300", 5001, 5001), "file observes same animation later");
        check(fileOnly.current(5001).animationKey.equals(animationKey),
                "duplicate channels do not restart animation");
        check(!fileOnly.offer("anim 0 0 0 300", 5100, 5100), "invalid animation ignored");
        ZoomChannelState restarted = new ZoomChannelState(6000);
        check(!restarted.offerEncoded("5000|0.7 744 22", 6100), "new server rejects old boot snapshot");
        check(restarted.current(6100).command.equals("1 0 0"), "new server starts restored");
        System.out.println("ZoomChannelState: " + checks + " checks passed");
    }
}
