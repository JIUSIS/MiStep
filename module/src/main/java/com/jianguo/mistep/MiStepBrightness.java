package com.jianguo.mistep;

import android.hardware.display.DisplayManager;
import android.view.Display;
import de.robv.android.xposed.XposedHelpers;

/** Uses the current user-accessible range, as the native non-V1 slider does. */
final class MiStepBrightness {
    final float value, minimum, maximum;

    private MiStepBrightness(float value, float minimum, float maximum) {
        if (!Float.isFinite(value) || !Float.isFinite(minimum) || !Float.isFinite(maximum)
                || minimum < 0f || maximum > 1f || maximum <= minimum)
            throw new IllegalStateException("Unavailable display brightness range");
        this.minimum = minimum;
        this.maximum = maximum;
        this.value = clamp(value);
    }

    static MiStepBrightness read(DisplayManager displays) {
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("Default display unavailable");
        Object info = XposedHelpers.callMethod(display, "getBrightnessInfo");
        if (info == null) throw new IllegalStateException("BrightnessInfo unavailable");
        return new MiStepBrightness(XposedHelpers.getFloatField(info, "brightness"),
                XposedHelpers.getFloatField(info, "brightnessMinimum"),
                XposedHelpers.getFloatField(info, "brightnessMaximum"));
    }

    int progress(Class<?> utils) {
        return ((Number) XposedHelpers.callStaticMethod(utils, "convertLinearToGammaFloat",
                value, minimum, maximum)).intValue();
    }

    void write(DisplayManager displays, Class<?> utils, int progress) {
        float linear = ((Number) XposedHelpers.callStaticMethod(utils, "convertGammaToLinearFloat",
                progress, minimum, maximum)).floatValue();
        if (!Float.isFinite(linear)) throw new IllegalStateException("Invalid brightness conversion");
        XposedHelpers.callMethod(displays, "setBrightness", Display.DEFAULT_DISPLAY, clamp(linear));
    }

    private float clamp(float value) { return Math.max(minimum, Math.min(maximum, value)); }
}
