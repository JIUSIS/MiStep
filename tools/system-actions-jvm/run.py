import os
"""Run actual controller/cache/admission method bodies with deterministic service/queue fakes.

This verifies protocol decisions and stale work, not Android UI, Binder or camera hardware.
"""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / 'module/src/main/java/com/jianguo/mistep'
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'build/tests/standalone'
OUT.mkdir(parents=True, exist_ok=True)
JDK = Path(os.environ["MISTEP_JAVA_BIN"])


def body(text, signature):
    start = text.index(signature)
    cursor = text.index('{', start) + 1
    depth = 1
    while depth:
        if text[cursor] == '{':
            depth += 1
        elif text[cursor] == '}':
            depth -= 1
        cursor += 1
    return text[start:cursor]


actions = (SRC / 'MiStepSystemActions.java').read_text(encoding='utf-8-sig')
torch = body(actions, '    private static void toggleRealTorch() {')
torch_test = r'''
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
public class TorchControllerTest {
    static int checks, fallbackTiles;
    static WeakReference<Object> flashlight=new WeakReference<>(null);
    static Controller fallback;
    static void check(boolean v){checks++;if(!v)throw new AssertionError("check="+checks);}
    static Object requireTile(String spec){fallbackTiles++;check(spec.equals("flashlight"));return new Tile(fallback);}
    static class Tile {Object mFlashlightController;Tile(Object c){mFlashlightController=c;}}
    static class Controller {
        boolean on,available=true,forced;int reads,sets;
        public boolean isEnabled(){reads++;return on;}
        public boolean isAvailable(){return available;}
        public void setFlashlight(boolean enabled){sets++;if(!forced)on=enabled;}
    }
    static class XposedHelpers {
        static Object callMethod(Object o,String name,Object...args){
            try {
                Method m=args.length==0?o.getClass().getMethod(name):o.getClass().getMethod(name,boolean.class);
                return m.invoke(o,args);
            }catch(Exception e){throw new IllegalStateException(e);}
        }
        static Object getObjectField(Object o,String name){
            try{Field f=o.getClass().getDeclaredField(name);return f.get(o);}
            catch(Exception e){throw new IllegalStateException(e);}
        }
    }
    __TORCH__
    public static void main(String[] args){
        Controller c=new Controller();flashlight=new WeakReference<>(c);
        toggleRealTorch();check(c.on&&c.reads==1&&c.sets==1);
        toggleRealTorch();check(!c.on&&c.reads==2&&c.sets==2);
        c.on=true;toggleRealTorch();check(!c.on); // Changed externally since our last click.
        c.on=false;toggleRealTorch();check(c.on);
        c.available=false;toggleRealTorch();check(!c.on); // Unavailable camera must still permit OFF.
        try{toggleRealTorch();throw new AssertionError("unavailable ON admitted");}
        catch(IllegalStateException expected){check(c.sets==5);}
        c.available=true;c.forced=true;toggleRealTorch();check(!c.on&&c.sets==6);
        c.forced=false;c.on=true;fallback=c;flashlight=new WeakReference<>(null);
        toggleRealTorch();check(!c.on&&fallbackTiles==1&&flashlight.get()==c);
        android.app.ActivityManager.monkey=true;toggleRealTorch();check(!c.on&&c.sets==7);
        System.out.println("TorchController checks="+checks+" passed");
    }
}
'''.replace('__TORCH__', torch)

