package com.jianguo.mistep;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A launcher entry used only by the HyperOS-style top shortcut rail. */
final class QuickApp {
    final String label;
    final ComponentName component;
    final Drawable icon;

    private QuickApp(String label, ComponentName component, Drawable icon) {
        this.label = label;
        this.component = component;
        this.icon = icon;
    }

    static List<QuickApp> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        List<QuickApp> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String self = ctx.getPackageName();
        for (ResolveInfo ri : infos) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(self)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            String key = component.flattenToShortString();
            if (!seen.add(key)) continue;
            CharSequence label = ri.loadLabel(pm);
            Drawable icon;
            try {
                icon = ri.loadIcon(pm);
            } catch (Throwable error) {
                icon = null;
            }
            list.add(new QuickApp(label == null ? pkg : label.toString(), component, icon));
        }
        Map<String, UsageStats> usage = Collections.emptyMap();
        try {
            UsageStatsManager stats = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (stats != null) {
                long now = System.currentTimeMillis();
                List<UsageStats> daily = stats.queryUsageStats(UsageStatsManager.INTERVAL_DAILY,
                        now - 7L * 86400000L, now);
                if (daily != null) {
                    Map<String, UsageStats> result = new HashMap<>();
                    for (UsageStats record : daily) {
                        if (record == null || record.getPackageName() == null) continue;
                        UsageStats previous = result.get(record.getPackageName());
                        if (previous == null) result.put(record.getPackageName(), new UsageStats(record));
                        else previous.add(record);
                    }
                    usage = result;
                }
            }
        } catch (RuntimeException ignored) { /* Keep the rail usable when usage access is denied. */ }
        final Map<String, UsageStats> recentUsage = usage;
        Collections.sort(list, (a, b) -> {
            UsageStats left = recentUsage.get(a.component.getPackageName());
            UsageStats right = recentUsage.get(b.component.getPackageName());
            return QuickAppUsageOrder.compare(
                    left == null ? 0L : left.getTotalTimeInForeground(),
                    left == null ? 0L : left.getLastTimeUsed(), a.label, a.component.flattenToString(),
                    right == null ? 0L : right.getTotalTimeInForeground(),
                    right == null ? 0L : right.getLastTimeUsed(), b.label, b.component.flattenToString());
        });
        return list;
    }
}
