package com.jiusis.mistep;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.AnimationUtils;
import android.view.animation.Transformation;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/** Native WM timing on a live rotation carrier, clipped in its physical task coordinates. */
final class MiStepRotationAnimation {
    private MiStepRotationAnimation() {}

    // All entry points and native finish callbacks run under WMS.mGlobalLock.
    private static final Map<Object, Owned> owned = new WeakHashMap<>();

    private static final class Owned {
        final Object activity, surface, parent, clip;
        final int width, height;
        Object animator, adapter, leash;
        boolean removed;
        Owned(Object activity, Object surface, Object parent, Object clip, Rect bounds) {
            this.activity = activity; this.surface = surface;
            this.parent = parent; this.clip = clip;
            width = bounds.width(); height = bounds.height();
        }
        void lost(Object transaction) {
            if (removed) return;
            // SurfaceAnimator has already restored the live carrier to its original task parent.
            XposedHelpers.callMethod(transaction, "remove", clip);
            removed = true;
            if (owned.get(activity) == this) owned.remove(activity);
        }
    }

    static String resource(int oldRotation, int newRotation) {
        switch ((oldRotation - newRotation + 4) & 3) {
            case 1: return "screen_rotate_plus_90_enter";
            case 2: return "screen_rotate_180_enter";
            case 3: return "screen_rotate_minus_90_enter";
            default: return null;
        }
    }

    static boolean cancel(Object activity) {
        Owned previous = owned.get(activity);
        if (previous == null) return true;
        if (previous.animator != null) {
            Object current = XposedHelpers.callMethod(previous.animator, "getAnimation");
            // A transferred/replaced animation still owns this carrier until its native finish.
            if (current != null && current != previous.adapter) return false;
            if (current != null) XposedHelpers.callMethod(previous.animator, "cancelAnimation");
        }
        if (!previous.removed) {
            previous.lost(XposedHelpers.callMethod(activity, "getPendingTransaction"));
            XposedHelpers.callMethod(activity, "commitPendingTransaction");
        }
        return previous.removed;
    }

    static void cancelAll() {
        for (Object activity : new java.util.ArrayList<>(owned.keySet())) cancel(activity);
    }

    static boolean hasOwners() { return !owned.isEmpty(); }

    static boolean owns(Object activity) { return owned.containsKey(activity); }