warm = (SRC / 'MiStepWarmup.java').read_text(encoding='utf-8-sig')
signatures = (
    '    static void start(Context context, ClassLoader classLoader) {',
    '    static List<QuickApp> quickApps(Context context) {',
    '    static void whenQuickAppsReady(Context context, Consumer<List<QuickApp>> ready) {',
    '    static Bitmap wallpaper(Context context, int width, int height) throws Exception {',
    '    private static void prepare(Context base) {',
    '    private static void deliver(Context context, PendingApps pending, List<QuickApp> ready) {',
    '    private static void deliver(Context context, PendingApps pending, List<QuickApp> ready, long request) {',
    '    private static void invalidate() {',
    '    private static final class PendingApps {',
)
warm_methods = '\n'.join(body(warm, signature) for signature in signatures)
warm_test = r'''
import java.util.*;
import java.util.function.Consumer;
public class BootWarmupTest {
    static int checks,user,scheduledCount;static long lastDelay;
    static Object LOCK=new Object();static final String USER_SWITCHED="android.intent.action.USER_SWITCHED";
    static final Queue MAIN=new Queue();static final List<PendingApps> pendingApps=new ArrayList<>();
    static List<QuickApp> apps=Collections.emptyList();static boolean appsLoaded,wallpaperPrepared,started;
    static int cacheUser=-1;static long generation;static Bitmap wallpaper;
    static final long APPS_REFRESH_MILLIS=600000L;static long appsPreparedAt;
    static class SystemClock{static long now=1;static long elapsedRealtime(){return now;}}
    static int wallpaperWidth,wallpaperHeight;static ClassLoader loader;
    static void check(boolean v){checks++;if(!v)throw new AssertionError("check="+checks);}
    static int currentUser(){return user;}
    static Context userContext(Context base,int user){return base;}
    static void preloadClasses(){}
    static void failed(String name,Throwable error){}
    static void schedule(Context c,long delay){lastDelay=delay;scheduledCount++;}
    static class Queue {
        ArrayDeque<Runnable> q=new ArrayDeque<>();void post(Runnable r){q.add(r);}
        void drain(){while(!q.isEmpty())q.remove().run();}
    }
    static class Context {
        static final String USER_SERVICE="user",WINDOW_SERVICE="window";
        static final int RECEIVER_NOT_EXPORTED=4;
        UserManager users=new UserManager();WindowManager windows=new WindowManager();
        List<BroadcastReceiver> receivers=new ArrayList<>();
        Object getSystemService(String key){return key.equals(USER_SERVICE)?users:windows;}
        void registerReceiver(BroadcastReceiver r,IntentFilter f,int flags){receivers.add(r);}
    }
    abstract static class BroadcastReceiver{public abstract void onReceive(Context c,Intent i);}
    static class Intent {
        static final String ACTION_USER_UNLOCKED="unlocked",ACTION_USER_PRESENT="present",
          ACTION_WALLPAPER_CHANGED="wallpaper",ACTION_PACKAGE_ADDED="added",
          ACTION_PACKAGE_REMOVED="removed",ACTION_PACKAGE_CHANGED="changed";
        String action;Intent(String a){action=a;}String getAction(){return action;}
    }
    static class IntentFilter{IntentFilter(String a){}void addAction(String a){}void addDataScheme(String s){}}
    static class UserManager{boolean unlocked;boolean isUserUnlocked(){return unlocked;}}
    static class Point{int x,y;}
    static class WindowManager{WindowManager getDefaultDisplay(){return this;}void getRealSize(Point p){p.x=1080;p.y=2400;}}
    static class Bitmap{boolean dead;boolean isRecycled(){return dead;}void recycle(){dead=true;}}
    static class QuickApp {
        static int loads;static Runnable afterLoad;
        static List<QuickApp> load(Context c){loads++;if(afterLoad!=null){Runnable r=afterLoad;afterLoad=null;r.run();}return List.of(new QuickApp());}
    }
    static class HyperOsStyle {
        static int blurs;static boolean fail;static Runnable beforeBlur;
        static Bitmap blurredWallpaper(Context c,int w,int h){
            blurs++;if(beforeBlur!=null){Runnable r=beforeBlur;beforeBlur=null;r.run();}
            if(fail)throw new IllegalStateException("blur unavailable");return new Bitmap();
        }
    }
    __WARM__
    public static void main(String[] args)throws Exception {
        Context c=new Context();start(c,BootWarmupTest.class.getClassLoader());
        check(QuickApp.loads==0&&HyperOsStyle.blurs==0&&!started);
        MAIN.drain();check(started&&c.receivers.size()==2&&lastDelay==12000&&QuickApp.loads==0);
        prepare(c);check(QuickApp.loads==0&&HyperOsStyle.blurs==0); // Locked boot.
        check(quickApps(c).isEmpty()&&QuickApp.loads==0); // Cold open does no PM work.
        int[] delivered={0};whenQuickAppsReady(c,result->{check(result.size()==1);delivered[0]++;});
        check(lastDelay==0&&QuickApp.loads==0&&delivered[0]==0);
        c.users.unlocked=true;HyperOsStyle.beforeBlur=()->check(!MAIN.q.isEmpty());
        prepare(c);check(QuickApp.loads==1&&HyperOsStyle.blurs==1&&delivered[0]==0);
        MAIN.drain();check(delivered[0]==1&&quickApps(c).size()==1);
        Bitmap original=wallpaper;Bitmap taken=wallpaper(c,1080,2400);
        check(taken==original&&wallpaper==null&&HyperOsStyle.blurs==1);
        prepare(c);check(HyperOsStyle.blurs==1); // Unlock notifications must not rebuild a consumed cache.
        invalidate();check(!taken.isRecycled()&&quickApps(c).isEmpty()); // Host owns consumed bitmap.
        prepare(c);Bitmap owned=wallpaper;invalidate();check(owned.isRecycled());
        prepare(c);Bitmap differentSize=wallpaper(c,2400,1080);check(differentSize!=wallpaper&&!wallpaper.isRecycled());
        int before=delivered[0];whenQuickAppsReady(c,result->delivered[0]++);user=1;MAIN.drain();check(delivered[0]==before);
        invalidate();int loads=QuickApp.loads;
        QuickApp.afterLoad=()->{user=2;invalidate();};prepare(c);
        check(QuickApp.loads==loads+1&&cacheUser==-1&&!appsLoaded); // Old user's result is discarded.
        user=0;invalidate();c.users.unlocked=true;prepare(c);
        whenQuickAppsReady(c,result->delivered[0]++);c.receivers.get(1).onReceive(c,new Intent(Intent.ACTION_PACKAGE_CHANGED));
        MAIN.drain();check(delivered[0]==before&&pendingApps.size()==1&&lastDelay==0);
        prepare(c);MAIN.drain();check(delivered[0]==before+1&&pendingApps.isEmpty());
        c.receivers.get(0).onReceive(c,new Intent(Intent.ACTION_WALLPAPER_CHANGED));check(wallpaper==null&&!appsLoaded);
        whenQuickAppsReady(c,result->delivered[0]++);HyperOsStyle.fail=true;prepare(c);MAIN.drain();
        check(delivered[0]==before+2&&appsLoaded&&wallpaper==null); // Failed blur cannot block shortcuts.
        HyperOsStyle.fail=false;
        whenQuickAppsReady(c,result->delivered[0]++);c.users.unlocked=false;MAIN.drain();
        check(delivered[0]==before+2&&pendingApps.size()==1);c.users.unlocked=true;prepare(c);MAIN.drain();
        check(delivered[0]==before+3&&pendingApps.isEmpty());
        user=-1;check(quickApps(c).isEmpty()&&wallpaper(c,1080,2400)==null);
        user=0;invalidate();prepare(c);MAIN.drain();
        int beforeRefresh=QuickApp.loads;SystemClock.now+=APPS_REFRESH_MILLIS;
        check(!quickApps(c).isEmpty()); // A stale cache still paints immediately.
        int[] refreshDelivered={0};whenQuickAppsReady(c,result->refreshDelivered[0]++);
        check(refreshDelivered[0]==0&&pendingApps.size()==1&&lastDelay==0);
        prepare(c);MAIN.drain();
        check(QuickApp.loads==beforeRefresh+1&&refreshDelivered[0]==1&&pendingApps.isEmpty());
        prepare(c);check(QuickApp.loads==beforeRefresh+1); // No polling or reload within ten minutes.
        System.out.println("BootWarmup checks="+checks+" passed");
    }
}
'''.replace('__WARM__', warm_methods)

