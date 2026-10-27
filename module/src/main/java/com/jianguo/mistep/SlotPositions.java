package com.jianguo.mistep;

/** Move the owner object, including its listeners and surface state, between side positions. */
final class SlotPositions {
    interface Entry {
        int position();
        void position(int value);
    }

    static <T extends Entry> void swap(T[] slots, int a, int b) {
        if (a <= 0 || b <= 0 || a >= slots.length || b >= slots.length
                || slots[a] == null || slots[b] == null
                || slots[a].position() != a || slots[b].position() != b) {
            throw new IllegalArgumentException("invalid or stale side position");
        }
        T owner = slots[a];
        slots[a] = slots[b];
        slots[b] = owner;
        slots[a].position(a);
        slots[b].position(b);
    }
}
