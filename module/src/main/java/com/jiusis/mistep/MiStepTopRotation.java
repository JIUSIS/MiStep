package com.jiusis.mistep;

import android.content.res.Configuration;
import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;

/** Rotate a real TOP activity inside its unchanged, local TaskView carrier. */
final class MiStepTopRotation {
    private MiStepTopRotation() {}
    private static final java.util.Map<Object, Applying> applying = new java.util.WeakHashMap<>();

    private static final class Applying {
        final Object task;
        final Rect bounds;
        final Configuration config;
        Applying(Object task, Rect bounds, Configuration config) {
            this.task = task; this.bounds = new Rect(bounds); this.config = config;
        }
    }

    static boolean phone(Object display) {
        Object info = XposedHelpers.callMethod(display, "getDisplayInfo");
        int w = XposedHelpers.getIntField(info, "logicalWidth");
        int h = XposedHelpers.getIntField(info, "logicalHeight");
        int dpi = XposedHelpers.getIntField(info, "logicalDensityDpi");
        return dpi > 0 && Math.min(w, h) * 160f / dpi < 600f;
    }

    static int rotation(int systemRotation) {
        return systemRotation == 1 || systemRotation == 3 ? systemRotation : 0;
    }

    static boolean isApplying(Object activity, Object task) {
        Configuration expectedConfig = applyingConfiguration(activity, task);
        if (expectedConfig == null) return false;
        Configuration current = (Configuration) XposedHelpers.callMethod(activity, "getConfiguration");
        Object wc = XposedHelpers.getObjectField(current, "windowConfiguration");
        Object expected = XposedHelpers.getObjectField(expectedConfig, "windowConfiguration");
        return current.densityDpi == expectedConfig.densityDpi
                && XposedHelpers.callMethod(wc, "getBounds").equals(
                        XposedHelpers.callMethod(expected, "getBounds"))
                && XposedHelpers.callMethod(wc, "getDisplayRotation").equals(
                        XposedHelpers.callMethod(expected, "getDisplayRotation"));
    }

    static Configuration applyingConfiguration(Object activity, Object task) {
        Applying scope = applying.get(activity);
        return scope != null && scope.task == task
                && scope.bounds.equals(XposedHelpers.callMethod(task, "getBounds"))
                ? scope.config : null;
    }

    static boolean shouldKeepLiveSurface(Object activity, int changes, Object previousConfig,
            Object ownedState, boolean releasing) {
        // Only the local viewport's orientation, layout, size and density changes.
        // Locale, font, UI mode and every other native relaunch reason stay native.
        int localChanges = android.content.pm.ActivityInfo.CONFIG_ORIENTATION
                | android.content.pm.ActivityInfo.CONFIG_SCREEN_LAYOUT
                | android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE
                | android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
                | android.content.pm.ActivityInfo.CONFIG_DENSITY;
        if (changes == 0 || (changes & ~localChanges) != 0) return false;
        if (activity == null || previousConfig == null || ownedState == null) return false;
        Object currentState = XposedHelpers.getObjectField(
                activity, "mFixedRotationTransformState");
        if (currentState != ownedState && !(releasing && currentState == null)) return false;
        Object previousWindow = XposedHelpers.getObjectField(previousConfig, "windowConfiguration");
        Object current = XposedHelpers.callMethod(activity, "getConfiguration");
        Object currentWindow = XposedHelpers.getObjectField(current, "windowConfiguration");
        int previousRotation = (Integer) XposedHelpers.callMethod(
                previousWindow, "getDisplayRotation");
        int currentRotation = (Integer) XposedHelpers.callMethod(
                currentWindow, "getDisplayRotation");
        return previousRotation != currentRotation;
    }

