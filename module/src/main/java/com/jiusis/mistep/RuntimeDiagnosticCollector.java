package com.jiusis.mistep;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit bounded snapshots; never changes settings, tasks, scopes or system logging levels. */
final class RuntimeDiagnosticCollector {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MiStep-diagnostic-deadline"); t.setDaemon(true); return t;
    });
    static RuntimeDiagnosticBundle.Part command(String name, String scope, String script, int seconds) {
        RuntimeDiagnosticBundle.Part first = command(name, scope + "；su -M 全局mount namespace", script, seconds, true);
        String error = first.text.toLowerCase(Locale.ROOT);
        if ("ERROR".equals(first.status) && (error.contains("invalid option") || error.contains("unrecognized option") || error.contains("unknown option"))) {
            RuntimeDiagnosticBundle.Part retry = command(name, scope, script, seconds, false);
            return new RuntimeDiagnosticBundle.Part(name, retry.status, scope + "；su不支持-M，退回调用方namespace",
                    retry.text + "\n[namespace fallback: initial error]\n" + first.text, retry.exit, first.duration + retry.duration);
        }
        return first;
    }
    private static RuntimeDiagnosticBundle.Part command(String name, String scope, String script, int seconds, boolean globalMount) {
        long begin = System.nanoTime();
        Process process = null; ScheduledFuture<?> deadline = null;
        AtomicBoolean timedOut = new AtomicBoolean(); RuntimeLogText.Buffer output = new RuntimeLogText.Buffer();
        String status = "ERROR"; int exit = -1;
        try {
            String command = "set -o pipefail\n" + script;
            process = (globalMount ? new ProcessBuilder("su", "-M", "-c", command)
                    : new ProcessBuilder("su", "-c", command)).redirectErrorStream(true).start();
            final Process running = process;
            deadline = DEADLINES.schedule(() -> {
                timedOut.set(true); running.destroyForcibly();
                try { running.getInputStream().close(); } catch (Exception ignored) {}
            }, seconds, TimeUnit.SECONDS);
            process.getOutputStream().close();
            try (InputStreamReader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[4096]; int n;
                while ((n = reader.read(chunk)) != -1) output.append(chunk, n);
            }
            if (process.waitFor(1, TimeUnit.SECONDS)) {
                exit = process.exitValue();
                status = exit == 4 ? output.toString().contains("Permission denied") ? "ERROR" : "MISSING" : exit != 0 ? "ERROR" : output.toString().isBlank() ? "EMPTY"
                        : output.truncated() || output.toString().contains("SOURCE_TRUNCATED") ? "TRUNCATED" : "OK";
            }
        } catch (Exception error) {
            char[] message = ("\n采集异常：" + error.getClass().getSimpleName() + "\n").toCharArray(); output.append(message, message.length);
        } finally {
            if (deadline != null) deadline.cancel(false); if (process != null) process.destroyForcibly();
        }
        if (timedOut.get()) status = "TIMEOUT";
        return new RuntimeDiagnosticBundle.Part(name, status, scope, output.toString(), exit,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
    }
    static RuntimeDiagnosticBundle collect(Context context, String description, java.util.List<RuntimeDiagnosticBundle.Part> recording) {
        return collect(context, description, recording, java.util.List.of());
    }
    static RuntimeDiagnosticBundle collect(Context context, String description, java.util.List<RuntimeDiagnosticBundle.Part> recording, java.util.List<RuntimeDiagnosticBundle.Attachment> attachments) {
        String now = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date());
        ArrayList<RuntimeDiagnosticBundle.Part> parts = new ArrayList<>();
        parts.add(new RuntimeDiagnosticBundle.Part("01-environment.txt", "OK", "版本/设备/设置/用户复现描述",
                header(context), 0, 0));
        if (recording != null) parts.addAll(recording);
        RuntimeDiagnosticBundle.Part root = command("02-root.txt", "Root UID/SELinux/命名空间可用性", "id -u; id; id -Z; getenforce", 25);
        boolean allowed = "OK".equals(root.status) && root.text.split("\n", 2)[0].trim().equals("0");
        if (!allowed && "OK".equals(root.status)) root = new RuntimeDiagnosticBundle.Part(root.name, "ROOT_UNAVAILABLE", root.scope, root.text, root.exit, root.duration);
        parts.add(root);
        if(allowed)parts.add(command("02-identity-handshake.txt","受WRITE_SECURE_SETTINGS保护的只读身份同步；不打开工作区或输入追踪",
                "am broadcast --user 0 -a com.jiusis.mistep.DEBUG -p com.android.systemui --es diagnosticRequest "
                        + java.util.UUID.randomUUID().toString().replace("-","")
                        + " --ez diagnosticStart false --es diagnosticBootId \"$(cat /proc/sys/kernel/random/boot_id)\"\nsleep 1",7));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        for (RuntimeDiagnosticSources.Source source : RuntimeDiagnosticSources.ALL) {
            if (!allowed || System.nanoTime() >= deadline) {
                parts.add(new RuntimeDiagnosticBundle.Part(source.name(), "SKIPPED", source.scope(),
                        allowed ? "总采集时间预算已到；该项未执行。\n" : "Root 未授权或不可用；该项未执行。\n", -1, 0));
                continue;
            }
            int seconds = (int) Math.max(1, Math.min(7, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime())));
            RuntimeDiagnosticBundle.Part part = command(source.name(), source.scope(), source.script(), seconds);
            if (part.name.equals("08-module-logcat.txt") && "OK".equals(part.status) && !RuntimeLogText.hasEntries(part.text))
                part = new RuntimeDiagnosticBundle.Part(part.name, "EMPTY", part.scope, part.text, part.exit, part.duration);
            parts.add(part);
        }
        RuntimeDiagnosticBundle.Part request = null, process = null;
        for (RuntimeDiagnosticBundle.Part part : parts) {
            if (part.name.equals("03-recording-request.txt")) request = part;
            if (part.name.equals("04-processes.txt")) process = part;
        }
        for (int i = 0; i < parts.size(); i++) {
            RuntimeDiagnosticBundle.Part part = parts.get(i);
            if (part.name.startsWith("15-input-") || part.name.startsWith("16-input-"))
                parts.set(i, RuntimeDiagnosticBundle.confirmTrace(part, request, process));
        }
        parts.add(RuntimeDiagnosticBundle.analyze(parts));
        return new RuntimeDiagnosticBundle(now, parts, attachments).withDescription(description);
    }
    static RuntimeDiagnosticBundle.Part startRecording() {
        return command("03-recording-request.txt", "主动请求6秒数值追踪；记录boot/PID/墙钟/elapsed以核对ARM身份", """
                [ "$(id -u)" = 0 ] || exit 1
                dumpsys window policy | grep -q 'inputRestricted=false' || exit 2
                date -u '+request UTC %Y-%m-%dT%H:%M:%SZ'
                date '+requestEpoch=%s'
                printf 'requestBootId='; cat /proc/sys/kernel/random/boot_id
                printf 'requestElapsedSeconds='; cut -d ' ' -f 1 /proc/uptime
                printf 'requestServerPID='; pidof system_server
                printf 'requestSystemUiPID='; pidof com.android.systemui
                am broadcast -a com.jiusis.mistep.DEBUG --ez traceInput true -p com.android.systemui
                """, 25);
    }
    static RuntimeDiagnosticBundle.Part stopRecording() {
        return command("03-recording-stop.txt", "显式结束短时追踪",
                "am broadcast -a com.jiusis.mistep.DEBUG --ez stopInputTrace true -p com.android.systemui", 7);
    }
    private static String header(Context context) {
        String version;
        try { PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = p.versionName + " / " + p.getLongVersionCode(); } catch (Exception e) { version = "未知"; }
        WorkspaceOptions o = WorkspaceSettingsStore.read(context);
        return "Mi Step 诊断包 v1\n模块APK版本：" + version + "\n设备：" + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid：" + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT
                + "\n构建：" + Build.FINGERPRINT + "\n设置：entry=" + o.entryGesture + " hidden=" + o.hiddenButtons
                + " swap=" + o.swapMode + " keepOnLock=" + o.keepOnLock
                + "\nAPK版本不代表系统进程已加载同版；请核对PID与LSPosed attached marker。\n";
    }
}
