package com.jiusis.mistep;

final class TopTaskReturnPolicy {
    private static final float CENTER_START_SCALE = .14f;

    static final class RevealFrame {
        static final RevealFrame FULL = new RevealFrame(0f, 0f, 1f, 1f);
        final float x, y, scaleX, scaleY;

        RevealFrame(float x, float y, float scaleX, float scaleY) {
            this.x = x;
            this.y = y;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
        }

        RevealFrame at(float progress) {
            if (progress <= 0f) return this;
            if (progress >= 1f) return FULL;
            float remaining = 1f - progress;
            return new RevealFrame(x * remaining, y * remaining,
                    scaleX + (1f - scaleX) * progress, scaleY + (1f - scaleY) * progress);
        }
    }

    static boolean keepUpperTask(boolean mainOccupiedOrUnknown) {
        return mainOccupiedOrUnknown;
    }

    static boolean liveCenterReveal(boolean mainOccupiedOrUnknown,
            boolean originatedFromMain, boolean originalSlotEmpty) {
        return !mainOccupiedOrUnknown && originatedFromMain && originalSlotEmpty;
    }

    static RevealFrame centeredFrame(int width, int height) {
        float inset = (1f - CENTER_START_SCALE) / 2f;
        return new RevealFrame(width * inset, height * inset, CENTER_START_SCALE, CENTER_START_SCALE);
    }

    static RevealFrame tabletPushFrame(float left, float top, int width, int height,
            int mainX, int mainY, float mainScale, int displayWidth, int displayHeight) {
        // The display area already applies the main area's origin and scale after handoff.
        return new RevealFrame((left - mainX) / mainScale, (top - mainY) / mainScale,
                width / (mainScale * displayWidth), height / (mainScale * displayHeight));
    }

    private TopTaskReturnPolicy() {}
}
