package com.jianguo.mistep;

public final class MiStepEntryGuardTest {
    public static void main(String[] args) {
        int checks = 0;
        for (int state = 0; state < 16; state++) {
            boolean interactive = (state & 1) != 0;
            boolean keyguard = (state & 2) != 0;
            boolean deviceLocked = (state & 4) != 0;
            boolean unlocked = (state & 8) != 0;
            boolean admitted = MiStepEntryGuard.allows(interactive, keyguard, deviceLocked, unlocked);
            if (admitted != (state == 9)) {
                throw new AssertionError("Unexpected entry admission for screen/keyguard/user state " + state);
            }
            checks++;
        }
        System.out.println("MiStepEntryGuard: " + checks + " checks passed");
    }
}
