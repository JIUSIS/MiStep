package com.jiusis.mistep;

public final class InputMagnificationPolicyTest {
    private static int checks;

    private static void expect(boolean expected, int type, int flags) {
        checks++;
        boolean actual = InputMagnificationPolicy.shouldForceMagnification(type, flags);
        if (actual != expected) {
            throw new AssertionError("type=" + type + " flags=" + flags
                    + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        expect(true, 2011, 0);
        expect(true, 2012, 0);
        expect(true, 2019, 0);
        expect(true, 1, 0x08000000);
        expect(false, 1, 0);
        expect(false, 2024, 0);
        expect(false, 2024, 0x08000000);
        expect(false, 2027, 0x08000000);
        expect(false, 2039, 0x08000000);
        check(InputMagnificationPolicy.shouldForceMagnification(2017, 554696704,
                "com.android.systemui", "FlaresView"), "entire native permission window admitted");
        check(!InputMagnificationPolicy.shouldForceMagnification(2017, 554696704,
                "other.app", "FlaresView"), "same title in another package excluded");
        check(!InputMagnificationPolicy.shouldForceMagnification(2017, 554696704,
                "com.android.systemui", "OtherWindow"), "other status panels retain native policy");
        check(!InputMagnificationPolicy.shouldForceMagnification(2024, 554696704,
                "com.android.systemui", "FlaresView"), "excluded magnification overlay retained");
        check(!InputMagnificationPolicy.shouldForceMagnification(2017, 0,
                null, null), "missing identity is not admitted");
        for (int flags : new int[]{0, 0x08000000}) {
            check(InputMagnificationPolicy.shouldForceMagnification(2027, flags,
                    "com.android.systemui", "MiuiBubbles"), "pin window admitted with native input");
            check(!InputMagnificationPolicy.shouldForceMagnification(2027, flags,
                    "another.app", "MiuiBubbles"), "foreign pin title excluded");
            for (CharSequence title : new CharSequence[]{null, "MiuiBubbles-copy", "miui-gesture"})
                check(!InputMagnificationPolicy.shouldForceMagnification(2027, flags,
                        "com.android.systemui", title), "other gesture windows untouched");
        }
        check(!InputMagnificationPolicy.shouldForceMagnification(2024, 0,
                "com.android.systemui", "MiuiBubbles"), "wrong pin type untouched");
        System.out.println("InputMagnificationPolicy: " + checks + " checks passed");
    }

    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}
