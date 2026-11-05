"""Replay the actual installed system-bar hook, preserving native focus and appearance."""
import ast
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'module/src/main/java/com/jiusis/mistep'
OUT = Path(sys.argv[1])
# Optional old source supplies a negative control without altering the candidate.
host_path = Path(sys.argv[2]) if len(sys.argv) > 2 else SRC / 'HostEntry.java'
host = host_path.read_text(encoding='utf-8-sig')
start = host.index('    private void installSideSystemBarPolicy(ClassLoader cl) {')
end = host.index('\n    private void ', start + 10)
install = host[start:end]
tree = ast.parse((ROOT / 'tools/handoff-input-jvm/run.py').read_text(encoding='utf-8-sig'))
shared = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign)
              and any(isinstance(t, ast.Name) and t.id == 'files' for t in n.targets))
files = {name: body for name, body in shared.items() if name.startswith('de/robv/')}
files.update({
'android/view/View.java': 'package android.view;public class View{public static final int SYSTEM_UI_FLAG_FULLSCREEN=4,SYSTEM_UI_FLAG_HIDE_NAVIGATION=2;}',
'android/view/WindowManager.java': 'package android.view;public interface WindowManager{}',
'com/android/server/wm/WindowState.java': '''package com.android.server.wm;
public class WindowState {
 public Object display,task;public int appearance;public boolean nativeAffects=true,fail;
 public WindowState(Object d,Object t,int a){display=d;task=t;appearance=a;}
 public Object getDisplayContent(){if(fail)throw new IllegalStateException("window detached");return display;}
 public Object getTask(){return task;}public int getSystemUiVisibility(){return 0;}
 public boolean canAffectSystemUiFlags(){return nativeAffects;}
}''',
'com/jiusis/mistep/MiStepDesktopLaunchBridge.java': '''package com.jiusis.mistep;
class MiStepDesktopLaunchBridge {
 static Object top;static boolean topAlive=true,fail;
 static final java.util.Map<Object,Lease> recents=new java.util.IdentityHashMap<>();
 public static class Lease{boolean alive=true;}
 static boolean ownsTopViewport(Object task){if(fail)throw new IllegalStateException("owner died");return task!=null&&topAlive&&top==task;}
 static Object recentsLease(Object task){return recents.get(task);}
 static boolean recentsLeaseAlive(Object lease){return ((Lease)lease).alive;}
}''',
'com/jiusis/mistep/HostEntry.java': '''package com.jiusis.mistep;
import de.robv.android.xposed.*;
class HostEntry {
 static boolean sZoomed=true,sFullscreenRotationActive=true;static Object sDisplayContent;
 static final java.util.List<String> logs=new java.util.ArrayList<>();
 static void log(String s){logs.add(s);}static boolean isMiStepSideTask(Object t){return MiStepSideRoleChange.isSide(t);}
 static void install(){new HostEntry().installSideSystemBarPolicy(HostEntry.class.getClassLoader());}
''' + install + '\n}',
'com/jiusis/mistep/SystemBarOwnerTest.java': r'''package com.jiusis.mistep;
import de.robv.android.xposed.*;import com.android.server.wm.WindowState;
public class SystemBarOwnerTest {
 static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 public static class Display {public Object mCurrentFocus,mFocusedApp;}
 public static class Task {
  public Object display;public int mode=6;public boolean translucent=true;
  public Object getDisplayContent(){return display;}public int getWindowingMode(){return mode;}
  public boolean isForceTranslucent(){return translucent;}
 }
 public static class Change {public int getWindowingMode(){return 6;}public int getChangeMask(){return 64;}public boolean getForceTranslucent(){return true;}}
 static boolean affects(WindowState window)throws Throwable {
  var p=new XC_MethodHook.MethodHookParam();p.thisObject=window;p.args=new Object[0];p.result=window.canAffectSystemUiFlags();
  XposedHelpers.hooks.get("com.android.server.wm.WindowState.canAffectSystemUiFlags").after(p);
  return (Boolean)p.result;
 }
 // Native post-layout traversal consumes the first eligible overlapping window's request.
 // Values remain app-provided; this receiver does not calculate a replacement color.
 static int mainAppearance(WindowState... windows)throws Throwable {
  for(var window:windows)if(affects(window))return window.appearance;
  throw new AssertionError("no native main bar owner");
 }
 public static void main(String[]args)throws Throwable {
  Display d=new Display();HostEntry.sDisplayContent=d;HostEntry.install();
  check(XposedHelpers.hooks.size()==2,"actual Host installation failed");
  Task top=new Task();top.display=d;MiStepDesktopLaunchBridge.top=top;
  Task main=new Task();main.display=d;main.mode=1;main.translucent=false;
  WindowState tw=new WindowState(d,top,0),mw=new WindowState(d,main,8);
  d.mCurrentFocus=tw;d.mFocusedApp=top;
  check(!affects(tw),"leased TOP must not claim the main status-bar region");
  check(affects(mw),"native main remains eligible");
  check(mainAppearance(tw,mw)==8,"white main uses its own LIGHT_STATUS_BARS request despite dark TOP");
  tw.appearance=8;mw.appearance=0;
  check(mainAppearance(tw,mw)==0,"dark main keeps white glyph request despite light TOP");
  check(d.mCurrentFocus==tw&&d.mFocusedApp==top,"bar appearance changed TOP keyboard/back focus");
  d.mCurrentFocus=mw;d.mFocusedApp=main;
  check(mainAppearance(tw,mw)==0&&d.mCurrentFocus==mw&&d.mFocusedApp==main,"appearance must not depend on TOP input focus");
  top.mode=1;top.translucent=false;
  check(affects(tw),"returned TOP must regain its normal native bar policy before publication catches up");
  check(mainAppearance(tw,mw)==8,"returned foreground app uses its own light request");
  top.mode=6;top.translucent=true;
  MiStepDesktopLaunchBridge.topAlive=false;check(affects(tw),"dead TOP owner preserves native decision");
  MiStepDesktopLaunchBridge.topAlive=true;MiStepDesktopLaunchBridge.top=null;
  check(affects(tw),"native unrelated multi-window is unchanged");
  MiStepDesktopLaunchBridge.top=top;top.translucent=false;
  check(affects(tw),"foreign non-side mode6 task is unchanged even with a stale row");
  Object role=MiStepSideRoleChange.enter(top,new Change(),d);
  check(role==top&&!affects(tw),"exact pending TOP native role is isolated before force-translucent publication");
  MiStepSideRoleChange.exit(role);check(affects(tw),"pending role exit releases the exception");top.translucent=true;
  HostEntry.sZoomed=false;check(affects(tw),"workspace exit restores native TOP eligibility");HostEntry.sZoomed=true;
  HostEntry.sFullscreenRotationActive=false;check(affects(tw),"inactive workspace rotation lease preserves native decision");HostEntry.sFullscreenRotationActive=true;
  tw.display=new Display();check(affects(tw),"another display is unchanged");tw.display=d;
  tw.nativeAffects=false;check(!affects(tw),"native rejection is never promoted");tw.nativeAffects=true;
  MiStepDesktopLaunchBridge.fail=true;check(affects(tw),"stale lookup failure preserves native policy");MiStepDesktopLaunchBridge.fail=false;
  tw.fail=true;check(affects(tw),"detached window preserves native policy");tw.fail=false;
  Task side=new Task();side.display=d;var sw=new WindowState(d,side,8);
  var lease=new MiStepDesktopLaunchBridge.Lease();MiStepDesktopLaunchBridge.recents.put(side,lease);
  check(!affects(sw),"existing portrait side color isolation remains");lease.alive=false;
  check(affects(sw),"expired portrait side remains native");
  tw.task=null;check(affects(tw),"non-task system windows remain native");
  check(d.mCurrentFocus==mw&&d.mFocusedApp==main,"bar checks must leave input focus unchanged throughout");
  System.out.println("actual installed main status-bar owner checks="+checks+" passed");
 }
}'''
})
sources = []
for name, body in files.items():
    path = OUT / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding='utf8')
    sources.append(str(path))
sources.extend(str(SRC / (name + '.java')) for name in ['MiStepSideRoleChange', 'SystemBarVisibilityPolicy'])
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(OUT), *sources], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(OUT), 'com.jiusis.mistep.SystemBarOwnerTest'], check=True)
