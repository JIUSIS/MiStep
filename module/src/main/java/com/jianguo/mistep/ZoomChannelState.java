package com.jianguo.mistep;

/** One leased request shared by file, broadcast and Settings transports. */
final class ZoomChannelState {
    static final class Request {
        final String command;
        final long modified;
        final String animationKey;

        Request(String command, long modified, long generation) {
            this.command = command;
            this.modified = modified;
            this.animationKey = command + "@" + generation;
        }
    }

    private final long started;
    private Request latest;
    private long generation;

    ZoomChannelState(long started) {
        this.started = started;
    }

    static String encode(String command, long modified) {
        return modified + "|" + command;
    }

    synchronized boolean offerEncoded(String encoded, long now) {
        if (encoded == null) return false;
        int separator = encoded.indexOf('|');
        if (separator < 1) return false; // A persistent value without a lease is unsafe to replay.
        try {
            return offer(encoded.substring(separator + 1),
                    Long.parseLong(encoded.substring(0, separator)), now);
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    synchronized boolean offer(String command, long modified, long now) {
        if (command == null || modified < started || modified > now
                || now - modified > ZoomCommand.ACTIVE_TIMEOUT_MS) return false;
        String value = command.trim();
        if (!valid(value, modified, now)) return false;
        if (latest != null && modified <= latest.modified) return false;
        if (latest == null || !latest.command.equals(value)
                || now - latest.modified > ZoomCommand.ACTIVE_TIMEOUT_MS) generation++;
        latest = new Request(value, modified, generation);
        return true;
    }

    synchronized Request current(long now) {
        if (latest == null || now < latest.modified
                || now - latest.modified > ZoomCommand.ACTIVE_TIMEOUT_MS) {
            return new Request("1 0 0", now, generation);
        }
        return latest;
    }

    private boolean valid(String value, long modified, long now) {
        if (value.startsWith("anim")) {
            return ZoomAnimCommand.parse(value, modified, started, now) != null;
        }
        String[] parts = value.split("\\s+");
        if (parts.length < 1 || parts.length > 3) return false;
        try {
            float scale = Float.parseFloat(parts[0]);
            float y = parts.length > 1 ? Float.parseFloat(parts[1]) : 0f;
            float x = parts.length > 2 ? Float.parseFloat(parts[2]) : 0f;
            return Float.isFinite(scale) && scale > 0f && scale <= 1f
                    && Float.isFinite(y) && y >= 0f && y <= 10000f
                    && Float.isFinite(x) && x >= 0f && x <= 10000f;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }
}
