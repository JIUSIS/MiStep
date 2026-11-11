package com.jiusis.mistep;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps collection and file writes off the UI thread; exports only through a chosen document URI. */
final class RuntimeLogController {
    static final int EXPORT_REQUEST = 7310;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private RuntimeDiagnosticBundle report;
    private static volatile RuntimeDiagnosticBundle cachedReport;
    private static volatile boolean captureActive, stopCapture;
    private String archivedName;
    private String pendingName, description = "";
    private String status = "正在采集诊断信息…";
    private boolean busy, closed;
    Runnable changed = () -> {};

    RuntimeLogController(Activity activity, Bundle state) {
        this.activity = activity;
        if (state != null) pendingName = state.getString("runtime_log_export");
        if (state != null) description = state.getString("runtime_log_description", "");
        if (pendingName != null) status = "正在导出已采集的日志…";
        report = cachedReport;
        archivedName = activity.getSharedPreferences("mistep-diagnostic-session",0).getString("lastReport",null);
    }

    void save(Bundle state) { state.putString("runtime_log_export", pendingName); state.putString("runtime_log_description", description); }
    String description() { return description; }
    void description(String text) { description = text; }
    boolean busy() { return busy || pendingName != null; }
    boolean canExport() { return !busy() && (report != null || validName(archivedName)); }
    boolean recording() { return captureActive; }
    String status() { return status; }
    String preview() { return report == null ? validName(archivedName)?"已恢复上次录制的诊断包，可直接导出；其中时间与身份以上次录制为准。":"尚未采集。" : RuntimeLogText.tail(report.preview(), RuntimeLogText.PREVIEW); }

    void open() {
        if(captureActive){busy=true;status="正在录制问题（最长60秒），复现后点结束…";monitor();changed.run();}
        else if(report!=null||validName(archivedName)){status=report!=null?report.status():"已恢复上次录制的诊断包";changed.run();}
        else if(!busy())refresh();
    }
    private void monitor() { main.postDelayed(() -> {
        if(closed)return;
        if(captureActive)monitor();
        else {busy=false;report=cachedReport;archivedName=activity.getSharedPreferences("mistep-diagnostic-session",0).getString("lastReport",null);status=report==null?"录制已结束，请刷新检查结果":report.status();changed.run();}
    },1000); }

    void refresh() {
        if (busy()) return;
        busy = true; status = "正在采集诊断信息…"; changed.run();
        String notes = description;
        worker.execute(() -> {
            try {
                RuntimeSystemTrace.Session previous = RuntimeSystemTrace.previous(activity.getApplicationContext());
                if(previous!=null)RuntimeSystemTrace.finish(activity.getApplicationContext(),previous);
                RuntimeDiagnosticBundle snapshot = RuntimeDiagnosticCollector.collect(activity.getApplicationContext(), notes, previous==null?null:previous.parts, previous==null?java.util.List.of():previous.attachments);
                cachedReport=snapshot;
                if(previous!=null)archive(snapshot);
                post(() -> { busy = false; report = snapshot; status = snapshot.status(); changed.run(); });
            } catch (Exception error) {
                failure(error);
            }
        });
    }
    void record() {
        if(captureActive){stopCapture=true;status="正在结束录制并采集诊断…";changed.run();return;}
        if (busy()) return;
        busy = true; captureActive=true;stopCapture=false; status = "正在准备系统录制…"; changed.run();
        String notes = description;
        worker.execute(() -> {
            RuntimeSystemTrace.Session session=null;
            try {
                session=RuntimeSystemTrace.start(activity.getApplicationContext());
                if(session.started) {
                    post(() -> {status="正在录制问题（最长60秒），支持锁屏复现；完成后点结束";toast("已开始录制，请复现问题后返回结束");changed.run();});
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(RuntimeSystemTrace.DURATION_MS);
                    while(!stopCapture&&System.nanoTime()<deadline)Thread.sleep(200);
                }
                RuntimeSystemTrace.finish(activity.getApplicationContext(),session);
                RuntimeDiagnosticBundle snapshot=RuntimeDiagnosticCollector.collect(activity.getApplicationContext(),notes,session.parts,session.attachments);
                cachedReport=snapshot;
                archive(snapshot);
                post(() -> { busy = false; report = snapshot; status = snapshot.status(); changed.run(); });
            } catch (Exception error) {
                if(session!=null)try{RuntimeSystemTrace.finish(activity.getApplicationContext(),session);}catch(Exception cleanup){error.addSuppressed(cleanup);}
                failure(error);
            } finally {captureActive=false;post(() -> {busy=false;changed.run();});}
        });
    }

