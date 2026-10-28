package com.jiusis.mistep;

/** Usage access may be absent; in that case preserve a deterministic launcher order. */
final class QuickAppUsageOrder {
    private QuickAppUsageOrder() {}

    static int compare(long foregroundA, long lastA, String labelA, String componentA,
            long foregroundB, long lastB, String labelB, String componentB) {
        long durationA = Math.max(0L, foregroundA), durationB = Math.max(0L, foregroundB);
        int order = Long.compare(durationB, durationA);
        if (order != 0) return order;
        if (durationA > 0L) {
            order = Long.compare(lastB, lastA);
            if (order != 0) return order;
        }
        order = labelA.compareToIgnoreCase(labelB);
        return order != 0 ? order : componentA.compareTo(componentB);
    }
}
