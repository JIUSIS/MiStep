import os
"""Use existing deterministic Android fixtures to test the actual outward animation."""
from pathlib import Path
import ast,subprocess,sys
root=Path(__file__).resolve().parents[2];out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
tree=ast.parse((root/'tools/cell-motion-jvm/run.py').read_text(encoding='utf-8'))
fixtures=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='files' for t in n.targets))
files={k:v for k,v in fixtures.items() if k.startswith('android/')}
files['com/jiusis/mistep/SideDismissMotionRegression.java']=r'''
package com.jiusis.mistep;
import android.view.View;import android.animation.ValueAnimator;import java.util.*;
public class SideDismissMotionRegression {
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 public static void main(String[]args){
  for(float direction:new float[]{-1,1}){
   View root=new View(),view=new View();root.children.add(view);view.x=815;view.tx=7;
   List<Boolean> done=new ArrayList<>();var motion=new MiStepSideDismiss(root,view,280*direction,()->true,done::add,s->{});
   motion.start();check(ValueAnimator.latest.duration==220,"bounded slide duration");
   ValueAnimator.latest.frame(.5f);root.draw();
   check(view.tx==7+140*direction,"real View travels outward in both layouts");
   check(view.surfaceX==view.holeX&&view.surfaceX==815+7+140*direction,"actual SurfaceView hole follows real surface");
   ValueAnimator.latest.end();check(done.equals(List.of(true)),"one completed slide callback");
   check(view.tx==7+280*direction,"offscreen transform held until handback ack");
   motion.close();check(view.tx==7&&root.listeners.isEmpty(),"ack restores owned transform and releases listener");
   ValueAnimator.latest.end();check(done.size()==1,"late animator end cannot complete twice");
  }
  View trackingRoot=new View(),trackingView=new View();List<Boolean> trackingDone=new ArrayList<>();
  var drag=new MiStepSideDismiss(trackingRoot,trackingView,280,()->true,trackingDone::add,s->{});
  drag.follow(35);trackingRoot.draw();check(trackingView.tx==35&&trackingView.holeX==trackingView.surfaceX,"MOVE instantly moves real surface and hole");
  drag.follow(80);check(trackingView.tx==80&&trackingDone.isEmpty(),"hold has no animation or handback");
  drag.settle(false);ValueAnimator.latest.frame(.5f);check(trackingView.tx==40,"rebound starts at finger release position");
  ValueAnimator.latest.end();check(trackingView.tx==0&&trackingDone.equals(List.of(false)),"rebound retains task and resets transform");drag.close();
  trackingDone=new ArrayList<>();drag=new MiStepSideDismiss(trackingRoot,trackingView,280,()->true,trackingDone::add,s->{});
  drag.follow(100);drag.settle(true);ValueAnimator.latest.frame(.5f);check(trackingView.tx==190,"finish continues from finger without jumping to zero");
  ValueAnimator.latest.end();check(trackingView.tx==280&&trackingDone.equals(List.of(true)),"finish waits for native acknowledgement offscreen");drag.close();
  View root=new View(),view=new View();boolean[] current={true};List<Boolean> done=new ArrayList<>();
  var m=new MiStepSideDismiss(root,view,280,()->current[0],done::add,s->{});m.start();ValueAnimator.latest.frame(.4f);current[0]=false;ValueAnimator.latest.frame(.6f);
  check(done.equals(List.of(false))&&view.tx==0,"replacement binding cancels and restores");m.close();
  root=new View();view=new View();done=new ArrayList<>();m=new MiStepSideDismiss(root,view,-280,()->true,done::add,s->{});m.start();ValueAnimator.latest.frame(.5f);root.detach();
  check(done.equals(List.of(false))&&view.tx==0&&root.listeners.isEmpty(),"detach cannot retain shifted task or listener");
  root=new View();view=new View();m=new MiStepSideDismiss(root,view,280,()->true,b->{},s->{});m.start();ValueAnimator.latest.frame(.5f);view.tx=99;m.close();
  check(view.tx==99,"external replacement transform preserved");
  root=new View();root.attached=false;view=new View();done=new ArrayList<>();m=new MiStepSideDismiss(root,view,280,()->true,done::add,s->{});m.start();
  check(done.equals(List.of(false))&&view.tx==0,"detached root never starts handback animation");m.close();
  System.out.println("actual side-dismiss motion checks="+checks+" passed");
 }
}
'''
for n,t in files.items():
 p=out/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(t,encoding='utf-8')
jdk=Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out)]+[str(out/n) for n in files]+[str(root/'module/src/main/java/com/jiusis/mistep/MiStepSideDismiss.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.SideDismissMotionRegression'],check=True)
