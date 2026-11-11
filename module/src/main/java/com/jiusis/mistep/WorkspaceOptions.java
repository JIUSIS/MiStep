package com.jiusis.mistep;

/** Immutable user choices. Invalid stored values retain the existing workspace behavior. */
final class WorkspaceOptions {
    static final int GROW_IN_PLACE = 0, FIXED_MAIN = 1;
    static final int STATUS_BAR_INWARD = 0, BOTTOM_CENTER_OUTWARD = 1;
    static final int CONTROL_CENTER = 0, WIFI = 1, MOBILE_DATA = 2, BLUETOOTH = 3,
            VPN = 4, DEVICE_NAME = 5, PAYMENT = 6, SCANNER = 7, TRANSIT = 8,
            VOLUME = 9, BRIGHTNESS = 10, EXIT = 11, SYSTEM_SETTINGS = 12,
            CLIPBOARD = 13, PREVIOUS_APP = 14, NEXT_APP = 15, TORCH = 16,
            SCREENSHOT = 17, SCREEN_RECORD = 18;
    static final int[] CONTROL_IDS = {CONTROL_CENTER, WIFI, MOBILE_DATA, BLUETOOTH, VPN,
            DEVICE_NAME, PAYMENT, SCANNER, TRANSIT, VOLUME, BRIGHTNESS};
    static final int[] HEADER_IDS = {EXIT, SYSTEM_SETTINGS, CLIPBOARD, PREVIOUS_APP,
            NEXT_APP, TORCH, SCREENSHOT, SCREEN_RECORD};
    private static final int KNOWN_BUTTONS = (1 << 19) - 1;
    static final WorkspaceOptions DEFAULT = new WorkspaceOptions(0, GROW_IN_PLACE, STATUS_BAR_INWARD);
    final int hiddenButtons, swapMode, entryGesture;
    final boolean keepOnLock;

    WorkspaceOptions(int hiddenButtons, int swapMode) {
        this(hiddenButtons, swapMode, STATUS_BAR_INWARD);
    }

    WorkspaceOptions(int hiddenButtons, int swapMode, int entryGesture) {
        this(hiddenButtons, swapMode, entryGesture, false);
    }

    WorkspaceOptions(int hiddenButtons, int swapMode, int entryGesture, boolean keepOnLock) {
        this.hiddenButtons = hiddenButtons & KNOWN_BUTTONS;
        this.swapMode = swapMode == FIXED_MAIN ? FIXED_MAIN : GROW_IN_PLACE;
        this.entryGesture = entryGesture == BOTTOM_CENTER_OUTWARD
                ? BOTTOM_CENTER_OUTWARD : STATUS_BAR_INWARD;
        this.keepOnLock = keepOnLock;
    }

    boolean visible(int button) {
        if (button < 0 || button > SCREEN_RECORD) throw new IllegalArgumentException("Unknown button");
        return (hiddenButtons & (1 << button)) == 0;
    }

    WorkspaceOptions withVisible(int button, boolean visible) {
        visible(button);
        return new WorkspaceOptions(visible ? hiddenButtons & ~(1 << button)
                : hiddenButtons | (1 << button), swapMode, entryGesture, keepOnLock);
    }

    WorkspaceOptions withSwapMode(int mode) { return new WorkspaceOptions(hiddenButtons, mode, entryGesture, keepOnLock); }
    WorkspaceOptions withEntryGesture(int mode) { return new WorkspaceOptions(hiddenButtons, swapMode, mode, keepOnLock); }
    WorkspaceOptions withKeepOnLock(boolean keep) { return new WorkspaceOptions(hiddenButtons, swapMode, entryGesture, keep); }

    boolean mainOnRightAfterSwap(boolean currentRight) {
        return swapMode == FIXED_MAIN ? currentRight : !currentRight;
    }

    static String modeLabel(int mode) { return mode == FIXED_MAIN ? "固定主区域" : "原地放大"; }

    static String label(int button) {
        switch (button) {
            case CONTROL_CENTER: return "显示控制中心";
            case WIFI: return "Wi-Fi";
            case MOBILE_DATA: return "移动数据";
            case BLUETOOTH: return "蓝牙";
            case VPN: return "VPN";
            case DEVICE_NAME: return "机型信息";
            case PAYMENT: return "付款码";
            case SCANNER: return "扫一扫";
            case TRANSIT: return "乘车码";
            case VOLUME: return "音量";
            case BRIGHTNESS: return "亮度";
            case EXIT: return "退出";
            case SYSTEM_SETTINGS: return "系统设置";
            case CLIPBOARD: return "剪贴板";
            case PREVIOUS_APP: return "上一个快捷应用";
            case NEXT_APP: return "下一个快捷应用";
            case TORCH: return "手电筒";
            case SCREENSHOT: return "截图";
            case SCREEN_RECORD: return "录屏";
            default: throw new IllegalArgumentException("Unknown button");
        }
    }
}
