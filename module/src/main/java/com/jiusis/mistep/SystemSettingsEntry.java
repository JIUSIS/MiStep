package com.jiusis.mistep;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import java.util.List;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** One native homepage row. Settings owns its adapter, theme and navigation. */
final class SystemSettingsEntry {
    private static final long ID = 0x6d737465L;
    private static boolean reported;
    private static Drawable.ConstantState iconState;

    static void install(ClassLoader loader) {
        try {
            Class<?> settings = XposedHelpers.findClass("com.android.settings.MiuiSettings", loader);
            Class<?> header = XposedHelpers.findClass(
                    "com.android.settingslib.miuisettings.preference.PreferenceActivity$Header", loader);
            installIcon(loader, header);
            XposedHelpers.findAndHookMethod(settings, "updateHeaderList", List.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.thisObject instanceof Activity)) return;
                    try {
                        @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) param.args[0];
                        if (rows == null) return;
                        for (Object row : rows)
                            if (XposedHelpers.getLongField(row, "id") == ID) return;
                        Activity activity = (Activity) param.thisObject;
                        Object entry = XposedHelpers.newInstance(header);
                        XposedHelpers.setLongField(entry, "id", ID);
                        XposedHelpers.setObjectField(entry, "key", "mistep_settings");
                        XposedHelpers.setObjectField(entry, "title", "Mi Step");
                        XposedHelpers.setObjectField(entry, "summary", "按钮显示、主副交换与运行日志");
                        XposedHelpers.setObjectField(entry, "intent", intent());
                        int icon = activity.getResources().getIdentifier(
                                "ic_my_device", "drawable", "com.android.settings");
                        XposedHelpers.setIntField(entry, "iconRes", icon);
                        // A separate native card immediately after the device card.
                        XposedHelpers.setIntField(entry, "groupId", (int) ID);
                        int device = activity.getResources().getIdentifier("my_device", "id", "com.android.settings");
                        int position = rows.size();
                        for (int i = 0; i < rows.size(); i++) {
                            if (device != 0 && XposedHelpers.getLongField(rows.get(i), "id") == device) {
                                position = i + 1;
                                break;
                            }
                        }
                        rows.add(position, entry);
                        if (!reported) {
                            reported = true;
                            XposedBridge.log("[Mi Step] native system Settings entry inserted");
                        }
                    } catch (Throwable error) {
                        XposedBridge.log("[Mi Step] Settings entry unavailable: " + error);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(settings, "onHeaderClick", header, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args[0] == null || XposedHelpers.getLongField(param.args[0], "id") != ID) return;
                    Activity activity = (Activity) param.thisObject;
                    try {
                        activity.startActivity(intent());
                    } catch (RuntimeException error) {
                        Toast.makeText(activity, "无法打开 Mi Step 设置，请确认模块已安装", Toast.LENGTH_SHORT).show();
                        XposedBridge.log("[Mi Step] Settings entry launch failed: " + error);
                    }
                    param.setResult(null);
                }
            });
            XposedBridge.log("[Mi Step] native system Settings entry hooks installed");
        } catch (Throwable error) {
            XposedBridge.log("[Mi Step] system Settings entry hooks unavailable: " + error);
        }
    }

    private static void installIcon(ClassLoader loader, Class<?> header) {
        try {
            Class<?> adapter = XposedHelpers.findClass("com.android.settings.MiuiSettings$HeaderAdapter", loader);
            Class<?> holder = XposedHelpers.findClass("com.android.settings.MiuiSettings$HeaderViewHolder", loader);
            XposedHelpers.findAndHookMethod(adapter, "setIcon", holder, header, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args[0] == null || param.args[1] == null
                            || XposedHelpers.getLongField(param.args[1], "id") != ID) return;
                    try {
                        ImageView view = (ImageView) XposedHelpers.getObjectField(param.args[0], "icon");
                        if (view == null) return;
                        if (iconState == null) {
                            Resources resources = view.getContext().getPackageManager()
                                    .getResourcesForApplication("com.jiusis.mistep");
                            int id = resources.getIdentifier("ic_settings_mistep", "drawable", "com.jiusis.mistep");
                            iconState = resources.getDrawable(id, null).getConstantState();
                        }
                        if (iconState != null) {
                            view.setImageDrawable(iconState.newDrawable(view.getResources()));
                            view.setVisibility(View.VISIBLE);
                        }
                    } catch (Throwable error) {
                        XposedBridge.log("[Mi Step] Settings entry icon unavailable: " + error);
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("[Mi Step] Settings entry icon hook unavailable: " + error);
        }
    }

    private static Intent intent() {
        return new Intent().setClassName("com.jiusis.mistep", "com.jiusis.mistep.SettingsActivity");
    }
}
