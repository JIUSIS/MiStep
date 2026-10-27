import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'build/tests/native-landscape-jvm'
JDK = Path(os.environ["MISTEP_JAVA_BIN"])
OUT.mkdir(parents=True, exist_ok=True)
test = OUT / "com/jianguo/mistep/NativeLandscapeRegression.java"
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text(
    r'''package com.jianguo.mistep;
public class NativeLandscapeRegression {
 static int checks;
 static void check(boolean value, String why) {
   checks++;
   if (!value) throw new AssertionError(why);
 }
 public static void main(String[] args) {
   int inactive = NativeLandscapePolicy.INACTIVE;
   int suspending = NativeLandscapePolicy.SUSPENDING;
   int fullscreen = NativeLandscapePolicy.FULLSCREEN;
   int resuming = NativeLandscapePolicy.RESUMING;

   check(NativeLandscapePolicy.onLandscapeRequested(inactive) == suspending,
       "first landscape request did not enter suspension");
   check(NativeLandscapePolicy.onLandscapeRequested(suspending) == suspending,
       "duplicate landscape request repeated suspension");
   check(NativeLandscapePolicy.onLandscapeRequested(fullscreen) == fullscreen,
       "fullscreen landscape requested a duplicate suspension");
   check(NativeLandscapePolicy.onZoom(suspending, 1f) == fullscreen,
       "completed suspend did not enter native fullscreen");
   check(NativeLandscapePolicy.onZoom(fullscreen, 0.7471545f) == inactive,
       "workspace zoom did not clear fullscreen mode");
   check(NativeLandscapePolicy.onZoom(inactive, 1f) == inactive,
       "ordinary workspace exit entered fullscreen mode");
   check(NativeLandscapePolicy.onZoom(resuming, 1f) == resuming,
       "failed resume collapsed back to fullscreen state");

   check(!NativeLandscapePolicy.needsResume(inactive, false, true),
       "inactive session requested resume");
   check(!NativeLandscapePolicy.needsResume(suspending, false, true),
       "suspending workspace requested resume");
   check(!NativeLandscapePolicy.needsResume(fullscreen, true, false),
       "native landscape requested resume");
   check(NativeLandscapePolicy.needsResume(fullscreen, false, false),
       "portrait top app did not restore workspace");
   check(NativeLandscapePolicy.needsResume(fullscreen, true, true),
       "Home in global landscape did not restore workspace");
   check(NativeLandscapePolicy.needsResume(fullscreen, false, true),
       "Home in portrait did not restore workspace");
   check(NativeLandscapePolicy.onResumeRequested(fullscreen) == resuming,
       "resume request did not mark in-progress");
   check(NativeLandscapePolicy.onResumeRequested(resuming) == resuming,
       "duplicate resume request restarted");

   check(!NativeLandscapePolicy.suppressesLocalRotation(inactive, false, false),
       "ordinary portrait app lost local task rotation");
   check(NativeLandscapePolicy.suppressesLocalRotation(suspending, false, false),
       "landscape app retained workspace-local rotation");
   check(!NativeLandscapePolicy.suppressesLocalRotation(fullscreen, true, false),
       "side task lost its retained local role");
   check(!NativeLandscapePolicy.suppressesLocalRotation(fullscreen, false, true),
       "Home inherited a landscape app transform");
   check(!NativeLandscapePolicy.shouldUseNativeFullscreen(true, false, false),
       "active Mi Step workspace exited for a main-area landscape app");
   check(NativeLandscapePolicy.shouldUseNativeFullscreen(false, false, false),
       "inactive workspace did not use native fullscreen");
   check(!NativeLandscapePolicy.shouldUseNativeFullscreen(false, true, false),
       "side task used display-wide fullscreen");
   check(!NativeLandscapePolicy.shouldUseNativeFullscreen(false, false, true),
       "Home used display-wide fullscreen");
   check(NativeLandscapePolicy.shouldRestoreFromDisplay(true, true, 0),
       "portrait display did not restore landscape-suspended workspace");
   check(NativeLandscapePolicy.shouldRestoreFromDisplay(true, true, 2),
       "reverse portrait display did not restore landscape-suspended workspace");
   check(!NativeLandscapePolicy.shouldRestoreFromDisplay(true, true, 1),
       "landscape display restored the workspace too early");
   check(!NativeLandscapePolicy.shouldRestoreFromDisplay(true, false, 0),
       "active workspace was resumed twice");
   check(NativeLandscapePolicy.shouldRestoreFromHome(true, true, "homekey"),
       "Home navigation did not restore landscape-suspended workspace");
   check(!NativeLandscapePolicy.shouldRestoreFromHome(true, true, "recentapps"),
       "Recents navigation was mistaken for Home");
   check(!NativeLandscapePolicy.shouldRestoreFromHome(false, true, "homekey"),
       "ordinary suspended workspace was resumed by Home");

   System.out.println("native landscape lifecycle checks=" + checks + " passed");
 }
}''',
    encoding="utf8",
)
subprocess.run(
    [
        str(JDK / "javac.exe"),
        "-encoding",
        "UTF-8",
        "--release",
        "17",
        "-d",
        str(OUT),
        str(test),
        str(ROOT / "module/src/main/java/com/jianguo/mistep/NativeLandscapePolicy.java"),
    ],
    check=True,
)
subprocess.run(
    [
        str(JDK / "java.exe"),
        "-cp",
        str(OUT),
        "com.jianguo.mistep.NativeLandscapeRegression",
    ],
    check=True,
)
