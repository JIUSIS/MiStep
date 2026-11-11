"""Run production diagnostic filters on dumpsys fixtures, without a device or root."""
from pathlib import Path
import base64
import os
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
sources = [root / 'module/src/main/java/com/jiusis/mistep' / name for name in
           ('RuntimeLogText.java', 'RuntimeDiagnosticBundle.java', 'RuntimeDiagnosticSources.java', 'RuntimeTraceFormat.java')]
tests = [root / 'tests/RuntimeDiagnosticBundleTest.java', root / 'tests/RuntimeLogTextTest.java',
         root / 'tests/InputTraceGateTest.java',root / 'tests/RuntimeTraceFormatTest.java']
sources.append(root / 'module/src/main/java/com/jiusis/mistep/InputTraceGate.java')
correlation = root / 'tests/RuntimeDiagnosticCorrelationTest.java'
if correlation.exists():
    tests.append(correlation)
subprocess.run([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                *map(str, sources + tests)], check=True)
result = subprocess.run([str(jdk / 'java.exe'), '-cp', str(out),
                         'com.jiusis.mistep.RuntimeDiagnosticBundleTest', '--sources'],
                        check=True, capture_output=True, text=True, encoding='utf-8')
commands = {line.split('\t', 1)[0]: base64.b64decode(line.split('\t', 1)[1]).decode()
            for line in result.stdout.splitlines() if '\t' in line}
print(result.stdout.splitlines()[0])
for name in ('RuntimeLogTextTest', 'InputTraceGateTest','RuntimeTraceFormatTest'):
    subprocess.run([str(jdk / 'java.exe'), '-cp', str(out), 'com.jiusis.mistep.' + name], check=True)
shell = Path(os.environ.get('MISTEP_TEST_BASH') or shutil.which('bash') or
             'C:/Program Files/Git/bin/bash.exe')
if not shell.exists():
    raise SystemExit('A local bash/awk is required to verify the production filters')
input_fixture = '''Input Dispatcher State:
  DispatchEnabled: true
  DispatchFrozen: false
  FocusedApplications:
    displayId=0, name=ActivityRecord{sample t81}
  FocusedWindows:
    displayId=0, name=ab ordinary.app/.Editor
  Windows:
      0: name=aa MiStepHost, id=7, displayId=0, inputConfig=NOT_FOCUSABLE | TRUSTED_OVERLAY, frame=[0,0][3048,2032], applicationInfo.name=PRIVATE_APP_TEXT, applicationInfo.token=token, touchableRegion=[0,0][800,1200], ownerPid=777, ownerUid=1000
        transform (ROT_90) (SCALE TRANSLATE)
          0.0000 -1.6529 0.0000
          1.6529 0.0000 -560.0000
          0.0000 0.0000 1.0000
      1: name=ab ordinary.app/.Editor, id=8, displayId=0, inputConfig=0x0, frame=[24,160][780,1400], applicationInfo.name=PRIVATE_APP_TEXT, applicationInfo.token=token, touchableRegion=[24,160][780,1400], ownerPid=888, ownerUid=10321
        transform (ROT_0) (SCALE TRANSLATE)
          1.6529 0.0000 -39.6696
          0.0000 1.6529 -264.4640
          0.0000 0.0000 1.0000
  RecentQueue: PRIVATE_KEY_HISTORY
'''
surface_fixture = '''Layer FE (Front End) state:
  Layer [7] VRI-MiStepHost#7
    visible reason=buffer=12 frame=15
    bounds={0,0,3048,2032} toDisplayTransform={ scale x=0.605 y=0.605 tx=2291 ty=508 }
    input{(NOT_FOCUSABLE) touchableRegion={24,160,780,1400}}
      isSecure=false privateLayer=0 geomLayerTransform (ROT_90) (SCALE TRANSLATE)
        0.0000 -0.6050 2291.0000
        0.6050 0.0000 508.0000
        0.0000 0.0000 1.0000
      blend=PREMULTIPLIED alpha=1.0 backgroundBlurRadius=0
  Layer [8] VRI-ordinary.app/.Editor#8
    bounds={24,160,780,1400} toDisplayTransform={ scale x=0.605 y=0.605 tx=24 ty=160 }
    input{(0x0) touchCropId=15 touchableRegion={24,160,780,1400}}
  Layer [9] unrelated-content
    PRIVATE_BUFFER_METADATA
  \u251c\u2500 Task=81#15 parent=4 relativeParent=7 z=1 flags:0 a:1 pid=888 uid=10321
  PRIVATE_BUFFER_METADATA
'''
for name, data in [('input', input_fixture), ('surface', surface_fixture)]:
    (out / (name + '.txt')).write_text(data, encoding='utf-8')

def run_filter(key, fixture, hierarchy=None):
    # dumpsys is a fixture producer; the rest is the unchanged production command.
    path = (out / (fixture + '.txt')).as_posix()
    producer = "cat '" + path + "'"
    if hierarchy is not None:
        producer = "if [ \"$2\" = --list ]; then cat '" + hierarchy.as_posix() + "'; else " + producer + "; fi"
    mocked = "dumpsys() { " + producer + "; };\n" + commands[key]
    command_file = out / (fixture + '-filter.sh')
    command_file.write_text(mocked, encoding='utf-8')
    run = subprocess.run([str(shell), str(command_file)], capture_output=True, text=True,
                         encoding='utf-8', check=True)
    (out / (fixture + '-filtered.txt')).write_text(run.stdout, encoding='utf-8')
    return run.stdout

