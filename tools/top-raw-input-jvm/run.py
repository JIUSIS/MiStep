"""Replay actual TOP token/UID validation and the protected scene registry."""
from pathlib import Path
import ast, os, subprocess, sys
root=Path(__file__).resolve().parents[2]
src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
tree=ast.parse((root/'tools/desktop-route-jvm/run.py').read_text(encoding='utf-8'))
files=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='files' for t in n.targets))
files.pop('com/jiusis/mistep/DesktopRouteRegression.java')
files['android/os/Bundle.java']=files['android/os/Bundle.java'].replace('Map<String,Object>m=', 'public Bundle getBundle(String k){return (Bundle)m.get(k);}public void putBundle(String k,Bundle b){m.put(k,b);}public void putFloat(String k,float v){m.put(k,v);}public float getFloat(String k,float d){return m.containsKey(k)?(Float)m.get(k):d;} Map<String,Object>m=')
files['android/os/Binder.java']='''package android.os;import java.util.*;public class Binder implements IBinder {public static int uid=10127;public boolean alive=true;List<DeathRecipient> listeners=new ArrayList<>();public static int getCallingUid(){return uid;}public boolean isBinderAlive(){return alive;}public void linkToDeath(DeathRecipient r,int f){listeners.add(r);}public boolean unlinkToDeath(DeathRecipient r,int f){return listeners.remove(r);}public void die(){alive=false;for(var r:new ArrayList<>(listeners))r.binderDied();}}'''
files['android/graphics/Rect.java']=(root/'tools/test-stubs/android/graphics/Rect.java').read_text(encoding='utf-8')
files['com/jiusis/mistep/NativeRawInputBridge.java']='package com.jiusis.mistep;class NativeRawInputBridge {static int uid=-1,calls;static float scale,left,top;static void updateTop(int u,float s){updateTop(u,s,0,0);}static void updateTop(int u,float s,float x,float y){uid=u;scale=s;left=x;top=y;calls++;}}'
files['com/jiusis/mistep/TopRawRegression.java']='''package com.jiusis.mistep;
import android.os.*;import android.content.*;import android.graphics.Rect;import java.util.*;import com.android.server.wm.WindowContainer;
public class TopRawRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static class Token {IBinder binder=new Binder();IBinder asBinder(){return binder;}}
 static class App {int uid=10321;}
 static class Activity {App applicationInfo=new App();}
 static class Info {int taskId=743,userId=0,displayId=0;int type=1;boolean parent;int getActivityType(){return type;}boolean hasParentTask(){return parent;}Token token=new Token();Activity topActivityInfo=new Activity();}
 static class Task {int mTaskId=743,mode=6;boolean translucent=true;Rect bounds=new Rect(0,0,2964,1707);Info info=new Info();int getWindowingMode(){return mode;}boolean isForceTranslucent(){return translucent;}Rect getBounds(){return bounds;}Info getTaskInfo(){return info;}}
 static class Service {Object mGlobalLock=new Object();}
 Task task=new Task();Bundle row=new Bundle();Binder owner=new Binder();Service service=new Service();long sequence=1;
 TopRawRegression(){WindowContainer.tasks.clear();WindowContainer.tasks.put(task.info.token.binder,task);row.putBinder("task",task.info.token.binder);row.putInt("taskId",743);row.putInt("userId",0);row.putInt("displayId",0);row.putInt("width",2964);row.putInt("height",1707);row.putFloat("scale",.364424f);}
 int uid(){try{return MiStepTopRawInput.uid(row,getClass().getClassLoader());}catch(Throwable reject){return -1;}}
 void register(Bundle top,long seq){Bundle state=new Bundle();state.putBinder("owner",owner);state.putLong("sequence",seq);state.putParcelableArrayList("rows",new ArrayList<Bundle>());state.putParcelableArrayList("recents",new ArrayList<Bundle>());state.putBundle("topInput",top);MiStepDesktopLaunchBridge.update(new Intent("update").putExtra("state",state));MiStepDesktopLaunchBridge.refreshTopInput(service);}
 public static void main(String[] args){
  var h=new TopRawRegression();check(h.uid()==10321,"real task UID resolved");
  h.task.bounds=new Rect(0,0,1251,2519);h.row.putInt("width",1251);h.row.putInt("height",2519);h.row.putFloat("scale",.605f);h.row.putFloat("left",2291);h.row.putFloat("top",508);
  check(h.uid()==10321,"exact portrait interactive viewport accepted");h.register(h.row,1);
  check(NativeRawInputBridge.left==2291&&NativeRawInputBridge.top==508&&NativeRawInputBridge.scale==.605f,"right offset reaches native lease");
  h.row.putFloat("left",Float.NaN);check(h.uid()<0,"nonfinite offset rejected");h=new TopRawRegression();
  h.task.mode=1;check(h.uid()<0,"returned fullscreen rejected");h.task.mode=6;
  h.task.translucent=false;check(h.uid()<0,"foreign native mode6 rejected");h.task.translucent=true;
  h.task.mTaskId++;check(h.uid()<0,"stale task ID rejected");h.task.mTaskId--;
  h.task.info.token=new Token();check(h.uid()<0,"replaced token rejected");h=new TopRawRegression();
  h.task.info.userId=999;check(h.uid()<0,"other task user rejected");h.task.info.userId=0;
  h.task.info.displayId=1;check(h.uid()<0,"other display rejected");h.task.info.displayId=0;
  h.task.bounds=new Rect(0,0,1630,939);check(h.uid()<0,"stale geometry rejected");h.task.bounds=new Rect(1,0,2965,1707);check(h.uid()<0,"shifted geometry rejected");h.task.bounds=new Rect(0,0,2964,1707);
  for(float s:new float[]{1,0,-1,Float.NaN,Float.POSITIVE_INFINITY}){h.row.putFloat("scale",s);check(h.uid()<0,"invalid scale rejected");}h.row.putFloat("scale",.364424f);
  h.task.info.topActivityInfo.applicationInfo.uid=1000;check(h.uid()<0,"system UID rejected");h.task.info.topActivityInfo.applicationInfo.uid=99910321;check(h.uid()<0,"clone UID mismatch rejected");
  h.row.putInt("userId",999);h.task.info.userId=999;check(h.uid()==99910321,"exact clone user accepted");h=new TopRawRegression();
  h.task.info.topActivityInfo=null;check(h.uid()<0,"absent activity rejected");h=new TopRawRegression();
  h.register(h.row,1);check(NativeRawInputBridge.uid==10321&&NativeRawInputBridge.scale==.364424f,"accepted scene updates native TOP");
  h.register(null,0);check(NativeRawInputBridge.uid==10321,"out of order clear cannot replace scene");
  h.register(null,2);check(NativeRawInputBridge.uid<0,"hidden returned task clears TOP");
  h.register(h.row,3);h.task.mode=1;MiStepDesktopLaunchBridge.refreshTopInput(h.service);check(NativeRawInputBridge.uid<0,"native mode revalidated before update");h.task.mode=6;
  h.register(h.row,4);h.owner.die();check(NativeRawInputBridge.uid<0,"host Binder death immediately clears TOP");
  h=new TopRawRegression();h.register(h.row,1);h.task.info.topActivityInfo.applicationInfo.uid=10322;h.register(h.row,2);check(NativeRawInputBridge.uid==10322,"activity UID refresh follows exact task");
  check(MiStepDesktopLaunchBridge.ownsTopTask(h.task),"current exact TOP token lease not recognized");
  h.task.info.userId=999;check(!MiStepDesktopLaunchBridge.ownsTopTask(h.task),"TOP user mismatch admitted");h.task.info.userId=0;
  h.task.info.displayId=1;check(!MiStepDesktopLaunchBridge.ownsTopTask(h.task),"foreign TOP display admitted");h.task.info.displayId=0;
  h.task.info.token=new Token();check(!MiStepDesktopLaunchBridge.ownsTopTask(h.task),"reused TOP id with new token admitted");
  h=new TopRawRegression();h.register(h.row,1);h.owner.die();check(!MiStepDesktopLaunchBridge.ownsTopTask(h.task),"dead TOP owner lease survived");
  h=new TopRawRegression();h.register(null,1);h.task.mode=1;
  Bundle pending=new Bundle(h.row);pending.putBinder("owner",h.owner);pending.putParcelable("bounds",h.task.bounds);
  var request=new Intent("prepare").putExtra("state",pending);
  check(MiStepDesktopLaunchBridge.prepareTop(h.service,request),"exact fullscreen request not prepared");
  check(!MiStepDesktopLaunchBridge.ownsTopViewport(h.task),"early lease changed fullscreen");h.task.mode=6;
  check(MiStepDesktopLaunchBridge.ownsTopViewport(h.task),"first mode6 layout lacks viewport before confirmation");
  check(!MiStepDesktopLaunchBridge.ownsTopTask(h.task),"early viewport grants confirmed input lease");
  h.task.bounds=new Rect(0,0,1630,939);check(!MiStepDesktopLaunchBridge.ownsTopViewport(h.task),"wrong pending viewport accepted");h.task.bounds=new Rect(0,0,2964,1707);
  h.task.info.userId=999;check(!MiStepDesktopLaunchBridge.ownsTopViewport(h.task),"pending different user accepted");h.task.info.userId=0;
  h.task.info.displayId=1;check(!MiStepDesktopLaunchBridge.ownsTopViewport(h.task),"pending foreign display accepted");h.task.info.displayId=0;
  h.task.translucent=false;check(!MiStepDesktopLaunchBridge.ownsTopViewport(h.task),"pending native unowned mode6 accepted");h.task.translucent=true;
  android.os.SystemClock.now+=5001;check(!MiStepDesktopLaunchBridge.ownsTopViewport(h.task),"pending lease does not expire");
  h.task.mode=1;check(MiStepDesktopLaunchBridge.prepareTop(h.service,request),"fresh request rejected");h.task.mode=6;h.owner.die();check(!MiStepDesktopLaunchBridge.ownsTopViewport(h.task),"dead pending owner accepted");
  h=new TopRawRegression();h.task.mode=1;pending=new Bundle(h.row);pending.putBinder("owner",h.owner);pending.putParcelable("bounds",h.task.bounds);request=new Intent("prepare").putExtra("state",pending);
  h.task.info.parent=true;check(!MiStepDesktopLaunchBridge.prepareTop(h.service,request),"nested task early lease accepted");h.task.info.parent=false;
  h.task.info.type=2;check(!MiStepDesktopLaunchBridge.prepareTop(h.service,request),"Home early lease accepted");h.task.info.type=1;
  pending.putInt("taskId",744);check(!MiStepDesktopLaunchBridge.prepareTop(h.service,request),"stale requested task accepted");pending.putInt("taskId",743);
  pending.putParcelable("bounds",new Rect(0,0,1080,2460));check(MiStepDesktopLaunchBridge.prepareTop(h.service,request),"portrait pending viewport rejected");
  pending.putParcelable("bounds",new Rect(1,0,1081,2460));check(!MiStepDesktopLaunchBridge.prepareTop(h.service,request),"offset logical viewport accepted");
  System.out.println("actual TOP raw ownership checks="+checks+" passed");
 }
}'''
for name,content in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out)]+[str(out/n) for n in files]+[str(src/n) for n in ['MiStepTopRawInput.java','MiStepDesktopLaunchBridge.java','MiStepSideRoleChange.java','DesktopIconLaunchPolicy.java']],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TopRawRegression'],check=True)
