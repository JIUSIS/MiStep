import os
"""Run the production exact-task bridge against caller, scene and native-task changes."""
from pathlib import Path
import subprocess, sys
root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jianguo/mistep'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
files = {
'android/Manifest.java': 'package android; public class Manifest {public static class permission {public static final String WRITE_SECURE_SETTINGS="secure";}}',
'android/os/IBinder.java': 'package android.os; public interface IBinder {boolean isBinderAlive();void linkToDeath(DeathRecipient r,int f);boolean unlinkToDeath(DeathRecipient r,int f);interface DeathRecipient {void binderDied();}}',
'android/os/Binder.java': 'package android.os;public class Binder implements IBinder {public static int uid=10127;public boolean alive=true;public static int getCallingUid(){return uid;}public boolean isBinderAlive(){return alive;}public void linkToDeath(DeathRecipient r,int f){}public boolean unlinkToDeath(DeathRecipient r,int f){return true;}}',
'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long now=10000;public static long uptimeMillis(){return now;}}',
'android/os/UserHandle.java': 'package android.os;public class UserHandle {public static int getUserId(int uid){return uid/100000;}}',
'android/os/Bundle.java': '''package android.os;import java.util.*;public class Bundle {
 Map<String,Object>m=new HashMap<>();public Bundle(){}public Bundle(Bundle b){m.putAll(b.m);}
 public void putBinder(String k,IBinder v){m.put(k,v);}public IBinder getBinder(String k){return (IBinder)m.get(k);}
 public void putInt(String k,int v){m.put(k,v);}public int getInt(String k){return getInt(k,0);}public int getInt(String k,int d){return m.containsKey(k)?(Integer)m.get(k):d;}
 public void putLong(String k,long v){m.put(k,v);}public long getLong(String k,long d){return m.containsKey(k)?(Long)m.get(k):d;}
 public void putBoolean(String k,boolean v){m.put(k,v);}public boolean getBoolean(String k,boolean d){return m.containsKey(k)?(Boolean)m.get(k):d;}
 public void putString(String k,String v){m.put(k,v);}public String getString(String k){return (String)m.get(k);}
 public <T>void putParcelableArrayList(String k,ArrayList<T>v){m.put(k,v);}public <T>ArrayList<T>getParcelableArrayList(String k,Class<T>t){return (ArrayList<T>)m.get(k);}}
''',
'android/content/ComponentName.java': 'package android.content;public class ComponentName {String pkg;public ComponentName(String p,String c){pkg=p;}public String getPackageName(){return pkg;}}',
'android/content/Intent.java': '''package android.content;import java.util.*;import android.os.*;public class Intent {
 public static final String ACTION_MAIN="android.intent.action.MAIN",CATEGORY_LAUNCHER="android.intent.category.LAUNCHER";public static final int FLAG_RECEIVER_FOREGROUND=1;
 public String action,pkg;public ComponentName component;Set<String>categories=new HashSet<>();Map<String,Object>extras=new HashMap<>();
 public Intent(String a){action=a;}public String getAction(){return action;}public Intent addCategory(String c){categories.add(c);return this;}public boolean hasCategory(String c){return categories.contains(c);}public ComponentName getComponent(){return component;}public Intent setComponent(ComponentName c){component=c;return this;}
 public Intent setPackage(String p){pkg=p;return this;}public Intent addFlags(int f){return this;}public Intent putExtra(String k,Object v){extras.put(k,v);return this;}public Bundle getBundleExtra(String k){return (Bundle)extras.get(k);}}
''',
'android/content/pm/PackageManager.java': 'package android.content.pm;public class PackageManager {public String[]packages={"com.miui.home"};public String[]getPackagesForUid(int u){return packages;}}',
'android/content/Context.java': '''package android.content;import java.util.*;import android.content.pm.*;public class Context {public PackageManager pm=new PackageManager();public List<Intent>sent=new ArrayList<>();public boolean fail;public PackageManager getPackageManager(){return pm;}public void sendBroadcast(Intent i,String p){if(fail)throw new IllegalStateException();sent.add(i);}}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;import java.lang.reflect.*;public class XposedHelpers {
 public static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new IllegalStateException(e);}}
 public static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
 public static Class<?>findClass(String n,ClassLoader l){try{return Class.forName(n,true,l);}catch(Exception e){throw new IllegalStateException(e);}}
 public static Object callMethod(Object o,String n,Object...a){return call(o.getClass(),o,n,a);}public static Object callStaticMethod(Class<?>c,String n,Object...a){return call(c,null,n,a);}
 static Object call(Class<?>c,Object o,String n,Object[]a){try{for(var m:c.getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length){m.setAccessible(true);return m.invoke(o,a);}throw new NoSuchMethodException(n);}catch(Exception e){throw new IllegalStateException(e);}}}
''',
'com/android/server/wm/WindowContainer.java': 'package com.android.server.wm;import android.os.*;import java.util.*;public class WindowContainer {public static Map<IBinder,Object>tasks=new HashMap<>();public static Object fromBinder(IBinder b){return tasks.get(b);}}',
'com/jianguo/mistep/DesktopRouteRegression.java': '''package com.jianguo.mistep;
import android.os.*;import android.content.*;import com.android.server.wm.WindowContainer;import java.util.*;
public class DesktopRouteRegression {
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static class Token {IBinder binder=new Binder();IBinder asBinder(){return binder;}}
 static class Info {int taskId=7,userId=0,displayId=0;Token token=new Token();ComponentName baseActivity=new ComponentName("com.android.settings","Settings");boolean parent;boolean hasParentTask(){return parent;}}
 static class Task {int mTaskId=7,mode=6,type=1;Info info=new Info();int getWindowingMode(){return mode;}Info getTaskInfo(){return info;}int getActivityType(){return type;}}
 static class Root {Task home=new Task();Root(){home.type=2;}Task getTopDisplayFocusedRootTask(){return home;}}
 static class Atms {Object mGlobalLock=new Object();Root mRootWindowContainer=new Root();}
 Context ctx=new Context();Atms atms=new Atms();Task task=new Task();Binder owner=new Binder();Bundle row=new Bundle();long sequence=1;
 Intent entry=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName("com.android.settings","Settings"));
 DesktopRouteRegression(){SystemClock.now+=1000;WindowContainer.tasks.clear();WindowContainer.tasks.put(task.info.token.binder,task);row.putBinder("task",task.info.token.binder);row.putInt("taskId",7);row.putInt("userId",0);row.putInt("displayId",0);row.putString("package","com.android.settings");row.putLong("scene",3);row.putLong("binding",8);register(new ArrayList<>(List.of(row)));}
 void register(ArrayList<Bundle>rows){Bundle state=new Bundle();state.putBinder("owner",owner);state.putLong("sequence",sequence++);state.putParcelableArrayList("rows",rows);state.putParcelableArrayList("recents",new ArrayList<>(List.of(row)));MiStepDesktopLaunchBridge.update(new Intent("update").putExtra("state",state));}
 boolean recent(int id){return MiStepDesktopLaunchBridge.beforeRecents(ctx,atms,new Object[]{id,null});}
 boolean route(){return MiStepDesktopLaunchBridge.before(ctx,atms,new Object[]{"com.miui.home",entry});}
 public static void main(String[]args){
  var h=new DesktopRouteRegression();check(h.route()&&h.ctx.sent.size()==1,"confirmed mode6 task routes once through Host");Bundle b=h.ctx.sent.get(0).getBundleExtra("binding");check(b.getBoolean("hostRoute",false)&&b.getBinder("task")==h.task.info.token.binder&&b.getLong("deadline",0)==SystemClock.now+1500,"exact binding and bounded handoff deadline");check(h.task.mode==6,"bridge never restores fullscreen or mutates the native task");check(h.route()&&h.ctx.sent.size()==1,"duplicate click cannot leak a competing native launch");
  h=new DesktopRouteRegression();h.ctx.pm.packages=new String[]{"other"};check(!h.route()&&h.ctx.sent.isEmpty(),"unrelated caller untouched");
  h=new DesktopRouteRegression();h.atms.mRootWindowContainer.home.type=1;check(!h.route(),"application-main scene untouched");
  h=new DesktopRouteRegression();h.task.mode=1;check(!h.route(),"already fullscreen task stays with native owner");
  h=new DesktopRouteRegression();h.task.mTaskId=8;check(!h.route(),"reused task ID rejected");
  h=new DesktopRouteRegression();h.task.info.token=new Token();check(!h.route(),"replaced task token rejected");
  h=new DesktopRouteRegression();h.task.info.parent=true;check(!h.route(),"embedded child rejected");
  h=new DesktopRouteRegression();h.task.info.displayId=1;check(!h.route(),"secondary display rejected");
  h=new DesktopRouteRegression();h.task.info.userId=10;check(!h.route(),"other user rejected");
  h=new DesktopRouteRegression();h.task.info.baseActivity=new ComponentName("different","Main");check(!h.route(),"different task package rejected");
  h=new DesktopRouteRegression();h.register(new ArrayList<>(List.of(h.row,new Bundle(h.row))));check(!h.route(),"ambiguous package rejected");
  h=new DesktopRouteRegression();h.owner.alive=false;check(!h.route(),"dead Host registry rejected");
  h=new DesktopRouteRegression();h.ctx.fail=true;check(!h.route(),"failed enqueue leaves original launch available");
  h=new DesktopRouteRegression();h.register(new ArrayList<>());check(!h.route(),"inactive/busy registration cannot capture a native launch");
  h=new DesktopRouteRegression();h.atms.mRootWindowContainer.home.type=1;check(h.recent(7)&&h.ctx.sent.size()==1,"exact Recents selection works while an app is behind Recents");check(h.recent(7)&&h.ctx.sent.size()==1,"repeated Recents click is coalesced");
  h=new DesktopRouteRegression();check(!h.recent(8),"unowned task stays native");
  h=new DesktopRouteRegression();h.task.info.token=new Token();check(!h.recent(7),"Recents token replacement rejected");
  h=new DesktopRouteRegression();h.task.info.userId=10;check(!h.recent(7),"Recents other-user task rejected");
  h=new DesktopRouteRegression();h.task.info.displayId=1;check(!h.recent(7),"Recents other-display task rejected");
  h=new DesktopRouteRegression();h.task.info.parent=true;check(!h.recent(7),"Recents embedded task rejected");
  h=new DesktopRouteRegression();h.task.mode=1;check(!h.recent(7),"Recents fullscreen task stays native");
  h=new DesktopRouteRegression();h.ctx.pm.packages=new String[]{"other"};check(!h.recent(7),"Recents untrusted caller rejected");
  h=new DesktopRouteRegression();h.register(new ArrayList<>());check(!h.recent(7),"inactive registry cannot route Recents");
  h=new DesktopRouteRegression();h.ctx.fail=true;check(!h.recent(7),"failed Recents handoff stays native");h.ctx.fail=false;check(h.recent(7)&&h.ctx.sent.size()==1,"retry after enqueue failure really dispatches");
  System.out.println("actual desktop route checks="+checks+" passed");
 }
}
'''
}
for name, text in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out)]+[str(out/n) for n in files]+[str(src/n) for n in ['MiStepDesktopLaunchBridge.java','DesktopIconLaunchPolicy.java']],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.DesktopRouteRegression'],check=True)
