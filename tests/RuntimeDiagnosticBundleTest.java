package com.jiusis.mistep;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class RuntimeDiagnosticBundleTest {
    static int checks;
    static void check(boolean b) { checks++; if (!b) throw new AssertionError(checks); }
    public static void main(String[] args) throws Exception {
        ArrayList<RuntimeDiagnosticBundle.Part> parts = new ArrayList<>();
        parts.add(new RuntimeDiagnosticBundle.Part("01-log.txt", "TRUNCATED", "recent bounded module records",
                "MiStepProbe: failure\njava.lang.Exception: example\n    at MiStep.test(Test.java:1)\n中文", 0, 20));
        parts.add(new RuntimeDiagnosticBundle.Part("02-root.txt", "ERROR", "root denied", "denied", 1, 8));
        RuntimeDiagnosticBundle bundle = new RuntimeDiagnosticBundle("2026-11-08", parts).withDescription("底部左右滑无响应");
        parts.clear(); check(bundle.parts.size() == 3);
        check(bundle.preview().contains("TRUNCATED") && bundle.preview().contains("ERROR"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bundle.writeZip(bytes);
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry entry; while ((entry = input.getNextEntry()) != null) entries.put(entry.getName(), input.readAllBytes());
        }
        check(entries.size() == 5 && entries.containsKey("README.txt"));
        Properties index = new Properties(); index.load(new InputStreamReader(new ByteArrayInputStream(entries.get("manifest.properties")), StandardCharsets.UTF_8));
        check(index.getProperty("schema").equals("mistep-diagnostics-1"));
        for (int i = 0; i < 3; i++) {
            String prefix = "part."+i+"."; byte[] data = entries.get(index.getProperty(prefix+"file"));
            check(Integer.parseInt(index.getProperty(prefix+"bytes")) == data.length);
            check(index.getProperty(prefix+"sha256").equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))));
        }
        check(new String(entries.get("01-log.txt"), StandardCharsets.UTF_8).contains("    at MiStep.test(Test.java:1)\n中文"));
        check(new String(entries.get("00-user-report.txt"), StandardCharsets.UTF_8).equals("底部左右滑无响应"));
        check(bundle.withDescription("新描述").parts.size() == 3);
        check(bundle.parts.get(0).text.equals("底部左右滑无响应"));
        try { new RuntimeDiagnosticBundle.Part("../private.txt", "OK", "", "", 0, 0); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }
        try { new RuntimeDiagnosticBundle("", List.of(bundle.parts.get(0), bundle.parts.get(0))); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }
        var process = new RuntimeDiagnosticBundle.Part("04-processes.txt", "OK", "",
                "serverPID=45\nsystemUiPID=90\nversionName=example\n", 0, 0);
        var module = new RuntimeDiagnosticBundle.Part("06-module-current.txt", "OK", "",
                "[ 2026 1000: 45: 45 I/LSPosed ] attached to system_server build=example end\n"
                + "[ 2026 1000: 90: 90 I/LSPosed ] attached to com.android.systemui build=example end\n", 0, 0);
        check(RuntimeDiagnosticBundle.analyze(List.of(process, module)).status.equals("OK"));
        var wrong = new RuntimeDiagnosticBundle.Part(module.name, "OK", "", module.text.replace("45: 45", "44: 44"), 0, 0);
        check(RuntimeDiagnosticBundle.analyze(List.of(process, wrong)).status.equals("INCOMPLETE"));
        check(RuntimeDiagnosticBundle.analyze(List.of(process)).status.equals("INCOMPLETE"));
        System.out.println("RuntimeDiagnosticBundle checks="+checks+" passed");
        if (args.length == 1 && args[0].equals("--sources")) {
            for (RuntimeDiagnosticSources.Source source : RuntimeDiagnosticSources.ALL)
                System.out.println(source.name()+"\t"+Base64.getEncoder().encodeToString(source.script().getBytes(StandardCharsets.UTF_8)));
        }
    }
}
