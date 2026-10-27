package com.jianguo.mistep;

import java.util.ArrayList;
import java.util.List;

/** Exercises failure/late-delivery/replacement cases, without pretending to render SystemUI. */
public final class SwapStatusBarLeaseTest {
    private static int checks;
    private static final class Timer implements SwapStatusBarLease.Timer {
        Runnable active;
        long delay;
        public void postDelayed(Runnable action, long value) { active = action; delay = value; }
        public void remove(Runnable action) { if (active == action) active = null; }
    }
    private static final class Window implements SwapStatusBarLease.Window {
        final Object key = new Object();
        float value;
        boolean failHide;
        Window(float initial) { value = initial; }
        public Object identity() { return key; }
        public float transitionAlpha() { return value; }
        public void transitionAlpha(float next) {
            value = next;
            if (failHide && next == 0f) throw new IllegalStateException("setter failed after write");
        }
    }
    private static void check(boolean pass, String reason) {
        checks++;
        if (!pass) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        Timer timer = new Timer();
        List<Throwable> errors = new ArrayList<>();
        SwapStatusBarLease lease = new SwapStatusBarLease(timer, errors::add);
        Object first = new Object(), second = new Object();
        Window original = new Window(.63f), replacement = new Window(.4f);
        int[] cleanup = {0};
        lease.begin(first, () -> cleanup[0]++, () -> {});
        check(timer.delay == 5000L, "bounded hide independent of Shell callbacks");
        lease.include(first, List.of(original));
        check(original.value == 0f, "hide current window drawing");
        lease.include(first, List.of(original, replacement));
        lease.finish(first);
        check(original.value == .63f, "repeat enumeration must retain OEM original value");
        check(replacement.value == .4f, "rebuilt status window retains its own original value");
        check(cleanup[0] == 1 && timer.active == null, "restore removes listener and timeout");
        lease.restore();
        check(cleanup[0] == 1, "restore is idempotent");

        lease.begin(first, () -> {}, () -> {});
        lease.include(first, List.of(original));
        Runnable cancelledTimeout = timer.active;
        lease.begin(second, () -> {}, () -> {});
        lease.include(second, List.of(original));
        lease.finish(first);
        cancelledTimeout.run();
        check(lease.owns(second) && original.value == 0f,
                "late completion and already-dispatched timeout cannot expose next swap");
        lease.include(first, List.of(replacement));
        check(replacement.value == .4f, "late window-listener delivery cannot join next swap");
        lease.finish(second);
        check(original.value == .63f, "next lease saves restored OEM value instead of zero");

        Window partial = new Window(.8f);
        partial.failHide = true;
        lease.begin(first, () -> {}, () -> {});
        lease.include(first, List.of(partial, original));
        check(errors.size() == 1 && original.value == 0f, "partial setter failure does not skip peers");
        lease.restore();
        check(partial.value == .8f && original.value == .63f, "partial writes remain restorable");

        boolean[] removedBridge = {false};
        lease.begin(first, () -> {}, () -> {
            check(original.value == 0f, "expire callback closes bridge before status restoration");
            removedBridge[0] = true;
        });
        lease.include(first, List.of(original));
        timer.active.run();
        check(removedBridge[0] && original.value == .63f && !lease.owns(first),
                "watchdog reveals and restores even with no successful completion");

        lease.begin(first, () -> { throw new IllegalStateException("cleanup"); }, () -> {
            throw new IllegalStateException("bridge close");
        });
        lease.include(first, List.of(original));
        timer.active.run();
        check(original.value == .63f && errors.size() == 3, "expiry and cleanup exceptions cannot strand alpha");

        lease.begin(first, () -> {}, () -> {
            lease.begin(second, () -> {}, () -> {});
            lease.include(second, List.of(original));
        });
        lease.include(first, List.of(original));
        timer.active.run();
        check(lease.owns(second) && original.value == 0f, "expiry cannot restore a replacement lease");
        lease.finish(second);
        check(original.value == .63f, "replacement during expiry still restores original");
        System.out.println("SwapStatusBarLease checks=" + checks + " passed");
    }
}
