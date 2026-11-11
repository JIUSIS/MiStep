package com.jiusis.mistep;

import android.content.res.Configuration;
import android.graphics.Rect;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import de.robv.android.xposed.XposedHelpers;

/** A landscape tablet's native IME uses the focused interactive task's viewport. */
final class MiStepImeViewport {
    private static boolean hasMethod(Class<?> type,String name,int count) {
        for(Class<?> c=type;c!=null;c=c.getSuperclass())
            for(Method method:c.getDeclaredMethods())
                if(method.getName().equals(name)&&method.getParameterCount()==count)return true;
        return false;
    }
    private static Object optionalField(Object object, String name) {
        try { return XposedHelpers.getObjectField(object, name); }
        catch (Throwable ignored) { return null; }
    }
    private static Object displayValue(Object info, String fieldName, String className,
            String constant, boolean privacy) {
        Object value = optionalField(info, fieldName);
        if (value != null) return value;
        try {
            Class<?> type = XposedHelpers.findClass(className, info.getClass().getClassLoader());
            try {
                java.lang.reflect.Field constantField = type.getDeclaredField(constant);
                constantField.setAccessible(true);
                return constantField.get(null);
            }
            catch (Throwable ignored) {
                if (privacy) return XposedHelpers.newInstance(type, (Object) new Rect[4], 0);
                return XposedHelpers.newInstance(type);
            }
        } catch (Throwable ignored) { return null; }
    }

    private static final class Lease {
        final Object state, activity;
        final Configuration configuration;
        Lease(Object state, Object activity, Configuration configuration) {
            this.state = state; this.activity = activity; this.configuration = configuration;
        }
    }
    private final IdentityHashMap<Object, Lease> owned = new IdentityHashMap<>();
    private boolean updating;

    static boolean imeWindow(Object window) {
        Object attrs = XposedHelpers.getObjectField(window, "mAttrs");
        int type = XposedHelpers.getIntField(attrs, "type");
        return type == 2011 || type == 2012 || XposedHelpers.getBooleanField(window, "mIsImWindow");
    }

