package android.os;

/** Records deferred work; these tests exercise the synchronous WM path only. */
public class Handler {
    public final java.util.List<Runnable> pending = new java.util.ArrayList<>();
    public boolean post(Runnable action) { pending.add(action); return true; }
}
