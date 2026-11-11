"""Run the real wallpaper acquisition gate and bounded reply/timeout ownership."""
from pathlib import Path
import os
import subprocess
import sys

root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'de/robv/android/xposed/XposedBridge.java':'package de.robv.android.xposed;public class XposedBridge {public static void log(String s){}}',
'android/Manifest.java':'package android;public class Manifest {public static class permission {public static final String WRITE_SECURE_SETTINGS="secure";}}',
'android/os/IBinder.java':'package android.os;public interface IBinder {}',
'android/os/Bundle.java':'''package android.os;import java.util.*;public class Bundle {Map<String,Object>data=new HashMap<>();public void putBinder(String n,IBinder o){data.put(n,o);}public IBinder getBinder(String n){return (IBinder)data.get(n);}public void putParcelable(String n,Object o){data.put(n,o);}public <T>T getParcelable(String n,Class<T>t){return t.cast(data.get(n));}}''',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {public List<Runnable>tasks=new ArrayList<>();public void postDelayed(Runnable r,long delay){tasks.add(r);}public void removeCallbacks(Runnable r){tasks.remove(r);}public void expire(){for(var r:List.copyOf(tasks)){tasks.remove(r);r.run();}}}''',
'android/content/pm/PackageManager.java':'package android.content.pm;import java.util.*;public class PackageManager {\npublic Map<Integer,String[]> owners=new HashMap<>();public boolean fail;\npublic PackageManager(){owners.put(1000,new String[]{"android","com.android.systemui"});}\npublic String[] getPackagesForUid(int uid){if(fail)throw new IllegalStateException("PM unavailable");return owners.get(uid);}}',
'android/content/Intent.java':'''package android.content;import android.os.*;public class Intent {Bundle state;public Intent(String a){}public Intent setPackage(String p){return this;}public Intent putExtra(String n,Bundle b){state=b;return this;}public Bundle getBundleExtra(String n){return state;}}''',
'android/content/BroadcastReceiver.java':'''package android.content;import android.os.*;public abstract class BroadcastReceiver {public Bundle reply;public int code;public Bundle getResultExtras(boolean b){return reply;}public int getResultCode(){return code;}public abstract void onReceive(Context c,Intent i);}''',
'android/content/Context.java':'''package android.content;import android.os.*;public class Context {public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();public android.content.pm.PackageManager getPackageManager(){return pm;}public BroadcastReceiver receiver;public boolean fail;public void sendOrderedBroadcast(Intent i,String p,BroadcastReceiver r,Handler h,int c,String d,Bundle b){if(!p.equals("secure"))throw new AssertionError();if(fail)throw new IllegalStateException("transport");receiver=r;}}''',
'android/graphics/Bitmap.java':'package android.graphics;public class Bitmap {}',
'android/view/WindowManager.java':'package android.view;public class WindowManager {public static class LayoutParams {public static final int FLAG_SHOW_WHEN_LOCKED=524288;public int type=2038,flags;public String packageName="com.android.systemui",title="MiStepHost";public String getTitle(){return title;}}}',
'android/view/SurfaceControl.java':'''package android.view;public class SurfaceControl {public boolean valid=true;public int releases;public SurfaceControl anchor;public int relativeZ=999;public boolean isValid(){return valid;}public void release(){releases++;}public static class Transaction implements AutoCloseable {public Transaction setRelativeLayer(SurfaceControl s,SurfaceControl a,int n){s.anchor=a;s.relativeZ=n;return this;}public void apply(){}public void close(){}}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers {public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}}''',
'com/jiusis/mistep/MiStepLiveBackdrop.java':'''package com.jiusis.mistep;import android.view.*;import android.graphics.*;import java.util.function.Consumer;class MiStepLiveBackdrop {static int creates,closes;static boolean fail;Object surface=new SurfaceControl();static MiStepLiveBackdrop create(Object p,Bitmap b,int w,int h,Consumer<String>log){if(fail)throw new IllegalStateException("producer");creates++;return new MiStepLiveBackdrop();}void close(){closes++;}}''',
'com/jiusis/mistep/WallpaperLeaseRegression.java':'''package com.jiusis.mistep;import android.os.*;import android.content.*;import android.view.*;import android.graphics.*;import java.util.*;
public class WallpaperLeaseRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static class Node {public Object mLastMagnificationSpec;Node parent;Display display;boolean magnifiable;String name="Node";SurfaceControl surface=new SurfaceControl();public String getName(){return name;}public boolean shouldMagnify(){return magnifiable;}public Node getParent(){return parent;}public Display getDisplayContent(){return display;}public SurfaceControl getSurfaceControl(){return surface;}public boolean isVisible(){return true;}}
 public static class Attr extends android.view.WindowManager.LayoutParams {}
 public static class Session {public int mUid=1000;}
 public static class Host extends Node {public Session mSession=new Session();public int mOwnerUid=1000;public Attr mAttrs=new Attr();public boolean visible=true;@Override public boolean isVisible(){return visible;}}
 public static class Controller {public List<Node> mWallpaperTokens=new ArrayList<>();}
 public static class Service {public Context mContext=new Context();public Map<IBinder,Host>mWindowMap=new HashMap<>();}
 public static class Display extends Node {public Service mWmService=new Service();public Controller mWallpaperController=new Controller();}
 static Bundle reply(SurfaceControl p,SurfaceControl a){var b=new Bundle();b.putParcelable("parent",p);b.putParcelable("anchor",a);return b;}
 public static void main(String[]args){
  for(int gate=0;gate<9;gate++){
   var d=new Display();var parent=new Node();parent.display=d;parent.parent=d;var wallpaper=new Node();wallpaper.parent=parent;wallpaper.display=d;d.mWallpaperController.mWallpaperTokens.add(wallpaper);
   var host=new Host();host.display=d;host.mOwnerUid=10237;host.mSession.mUid=10237;d.mWmService.mContext.pm.owners.put(10237,new String[]{"com.android.systemui"});IBinder token=new IBinder(){};d.mWmService.mWindowMap.put(token,host);var state=new Bundle();state.putBinder("host",token);var i=new Intent("x").putExtra("state",state);
   if(gate==1)host.mOwnerUid=12345;if(gate==2)host.mAttrs.packageName="other";if(gate==3)host.mAttrs.title="other";if(gate==4)host.visible=false;if(gate==5)parent.mLastMagnificationSpec=new Object();if(gate==6)wallpaper.surface.valid=false;if(gate==7)host.display=new Display();if(gate==8)d.mWmService.mWindowMap.clear();
   var result=MiStepWorkspaceBackdrop.capture(d,i);
   check((result!=null)==(gate==0||gate==5),"exact native host and physical wallpaper parent gate "+gate);
   if(result!=null)check(result.getParcelable("parent",SurfaceControl.class)==(gate==5?d.surface:parent.surface)&&result.getParcelable("anchor",SurfaceControl.class)==(gate==5?parent.surface:wallpaper.surface),"parent/anchor remain outside magnified wallpaper branch, never an app task");
  }
  for(int state=0;state<3;state++){
   var d=new Display();var outer=new Node();outer.display=d;outer.parent=d;outer.name="OneHanded:0:23";outer.magnifiable=true;
   var branch=new Node();branch.display=d;branch.parent=outer;branch.magnifiable=true;
   var wallpaper=new Node();wallpaper.display=d;wallpaper.parent=branch;d.mWallpaperController.mWallpaperTokens.add(wallpaper);
   var host=new Host();host.display=d;host.mOwnerUid=10237;host.mSession.mUid=10237;d.mWmService.mContext.pm.owners.put(10237,new String[]{"com.android.systemui"});IBinder token=new IBinder(){};d.mWmService.mWindowMap.put(token,host);var request=new Bundle();request.putBinder("host",token);
   if(state==1)branch.mLastMagnificationSpec=new Object();
   if(state==2){branch.magnifiable=false;branch.mLastMagnificationSpec=new Object();}
   var result=MiStepWorkspaceBackdrop.capture(d,new Intent("x").putExtra("state",request));
   check(result!=null,"cold/active/retiring native wallpaper branch accepted "+state);
   check(result.getParcelable("parent",SurfaceControl.class)==outer.surface,"wallpaper stays outside future magnification even before first spec "+state);
   check(result.getParcelable("anchor",SurfaceControl.class)==branch.surface,"native excluded OneHanded ancestor remains the physical parent "+state);
  }
  for(int mode=0;mode<5;mode++){
   var c=new Context();var h=new Handler();var p=new SurfaceControl();var a=new SurfaceControl();int[]calls={0};MiStepLiveBackdrop[]lease={null};c.fail=mode==4;MiStepLiveBackdrop.fail=mode==3;
   MiStepWorkspaceBackdrop.acquire(c,h,new IBinder(){},new Bitmap(),3048,2032,b->{calls[0]++;lease[0]=b;},s->{});
   if(mode==4){check(calls[0]==1&&lease[0]==null&&h.tasks.isEmpty(),"transport failure completes exactly once");continue;}
   c.receiver.reply=reply(p,a);c.receiver.code=mode==2?0:-1;
   if(mode==1){h.expire();check(calls[0]==1&&lease[0]==null,"bounded timeout fails open");}
   c.receiver.onReceive(c,new Intent("reply"));
   check(calls[0]==1,"reply/timeout completes once");check((lease[0]!=null)==(mode==0),"only valid timely reply acquires wallpaper");
   if(mode==0){var surface=(SurfaceControl)lease[0].surface;check(surface.anchor==a,"backing stays relative to the original native wallpaper branch");check(surface.relativeZ<0,"opaque native main wallpaper must cover blurred physical backing");check(surface.valid&&MiStepLiveBackdrop.closes==0,"backing remains live outside the native main wallpaper coverage");}
   check(p.releases==1&&a.releases==1&&h.tasks.isEmpty(),"borrowed surface references released even on late reply/failure");
  }
  System.out.println("actual workspace wallpaper lease checks="+checks+" passed");
 }
}'''
}
paths=[]
for n,t in files.items():
 p=out/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(t,encoding='utf-8');paths.append(str(p))
paths.extend(str(root/'module/src/main/java/com/jiusis/mistep'/n) for n in ['MiStepWorkspaceBackdrop.java','MiStepWindowIdentity.java'])
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.WallpaperLeaseRegression'],check=True)
