package com.jiusis.mistep;

import android.content.Context;

/** Exercise the production reflection gates against matching and changed ABI shapes. */
public final class WeChatNativeEntryTest {
    public static class Stat { public int f; }
    public static class NamedStat { public int scene; }
    public static class ChangedStat { public String f; }
    public static class StaticStat { public static int f; }
    public static class Referrer {}
    public static class Optional {}
    public static class Home { public boolean a; }
    public static class Launcher {
        final Home home = new Home();
        public Home getHomeUI() { return home; }
    }
    public static class EmptyLauncher { public Home getHomeUI() { return null; } }
    public static class ChangedHome { public String a; }
    public static class ChangedLauncher { public ChangedHome getHomeUI() { return new ChangedHome(); } }
    public static class StaticHome { public static boolean a; }
    public static class StaticLauncher { public StaticHome getHomeUI() { return new StaticHome(); } }
    public static class Matching {
        public static boolean renamed(Context c, String user, String id, String path,
                int type, int version, Stat stat, Referrer ref, Optional extra) { return true; }
        public static boolean unrelated(Context c, String path) { return false; }
    }
    public static class Ambiguous extends Matching {
        public static boolean first(Context c, String user, String id, String path,
                int type, int version, Stat stat, Referrer ref, Optional extra) { return true; }
        public static boolean second(Context c, String user, String id, String path,
                int type, int version, Stat stat, Referrer ref, Optional extra) { return true; }
    }
    public static class WrongReturn {
        public static Boolean renamed(Context c, String user, String id, String path,
                int type, int version, Stat stat, Referrer ref, Optional extra) { return true; }
    }
    public static class NonStatic {
        public boolean renamed(Context c, String user, String id, String path,
                int type, int version, Stat stat, Referrer ref, Optional extra) { return true; }
    }
    public static class WrongParameter {
        public static boolean renamed(Context c, String user, String id, String path,
                long type, int version, Stat stat, Referrer ref, Optional extra) { return true; }
    }
    interface Check { void run() throws Exception; }
    static int checks;
    static void require(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }
    static void reject(Check body) throws Exception {
        checks++;
        try { body.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("ABI drift accepted at " + checks);
    }
    public static void main(String[] args) throws Exception {
        require(MiStepWeChatNative.launchMethod(Matching.class, Stat.class, Referrer.class,
                Optional.class).getName().equals("renamed"));
        reject(() -> MiStepWeChatNative.launchMethod(Ambiguous.class, Stat.class, Referrer.class, Optional.class));
        reject(() -> MiStepWeChatNative.launchMethod(WrongReturn.class, Stat.class, Referrer.class, Optional.class));
        reject(() -> MiStepWeChatNative.launchMethod(NonStatic.class, Stat.class, Referrer.class, Optional.class));
        reject(() -> MiStepWeChatNative.launchMethod(WrongParameter.class, Stat.class, Referrer.class, Optional.class));
        reject(() -> MiStepWeChatNative.launchMethod(Matching.class, NamedStat.class, Referrer.class, Optional.class));
        require(((Stat) MiStepWeChatNative.launchStat(Stat.class)).f == 1001);
        require(((NamedStat) MiStepWeChatNative.launchStat(NamedStat.class)).scene == 1001);
        reject(() -> MiStepWeChatNative.launchStat(ChangedStat.class));
        reject(() -> MiStepWeChatNative.launchStat(StaticStat.class));
        require(MiStepWeChatNative.fresh(100, 100));
        require(MiStepWeChatNative.fresh(100, 6100));
        require(!MiStepWeChatNative.fresh(100, 6101));
        require(!MiStepWeChatNative.fresh(100, 99));
        require(!MiStepWeChatNative.fresh(0, 100));
        require(!MiStepWeChatNative.fresh(-1, 100));
        Launcher launcher = new Launcher();
        require(!MiStepWeChatNative.homeReady(launcher));
        launcher.home.a = true;
        require(MiStepWeChatNative.homeReady(launcher));
        require(!MiStepWeChatNative.homeReady(new EmptyLauncher()));
        reject(() -> MiStepWeChatNative.homeReady(new ChangedLauncher()));
        reject(() -> MiStepWeChatNative.homeReady(new StaticLauncher()));
        System.out.println("WeChat production native ABI and request lifetime: " + checks + " checks passed");
    }
}
