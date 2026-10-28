package com.jiusis.mistep;

/** Task ids may be reused; the covered Home and app must both retain binder identities. */
public final class HomeTaskReturnTest {
    private static int checks;
    private static void check(boolean pass, String reason) {
        checks++;
        if (!pass) throw new AssertionError(reason);
    }
    private static void rejects(int appId, Object app, int homeId, Object home) {
        boolean rejected = false;
        try { new HomeTaskReturn(appId, app, homeId, home); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "invalid app/Home pair must not become a restore target");
    }
    public static void main(String[] args) {
        Object app = "app-binder", home = "home-binder";
        HomeTaskReturn covered = new HomeTaskReturn(120, app, 8, home);
        check(covered.covers(120, new String("app-binder")), "binder wrappers may compare equal");
        check(covered.restores(8, new String("home-binder")), "same Home binder can be rewrapped");
        check(!covered.covers(120, new Object()), "reused app id cannot restore an earlier cover");
        check(!covered.covers(121, app), "different app id is not the covered task");
        check(!covered.restores(8, new Object()), "recreated Home id must not stand in for original Home");
        check(!covered.restores(9, home), "different Home task id cannot replace snapshot identity");
        check(!covered.covers(120, null) && !covered.restores(8, null), "missing bindings reject restoration");
        rejects(-1, app, 8, home);
        rejects(120, null, 8, home);
        rejects(120, app, -1, home);
        rejects(120, app, 8, null);
        rejects(120, app, 120, home);
        rejects(120, app, 8, new String("app-binder"));
        System.out.println("HomeTaskReturn checks=" + checks + " passed");
    }
}
