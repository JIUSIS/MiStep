package com.jiusis.mistep;

import java.util.function.Consumer;

/** Exact-generation acknowledgements; command return and Host frame commits are not zoom commits. */
final class LiveSwapZoom {
    private LiveSwapZoom() {}

    static final class Zoom {
        final long generation;
        final float scale, x, y;
        Zoom(long generation, float scale, float x, float y) {
            if (generation <= 0 || !Float.isFinite(scale) || scale <= 0
                    || !Float.isFinite(x) || !Float.isFinite(y))
                throw new IllegalArgumentException("invalid zoom generation/tuple");
            this.generation = generation; this.scale = scale; this.x = x; this.y = y;
        }
        Zoom withNewGeneration(long next) { return new Zoom(next, scale, x, y); }
        boolean sameTuple(float otherScale, float otherX, float otherY) {
            return Float.floatToIntBits(scale) == Float.floatToIntBits(otherScale)
                    && Float.floatToIntBits(x) == Float.floatToIntBits(otherX)
                    && Float.floatToIntBits(y) == Float.floatToIntBits(otherY);
        }
    }

    static final class Ack {
        final long generation, transactionId;
        final float actualScale, actualX, actualY;
        final boolean committed;
        final String error;
        final Object finishReplay;
        Ack(long generation, long transactionId, float actualScale, float actualX, float actualY,
                boolean committed, String error) {
            this(generation, transactionId, actualScale, actualX, actualY, committed, error, null);
        }
        Ack(long generation, long transactionId, float actualScale, float actualX, float actualY,
                boolean committed, String error, Object finishReplay) {
            this.generation = generation; this.transactionId = transactionId;
            this.actualScale = actualScale; this.actualX = actualX; this.actualY = actualY;
            this.committed = committed; this.error = error;
            this.finishReplay = finishReplay;
        }
        boolean confirms(Zoom expected) {
            return expected != null && generation == expected.generation && transactionId != 0
                    && committed && error == null
                    && expected.sameTuple(actualScale, actualX, actualY);
        }
        boolean confirmsFinish(Zoom expected) { return confirms(expected) && replayOpen(finishReplay); }
    }

    /** Transaction.merge reaches native code without checking a zero source pointer on this ROM. */
    static boolean replayOpen(Object replay) {
        if (replay == null) return false;
        try {
            java.lang.reflect.Field pointer = replay.getClass().getDeclaredField("mNativeObject");
            pointer.setAccessible(true);
            return pointer.getLong(replay) != 0L;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
    }

    static void closeReplay(Object replay) {
        if (replay instanceof AutoCloseable) {
            try { ((AutoCloseable) replay).close(); } catch (Exception ignored) {}
        }
    }

    interface Bridge { void apply(Zoom target, Consumer<Ack> done); }

    static final class FrameAck {
        final long generation;
        final boolean committed;
        final String error;
        FrameAck(long generation, boolean committed, String error) {
            this.generation = generation; this.committed = committed; this.error = error;
        }
        boolean confirms(long expected) { return generation == expected && committed && error == null; }
    }

    /** Apply target or original Host layout; completion is that Host's own hardware frame commit. */
    interface Layout { void apply(Zoom target, boolean restoring, Consumer<FrameAck> done); }
}
