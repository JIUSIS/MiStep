package com.jiusis.mistep;

/** Production correlation: a fresh mtime must not authenticate an old or foreign ARM. */
public final class RuntimeDiagnosticCorrelationTest {
    static int checks;
    static final String BOOT = "12345678-1234-1234-1234-123456789abc";
    static RuntimeDiagnosticBundle.Part part(String name, String status, String text) {
        return new RuntimeDiagnosticBundle.Part(name, status, "scope", text, 0, 1);
    }
    static final RuntimeDiagnosticBundle.Part REQUEST = part("03-recording-request.txt", "OK",
            "requestEpoch=2000\nrequestElapsedSeconds=100.00\nrequestBootId=" + BOOT
                    + "\nrequestServerPID=45\nrequestSystemUiPID=90\n");
    static final RuntimeDiagnosticBundle.Part PROCESS = part("04-processes.txt", "OK",
            "capturedEpoch=2007\nelapsedSeconds=107.00\nbootId=" + BOOT
                    + "\nserverPID=45\nsystemUiPID=90\n");
    static RuntimeDiagnosticBundle.Part trace(String name, String pid, String boot, long epoch, long elapsed, long gen) {
        return part(name, "OK", "source=ui-100010.txt\nmtimeEpoch=2007\n100010 ARM uptime=100010 bootId=" + boot
                + " pid=" + pid + " epochMillis=" + epoch + " elapsedRealtime=" + elapsed
                + " generation=" + gen + " duration=6000 maxLines=128 numeric-only=true\n");
    }
    static String status(RuntimeDiagnosticBundle.Part trace, RuntimeDiagnosticBundle.Part request,
            RuntimeDiagnosticBundle.Part process) {
        return RuntimeDiagnosticBundle.confirmTrace(trace, request, process).status;
    }
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static void rejected(RuntimeDiagnosticBundle.Part trace, String reason) {
        check(status(trace, REQUEST, PROCESS).equals("STALE_OR_UNCONFIRMED"), reason);
    }
    public static void main(String[] args) {
        var ui = trace("15-input-trace-ui.txt", "90", BOOT, 2000010, 100010, 1);
        check(status(ui, REQUEST, PROCESS).equals("OK"), "matching UI ARM lost");
        check(status(trace("16-input-trace-server.txt", "45", BOOT, 2000010, 100010, 9), REQUEST, PROCESS).equals("OK"), "matching WM ARM lost");
        check(status(ui, null, PROCESS).equals("HISTORICAL"), "plain snapshot claimed current recording");
        check(status(ui, REQUEST, null).equals("STALE_OR_UNCONFIRMED"), "missing process snapshot confirmed");
        check(status(ui, part(REQUEST.name, "ERROR", REQUEST.text), PROCESS).equals("STALE_OR_UNCONFIRMED"), "rejected request confirmed");
        check(status(part(ui.name, "TRUNCATED", ui.text), REQUEST, PROCESS).equals("TRUNCATED"), "truncated trace upgraded");
        check(status(part(ui.name, "MISSING", ""), REQUEST, PROCESS).equals("MISSING"), "missing trace upgraded");
        rejected(trace(ui.name, "91", BOOT, 2000010, 100010, 1), "wrong process confirmed");
        rejected(trace(ui.name, "90", BOOT.replace("abc", "def"), 2000010, 100010, 1), "previous boot confirmed");
        rejected(trace(ui.name, "90", BOOT, 1999999, 100010, 1), "old wallclock ARM confirmed by fresh mtime");
        rejected(trace(ui.name, "90", BOOT, 2000010, 99999, 1), "old elapsed ARM confirmed by fresh mtime");
        rejected(trace(ui.name, "90", BOOT, 2000010, 100009, 1), "same 10ms request bucket could contain an earlier ARM");
        rejected(trace(ui.name, "90", BOOT, 2008000, 100010, 1), "future wallclock ARM confirmed");
        rejected(trace(ui.name, "90", BOOT, 2000010, 107011, 1), "future elapsed ARM confirmed");
        rejected(trace(ui.name, "90", BOOT, 2000010, 100010, 0), "invalid generation confirmed");
        rejected(part(ui.name, "OK", "mtimeEpoch=2007\n100010 ARM uptime=100010 duration=6000 maxLines=128\n"), "legacy ARM confirmed");
        rejected(part(ui.name, "OK", ui.text.replace("elapsedRealtime=100010", "elapsedRealtime=NaN")), "invalid clock confirmed");
        rejected(part(ui.name, "OK", ui.text.replace(" pid=90", " pid=90junk")), "malformed PID confirmed");
        check(status(ui, REQUEST, part(PROCESS.name, "OK", PROCESS.text.replace("systemUiPID=90", "systemUiPID=91"))).equals("STALE_OR_UNCONFIRMED"), "restart after request confirmed");
        check(status(ui, REQUEST, part(PROCESS.name, "OK", PROCESS.text.replace(BOOT, BOOT.replace("abc", "def")))).equals("STALE_OR_UNCONFIRMED"), "boot after request confirmed");
        check(status(ui, part(REQUEST.name, "OK", REQUEST.text.replace("100.00", "NaN")), PROCESS).equals("STALE_OR_UNCONFIRMED"), "NaN request clock confirmed");
        check(RuntimeDiagnosticBundle.confirmTrace(ui, REQUEST, PROCESS).scope.contains("非跨进程共享token"), "time-window limitation omitted");
        System.out.println("RuntimeDiagnosticCorrelation checks=" + checks + " passed");
    }
}
