package com.jianguo.mistep;

import java.util.ArrayList;
import java.util.List;

/** Text only, bounded, process-local history. No clipboard content is persisted. */
final class ClipboardHistory {
    static final int MAX_ENTRIES = 24;
    static final int MAX_TEXT = 8192;
    static final int MAX_TOTAL = 65536;
    private final List<String> entries = new ArrayList<>();

    synchronized void add(String text, boolean sensitive) {
        if (sensitive || text == null || text.trim().isEmpty() || text.length() > MAX_TEXT) return;
        entries.remove(text);
        entries.add(0, text);
        int total = 0;
        for (int i = 0; i < entries.size(); i++) {
            total += entries.get(i).length();
            if (i >= MAX_ENTRIES || total > MAX_TOTAL) {
                entries.subList(i, entries.size()).clear();
                break;
            }
        }
    }

    synchronized List<String> snapshot() { return new ArrayList<>(entries); }
    synchronized void remove(String text) { entries.remove(text); }
    synchronized void clear() { entries.clear(); }
}
