package com.jiusis.mistep;

/** Focus a handed-off task only after the old main task has finished backfilling. */
final class PromotionFocusTransfer {
    enum Result { FOCUSED, REQUESTED, PENDING, UNAVAILABLE }

    interface Tasks {
        State read(int taskId) throws Exception;
        void focus(int taskId) throws Exception;
    }

    static final class State {
        final Object token;
        final int displayId, userId, windowingMode;
        final boolean visible, focused;

        State(Object token, int displayId, int userId, int windowingMode,
                boolean visible, boolean focused) {
            this.token = token;
            this.displayId = displayId;
            this.userId = userId;
            this.windowingMode = windowingMode;
            this.visible = visible;
            this.focused = focused;
        }

        boolean matches(Object expectedToken, int expectedUser) {
            return expectedToken != null && expectedToken.equals(token)
                    && displayId == 0 && userId == expectedUser
                    && windowingMode == 1 && visible;
        }
    }

    static Result transfer(Tasks tasks, int id, Object token, int user) throws Exception {
        Result before = observe(tasks, id, token, user);
        if (before != Result.PENDING) return before;
        tasks.focus(id);
        return observe(tasks, id, token, user);
    }

    /** Request the confirmed live main once, in the existing Shell finish turn. */
    static Result request(Tasks tasks, int id, Object token, int user) throws Exception {
        State state = tasks.read(id);
        if (state == null || !state.matches(token, user)) return Result.UNAVAILABLE;
        if (state.focused) return Result.FOCUSED;
        tasks.focus(id);
        // Acceptance is distinct from observed focus. Do not keep input blocked
        // for a polling read or timer after the real task finish has committed.
        return Result.REQUESTED;
    }

    static Result observe(Tasks tasks, int id, Object token, int user) throws Exception {
        State state = tasks.read(id);
        if (state == null || !state.matches(token, user)) return Result.UNAVAILABLE;
        return state.focused ? Result.FOCUSED : Result.PENDING;
    }

    private PromotionFocusTransfer() {}
}
