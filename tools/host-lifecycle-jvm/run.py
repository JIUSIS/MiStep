import os
"""Execute the actual close/reveal methods against deterministic window/frame queues.

This checks ordering and stale callbacks only, not Android rendering or WM ownership.
"""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / "module/src/main/java/com/jiusis/mistep"
out = Path(sys.argv[1]) if len(sys.argv) > 1 else root / 'build/tests/standalone'
out.mkdir(parents=True, exist_ok=True)
jdk = Path(os.environ["MISTEP_JAVA_BIN"])


def method(text, signature):
    start = text.index(signature)
    brace = text.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        if text[end] == "{":
            depth += 1
        elif text[end] == "}":
            depth -= 1
        end += 1
    return text[start:end]


preview = (src / "SwapPreview.java").read_text(encoding="utf-8-sig")
close = "\n".join(method(preview, s) for s in ("    void close() {", "    void close(Runnable onDetached) {"))
# Runtime-independent logger; method control flow is otherwise unmodified.
close = close.replace('android.util.Log.w("MiStepProbe", "bridge window removal failed", error);', "removalErrors++;")
bridge_test = r'''
import java.util.*;
public class BridgeCloseCompletionTest {
    static int checks;
    static void check(boolean v) { checks++; if (!v) throw new AssertionError("check="+checks); }
    static class View {
        interface OnAttachStateChangeListener {
            void onViewAttachedToWindow(View v);
            void onViewDetachedFromWindow(View v);
        }
    }
    static class Root extends View {
        boolean attached=true;
        List<OnAttachStateChangeListener> listeners=new ArrayList<>();
        boolean isAttachedToWindow() {return attached;}
        void addOnAttachStateChangeListener(OnAttachStateChangeListener l) {listeners.add(l);}
        void removeOnAttachStateChangeListener(OnAttachStateChangeListener l) {listeners.remove(l);}
        void detach() {
            attached=false;
            List<OnAttachStateChangeListener> queued=new ArrayList<>(listeners);
            for (var l:queued) l.onViewDetachedFromWindow(this);
            // An already-queued duplicate must not complete the lease a second time.
            for (var l:queued) l.onViewDetachedFromWindow(this);
        }
    }
    static class Animator {void cancel() {}}
    static class Manager {
        int mode;
        void removeView(Root root) {
            if (mode==2) throw new IllegalStateException("failed remove");
            if (mode==0) root.detach();
        }
    }
    Root overlayRoot=new Root();
    Animator animator=new Animator();
    Manager overlayWindowManager=new Manager();
    boolean closed, removalFailed;
    int removalErrors;
    __CLOSE_METHODS__
    public static void main(String[] args) {
        var sync=new BridgeCloseCompletionTest(); int[] a={0};
        sync.close(()->{check(!sync.overlayRoot.attached);a[0]++;});
        check(a[0]==1 && sync.overlayRoot.listeners.isEmpty());
        sync.close(()->a[0]++); check(a[0]==2);

        var delayed=new BridgeCloseCompletionTest(); delayed.overlayWindowManager.mode=1; int[] b={0};
        delayed.close(()->b[0]++); check(b[0]==0 && delayed.closed);
        delayed.close(()->b[0]++); check(b[0]==0);
        delayed.overlayRoot.detach(); check(b[0]==2 && delayed.overlayRoot.listeners.isEmpty());

        var failed=new BridgeCloseCompletionTest(); failed.overlayWindowManager.mode=2; int[] c={0};
        failed.close(()->c[0]++); check(c[0]==1 && failed.removalFailed && failed.removalErrors==1);
        check(failed.overlayRoot.attached && failed.overlayRoot.listeners.isEmpty());
        failed.overlayRoot.detach(); check(c[0]==1);
        failed.close(()->c[0]++); check(c[0]==2);

        var legacy=new BridgeCloseCompletionTest(); legacy.close(); int[] d={0};
        legacy.close(()->d[0]++); check(d[0]==1);
        System.out.println("BridgeCloseCompletion checks="+checks+" passed");
    }
}
'''.replace("__CLOSE_METHODS__", close)

