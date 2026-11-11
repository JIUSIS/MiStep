package com.jiusis.mistep;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings use the installed system Settings resources and layout proportions. */
final class WorkspaceSettingsPage extends FrameLayout {
    private static final int HOME = 0, CONTROL = 1, HEADER = 2, SWAP = 3, LOGS = 4, GESTURE = 5,
            ABOUT = 6, DONATE = 7;
    private static final int BLUE = HyperOsStyle.CONTROL_ACCENT;
    private final SettingsActivity activity;
    private final boolean dark;
    private final int primary, secondary, surface;
    private final int[] scrollPositions = new int[8];
    private final SharedPreferences aboutPreferences;
    private int donationMethod;
    private boolean donationTemporarilyHidden;
    private Dialog donationDialog;
    private final RuntimeLogController runtimeLogs;
    private TextView logStatus, logPreview;
    private View refreshLogs, exportLogs;
    private TextView recordLogs;
    private final RadioButton[] choices = new RadioButton[2];
    private final boolean[] nativeChoices = new boolean[2];
    private final android.database.ContentObserver gestureObserver =
            new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            if (screen == HOME || screen == GESTURE) show(screen, false);
        }
    };
    private int screen;
    private ScrollView scroll;
    private LinearLayout content;

    WorkspaceSettingsPage(SettingsActivity activity, Bundle state, RuntimeLogController runtimeLogs) {
        super(activity);
        this.activity = activity;
        this.runtimeLogs = runtimeLogs;
        aboutPreferences = activity.createDeviceProtectedStorageContext()
                .getSharedPreferences("about_settings", android.content.Context.MODE_PRIVATE);
        donationMethod = state == null ? 0 : state.getInt("donation_method", 0);
        donationTemporarilyHidden = state != null && state.getBoolean("donation_temporary_hidden", false);
        dark = (getResources().getConfiguration().uiMode & 48) == 32;
        primary = activity.settingsColor("miuix_preference_primary_text_color_" + (dark ? "dark" : "light"),
                dark ? 0xfff4f5f8 : 0xff000000);
        secondary = activity.settingsColor("miuix_preference_secondary_text_color_" + (dark ? "dark" : "light"),
                dark ? 0xff999ca7 : 0xff999999);
        surface = dark ? 0xff1b1d23 : 0xffffffff;
        if (state != null) {
            int[] saved = state.getIntArray("settings_scroll");
            if (saved != null && saved.length == scrollPositions.length)
                System.arraycopy(saved, 0, scrollPositions, 0, saved.length);
        }
        int restored = state == null ? HOME : state.getInt("settings_page", HOME);
        show(restored >= HOME && restored <= DONATE ? restored : HOME, false);
    }

    void save(Bundle state) {
        scrollPositions[screen] = scroll.getScrollY();
        state.putInt("settings_page", screen);
        state.putIntArray("settings_scroll", scrollPositions);
        state.putInt("donation_method", donationMethod);
        state.putBoolean("donation_temporary_hidden", donationTemporarilyHidden);
    }

    boolean goBack() {
        if (screen == HOME) return false;
        show(screen == DONATE ? ABOUT : HOME, true);
        return true;
    }

    private void show(int next, boolean animate) {
        if (next == DONATE && donationHidden()) next = ABOUT;
        if (scroll != null) scrollPositions[screen] = scroll.getScrollY();
        screen = next;
        removeAllViews();
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        FrameLayout centered = new FrameLayout(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(4), dp(20), dp(32));
        int width = getResources().getDisplayMetrics().widthPixels;
        centered.addView(content, new FrameLayout.LayoutParams(width > dp(680) ? dp(680) : -1,
                -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        scroll.addView(centered, new ScrollView.LayoutParams(-1, -1));
        addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        String title = next == CONTROL ? "控制中心按钮" : next == HEADER ? "标题栏按钮"
                : next == SWAP ? "主副交换逻辑" : next == LOGS ? "运行日志"
                : next == GESTURE ? "唤出手势" : next == ABOUT ? "关于 MiStep"
                : next == DONATE ? "支持 MiStep" : "Mi Step";
        activity.setPageTitle(title);
        if (next == HOME) home();
        else if (next == LOGS) logs();
        else if (next == SWAP) swap();
        else if (next == GESTURE) gesture();
        else if (next == ABOUT) about();
        else if (next == DONATE) donate();
        else buttons(next == CONTROL ? WorkspaceOptions.CONTROL_IDS : WorkspaceOptions.HEADER_IDS);
        int restoreY = scrollPositions[next];
        scroll.post(() -> scroll.scrollTo(0, restoreY));
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            content.setAlpha(0f);
            content.animate().alpha(1f).setDuration(160).start();
        }
    }

    private void home() {
        WorkspaceOptions options = WorkspaceSettingsStore.read(activity);
        section("界面");
        LinearLayout appearance = card();
        menu(appearance, "控制中心按钮", options.visible(WorkspaceOptions.CONTROL_CENTER)
                ? visibleCount(options, WorkspaceOptions.CONTROL_IDS, 1) + " 项已显示" : "已隐藏", () -> show(CONTROL, true));
        divider(appearance);
        menu(appearance, "标题栏按钮", visibleCount(options, WorkspaceOptions.HEADER_IDS, 0)
                + " 项已显示", () -> show(HEADER, true));
        add(appearance, 0, 12);
        section("交互");
        LinearLayout interaction = card();
        menu(interaction, "主副交换逻辑", WorkspaceOptions.modeLabel(options.swapMode), () -> show(SWAP, true));
        divider(interaction);
        menu(interaction, "唤出手势", gestureLabel(EntryGesturePolicy.effectiveMode(
                        options.entryGesture, EntryGestureSettings.lineHidden(activity))),
                () -> show(GESTURE, true));
        divider(interaction);
        LinearLayout lockRow = row();
        TextView lockLabel = text("锁屏时保留 MiStep", 17, primary);
        lockLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        lockRow.addView(lockLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Switch keepOnLock = settingsSwitch("锁屏时保留 MiStep", options.keepOnLock);
        lockRow.addView(keepOnLock, new LinearLayout.LayoutParams(-2, -2));
        keepOnLock.setOnCheckedChangeListener((button, checked) -> {
            WorkspaceOptions current = WorkspaceSettingsStore.read(activity);
            if (current.keepOnLock == checked) return;
            if (!persist(current.withKeepOnLock(checked))) button.setChecked(!checked);
        });
        lockRow.setOnClickListener(v -> keepOnLock.toggle());
        interaction.addView(lockRow);
        add(interaction, 0, 12);
        section("诊断");
        LinearLayout diagnostics = card();
        menu(diagnostics, "运行日志", "诊断信息与问题反馈", () -> show(LOGS, true));
        add(diagnostics, 0, 0);
        LinearLayout about = card();
        menu(about, "关于 MiStep", "项目与作者", () -> show(ABOUT, true));
        add(about, 12, 0);
    }

    private void about() {
        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(activity.getApplicationInfo().loadIcon(activity.getPackageManager()));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(76), dp(76));
        iconSize.gravity = Gravity.CENTER_HORIZONTAL;
        iconSize.topMargin = dp(16); iconSize.bottomMargin = dp(14);
        content.addView(icon, iconSize);
        TextView name = text("MiStep", 28, primary);
        name.setTypeface(Typeface.create("sans-serif-medium", 0));
        name.setGravity(Gravity.CENTER);
        name.setAccessibilityHeading(true);
        add(name, 0, 6);
        String version = "";
        try { version = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        TextView versionLabel = text(version, 13, secondary);
        versionLabel.setGravity(Gravity.CENTER);
        add(versionLabel, 0, 22);
        TextView intro = text("让多任务，自在一步。", 19, primary);
        intro.setGravity(Gravity.CENTER);
        add(intro, 0, 10);
        TextView description = text("灵感来自锤子「一步」，为小米带来主区域与多个真实副任务协同的工作区，让应用之间的切换更从容。", 14, secondary);
        description.setGravity(Gravity.CENTER);
        description.setPadding(dp(12), 0, dp(12), 0);
        description.setLineSpacing(dp(5), 1f);
        add(description, 0, 22);
        LinearLayout project = card();
        menu(project, "项目仓库", "GitHub · JIUSIS / MiStep",
                () -> openLink("https://github.com/JIUSIS/MiStep"));
        add(project, 0, 12);
        section("作者 · 君九思 JIUSI");
        LinearLayout author = card();
        menu(author, "酷安", "作者主页", () -> openLink("https://www.coolapk.com/u/652464"));
        divider(author);
        menu(author, "哔哩哔哩", "君九思JIUSI", () -> openLink("https://b23.tv/0jlB8Vb"));
        divider(author);
        menu(author, "抖音", "作者动态",
                () -> openLink("https://www.iesdouyin.com/share/user/MS4wLjABAAAAwO_VlPNVbWRMdL5IKWyZmlYsgQWp1F7242NaGaChouEgyhgSH3A1vufR90CNWshx?u_code=4af0205jm7b6&did=MS4wLjABAAAAcPUqe1yfW2XQDXt_6aKgTqt-iCG5oBjpQ1-IXRV42TiF3OQp2aRnEMkkL9D98zEJ&iid=MS4wLjABAAAABcarNUFlFQX1acVALzLVSpihiFYInZAz1hvCeN41hTtxk8oCmzazMurlvhbTkIfi&with_sec_did=1&sec_uid=MS4wLjABAAAAwO_VlPNVbWRMdL5IKWyZmlYsgQWp1F7242NaGaChouEgyhgSH3A1vufR90CNWshx&from_ssr=1&from_aid=1128&timestamp=1794330190"));
        add(author, 0, 12);
        if (!donationHidden()) {
            LinearLayout support = card();
            LinearLayout entry = menu(support, "捐赠", "赛博乞讨", () -> show(DONATE, true));
            entry.setOnLongClickListener(v -> {
                showDonationDialog(false);
                return true;
            });
            add(support, 0, 10);
            TextView hint = text("纯为爱发电 · 长按捐赠入口可关闭", 12, secondary);
            hint.setGravity(Gravity.CENTER);
            add(hint, 0, 0);
        }
    }

    void closeDialogs() {
        if (donationDialog != null) {
            donationDialog.dismiss();
            donationDialog = null;
        }
    }

    private void showDonationDialog(boolean permanent) {
        closeDialogs();
        Dialog dialog = new Dialog(activity);
        donationDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = card();
        panel.setPadding(dp(8), dp(22), dp(8), dp(8));
        TextView title = text(permanent ? "永久关闭捐赠入口？" : "关闭捐赠入口", 20, primary);
        title.setTypeface(Typeface.create("sans-serif-medium", 0));
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(16), 0, dp(16), dp(18));
        title.setAccessibilityHeading(true);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (permanent) {
            TextView message = text("确认后将不再显示捐赠入口，且无法在设置中恢复。", 14, secondary);
            message.setGravity(Gravity.CENTER);
            message.setLineSpacing(dp(5), 1f);
            message.setPadding(dp(18), 0, dp(18), dp(22));
            panel.addView(message, new LinearLayout.LayoutParams(-1, -2));
        } else {
            menu(panel, "临时关闭", "本次隐藏，重新打开设置后恢复", () -> {
                dialog.dismiss();
                donationTemporarilyHidden = true;
                show(ABOUT, false);
                Toast.makeText(activity, "本次已隐藏，重新打开设置后恢复", Toast.LENGTH_SHORT).show();
            });
            divider(panel);
            menu(panel, "永久关闭", "关闭后无法在设置中恢复", () -> {
                dialog.dismiss();
                showDonationDialog(true);
            });
        }
        LinearLayout actions = new LinearLayout(activity);
        actions.setPadding(dp(8), dp(permanent ? 0 : 12), dp(8), dp(8));
        TextView cancel = dialogAction("取消", false, dialog::dismiss);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        if (permanent) {
            TextView confirm = dialogAction("永久关闭", true, () -> {
                if (closeDonationPermanently()) {
                    dialog.dismiss();
                    show(ABOUT, false);
                    Toast.makeText(activity, "捐赠入口已永久关闭", Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams confirmSize = new LinearLayout.LayoutParams(0, -2, 1);
            confirmSize.leftMargin = dp(12);
            actions.addView(confirm, confirmSize);
        }
        panel.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(0));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.32f);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dialog.show();
        window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(560)), -2);
        WindowManager.LayoutParams placement = window.getAttributes();
        placement.y = dp(16);
        window.setAttributes(placement);
    }

    private TextView dialogAction(String label, boolean destructive, Runnable action) {
        TextView button = text(label, 16, destructive ? 0xffffffff : primary);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", 0));
        button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(14), dp(12), dp(14));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x20ffffff : 0x14000000),
                shape(destructive ? 0xffd74742 : dark ? 0xff2a2d34 : 0xfff0f1f3, 12), null));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void donate() {
        TextView title = text("给 MiStep 添一点口粮", 23, primary);
        title.setTypeface(Typeface.create("sans-serif-medium", 0));
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        add(title, 22, 14);
        TextView note = text("MiStep 纯为爱发电。\n如果你觉得它做得不错，欢迎给作者添一点口粮。\n支持完全自愿，感谢每一份心意。", 14, secondary);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(dp(6), 1f);
        add(note, 0, 24);

        LinearLayout methods = new LinearLayout(activity);
        methods.setPadding(dp(4), dp(4), dp(4), dp(4));
        methods.setBackground(shape(dark ? 0xff292c32 : 0xffe7e9ed, 8));
        TextView[] tabs = new TextView[2];
        FrameLayout qrFrame = new FrameLayout(activity);
        qrFrame.setBackground(shape(0xffffffff, 8));
        qrFrame.setPadding(dp(4), dp(4), dp(4), dp(4));
        ImageView qr = new ImageView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
            }
        };
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrFrame.addView(qr, new FrameLayout.LayoutParams(-1, -2));
        Runnable select = () -> {
            int selected = donationMethod == 1 ? 1 : 0;
            for (int i = 0; i < tabs.length; i++) {
                tabs[i].setSelected(i == selected);
                tabs[i].setTextColor(i == selected ? (i == 0 ? 0xff078047 : 0xff1677ff) : secondary);
                tabs[i].setBackground(i == selected ? shape(0xffffffff, 6) : null);
            }
            String resource = selected == 0 ? "donate_wechat" : "donate_alipay";
            int id = getResources().getIdentifier(resource, "drawable", activity.getPackageName());
            qr.setImageResource(id);
            qr.setContentDescription(selected == 0 ? "微信收款二维码" : "支付宝收款二维码");
        };
        for (int i = 0; i < tabs.length; i++) {
            final int method = i;
            TextView tab = text(i == 0 ? "微信" : "支付宝", 16, primary);
            tab.setGravity(Gravity.CENTER);
            tab.setTypeface(Typeface.create("sans-serif-medium", 0));
            tab.setMinHeight(dp(44));
            tab.setPadding(dp(8), dp(10), dp(8), dp(10));
            tab.setOnClickListener(v -> { donationMethod = method; select.run(); });
            tabs[i] = tab;
            methods.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        add(methods, 0, 16);
        add(qrFrame, 0, 18);
        TextView thanks = text("谢谢你的喜欢与支持", 13, secondary);
        thanks.setGravity(Gravity.CENTER);
        add(thanks, 0, 0);
        select.run();
    }

    private boolean donationHidden() {
        return donationTemporarilyHidden || aboutPreferences.getBoolean("donation_permanently_closed", false);
    }

    private boolean closeDonationPermanently() {
        if (aboutPreferences.edit().putBoolean("donation_permanently_closed", true).commit()) return true;
        Toast.makeText(activity, "未能保存，请重试", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void openLink(String url) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void logs() {
        TextView note = text("先开始录制，再复现问题，返回后点结束并导出；最长60秒，支持锁屏复现。需要 Root 权限。诊断包含系统日志及窗口信息，分享前请检查。", 13, secondary);
        note.setPadding(dp(16), 0, dp(16), 0);
        add(note, 0, 18);
        LinearLayout description = card();
        android.widget.EditText input = new android.widget.EditText(activity);
        input.setTextSize(14); input.setTextColor(primary); input.setHintTextColor(secondary);
        input.setHint("问题现象、发生时间与复现步骤（选填）");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2); input.setMaxLines(5);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        input.setBackgroundColor(0x00000000); input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setText(runtimeLogs.description());
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { runtimeLogs.description(s.toString()); }
            public void afterTextChanged(android.text.Editable text) {}
        });
        description.addView(input, new LinearLayout.LayoutParams(-1, -2)); add(description, 0, 18);
        LinearLayout actions = new LinearLayout(activity);
        refreshLogs = action("刷新诊断", false, runtimeLogs::refresh);
        exportLogs = action("导出诊断包", true, runtimeLogs::export);
        LinearLayout.LayoutParams refresh = new LinearLayout.LayoutParams(0, dp(50), 1);
        refresh.rightMargin = dp(12);
        actions.addView(refreshLogs, refresh);
        actions.addView(exportLogs, new LinearLayout.LayoutParams(0, dp(50), 1));
        add(actions, 0, 18);
        recordLogs = action("开始录制问题（最长60秒）", false, runtimeLogs::record);
        recordLogs.setMinimumHeight(dp(50));
        add(recordLogs, 0, 14);
        logStatus = text("", 13, secondary);
        logStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        logStatus.setPadding(dp(16), 0, dp(16), 0);
        add(logStatus, 0, 12);
        LinearLayout preview = card();
        logPreview = text("", 11, primary);
        logPreview.setTypeface(Typeface.MONOSPACE);
        logPreview.setTextIsSelectable(true);
        logPreview.setPadding(dp(16), dp(16), dp(16), dp(16));
        preview.addView(logPreview, new LinearLayout.LayoutParams(-1, -2));
        add(preview, 0, 0);
        runtimeLogs.open(); updateLogs();
    }

    private TextView action(String label, boolean selected, Runnable run) {
        TextView button = text(label, 16, selected ? 0xffffffff : primary);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", 0));
        button.setBackground(HyperOsStyle.controlButton(activity, selected, 18));
        button.setFocusable(true);
        button.setOnClickListener(v -> run.run());
        return button;
    }

    void updateLogs() {
        if (screen != LOGS || logStatus == null) return;
        logStatus.setText(runtimeLogs.status());
        logPreview.setText(runtimeLogs.preview());
        refreshLogs.setEnabled(!runtimeLogs.busy());
        refreshLogs.setAlpha(runtimeLogs.busy() ? .45f : 1f);
        exportLogs.setEnabled(runtimeLogs.canExport());
        exportLogs.setAlpha(runtimeLogs.canExport() ? 1f : .45f);
        recordLogs.setText(runtimeLogs.recording()?"结束录制并采集诊断":"开始录制问题（最长60秒）");
        recordLogs.setEnabled(runtimeLogs.recording()||!runtimeLogs.busy()); recordLogs.setAlpha(runtimeLogs.recording()||!runtimeLogs.busy()?1f:.45f);
    }

    private int visibleCount(WorkspaceOptions options, int[] ids, int from) {
        int count = 0;
        for (int i = from; i < ids.length; i++) if (options.visible(ids[i])) count++;
        return count;
    }

    private void buttons(int[] ids) {
        WorkspaceOptions options = WorkspaceSettingsStore.read(activity);
        LinearLayout group = card();
        for (int i = 0; i < ids.length; i++) {
            int id = ids[i];
            if (group.getChildCount() > 0) divider(group);
            LinearLayout row = row();
            TextView label = text(WorkspaceOptions.label(id), 17, primary);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            Switch toggle = settingsSwitch(WorkspaceOptions.label(id), options.visible(id));
            row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
            toggle.setOnCheckedChangeListener((button, checked) -> {
                WorkspaceOptions current = WorkspaceSettingsStore.read(activity);
                if (current.visible(id) == checked) return;
                if (!persist(current.withVisible(id, checked))) button.setChecked(!checked);
            });
            row.setOnClickListener(v -> toggle.toggle());
            group.addView(row);
            // Keep the panel visibility switch in its own group.
            if (id == WorkspaceOptions.CONTROL_CENTER) {
                add(group, 0, 4);
                section("显示的按钮与信息");
                group = card();
            }
        }
        add(group, 0, 0);
    }

    private Switch settingsSwitch(String label, boolean checked) {
        Switch toggle = new Switch(activity);
        toggle.setShowText(false);
        toggle.setText("");
        toggle.setContentDescription(label);
        toggle.setSwitchMinWidth(activity.settingsDimension("miuix_appcompat_sliding_button_width", 49));
        toggle.setSplitTrack(false);
        int thumbSize = activity.settingsDimension("miuix_appcompat_sliding_button_slider_size", 20);
        int frameHeight = activity.settingsDimension("miuix_appcompat_sliding_button_height", 28);
        toggle.setThumbDrawable(new Drawable() {
            private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
            { knob.setColor(0xffffffff); }
            @Override public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(),
                        Math.min(bounds.width(), bounds.height()) / 2f, knob);
            }
            @Override public int getIntrinsicWidth() { return thumbSize; }
            @Override public int getIntrinsicHeight() { return frameHeight; }
            @Override public void setAlpha(int alpha) { knob.setAlpha(alpha); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter filter) { knob.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        });
        StateListDrawable track = new StateListDrawable();
        Drawable on = switchTrack(activity.settingsColor("miuix_appcompat_sliding_button_bar_on_"
                + (dark ? "dark" : "light"), BLUE));
        Drawable off = switchTrack(activity.settingsColor(dark
                ? "miuix_appcompat_sliding_button_bar_off_dark"
                : "miuix_appcompat_sliding_button_bar_off_normal_color_light",
                dark ? 0xff42454e : 0xffdedfe5));
        track.addState(new int[]{android.R.attr.state_checked}, on);
        track.addState(new int[]{}, off);
        toggle.setTrackDrawable(track);
        toggle.setThumbTintList(null); toggle.setTrackTintList(null);
        toggle.setChecked(checked);
        return toggle;
    }

    private Drawable switchTrack(int color) {
        int inset = activity.settingsDimension("miuix_appcompat_sliding_button_slider_horizontal_padding", 4);
        float radius = dp(14);
        ShapeDrawable track = new ShapeDrawable(new RoundRectShape(new float[] {
                radius, radius, radius, radius, radius, radius, radius, radius}, null, null));
        track.getPaint().setColor(color);
        track.setPadding(inset, 0, inset, 0);
        track.setIntrinsicWidth(activity.settingsDimension("miuix_appcompat_sliding_button_width", 49));
        track.setIntrinsicHeight(activity.settingsDimension("miuix_appcompat_sliding_button_height", 28));
        return track;
    }

    private void swap() {
        int selected = WorkspaceSettingsStore.read(activity).swapMode;
        for (int mode = 0; mode < choices.length; mode++) {
            final int choice = mode;
            LinearLayout group = card();
            LinearLayout row = row();
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(WorkspaceOptions.modeLabel(mode), 18, primary));
            TextView description = text(mode == WorkspaceOptions.FIXED_MAIN
                    ? "主区域保持在当前一侧，只交换应用" : "副任务在原侧放大，主区域随之换边", 13, secondary);
            LinearLayout.LayoutParams detail = new LinearLayout.LayoutParams(-1, -2);
            detail.topMargin = dp(6); labels.addView(description, detail);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            RadioButton radio = new RadioButton(activity);
            Drawable indicator = activity.settingsDrawable("miuix_appcompat_btn_radio_" + (dark ? "dark" : "light"));
            nativeChoices[mode] = indicator != null;
            if (indicator != null) {
                radio.setButtonDrawable(indicator);
                radio.setButtonTintList(null);
            } else {
                radio.setButtonTintList(ColorStateList.valueOf(mode == selected ? BLUE : secondary));
            }
            radio.setChecked(mode == selected);
            radio.setContentDescription(WorkspaceOptions.modeLabel(mode));
            choices[mode] = radio;
            row.addView(radio, new LinearLayout.LayoutParams(-2, -2));
            group.addView(row);
            ModeDiagram diagram = new ModeDiagram(mode);
            LinearLayout.LayoutParams preview = new LinearLayout.LayoutParams(-1, dp(104));
            preview.setMargins(dp(18), 0, dp(18), dp(16));
            group.addView(diagram, preview);
            Runnable select = () -> {
                if (!persist(WorkspaceSettingsStore.read(activity).withSwapMode(choice))) return;
                for (int i = 0; i < choices.length; i++) {
                    choices[i].setChecked(i == choice);
                    if (!nativeChoices[i])
                        choices[i].setButtonTintList(ColorStateList.valueOf(i == choice ? BLUE : secondary));
                }
            };
            group.setOnClickListener(v -> select.run());
            row.setOnClickListener(v -> select.run());
            radio.setOnClickListener(v -> select.run());
            add(group, 0, 14);
        }
        TextView note = text("从下一次主副交换开始生效", 13, secondary);
        note.setPadding(dp(16), dp(2), dp(16), 0);
        add(note, 0, 0);
    }

    private String gestureLabel(int mode) {
        return mode == WorkspaceOptions.BOTTOM_CENTER_OUTWARD ? "底部向外滑" : "状态栏向内滑";
    }

    private void gesture() {
        WorkspaceOptions options = WorkspaceSettingsStore.read(activity);
        boolean lineHidden = EntryGestureSettings.lineHidden(activity);
        int selected = EntryGesturePolicy.effectiveMode(options.entryGesture, lineHidden);
        section("唤出手势");
        LinearLayout group = card();
        entryGestureOption(group, WorkspaceOptions.STATUS_BAR_INWARD,
                "状态栏向内滑", "从状态栏左侧或右侧向屏幕中间滑动", selected, true);
        divider(group);
        String detail = lineHidden ? "从屏幕底部任意位置向左或向右滑动"
                : "需先关闭澎湃小白条（手势提示线）";
        entryGestureOption(group, WorkspaceOptions.BOTTOM_CENTER_OUTWARD,
                "底部向外滑", detail, selected, lineHidden);
        add(group, 0, 0);
    }

    private void entryGestureOption(LinearLayout group, int mode, String label, String detail,
            int selectedMode, boolean enabled) {
        LinearLayout row = row();
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(label, 17, primary);
        TextView description = text(detail, 13, secondary);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(5);
        labels.addView(title);
        labels.addView(description, descriptionParams);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        RadioButton radio = new RadioButton(activity);
        radio.setChecked(selectedMode == mode);
        radio.setEnabled(enabled);
        radio.setContentDescription(label + "，" + detail);
        Drawable indicator = activity.settingsDrawable(
                "miuix_appcompat_btn_radio_" + (dark ? "dark" : "light"));
        if (indicator != null) {
            radio.setButtonDrawable(indicator);
            radio.setButtonTintList(null);
        } else {
            radio.setButtonTintList(ColorStateList.valueOf(selectedMode == mode ? BLUE : secondary));
        }
        row.addView(radio, new LinearLayout.LayoutParams(-2, -2));
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : .45f);
        Runnable select = () -> {
            if (!EntryGesturePolicy.canSelect(mode, EntryGestureSettings.lineHidden(activity))) {
                Toast.makeText(activity, "请先关闭澎湃的手势提示线", Toast.LENGTH_SHORT).show();
                show(GESTURE, false);
                return;
            }
            WorkspaceOptions current = WorkspaceSettingsStore.read(activity);
            if (!persist(current.withEntryGesture(mode))) {
                show(GESTURE, false);
                return;
            }
            show(GESTURE, false);
        };
        row.setOnClickListener(v -> select.run());
        if (enabled) radio.setOnClickListener(v -> select.run());
        group.addView(row);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            activity.getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(EntryGestureSettings.HIDE_GESTURE_LINE),
                    false, gestureObserver);
        } catch (RuntimeException unavailable) { /* Selection and runtime still check live state. */ }
    }

    @Override protected void onDetachedFromWindow() {
        activity.getContentResolver().unregisterContentObserver(gestureObserver);
        super.onDetachedFromWindow();
    }

    private boolean persist(WorkspaceOptions options) {
        if (WorkspaceSettingsStore.write(activity, options)) return true;
        Toast.makeText(activity, "未能保存，请重试", Toast.LENGTH_SHORT).show();
        return false;
    }

    private LinearLayout card() {
        LinearLayout group = new LinearLayout(activity);
        group.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable material = new GradientDrawable();
        material.setColor(surface);
        material.setCornerRadius(activity.settingsDimension("miuix_theme_radius_common", 16));
        group.setBackground(material);
        group.setClipToOutline(true);
        return group;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(activity.settingsDimension("miuix_preference_item_min_height", 56));
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x18ffffff : 0x0b000000),
                null, shape(0xffffffff, 0)));
        return row;
    }

    private LinearLayout menu(LinearLayout group, String title, String summary, Runnable action) {
        LinearLayout row = row();
        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 17, primary));
        TextView detail = text(summary, 14, secondary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(3); labels.addView(detail, lp);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        View arrow = new View(activity);
        arrow.setBackground(new Chevron());
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(22)));
        row.setOnClickListener(v -> action.run());
        group.addView(row);
        return row;
    }

    private void divider(LinearLayout group) {
        View divider = new View(activity);
        divider.setBackgroundColor(dark ? 0x08ffffff : 0x04000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(.5f));
        lp.leftMargin = lp.rightMargin = dp(20);
        group.addView(divider, lp);
    }

    private void section(String title) {
        TextView section = text(title, 13, secondary);
        section.setPadding(dp(16), dp(8), dp(16), 0);
        section.setAccessibilityHeading(true);
        add(section, 0, 8);
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setLineSpacing(dp(2), 1f);
        return text;
    }

    private void add(View view, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top); lp.bottomMargin = dp(bottom);
        content.addView(view, lp);
    }

    private GradientDrawable shape(int color, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(float value) { return Math.max(value > 0 ? 1 : 0, Math.round(value * getResources().getDisplayMetrics().density)); }

    private final class Chevron extends Drawable {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            paint.setColor(secondary); paint.setStrokeWidth(dp(1.6f)); paint.setStrokeCap(Paint.Cap.ROUND);
            float x = getBounds().exactCenterX(), y = getBounds().exactCenterY();
            canvas.drawLine(x - dp(2), y - dp(4), x + dp(2), y, paint);
            canvas.drawLine(x + dp(2), y, x - dp(2), y + dp(4), paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private final class ModeDiagram extends View {
        final int mode;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ModeDiagram(int mode) { super(activity); this.mode = mode; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scale = Math.min(getWidth() / 280f, getHeight() / 104f);
            canvas.save(); canvas.translate((getWidth() - 280 * scale) / 2, (getHeight() - 104 * scale) / 2);
            canvas.scale(scale, scale);
            scene(canvas, 0, false, false);
            paint.setColor(secondary); paint.setStrokeWidth(1.7f); paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(128, 52, 150, 52, paint);
            canvas.drawLine(145, 47, 150, 52, paint); canvas.drawLine(145, 57, 150, 52, paint);
            scene(canvas, 170, mode == WorkspaceOptions.GROW_IN_PLACE, true);
            canvas.restore();
        }
        void scene(Canvas canvas, float x, boolean right, boolean after) {
            fill(canvas, x, 5, 110, 94, dark ? 0xff272a33 : 0xffeef1f8, 12);
            fill(canvas, x + 8, 13, 94, 10, dark ? 0xff333742 : 0xffe0e5ef, 4);
            float mainX = x + (right ? 38 : 8), sideX = x + (right ? 8 : 80);
            fill(canvas, mainX, 30, 64, 61, dark ? 0xff244b80 : 0xffdceaff, 7);
            label(canvas, after ? "B" : "A", mainX + 32, 64, BLUE);
            for (int i = 0; i < 3; i++) {
                fill(canvas, sideX, 30 + i * 22, 22, 17, i == 0
                        ? dark ? 0xff49405e : 0xffe8e0f6 : dark ? 0xff373b46 : 0xffdfe3ec, 4);
                if (i == 0) label(canvas, after ? "A" : "B", sideX + 11, 43, dark ? 0xffb9a5df : 0xff8b71bb);
            }
        }
        void fill(Canvas canvas, float x, float y, float w, float h, int color, float radius) {
            paint.setColor(color); canvas.drawRoundRect(new RectF(x, y, x + w, y + h), radius, radius, paint);
        }
        void label(Canvas canvas, String text, float x, float y, int color) {
            paint.setColor(color); paint.setTypeface(Typeface.create("sans-serif-medium", 0));
            paint.setTextSize(12); paint.setTextAlign(Paint.Align.CENTER); canvas.drawText(text, x, y, paint);
        }
    }
}
