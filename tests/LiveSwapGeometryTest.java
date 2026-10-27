package com.jianguo.mistep;

public final class LiveSwapGeometryTest {
    private static int checks;
    private static void near(double actual, double expected) {
        checks++;
        if (Math.abs(actual - expected) > 0.00001)
            throw new AssertionError(actual + " != " + expected);
    }
    private static void rejects(Runnable runnable) {
        checks++;
        try { runnable.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unsafe geometry accepted");
    }
    public static void main(String[] args) {
        LiveSwapGeometry.Rect main = new LiveSwapGeometry.Rect(304, 661, 896, 1999);
        LiveSwapGeometry.Rect side = new LiveSwapGeometry.Rect(0, 240, 297, 663);
        LiveSwapGeometry.Task first = new LiveSwapGeometry.Task(11820, "A", 1200, 2676, main, side);
        LiveSwapGeometry.Task second = new LiveSwapGeometry.Task(11815, "B", 1200, 2676, side, main);
        LiveSwapGeometry.Affine[] parents = {
                LiveSwapGeometry.Affine.identity(),
                new LiveSwapGeometry.Affine(.746, 0, 0, .746, 304, 661),
                new LiveSwapGeometry.Affine(0, .746, -.746, 0, 2000, 30),
                new LiveSwapGeometry.Affine(1, .2, .1, 1, -27, 42)
        };
        LiveSwapGeometry.Affine[] content = {
                new LiveSwapGeometry.Affine(.746, 0, 0, .746, 304, 661),
                new LiveSwapGeometry.Affine(.2473, 0, 0, .2473, 0, 240)
        };
        for (LiveSwapGeometry.Affine parent : parents) {
            for (double fraction : new double[]{0, .1, .5, .9, 1}) {
                LiveSwapGeometry.Frame[] frames = LiveSwapGeometry.frames(first, second,
                        parent, content[0], content[1], fraction);
                for (int i = 0; i < frames.length; i++) {
                    LiveSwapGeometry.Frame frame = frames[i];
                    LiveSwapGeometry.Affine actual = parent.times(frame.local).times(content[i]);
                    for (double x : new double[]{0, 123, 1200}) {
                        for (double y : new double[]{0, 777, 2676}) {
                            double physicalX = frame.physical.left + x / 1200 * frame.physical.width;
                            double physicalY = frame.physical.top + y / 2676 * frame.physical.height;
                            near(actual.mapX(x, y), physicalX);
                            near(actual.mapY(x, y), physicalY);
                            near(frame.bufferFromPhysical.mapX(physicalX, physicalY), x);
                            near(frame.bufferFromPhysical.mapY(physicalX, physicalY), y);
                        }
                    }
                }
            }
        }
        // Final main and side retain the original full buffer; there are no per-frame task resizes.
        LiveSwapGeometry.Frame[] finish = LiveSwapGeometry.frames(first, second, parents[1], 1);
        near(finish[0].physical.width, side.width);
        near(finish[1].physical.width, main.width);
        near(finish[0].task.bufferWidth, 1200);
        rejects(() -> parents[0].times(new LiveSwapGeometry.Affine(Double.NaN, 0, 0, 1, 0, 0)));
        rejects(() -> new LiveSwapGeometry.Affine(0, 0, 0, 0, 0, 0).inverse());
        rejects(() -> LiveSwapGeometry.frames(first, first, parents[0], .5));
        rejects(() -> LiveSwapGeometry.frames(first, second, null, .5));
        rejects(() -> LiveSwapGeometry.frames(first, second, parents[0], null, content[1], .5));
        rejects(() -> LiveSwapGeometry.frames(first, second, parents[0], -0.01));
        rejects(() -> LiveSwapGeometry.frames(first, second, parents[0], 1.01));
        System.out.println("LiveSwapGeometryTest: " + checks + " checks passed");
    }
}
