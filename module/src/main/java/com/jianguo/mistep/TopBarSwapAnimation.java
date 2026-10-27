package com.jianguo.mistep;

/** Hides buttons before their layout changes, then fades in only the latest side. */
final class TopBarSwapAnimation {
    interface Target {
        boolean ready();
        void cancel();
        void fade(float alpha, long duration, Runnable finished);
        void move(boolean right, Runnable laidOut);
        void finish();
    }
    private final Target target;
    private boolean right;
    private int generation;

    TopBarSwapAnimation(Target target, boolean right) { this.target = target; this.right = right; }

    void setRight(boolean next) {
        int ticket = ++generation;
        target.cancel();
        if (!target.ready()) { right = next; target.move(next, null); target.finish(); return; }
        if (right == next) { fadeIn(ticket); return; }
        target.fade(0f, 90, () -> {
            if (ticket != generation) return;
            right = next;
            target.move(next, () -> { if (ticket == generation) fadeIn(ticket); });
        });
    }

    private void fadeIn(int ticket) {
        target.fade(1f, 150, () -> { if (ticket == generation) target.finish(); });
    }

    void settle(boolean next) {
        ++generation;
        target.cancel();
        right = next;
        target.move(next, null);
        target.finish();
    }
}
