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

/** Independent views from the already loaded OS4 plugin, never reparent the system's views. */
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
                        Object data = XposedHelpers.getObjectField(p.thisObject, "pluginData");
                        if (data == null) return;
                        Context context = (Context) XposedHelpers.getObjectField(data, "context");
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
                        Object data = XposedHelpers.getObjectField(p.thisObject, "pluginData");
                        if (data != null && XposedHelpers.getObjectField(data, "context") == plugin.get())
                            plugin.clear();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable e) { failed("install", e); }
    }

    static Context context() {
        Context context = plugin.get();
        if (context == null) throw new IllegalStateException("OS4 control plugin not loaded");
        return context;
    }

    static Object inflate(String bindingName) {
        Context context = context();
        Class<?> binding = XposedHelpers.findClass("miui.systemui.controlcenter.databinding." + bindingName,
                context.getClassLoader());
        return XposedHelpers.callStaticMethod(binding, "inflate", LayoutInflater.from(context));
    }

    static View field(Object object, String name) { return (View) XposedHelpers.getObjectField(object, name); }

    static View tile(Context system, String label, Runnable action) {
        return tile(system, label, action, false);
    }

    static View tile(Context system, String label, Runnable action, boolean iconOnly) {
        Context context = context();
        Object binding = inflate("QsTileItemViewBinding");
        ViewGroup root = (ViewGroup) XposedHelpers.callMethod(binding, "getRoot");
        Class<?> iconType = XposedHelpers.findClass("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", context.getClassLoader());
        View icon = (View) XposedHelpers.newInstance(iconType, context, system, false, false);
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
        text.setTextSize(9);
        text.setTextColor(HyperOsStyle.textPrimary(system));
        text.setSingleLine(true);
        text.setIncludeFontPadding(false);
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
            int top = Math.max(0, ((b - t) - dp(system, 54)) / 2);
            FrameLayout.LayoutParams circleLp = (FrameLayout.LayoutParams) frame.getLayoutParams();
            if (circleLp.topMargin != top) {
                circleLp.topMargin = top;
                frame.setLayoutParams(circleLp);
            }
            FrameLayout.LayoutParams captionLp = (FrameLayout.LayoutParams) text.getLayoutParams();
            int captionTop = top + dp(system, 40);
            if (captionLp.topMargin != captionTop) {
                captionLp.topMargin = captionTop;
                text.setLayoutParams(captionLp);
            }
            TileStyle style = (TileStyle) root.getTag();
            if (style != null && style.codeImage != null) {
                FrameLayout.LayoutParams glyphLp =
                        new FrameLayout.LayoutParams(dp(system, 36), dp(system, 36),
                                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                glyphLp.topMargin = top;
                style.codeImage.setLayoutParams(glyphLp);
                style.codeImage.bringToFront();
            }
        });
        icon.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        icon.setClickable(false);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.setTag(new TileStyle(system, label, icon, frame, root, action, iconOnly));
        ((TileStyle) root.getTag()).apply(root, false);
        return root;
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
        final GradientDrawable material;
        final View codeImage;
        final boolean iconOnly;
        TileStyle(Context system, String label, View icon, View frame, View root,
                Runnable action, boolean iconOnly) {
            this.system = system; this.label = label; this.icon = icon; this.frame = frame;
            this.root = root;
            this.iconOnly = iconOnly;
            Object source = XposedHelpers.getObjectField(icon, "disabledBg");
            material = copyShape(source instanceof Drawable ? (Drawable) source : null, system);
            if ("付款码".equals(label) || "乘车码".equals(label)) {
                // Keep custom strokes outside the OEM QS image blend/animation pipeline.
                codeImage = new MiStepCodeIcon.GlyphView(system, "乘车码".equals(label));
                codeImage.setOnClickListener(v -> action.run());
                codeImage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                // OEM QSTileItemIconView paints its own layers over child views.
                // Draw our glyph as a later sibling above the frame instead.
                ((ViewGroup) root).addView(codeImage, new FrameLayout.LayoutParams(
                        dp(system, 36), dp(system, 36),
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            } else codeImage = null;
        }
        void apply(View root, boolean active) {
            if (iconOnly) fitIconOnly();
            // Native blend tokens need the OEM panel blur provider. Our window supplies a
            // standalone material behind the unchanged OEM view and glyph instead.
            material.setColor(active ? 0xff3478ed : 0xb25d6875);
            material.setCornerRadius(iconOnly ? Math.max(1, Math.min(root.getWidth(), root.getHeight())) / 2f : dp(system, 18));
            frame.setBackground(material);
            root.setBackground(null);
            root.setElevation(0);
            icon.setVisibility(codeImage == null ? View.VISIBLE : View.INVISIBLE);
            if (codeImage != null) {
                codeImage.setVisibility(View.VISIBLE);
                codeImage.bringToFront();
                TextView text = (TextView) field(XposedHelpers.callMethod(root, "getBinding"), "tileLabel");
                text.setTextColor(HyperOsStyle.textPrimary(system));
                return;
            }
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
            TextView text = (TextView) field(XposedHelpers.callMethod(root, "getBinding"), "tileLabel");
            text.setTextColor("扫一扫".equals(label) ? HyperOsStyle.textPrimary(system) : 0xffffffff);
        }

        void fitIconOnly() {
            field(XposedHelpers.callMethod(root, "getBinding"), "tileLabel").setVisibility(View.GONE);
            int size = Math.max(1, Math.min(root.getWidth(), root.getHeight()));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
            if (lp.width != size || lp.height != size || lp.gravity != Gravity.CENTER || lp.topMargin != 0) {
                lp.width = lp.height = size;
                lp.gravity = Gravity.CENTER;
                lp.topMargin = lp.bottomMargin = lp.leftMargin = lp.rightMargin = 0;
                frame.setLayoutParams(lp);
            }
            material.setCornerRadius(size / 2f);
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
        Slider(Context system, String title, String iconName) {
            this.system = system;
            isVolume = "音量".equals(title);
            Object binding = inflate("ToggleSliderItemViewBinding");
            root = (ViewGroup) XposedHelpers.callMethod(binding, "getRoot");
            slider = (SeekBar) field(binding, "slider");
            progress = field(binding, "progress");
            background = field(binding, "progressBg");
            GradientDrawable base = copyShape(background.getBackground(), system);
            base.setColor(0xb2353f49);
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
            text.setTextSize(9);
            text.setTextColor(0xfff2f4f8);
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
            XposedHelpers.callMethod(slider, "setDragView", root);
            icon = (ImageView) field(binding, "icon");
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(system, 22), dp(system, 22), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            iconLp.bottomMargin = dp(system, 12);
            icon.setLayoutParams(iconLp);
            Context context = context();
            int id = context.getResources().getIdentifier(iconName, "drawable", context.getPackageName());
            if (id != 0) icon.setImageDrawable(context.getDrawable(id));
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
            if (isVolume) {
                String name = value == 0 ? "ic_volume_media_mute" : "ic_volume_media";
                int id = system.getResources().getIdentifier(name, "drawable", "com.android.systemui");
                if (id != 0) icon.setImageDrawable(system.getDrawable(id));
            }
        }
        void updateProgressShape() { fill.invalidateSelf(); progress.invalidate(); }
        void release() {
            slider.setOnSeekBarChangeListener(null);
            try { XposedHelpers.callMethod(slider, "resetTouchAnimNow"); }
            catch (Throwable ignored) {}
        }
    }

    static int cornerRadius(Context context) { return dp(context, 22); }

    static void failed(String label, Throwable error) {
        if (failures-- > 0) XposedBridge.log("[Mi Step] OS4 controls " + label + ": " + error);
    }
    private static int dp(Context context, float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
