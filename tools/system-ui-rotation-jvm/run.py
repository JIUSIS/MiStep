import os
"""Exercise the actual native-token owner with independent WM fakes and real role changes."""
from pathlib import Path
import ast, subprocess, sys
ROOT=Path(__file__).resolve().parents[2]
OUT=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'build/tests/system-ui-rotation-jvm'
JDK=Path(os.environ["MISTEP_JAVA_BIN"])
tree=ast.parse((ROOT/'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
files=next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='FILES' for t in n.targets))
fake=OUT/'de/robv/android/xposed/XposedHelpers.java';fake.parent.mkdir(parents=True,exist_ok=True)
fake.write_text(files['de/robv/android/xposed/XposedHelpers.java'],encoding='utf8')
test=OUT/'com/jiusis/mistep/SystemUiRotationRegression.java';test.parent.mkdir(parents=True,exist_ok=True)
test.write_text(r'''package com.jiusis.mistep;
public class SystemUiRotationRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static class State {Object mRotatedOverrideConfiguration=new Object();}
 public static class Node {
  Object display,mFixedRotationTransformState,mLastMagnificationSpec;Node parent;Controller mTransitionController=new Controller();
  public int applies,finishes,leashes,zooms;boolean home,multi;Object info=new Object(),frames=new Object();
  public Node(Object d,Node p){display=d;parent=p;}
  public Node getParent(){return parent;}public Object getDisplayContent(){return display;}
  public boolean isActivityTypeHome(){return home;}public boolean inMultiWindowMode(){return multi;}
  public Object getFixedRotationTransformDisplayInfo(){return info;}
  public Object getFixedRotationTransformDisplayFrames(){return frames;}
  public void applyFixedRotationTransform(Object i,Object f,Object c){applies++;mFixedRotationTransformState=new State();}
  public void finishFixedRotationTransform(){finishes++;mFixedRotationTransformState=null;}
  public Object getPendingTransaction(){return this;}public Object getOrCreateFixedRotationLeash(Object t){leashes++;return this;}
  public boolean shouldMagnify(){return true;}
  public void applyMagnificationSpec(Object t,Object s){zooms++;mLastMagnificationSpec=s;}
 }
 static class Controller {boolean shell;public boolean useShellTransitionsRotation(){return shell;}}
 static class Display {Window target;public Window getImeInputTarget(){return target;}}
 static class Task {int mode=1;public int getWindowingMode(){return mode;}}
 static class Attrs {int type;String packageName,title;Attrs(int t,String p,String n){type=t;packageName=p;title=n;}public CharSequence getTitle(){return title;}}
 static class Window {
  Attrs mAttrs;Node mToken;Task task=new Task();boolean mIsImWindow;Window mParentWindow;
  Window(Node n,int t,String p,String title){mToken=n;mAttrs=new Attrs(t,p,title);}
  public Object getDisplayContent(){return mToken.display;}public Window getWindowState(){return this;}public Task getTask(){return task;}
 }
 static Window window(Display d,int type,String pkg,String title){return new Window(new Node(d,new Node(d,null)),type,pkg,title);}
 public static void main(String[]args){
  Display d=new Display();Node main=new Node(d,new Node(d,null));main.mFixedRotationTransformState=new State();
  MiStepSystemUiRotation owner=new MiStepSystemUiRotation();Window status=window(d,2012,"keyboard.vendor","InputMethodDialog");
  owner.update(status,d,main,s->{});check(status.mToken.applies==1,"main system bar did not receive rotated configuration");
  check(status.mToken.mFixedRotationTransformState!=main.mFixedRotationTransformState,"system bar shares app finish lifecycle");
  check(status.mToken.leashes==0,"legacy native token rotation received a second translation leash");
  Window shellBar=window(d,2012,"keyboard.vendor","InputMethodDialog");shellBar.mToken.mTransitionController.shell=true;
  owner.update(shellBar,d,main,s->{});check(shellBar.mToken.leashes==1,"Shell rotation did not acquire its native position leash");
  owner.update(status,d,main,s->{});check(status.mToken.applies==1,"same native state unnecessarily restarted");
  Window ime=window(d,2011,"keyboard.vendor","InputMethod");d.target=new Window(main,1,"main","main");
  owner.update(ime,d,main,s->{});check(ime.mToken.applies==1,"real IME direction was not configured");
  Window imeChild=window(d,1000,"keyboard.vendor","PopupWindow");
  imeChild.mIsImWindow=true;imeChild.mParentWindow=ime;imeChild.mToken=new Node(d,new Node(d,null));
  owner.update(imeChild,d,main,s->{});check(imeChild.mToken.applies==1,"visible IME child panel did not receive rotation");
  d.target.task.mode=5;owner.update(ime,d,main,s->{});check(ime.mToken.finishes==1,"native floating keyboard retained main rotation");
  d.target.task.mode=1;owner.update(ime,d,main,s->{});check(ime.mToken.applies==2,"return to main failed to reconfigure IME");
  Window other=window(d,2000,"foreign","StatusBar");owner.update(other,d,main,s->{});check(other.mToken.applies==0,"foreign overlay was changed");
  Window foreignDisplay=window(new Display(),2011,"keyboard.vendor","InputMethod");owner.update(foreignDisplay,d,main,s->{});check(foreignDisplay.mToken.applies==0,"foreign display was changed");
  Window nativeRotated=window(d,2011,"keyboard.vendor","InputMethod");nativeRotated.mToken.mFixedRotationTransformState=new State();
  owner.update(nativeRotated,d,main,s->{});check(nativeRotated.mToken.applies==0,"native rotation owner was replaced");
  Object original=status.mToken.mFixedRotationTransformState;main.mFixedRotationTransformState=new State();owner.update(status,d,main,s->{});
  check(status.mToken.applies==2&&status.mToken.finishes==1,"main token handoff left old system rotation");check(main.finishes==0,"system UI cleanup finished the app");
  status.mToken.mFixedRotationTransformState=new State();owner.clear(s->{});check(status.mToken.finishes==1,"exit finished a replacement native state");
  check(ime.mToken.finishes==2,"exit did not release owned keyboard rotation");
  Window side=window(d,2011,"keyboard.vendor","InputMethod");main.multi=true;owner.update(side,d,main,s->{});check(side.mToken.applies==0,"side app supplied system orientation");main.multi=false;
  main.home=true;owner.update(side,d,main,s->{});check(side.mToken.applies==0,"Home inherited previous app orientation");main.home=false;
  Window removed=window(d,2011,"keyboard.vendor","InputMethod");removed.mToken.parent=null;owner.update(removed,d,main,s->{});check(removed.mToken.applies==0,"detached token acquired a transform");
  Window[] nativePanels={window(d,2000,"com.android.systemui","StatusBar"),
    window(d,2019,"com.android.systemui","NavigationBar0"),
    window(d,2040,"com.android.systemui","NotificationShade"),
    window(d,2017,"com.android.systemui","NotificationModalWindowManager"),
    window(d,2017,"com.android.systemui","FlaresView"),
    window(d,2017,"com.android.systemui","OtherSystemPanel"),
    window(d,2020,"com.android.systemui","VolumeDialog"),
    window(d,2024,"com.android.systemui","NavigationBarPanel"),
    window(d,2040,"foreign","NotificationShade"),
    window(d,2038,"com.android.systemui","MiStepHost"),
    window(d,2036,"com.android.systemui","Screenshot")};
  for(int j=0;j<nativePanels.length;j++){Window panel=nativePanels[j];owner.update(panel,d,main,s->{});
    check(panel.mToken.applies==(j<8?1:0),"main native panel whitelist: "+panel.mAttrs.title);}
  main.home=true;owner.update(ime,d,main,s->{});main.home=false;owner.clear(s->{});
  for(int j=0;j<nativePanels.length;j++)check(nativePanels[j].mToken.finishes==(j<8?1:0),"native panel owned cleanup: "+nativePanels[j].mAttrs.title);
  Window privacy=window(d,2017,"com.android.systemui","FlaresView");Object spec=new Object(),tx=new Object();
  check(MiStepSystemUiRotation.magnifyPrivacy(privacy,d,spec,tx),"late permission window did not acquire native magnification");
  check(privacy.mToken.mLastMagnificationSpec==spec,"native input/exit magnification metadata was not recorded");
  privacy.mToken.parent.mLastMagnificationSpec=spec;int zooms=privacy.mToken.zooms;
  check(!MiStepSystemUiRotation.magnifyPrivacy(privacy,d,spec,tx)&&privacy.mToken.zooms==zooms,"permission window was magnified twice");
  privacy.mToken.parent.mLastMagnificationSpec=null;
  com.android.server.wm.DisplayArea.Tokens area=new com.android.server.wm.DisplayArea.Tokens(d);privacy.mToken.parent=area;
  check(MiStepSystemUiRotation.magnifyPrivacy(privacy,d,spec,tx)&&area.zooms==1,"magnification was placed inside the rotation leash");
  check(!MiStepSystemUiRotation.magnifyPrivacy(other,d,spec,tx),"foreign overlay admitted as permission UI");
  check(!MiStepSystemUiRotation.magnifyPrivacy(privacy,new Display(),spec,tx),"wrong display zoom admitted");
  check(!MiStepSystemUiRotation.magnifyPrivacy(privacy,d,null,tx),"inactive zoom admitted");
  check(!MiStepSystemUiRotation.privacyWindow(2017,"foreign","FlaresView"),"title spoof admitted");
  check(!MiStepSystemUiRotation.privacyWindow(2038,"com.android.systemui","FlaresView"),"wrong type admitted");
  System.out.println("actual main system UI rotation/permission ownership checks="+checks+" passed");
 }
}''',encoding='utf8')
area=OUT/'com/android/server/wm/DisplayArea.java';area.parent.mkdir(parents=True,exist_ok=True)
area.write_text('''package com.android.server.wm;public class DisplayArea {public static class Tokens extends com.jiusis.mistep.SystemUiRotationRegression.Node {public Tokens(Object display){super(display,null);}}}''',encoding='utf8')
source=ROOT/'module/src/main/java/com/jiusis/mistep/MiStepSystemUiRotation.java'
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),str(fake),str(test),str(area),str(source)],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.SystemUiRotationRegression'],check=True)
