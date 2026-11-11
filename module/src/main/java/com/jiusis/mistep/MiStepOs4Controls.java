package com.jiusis.mistep;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Independent views from the loaded OEM plugin, never reparent the system's views. */
final class MiStepOs4Controls {
    private static WeakReference<Context> plugin = new WeakReference<>(null);
    private static int failures = 4;

    static void install(ClassLoader loader) {
        try {
            Class<?> instance = XposedHelpers.findClass("com.android.systemui.shared.plugins.PluginInstance", loader);
            XposedHelpers.findAndHookMethod(instance, "loadPlugin", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    try {
                        Context context = pluginContext(p.thisObject);
                        if (context != null && "miui.systemui.plugin".equals(context.getPackageName())) {
                            // This loader and theme belong to the initialized OEM plugin.
                            XposedHelpers.findClass("miui.systemui.controlcenter.widget.VerticalSeekBar", context.getClassLoader());
                            plugin = new WeakReference<>(context);
                        }
                    } catch (Throwable e) { failed("capture", e); }
                }
            });
            XposedHelpers.findAndHookMethod(instance, "unloadPlugin", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (pluginContext(p.thisObject) == plugin.get())
                            plugin.clear();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable e) { failed("install", e); }
    }

    private static Context pluginContext(Object instance) {
        if (ShellCompat.hasMethod(instance.getClass(), "getPluginContext", 0))
            return (Context) XposedHelpers.callMethod(instance, "getPluginContext");
        Object data = XposedHelpers.getObjectField(instance, "pluginData");
        return data == null ? null : (Context) XposedHelpers.getObjectField(data, "context");
    }

    static Context context() {
        Context context = plugin.get();
        if (context == null) throw new IllegalStateException("OEM control plugin not loaded");
        return context;
    }

    static Object inflate(String bindingName) {
        Context context = context();
        Class<?> binding = XposedHelpers.findClassIfExists("miui.systemui.controlcenter.databinding." + bindingName,
                context.getClassLoader());
        if (binding != null)
            return XposedHelpers.callStaticMethod(binding, "inflate", LayoutInflater.from(context));
        String layout, rootType;
        switch (bindingName) {
            case "QsTileItemViewBinding":
                layout = "qs_tile_item_view";
                rootType = "miui.systemui.controlcenter.qs.tileview.QSTileItemView";
                break;
            case "ToggleSliderItemViewBinding":
                layout = "toggle_slider_item_view";
                rootType = "miui.systemui.controlcenter.widget.ToggleSliderView";
                break;
            default: throw new IllegalArgumentException("Unsupported OEM binding " + bindingName);
        }
        int id = context.getResources().getIdentifier(layout, "layout", context.getPackageName());
        if (id == 0) throw new IllegalStateException("Missing OEM layout " + layout);
        View root = LayoutInflater.from(context).inflate(id, null, false);
        if (!root.getClass().getName().equals(rootType))
            throw new IllegalStateException("Unexpected OEM layout root " + root.getClass().getName());
        return root;
    }

    static View root(Object binding) {
        return binding instanceof View ? (View) binding
                : (View) XposedHelpers.callMethod(binding, "getRoot");
    }

    static View field(Object object, String name) {
        if (!(object instanceof View)) return (View) XposedHelpers.getObjectField(object, name);
        View root = (View) object;
        Context context = root.getContext();
        String resource = name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
        int id = context.getResources().getIdentifier(resource, "id", context.getPackageName());
        if (id == 0) throw new IllegalStateException("Missing OEM view " + resource);
        return root.requireViewById(id);
    }

    static View tile(Context system, String label, Runnable action) {
        return tile(system, label, action, false);
    }

    static View tile(Context system, String label, Runnable action, boolean iconOnly) {
        Context context = context();
        Object binding = inflate("QsTileItemViewBinding");
        ViewGroup root = (ViewGroup) root(binding);
        Class<?> iconType = XposedHelpers.findClass("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", context.getClassLoader());
        boolean fourArguments = false;
        for (java.lang.reflect.Constructor<?> constructor : iconType.getDeclaredConstructors())
            if (constructor.getParameterCount() == 4) fourArguments = true;
        View icon = (View) (fourArguments
                ? XposedHelpers.newInstance(iconType, MiStepNativeIconContext.wrap(context), MiStepNativeIconContext.wrap(system), false, false)
                : XposedHelpers.newInstance(iconType, MiStepNativeIconContext.wrap(context), MiStepNativeIconContext.wrap(system), false));
        XposedHelpers.callMethod(root, "init", icon);
        root.setContentDescription(label);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.setOnClickListener(v -> action.run());
        root.setOnLongClickListener(null);
        root.setPadding(0, 0, 0, 0);
        field(binding, "mark").setVisibility(View.GONE);
        TextView text = (TextView) field(binding, "tileLabel");
        text.setVisibility(iconOnly ? View.GONE : View.VISIBLE);
        text.setAlpha(1f);
        text.setText(label);
        configureCaption(system, text);
        text.setTextColor(HyperOsStyle.textPrimary(system));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, dp(system, 14), Gravity.TOP);
        tp.topMargin = dp(system, 40);
        text.setLayoutParams(tp);
        ViewGroup frame = (ViewGroup) field(binding, "iconFrame");
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(system, 36), dp(system, 36), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        frame.setLayoutParams(fp);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (iconOnly) {
                ((TileStyle) root.getTag()).fitIconOnly();
                return;
            }
            ((TileStyle) root.getTag()).fitCaption(r - l, b - t);
        });
        icon.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        icon.setClickable(false);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.setTag(new TileStyle(system, label, icon, frame, root, text, iconOnly));
        ((TileStyle) root.getTag()).apply(root, false);
        return root;
    }

    /** Run before the parent's measure, so the first frame uses the fitting bounds. */
    static void fitTile(View tile, int width, int height) {
        Object tag = tile.getTag();
        if (tag instanceof TileStyle && !((TileStyle) tag).iconOnly)
            ((TileStyle) tag).fitCaption(width, height);
    }

    private static void configureCaption(Context system, TextView text) {
        text.setTextSize(DeviceProfile.isTablet(system) ? 12 : 9);
        text.setSingleLine(true);
        // Single-line TextViews otherwise measure an unbounded scrolling width,
        // preventing auto-size from fitting a three-character caption to a narrow tile.
        text.setHorizontallyScrolling(false);
        text.setEllipsize(null);
        text.setIncludeFontPadding(false);
        text.setLineSpacing(0f, 1f);
        text.setPadding(0, 0, 0, 0);
        text.setMinWidth(0); text.setMinimumWidth(0);
        text.setMinHeight(0); text.setMinimumHeight(0);
        text.setGravity(Gravity.CENTER);
        int max = Math.max(2, Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, DeviceProfile.isTablet(system) ? 12 : 9,
                system.getResources().getDisplayMetrics())));
        text.setAutoSizeTextTypeUniformWithConfiguration(1, max, 1,
                android.util.TypedValue.COMPLEX_UNIT_PX);
    }

    static void state(View tile, Object state) {
        if (state == null) return;
        try {
            TileStyle style = (TileStyle) tile.getTag();
            // The system owns its State; keep our caption without changing its shared label.
            Object own = XposedHelpers.callMethod(state, "copy");
            XposedHelpers.setObjectField(own, "label", style.label);
            XposedHelpers.callMethod(tile, "updateState", own, false, false);
            style.apply(tile, XposedHelpers.getIntField(own, "state") == 2);
        }
        catch (Throwable e) { failed("tile state", e); }
    }

    private static final class TileStyle {
        final Context system;
        final String label;
        final View icon;
        final View frame;
        final View root;
        final TextView caption;
        final GradientDrawable material;
        final android.graphics.drawable.RippleDrawable surface;
        final View codeGlyph;
        final boolean iconOnly;
        TileStyle(Context system, String label, View icon, View frame, View root, TextView caption, boolean iconOnly) {
            this.system = system; this.label = label; this.icon = icon; this.frame = frame;
            this.root = root;
            this.caption = caption;
            this.iconOnly = iconOnly;
            Object source = XposedHelpers.getObjectField(icon, "disabledBg");
            material = copyShape(source instanceof Drawable ? (Drawable) source : null, system);
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(0xffffffff);
            surface = new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33ffffff), material, mask);
            frame.setDuplicateParentStateEnabled(true);
            // These two MiStep actions have no matching stock QS artwork.
            codeGlyph = "付款码".equals(label) || "乘车码".equals(label)
                    ? new MiStepCodeIcon.GlyphView(system, "乘车码".equals(label)) : null;
            if (codeGlyph != null) ((ViewGroup) root).addView(codeGlyph,
                    new FrameLayout.LayoutParams(dp(system, 36), dp(system, 36),
                            Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }
        void apply(View root, boolean active) {
            if (iconOnly) fitIconOnly();
            else if (root.getWidth() > 0 && root.getHeight() > 0)
                fitCaption(root.getWidth(), root.getHeight());
            // Native blend tokens need the OEM panel blur provider. Our window supplies a
            // standalone material behind the unchanged OEM view.
            material.setColor(HyperOsStyle.nativeGlyphFill(system, active));
            ViewGroup.LayoutParams frameSize = frame.getLayoutParams();
            material.setCornerRadius(Math.max(0, Math.min(frameSize.width, frameSize.height)) / 2f);
            frame.setBackground(surface);
            root.setBackground(null);
            root.setElevation(0);
            if (codeGlyph != null) {
                // Only the action glyph is drawn: the unrelated stock fallback stays hidden.
                icon.setVisibility(View.INVISIBLE);
                codeGlyph.setVisibility(View.VISIBLE);
                codeGlyph.bringToFront();
                caption.setTextColor(HyperOsStyle.textPrimary(system));
                return;
            }
            // Exactly one icon view; its State resolves the original OEM resources.
            icon.setVisibility(View.VISIBLE);
            ImageView image = (ImageView) XposedHelpers.callMethod(icon, "getIcon");
            // The OEM child otherwise measures at its original full-size tile dimensions.
            // Fit the entire compound drawable inside our independent circular icon frame.
            ViewGroup.LayoutParams imageLp = image.getLayoutParams();
            imageLp.width = imageLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (imageLp instanceof LinearLayout.LayoutParams)
                ((LinearLayout.LayoutParams) imageLp).gravity = Gravity.CENTER;
            else if (imageLp instanceof FrameLayout.LayoutParams)
                ((FrameLayout.LayoutParams) imageLp).gravity = Gravity.CENTER;
            image.setLayoutParams(imageLp);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(0, 0, 0, 0);
            // System action vectors have no padded tile canvas, unlike the QS AVDs.
            float glyphScale = "VPN".equals(label) || "扫一扫".equals(label) ? .55f : 1f;
            image.setScaleX(glyphScale);
            image.setScaleY(glyphScale);
            // The OEM drawable includes blend/background layers: tinting the entire
            // ImageView makes those transparent layers opaque circles.
            image.setImageTintList(null);
            settleIcon(image.getDrawable());
            caption.setTextColor("扫一扫".equals(label) || "付款码".equals(label)
                    || "乘车码".equals(label) ? HyperOsStyle.textPrimary(system) : 0xffffffff);
        }

        void fitCaption(int width, int height) {
            Paint metrics = new Paint(caption.getPaint());
            metrics.setTextSize(android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, DeviceProfile.isTablet(system) ? 12 : 9,
                    system.getResources().getDisplayMetrics()));
            Paint.FontMetrics font = metrics.getFontMetrics();
            int lineHeight = Math.max(dp(system, 14), (int) Math.ceil(font.bottom - font.top));
            ControlCenterTileGeometry g = new ControlCenterTileGeometry(
                    width, height, dp(system, 36), lineHeight, dp(system, 4));
            fitIconFrame(frame, g.iconSize, g.iconTop);
            if (codeGlyph != null) fitIconFrame(codeGlyph, g.iconSize, g.iconTop);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) caption.getLayoutParams();
            if (lp.width != -1 || lp.height != g.captionHeight || lp.topMargin != g.captionTop
                    || lp.gravity != Gravity.TOP || lp.leftMargin != 0 || lp.rightMargin != 0 || lp.bottomMargin != 0) {
                lp.width = -1; lp.height = g.captionHeight; lp.topMargin = g.captionTop;
                lp.leftMargin = lp.rightMargin = lp.bottomMargin = 0;
                lp.gravity = Gravity.TOP;
                caption.setLayoutParams(lp);
            }
            material.setCornerRadius(g.iconSize / 2f);
        }

        private void fitIconFrame(View view, int size, int top) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) view.getLayoutParams();
            int gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            if (lp.width != size || lp.height != size || lp.topMargin != top || lp.gravity != gravity
                    || lp.leftMargin != 0 || lp.rightMargin != 0 || lp.bottomMargin != 0) {
                lp.width = lp.height = size; lp.topMargin = top; lp.gravity = gravity;
                lp.leftMargin = lp.rightMargin = lp.bottomMargin = 0;
                view.setLayoutParams(lp);
            }
        }

        void fitIconOnly() {
            caption.setVisibility(View.GONE);
            int size = Math.max(1, Math.min(root.getWidth(), root.getHeight()));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
            if (lp.width != size || lp.height != size || lp.gravity != Gravity.CENTER || lp.topMargin != 0) {
                lp.width = lp.height = size;
                lp.gravity = Gravity.CENTER;
                lp.topMargin = lp.bottomMargin = lp.leftMargin = lp.rightMargin = 0;
                frame.setLayoutParams(lp);
            }
            material.setCornerRadius(size / 2f);
            if (codeGlyph != null) codeGlyph.setLayoutParams(new FrameLayout.LayoutParams(
                    dp(system, 36), dp(system, 36), Gravity.CENTER));
        }
    }

    private static void settleIcon(Drawable drawable) {
        if (drawable instanceof AnimatedVectorDrawable) {
            // Our independent tile has no OEM panel animator. AVD.stop ends its
            // animator, so the native complete symbol replaces the collapsed start frame.
            AnimatedVectorDrawable vector = (AnimatedVectorDrawable) drawable;
            vector.start(); vector.stop();
        } else if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) settleIcon(layers.getDrawable(i));
        } else if (drawable instanceof DrawableWrapper) {
            settleIcon(((DrawableWrapper) drawable).getDrawable());
        } else if (drawable != null && drawable.getCurrent() != drawable) {
            settleIcon(drawable.getCurrent());
        }
    }

    private static GradientDrawable copyShape(Drawable original, Context context) {
        Drawable copy = original != null && original.getConstantState() != null
                ? original.getConstantState().newDrawable().mutate() : null;
        if (copy instanceof GradientDrawable) return (GradientDrawable) copy;
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 20));
        return shape;
    }

    static Object simpleState(Context system, String label, boolean active, String... icons) {
        ClassLoader loader = system.getClassLoader();
        Object state = XposedHelpers.newInstance(XposedHelpers.findClass("com.android.systemui.plugins.qs.QSTile$BooleanState", loader));
        XposedHelpers.setIntField(state, "state", active ? 2 : 1);
        XposedHelpers.setBooleanField(state, "value", active);
        XposedHelpers.setObjectField(state, "label", label);
        XposedHelpers.setObjectField(state, "contentDescription", label);
        XposedHelpers.setObjectField(state, "expandedAccessibilityClassName", "android.widget.Button");
        for (String icon : icons) {
            int id = system.getResources().getIdentifier(icon, "drawable", "com.android.systemui");
            if (id != 0) {
                Object resource = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                        "com.android.systemui.qs.tileimpl.QSTileImpl$ResourceIcon", loader), "get", id);
                XposedHelpers.setObjectField(state, "icon", resource);
                break;
            }
        }
        return state;
    }

    static final class Slider {
        final ViewGroup root;
        final SeekBar slider;
        final View progress, background;
        final TextView text;
        final ImageView icon;
        final Context system;
        final boolean isVolume;
        final Drawable fill;
        final Drawable normalIcon, mutedIcon;
        private Boolean muted;
        Slider(Context system, String title) {
            this.system = system;
            isVolume = "音量".equals(title);
            Object binding = inflate("ToggleSliderItemViewBinding");
            root = (ViewGroup) root(binding);
            slider = (SeekBar) field(binding, "slider");
            progress = field(binding, "progress");
            background = field(binding, "progressBg");
            GradientDrawable base = copyShape(background.getBackground(), system);
            base.setColor(HyperOsStyle.controlFill(system, false));
            base.setCornerRadius(cornerRadius(system));
            background.setBackground(base);
            // Draw the changing filled rectangle itself. Clipping a full-height
            // background leaves a straight edge on OEM progress implementations.
            fill = new Drawable() {
                final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                final RectF rect = new RectF();
                { paint.setColor(0xfff5f7fa); }
                @Override public void draw(Canvas canvas) {
                    float fraction = Math.max(0f, Math.min(1f,
                            slider.getProgress() / (float) Math.max(1, slider.getMax())));
                    if (fraction <= 0f) return;
                    rect.set(getBounds());
                    rect.top = rect.bottom - rect.height() * fraction;
                    float radius = ControlCenterGeometry.progressCapRadius(
                            rect.width(), rect.height(), cornerRadius(system));
                    canvas.drawRoundRect(rect, radius, radius, paint);
                }
                @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
                @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
                @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
            };
            progress.setBackground(fill);
            text = (TextView) field(binding, "topText");
            text.setVisibility(View.VISIBLE);
            text.setText(title);
            configureCaption(system, text);
            text.setTextColor(HyperOsStyle.textSecondary(system));
            text.setLayoutParams(new FrameLayout.LayoutParams(-1, dp(system, 24), Gravity.TOP));
            text.setPadding(0, dp(system, 8), 0, 0);
            root.setContentDescription(title);
            root.setClipToOutline(true);
            root.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(system));
                }
            });
            slider.setContentDescription(title);
            View inner = field(binding, "toggleSliderInner");
            inner.setClipToOutline(true);
            inner.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius(system));
                }
            });
            progress.setClipToOutline(false);
            configureSliderDrag(slider, root);
            icon = (ImageView) field(binding, "icon");
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(system, 22), dp(system, 22), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            iconLp.bottomMargin = dp(system, 12);
            icon.setLayoutParams(iconLp);
            icon.setVisibility(View.VISIBLE);
            icon.setAlpha(1f);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Context plugin = context();
            normalIcon = MiStepNativeIconContext.drawable(plugin,
                    isVolume ? "ic_volume_slider_media_enabled" : "ic_brightness_slider",
                    isVolume ? "ic_volume_slider_animate_icon" : "ic_brightness_slider_animate_icon");
            mutedIcon = isVolume ? MiStepNativeIconContext.drawable(plugin,
                    "ic_volume_slider_media_disabled", "ic_miui_volume_media_mute") : null;
            updateIcon(false);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xff242930));
            // OEM sliders rotate 270 degrees and swap physical width/height (holder.updateSize).
            root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) slider.getLayoutParams();
                int width = Math.max(1, r-l), height = Math.max(1, b-t);
                if (lp.width != height || lp.height != width) {
                    lp.width = height; lp.height = width; lp.gravity = Gravity.CENTER;
                    slider.setLayoutParams(lp);
                }
                base.setCornerRadius(cornerRadius(system));
                root.invalidateOutline(); inner.invalidateOutline(); updateProgressShape();
            });
        }
        void value(int value, String label) {
            slider.setProgress(value);
            updateProgressShape();
            text.setText(label);
            if (isVolume) updateIcon(value == 0);
        }
        private void updateIcon(boolean silent) {
            if (muted != null && muted == silent) return;
            muted = silent;
            Drawable drawable = silent && mutedIcon != null ? mutedIcon : normalIcon;
            if (drawable != null) { icon.setImageDrawable(drawable); settleIcon(drawable); }
        }
        void updateProgressShape() { fill.invalidateSelf(); progress.invalidate(); }
        void release() {
            slider.setOnSeekBarChangeListener(null);
            try { releaseSliderDrag(slider); }
            catch (Throwable ignored) {}
        }
    }

    private static void configureSliderDrag(View slider, View root) {
        if (ShellCompat.hasMethod(slider.getClass(), "setDragView", 1))
            XposedHelpers.callMethod(slider, "setDragView", root);
        else XposedHelpers.callMethod(XposedHelpers.callMethod(slider, "getDragAnim"), "setDragView", root);
    }

    private static void releaseSliderDrag(View slider) {
        if (ShellCompat.hasMethod(slider.getClass(), "resetTouchAnimNow", 0))
            XposedHelpers.callMethod(slider, "resetTouchAnimNow");
        else XposedHelpers.callMethod(XposedHelpers.callMethod(slider, "getDragAnim"), "cleanAnim");
    }

    static int cornerRadius(Context context) { return dp(context, 22); }

    static void failed(String label, Throwable error) {
        if (failures-- > 0) XposedBridge.log("[Mi Step] OS4 controls " + label + ": " + error);
    }
    private static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
