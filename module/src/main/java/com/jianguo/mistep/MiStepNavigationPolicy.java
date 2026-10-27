package com.jianguo.mistep;

final class MiStepNavigationPolicy {
    private MiStepNavigationPolicy() {}

    static boolean shouldSuspend(String reason) {
        return "homekey".equals(reason) || "recentapps".equals(reason);
    }
}
