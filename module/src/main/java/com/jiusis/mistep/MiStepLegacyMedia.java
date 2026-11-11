package com.jiusis.mistep;

import android.view.View;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** OS2's native panel, with independent models and host dimensions. */
final class MiStepLegacyMedia {
    private static final String UI = "com.android.systemui.media.controls.ui.";
    private static final String PANEL = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaControlPanel";
    private static final ThreadLocal<Boolean> creating = new ThreadLocal<>();
    private static final Set<Object> owned = Collections.newSetFromMap(new IdentityHashMap<>());
    private static volatile Object[] arguments;
    private static Class<?> panelType, playerData;

    static void install(ClassLoader loader, Runnable changed) {
        panelType = XposedHelpers.findClass(PANEL, loader);
        playerData = XposedHelpers.findClass(UI + "controller.MediaPlayerData", loader);
        XposedBridge.hookAllConstructors(panelType, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable() && !Boolean.TRUE.equals(creating.get()) && p.args.length == 23)
                    arguments = p.args.clone();
            }
        });
        XC_MethodHook update = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!p.hasThrowable() && !owned.contains(p.thisObject)) changed.run();
            }
        };
        XposedBridge.hookAllMethods(panelType, "bindPlayer", update);
        for (String method : new String[] { "addMediaPlayer", "removeMediaPlayer" })
            XposedBridge.hookAllMethods(playerData, method, update);
        Class<?> carousel = XposedHelpers.findClass(UI + "controller.MediaCarouselController", loader);
        XposedBridge.hookAllMethods(carousel, "updatePlayers", update);
        Class<?> scroll = XposedHelpers.findClass(UI + "view.MediaCarouselScrollHandler", loader);
        XposedBridge.hookAllMethods(scroll, "updatePlayerVisibilities", update);
    }

    static Object[] selected() {
        Object[] args = arguments;
        if (args == null) return null;
        Object handler = XposedHelpers.getObjectField(args[9], "mediaCarouselScrollHandler");
        int index = XposedHelpers.getIntField(handler, "visibleMediaIndex");
        Object visible = XposedHelpers.getStaticObjectField(playerData, "visibleMediaPlayers");
        Object players = XposedHelpers.getStaticObjectField(playerData, "mediaPlayers");
        if (!(visible instanceof Map) || !(players instanceof Map)) return null;
        ArrayList<?> order = new ArrayList<>(((Map<?, ?>) visible).values());
        if (index < 0 || index >= order.size()) return null;
        Object key = order.get(index);
        Object original = ((Map<?, ?>) players).get(key);
        if (original == null || !panelType.isInstance(original) || owned.contains(original)) return null;
        Object data = XposedHelpers.getObjectField(original, "mMediaData");
        Object name = XposedHelpers.getObjectField(key, "key");
        return data == null || !(name instanceof String) ? null : new Object[] { data, name };
    }

    static Object create() {
        Object[] source = arguments;
        if (source == null) throw new IllegalStateException("OS2 native media factory not ready");
        Object[] args = source.clone();
        Object sharedSeek = args[6], sharedView = args[5];
        Object seek = XposedHelpers.newInstance(sharedSeek.getClass(),
                XposedHelpers.getObjectField(sharedSeek, "bgExecutor"),
                XposedHelpers.getObjectField(sharedSeek, "falsingManager"));
        ClassLoader loader = sharedView.getClass().getClassLoader();
        Object manager = XposedHelpers.newInstance(XposedHelpers.findClass(UI + "controller.MediaHostStatesManager", loader));
        Object view = null;
        boolean completed = false;
        try {
            view = XposedHelpers.newInstance(sharedView.getClass(),
                    XposedHelpers.getObjectField(sharedView, "context"),
                    XposedHelpers.getObjectField(sharedView, "configurationController"), manager,
                    XposedHelpers.getObjectField(sharedView, "logger"), seek,
                    XposedHelpers.getObjectField(sharedView, "mainExecutor"),
                    XposedHelpers.getObjectField(sharedView, "mediaFlags"),
                    XposedHelpers.getObjectField(sharedView, "globalSettings"));
            Class<?> callback = XposedHelpers.findClass("kotlin.jvm.functions.Function0", loader);
            Object unit = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("kotlin.Unit", loader), "INSTANCE");
            XposedHelpers.setObjectField(view, "sizeChangedListener", Proxy.newProxyInstance(loader,
                    new Class<?>[] {callback}, (proxy, method, values) -> {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return values != null && values[0] == proxy;
                        if ("toString".equals(method.getName())) return "MiStepMediaSize";
                        return unit;
                    }));
            args[5] = view;
            args[6] = seek;
            creating.set(true);
            Object panel = XposedHelpers.newInstance(panelType, args);
            owned.add(panel);
            completed = true;
            return panel;
        } finally {
            creating.remove();
            if (!completed && view != null) XposedHelpers.callMethod(view, "onDestroy");
        }
    }

    @SuppressWarnings("unchecked")
    static void measure(Object panel, int width) {
        if (!owned.contains(panel)) throw new IllegalStateException("Unowned OS2 media panel");
        Object view = XposedHelpers.getObjectField(panel, "mMediaViewController");
        ClassLoader loader = view.getClass().getClassLoader();
        Object manager = XposedHelpers.getObjectField(view, "mediaHostStatesManager");
        Map<Object, Object> states = (Map<Object, Object>) XposedHelpers.getObjectField(manager, "mediaHostStates");
        Object state = states.get(0);
        if (state == null) {
            state = XposedHelpers.newInstance(XposedHelpers.findClass(UI + "view.MediaHost$MediaHostStateHolder", loader));
            XposedHelpers.callMethod(state, "setExpansion", 1f);
            XposedHelpers.setBooleanField(state, "visible", true);
            XposedHelpers.setFloatField(state, "squishFraction", 1f);
            states.put(0, state);
        }
        Object input = XposedHelpers.newInstance(XposedHelpers.findClass(
                "com.android.systemui.util.animation.MeasurementInput", loader),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        XposedHelpers.callMethod(state, "setMeasurementInput", input);
        XposedHelpers.callMethod(view, "setCurrentState", 0, 0, 1f, true, false);
    }

    static void release(Object panel) {
        if (panel != null && owned.remove(panel)) XposedHelpers.callMethod(panel, "onDestroy");
    }

    static void measurePlaceholder(View root, int width) {
        android.content.Context context = root.getContext();
        ClassLoader loader = context.getClassLoader();
        Object constraints = XposedHelpers.newInstance(XposedHelpers.findClass(
                "androidx.constraintlayout.widget.ConstraintSet", loader));
        int xml = context.getResources().getIdentifier("media_session_expanded", "xml", "com.android.systemui");
        if (xml == 0) throw new IllegalStateException("OS2 media constraints unavailable");
        ShellCompat.loadConstraints(constraints, context, xml);
        for (String name : new String[] {"header_title", "header_artist", "media_progress_bar",
                "media_elapsed_time", "media_total_time", "action0", "action1", "action2", "action3"}) {
            int id = context.getResources().getIdentifier(name, "id", "com.android.systemui");
            if (id != 0) XposedHelpers.callMethod(constraints, "setVisibility", id, View.VISIBLE);
        }
        Object input = XposedHelpers.newInstance(XposedHelpers.findClass(
                "com.android.systemui.util.animation.MeasurementInput", loader),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        Object state = XposedHelpers.newInstance(XposedHelpers.findClass(
                "com.android.systemui.util.animation.TransitionViewState", loader));
        applyPlaceholderState(root, input, constraints, state);
    }

    private static void applyPlaceholderState(Object root, Object input, Object constraints, Object state) {
        Object measured = XposedHelpers.callMethod(root, "calculateViewState", input, constraints, state);
        XposedHelpers.callMethod(root, "setMeasureState", measured);
        XposedHelpers.callMethod(root, "setState", measured);
    }

    private MiStepLegacyMedia() {}
}
