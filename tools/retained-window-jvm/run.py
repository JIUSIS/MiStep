"""Execute the production native-window lease and visibility hook with WM doubles."""
from pathlib import Path
import os, subprocess, sys
root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'com/jiusis/mistep/RuntimeDiagnosticEvents.java':'package com.jiusis.mistep;class RuntimeDiagnosticEvents {static void event(String s){}}',
'com/jiusis/mistep/ShellCompat.java':'package com.jiusis.mistep;class ShellCompat {static boolean hasMethod(Class<?> c,String name,int count){for(var m:c.getMethods())if(m.getName().equals(name)&&m.getParameterCount()==count)return true;return false;}}',
'android/os/IBinder.java':'package android.os;public interface IBinder {}',
'android/os/Handler.java':'package android.os;public class Handler {public Runnable timeout;public void postDelayed(Runnable r,long delay){timeout=r;}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long now=100;public static long uptimeMillis(){return now;}}',
'android/os/Bundle.java':'''package android.os;import java.util.*;public class Bundle {
Map<String,Object> m=new HashMap<>();public void putBinder(String k,IBinder v){m.put(k,v);}public IBinder getBinder(String k){return (IBinder)m.get(k);}
public void putLong(String k,long v){m.put(k,v);}public long getLong(String k,long d){return (long)m.getOrDefault(k,d);}}''',
'android/Manifest.java':'package android;public class Manifest {public static class permission {public static final String WRITE_SECURE_SETTINGS="protected";}}',
'android/content/pm/PackageManager.java':'package android.content.pm;import java.util.*;public class PackageManager {\npublic Map<Integer,String[]> owners=new HashMap<>();public boolean fail;\npublic PackageManager(){owners.put(1000,new String[]{"android","com.android.systemui"});}\npublic String[] getPackagesForUid(int uid){if(fail)throw new IllegalStateException("PM unavailable");return owners.get(uid);}}',
'android/content/Intent.java':'''package android.content;import android.os.*;import java.util.*;public class Intent {
public String action,pkg;Map<String,Object> m=new HashMap<>();public Intent(String a){action=a;}public Intent setPackage(String p){pkg=p;return this;}
public Intent putExtra(String k,Object v){m.put(k,v);return this;}public Bundle getBundleExtra(String k){return (Bundle)m.get(k);}
public float getFloatExtra(String k,float d){return (float)m.getOrDefault(k,d);}public long getLongExtra(String k,long d){return (long)m.getOrDefault(k,d);}
public boolean getBooleanExtra(String k,boolean d){return (boolean)m.getOrDefault(k,d);}}''',
'android/content/BroadcastReceiver.java':'''package android.content;import android.os.Bundle;public abstract class BroadcastReceiver {
public int code;public Bundle extras;public int getResultCode(){return code;}public Bundle getResultExtras(boolean b){return extras;}
public abstract void onReceive(Context c,Intent i);}''',
'android/content/Context.java':'''package android.content;import android.os.*;public class Context {
public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.content.pm.PackageManager getPackageManager(){return pm;}public Intent sent;public String permission;public BroadcastReceiver receiver;public boolean fail;
public void sendOrderedBroadcast(Intent i,String p,BroadcastReceiver r,Handler h,int n,String s,Bundle b){if(fail)throw new IllegalStateException();sent=i;permission=p;receiver=r;}}''',
'android/view/WindowManager.java':'''package android.view;public class WindowManager {public static class LayoutParams {
public static final int FLAG_SHOW_WHEN_LOCKED=524288;public int type=2038,flags;public String packageName="com.android.systemui";
public String title="MiStepHost";public CharSequence getTitle(){return title;}}}''',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook {
public static class MethodHookParam {public Object thisObject;public Object[]args;public boolean hasThrowable(){return false;}}
protected void afterHookedMethod(MethodHookParam p){}public void run(Object o,Object...a){MethodHookParam p=new MethodHookParam();p.thisObject=o;p.args=a;afterHookedMethod(p);}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;public class XposedBridge {
public static XC_MethodHook hook;public static void hookAllMethods(Class<?> c,String n,XC_MethodHook h){hook=h;}public static void log(String s){}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers {
public static Class<?> findClass(String n,ClassLoader l){return Object.class;}
public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new IllegalStateException(e);}}
public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}public static boolean getBooleanField(Object o,String n){return (Boolean)getObjectField(o,n);}
public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new IllegalStateException(e);}}}''',
'com/jiusis/mistep/RetainedWindowRegression.java':r'''package com.jiusis.mistep;
import android.os.*;import android.content.*;import android.view.WindowManager.LayoutParams;import de.robv.android.xposed.*;import java.util.*;
public class RetainedWindowRegression {
public static class Token implements IBinder {}public static class Service {public Context mContext=new Context();public Map<Object,Object>mWindowMap=new HashMap<>();public Object mGlobalLock=new Object();}
public static class Display {public Service mWmService=new Service();public Object getPendingTransaction(){return transaction;}public void scheduleAnimation(){}}
public static class Activity {public boolean showWhenLocked;public boolean canShowWhenLocked(){return showWhenLocked;}}
public static class Surface {public float alpha=1;public boolean isValid(){return true;}}
public static class Tx {public void setAlpha(Object s,float a){((Surface)s).alpha=a;}}
public static class Session {public int mUid=1000;}
public static class Window {public Session mSession=new Session();public int mOwnerUid=1000;public LayoutParams mAttrs=new LayoutParams();public Display dc;public Object mActivityRecord;public Surface surface=new Surface();public int mode=1;public int getWindowingMode(){return mode;}public Object getSurfaceControl(){return surface;}public Animator mWinAnimator=new Animator();public boolean onScreen;public Object getPendingTransaction(){return transaction;}public Object getDisplayContent(){return dc;}public boolean isOnScreen(){return onScreen;}}
public static class LegacyAnimator {public boolean mSurfaceShown=true;}
public static class Animator {public boolean getShown(){return shown;}public Window mWin;public boolean shown=true;public int mDrawState=4;public float mAlpha=1;}
static int checks,calls;static boolean authenticated;static MiStepRetainedWindow lease;static Window win;static Display dc;static Token token;static Animator anim;static Object transaction;
static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
static Intent request(long generation){Bundle b=new Bundle();b.putBinder("host",token);return new Intent(MiStepRetainedWindow.ACTION).putExtra("state",b).putExtra("generation",generation).putExtra("scale",.7f).putExtra("x",0f).putExtra("y",606f);}
static void fresh(){calls=0;authenticated=false;dc=new Display();token=new Token();win=new Window();win.dc=dc;dc.mWmService.mWindowMap.put(token,win);anim=win.mWinAnimator;anim.mWin=win;transaction=new Tx();android.os.SystemClock.now=100;lease=new MiStepRetainedWindow();lease.install(RetainedWindowRegression.class.getClassLoader(),()->dc,()->authenticated,(t,g,s,x,y)->{check(t==transaction&&g==100&&s==.7f&&x==0&&y==606,"lost exact native reveal transaction/geometry/generation");calls++;});}
static void reveal(){XposedBridge.hook.run(anim.mWin);}
public static void main(String[]args){
check(MiStepRetainedWindow.surfaceShown(new LegacyAnimator()),"legacy field fallback");var legacy=new LegacyAnimator();legacy.mSurfaceShown=false;check(!MiStepRetainedWindow.surfaceShown(legacy),"legacy hidden surface");var modern=new Animator();check(MiStepRetainedWindow.surfaceShown(modern),"native getter without old field");modern.shown=false;check(!MiStepRetainedWindow.surfaceShown(modern),"native getter hidden surface");
fresh();check(lease.retain(dc,request(100))!=null,"native hidden own Host must be retained");win.onScreen=true;reveal();check(calls==0,"native showing alone must not bypass full authentication");authenticated=true;reveal();check(calls==1,"authenticated native window show must restore same transaction");reveal();check(calls==1,"reveal can only consume once");
for(int uid:new int[]{10237,1010237}){fresh();win.mOwnerUid=uid;win.mSession.mUid=uid;dc.mWmService.mContext.pm.owners.put(uid,new String[]{"com.android.systemui"});check(lease.retain(dc,request(100))!=null,"registered SystemUI UID/native session must work independently of model or user");authenticated=true;win.onScreen=true;reveal();check(calls==1,"registered native UID failed authenticated same-transaction reveal");}
for(int gate=0;gate<5;gate++){fresh();win.mOwnerUid=10237;win.mSession.mUid=10237;var pm=dc.mWmService.mContext.pm;pm.owners.put(10237,new String[]{"com.android.systemui"});if(gate==0)win.mSession.mUid=1000;if(gate==1)pm.owners.remove(10237);if(gate==2)pm.owners.put(10237,new String[]{"foreign"});if(gate==3)pm.fail=true;if(gate==4)win.mSession=null;check(lease.retain(dc,request(100))==null,"spoofed/unresolved native identity granted privileged lease");}
for(int gate=0;gate<9;gate++){fresh();if(gate==0)win.onScreen=true;if(gate==1)win.mOwnerUid=1001;if(gate==2)win.mAttrs.type=2000;if(gate==3)win.mAttrs.packageName="foreign";if(gate==4)win.mAttrs.title="foreign";if(gate==5)win.mAttrs.flags=524288;if(gate==6)win.dc=new Display();if(gate==7)dc.mWmService.mWindowMap.clear();Intent i=request(100);if(gate==8)i.putExtra("scale",Float.NaN);check(lease.retain(dc,i)==null,"foreign/on-screen/showWhenLocked/invalid lease granted");authenticated=true;win.onScreen=true;reveal();check(calls==0,"rejected lease revealed a window");}
for(int gate=0;gate<5;gate++){fresh();lease.retain(dc,request(100));win.onScreen=true;authenticated=true;if(gate==0)anim.shown=false;if(gate==1)anim.mDrawState=3;if(gate==2)anim.mWin=new Window();if(gate==3)win.mAttrs.title="changed";if(gate==4)win.onScreen=false;reveal();check(calls==0,"non-reveal or changed owner consumed geometry");}
fresh();lease.retain(dc,request(100));check(lease.retain(dc,request(99))==null,"stale generation overwrote native lease");win.onScreen=true;authenticated=true;reveal();check(calls==1,"stale rejection damaged exact current lease");
fresh();Handler handler=new Handler();lease.setHandler(handler);lease.retain(dc,request(100));Window main=new Window();main.dc=dc;main.mAttrs.type=1;main.mActivityRecord=new Activity();main.onScreen=true;
XposedBridge.hook.run(main);check(main.surface.alpha==0&&calls==0,"native opening main must wait behind lock without revealing Host");
Window side=new Window();side.dc=dc;side.mAttrs.type=1;side.mActivityRecord=new Activity();side.onScreen=true;side.mode=6;XposedBridge.hook.run(side);check(side.surface.alpha==1,"owned side task is outside main-window hold");
Window shade=new Window();shade.dc=dc;shade.mAttrs.type=2040;shade.onScreen=true;XposedBridge.hook.run(shade);check(shade.surface.alpha==1,"native physical lockscreen shade must remain untouched");
win.onScreen=true;authenticated=true;XposedBridge.hook.run(win);check(main.surface.alpha==1&&calls==1,"exact native Host transaction must release original main alpha");handler.timeout.run();check(main.surface.alpha==1&&calls==1,"old watchdog cannot change completed native handoff");
fresh();handler=new Handler();lease.setHandler(handler);lease.retain(dc,request(100));main=new Window();main.dc=dc;main.mAttrs.type=1;main.mActivityRecord=new Activity();main.onScreen=true;XposedBridge.hook.run(main);check(main.surface.alpha==0,"watchdog fixture needs a held real main");handler.timeout.run();check(main.surface.alpha==1&&calls==0,"timeout must restore actual native main without bypassing authentication");
fresh();handler=new Handler();lease.setHandler(handler);main=new Window();main.dc=dc;main.mAttrs.type=1;main.mActivityRecord=new Activity();main.mWinAnimator.shown=false;dc.mWmService.mWindowMap.put(new Token(),main);lease.retain(dc,request(100));check(main.surface.alpha==0&&handler.timeout==null,"lock-time priming must hide canonical main before client recreation, without timing out while user remains locked");main.onScreen=true;main.mWinAnimator.shown=true;XposedBridge.hook.run(main);check(handler.timeout!=null,"native opening starts bounded watchdog after lock-time priming");win.onScreen=true;authenticated=true;XposedBridge.hook.run(win);check(main.surface.alpha==1&&calls==1,"primed canonical main released only with authenticated Host transaction");
fresh();handler=new Handler();lease.setHandler(handler);main=new Window();main.dc=dc;main.mAttrs.type=1;main.mActivityRecord=new Activity();((Activity)main.mActivityRecord).showWhenLocked=true;dc.mWmService.mWindowMap.put(new Token(),main);lease.retain(dc,request(100));check(main.surface.alpha==1,"native activity permitted on keyguard is never held");
fresh();Context c=new Context();final int[] ready={0};MiStepRetainedWindow.request(c,new Handler(),token,100,.7f,0,606,ok->{if(ok)ready[0]++;});check("android".equals(c.sent.pkg)&&"protected".equals(c.permission),"request not protected/scoped");c.receiver.code=-1;c.receiver.extras=new Bundle();c.receiver.extras.putLong("generation",99);c.receiver.onReceive(c,c.sent);check(ready[0]==0,"stale WM hidden acknowledgement warmed Host");c.receiver.extras.putLong("generation",100);c.receiver.onReceive(c,c.sent);check(ready[0]==1,"exact native hidden acknowledgement lost");
System.out.println("Production retained native WindowState: "+checks+" checks passed");
}}
'''}
paths=[]
for name,body in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.extend(str(root/'module/src/main/java/com/jiusis/mistep'/n) for n in ['MiStepRetainedWindow.java','MiStepWindowIdentity.java'])
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.RetainedWindowRegression'],check=True)
