package com.jiusis.mistep;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** Clipboard history displayed inside the host's unused upper strip. */
final class MiStepClipboard {
    private static final String ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private static MiStepClipboard instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ClipboardHistory history = new ClipboardHistory();
    private final ClipboardManager.OnPrimaryClipChangedListener listener = () -> main.post(this::record);
    private ClipboardManager clipboard;
    private ViewGroup owner;
    private FrameLayout popup;
    private LinearLayout popupPanel;
    private android.graphics.drawable.Drawable popupGlass;
    private boolean closing;
    private long popupGeneration;

    static void start(Context context) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (instance == null) instance = new MiStepClipboard(context);
        });
    }

    static MiStepClipboard get(Context context) {
        if (instance == null) instance = new MiStepClipboard(context);
        return instance;
    }

    private MiStepClipboard(Context context) {
        this.context = context;
        attachUser();
        IntentFilter filter = new IntentFilter(ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                close();
                if (ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    history.clear();
                    attachUser();
                }
            }
        }, filter);
    }

    private void attachUser() {
        try {
            if (clipboard != null) clipboard.removePrimaryClipChangedListener(listener);
            int user = (Integer) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityManager"), "getCurrentUser");
            Context userContext = (Context) XposedHelpers.callMethod(context, "createContextAsUser",
                    XposedHelpers.callStaticMethod(UserHandle.class, "of", user), 0);
            clipboard = (ClipboardManager) userContext.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.addPrimaryClipChangedListener(listener);
            record();
        } catch (Throwable ignored) { clipboard = null; }
    }

    private boolean locked() {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private void record() {
        if (clipboard == null || locked()) return;
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() != 1) return;
            PersistableBundle extras = clip.getDescription().getExtras();
            boolean sensitive = extras != null && extras.getBoolean("android.content.extra.IS_SENSITIVE", false);
            CharSequence text = clip.getItemAt(0).getText();
            history.add(text == null ? null : text.toString(), sensitive);
        } catch (Throwable ignored) { /* Access can be denied during a user or lock transition. */ }
    }

    void toggle(ViewGroup host, Rect area, Bitmap backdrop, int screenWidth, int screenHeight) {
        if (popup != null) {
            if (closing && popupPanel != null) animateOpen(popupPanel);
            else dismissAnimated();
            return;
        }
        if (locked() || host == null || area == null || area.isEmpty()) return;
        record();
        FrameLayout container = new FrameLayout(context);
        container.setClipChildren(true);
        container.setClipToPadding(true);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(4), dp(12), dp(8));
        panel.setOnClickListener(v -> {});
        android.graphics.drawable.Drawable glass = HyperOsStyle.glassSheet(context, 20f);
        panel.setBackground(glass);
        container.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        int panelWidth = Math.min(area.width(), dp(304));
        FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(panelWidth, area.height());
        bounds.leftMargin = area.left + (area.width() - panelWidth) / 2;
        bounds.topMargin = area.top;

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("剪贴板", 14);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(30), 1));
        TextView clear = label("清空", 12);
        clear.setPadding(dp(10), 0, dp(10), 0);
        clear.setOnClickListener(v -> { history.clear(); render(panel, header); });
        header.addView(clear, new LinearLayout.LayoutParams(-2, dp(30)));
        MiStepIconButton close = new MiStepIconButton(context, MiStepIconButton.EXIT, "关闭剪贴板");
        close.setOnClickListener(v -> dismissAnimated());
        header.addView(close, new LinearLayout.LayoutParams(dp(30), dp(30)));
        render(panel, header);
        try {
            host.addView(container, bounds);
            owner = host;
            popup = container;
            popupPanel = panel;
            popupGlass = glass;
            panel.setAlpha(0f);
            panel.setTranslationY(-area.height());
            panel.post(() -> { if (popup == container) animateOpen(panel); });
        } catch (Throwable ignored) {
            Toast.makeText(context, "剪贴板暂时不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private void render(LinearLayout panel, LinearLayout header) {
        panel.removeAllViews();
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(30)));
        ScrollView scroll = new ScrollView(context);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        List<String> texts = history.snapshot();
        if (texts.isEmpty()) rows.addView(label("暂无文本记录", 12), new LinearLayout.LayoutParams(-1, dp(48)));
        for (String value : texts) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView text = label(value, 12);
            text.setMaxLines(4);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setPadding(0, dp(8), dp(8), dp(8));
            text.setOnClickListener(v -> {
                try {
                    if (clipboard == null || locked()) return;
                    clipboard.setPrimaryClip(ClipData.newPlainText("Mi Step", value));
                    dismissAnimated();
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                    Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show();
                }
            });
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView remove = label("删除", 11);
            remove.setOnClickListener(v -> { history.remove(value); render(panel, header); });
            row.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(48)));
            rows.addView(row);
            View line = new View(context);
            line.setBackgroundColor(0x20777777);
            rows.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(HyperOsStyle.textPrimary(context));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    void close() {
        if (popup == null) return;
        FrameLayout old = popup;
        ++popupGeneration;
        if (popupPanel != null) popupPanel.animate().cancel();
        popup = null;
        popupPanel = null;
        popupGlass = null;
        closing = false;
        ViewGroup parent = owner;
        owner = null;
        try { if (parent != null) parent.removeView(old); } catch (Throwable ignored) {}
    }

    private void animateOpen(LinearLayout panel) {
        if (popup == null || popupPanel != panel || locked()) { close(); return; }
        ++popupGeneration;
        closing = false;
        panel.animate().cancel();
        panel.animate().alpha(1f).translationY(0f)
                .setDuration(220L).setInterpolator(new PathInterpolator(.2f, 0f, .1f, 1f))
                .withEndAction(null).start();
    }

    private void dismissAnimated() {
        if (popup == null || popupPanel == null || closing) return;
        if (locked()) { close(); return; }
        FrameLayout expected = popup;
        long generation = ++popupGeneration;
        closing = true;
        popupPanel.animate().cancel();
        popupPanel.animate().alpha(0f).translationY(-Math.max(1, popup.getHeight()))
                .setDuration(150L).withEndAction(() -> {
                    if (popup == expected && popupGeneration == generation) close();
                }).start();
    }

    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