    static boolean inTransition(Object activity) {
        for (Object node = activity; node != null; node = XposedHelpers.callMethod(node, "getParent"))
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(node, "inTransition"))) return true;
        return false;
    }

    /** Complete our fixed transform without WM creating a second snapshot rotation transition. */
    static void finishLocalRotation(Object activity) {
        Object state = XposedHelpers.getObjectField(activity, "mFixedRotationTransformState");
        if (state == null) return;
        java.util.List<?> tokens = new java.util.ArrayList<>((java.util.List<?>)
                XposedHelpers.getObjectField(state, "mAssociatedTokens"));
        if (!tokens.contains(activity)) throw new IllegalStateException("rotation token is not associated");
        for (Object token : tokens) {
            if (XposedHelpers.getObjectField(token, "mFixedRotationTransformState") != state)
                throw new IllegalStateException("rotation association changed owner");
        }
        // Native resetTransform removes fixed leashes (and finishes legacy input transforms).
        // A non-null callback skips cancelFixedRotationTransform's new Shell transition.
        XposedHelpers.callMethod(activity, "finishFixedRotationTransform", (Runnable) () -> {});
        for (Object token : tokens) {
            if (XposedHelpers.getObjectField(token, "mFixedRotationTransformState") != null) continue;
            Object parent = XposedHelpers.callMethod(token, "getParent");
            if (parent == null) continue;
            XposedHelpers.callMethod(token, "onConfigurationChanged",
                    XposedHelpers.callMethod(parent, "getConfiguration"));
            // onCancelFixedRotationTransform starts screen freezing on OS2. Use the actual
            // configuration lifecycle without that display-level screenshot animation.
            Object record = XposedHelpers.callMethod(token, "asActivityRecord");
            if (record != null) XposedHelpers.callMethod(record, "ensureActivityConfiguration");
        }
    }

    private static boolean busy(Object container) {
        Object animator = XposedHelpers.getObjectField(container, "mSurfaceAnimator");
        if (XposedHelpers.callMethod(animator, "getAnimation") != null) return true;
        // SurfaceFreezer was removed from the cached OS3/4 WindowContainer.
        try {
            Object freezer = XposedHelpers.getObjectField(container, "mSurfaceFreezer");
            return freezer != null && Boolean.TRUE.equals(XposedHelpers.callMethod(freezer, "hasLeash"));
        } catch (NoSuchFieldError absentOnNewerRom) { return false; }
    }

    /** Enter resources can include a screenshot crossfade; our live buffer stays opaque. */
    private static void removeCrossfade(Animation animation) {
        if (!(animation instanceof AnimationSet)) return;
        java.util.List<Animation> children = ((AnimationSet) animation).getAnimations();
        for (int i = children.size() - 1; i >= 0; i--) {
            Animation child = children.get(i);
            if (child instanceof AlphaAnimation) children.remove(i);
            else removeCrossfade(child);
        }
    }

    /**
     * SF inherits a rectangular crop through the inverse child transform. At
     * intermediate angles that rectangle's bounding box can grow beyond its
     * parent again. Keep the real carrier's four corners inside the physical
     * viewport instead of depending on that crop to clip a rotated polygon.
     */
    private static Animation fitViewport(
            Animation nativeAnimation, int viewportWidth, int viewportHeight) {
        AnimationSet fitted = new AnimationSet(false) {
            private final RectF mapped = new RectF();
            @Override public boolean getTransformation(long time, Transformation out) {
                boolean more = super.getTransformation(time, out);
                mapped.set(0f, 0f, viewportWidth, viewportHeight);
                out.getMatrix().mapRect(mapped);
                float fit = Math.min(1f, Math.min(
                        viewportWidth / mapped.width(), viewportHeight / mapped.height()));
                if (fit > 0f && fit < 1f) {
                    out.getMatrix().postScale(
                            fit, fit, viewportWidth / 2f, viewportHeight / 2f);
                    mapped.set(0f, 0f, viewportWidth, viewportHeight);
                    out.getMatrix().mapRect(mapped);
                }
                float dx = mapped.left < 0f ? -mapped.left
                        : mapped.right > viewportWidth ? viewportWidth - mapped.right : 0f;
                float dy = mapped.top < 0f ? -mapped.top
                        : mapped.bottom > viewportHeight ? viewportHeight - mapped.bottom : 0f;
                if (dx != 0f || dy != 0f) out.getMatrix().postTranslate(dx, dy);
                return more;
            }
        };
        fitted.addAnimation(nativeAnimation);
        return fitted;
    }

    private static Object animatable(Owned o, ClassLoader loader) {
        Class<?> contract = XposedHelpers.findClass("com.android.server.wm.SurfaceAnimator$Animatable", loader);
        // SimpleSurfaceAnimatable.Builder exists on OS2 only. Implement the ROM interface:
        // its native animator still owns leash creation, vsync, cancellation and finish.
        return Proxy.newProxyInstance(loader, new Class<?>[]{contract}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getSurfaceControl": return o.surface;
                case "getParentSurfaceControl": return o.parent;
                case "getAnimationLeashParent": return o.clip;
                case "getAnimationLeash": return o.leash;
                case "getSurfaceWidth": return o.width;
                case "getSurfaceHeight": return o.height;
                case "makeAnimationLeash": return XposedHelpers.callMethod(o.activity, "makeSurface");
                case "getSyncTransaction": return XposedHelpers.callMethod(o.activity, "getSyncTransaction");
                case "getPendingTransaction": return XposedHelpers.callMethod(o.activity, "getPendingTransaction");
                case "commitPendingTransaction":
                    XposedHelpers.callMethod(o.activity, "commitPendingTransaction"); return null;
                case "onAnimationLeashCreated": o.leash = args[1]; return null;
                case "onAnimationLeashLost": o.lost(args[0]); return null;
                case "onLeashAnimationStarting": return null;
                case "shouldDeferAnimationFinish": return false; // OS2; absent on OS3/4.
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "MiStepLiveRotation";
                default: throw new UnsupportedOperationException("native animatable: " + method);
            }
        });
    }

    static boolean start(Object activity, int oldRotation, int newRotation) {
        String name = resource(oldRotation, newRotation);
        if (name == null) return false;
        if (!cancel(activity) || owned.containsKey(activity)) return false;
        Object window = XposedHelpers.callMethod(activity, "findMainWindow");
        if (window == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(window, "isVisible"))
                || XposedHelpers.callMethod(window, "getSurfaceControl") == null
                || busy(window) || busy(activity)) return false;
        // Shell uses its own hierarchy. Do not wrap a container participating in that transition.
        if (inTransition(activity)) return false;
        Object task = XposedHelpers.callMethod(activity, "getTask");
        if (task == null) return false;
        Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
        Object parent = XposedHelpers.callMethod(activity, "getParentSurfaceControl");
        Object surface = XposedHelpers.callMethod(activity, "getFixedRotationLeash");
        if (surface == null) surface = XposedHelpers.callMethod(activity, "getSurfaceControl");
        if (bounds == null || bounds.isEmpty() || parent == null || surface == null
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(parent, "isValid"))) return false;
        Object service = XposedHelpers.getObjectField(activity, "mWmService");
        Context context = (Context) XposedHelpers.getObjectField(service, "mContext");
        float scale = ((Number) XposedHelpers.callMethod(service, "getAnimationScale", 1)).floatValue();
        if (context == null || !Float.isFinite(scale) || scale <= 0f) return false;
        int id = context.getResources().getIdentifier(name, "anim", "android");
        if (id == 0) return false;
        Animation animation = AnimationUtils.loadAnimation(context, id);
        removeCrossfade(animation);
        // The app viewport can swap axes. The physical task carrier does not swap.
        animation.initialize(bounds.width(), bounds.height(), bounds.width(), bounds.height());
        animation.scaleCurrentDuration(scale);
        ClassLoader loader = service.getClass().getClassLoader();
        Object spec = XposedHelpers.newInstance(XposedHelpers.findClass(
                "com.android.server.wm.WindowAnimationSpec", loader), animation, new Point(), false, 0f);
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(spec, "hasExtension"))) return false;
        // Keep the system resource, timing, WM animator and live carrier. Only
        // constrain each resulting matrix; never introduce a snapshot layer.
        animation = fitViewport(animation, bounds.width(), bounds.height());
        spec = XposedHelpers.newInstance(XposedHelpers.findClass(
                "com.android.server.wm.WindowAnimationSpec", loader), animation, new Point(), false, 0f);
        Object adapter = XposedHelpers.newInstance(XposedHelpers.findClass(
                "com.android.server.wm.LocalAnimationAdapter", loader), spec,
                XposedHelpers.getObjectField(service, "mSurfaceAnimationRunner"));
        Object builder = XposedHelpers.callMethod(activity, "makeSurface");
        XposedHelpers.callMethod(builder, "setParent", parent);
        XposedHelpers.callMethod(builder, "setContainerLayer");
        XposedHelpers.callMethod(builder, "setName", "MiStep rotation viewport");
        XposedHelpers.callMethod(builder, "setHidden", false);
        Object clip = XposedHelpers.callMethod(builder, "build");
        Owned o = new Owned(activity, surface, parent, clip, bounds);
        owned.put(activity, o);
        try {
            Object transaction = XposedHelpers.callMethod(activity, "getPendingTransaction");
            XposedHelpers.callMethod(transaction, "setWindowCrop", clip, o.width, o.height);
            XposedHelpers.callMethod(transaction, "setPosition", clip, 0f, 0f);
            XposedHelpers.callMethod(transaction, "setLayer", clip,
                    XposedHelpers.getIntField(activity, "mLastLayer"));
            XposedHelpers.callMethod(transaction, "show", clip);
            Class<?> animatorClass = XposedHelpers.findClass("com.android.server.wm.SurfaceAnimator", loader);
            o.animator = XposedHelpers.newInstance(animatorClass, animatable(o, loader), null, service);
            o.adapter = adapter;
            int type = (Integer) XposedHelpers.getStaticObjectField(animatorClass, "ANIMATION_TYPE_SCREEN_ROTATION");
            // Four arguments never supply a SurfaceFreezer or snapshot adapter.
            XposedHelpers.callMethod(o.animator, "startAnimation", transaction, adapter, false, type);
            XposedHelpers.callMethod(activity, "commitPendingTransaction");
            return true;
        } catch (Throwable error) {
            cancel(activity);
            throw error;
        }
    }
}
