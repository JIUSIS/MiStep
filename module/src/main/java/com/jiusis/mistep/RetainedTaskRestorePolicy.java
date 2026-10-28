package com.jiusis.mistep;

final class RetainedTaskRestorePolicy {
    static boolean preserveAsMain(boolean ordinaryFullscreenRoot, boolean visible, boolean focused) {
        return ordinaryFullscreenRoot && (visible || focused);
    }

    static boolean reclaim(boolean occupied, boolean parked, Object savedToken,
            java.util.Set<Object> foregroundTokens) {
        return occupied && parked && savedToken != null && !foregroundTokens.contains(savedToken);
    }
}
