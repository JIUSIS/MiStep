package com.jianguo.mistep;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Process;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.widget.Toast;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** User-selected, package-scoped scanner entry. Prefer the app's own published shortcut. */
final class MiStepScanner {
    private static final String[] PACKAGES = {"com.tencent.mm", "com.eg.android.AlipayGphone"};
    private static final String[] NAMES = {"微信", "支付宝"};
    private static Choice chooser;

    private static final class Choice {
        final FrameLayout layer;
        final LinearLayout panel;
        final MiStepTopBar host;
        boolean closing, removed;
        Runnable completion;
        Choice(FrameLayout layer, LinearLayout panel, MiStepTopBar host) {
            this.layer = layer; this.panel = panel; this.host = host;
        }
        void dismiss(Runnable action, boolean animate) {
            if (removed || closing && animate) return;
            closing = true;
            host.coverCodeButtons(false, animate);
            if (action != null) completion = action;
            layer.setOnClickListener(null);
            for (int i = 0; i < panel.getChildCount(); i++) panel.getChildAt(i).setEnabled(false);
            panel.animate().withEndAction(null).cancel();
            if (animate && panel.isAttachedToWindow()) {
                panel.animate().alpha(0f).scaleX(.94f).scaleY(.94f).setDuration(140)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(this::finish).start();
            } else finish();
        }
        void finish() {
            if (removed) return;
            removed = true;
            if (chooser == this) chooser = null;
            if (layer.getParent() instanceof ViewGroup) ((ViewGroup) layer.getParent()).removeView(layer);
            if (completion != null) { Runnable action = completion; completion = null; action.run(); }
        }
    }

    static void choose(Context context, View anchor, IntConsumer selected) {
        choose(context, anchor, "扫一扫", selected);
    }