filtered = run_filter('12-input-state.txt', 'input')
for expected in ('ownerPid=888', 'ownerUid=10321', 'frame=[24,160]', 'touchableRegion=', 'inputConfig=', '-264.4640'):
    assert expected in filtered, 'production input filter lost ' + expected
assert 'PRIVATE_APP_TEXT' not in filtered and 'PRIVATE_KEY_HISTORY' not in filtered
surface = run_filter('14-surface-layers.txt', 'surface')
for expected in ('toDisplayTransform=', 'relativeParent=7', 'z=1', 'geomLayerTransform', 'touchCropId=15', '2291.0000'):
    assert expected in surface, 'production surface filter lost ' + expected
assert 'PRIVATE_BUFFER_METADATA' not in surface
assert 'SOURCE_TRUNCATED' in commands['12-input-state.txt']
assert 'SOURCE_TRUNCATED' in commands['14-surface-layers.txt']
assert 'MiStepRawInput:V' in commands['08-module-logcat.txt']
print('Production dumpsys filters preserve window ownership, transforms and relative layer context; exclude content')
for fixture, key in (('input', '12-input-state.txt'), ('surface', '14-surface-layers.txt')):
    many = out / (fixture + '-flood.txt')
    many.write_text((input_fixture if fixture == 'input' else surface_fixture) * 600, encoding='utf-8')
    bounded = run_filter(key, fixture + '-flood')
    assert 'SOURCE_TRUNCATED' in bounded and len(bounded.splitlines()) <= 502
print('Production filters enforce 500-line budgets and report truncation')
captures = root.parent / 'research/onestep/os4-top-input-regression-20261110'
launcher_samples = [captures / 'pad' / (name + '-launcher.log')
                    for name in ('back-main-native176', 'back-top-native176')]
launcher_fixture = '\n'.join(p.read_text(encoding='utf-8') for p in launcher_samples if p.exists())
launcher_fixture += '\nhyper_launcher_app: unrelated PRIVATE_APP_CONTENT\n'
if not launcher_samples[0].exists():
    launcher_fixture += ('hyper_launcher_app: task_filter_helper: get_running_task_for_gesture task_id: 86\n'
                         'hyper_launcher_app: gesture_stub_view_window: BackGesture BLOCKED by classic nav\n'
                         'hyper_launcher_app: gesture_input_back_helper: on_back_navigation_finished true\n')
launcher_path = out / 'launcher-fixture.txt'
launcher_path.write_text(launcher_fixture, encoding='utf-8')
launcher_command = commands['19-launcher-gestures.txt']
assert "hyper_launcher_app:I '*:S'" in launcher_command and '-t 4000' in launcher_command
def launcher_filter(fixture):
    script = out / 'launcher-filter.sh'
    script.write_text("logcat() { cat '" + fixture.as_posix() + "'; };\n" + launcher_command, encoding='utf-8')
    return subprocess.run([str(shell), str(script)], capture_output=True, text=True,
                          encoding='utf-8', check=True).stdout
launcher_filtered = launcher_filter(launcher_path)
assert all(value in launcher_filtered for value in ('get_running_task_for_gesture', 'BackGesture BLOCKED', 'on_back_navigation_finished'))
assert 'PRIVATE_APP_CONTENT' not in launcher_filtered
(out / 'launcher-filtered.txt').write_text(launcher_filtered, encoding='utf-8')
launcher_path.write_text(launcher_fixture * 600 + '\nhyper_launcher_app: gesture_stub_view_window: LATEST_SENTINEL\n', encoding='utf-8')
launcher_flood = launcher_filter(launcher_path)
assert 'LATEST_SENTINEL' in launcher_flood and 'SOURCE_TRUNCATED' in launcher_flood
assert len(launcher_flood.splitlines()) <= 501
print('Production Launcher gesture filter retains actual task/Back gates, latest records and explicit 500-line truncation')
for device in ('pad', '17pm'):
    captured = captures / device / 'captured174'
    if not all((captured / name).exists() for name in ('input.txt', 'surfaceflinger.txt', 'layers.txt')):
        continue
    # Existing local evidence only; no ADB or new device capture.
    for file, fixture in (('input.txt', 'actual-' + device + '-input'), ('surfaceflinger.txt', 'actual-' + device + '-surface')):
        target = out / (fixture + '.txt')
        target.write_text((captured / file).read_text(encoding='utf-8').replace('\r', ''), encoding='utf-8')
        if file == 'input.txt':
            actual = run_filter('12-input-state.txt', fixture)
            assert 'ownerPid=' in actual and 'touchableRegion=' in actual and 'inputConfig=' in actual
        else:
            actual = run_filter('14-surface-layers.txt', fixture, captured / 'layers.txt')
            assert 'toDisplayTransform=' in actual and 'geomLayerTransform' in actual and 'parentId=' in actual
    print('Existing ' + device + ' 174 dumps preserve real input and Surface state metadata (not device acceptance)')
if correlation.exists():
    subprocess.run([str(jdk / 'java.exe'), '-cp', str(out),
                    'com.jiusis.mistep.RuntimeDiagnosticCorrelationTest'], check=True)
from trace import run as run_trace
run_trace(root, out, jdk)
from system_trace import run as run_system_trace
run_system_trace(root,out,jdk)
