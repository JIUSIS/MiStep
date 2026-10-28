package com.jiusis.mistep;

public final class NativeFreeformPolicyTest {
    public static void main(String[] args) {
        int checks = 0;
        for (int display : new int[]{0, 1})
            for (int uid : new int[]{1000, 10127}) {
                String kind = NativeFreeformPolicy.gestureKind(display,
                        "[Gesture Monitor] MultiTaskSwitch", uid, null);
                if ((display == 0 && uid == 1000) != "native-freeform".equals(kind))
                    throw new AssertionError("Only the default-display system freeform monitor may map");
                checks++;
            }
        for (String name : new String[]{"MultiTaskSwitch", "[Gesture Monitor] onehanded-touch",
                "[Gesture Monitor] MultiTaskSwitch-copy", "PointerEventDispatcherOverlay0", null}) {
            if (NativeFreeformPolicy.gestureKind(0, name, 1000, null) != null)
                throw new AssertionError("Unrelated monitor changed: " + name);
            checks++;
        }
        // 17PM publishes MultiTaskSwitch from SystemUI's app UID, unlike xaga's system UID.
        for (int display : new int[]{0, 1})
            for (int uid : new int[]{-1, 0, 1000, 9999, 10000, 10237, 10451, 99910237})
                for (String[] packages : new String[][]{null, new String[0], {"unrelated"},
                        {"com.miui.home"}, {"com.android.systemui"}, {"shared", "com.android.systemui"}}) {
                    boolean expected = display == 0 && (uid == 1000 || uid >= 10000
                            && packages != null && java.util.Arrays.asList(packages).contains("com.android.systemui"));
                    boolean admitted = "native-freeform".equals(NativeFreeformPolicy.gestureKind(display,
                            "[Gesture Monitor] MultiTaskSwitch", uid, packages));
                    if (admitted != expected) throw new AssertionError("SystemUI ownership gate uid=" + uid);
                    checks++;
                }
        if (NativeFreeformPolicy.gestureKind(0, "[Gesture Monitor] swipe-up", 10127,
                new String[]{"unrelated"}) != null
                || !"swipe-up".equals(NativeFreeformPolicy.gestureKind(0,
                "[Gesture Monitor] swipe-up", 10127, new String[]{"com.miui.home"})))
            throw new AssertionError("Launcher ownership gate changed");
        checks += 2;
        for (int mask = 0; mask < 16; mask++) {
            if (NativeFreeformPolicy.omitFromTouchExclusion((mask & 1) != 0,
                    (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0) != (mask == 15))
                throw new AssertionError("native exclusion must retain every unrelated or stale window");
            checks++;
        }
        for (boolean zoomed : new boolean[]{false, true})
            for (boolean primary : new boolean[]{false, true})
                for (int mode : new int[]{-1, 0, 1, 2, 5, 6}) {
                    boolean expected = false;
                    if (NativeFreeformPolicy.excludeFromZoom(zoomed, primary, mode) != expected)
                        throw new AssertionError("Native windows must inherit the main WM transform");
                    checks++;
                }
        for (boolean nativeVisible : new boolean[]{false, true})
            for (String kind : new String[]{null, "return", "swipe-up", "unrelated"}) {
                float expected = .747f;
                if (NativeFreeformPolicy.gestureScale(nativeVisible, kind, .747f) != expected)
                    throw new AssertionError("OEM spies must use the same transform as native windows");
                checks++;
            }
        System.out.println("NativeFreeformPolicy checks=" + checks + " passed");
    }
}