    static void choose(Context context, View anchor, String title, IntConsumer selected) {
        if (chooser != null) { close(); return; }
        for (View view = anchor; view != null;) {
            if (view instanceof MiStepTopBar) {
                ((MiStepTopBar) view).scannerBubble(anchor, title, selected); return;
            }
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        message(context, "请从一步功能区打开扫一扫");
    }

    static void bubble(MiStepTopBar host, View anchor, Rect area, Bitmap wallpaper,
            int screenWidth, int screenHeight, String label, IntConsumer selected) {
        closeImmediately();
        if (area.isEmpty() || !host.isAttachedToWindow()) return;
        Context context = host.getContext();
        FrameLayout layer = new FrameLayout(context);
        layer.setOnClickListener(v -> close());
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        int padding = dp(context, 4);
        panel.setPadding(padding, padding, padding, padding);
        panel.setOnClickListener(v -> {});
        panel.setElevation(dp(context, 6));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(0xff657178);
        background.setCornerRadius(area.height() / 2f);
        background.setStroke(Math.max(1, dp(context, .5f)), HyperOsStyle.stroke(context));
        panel.setBackground(background);
        panel.setClipToOutline(true);
        for (int which : new int[]{1, 0, 2}) {
            boolean available = which == 2 || installed(context, PACKAGES[which]);
            String provider = which == 2 ? "钱包" : NAMES[which];
            ProviderButton button = new ProviderButton(context, which, provider, available);
            button.setOnClickListener(v -> {
                if (!available) { message(context, "请先安装" + NAMES[which]); return; }
                Choice current = chooser;
                if (current != null) current.dismiss(() -> selected.accept(which), true);
            });
            panel.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
        }
        FrameLayout.LayoutParams card = new FrameLayout.LayoutParams(area.width(), area.height());
        card.leftMargin = area.left; card.topMargin = area.top;
        layer.addView(panel, card);
        chooser = new Choice(layer, panel, host);
        host.coverCodeButtons(true, true);
        host.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        panel.setPivotX(area.width() / 2f); panel.setPivotY(area.height() / 2f);
        panel.setAlpha(0f); panel.setScaleX(.94f); panel.setScaleY(.94f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }

    static void close() { if (chooser != null) chooser.dismiss(null, true); }
    static void closeImmediately() { if (chooser != null) chooser.dismiss(null, false); }

    private static final class ProviderButton extends FrameLayout {
        final View circle;
        ProviderButton(Context context, int provider, String name, boolean available) {
            super(context);
            setContentDescription(name + (available ? "" : "，未安装"));
            setBackground(HyperOsStyle.pressableItem(context, 24));
            final int color = 0xff7d888f;
            circle = new View(context) {
                final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                protected void onDraw(android.graphics.Canvas canvas) {
                    int saved = canvas.save();
                    float size = Math.min(getWidth(), getHeight()) * .6f;
                    canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
                    canvas.scale(size / 24, size / 24);
                    paint.setColor(0xffffffff); paint.setStyle(android.graphics.Paint.Style.FILL);
                    if (provider == 1) {
                        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); paint.setTextSize(22);
                        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
                        canvas.drawText("支", 12, 20, paint);
                    } else if (provider == 0) {
                        canvas.drawOval(1, 3, 16, 15, paint); canvas.drawOval(9, 10, 23, 21, paint);
                        android.graphics.Path tails = new android.graphics.Path();
                        tails.moveTo(4, 12); tails.lineTo(3, 17); tails.lineTo(9, 14);
                        tails.moveTo(17, 19); tails.lineTo(21, 23); tails.lineTo(20, 17);
                        canvas.drawPath(tails, paint);
                        paint.setColor(color);
                        for (float[] point : new float[][]{{6,8},{11,8},{13,14.5f},{18,14.5f}})
                            canvas.drawCircle(point[0], point[1], 1, paint);
                    } else {
                        paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(1.8f);
                        android.graphics.Path wallet = new android.graphics.Path();
                        wallet.addRoundRect(2, 5, 22, 20, 3, 3, android.graphics.Path.Direction.CW);
                        canvas.drawPath(wallet, paint); canvas.drawLine(3, 9, 21, 9, paint);
                        canvas.drawLine(16, 12, 22, 12, paint); canvas.drawLine(16, 16, 22, 16, paint);
                        canvas.drawLine(16, 12, 16, 16, paint);
                    }
                    canvas.restoreToCount(saved);
                }
            };
            android.graphics.drawable.GradientDrawable disc = new android.graphics.drawable.GradientDrawable();
            disc.setShape(android.graphics.drawable.GradientDrawable.OVAL); disc.setColor(color);
            circle.setBackground(disc); circle.setAlpha(available ? 1f : .5f);
            circle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(circle, new FrameLayout.LayoutParams(1, 1));
        }
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec), height = View.MeasureSpec.getSize(heightSpec);
            int diameter = Math.max(1, Math.min(width - dp(getContext(), 8), height - dp(getContext(), 8)));
            FrameLayout.LayoutParams icon = (FrameLayout.LayoutParams) circle.getLayoutParams();
            icon.width = icon.height = diameter; icon.leftMargin = (width - diameter) / 2;
            icon.topMargin = (height - diameter) / 2;
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private static int dp(Context context, float value) { return Math.round(HyperOsStyle.dp(context, value)); }

    static void launch(Context context, int selection) {
        if (selection < 0 || selection >= PACKAGES.length) return;
        String pkg = PACKAGES[selection];
        if (!installed(context, pkg)) { message(context, "请先安装" + NAMES[selection]); return; }
        if (shortcut(context, pkg)) return;
        Intent intent;
        if (selection == 0) {
            intent = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg,
                    "com.tencent.mm.ui.LauncherUI"));
            intent.putExtra("LauncherUI.From.Scaner.Shortcut", true);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        } else {
            intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("alipays://platformapi/startapp?appId=10000007")).setPackage(pkg);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            message(context, NAMES[selection] + "扫一扫暂不可用"); return;
        }
        try { context.startActivity(intent); }
        catch (RuntimeException error) { message(context, "无法打开" + NAMES[selection] + "扫一扫"); }
    }

    private static boolean installed(Context context, String pkg) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            return info.enabled;
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) { return false; }
    }

    private static boolean shortcut(Context context, String pkg) {
        try {
            LauncherApps launcher = context.getSystemService(LauncherApps.class);
            if (launcher == null || !launcher.hasShortcutHostPermission()) return false;
            List<ShortcutInfo> shortcuts = launcher.getShortcuts(new LauncherApps.ShortcutQuery()
                    .setPackage(pkg).setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                            | LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED), Process.myUserHandle());
            if (shortcuts == null) return false;
            for (ShortcutInfo info : shortcuts) {
                if (!info.isEnabled() || !pkg.equals(info.getPackage()) || !scannerLabel(info)) continue;
                launcher.startShortcut(pkg, info.getId(), null, null, info.getUserHandle());
                return true;
            }
        } catch (RuntimeException unavailable) { /* Use the package-scoped app entry below. */ }
        return false;
    }

    private static boolean scannerLabel(ShortcutInfo info) {
        String label = String.valueOf(info.getShortLabel()).toLowerCase(Locale.ROOT);
        return label.contains("扫一扫") || label.equals("scan") || label.equals("scan qr code")
                || label.equals("scan qr") || label.equals("scan qr-code");
    }

    private static void message(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
}
