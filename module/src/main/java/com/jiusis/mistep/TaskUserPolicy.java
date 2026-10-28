package com.jiusis.mistep;

/** User boundary for tasks owned by the current user or one of its associated profiles. */
final class TaskUserPolicy {
    private TaskUserPolicy() {}

    static boolean allows(int taskUserId, int currentUserId, Iterable<Integer> profileUserIds) {
        if (taskUserId == currentUserId) return true;
        if (profileUserIds == null) return false;
        for (Integer profileUserId : profileUserIds) {
            if (profileUserId != null && taskUserId == profileUserId) return true;
        }
        return false;
    }

    static boolean isCurrentUser(int taskUserId, int currentUserId) {
        return taskUserId == currentUserId;
    }

    static boolean mayBeMainTask(int taskUserId, int currentUserId,
            Iterable<Integer> profileUserIds, boolean visible, boolean focused,
            boolean sideTask) {
        if (sideTask || !allows(taskUserId, currentUserId, profileUserIds)) return false;
        return visible || (taskUserId != currentUserId && focused);
    }
}
