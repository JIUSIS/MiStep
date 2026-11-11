package com.jiusis.mistep;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toolbar;

/** Workspace settings opened by the native system Settings entry. */
public final class SettingsActivity extends Activity {
    private WorkspaceSettingsPage settings;
    private RuntimeLogController runtimeLogs;
    private TextView pageTitle;
    private Resources systemSettingsResources;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            systemSettingsResources = getPackageManager().getResourcesForApplication("com.android.settings");
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }

        TypedValue background = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, background, true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        boolean dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int pageBackground = dark ? background.data : 0xfff2f2f2;
        page.setBackgroundColor(pageBackground);
        getWindow().setBackgroundDrawable(new ColorDrawable(pageBackground));

        Toolbar toolbar = new Toolbar(this);
        toolbar.setTitle("");
        int barHeight = settingsDimension("miuix_appcompat_action_bar_default_height", 56);
        toolbar.setMinimumHeight(barHeight);
        FrameLayout titleBar = new FrameLayout(this);
        titleBar.addView(toolbar, new FrameLayout.LayoutParams(-1, -1));
        pageTitle = new TextView(this);
        pageTitle.setTextSize(17);
        pageTitle.setTypeface(Typeface.create(pageTitle.getTypeface(), Typeface.BOLD));
        pageTitle.setTextColor(settingsColor("miuix_preference_primary_text_color_" + (dark ? "dark" : "light"),
                dark ? 0xfff4f5f8 : 0xff000000));
        pageTitle.setGravity(Gravity.CENTER);
        pageTitle.setSingleLine(true);
        pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pageTitle.setAccessibilityHeading(true);
        FrameLayout.LayoutParams titleBounds = new FrameLayout.LayoutParams(-1, -1);
        titleBounds.leftMargin = titleBounds.rightMargin = barHeight;
        titleBar.addView(pageTitle, titleBounds);
        page.addView(titleBar, new LinearLayout.LayoutParams(-1, barHeight));
        setContentView(page);
        setActionBar(toolbar);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationContentDescription("返回");
        runtimeLogs = new RuntimeLogController(this, savedInstanceState);
        settings = new WorkspaceSettingsPage(this, savedInstanceState, runtimeLogs);
        runtimeLogs.changed = () -> settings.updateLogs();
        page.addView(settings, new LinearLayout.LayoutParams(-1, 0, 1f));
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
                    if (!settings.goBack()) finish();
                });

        page.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        page.requestApplyInsets();

        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark ? 0 : lightBars, lightBars);
        }
    }

    void setPageTitle(String title) {
        pageTitle.setText(title);
    }

    int settingsColor(String name, int fallback) {
        try {
            if (systemSettingsResources != null) {
                int id = systemSettingsResources.getIdentifier(name, "color", "com.android.settings");
                if (id != 0) return systemSettingsResources.getColor(id, null);
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }

    int settingsDimension(String name, int fallbackDp) {
        try {
            if (systemSettingsResources != null) {
                int id = systemSettingsResources.getIdentifier(name, "dimen", "com.android.settings");
                if (id != 0) return systemSettingsResources.getDimensionPixelSize(id);
            }
        } catch (RuntimeException ignored) { }
        return Math.round(fallbackDp * getResources().getDisplayMetrics().density);
    }

    Drawable settingsDrawable(String name) {
        try {
            if (systemSettingsResources != null) {
                int id = systemSettingsResources.getIdentifier(name, "drawable", "com.android.settings");
                if (id != 0) return systemSettingsResources.getDrawable(id, null);
            }
        } catch (RuntimeException ignored) { }
        return null;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        settings.save(state);
        runtimeLogs.save(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        runtimeLogs.onResult(request, result, data);
    }

    @Override
    protected void onDestroy() {
        if (settings != null) settings.closeDialogs();
        if (runtimeLogs != null) runtimeLogs.close();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (!settings.goBack()) finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
