package com.jianguo.mistep;

public final class CornerSwipeTest {
    private static int checks;
    private static final int W = 1080, H = 2460;
    private static final int REGION = 320, SWEEP = 160, VERTICAL_DRIFT = 64;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static boolean move(CornerSwipe s, float x, float y, long t) {
        return s.move(7, 1, x, y, t, W, H, 0);
    }
    private static void left(CornerSwipe s) { s.down(7, 1, 100, 100, 1000, W, H, 0, REGION, SWEEP, VERTICAL_DRIFT); }
    public static void main(String[] args) {
        CornerSwipe s = new CornerSwipe();
        left(s);
        check(!move(s, 102, 103, 1100), "tap motion never claims original stream");
        s.reset();
        check(!move(s, 500, 500, 1150), "UP/cancel clears candidate");
        left(s);
        check(!move(s, 259, 100, 1100), "horizontal motion toward center must reach the threshold");
        check(move(s, 260, 100, 1500), "left horizontal inward swipe at exact displacement");
        check(!move(s, 500, 500, 1501), "exactly one claim per stream");
        s.down(7, 1, 1000, 10, 2000, W, H, 0, REGION, SWEEP, VERTICAL_DRIFT);
        check(!move(s, 1160, 10, 2100), "right swipe toward outside rejected");
        check(move(s, 840, 10, 2200), "right horizontal inward swipe accepted");
        left(s);
        check(!move(s, 260, 165, 1100), "diagonal game/app gesture cancels on vertical drift");
        check(!move(s, 300, 100, 1200), "cancelled diagonal gesture cannot revive");
        left(s);
        check(!move(s, 260, 165, 1100), "vertical drift beyond tolerance is rejected");
        left(s);
        check(!move(s, 600, 600, 1501), "timeout never pilfers");
        check(!move(s, 600, 600, 1200), "expired candidate cannot revive");
        s.down(7, 1, 400, 100, 1000, W, H, 0, REGION, SWEEP, VERTICAL_DRIFT);
        check(!move(s, 700, 500, 1200), "outside corners ignored");
        s.down(7, 1, 319, 320, 1000, W, H, 0, REGION, SWEEP, VERTICAL_DRIFT);
        check(!move(s, 500, 500, 1200), "bottom edge outside start zone");
        left(s);
        check(!s.move(7, 2, 300, 300, 1100, W, H, 0), "multi pointer cancels");
        check(!move(s, 300, 300, 1200), "return to single pointer cannot revive");
        left(s);
        check(!s.move(8, 1, 300, 300, 1100, W, H, 0), "different pointer cannot claim");
        left(s);
        check(!s.move(7, 1, 300, 300, 1100, H, W, 1), "rotation cancels");
        left(s);
        check(!s.move(7, 1, 300, 300, 1100, W + 1, H, 0), "display resize cancels");
        left(s);
        check(!move(s, 300, 300, 999), "out of order event time cancels");
        s.down(7, 2, 100, 100, 1000, W, H, 0, REGION, SWEEP, VERTICAL_DRIFT);
        check(!move(s, 300, 300, 1100), "multi pointer DOWN is not a candidate");
        s.down(7, 1, W - 320, 0, 1000, W, H, 0, REGION, SWEEP, VERTICAL_DRIFT);
        check(move(s, W - 480, 0, 1100), "right start-zone boundary");
        s.down(7, 1, 320, 0, 1000, W, H, 0, REGION, SWEEP, VERTICAL_DRIFT);
        check(!move(s, 500, 200, 1100), "left exclusive boundary");
        System.out.println("CornerSwipe: " + checks + " checks passed");
    }
}
