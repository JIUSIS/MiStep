package com.jianguo.mistep;

/** Reuse the host's renewable lease; never change an IME preference. */
final class MiStepImePolicy {
    private MiStepImePolicy() {}

    static boolean suppressAutomaticFloating(String encoded, long bootAt, long now) {
        ZoomChannelState lease = new ZoomChannelState(bootAt);
        if (!lease.offerEncoded(encoded, now)) return false;
        String[] parts = lease.current(now).command.split("\\s+");
        int scaleIndex = "anim".equals(parts[0]) ? 1 : 0;
        return Float.parseFloat(parts[scaleIndex]) < 1f;
    }
}
