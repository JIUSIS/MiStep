package com.jianguo.mistep;
import java.util.ArrayList;
import java.util.List;
public final class FinishTransactionsTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        FinishTransactions<String> f = new FinishTransactions<>(2);
        Object a = new String("same"), b = new String("same"), c = new Object();
        check(!f.mark(null, List.of("x")), "null cannot be marked");
        check(!f.mark(a, List.of()), "no bindings leave no transaction marker");
        ArrayList<String> source = new ArrayList<>(List.of("side1"));
        check(f.mark(a, source), "mark exact finish transaction");
        source.clear();
        check(f.take(b).isEmpty(), "equal but different transaction is unrelated");
        check(f.take(a).equals(List.of("side1")), "captures bindings without caller list alias");
        check(f.take(a).isEmpty(), "apply overload chain consumes only once");
        f.mark(a, List.of("side1"));
        f.mark(b, List.of("side2", "side3"));
        check(!f.mark(c, List.of("new")), "bounded live transaction registry");
        f.merge(c, a);
        f.forget(a);
        check(f.size() == 2, "native merge transfers marker and close-source is harmless");
        check(f.take(c).equals(List.of("side1")), "merged receiver restores original side");
        f.merge(c, b);
        check(f.take(c).equals(List.of("side2", "side3")), "merge into unmarked target tracked");
        f.mark(a, List.of("first"));
        f.mark(b, List.of("second"));
        f.merge(a, b);
        check(f.take(a).equals(List.of("first", "second")), "two known finish transactions retain both snapshots");
        check(f.take(b).isEmpty(), "merged source never applies twice");
        f.mark(a, List.of("closed"));
        f.forget(a);
        check(f.take(a).isEmpty(), "close drops abandoned native transaction");
        f.mark(a, List.of("self"));
        f.merge(a, a);
        check(f.take(a).equals(List.of("self")), "self merge does not erase marker");
        check(f.size() == 0, "all leases consumed");
        System.out.println("FinishTransactions: " + checks + " checks passed");
    }
}