    /** Native state owns surface AND window input transforms; no second angle in raw input. */
    static void apply(Object display, Object activity, Object task, int rotation) {
        if (rotation != 1 && rotation != 3) throw new IllegalArgumentException("TOP rotation");
        if (XposedHelpers.getObjectField(activity, "mFixedRotationTransformState") != null)
            throw new IllegalStateException("TOP transform already owned");
        Rect carrier = (Rect) XposedHelpers.callMethod(task, "getBounds");
        if (carrier.left != 0 || carrier.top != 0 || carrier.width() <= carrier.height()
                || carrier.height() <= 0 || XposedHelpers.callMethod(activity, "getParent") == null)
            throw new IllegalStateException("TOP carrier detached or invalid");
        Rect viewport = new Rect(0, 0, carrier.height(), carrier.width());
        Configuration config = new Configuration((Configuration)
                XposedHelpers.callMethod(task, "getConfiguration"));
        if (config.densityDpi <= 0) throw new IllegalStateException("TOP density unavailable");
        // Both orientations use the landscape task's original density.
        // Only the local viewport's dimensions and direction change.
        XposedHelpers.setIntField(config, "seq", 0);
        config.orientation = Configuration.ORIENTATION_PORTRAIT;
        config.screenWidthDp = Math.round(viewport.width() * 160f / config.densityDpi);
        config.screenHeightDp = Math.round(viewport.height() * 160f / config.densityDpi);
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
        config.screenLayout = (Integer) XposedHelpers.callStaticMethod(Configuration.class,
                "reduceScreenLayout", XposedHelpers.callStaticMethod(Configuration.class,
                        "resetScreenLayout", config.screenLayout),
                config.screenHeightDp, config.screenWidthDp);
        Object wc = XposedHelpers.getObjectField(config, "windowConfiguration");
        // Task stacking policy is not inherited by Activity containers. Copying it
        // into the token's rotation override pins the old page above new pages.
        XposedHelpers.callMethod(wc, "unsetAlwaysOnTop");
        XposedHelpers.callMethod(wc, "setBounds", viewport);
        XposedHelpers.callMethod(wc, "setAppBounds", viewport);
        XposedHelpers.callMethod(wc, "setMaxBounds", viewport);
        XposedHelpers.callMethod(wc, "setRotation", rotation);
        XposedHelpers.callMethod(wc, "setDisplayRotation", rotation);
        Object physical = XposedHelpers.callMethod(display, "getDisplayInfo");
        Object info = XposedHelpers.newInstance(physical.getClass(), physical);
        XposedHelpers.setIntField(info, "rotation", rotation);
        for (String name : new String[]{"logicalWidth", "appWidth"})
            XposedHelpers.setIntField(info, name, viewport.width());
        for (String name : new String[]{"logicalHeight", "appHeight"})
            XposedHelpers.setIntField(info, name, viewport.height());
        XposedHelpers.setIntField(info, "logicalDensityDpi", config.densityDpi);
        ClassLoader loader = display.getClass().getClassLoader();
        Object cutout = empty(loader, "DisplayCutout", "NO_CUTOUT");
        Object corners = empty(loader, "RoundedCorners", "NO_ROUNDED_CORNERS");
        Object privacy = XposedHelpers.newInstance(XposedHelpers.findClass(
                "android.view.PrivacyIndicatorBounds", loader), (Object) new Rect[4], rotation);
        Object shape = empty(loader, "DisplayShape", "NONE");
        XposedHelpers.setObjectField(info, "displayCutout", cutout);
        XposedHelpers.setObjectField(info, "roundedCorners", corners);
        XposedHelpers.setObjectField(info, "displayShape", shape);
        Object insets = XposedHelpers.newInstance(XposedHelpers.findClass(
                "android.view.InsetsState", loader));
        Object frames = XposedHelpers.newInstance(XposedHelpers.findClass(
                "com.android.server.wm.DisplayFrames", loader),
                insets, info, cutout, corners, privacy, shape);
        if (applying.containsKey(activity)) throw new IllegalStateException("TOP apply reentered");
        applying.put(activity, new Applying(task, carrier, config));
        try {
            XposedHelpers.callMethod(activity, "applyFixedRotationTransform", info, frames, config);
        } finally {
            applying.remove(activity);
        }
        ensureSurface(activity);
    }

    /** Each linked token needs its own native leash after its full configuration has resolved. */
    static void ensureSurface(Object activity) {
        if (XposedHelpers.getObjectField(activity, "mFixedRotationTransformState") == null
                || XposedHelpers.callMethod(activity, "getParent") == null) return;
        Object controller = XposedHelpers.getObjectField(activity, "mTransitionController");
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(controller, "useShellTransitionsRotation")))
            XposedHelpers.callMethod(activity, "getOrCreateFixedRotationLeash",
                    XposedHelpers.callMethod(activity, "getPendingTransaction"));
    }

    private static Object empty(ClassLoader loader, String type, String field) {
        return XposedHelpers.getStaticObjectField(
                XposedHelpers.findClass("android.view." + type, loader), field);
    }
}