    void export() {
        if (!canExport()) return;
        RuntimeDiagnosticBundle snapshot = report==null?null:report.withDescription(description);
        busy = true; changed.run();
        worker.execute(() -> {
            File file = null;
            try {
                File directory = directory();
                File[] old = directory.listFiles((dir, name) -> validName(name));
                if (old != null) {
                    Arrays.sort(old, Comparator.comparingLong(File::lastModified).reversed());
                    for (int i = 2; i < old.length; i++) if(!old[i].getName().equals(archivedName)) old[i].delete();
                }
                file = new File(directory, "report-" + UUID.randomUUID() + ".zip");
                try (FileOutputStream output = new FileOutputStream(file)) {
                    if(snapshot!=null)snapshot.writeZip(output);
                    else Files.copy(new File(directory,archivedName).toPath(),output);
                }
                final File ready = file;
                post(() -> {
                    busy = false; pendingName = ready.getName();
                    Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "MiStep-diagnostics-"
                                    + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".zip");
                    try { activity.startActivityForResult(create, EXPORT_REQUEST); }
                    catch (Exception unavailable) {
                        pendingName = null; ready.delete(); toast("无法打开文件保存页面，请检查系统文件应用");
                    }
                    changed.run();
                });
            } catch (Exception error) {
                if (file != null) file.delete();
                post(() -> { busy = false; toast("无法准备日志文件，请重试"); changed.run(); });
            }
        });
    }

    boolean onResult(int request, int result, Intent data) {
        if (request != EXPORT_REQUEST) return false;
        String name = pendingName; pendingName = null;
        Uri uri = data == null ? null : data.getData();
        if (result != Activity.RESULT_OK || uri == null) {
            deletePending(name); changed.run(); open(); return true;
        }
        if (!"content".equals(uri.getScheme()) || !validName(name)) {
            deletePending(name); toast("未能获取有效保存位置，请重试"); changed.run(); open(); return true;
        }
        busy = true; status = "正在导出日志…"; changed.run();
        worker.execute(() -> {
            boolean saved = false;
            File file = null;
            try {
                file = new File(directory(), name);
                if (!file.isFile() || file.length() > 96L * 1024 * 1024) throw new IllegalStateException("Snapshot unavailable");
                try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("Document unavailable");
                    Files.copy(file.toPath(), output);
                }
                saved = true;
            } catch (Exception ignored) {
                // A cancelled/revoked document grant must not fall back to writing elsewhere.
            } finally { if (file != null) file.delete(); }
            final boolean success = saved;
            post(() -> {
                busy = false; status = success ? "日志已导出，可作为问题反馈附件" : "日志导出失败，请重新选择保存位置";
                toast(success ? "日志已导出" : "导出失败，请重试"); changed.run();
            });
        });
        return true;
    }

    void close() { closed = true; worker.shutdown(); }
    private void failure(Exception error) {
        android.util.Log.e("MiStepDiagnostics","Collection failed",error);
        java.io.StringWriter text=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(text));
        RuntimeDiagnosticBundle snapshot=new RuntimeDiagnosticBundle(new Date().toString(),java.util.List.of(
                new RuntimeDiagnosticBundle.Part("99-collection-error.txt","ERROR","本次采集异常原始堆栈；不代表成功",text.toString(),-1,0))).withDescription(description);
        cachedReport=snapshot;
        try{archive(snapshot);}catch(Exception ignored){}
        post(()->{busy=false;report=snapshot;status="采集失败，错误详情可导出反馈";changed.run();});
    }
    private void archive(RuntimeDiagnosticBundle snapshot) throws Exception {
        File saved=new File(directory(),"report-"+UUID.randomUUID()+".zip");
        try(FileOutputStream output=new FileOutputStream(saved)){snapshot.writeZip(output);}
        catch(Exception e){saved.delete();throw e;}
        activity.getSharedPreferences("mistep-diagnostic-session",0).edit().putString("lastReport",saved.getName()).apply();
        archivedName=saved.getName();
        File[] old=directory().listFiles((dir,name)->validName(name)&&!name.equals(archivedName)&&!name.equals(pendingName));
        if(old!=null){Arrays.sort(old,Comparator.comparingLong(File::lastModified).reversed());for(int i=1;i<old.length;i++)old[i].delete();}
        File rawDir=new File(activity.getCacheDir(),"mistep-native-recordings");
        java.util.Set<File> keep=new java.util.HashSet<>();
        for(RuntimeDiagnosticBundle.Attachment a:snapshot.attachments)keep.add(a.file);
        File[] raw=rawDir.listFiles((dir,name)->name.matches("[a-f0-9]{32}([.]pftrace|-[-a-z0-9.]+[.]txt)"));
        if(raw!=null)for(File f:raw)if(!keep.contains(f))f.delete();
    }
    private void post(Runnable action) {
        main.post(() -> { if (!closed && !activity.isFinishing() && !activity.isDestroyed()) action.run(); });
    }
    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_SHORT).show(); }
    private File directory() throws Exception {
        File file = new File(activity.getCacheDir(), "runtime-logs");
        if (!file.isDirectory() && !file.mkdirs()) throw new IllegalStateException("Cache unavailable");
        return file;
    }
    private void deletePending(String name) {
        if (!validName(name)) return;
        worker.execute(() -> { try { new File(directory(), name).delete(); } catch (Exception ignored) {} });
    }
    private static boolean validName(String name) {
        return name != null && name.matches("report-[0-9a-f-]{36}[.](zip|txt)");
    }
}