    static Object focusedTarget(Object display, Predicate<Object> lease) {
        if (display == null) return null;
        Object info = XposedHelpers.callMethod(display, "getDisplayInfo");
        int width = XposedHelpers.getIntField(info, "logicalWidth");
        int height = XposedHelpers.getIntField(info, "logicalHeight");
        int density = XposedHelpers.getIntField(info, "logicalDensityDpi");
        if (width <= height || density <= 0 || height * 160f / density < 600f) return null;
        Object input = XposedHelpers.callMethod(display, "getImeInputTarget");
        Object window = input == null ? null : XposedHelpers.callMethod(input, "getWindowState");
        if (!MiStepImePolicy.isFocusedTopWindow(window, display, lease)) return null;
        Object container = XposedHelpers.getObjectField(display, "mImeContainer");
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(container, "isOrganized"))) return null;
        return XposedHelpers.getObjectField(window, "mActivityRecord");
    }

    static boolean skipOtherTask(Object window, Object display, Predicate<Object> lease) {
        Object activity = focusedTarget(display, lease);
        if (activity == null || window == null
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return false;
        Object task = XposedHelpers.callMethod(window, "getTask");
        return task != null && task != XposedHelpers.callMethod(activity, "getTask");
    }

    static void refreshLayering(Object display, Predicate<Object> lease) {
        Object activity = focusedTarget(display, lease);
        if (activity == null) return;
        Object layering = XposedHelpers.getObjectField(display, "mImeLayeringTarget");
        if (layering == null || XposedHelpers.callMethod(layering, "getTask")
                != XposedHelpers.callMethod(activity, "getTask")) {
            Class<?> type = display.getClass();
            if (hasMethod(type, "computeImeLayeringTarget", 1))
                XposedHelpers.callMethod(display, "computeImeLayeringTarget", true);
            else if (hasMethod(type, "computeImeTarget", 1))
                XposedHelpers.callMethod(display, "computeImeTarget", true);
            else throw new IllegalStateException("No native IME target scan");
        }
    }

    static Object target(Object display, Predicate<Object> lease) {
        Object activity = focusedTarget(display, lease);
        if (activity == null) return null;
        Object layering = XposedHelpers.getObjectField(display, "mImeLayeringTarget");
        return layering != null && XposedHelpers.callMethod(layering, "getTask")
                == XposedHelpers.callMethod(activity, "getTask") ? activity : null;
    }

    Object update(Object window, Object display, Predicate<Object> topLease, Consumer<String> log) {
        if (window == null || display == null || !imeWindow(window)
                || XposedHelpers.callMethod(window, "getDisplayContent") != display) return null;
        Object activity = target(display, topLease);
        if (updating) return activity;
        Object token = XposedHelpers.getObjectField(window, "mToken");
        if (token == null) return null;
        updating = true;
        try {
            Lease lease = owned.get(token);
            Object state = XposedHelpers.getObjectField(token, "mFixedRotationTransformState");
            if (lease != null && state != lease.state) {
                owned.remove(token); lease = null;
            }
            if (activity == null) { release(token); return null; }
            Configuration configuration = new Configuration((Configuration)
                    XposedHelpers.callMethod(activity, "getConfiguration"));
            XposedHelpers.setIntField(configuration, "seq", 0);
            Object wc = XposedHelpers.getObjectField(configuration, "windowConfiguration");
            Rect bounds = new Rect((Rect) XposedHelpers.callMethod(
                    XposedHelpers.callMethod(activity, "getTask"), "getBounds"));
            XposedHelpers.callMethod(wc, "setWindowingMode", 1);
            XposedHelpers.callMethod(wc, "setMaxBounds", bounds);
            if (lease != null && lease.activity == activity
                    && lease.configuration.equals(configuration)) return activity;
            if (lease == null && state != null) return null;
            if (lease != null) release(token);
            if (XposedHelpers.callMethod(token, "getParent") == null) return null;
            Object physical = XposedHelpers.callMethod(display, "getDisplayInfo");
            Object info = XposedHelpers.newInstance(physical.getClass(), physical);
            for (String name : new String[]{"logicalWidth", "appWidth"})
                XposedHelpers.setIntField(info, name, bounds.width());
            for (String name : new String[]{"logicalHeight", "appHeight"})
                XposedHelpers.setIntField(info, name, bounds.height());
            ClassLoader loader = display.getClass().getClassLoader();
            Object insets = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "android.view.InsetsState", loader));
            Object frames = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "com.android.server.wm.DisplayFrames", loader), insets, info,
                    displayValue(info, "displayCutout", "android.view.DisplayCutout",
                            "NO_CUTOUT", false),
                    displayValue(info, "roundedCorners", "android.view.RoundedCorners",
                            "NO_ROUNDED_CORNERS", false),
                    displayValue(info, "privacyIndicatorBounds",
                            "android.view.PrivacyIndicatorBounds", "NO_BOUNDS", true),
                    displayValue(info, "displayShape", "android.view.DisplayShape",
                            "DEFAULT_DISPLAY_SHAPE", false));
            XposedHelpers.callMethod(token, "applyFixedRotationTransform", info, frames, configuration);
            Object installed = XposedHelpers.getObjectField(token, "mFixedRotationTransformState");
            if (installed == null) throw new IllegalStateException("IME viewport state was not installed");
            owned.put(token, new Lease(installed, activity, configuration));
            log.accept("interactive IME native viewport=" + bounds.width() + "x" + bounds.height());
            return activity;
        } finally { updating = false; }
    }

    private void release(Object token) {
        Lease lease = owned.get(token);
        if (lease == null) return;
        if (XposedHelpers.getObjectField(token, "mFixedRotationTransformState") == lease.state)
            XposedHelpers.callMethod(token, "finishFixedRotationTransform");
        owned.remove(token);
    }

    void clear(Consumer<String> log) {
        for (Object token : owned.keySet().toArray()) {
            try { release(token); }
            catch (Throwable error) { log.accept("interactive IME viewport restore failed: " + error); }
        }
    }
}
