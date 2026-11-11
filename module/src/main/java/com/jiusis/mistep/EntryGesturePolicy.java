package com.jiusis.mistep;

final class EntryGesturePolicy {
    private EntryGesturePolicy() {}

    static boolean canSelect(int mode, boolean gestureLineHidden) {
        return mode != WorkspaceOptions.BOTTOM_CENTER_OUTWARD || gestureLineHidden;
    }

    static int effectiveMode(int selectedMode, boolean gestureLineHidden) {
        return canSelect(selectedMode, gestureLineHidden)
                ? selectedMode : WorkspaceOptions.STATUS_BAR_INWARD;
    }
}
