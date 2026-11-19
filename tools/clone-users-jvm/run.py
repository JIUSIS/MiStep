"""Replay production collection/peer methods with independently frozen profile identities."""
from pathlib import Path
import os, subprocess, sys

root = Path(__file__).resolve().parents[2]
src = root / 'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1])
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([sys.executable, '-X', 'utf8', str(root/'tools/live-collect-jvm/run.py'), str(out)], check=True)

def member(path, signature):
    text = path.read_text(encoding='utf-8-sig')
    start = text.index(signature)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

resolve = member(src/'MiStepLiveCollect.java', '    @Override public MiStepLiveSwapSession.Ready resolve(')
peer = member(src/'MiStepLiveSwap64.java', '        Object peer(int i)')
files = {
'android/graphics/Point.java': 'package android.graphics; public class Point {public int x,y;}',
'com/jiusis/mistep/MiStepLiveSwap.java': '''package com.jiusis.mistep;
final class MiStepLiveSwap {static class Result {} static boolean startFlip(Object... a){return false;}static boolean startHome(Object... a){return false;}}''',
'com/jiusis/mistep/MiStepLiveSwapSession.java': '''package com.jiusis.mistep;
final class MiStepLiveSwapSession {static class Ready {
 final Object[] surfaces;
 Ready(Object[] s,Object parent,int[] layers,LiveSwapGeometry.Affine affine,LiveSwapGeometry.Affine[] content){surfaces=s;}
 Ready(Object a,Object b,Object p,int x,int y,LiveSwapGeometry.Affine t,LiveSwapGeometry.Affine u,LiveSwapGeometry.Affine v){surfaces=new Object[]{a,b};}
}}''',
'com/jiusis/mistep/CloneUsersRegression.java': r'''package com.jiusis.mistep;
import java.util.*;
import android.graphics.Rect;
import de.robv.android.xposed.XposedHelpers;
import com.jiusis.mistep.LivePairRegression.*;
public class CloneUsersRegression {
 static int checks;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 static void reject(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalStateException expected){checks++;}}
 public static class Root {
  public int getDisplayId(){return 0;}
  public android.graphics.Point getOffset(){return new android.graphics.Point();}
  public Surface getLeash(){return new Surface(800);}
 }
 public static class Transition extends LiveHomeRegression.Transition {
  public int getRootCount(){return 1;}public Root getRoot(int index){return new Root();}
 }
 static LiveSwapGeometry.Task geometry(int id,Object token){return new LiveSwapGeometry.Task(id,token,1200,2608,
     new LiveSwapGeometry.Rect(0,0,900,1956),new LiveSwapGeometry.Rect(900,0,300,652));}
 static LiveTaskSwapDriver.Flip flip(LiveTaskSwapDriver.Peer peer){
  return new LiveTaskSwapDriver.Flip(geometry(11,"A"),geometry(12,"B"),0,999,
      new Rect(0,0,1200,2608),new Surface(900),new LiveSwapZoom.Zoom(1,.75f,0,0),
      new LiveSwapZoom.Zoom(2,.75f,300,0),(zoom,done)->{},(zoom,restoring,done)->{},
      new LiveTaskSwapDriver.Peer[]{peer},new Object[0],null);
 }
 static class PeerLease {
  final Organizer organizer=new Organizer();final Controller controller=new Controller();
  final Info info=new Info(13,"P",6);final LiveTaskSwapDriver.Flip flip;
  final Object[] peerLeashes=new Object[1],peerParents=new Object[1];
  PeerLease(int user){info.userId=user;controller.mTaskToken="P";
   organizer.mTasks.put(13,new Appeared(info));organizer.mTaskListeners.put(13,controller);
   flip=CloneUsersRegression.flip(new LiveTaskSwapDriver.Peer(geometry(13,"P"),controller,user));}
  __PEER__
 }
 static class CollectScene {
  Object homeSurface,homeTransitionRoot;
  final Object homeToken="HOME",collectedToken="A",hostRoot=new Surface(900);
  final int homeUserId=0;int rootX,rootY;
  final LiveCollectRegression.Case collected=new LiveCollectRegression.Case(999);
  final MiStepLiveCollectTask task=collected.task;
  final Transition transition=new Transition();final Info app=new Info(11,"A",6),home=new Info(90,"HOME",1);
  CollectScene(){app.userId=999;home.type=2;
   transition.changes.add(new LiveHomeRegression.Change(app,new Surface(11)));
   transition.changes.add(new LiveHomeRegression.Change(home,new Surface(90)));}
  __RESOLVE__
 }
 public static void main(String[] args){
  for(int user:new int[]{0,999}){PeerLease p=new PeerLease(user);check(p.peer(0)!=null,"mixed profile peer rejected");
   check(p.flip.firstUserId==0&&p.flip.secondUserId==999&&p.flip.peers[0].userId==user,"request collapsed participant users");
   p.info.userId=user==0?999:0;reject(()->p.peer(0),"peer user drift admitted");}
  PeerLease p=new PeerLease(999);p.info.taskId=14;PeerLease reused=p;reject(()->reused.peer(0),"peer task id reuse admitted");
  p=new PeerLease(999);p.info.token="new";PeerLease token=p;reject(()->token.peer(0),"peer token reuse admitted");
  p=new PeerLease(999);p.info.displayId=1;PeerLease display=p;reject(()->display.peer(0),"peer other display admitted");
  p=new PeerLease(999);p.organizer.mTaskListeners.put(13,new Controller());PeerLease owner=p;reject(()->owner.peer(0),"peer organizer owner drift admitted");
  p=new PeerLease(999);p.peer(0);((Appeared)p.organizer.mTasks.get(13)).leash=new Surface(666);PeerLease layer=p;reject(()->layer.peer(0),"peer layer drift admitted");
  CollectScene c=new CollectScene();check(c.resolve(c.transition).surfaces.length==1,"clone999/Home0 collection not resolved");
  check(c.homeSurface!=null&&c.homeTransitionRoot!=null,"native Home surfaces not retained");
  for(int kind=0;kind<7;kind++){
   CollectScene bad=new CollectScene();
   if(kind==0)bad.home.userId=999;if(kind==1)bad.home.displayId=1;if(kind==2)bad.home.type=1;
   if(kind==3)bad.home.mode=6;if(kind==4)bad.home.parent=true;
   if(kind==5)bad.transition.changes.add(bad.transition.changes.get(1));
   if(kind==6)bad.app.userId=0;
   reject(()->bad.resolve(bad.transition),"collection participant identity drift admitted "+kind);
  }
  System.out.println("actual clone collect/peer/request checks="+checks+" passed");
 }
}'''.replace('__PEER__', peer).replace('__RESOLVE__', resolve.replace('@Override ', ''))
}
rect = out/'android/graphics/Rect.java'
paths = [str(rect)]
for name, text in files.items():
    path=out/name
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(text,encoding='utf-8')
    paths.append(str(path))
paths += [str(src/'LiveSwapZoom.java'),str(src/'LiveTaskSwapDriver.java')]
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-cp',str(out),'-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.CloneUsersRegression'],check=True)