guard = (SRC / 'MiStepEntryGuard.java').read_text(encoding='utf-8-sig')
guard_methods = '\n'.join(body(guard, s) for s in (
    '    static boolean canOpen(Context context) {',
    '    static boolean allows(boolean interactive, boolean keyguardLocked,'))
guard_test = r'''
public class EntryGuardServicesTest {
    static int checks;
    static void check(boolean v){checks++;if(!v)throw new AssertionError("check="+checks);}
    static class Context {
        static final String POWER_SERVICE="power",KEYGUARD_SERVICE="lock",USER_SERVICE="user";
        PowerManager power=new PowerManager();KeyguardManager keyguard=new KeyguardManager();UserManager user=new UserManager();
        boolean fail;Object getSystemService(String name){if(fail)throw new IllegalStateException();
            return name.equals(POWER_SERVICE)?power:name.equals(KEYGUARD_SERVICE)?keyguard:user;}
    }
    static class PowerManager{boolean interactive;boolean isInteractive(){return interactive;}}
    static class KeyguardManager{boolean locked,deviceLocked;boolean isKeyguardLocked(){return locked;}boolean isDeviceLocked(){return deviceLocked;}}
    static class UserManager{boolean unlocked;boolean isUserUnlocked(){return unlocked;}}
    __GUARD__
    public static void main(String[] args){
        check(!canOpen(null));Context c=new Context();
        for(int state=0;state<16;state++){
            c.power.interactive=(state&1)!=0;c.keyguard.locked=(state&2)!=0;
            c.keyguard.deviceLocked=(state&4)!=0;c.user.unlocked=(state&8)!=0;
            check(canOpen(c)==(state==9));
        }
        c.fail=true;check(!canOpen(c));c.fail=false;c.power=null;check(!canOpen(c));
        c.power=new PowerManager();c.keyguard=null;check(!canOpen(c));c.keyguard=new KeyguardManager();c.user=null;check(!canOpen(c));
        System.out.println("EntryGuardServices checks="+checks+" passed");
    }
}
'''.replace('__GUARD__', guard_methods)

sources = {
    'TorchControllerTest.java': torch_test,
    'BootWarmupTest.java': warm_test,
    'EntryGuardServicesTest.java': guard_test,
    'android/app/ActivityManager.java': 'package android.app;public class ActivityManager{public static boolean monkey;public static boolean isUserAMonkey(){return monkey;}}',
}
paths = []
for relative, text in sources.items():
    path = OUT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')
    paths.append(str(path))
subprocess.run([str(JDK / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(OUT), *paths], check=True)
for test in ('TorchControllerTest', 'BootWarmupTest', 'EntryGuardServicesTest'):
    subprocess.run([str(JDK / 'java.exe'), '-cp', str(OUT), test], check=True)
