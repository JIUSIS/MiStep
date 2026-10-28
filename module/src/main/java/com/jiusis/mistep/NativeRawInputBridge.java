package com.jiusis.mistep;

import android.os.Build;

import java.io.FileInputStream;
import java.security.MessageDigest;

import de.robv.android.xposed.XposedBridge;

/** System-server-only raw-input bridge for the inspected arm64 ROM profiles. */
final class NativeRawInputBridge {
    private static boolean loaded;
    private static Boolean lastReady;

    private NativeRawInputBridge() {}

    static void install() {
        try {
            if (!java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
                XposedBridge.log("MiStepProbe: native raw unsupported ABI; hook skipped");
                return;
            }
            String profile = profileForDeviceLibraries();
            if (profile == null) {
                XposedBridge.log("MiStepProbe: native raw profile unknown; hook skipped");
                return;
            }
            System.loadLibrary("mistep_raw");
            loaded = true;
            XposedBridge.log("MiStepProbe: native raw library loaded profile=" + profile);
            update(1, 0, 0);
        } catch (Throwable error) {
            XposedBridge.log("MiStepProbe: native raw load unavailable " + error);
        }
    }

    private static String profileForDeviceLibraries() throws Exception {
        boolean popsicle = matches("/system/lib64/libinputflinger.so",
                "d5287b7356b6693fbab60b555073b7f28f8cfba720710ffd8aa53f949eaa4938")
                && matches("/system/lib64/libinput.so",
                "6866e046b0909072e77dcc80015e9b8986cf177cdb7e71e9bb625e81c4c636f2")
                && matches("/system/lib64/libui.so",
                "6ae57e67eb27dea87f5b9775d94d75f2c490f3c5418d859b1cc37f5ee52dfc35");
        if (popsicle) return "popsicle-17pm";
        boolean xaga = matches("/system/lib64/libinputflinger.so",
                "34df4cf5b6af3e6cfb16a06d5401fe23d302e05c4d082773f4e0bf22e5d8139f")
                && matches("/system/lib64/libinput.so",
                "94a7d234c897f6ce02b1ab74189380167923c86869358024072be51ec7f1a768")
                && matches("/system/lib64/libui.so",
                "67146e36c03eaac4f7016d933ab393de92f8901069820e1ceec6464cefd2e428");
        return xaga ? "xaga-11t" : null;
    }

    static void update(float scale, float offsetY, float offsetX) {
        update(scale, offsetY, offsetX, 0, 0, -1);
    }

    static void update(float scale, float offsetY, float offsetX, int width, int height, int rotation) {
        if (!loaded) return;
        try {
            boolean ready = updateNative(scale, offsetY, offsetX, width, height, rotation);
            if (lastReady == null || lastReady.booleanValue() != ready) {
                XposedBridge.log("MiStepProbe: native raw signed publish ready=" + ready);
                lastReady = ready;
            }
        } catch (Throwable error) {
            XposedBridge.log("MiStepProbe: native raw update unavailable " + error);
        }
    }

    private static boolean matches(String path, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[32768];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT,
                "%02x", value & 255));
        return expected.equals(actual.toString());
    }

    private static native boolean updateNative(float scale, float offsetY, float offsetX,
            int width, int height, int rotation);
}
