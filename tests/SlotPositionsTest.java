package com.jianguo.mistep;

import java.util.function.IntSupplier;

/** Regression for the actual compaction helper; no Android stubs or runtime. */
public final class SlotPositionsTest {
    private static int checks;

    private static final class Owner implements SlotPositions.Entry {
        int index;
        final int task;
        final Object controller = new Object();
        final Object backing = new Object();
        final IntSupplier callback = () -> index;

        Owner(int index, int task) { this.index = index; this.task = task; }
        public int position() { return index; }
        public void position(int value) { index = value; }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static void selection(int selected) {
        Owner[] slots = {new Owner(0, 10), new Owner(1, 850),
                new Owner(2, 851), new Owner(3, 852)};
        Owner main = slots[0];
        Owner removed = slots[selected];
        Owner[] retained = new Owner[2];
        int n = 0;
        for (int i = 1; i < 4; i++) if (i != selected) retained[n++] = slots[i];
        Object c0 = retained[0].controller;
        Object b0 = retained[0].backing;
        // Clear the selected owner, then move each surviving owner forward.
        for (int i = selected; i < 3; i++) SlotPositions.swap(slots, i, i + 1);
        check(slots[0] == main, "system main cannot be moved");
        check(slots[3] == removed && removed.index == 3, "empty owner moves to tail");
        check(slots[1] == retained[0] && slots[2] == retained[1], "two surviving owners retained");
        check(slots[1].controller == c0 && slots[1].backing == b0, "controller and surface stay together");
        check(slots[1].callback.getAsInt() == 1, "old listener follows owner to new position");
        check(slots[2].callback.getAsInt() == 2, "second listener follows owner");
        check(removed.callback.getAsInt() == 3, "empty owner callbacks cannot target a survivor");
        check(retained[0].task != removed.task && retained[1].task != removed.task,
                "task identity does not transfer between owners");
        // A later compaction must still follow the same owner, not its creation index.
        SlotPositions.swap(slots, 1, 2);
        check(retained[0].callback.getAsInt() == 2 && retained[1].callback.getAsInt() == 1,
                "repeated moves keep callbacks attached");
    }

    private static void rejects(Owner[] slots, int a, int b) {
        Owner first = slots[1];
        Owner second = slots[2];
        try {
            SlotPositions.swap(slots, a, b);
            throw new AssertionError("invalid swap accepted");
        } catch (IllegalArgumentException expected) {
            check(slots[1] == first && slots[2] == second, "invalid swap changes no owner");
        }
    }

    public static void main(String[] args) {
        selection(1);
        selection(2);
        selection(3);
        Owner[] slots = {new Owner(0, 10), new Owner(1, 850), new Owner(2, 851)};
        rejects(slots, 0, 1);
        rejects(slots, -1, 1);
        rejects(slots, 1, 3);
        slots[2].index = 1;
        rejects(slots, 1, 2);
        System.out.println("SlotPositions regression passed: " + checks + " checks");
    }
}
