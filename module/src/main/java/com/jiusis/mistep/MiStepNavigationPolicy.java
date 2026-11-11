package com.jiusis.mistep;

final class MiStepNavigationPolicy {
    private MiStepNavigationPolicy() {}

    static boolean shouldSuspend(String reason) {
        // Recents is also the native freeform entry. Keep the existing workspace
        // while choosing a floating task, as for gesture navigation.
        return "homekey".equals(reason);
    }

    /** Only a gesture which began on the empty main Home may become overview. */
    static final class EmptyMainGesture {
        private long epoch, started;
        private boolean pending;
        private long overviewAt;
        private boolean overviewRequested;

        void begin(long sceneEpoch, long now, boolean emptyHome) {
            epoch = sceneEpoch;
            started = now;
            pending = emptyHome;
            overviewRequested = false;
        }

        boolean consume(long sceneEpoch, long now) {
            boolean matches = pending && epoch == sceneEpoch
                    && now >= started && now - started <= 2000L;
            pending = false;
            return matches;
        }

        void requested(long now) { overviewAt = now; overviewRequested = true; }

        boolean duplicateHome(long sceneEpoch, long now) {
            return overviewRequested && epoch == sceneEpoch
                    && now >= overviewAt && now - overviewAt <= 750L;
        }

        void clear() { pending = false; }
    }
}
