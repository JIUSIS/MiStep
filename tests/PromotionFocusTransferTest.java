package com.jiusis.mistep;

public final class PromotionFocusTransferTest {
    private static int checks;
    private static final Object TOKEN = new Object();
    private static final class Service implements PromotionFocusTransfer.Tasks {
        PromotionFocusTransfer.State state = state(TOKEN, 0, 0, 1, true, false);
        int calls, reads;
        boolean delayed;
        public PromotionFocusTransfer.State read(int id) {
            check(id == 101, "focus observes the promoted task identity");
            reads++;
            return state;
        }
        public void focus(int id) {
            check(id == 101, "focus requests the promoted task");
            calls++;
            if (!delayed) state = state(TOKEN, 0, 0, 1, true, true);
        }
    }

    public static void main(String[] args) throws Exception {
        Service service = new Service();
        check(transfer(service) == PromotionFocusTransfer.Result.FOCUSED,
                "one promotion action focuses the target after backfill stole focus");
        check(service.calls == 1 && service.reads == 2, "request is followed by a fresh system read");
        check(transfer(service) == PromotionFocusTransfer.Result.FOCUSED && service.calls == 1,
                "an already focused task is not reordered again");

        Service pending = new Service(); pending.delayed = true;
        check(transfer(pending) == PromotionFocusTransfer.Result.PENDING,
                "transaction acceptance alone is not focus confirmation");
        pending.state = state(TOKEN, 0, 0, 1, true, true);
        check(PromotionFocusTransfer.observe(pending, 101, TOKEN, 0)
                == PromotionFocusTransfer.Result.FOCUSED && pending.calls == 1,
                "a later observation confirms focus without submitting twice");

        PromotionFocusTransfer.State[] unsafe = {
            null, state(new Object(), 0, 0, 1, true, false),
            state(TOKEN, 1, 0, 1, true, false), state(TOKEN, 0, 10, 1, true, false),
            state(TOKEN, 0, 0, 5, true, false), state(TOKEN, 0, 0, 6, true, false),
            state(TOKEN, 0, 0, 1, false, false)
        };
        for (PromotionFocusTransfer.State value : unsafe) {
            Service invalid = new Service(); invalid.state = value;
            check(transfer(invalid) == PromotionFocusTransfer.Result.UNAVAILABLE && invalid.calls == 0,
                    "missing, replaced, hidden, native-freeform, secondary-display/user task is not focused");
            Service invalidRequest = new Service(); invalidRequest.state = value;
            check(PromotionFocusTransfer.request(invalidRequest, 101, TOKEN, 0)
                    == PromotionFocusTransfer.Result.UNAVAILABLE && invalidRequest.calls == 0,
                    "live main request rejects stale identity and non-main roles");
        }
        Service live = new Service(); live.delayed = true;
        check(PromotionFocusTransfer.request(live, 101, TOKEN, 0)
                == PromotionFocusTransfer.Result.REQUESTED && live.calls == 1 && live.reads == 1,
                "live main requests once without waiting for another system focus sample");
        live.state = state(TOKEN, 0, 0, 1, true, true);
        check(PromotionFocusTransfer.request(live, 101, TOKEN, 0)
                == PromotionFocusTransfer.Result.FOCUSED && live.calls == 1,
                "already focused main is retained without reordering");
        System.out.println("PromotionFocusTransfer checks=" + checks + " passed");
    }

    private static PromotionFocusTransfer.Result transfer(Service service) throws Exception {
        return PromotionFocusTransfer.transfer(service, 101, TOKEN, 0);
    }
    private static PromotionFocusTransfer.State state(Object token, int display, int user,
            int mode, boolean visible, boolean focused) {
        return new PromotionFocusTransfer.State(token, display, user, mode, visible, focused);
    }
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
