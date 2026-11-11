package com.jiusis.mistep;

/** Fixed read-only commands. No user text, intent value or export path enters a shell. */
final class RuntimeDiagnosticSources {
    record Source(String name, String scope, String script) {}
    static final Source[] ALL = {
        new Source("24-loaded-server.txt", "当前server独立启动身份；须核boot/PID/build", "cat /data/system/mistep-diagnostics/loaded.txt"),
        new Source("25-loaded-systemui.txt", "当前UI独立启动身份；兼容系统或应用UID", "cat /data/user_de/0/com.android.systemui/files/mistep-diagnostics/loaded.txt"),
        new Source("26-events-server.txt", "server近期有界关键状态，跨熄屏；非原始输入", "cat /data/system/mistep-diagnostics/events.txt"),
        new Source("27-events-systemui.txt", "UI近期有界锁屏/缩放/恢复/错误事件", "cat /data/user_de/0/com.android.systemui/files/mistep-diagnostics/events.txt"),
        new Source("04-processes.txt", "本次启动/当前PID/APK版本；加载版须与模块marker交叉核对", """
                set -e
                date -u; date '+capturedEpoch=%s'
                printf 'bootId='; cat /proc/sys/kernel/random/boot_id
                printf 'elapsedSeconds='; cut -d ' ' -f 1 /proc/uptime
                printf 'serverPID='; pidof system_server
                printf 'systemUiPID='; pidof com.android.systemui
                printf 'launcherPID='; pidof com.miui.home || true
                dumpsys package com.jiusis.mistep | grep -E 'versionCode=|versionName=|codePath=|lastUpdateTime=|userId='
                """),
        new Source("05-settings.txt", "工作区缩放/手势提示线/导航/旋转/模块自己的偏好", """
                set -e
                for key in mistep_zoom hide_gesture_line force_fsg_nav_bar; do printf '%s=' "$key"; settings get global "$key"; done
                for key in accelerometer_rotation user_rotation; do printf '%s=' "$key"; settings get system "$key"; done
                cat /data/user_de/0/com.jiusis.mistep/shared_prefs/workspace_settings.xml
                for key in ro.mi.os.version.name ro.miui.ui.version.name ro.build.version.incremental ro.build.version.security_patch; do printf '%s=' "$key"; getprop "$key"; done
                """),
        moduleLog("06-module-current.txt", 1), moduleLog("07-module-previous.txt", 2),
        new Source("08-module-logcat.txt", "指定模块标签所有buffer；最多近期16000条，非全量系统日志", """
                logcat -b all -d -v threadtime -t 16000 -s MiStepProbe:V MiStepRuntime:V MiStepHomeTrace:V MiStepReturnTrace:V MiStepRaw:V MiStepRawInput:V '*:S'
                """),
        new Source("09-crashes.txt", "崩溃buffer最多4000行；仅MiStep/system_server/SystemUI进程（含已结束的进程）", """
                logcat -b crash -d -v threadtime -t 4000 | awk '
                { lines[NR]=$0; pids[NR]=$3; if (/Process: (com[.]android[.]systemui|com[.]jiusis[.]mistep|system_server)(,|$)/) keep[$3]=1; }
                END {for (i=1;i<=NR;i++) if (keep[pids[i]]) print lines[i];}'
                """),
        new Source("18-native-input.txt", "原生输入分发最近1200条：穿透/取消/通道接管；非全量系统日志", """
                logcat -d -v threadtime -t 1200 -s InputDispatcher:V MiInputDispatcher:V '*:S'
                """),
        new Source("19-launcher-gestures.txt", "OS4桌面原生手势的任务识别/热区/返回执行或取消；指定标签近期4000条中最多500行，其他ROM可能无此标签", """
                logcat -b all -d -v threadtime -t 4000 -s hyper_launcher_app:I '*:S' | awk '
                /task_filter_helper|get_running_task_for_gesture|on_task_moved_to_front|before_handle_down_event|update_fsg_gesture_region|gesture_stub_view_window|gesture_input_back_helper/ {lines[n%500]=$0; n++;}
                END {start=n>500?n-500:0; for(i=start;i<n;i++) print lines[i%500]; if(n>500) print "SOURCE_TRUNCATED_to_latest_500_lines";}'
                """),
        new Source("10-task-state.txt", "任务树/焦点/窗口模式/边界；不采集Intent或应用内容", """
                dumpsys activity activities | grep -E 'Task\\{|RootTask|mResumedActivity|topResumedActivity|mFocusedRootTask|windowingMode|mBounds=|mRotation=|mVisibleRequested'
                """),
        new Source("11-window-state.txt", "锁屏/显示/焦点/窗口缩放与边界", """
                dumpsys window policy | grep -E 'showing=|inputRestricted=|screenState=|mAwake=|mKeyguard'
                dumpsys window displays | grep -E 'DisplayContent|mDisplayInfo|mDisplayFrames|mCurrentFocus|mFocusedApp|init=|cur=|app=|mScreenRotation'
                dumpsys window windows | grep -E 'Window #[0-9]|mOwnerUid|mAttrs|mGlobalScale|mInvGlobalScale|mCompatScale|mFrame=|mWindowFrames|mDisplayId=|mInputChannel'
                """),
        new Source("12-input-state.txt", "真实输入窗口名称/owner/frame/InputConfig/touchable/变换及焦点；最多500行，不含按键历史", """
                dumpsys input | awk '
                function emit(s) {gsub(/applicationInfo[.]name=[^,]*/, "applicationInfo.name=<omitted>", s); gsub(/applicationInfo[.]token=[^,]*/, "applicationInfo.token=<omitted>", s); if (++n<=500) print s;}
                /^[[:space:]]*[0-9]+: name=/ {emit($0); matrix=0; next;}
                /FocusedWindows:|FocusedApplications:|TouchStates(ByDisplay)?:/ {emit($0); context=1; next;}
                /logicalSize|orientation=|DispatchEnabled|DispatchFrozen/ {emit($0); next;}
                /^[[:space:]]*transform / {emit($0); matrix=3; next;}
                matrix>0 && /^[[:space:]]*[-0-9.]+[[:space:]]+[-0-9.]+[[:space:]]+[-0-9.]+[[:space:]]*$/ {emit($0); matrix--; next;}
                context>0 && /^[[:space:]]*displayId=/ {emit($0); context--; next;}
                /^[[:space:]]*(MiStepGesture|miui-gesture|GestureStub)/ {emit($0);}
                END {if (n>500) print "SOURCE_TRUNCATED_at_500_lines";}'
                """),
        new Source("13-display-state.txt", "物理/逻辑尺寸和密度、显示朝向", """
                set -e
                wm size; wm density
                dumpsys display | grep -E 'DisplayDeviceInfo|mBaseDisplayInfo|mOverrideDisplayInfo|mCurrentOrientation|mCurrentLayerStackRect|mCurrentDisplayRect'
                """),
        new Source("14-surface-layers.txt", "相关任务/窗口/IME/壁纸层的矩阵、裁切、alpha和相对层序；最多500行，非图像或buffer内容", """
                dumpsys SurfaceFlinger | awk '
                function emit(s) {if (++n<=350) print s;}
                /Layer [[][0-9]+[]]/ {keep=($0 ~ /MiStep|TaskView|Task=|Magnification|InputMethod|Ime|GestureStub|Wallpaper|AppZoomOut|ActivityRecordInputSink|VRI-/); matrix=0; if (keep) emit($0); next;}
                keep && /geomLayerTransform/ {emit($0); matrix=3; next;}
                keep && matrix>0 && /^[[:space:]]*[-0-9.]+[[:space:]]+[-0-9.]+[[:space:]]+[-0-9.]+[[:space:]]*$/ {emit($0); matrix--; next;}
                keep && /^[[:space:]]*(visible reason=|bounds=|input[{]|geomLayerTransform|geomLayerBounds|geomBufferSize|blend=|isOpaque=)/ {emit($0); next;}
                /MiStep|TaskView|Task=|Magnification|InputMethod|Ime|GestureStub|Wallpaper|AppZoomOut/ {if ($0 ~ /parent=|parentId=|relativeParent/) emit($0);}
                END {if (n>350) print "SOURCE_TRUNCATED_at_350_state_lines";}'
                dumpsys SurfaceFlinger --list | awk '
                /MiStep|TaskView|Task=|Magnification|InputMethod|Ime|GestureStub|Wallpaper|AppZoomOut/ {if (++n<=150) print;}
                END {if (n>150) print "SOURCE_TRUNCATED_at_150_hierarchy_lines";}'
                """),
        trace("15-input-trace-ui.txt", "/data/user_de/0/com.android.systemui/files/mistep-input-traces/ui-*.txt"),
        trace("16-input-trace-server.txt", "/data/system/mistep-input-traces/wm-*.txt"),
        new Source("28-native-system-logcat.txt", "近期8000条原生系统日志；无标签过滤，大小超限明确截断", "logcat -b all -d -v epoch -t 8000")
    };
    private static Source moduleLog(String name, int position) {
        return new Source(name, "近期第" + position + "个LSPosed文件末16000行，仅本模块记录且保留堆栈续行",
                "f=$(ls -t /data/adb/lspd/log/modules_*.log | sed -n '" + position + "p'); "
                + "[ -n \"$f\" ] || exit 4; printf 'source=%s\\n' \"$f\"; "
                + "[ $(wc -l < \"$f\") -le 16000 ] || echo SOURCE_TRUNCATED_at_16000_lines; "
                + "tail -n 16000 \"$f\" | awk '/^\\[ [0-9][0-9][0-9][0-9]-/ {keep=($0 ~ /\\[com[.]jiusis[.]mistep,/)} keep {print; n++} END {if (!n) exit 4}'");
    }
    private static Source trace(String name, String pattern) {
        return new Source(name, "最近一次6秒数值追踪；本次须核对ARM的boot/PID/墙钟/elapsed关联，mtime不作录制证明",
                "f=$(ls -t " + pattern + " | head -n 1); [ -n \"$f\" ] || exit 4; "
                + "printf 'source=%s\\n' \"$f\"; stat -c 'mtimeEpoch=%Y' \"$f\"; cat \"$f\"");
    }
}
