package com.jianguo.mistep;

/** A native detach can finish before our bounded observation runs. */
final class TaskHandoffPolicy {
    enum Owner { OUR_CONTROLLER, NATIVE_FULLSCREEN, UNAVAILABLE }

    static Owner owner(Object expectedToken, Object currentToken, Object listener,
            Object controller, Object fullscreenListener) {
        if (expectedToken == null || controller == null || fullscreenListener == null
                || fullscreenListener == controller) return Owner.UNAVAILABLE;
        if (expectedToken.equals(currentToken) && listener == controller)
            return Owner.OUR_CONTROLLER;
        if (currentToken == null && listener == fullscreenListener)
            return Owner.NATIVE_FULLSCREEN;
        return Owner.UNAVAILABLE;
    }
}
