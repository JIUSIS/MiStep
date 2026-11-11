package com.jiusis.mistep;

/** Bounds snapshots and previews while retaining the newest complete Unicode characters. */
final class RuntimeLogText {
    static final int CAPACITY = 512 * 1024, PREVIEW = 42 * 1024;
    private static final String OMITTED = "[较早内容已省略，仅保留近期日志]\n";
    private RuntimeLogText() {}

    static String tail(String text, int limit) {
        if (limit < 2) throw new IllegalArgumentException("Log limit is too small");
        if (text.length() <= limit) return text;
        int start = text.length() - limit;
        if (Character.isLowSurrogate(text.charAt(start))) start++;
        return OMITTED + text.substring(start);
    }

    static final class Buffer {
        private final StringBuilder text = new StringBuilder();
        private boolean omitted;
        void append(char[] chunk, int count) {
            text.append(chunk, 0, count);
            if (text.length() > CAPACITY) {
                int start = text.length() - CAPACITY;
                if (Character.isLowSurrogate(text.charAt(start))) start++;
                text.delete(0, start);
                omitted = true;
            }
        }
        @Override public String toString() { return (omitted ? OMITTED : "") + text; }
        boolean truncated() { return omitted; }
    }

    static boolean hasEntries(String text) {
        return text.contains("MiStepProbe:") || text.contains("MiStepRuntime:")
                || text.contains("MiStepHomeTrace:") || text.contains("MiStepReturnTrace:") || text.contains("MiStepRaw:")
                || text.contains("[com.jiusis.mistep,") || text.contains("[Mi Step]")
                || text.contains("[MiStep]");
    }
}
