package com.jiusis.mistep;

/** Offline regression for the animated zoom command; no Android runtime required. */
public final class ZoomAnimCommandTest {
    private static int cases;

    private static void expectParse(String name, String line, long modified, long started,
            long now, Float scale, Float y, Float x, Long duration) {
        ZoomAnimCommand.Plan plan = ZoomAnimCommand.parse(line, modified, started, now);
        if (plan == null) {
            if (scale != null || y != null || x != null || duration != null) {
                throw new AssertionError(name + ": expected a plan, got null");
            }
            cases++;
            return;
        }
        if (scale == null || y == null || x == null || duration == null) {
            throw new AssertionError(name + ": expected null, got " + plan.describe());
        }
        if (Math.abs(plan.scale - scale) > 0.00001f || plan.offsetY != y
                || plan.offsetX != x || plan.durationMs != duration) {
            throw new AssertionError(name + ": " + plan.describe());
        }
        cases++;
    }

    private static void expectValue(String name, float actual, float expected) {
        if (Math.abs(actual - expected) > 0.00001f) {
            throw new AssertionError(name + ": " + actual + " != " + expected);
        }
        cases++;
    }

    public static void main(String[] args) {
        expectParse("valid slot target", "anim 0.2472 1237 0 300",
                1100, 1000, 1100, .2472f, 1237f, 0f, 300L);
        expectParse("extra whitespace", "anim   0.7472  622 273 200",
                1100, 1000, 1100, .7472f, 622f, 273f, 200L);
        expectParse("min duration", "anim 1 0 0 40",
                1100, 1000, 1100, 1f, 0f, 0f, 40L);
        expectParse("max duration", "anim 1 0 0 2000",
                1100, 1000, 1100, 1f, 0f, 0f, 2000L);
        expectParse("within lease", "anim 0.5 0 0 300",
                1100, 1000, 16100, .5f, 0f, 0f, 300L);

        expectParse("previous boot stale", "anim 0.5 0 0 300",
                999, 1000, 1100, null, null, null, null);
        expectParse("dead host lease", "anim 0.5 0 0 300",
                1100, 1000, 16101, null, null, null, null);
        expectParse("plain state is not anim", "0.7472 622 273",
                1100, 1000, 1100, null, null, null, null);
        expectParse("keyword only", "anim",
                1100, 1000, 1100, null, null, null, null);
        expectParse("keyword prefix", "animate 0.5 0 0 300",
                1100, 1000, 1100, null, null, null, null);
        expectParse("missing duration", "anim 0.5 0 0",
                1100, 1000, 1100, null, null, null, null);
        expectParse("extra field", "anim 0.5 0 0 300 7",
                1100, 1000, 1100, null, null, null, null);
        expectParse("duration too short", "anim 0.5 0 0 39",
                1100, 1000, 1100, null, null, null, null);
        expectParse("duration too long", "anim 0.5 0 0 2001",
                1100, 1000, 1100, null, null, null, null);
        expectParse("zero scale", "anim 0 0 0 300",
                1100, 1000, 1100, null, null, null, null);
        expectParse("overscale", "anim 1.2 0 0 300",
                1100, 1000, 1100, null, null, null, null);
        expectParse("NaN scale", "anim NaN 0 0 300",
                1100, 1000, 1100, null, null, null, null);
        expectParse("Infinity offset", "anim 0.5 Infinity 0 300",
                1100, 1000, 1100, null, null, null, null);
        expectParse("negative offset", "anim 0.5 -1 0 300",
                1100, 1000, 1100, null, null, null, null);
        expectParse("offset beyond screen", "anim 0.5 10001 0 300",
                1100, 1000, 1100, null, null, null, null);
        expectParse("non-numeric duration", "anim 0.5 0 0 abc",
                1100, 1000, 1100, null, null, null, null);
        expectParse("empty line", "  ",
                1100, 1000, 1100, null, null, null, null);
        expectParse("null line", null,
                1100, 1000, 1100, null, null, null, null);

        expectValue("lerp start", ZoomAnimCommand.valueAt(.7472f, .2472f, 0f), .7472f);
        expectValue("lerp end", ZoomAnimCommand.valueAt(.7472f, .2472f, 1f), .2472f);
        expectValue("lerp middle", ZoomAnimCommand.valueAt(0f, 100f, .25f), 25f);
        expectValue("lerp clamps below", ZoomAnimCommand.valueAt(10f, 20f, -.5f), 10f);
        expectValue("lerp clamps above", ZoomAnimCommand.valueAt(10f, 20f, 1.5f), 20f);

        ZoomAnimCommand.Plan plan = new ZoomAnimCommand.Plan(.2472f, 1237f, 273f, 300L);
        float[] mid = ZoomAnimCommand.stateAt(.7472f, 622f, 0f, plan, .5f);
        expectValue("stateAt scale", mid[0], (.7472f + .2472f) / 2f);
        expectValue("stateAt offsetY", mid[1], (622f + 1237f) / 2f);
        expectValue("stateAt offsetX", mid[2], (0f + 273f) / 2f);
        float[] end = ZoomAnimCommand.stateAt(.7472f, 622f, 0f, plan, 1f);
        expectValue("stateAt end scale", end[0], .2472f);
        expectValue("stateAt end offsetY", end[1], 1237f);
        expectValue("stateAt end offsetX", end[2], 273f);

        System.out.println("ZoomAnimCommand regression passed: " + cases + " cases");
    }
}
