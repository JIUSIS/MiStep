package com.jianguo.mistep;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Keep native panel gesture raw axes in its already rotated window coordinates. */
final class MiStepPanelInput {
    private static final String PREVIOUS = "mistep.panel.previousInput";
    private static final ThreadLocal<MiStepPanelInputCoordinates> active = new ThreadLocal<>();

    static void install(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "dispatchPointerEvent", MotionEvent.class,
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra(PREVIOUS, active.get());
                    active.remove();
                    try {
                        if (MiStepHost.activeMainTransform() == null) return;
                        View view = (View) p.thisObject;
                        MotionEvent event = (MotionEvent) p.args[0];
                        if (event == null || (event.getSource() & InputDevice.SOURCE_CLASS_POINTER) == 0) return;
                        Object root = XposedHelpers.callMethod(view, "getViewRootImpl");
                        if (root == null || XposedHelpers.callMethod(root, "getView") != view) return;
                        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                                XposedHelpers.getObjectField(root, "mWindowAttributes");
                        int display = (Integer) XposedHelpers.callMethod(event, "getDisplayId");
                        // SystemUI's shared Context may retain the physical display config.
                        // Use the exact merged configuration delivered to this input window.
                        Object merged = XposedHelpers.getObjectField(root, "mLastReportedMergedConfiguration");
                        Object config = XposedHelpers.getObjectField(
                                XposedHelpers.callMethod(merged, "getMergedConfiguration"), "windowConfiguration");
                        int rotation = (Integer) XposedHelpers.callMethod(config, "getRotation");
                        String pkg = attrs.packageName == null ? view.getContext().getPackageName() : attrs.packageName;
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                            MiStepInputTrace.panelInputGate(attrs.type, display, rotation,
                                    "com.android.systemui".equals(pkg), null);
                        if (!MiStepPanelInputCoordinates.accepts(true, display, attrs.type,
                                pkg, attrs.getTitle(), rotation)) return;
                        int[] origin = new int[2]; view.getLocationOnScreen(origin);
                        int count = event.getPointerCount();
                        int[] ids = new int[count]; float[] x = new float[count], y = new float[count];
                        for (int i = 0; i < count; i++) {
                            ids[i] = event.getPointerId(i);
                            x[i] = event.getX(i) + origin[0]; y[i] = event.getY(i) + origin[1];
                        }
                        active.set(new MiStepPanelInputCoordinates(event.getDownTime(), event.getEventTime(),
                                event.getDeviceId(), event.getSource(), display, ids, x, y));
                    } catch (Throwable error) {
                        active.remove();
                        MiStepInputTrace.panelInputGate(-1, -1, -1, false, error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    MiStepPanelInputCoordinates previous = (MiStepPanelInputCoordinates) p.getObjectExtra(PREVIOUS);
                    if (previous == null) active.remove(); else active.set(previous);
                }
            });
            for (String method : new String[]{"getRawX", "getRawY"}) {
                final boolean horizontal = "getRawX".equals(method);
                XposedBridge.hookAllMethods(MotionEvent.class, method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        MiStepPanelInputCoordinates frame = active.get();
                        if (frame == null || p.hasThrowable()) return;
                        MotionEvent event = (MotionEvent) p.thisObject;
                        int index = p.args.length == 0 ? 0 : (Integer) p.args[0];
                        if (index < 0 || index >= event.getPointerCount()) return;
                        Float raw = frame.raw(event.getDownTime(), event.getEventTime(), event.getDeviceId(),
                                event.getSource(), (Integer) XposedHelpers.callMethod(event, "getDisplayId"),
                                event.getPointerId(index), horizontal);
                        if (raw != null) p.setResult(raw);
                    }
                });
            }
            XposedBridge.log("MiStep: native panel raw axes follow their rotated window during dispatch");
        } catch (Throwable error) {
            XposedBridge.log("MiStep: native panel input unavailable: " + error);
        }
    }
}
