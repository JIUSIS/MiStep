package com.jiusis.mistep;

import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Indexed evidence; missing or truncated data is never reported as a successful capture. */
final class RuntimeDiagnosticBundle {
    static final class Part {
        final String name, status, scope, text;
        final int exit;
        final long duration;
        Part(String name, String status, String scope, String text, int exit, long duration) {
            if (!name.matches("[a-z0-9][a-z0-9._-]{0,70}[.]txt")) throw new IllegalArgumentException("Unsafe entry");
            this.name = name; this.status = status; this.scope = scope; this.text = text;
            this.exit = exit; this.duration = duration;
        }
    }
    final List<Part> parts;
    final List<Attachment> attachments;
    static final class Attachment {
        final String name, status, scope; final File file;
        Attachment(String name, File file, String status, String scope) {
            if (!name.matches("[a-z0-9][a-z0-9._-]{0,70}[.](pftrace|txt)")) throw new IllegalArgumentException("Unsafe attachment");
            this.name=name;this.file=file;this.status=status;this.scope=scope;
        }
    }
    private final String created;
    RuntimeDiagnosticBundle(String created, List<Part> parts) {
        this(created, parts, List.of());
    }
    RuntimeDiagnosticBundle(String created, List<Part> parts, List<Attachment> attachments) {
        this.created = created;
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (Part part : parts) if (!names.add(part.name)) throw new IllegalArgumentException("Duplicate entry");
        for (Attachment a : attachments) if (!names.add(a.name)) throw new IllegalArgumentException("Duplicate attachment");
        this.parts = Collections.unmodifiableList(new ArrayList<>(parts));
        this.attachments = Collections.unmodifiableList(new ArrayList<>(attachments));
    }
    String status() {
        int available = 0, missing = 0;
        for (Part part : parts) { if ("OK".equals(part.status)) available++; else missing++; }
        for (Attachment a : attachments) { if ("OK".equals(a.status)) available++; else missing++; }
        return "已采集 " + available + " 项，" + missing + " 项为空、受限、历史、未确认或截断（详见索引）";
    }
    RuntimeDiagnosticBundle withDescription(String description) {
        ArrayList<Part> updated = new ArrayList<>();
        updated.add(new Part("00-user-report.txt", description.isBlank() ? "EMPTY" : "OK", "用户填写的现象/时间/复现步骤",
                RuntimeLogText.tail(description, 4000), 0, 0));
        for (Part part : parts) if (!part.name.equals("00-user-report.txt")) updated.add(part);
        return new RuntimeDiagnosticBundle(created, updated, attachments);
    }
    /** Correlates existing evidence only. File mtime alone never confirms an ARM. */
    static Part confirmTrace(Part trace, Part request, Part process) {
        if (!"OK".equals(trace.status)) return trace;
        String reason = null;
        if (request == null) reason = "HISTORICAL：未请求本次录制";
        else if (!"OK".equals(request.status) || process == null || !"OK".equals(process.status))
            reason = "UNCONFIRMED：请求或当前进程快照不可用";
        else try {
            java.util.regex.Matcher arm = java.util.regex.Pattern.compile("(?m)^[0-9]+ ARM uptime=[0-9]+ ([^\\r\\n]+)$").matcher(trace.text);
            if (!arm.find()) throw new IllegalArgumentException();
            String header = arm.group(1);
            String boot = value(header, "bootId", false);
            String pid = value(header, "pid", false);
            String expected = trace.name.startsWith("15-") ? "SystemUiPID" : "ServerPID";
            if (!boot.matches("[a-fA-F0-9-]{36}") || !pid.matches("[0-9]+")
                    || !boot.equals(value(request.text, "requestBootId", true))
                    || !boot.equals(value(process.text, "bootId", true))
                    || !pid.equals(value(request.text, "request" + expected, true))
                    || !pid.equals(value(process.text, Character.toLowerCase(expected.charAt(0)) + expected.substring(1), true)))
                reason = "UNCONFIRMED：boot/PID不匹配（可能已重启）";
            else {
                long epoch = Long.parseLong(value(header, "epochMillis", false));
                long elapsed = Long.parseLong(value(header, "elapsedRealtime", false));
                long generation = Long.parseLong(value(header, "generation", false));
                double requestElapsed = Double.parseDouble(value(request.text, "requestElapsedSeconds", true)) * 1000;
                double capturedElapsed = Double.parseDouble(value(process.text, "elapsedSeconds", true)) * 1000;
                long requestEpoch = Long.parseLong(value(request.text, "requestEpoch", true)) * 1000;
                long capturedEpoch = Long.parseLong(value(process.text, "capturedEpoch", true)) * 1000;
                if (generation <= 0 || !Double.isFinite(requestElapsed) || !Double.isFinite(capturedElapsed)
                        // /proc/uptime has 10ms granularity. Exclude the request bucket
                        // so an ARM just before the request cannot pass in the same tick.
                        || elapsed < requestElapsed + 10 || elapsed > capturedElapsed + 10
                        || epoch < requestEpoch || epoch >= capturedEpoch + 1000)
                    reason = "UNCONFIRMED：ARM不在本次请求至快照的墙钟/elapsed时间窗";
            }
        } catch (RuntimeException missing) { reason = "UNCONFIRMED：缺少有效ARM身份/时钟字段"; }
        String status = reason == null ? "OK" : request == null ? "HISTORICAL" : "STALE_OR_UNCONFIRMED";
        String scope = trace.scope + "；" + (reason == null ? "同boot/PID且ARM位于本次请求时间窗；非跨进程共享token" : reason);
        return new Part(trace.name, status, scope, trace.text, trace.exit, trace.duration);
    }
    private static String value(String text, String key, boolean line) {
        java.util.regex.Matcher match = java.util.regex.Pattern.compile((line ? "(?m)^" : "(?:^| )")
                + java.util.regex.Pattern.quote(key) + "=([^\\s]+)" + (line ? "[ \\t]*$" : "(?: |$)")).matcher(text);
        if (!match.find()) throw new IllegalArgumentException("Missing " + key);
        return match.group(1);
    }
    static Part analyze(List<Part> parts) {
        String process = "", records = "", settings = "";
        java.util.Map<String,String> loaded = new java.util.HashMap<>();
        for (Part part : parts) {
            if (part.name.equals("04-processes.txt")) process = part.text;
            if (part.name.startsWith("06-module-") || part.name.startsWith("07-module-")) records += part.text + "\n";
            if (part.name.equals("05-settings.txt")) settings = part.text;
            if ("OK".equals(part.status) && (part.name.equals("24-loaded-server.txt")||part.name.equals("25-loaded-systemui.txt")))
                loaded.put(part.name,part.text);
        }
        StringBuilder report = new StringBuilder("自动核验只解释已有证据，不代表功能验收或完整故障诊断。\n");
        java.util.regex.Matcher v = java.util.regex.Pattern.compile("versionName=([^\\s]+)").matcher(process);
        String version = v.find() ? v.group(1) : null;
        boolean complete = version != null;
        report.append("安装版本：").append(version == null ? "UNKNOWN" : version).append('\n');
        for (String[] key : new String[][]{{"serverPID", "system_server"}, {"systemUiPID", "com.android.systemui"}}) {
            java.util.regex.Matcher p = java.util.regex.Pattern.compile("(?m)^" + key[0] + "=([0-9]+)").matcher(process);
            String pid = p.find() ? p.group(1) : null;
            boolean found = false;
            if (pid != null && version != null) for (String line : records.split("\n")) {
                if (line.contains("attached to " + key[1]) && line.contains("build=" + version + " ")
                        && java.util.regex.Pattern.compile(":[ ]*" + pid + ":[ ]*[0-9]+[ ]+[VDIWEF]/").matcher(line).find()) found = true;
            }
            String independent = loaded.get(key[0].equals("serverPID")?"24-loaded-server.txt":"25-loaded-systemui.txt");
            if(pid!=null && version!=null && independent!=null) try {
                found |= pid.equals(value(independent,"pid",true))
                        && value(process,"bootId",true).equals(value(independent,"bootId",true))
                        && independent.contains("attached to "+key[1])
                        && independent.contains("build="+version+" ");
            } catch(RuntimeException invalid) { /* Stale/incomplete identity never confirms loading. */ }
            complete &= found;
            report.append(key[1]).append(" PID=").append(pid).append(found ? " 同版加载marker已找到\n"
                    : " UNKNOWN：未找到同版当前PID marker；可能未加载、日志缺失或被截断，不能仅凭缺失断定原因。\n");
        }
        if (settings.contains("name=\"entry_gesture\" value=\"1\"") && !settings.contains("hide_gesture_line=1\n")) {
            report.append("底部模式已选择，但小白条未确认关闭；运行时会回退原状态栏入口。\n"); complete = false;
        }
        report.append("若日志无异常且功能仍失败，录制问题后导出并附具体时间，不能据此判定没有bug。\n");
        for (Part part : parts) if (part.name.startsWith("15-input-") || part.name.startsWith("16-input-"))
            report.append(part.name).append(" [").append(part.status).append("] ").append(part.scope).append('\n');
        return new Part("17-loading-checks.txt", complete ? "OK" : "INCOMPLETE", "APK/当前PID/实际加载版交叉核验", report.toString(), 0, 0);
    }
    String preview() {
        StringBuilder out = new StringBuilder("MiStep 诊断包\n采集时间：" + created + "\n" + status() + "\n\n");
        for (Part part : parts) out.append(part.name).append("  [").append(part.status).append("] ")
                .append(part.duration).append("ms  ").append(part.scope).append('\n');
        for (Part part : parts) {
            out.append("\n===== ").append(part.name).append(" [").append(part.status).append("] =====\n");
            out.append(RuntimeLogText.tail(part.text, 1800));
        }
        for (Attachment a : attachments) out.append("\n").append(a.name).append(" [").append(a.status).append("] bytes=").append(a.file.length()).append(" 原始证据，随ZIP导出。\n");
        return out.toString();
    }
    void writeZip(OutputStream destination) throws Exception {
        Properties index = new Properties();
        index.setProperty("schema", attachments.isEmpty()?"mistep-diagnostics-1":"mistep-diagnostics-2"); index.setProperty("created", created);
        index.setProperty("parts", Integer.toString(parts.size()+attachments.size()));
        try (ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8)) {
            put(zip, "README.txt", "MiStep 诊断包\n" + status() + "\n"
                    + "先看 manifest.properties 的每项状态，再核对版本/当前进程和时间线。\n"
                    + "EMPTY/MISSING/ERROR/TIMEOUT/SKIPPED/TRUNCATED 都不是正常运行的证明。\n"
                    + "日志有时间和大小上限；未记录或已覆盖的历史无法事后还原。\n"
                    + "input-traces 为用户主动录制的短时数值追踪；OK仅表示ARM的boot/PID/墙钟/elapsed匹配请求时间窗，不代表触摸或动画成功。\n"
                    + "HISTORICAL/STALE_OR_UNCONFIRMED不能当本次复现证据；旧6秒input追踪无共享token，新的系统录制用requestId关联自有文件。\n"
                    + "系统录制跨熄屏，最长60秒。pftrace原始记录可用Winscope/Perfetto分析；先看22-native-trace-checks。\n"
                    + "缺少数据源、只有一个快照、时间未覆盖故障或环形覆盖都不能当完整过程；原始TraceStats保留。\n"
                    + "不主动采集截图、录像、剪贴板、通知正文或原始输入，不自动上传；原生系统日志可能包含应用名称或其他敏感信息，请检查后分享。\n");
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i); byte[] bytes = part.text.getBytes(StandardCharsets.UTF_8);
                String prefix = "part." + i + ".";
                index.setProperty(prefix + "file", part.name); index.setProperty(prefix + "status", part.status);
                index.setProperty(prefix + "scope", part.scope); index.setProperty(prefix + "exit", Integer.toString(part.exit));
                index.setProperty(prefix + "durationMs", Long.toString(part.duration));
                index.setProperty(prefix + "bytes", Integer.toString(bytes.length));
                index.setProperty(prefix + "sha256", java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
                put(zip, part.name, part.text);
            }
            for (int j=0;j<attachments.size();j++) {
                Attachment a=attachments.get(j);
                if (!a.file.isFile() || a.file.length()>80L*1024*1024) throw new java.io.IOException("Trace attachment unavailable");
                String prefix="part."+(parts.size()+j)+".";
                index.setProperty(prefix+"file",a.name);index.setProperty(prefix+"status",a.status);index.setProperty(prefix+"scope",a.scope);
                index.setProperty(prefix+"exit","0");index.setProperty(prefix+"durationMs","0");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
                zip.putNextEntry(new ZipEntry(a.name));
                try(FileInputStream input=new FileInputStream(a.file)) { byte[] b=new byte[32768];int n;while((n=input.read(b))!=-1){count+=n;if(count>80L*1024*1024)throw new java.io.IOException("Trace grew");digest.update(b,0,n);zip.write(b,0,n);} }
                zip.closeEntry();index.setProperty(prefix+"bytes",Long.toString(count));
                index.setProperty(prefix+"sha256",java.util.HexFormat.of().formatHex(digest.digest()));
            }
            StringWriter writer = new StringWriter(); index.store(writer, "MiStep diagnostic collection index (UTF-8)");
            put(zip, "manifest.properties", writer.toString());
        }
    }
    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
