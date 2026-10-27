package android.view;

/** JVM stand-in for snapshot ownership; does not simulate Android rendering. */
public final class SurfaceControl {
    public static final class Transaction implements AutoCloseable {
        public static Transaction latest;
        public float x;
        public long mNativeObject = 1;
        public int applies, closes;
        public Transaction() { latest = this; }
        public void apply() { applies++; }
        @Override public void close() { closes++; mNativeObject = 0; }
    }
}
