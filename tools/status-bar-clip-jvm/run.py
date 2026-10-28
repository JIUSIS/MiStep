import os
"""Run the production clip owner against traced geometry and WM lifecycle fakes."""
from pathlib import Path
import ast
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'build/tests/status-bar-clip-jvm'
JDK = Path(os.environ["MISTEP_JAVA_BIN"])
tree = ast.parse((ROOT / 'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
files = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n, ast.Assign)
             and any(isinstance(t, ast.Name) and t.id == 'FILES' for t in n.targets))
fake = OUT / 'de/robv/android/xposed/XposedHelpers.java'
fake.parent.mkdir(parents=True, exist_ok=True)
fake.write_text(files['de/robv/android/xposed/XposedHelpers.java'], encoding='utf8')
rect = OUT / 'android/graphics/Rect.java'
rect.parent.mkdir(parents=True, exist_ok=True)
rect.write_text('''package android.graphics;
public final class Rect {
 public final int left,top,right,bottom;
 public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
 public int width(){return right-left;} public int height(){return bottom-top;}
 public boolean equals(Object o){if(!(o instanceof Rect r))return false;return left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}
 public int hashCode(){return left+31*top+961*right+29791*bottom;}
 public String toString(){return left+","+top+","+right+","+bottom;}
}''', encoding='utf8')
source = ROOT / 'module/src/main/java/com/jianguo/mistep/MiStepStatusBarClip.java'
test = Path(__file__).with_name('StatusBarClipRegression.java')
subprocess.run([str(JDK / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(OUT),
                str(fake), str(rect), str(source), str(test)], check=True)
subprocess.run([str(JDK / 'java.exe'), '-cp', str(OUT),
                'com.jianguo.mistep.StatusBarClipRegression'], check=True)
