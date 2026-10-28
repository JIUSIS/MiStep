package com.jianguo.mistep;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Exact transaction identities only. Ownership follows native merge and is consumed once. */
final class FinishTransactions<T> {
    private final int limit;
    private final IdentityHashMap<Object,List<T>> pending = new IdentityHashMap<>();
    FinishTransactions(int limit) { this.limit = limit; }
    synchronized boolean mark(Object transaction, List<T> frames) {
        if (transaction == null || frames.isEmpty()) return false;
        if (!pending.containsKey(transaction) && pending.size() >= limit) return false;
        pending.put(transaction, new ArrayList<>(frames));
        return true;
    }
    synchronized void merge(Object target, Object source) {
        if (target == null || target == source) return;
        List<T> moved = pending.remove(source);
        if (moved == null) return;
        List<T> existing = pending.get(target);
        if (existing == null) pending.put(target, moved);
        else existing.addAll(moved);
    }
    synchronized List<T> take(Object transaction) {
        List<T> frames = pending.remove(transaction);
        return frames == null ? java.util.Collections.emptyList() : frames;
    }
    synchronized void forget(Object transaction) { pending.remove(transaction); }
    synchronized int size() { return pending.size(); }
}

