package com.jiusis.mistep;

public final class RawInputMagnificationPolicyTest {
    private static int checks;

    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }

    private static void close(float actual, float expected, String name) {
        check(Math.abs(actual - expected) < 0.01f, name);
    }

    public static void main(String[] args) {
        RawInputMagnificationPolicy.Transform left =
                RawInputMagnificationPolicy.parseLease("1000|0.75 652 0", 1100);
        check(left != null, "active steady zoom accepted");
        close(RawInputMagnificationPolicy.mapRawY(1402f, left), 1000f,
                "vertical raw coordinate inversely maps through scale and offset");
        check(RawInputMagnificationPolicy.mapsToDisplay(600f, 1402f, left, 1200, 2608),
                "point in left main viewport maps to source display");
        check(!RawInputMagnificationPolicy.mapsToDisplay(950f, 1402f, left, 1200, 2608),
                "right side-task strip is not remapped");
        check(!RawInputMagnificationPolicy.mapsToDisplay(600f, 500f, left, 1200, 2608),
                "point above translated main viewport is not remapped");

        RawInputMagnificationPolicy.Transform right =
                RawInputMagnificationPolicy.parseLease("2000|0.75 652 300", 2100);
        check(right != null && RawInputMagnificationPolicy.mapsToDisplay(
                900f, 1402f, right, 1200, 2608), "right main viewport is remapped");
        check(!RawInputMagnificationPolicy.mapsToDisplay(200f, 1402f, right, 1200, 2608),
                "left side-task strip is not remapped");
        close(RawInputMagnificationPolicy.mapRawX(900f, right), 800f,
                "horizontal main-area offset is removed");

        check(RawInputMagnificationPolicy.parseLease("1000|anim 0.75 652 0 300", 1100) == null,
                "intermediate zoom animation is not treated as steady geometry");
        check(RawInputMagnificationPolicy.parseLease("1000|1 0 0", 1100) == null,
                "identity zoom does not remap input");
        check(RawInputMagnificationPolicy.parseLease("1000|NaN 0 0", 1100) == null,
                "non-finite state rejected");
        check(RawInputMagnificationPolicy.parseLease("malformed", 1100) == null,
                "unleased setting rejected");
        check(RawInputMagnificationPolicy.parseLease("1000|0.75 652 0", 16001) == null,
                "expired host lease does not remap input");
        check(!RawInputMagnificationPolicy.isActive(left, 16001),
                "cached transform expires when the host heartbeat stops");
        check(RawInputMagnificationPolicy.parseLease("1200|0.75 652 0", 1100) == null,
                "future state rejected");

        System.out.println("RawInputMagnificationPolicy: " + checks + " checks passed");
    }
}
