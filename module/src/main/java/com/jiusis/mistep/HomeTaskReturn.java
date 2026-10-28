package com.jiusis.mistep;

/** Exact identities for an app covering an existing Home. No launcher intent is stored. */
final class HomeTaskReturn {
    final int appId;
    final Object appToken;
    final int homeId;
    final Object homeToken;

    HomeTaskReturn(int appId, Object appToken, int homeId, Object homeToken) {
        if (appId < 0 || homeId < 0 || appToken == null || homeToken == null
                || appId == homeId || appToken.equals(homeToken))
            throw new IllegalArgumentException("distinct app and Home identities required");
        this.appId = appId;
        this.appToken = appToken;
        this.homeId = homeId;
        this.homeToken = homeToken;
    }

    boolean covers(int taskId, Object token) {
        return appId == taskId && appToken.equals(token);
    }

    boolean restores(int taskId, Object token) {
        return homeId == taskId && homeToken.equals(token);
    }
}
