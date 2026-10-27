package com.jianguo.mistep;

import android.view.View;

/** App-requested immersive flags are display-global, so side tasks must not hide system bars. */
final class SystemBarVisibilityPolicy {
    private static final int SIDE_TASK_HIDE_FLAGS =
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;

    private SystemBarVisibilityPolicy() {}

    static int forSideTask(int systemUiVisibility) {
        return systemUiVisibility & ~SIDE_TASK_HIDE_FLAGS;
    }
}
