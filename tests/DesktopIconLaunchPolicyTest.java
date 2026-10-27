package com.jianguo.mistep;

public final class DesktopIconLaunchPolicyTest {
    public static void main(String[] args) {
        expect("com.example.app", DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.miui.home", "android.intent.action.MAIN", true, "com.example.app"));
        expect(null, DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.example.app", "android.intent.action.MAIN", true, "com.example.app"));
        expect(null, DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.miui.home", "android.intent.action.VIEW", true, "com.example.app"));
        expect(null, DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.miui.home", "android.intent.action.MAIN", false, "com.example.app"));
        expect(null, DesktopIconLaunchPolicy.packageForLauncherEntry(
                "com.miui.home", "android.intent.action.MAIN", true, "com.miui.home"));

        index(1, DesktopIconLaunchPolicy.uniqueSideSlot("com.example.app",
                new String[]{"com.other", "com.example.app", null},
                new boolean[]{true, true, false}));
        index(-1, DesktopIconLaunchPolicy.uniqueSideSlot("com.missing",
                new String[]{"com.other", "com.example.app"}, new boolean[]{true, true}));
        index(-1, DesktopIconLaunchPolicy.uniqueSideSlot("com.example.app",
                new String[]{"com.example.app", "com.example.app"}, new boolean[]{true, true}));
        index(-1, DesktopIconLaunchPolicy.uniqueSideSlot("com.example.app",
                new String[]{"com.example.app"}, new boolean[]{false}));
        System.out.println("DesktopIconLaunchPolicyTest PASS 9");
    }

    private static void expect(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("expected package=" + expected + " actual=" + actual);
    }

    private static void index(int expected, int actual) {
        if (expected != actual)
            throw new AssertionError("expected slot=" + expected + " actual=" + actual);
    }
}
