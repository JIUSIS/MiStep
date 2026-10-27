package com.jianguo.mistep;

import android.view.View;

public final class SystemBarVisibilityPolicyTest {
    public static void main(String[] args) {
        int immersive = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        int side = SystemBarVisibilityPolicy.forSideTask(immersive);
        if ((side & (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)) != 0)
            throw new AssertionError("side task must not hide display-global bars");
        if ((side & View.SYSTEM_UI_FLAG_LAYOUT_STABLE) == 0)
            throw new AssertionError("non-hiding layout flags must be preserved");
        int ordinary = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        if (SystemBarVisibilityPolicy.forSideTask(ordinary) != ordinary)
            throw new AssertionError("layout-only flags must remain unchanged");
        System.out.println("SystemBarVisibilityPolicyTest PASS 3");
    }
}
