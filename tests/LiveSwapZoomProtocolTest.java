package com.jianguo.mistep;

public final class LiveSwapZoomProtocolTest {
    public static void main(String[] args) {
        long now = System.currentTimeMillis();
        check(LiveSwapZoomProtocol.valid(1L, .75f, 10f, 20f, now, now), "valid lease");
        check(!LiveSwapZoomProtocol.valid(0L, .75f, 10f, 20f, now, now), "generation required");
        check(!LiveSwapZoomProtocol.valid(2L, 1.1f, 10f, 20f, now, now), "scale bounded");
        check(!LiveSwapZoomProtocol.valid(3L, .75f, 10f, 20f,
                now - LiveSwapZoomProtocol.REQUEST_TIMEOUT_MS - 1, now), "expired lease rejected");
        LiveSwapZoom.Zoom z = new LiveSwapZoom.Zoom(4L, .75f, 10f, 20f);
        LiveSwapZoom.Ack ack = new LiveSwapZoom.Ack(4L, 9L, .75f, 10f, 20f, true, null);
        check(ack.confirms(z), "exact ack tuple");
        check(!new LiveSwapZoom.Ack(4L, 9L, .75f, 10f, 20f, false, null).confirms(z),
                "uncommitted ack rejected");
        System.out.println("LiveSwapZoomProtocol: 6 checks passed");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
