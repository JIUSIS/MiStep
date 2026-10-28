package com.jiusis.mistep;

/** Exact, permission-protected SystemUI/WMS handshake for one live layout handoff. */
final class LiveSwapZoomProtocol {
    static final String APPLY = "com.jiusis.mistep.ZOOM64";
    static final String ACK = "com.jiusis.mistep.ZOOM64_ACK";
    static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    static final String GENERATION = "generation";
    static final String SCALE = "scaleBits", X = "xBits", Y = "yBits";
    static final String ISSUED = "issuedAt";
    /** Unapplied current WMS magnification operations to merge last into Shell finish. */
    static final String FINISH_REPLAY = "finishReplay";
    static final String TRANSACTION = "transactionId", COMMITTED = "committed", ERROR = "error";
    static final long REQUEST_TIMEOUT_MS = 1500L;

    static boolean valid(long generation, float scale, float x, float y, long issued, long now) {
        return generation > 0L && Float.isFinite(scale) && scale > 0f && scale <= 1f
                && Float.isFinite(x) && x >= 0f && x <= 10000f
                && Float.isFinite(y) && y >= 0f && y <= 10000f
                && issued > 0L && issued <= now && now - issued <= REQUEST_TIMEOUT_MS;
    }

    private LiveSwapZoomProtocol() {}
}