host = (src / "MiStepHost.java").read_text(encoding="utf-8-sig")
host_methods = "\n".join(method(host, s) for s in (
    "    private void closeSwapPreview() {",
    "    private void closeSwapPreview(Object fallbackStatusOwner) {",
    "    private void finishSwapPreview(Object statusOwner, long settleMs) {"))
host_test = r'''
import java.util.*;
public class HostRevealOrderTest {
    static int checks;
    static void check(boolean v) {checks++;if(!v)throw new AssertionError("check="+checks);}
    static class Queue {
        ArrayDeque<Runnable> tasks=new ArrayDeque<>();
        void postOnAnimation(Runnable r){tasks.add(r);}
        void requestLayout(){}
        void postDelayed(Runnable r,long ms){tasks.add(r);}
        void next(){tasks.remove().run();}
    }
    static class Status {
        Object owner;
        boolean hidden;
        void begin(Object t){owner=t;hidden=true;}
        void finish(Object t){if(t!=null&&owner==t){owner=null;hidden=false;}}
    }
    static class SwapPreview {
        boolean closed;
        Runnable detached;
        void close(Runnable r){closed=true;detached=r;}
        void detach(){Runnable r=detached;detached=null;if(r!=null)r.run();}
    }
    Queue root=new Queue(),main=new Queue();
    Status statusBar=new Status();
    Object promotion;
    SwapPreview swapPreview;
    Object swapPreviewStatusOwner;
    void stopLiveLift(Object p){}
    __HOST_METHODS__
    public static void main(String[] args){
        var h=new HostRevealOrderTest();Object first=new Object(),second=new Object();
        var bridge=new SwapPreview();h.swapPreview=bridge;h.swapPreviewStatusOwner=first;h.statusBar.begin(first);
        h.finishSwapPreview(first,120);check(h.statusBar.hidden&&!bridge.closed);
        h.root.next();check(h.statusBar.hidden&&!bridge.closed);
        h.root.next();check(h.statusBar.hidden&&!bridge.closed);
        h.main.next();check(bridge.closed&&h.statusBar.hidden&&h.swapPreview==null);
        bridge.detach();check(!h.statusBar.hidden);

        h.statusBar.begin(first);h.finishSwapPreview(first,0);h.root.next();h.root.next();
        check(!h.statusBar.hidden);
        h.statusBar.begin(first);h.finishSwapPreview(first,0);h.statusBar.begin(second);
        h.root.next();h.root.next();check(h.statusBar.hidden&&h.statusBar.owner==second);

        var old=new SwapPreview();h.swapPreview=old;h.swapPreviewStatusOwner=first;h.statusBar.begin(first);
        h.closeSwapPreview(first);check(old.closed&&h.statusBar.hidden);
        h.statusBar.begin(second);old.detach();check(h.statusBar.hidden&&h.statusBar.owner==second);

        var obsolete=new SwapPreview();h.swapPreview=obsolete;h.swapPreviewStatusOwner=first;h.statusBar.begin(first);
        h.finishSwapPreview(first,120);h.root.next();h.root.next();
        var newer=new SwapPreview();h.swapPreview=newer;h.swapPreviewStatusOwner=second;h.statusBar.begin(second);
        h.main.next();check(!newer.closed&&h.statusBar.hidden);
        h.closeSwapPreview(second);check(newer.closed&&h.statusBar.hidden);newer.detach();check(!h.statusBar.hidden);
        System.out.println("HostRevealOrder checks="+checks+" passed");
    }
}
'''.replace("__HOST_METHODS__", host_methods)

files = []
for name, body in (("BridgeCloseCompletionTest", bridge_test), ("HostRevealOrderTest", host_test)):
    file = out / f"{name}.java"
    file.write_text(body, encoding="utf-8")
    files.append(str(file))
subprocess.run([str(jdk / "javac.exe"), "-encoding", "UTF-8", "--release", "17", "-d", str(out), *files], check=True)
for name in ("BridgeCloseCompletionTest", "HostRevealOrderTest"):
    subprocess.run([str(jdk / "java.exe"), "-cp", str(out), name], check=True)
