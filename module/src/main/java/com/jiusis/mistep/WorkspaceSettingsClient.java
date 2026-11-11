package com.jiusis.mistep;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.function.Consumer;

/** One observer for the process-lifetime Host; provider IPC never blocks SystemUI main. */
final class WorkspaceSettingsClient {
    private final Context context;
    private final Handler main, worker;
    private final Consumer<WorkspaceOptions> changed;
    private boolean reportedFailure;
    private boolean observing;
    private final ContentObserver observer;
    private final Runnable load = this::load;

    WorkspaceSettingsClient(Context context, Handler main, Consumer<WorkspaceOptions> changed) {
        this.context = context;
        this.main = main;
        this.changed = changed;
        HandlerThread thread = new HandlerThread("MiStep-settings");
        thread.start();
        worker = new Handler(thread.getLooper());
        observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) { refresh(); }
        };
        refresh();
    }

    void refresh() {
        if (!observing) try {
            context.getContentResolver().registerContentObserver(WorkspaceSettingsStore.URI, false, observer);
            observing = true;
        } catch (Exception error) {
            android.util.Log.w("MiStepProbe", "workspace settings observer unavailable", error);
        }
        worker.removeCallbacks(load); worker.post(load);
    }

    private void load() {
        try {
            WorkspaceOptions options = WorkspaceSettingsStore.decode(context.getContentResolver().call(
                    WorkspaceSettingsStore.URI, WorkspaceSettingsStore.READ, null, null));
            main.post(() -> changed.accept(options));
            reportedFailure = false;
        } catch (Exception error) {
            if (!reportedFailure) android.util.Log.w("MiStepProbe", "workspace settings unavailable; keep last choices", error);
            reportedFailure = true;
        }
    }
}
