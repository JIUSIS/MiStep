package com.jiusis.mistep;

public final class RuntimeLogTextTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError(checks); }
    public static void main(String[] args) {
        check(!RuntimeLogText.hasEntries("[LSPosed 模块近期日志]\n未找到可读日志"));
        check(RuntimeLogText.hasEntries("11-05 1000 MiStepProbe: native swap error"));
        check(RuntimeLogText.hasEntries("[com.jiusis.mistep,XposedBridge] exception"));
        check(!RuntimeLogText.hasEntries("OtherModule: other app details"));
        check(RuntimeLogText.tail("运行日志😀", 50).equals("运行日志😀"));
        check(!Character.isLowSurrogate(RuntimeLogText.tail("abc😀结束", 5).split("\n", 2)[1].charAt(0)));
        RuntimeLogText.Buffer buffer = new RuntimeLogText.Buffer();
        char[] chunk = "旧日志😀\n".repeat(200).toCharArray();
        for (int i = 0; i < 700; i++) buffer.append(chunk, chunk.length);
        char[] latest = "\nMiStepProbe: newest failure\n".toCharArray();
        buffer.append(latest, latest.length);
        String bounded = buffer.toString();
        check(bounded.length() <= RuntimeLogText.CAPACITY + 50);
        check(bounded.endsWith(new String(latest)));
        check(bounded.startsWith("[较早内容已省略"));
        check(!Character.isLowSurrogate(bounded.split("\n", 2)[1].charAt(0)));
        String preview = RuntimeLogText.tail(bounded, RuntimeLogText.PREVIEW);
        check(preview.endsWith(new String(latest)) && preview.length() <= RuntimeLogText.PREVIEW + 50);
        System.out.println("RuntimeLogText checks=" + checks + " passed");
    }
}
