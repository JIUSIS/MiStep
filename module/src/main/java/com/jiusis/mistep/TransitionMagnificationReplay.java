package com.jiusis.mistep;

final class TransitionMagnificationReplay {
    interface Applier {
        void reapply(Object display, Object transaction) throws Throwable;
    }

    private TransitionMagnificationReplay() {}

    static boolean reapplyIfActive(boolean zoomed, Object defaultDisplay,
            boolean globalLockHeld, Object[] participantDisplays, Object finishTransaction,
            Applier applier) throws Throwable {
        if (!zoomed || defaultDisplay == null || !globalLockHeld
                || participantDisplays == null || finishTransaction == null || applier == null) {
            return false;
        }
        for (Object display : participantDisplays) {
            if (display == defaultDisplay) {
                applier.reapply(defaultDisplay, finishTransaction);
                return true;
            }
        }
        return false;
    }
}
