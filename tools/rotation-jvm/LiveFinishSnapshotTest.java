package com.jiusis.mistep;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import android.view.SurfaceControl.Transaction;

/** Invokes the actual HostEntry snapshot method against a bounded fake display. */
public final class LiveFinishSnapshotTest {
    public static final class Display {
        float currentX = 273;
        boolean fail;
        boolean sawFinalSnapshot;
        public void reapplyMagnificationSpec(Object raw) {
            try {
                java.lang.reflect.Field flag = HostEntry.class.getDeclaredField("sFinalMagnificationSnapshot");
                flag.setAccessible(true);
                sawFinalSnapshot = Boolean.TRUE.equals(((ThreadLocal<?>) flag.get(null)).get());
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            Transaction tx = (Transaction) raw;
            tx.x = currentX;
            if (fail) throw new IllegalStateException("display changed");
        }
    }
    static int checks;
    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    public static void main(String[] args) throws Exception {
        Method snapshot = HostEntry.class.getDeclaredMethod("createMagnificationFinishReplay", Object.class);
        snapshot.setAccessible(true);
        Display display = new Display();
        Transaction target = (Transaction) snapshot.invoke(null, display);
        java.lang.reflect.Field flagField = HostEntry.class.getDeclaredField("sFinalMagnificationSnapshot");
        flagField.setAccessible(true);
        @SuppressWarnings("unchecked") ThreadLocal<Boolean> flag = (ThreadLocal<Boolean>) flagField.get(null);
        check(display.sawFinalSnapshot && flag.get() == null,
                "finish must use physical native destination and restore ordinary traversal afterwards");
        check(target.x == 273 && target.applies == 0 && target.closes == 0,
                "current right-main snapshot applied or closed before Shell handoff");
        display.currentX = 0;
        Transaction rollback = (Transaction) snapshot.invoke(null, display);
        check(rollback != target && rollback.x == 0 && target.x == 273,
                "rollback reused the forward snapshot");
        LiveSwapZoom.closeReplay(target); LiveSwapZoom.closeReplay(rollback);
        check(target.closes == 1 && rollback.closes == 1, "unused snapshots leaked");
        display.fail = true;
        try { snapshot.invoke(null, display); throw new AssertionError("failed snapshot admitted"); }
        catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalStateException, "snapshot failure lost");
        }
        check(Transaction.latest.closes == 1 && Transaction.latest.applies == 0,
                "failed snapshot leaked or applied partial operations");
        check(flag.get() == null, "failed snapshot leaked final-state traversal policy");
        display.fail = false;flag.set(Boolean.FALSE);
        Transaction nested = (Transaction) snapshot.invoke(null, display);
        check(display.sawFinalSnapshot && flag.get() == Boolean.FALSE,
                "nested snapshot did not restore the caller's previous traversal policy");
        flag.remove();LiveSwapZoom.closeReplay(nested);
        System.out.println("actual WMS finish snapshot checks=" + checks + " passed");
    }
}
