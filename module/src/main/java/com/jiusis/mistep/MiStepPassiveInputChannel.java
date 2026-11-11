package com.jiusis.mistep;

import android.os.Binder;
import de.robv.android.xposed.XposedHelpers;

/** Owns one passive input channel; never pilfers or changes another monitor. */
final class MiStepPassiveInputChannel {
    final Object input;
    private final Object monitor;
    private boolean closed;

    private MiStepPassiveInputChannel(Object input, Object monitor) {
        this.input = input;
        this.monitor = monitor;
    }

    static MiStepPassiveInputChannel open(Object service, String name, ClassLoader loader) {
        try {
            return new MiStepPassiveInputChannel(
                    XposedHelpers.callMethod(service, "monitorInput", name, 0), null);
        } catch (NoSuchMethodError missingLegacyMethod) {
            // New ROMs removed global motion monitors. Their native penetration path
            // retains DO_NOT_PILFER spies, as used by the ROM's own MultiTaskSwitch.
            Object monitor = XposedHelpers.callMethod(service, "monitorGestureInput",
                    new Binder(), name, 0);
            try {
                Object input = XposedHelpers.callMethod(monitor, "getInputChannel");
                Object token = XposedHelpers.callMethod(input, "getToken");
                Object windows = XposedHelpers.getObjectField(service, "mInputMonitors");
                Object spy;
                synchronized (windows) {
                    spy = XposedHelpers.callMethod(windows, "get", token);
                }
                if (spy == null) throw new IllegalStateException("passive monitor window missing");
                Object handle = XposedHelpers.getObjectField(spy, "mWindowHandle");
                Object surface = XposedHelpers.getObjectField(spy, "mInputSurface");
                int passive = XposedHelpers.getStaticIntField(
                        XposedHelpers.findClass("android.os.InputConfig", loader), "DO_NOT_PILFER");
                int config = XposedHelpers.getIntField(handle, "inputConfig");
                XposedHelpers.setIntField(handle, "inputConfig", config | passive);
                Object transaction = XposedHelpers.newInstance(
                        XposedHelpers.findClass("android.view.SurfaceControl$Transaction", loader));
                try {
                    XposedHelpers.callMethod(transaction, "setInputWindowInfo", surface, handle);
                    XposedHelpers.callMethod(transaction, "apply");
                } finally {
                    XposedHelpers.callMethod(transaction, "close");
                }
                return new MiStepPassiveInputChannel(input, monitor);
            } catch (Throwable error) {
                try { XposedHelpers.callMethod(monitor, "dispose"); } catch (Throwable ignored) { }
                throw error;
            }
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        try { XposedHelpers.callMethod(monitor == null ? input : monitor, "dispose"); }
        catch (Throwable ignored) { }
    }
}
