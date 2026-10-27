import os
"""Verify extracted Host recovery of an A task not yet bound to any visible slot."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[2]
source=(root/'module/src/main/java/com/jianguo/mistep/MiStepHost.java').read_text(encoding='utf-8-sig')
out=root/'build/tests/standalone';out.mkdir(parents=True,exist_ok=True)
def method(signature):
 start=source.index(signature);brace=source.index('{',start);depth=1;end=brace+1
 while depth:
  if source[end]=='{':depth+=1
  elif source[end]=='}':depth-=1
  end+=1
 return source[start:end]
methods='\n'.join(method(s) for s in ['    private void returnUnboundOwnedCandidates(', '    private void returnUnboundOwnedCandidate('])
body=r'''
package com.jianguo.mistep;
import java.util.*;
public class OwnedRecoveryRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class OwnedTask {int id=10,userId;Object token=new Object(),recoveryController;boolean recoveryCandidate=true;}
 static class LiveTask {int id;Object token;LiveTask(int i,Object t,String p){id=i;token=t;}boolean matches(Object o){return o instanceof Info&&((Info)o).taskId==id&&((Info)o).token==token;}}
 static class Slot {int index,taskId;Object controller,taskToken;boolean occupied;Slot(int i){index=i;}}
 static class Promotion {Slot slot;boolean background,candidateRecovery,handoffComplete;Promotion(Slot s,Slot[]a){slot=s;}}
 static class Info {int taskId=10,userId,displayId,mode=6;Object token;Info(Object t){token=t;}}
 static class Appeared {Info info;Appeared(Info i){info=i;}public Info getTaskInfo(){return info;}}
 static class Organizer {Object mLock=new Object();android.util.SparseArray<Appeared>mTasks=new android.util.SparseArray<>();}
 static class Controller {Organizer mTaskOrganizer=new Organizer();Object mPendingInfo,mTaskToken;}
 static class XposedHelpers {
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static int getIntField(Object o,String n){return (Integer)getObjectField(o,n);}
  static Object callMethod(Object o,String n,Object...a){return ((Appeared)o).getTaskInfo();}
 }
 static class Queue {ArrayDeque<Runnable>q=new ArrayDeque<>();void post(Runnable r){q.add(r);}void postDelayed(Runnable r,long d){q.add(r);}void flush(){int cap=30;while(!q.isEmpty()&&cap-->0)q.remove().run();}}
 static final int ACTIVE=0,SUSPENDING=1;
 int scene=SUSPENDING;long sceneEpoch=4;boolean screenOn=true,keyguardLocked,noShell;
 Map<Object,OwnedTask>ownedTasks=new LinkedHashMap<>();Slot[]slots={new Slot(0),new Slot(1),new Slot(2),new Slot(3)};
 Queue main=new Queue(),shell=new Queue();Info core;int wcts,handoffs,completed,zoomWrites;float workspaceAlpha;boolean refuseWct,neverSettle;
 OwnedTask candidate;Controller controller=new Controller();
 OwnedRecoveryRegression(){candidate=new OwnedTask();candidate.recoveryController=controller;core=new Info(candidate.token);controller.mTaskOrganizer.mTasks.put(candidate.id,new Appeared(core));ownedTasks.put(candidate.token,candidate);}
 boolean executeOnShell(Runnable r){if(noShell)return false;shell.post(r);return true;}
 Object rootInfo(LiveTask i){return core;}
 boolean ordinaryRoot(Object o,int mode){return o instanceof Info&&((Info)o).displayId==0&&((Info)o).mode==mode;}
 Object fullscreenExitTransaction(Promotion p){check(p.background&&p.candidateRecovery,"candidate uses existing background full-screen policy");return p;}
 boolean applyWct(String name,Object wct){wcts++;if(refuseWct)return false;if(!neverSettle)core.mode=1;return true;}
 void handoffPromotedTask(Promotion p){handoffs++;check(core.mode==1&&p.slot.taskToken==candidate.token,"surface handback only after exact A mode1");p.handoffComplete=true;}
 void log(String s){}void writeZoom(boolean b){zoomWrites++;}void setZoomState(boolean b){}void setWorkspaceAlpha(float a){workspaceAlpha=a;}
 void pump(){for(int i=0;i<12;i++){shell.flush();main.flush();}}
 __METHODS__
 public static void main(String[]args){
  var h=new OwnedRecoveryRegression();h.returnUnboundOwnedCandidates(4,()->h.completed++);h.pump();
  check(h.wcts==1&&h.handoffs==1&&h.completed==1&&h.ownedTasks.isEmpty(),"unbound A6 returns A1 before normal slot parking");
  var waiting=new OwnedRecoveryRegression();waiting.neverSettle=true;waiting.returnUnboundOwnedCandidates(4,()->waiting.completed++);waiting.pump();
  check(waiting.completed==0&&waiting.ownedTasks.containsKey(waiting.candidate.token)&&waiting.scene==ACTIVE,"unsettled A remains in ledger; never falsely suspended");
  check(waiting.workspaceAlpha==1f&&waiting.zoomWrites==1,"failed visible exit restores real workspace alpha and zoom");
  var locked=new OwnedRecoveryRegression();locked.keyguardLocked=true;locked.neverSettle=true;locked.returnUnboundOwnedCandidates(4,()->locked.completed++);locked.pump();
  check(locked.workspaceAlpha==0f&&locked.zoomWrites==0&&locked.ownedTasks.size()==1,"failed locked exit does not reveal workspace");
  var unavailable=new OwnedRecoveryRegression();unavailable.noShell=true;unavailable.returnUnboundOwnedCandidates(4,()->unavailable.completed++);unavailable.pump();
  check(unavailable.scene==ACTIVE&&unavailable.workspaceAlpha==1f&&unavailable.zoomWrites==1&&unavailable.completed==0,"unavailable shell restores visible workspace without false handoff");
  var off=new OwnedRecoveryRegression();off.screenOn=false;off.noShell=true;off.returnUnboundOwnedCandidates(4,()->off.completed++);off.pump();
  check(off.workspaceAlpha==0f&&off.zoomWrites==0&&off.ownedTasks.size()==1,"unavailable shell while screen off does not reveal workspace");
  var slot=new OwnedRecoveryRegression();slot.slots[1].occupied=true;slot.slots[1].taskToken=slot.candidate.token;slot.returnUnboundOwnedCandidates(4,()->slot.completed++);slot.pump();
  check(slot.completed==1&&slot.wcts==0&&slot.ownedTasks.size()==1,"A already in slot stays on ordinary slot return path");
  var changed=new OwnedRecoveryRegression();changed.core.userId=10;changed.returnUnboundOwnedCandidates(4,()->changed.completed++);changed.pump();
  check(changed.wcts==0&&changed.handoffs==0&&changed.ownedTasks.size()==1&&changed.completed==0,"different user cannot be mutated");
  var stale=new OwnedRecoveryRegression();stale.returnUnboundOwnedCandidates(4,()->stale.completed++);stale.sceneEpoch=5;stale.pump();
  check(stale.wcts==0&&stale.handoffs==0&&stale.completed==0&&stale.ownedTasks.size()==1,"late Shell work cannot mutate or finish a changed scene");
  var absent=new OwnedRecoveryRegression();absent.core=null;absent.controller.mTaskOrganizer.mTasks=new android.util.SparseArray<>();absent.returnUnboundOwnedCandidates(4,()->absent.completed++);absent.pump();
  check(absent.completed==1&&absent.ownedTasks.isEmpty()&&absent.wcts==0,"gone from roots and organizer plus controller may retire exact candidate");
  var held=new OwnedRecoveryRegression();held.core=null;held.controller.mTaskOrganizer.mTasks=new android.util.SparseArray<>();held.controller.mTaskToken=held.candidate.token;held.returnUnboundOwnedCandidates(4,()->held.completed++);held.pump();
  check(held.completed==0&&held.ownedTasks.size()==1,"root absence cannot discard controller-held candidate");
  System.out.println("actual Host owned-candidate recovery checks="+checks+" passed");
 }
}
'''.replace('__METHODS__',methods)
f=out/'OwnedRecoveryRegression.java';f.write_text(body,encoding='utf8')
sparse=out/'android/util/SparseArray.java';sparse.parent.mkdir(parents=True,exist_ok=True)
sparse.write_text('package android.util; public class SparseArray<T>{java.util.Map<Integer,T>m=new java.util.HashMap<>();public T get(int i){return m.get(i);}public void put(int i,T v){m.put(i,v);}}',encoding='utf8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(sparse),str(f)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jianguo.mistep.OwnedRecoveryRegression'],check=True)
