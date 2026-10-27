package com.jianguo.mistep;

public final class LiveSwapFinishReplayPolicyTest {
    static class Replay { long mNativeObject = 123; }
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        LiveSwapZoom.Zoom target = new LiveSwapZoom.Zoom(2, .747f, 273, 622);
        Replay replay = new Replay();
        check(!new LiveSwapZoom.Ack(2, 99, .747f, 273, 622, true, null).confirmsFinish(target),
                "zoom commit alone admitted without current finish operations");
        check(new LiveSwapZoom.Ack(2, 99, .747f, 273, 622, true, null, replay).confirmsFinish(target),
                "exact commit and finish snapshot rejected");
        check(!new LiveSwapZoom.Ack(2, 99, .747f, 0, 622, true, null, replay).confirmsFinish(target),
                "old left-main tuple admitted with right-main replay");
        check(!new LiveSwapZoom.Ack(1, 99, .747f, 273, 622, true, null, replay).confirmsFinish(target),
                "previous generation admitted");
        check(!new LiveSwapZoom.Ack(2, 99, .747f, 273, 622, false, null, replay).confirmsFinish(target),
                "uncommitted replay admitted");
        check(!new LiveSwapZoom.Ack(2, 99, .747f, 273, 622, true, "failed", replay).confirmsFinish(target),
                "failed replay admitted");
        replay.mNativeObject = 0;
        check(!new LiveSwapZoom.Ack(2, 99, .747f, 273, 622, true, null, replay).confirmsFinish(target),
                "closed native source admitted to merge");
        check(!LiveSwapZoom.replayOpen(new Object()), "unknown transaction representation admitted");
        final boolean[] closed = {false};
        LiveSwapZoom.closeReplay((AutoCloseable) () -> closed[0] = true);
        check(closed[0], "abandoned replay leaked");
        System.out.println("LiveSwapFinishReplayPolicy checks=" + checks + " passed");
    }
}
