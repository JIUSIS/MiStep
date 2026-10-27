package com.jianguo.mistep;

import java.util.List;

public final class ClipboardHistoryTest {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        ClipboardHistory history = new ClipboardHistory();
        history.add("first", false);
        history.add("second", false);
        history.add("first", false);
        check(history.snapshot().equals(List.of("first", "second")), "newest first and dedupe");
        history.add("secret", true);
        history.add(" \n ", false);
        history.add(null, false);
        history.add("x".repeat(ClipboardHistory.MAX_TEXT + 1), false);
        check(history.snapshot().size() == 2, "reject sensitive, blank, null and oversized text");
        List<String> snapshot = history.snapshot();
        snapshot.clear();
        check(history.snapshot().size() == 2, "snapshot is detached");
        history.remove("first");
        check(history.snapshot().equals(List.of("second")), "remove one item");
        history.clear();
        for (int i = 0; i < ClipboardHistory.MAX_ENTRIES + 3; i++)
            history.add("entry-" + i, false);
        check(history.snapshot().size() == ClipboardHistory.MAX_ENTRIES, "entry count is bounded");
        history.clear();
        for (int i = 0; i < ClipboardHistory.MAX_TOTAL / 4096 + 2; i++)
            history.add("z".repeat(4096) + i, false);
        int total = history.snapshot().stream().mapToInt(String::length).sum();
        check(total <= ClipboardHistory.MAX_TOTAL, "total text size is bounded");
        System.out.println("ClipboardHistory: " + checks + " checks passed");
    }
}
