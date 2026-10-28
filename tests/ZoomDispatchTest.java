package com.jiusis.mistep;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercise the product dispatcher itself with a deterministic WM queue and a real monitor. */
public final class ZoomDispatchTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    private static final class Queue implements ZoomDispatch.Poster {
        final ArrayDeque<Runnable> work = new ArrayDeque<>();
        boolean accepts = true;
        public boolean post(Runnable runnable) {
            if (!accepts) return false;
            work.addLast(runnable);
            return true;
        }
        void runOne() { work.removeFirst().run(); }
    }

    public static void main(String[] args) throws Exception {
        Object lock = new Object();
        Object display = new Object();
        Queue queue = new Queue();
        List<String> calls = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        ZoomDispatch dispatch = new ZoomDispatch(queue, lock, (target, scale, y, x) -> {
            check(Thread.holdsLock(lock), "applier must hold the actual WM lock");
            calls.add(scale + "/" + y);
        }, errors::add);

        dispatch.submit(display, .7472f, 622f);
        check(calls.isEmpty(), "watcher thread never applies directly");
        check(queue.work.size() == 1, "one queue item");
        for (int i = 0; i < 1000; i++) dispatch.submit(display, .7472f, 622f);
        check(queue.work.size() == 1, "pending heartbeats coalesce");
        queue.runOne();
        check(calls.size() == 1, "first active state applied once");
        for (int i = 0; i < 1000; i++) dispatch.submit(display, .7472f, 622f);
        check(queue.work.isEmpty(), "applied heartbeat does not repeat shared native transaction");

        dispatch.submit(display, 1f, 0f);
        queue.runOne();
        check(calls.get(1).equals("1.0/0.0"), "exit restores identity");
        dispatch.submit(new Object(), 1f, 0f);
        queue.runOne();
        check(calls.size() == 3, "new display surface generation reapplies identical state");

        dispatch.submit(display, .7472f, 622f);
        dispatch.submit(display, 1f, 0f);
        check(queue.work.size() == 1, "newer exit replaces queued zoom");
        queue.runOne();
        check(calls.size() == 4 && calls.get(3).equals("1.0/0.0"), "stale queued zoom never applied");
        check(errors.isEmpty(), "no failures on normal path");

        // Hold the real WM lock while the worker starts. Expiry/exit must replace the
        // request even after drain has begun waiting, rather than replay a saved snapshot.
        Queue blockedQueue = new Queue();
        List<String> blockedCalls = new ArrayList<>();
        ZoomDispatch blocked = new ZoomDispatch(blockedQueue, lock,
                (target, scale, y, x) -> blockedCalls.add(Thread.currentThread().getName() + ":" + scale),
                errors::add);
        blocked.submit(display, .7472f, 622f);
        Runnable drain = blockedQueue.work.removeFirst();
        CountDownLatch started = new CountDownLatch(1);
        Thread worker = new Thread(() -> { started.countDown(); drain.run(); }, "wm-test");
        synchronized (lock) {
            worker.start();
            check(started.await(1, TimeUnit.SECONDS), "WM worker started");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (worker.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            check(worker.getState() == Thread.State.BLOCKED, "worker is waiting for the WM monitor");
            check(blockedCalls.isEmpty(), "shared lock blocks concurrent transformation");
            float[] expired = ZoomCommand.read("0.7472 622", 1100, 1000, 16101);
            blocked.submit(display, expired[0], expired[1]);
        }
        worker.join(2000);
        check(!worker.isAlive(), "worker completes after WM unlock");
        check(blockedCalls.size() == 1 && blockedCalls.get(0).equals("wm-test:1.0"),
                "lease expiry wins under lock and applies only on worker");

        // New state can arrive while the previous application is running; it must be
        // scheduled exactly once after the current transaction/WM lock is released.
        Queue duringQueue = new Queue();
        List<Float> duringCalls = new ArrayList<>();
        ZoomDispatch[] during = new ZoomDispatch[1];
        during[0] = new ZoomDispatch(duringQueue, lock, (target, scale, y, x) -> {
            duringCalls.add(scale);
            if (scale != 1f) during[0].submit(display, 1f, 0f);
        }, errors::add);
        during[0].submit(display, .7472f, 622f);
        duringQueue.runOne();
        check(duringQueue.work.size() == 1, "exit arriving during apply is retained once");
        duringQueue.runOne();
        check(duringCalls.size() == 2 && duringCalls.get(1) == 1f && duringQueue.work.isEmpty(),
                "one follow-up restore, no self-rescheduling loop");

        Queue failureQueue = new Queue();
        List<Throwable> failures = new ArrayList<>();
        int[] attempts = {0};
        ZoomDispatch failing = new ZoomDispatch(failureQueue, lock, (target, scale, y, x) -> {
            attempts[0]++;
            if (scale != 1f) throw new IllegalStateException("simulated Java failure");
        }, failures::add);
        failing.submit(display, .7472f, 622f);
        failureQueue.runOne();
        check(failures.size() == 1 && attempts[0] == 1, "Java failure reported without crashing WM worker");
        for (int i = 0; i < 1000; i++) failing.submit(display, .7472f, 622f);
        check(failureQueue.work.isEmpty(), "failed heartbeat never retries indefinitely");
        failing.submit(display, 1f, 0f);
        failureQueue.runOne();
        check(attempts[0] == 2, "explicit restore remains possible after failed active request");

        Queue rejectedQueue = new Queue();
        rejectedQueue.accepts = false;
        int[] rejectedCalls = {0};
        List<Throwable> rejections = new ArrayList<>();
        ZoomDispatch rejected = new ZoomDispatch(rejectedQueue, lock,
                (target, scale, y, x) -> rejectedCalls[0]++, rejections::add);
        rejected.submit(display, .7472f, 622f);
        for (int i = 0; i < 100; i++) rejected.submit(display, .7472f, 622f);
        check(rejectedCalls[0] == 0 && rejections.size() == 1, "rejected queue never falls back inline");
        rejectedQueue.accepts = true;
        rejected.submit(new Object(), .7472f, 622f);
        rejectedQueue.runOne();
        check(rejectedCalls[0] == 1, "new target may recover from old queue rejection");
        System.out.println("ZoomDispatch regression passed: " + checks + " checks");
    }
}
