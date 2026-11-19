"""Exercise production TOP local viewport and exact active-task rotation registry."""
import ast, os, subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'build/tests/phone-rotation-jvm'
JDK = Path(os.environ['MISTEP_JAVA_BIN'])
tree = ast.parse((ROOT/'tools/live-swap-jvm/run.py').read_text(encoding='utf-8-sig'))
shared = next(ast.literal_eval(n.value) for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='FILES' for t in n.targets))
helper = shared['de/robv/android/xposed/XposedHelpers.java'][:-1] + r'''
 public static Object callStaticMethod(Class<?> c,String n,Object...a){try{for(var m:c.getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length){m.setAccessible(true);return m.invoke(null,a);}throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}
 public static Class<?> findClass(String n,ClassLoader l){try{return Class.forName(n,true,l);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object getStaticObjectField(Class<?> c,String n){try{return c.getField(n).get(null);}catch(Exception e){throw new RuntimeException(e);}}
 public static void setObjectField(Object o,String n,Object v){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}catch(Exception e){throw new RuntimeException(e);}}
 public static void setIntField(Object o,String n,int v){setObjectField(o,n,v);}
 public static Object newInstance(Class<?> c,Object...a){try{for(var k:c.getDeclaredConstructors())if(k.getParameterCount()==a.length){k.setAccessible(true);return k.newInstance(a);}throw new NoSuchMethodException();}catch(Exception e){throw new RuntimeException(e);}}
}'''
FILES = {
 'de/robv/android/xposed/XposedHelpers.java': helper,
 'android/content/pm/ActivityInfo.java': 'package android.content.pm;public class ActivityInfo{public static final int CONFIG_ORIENTATION=128,CONFIG_SCREEN_LAYOUT=256,CONFIG_SCREEN_SIZE=1024,CONFIG_SMALLEST_SCREEN_SIZE=2048,CONFIG_DENSITY=4096;}',
 'android/graphics/Rect.java': (ROOT/'tools/test-stubs/android/graphics/Rect.java').read_text().replace('public int width()', 'public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);} public int width()'),
 'android/content/res/Configuration.java': r'''package android.content.res;import android.graphics.Rect;
public class Configuration {
 public static final int ORIENTATION_PORTRAIT=1;
 public int seq=8,orientation=2,densityDpi=242,screenWidthDp=1983,screenHeightDp=1141,smallestScreenWidthDp=1141,screenLayout=17;
 public WC windowConfiguration=new WC();public Configuration(){}
 public Configuration(Configuration c){seq=c.seq;orientation=c.orientation;densityDpi=c.densityDpi;screenWidthDp=c.screenWidthDp;screenHeightDp=c.screenHeightDp;smallestScreenWidthDp=c.smallestScreenWidthDp;screenLayout=c.screenLayout;windowConfiguration=new WC(c.windowConfiguration);}
 public static int resetScreenLayout(int v){return 0;}public static int reduceScreenLayout(int s,int h,int w){return h>w?7:8;}
 public static class WC{public Rect bounds=new Rect(0,0,3000,1725),app=new Rect(bounds),max=new Rect(bounds);public int rotation,displayRotation,alwaysOnTop;
 WC(){}WC(WC w){bounds=new Rect(w.bounds);app=new Rect(w.app);max=new Rect(w.max);rotation=w.rotation;displayRotation=w.displayRotation;alwaysOnTop=w.alwaysOnTop;}
 public void unsetAlwaysOnTop(){alwaysOnTop=0;}
 public void setBounds(Rect r){bounds=new Rect(r);}public Rect getBounds(){return bounds;}
 public void setAppBounds(Rect r){app=new Rect(r);}public Rect getAppBounds(){return app;}
 public void setMaxBounds(Rect r){max=new Rect(r);}public void setRotation(int r){rotation=r;}public void setDisplayRotation(int r){displayRotation=r;}public int getDisplayRotation(){return displayRotation;}}
}''',
 'android/view/InsetsState.java': 'package android.view;public class InsetsState {}',
 'android/view/DisplayCutout.java': 'package android.view;public class DisplayCutout{public static final Object NO_CUTOUT=new Object();}',
 'android/view/RoundedCorners.java': 'package android.view;public class RoundedCorners{public static final Object NO_ROUNDED_CORNERS=new Object();}',
 'android/view/DisplayShape.java': 'package android.view;public class DisplayShape{public static final Object NONE=new Object();}',
 'android/view/PrivacyIndicatorBounds.java': 'package android.view;public class PrivacyIndicatorBounds{public PrivacyIndicatorBounds(android.graphics.Rect[] r,int rotation){}}',
 'com/android/server/wm/DisplayFrames.java': 'package com.android.server.wm;public class DisplayFrames{public Object info;public DisplayFrames(Object s,Object i,Object c,Object r,Object p,Object d){info=i;}}',
 'com/jiusis/mistep/PhoneRotationTest.java': r'''package com.jiusis.mistep;
import android.content.res.Configuration;import android.graphics.Rect;
public class PhoneRotationTest {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 public static class Info {public int rotation,logicalWidth=1080,logicalHeight=2460,appWidth=1080,appHeight=2460,logicalDensityDpi=440;public Object displayCutout=new Object(),roundedCorners=new Object(),displayShape=new Object();
 public Info(){}public Info(Info i){rotation=i.rotation;logicalWidth=i.logicalWidth;logicalHeight=i.logicalHeight;appWidth=i.appWidth;appHeight=i.appHeight;logicalDensityDpi=i.logicalDensityDpi;}}
 static class Display {Info info=new Info();Info getDisplayInfo(){return info;}}
 static class Controller {boolean shell;boolean useShellTransitionsRotation(){return shell;}}
 static class Task {Rect bounds=new Rect(0,0,3000,1725);Configuration config=new Configuration();int mode=6;boolean translucent=true;Task(){config.windowConfiguration.alwaysOnTop=1;}Rect getBounds(){return bounds;}Configuration getConfiguration(){return config;}int getWindowingMode(){return mode;}boolean isForceTranslucent(){return translucent;}}
 static class Activity {Object mFixedRotationTransformState;Controller mTransitionController=new Controller();Task task=new Task();Configuration resolved=new Configuration(),applied;Info info;int starts,leashes;boolean attached=true;
 Object getParent(){return attached?task:null;}Configuration getResolvedOverrideConfiguration(){return resolved;}Configuration getConfiguration(){return resolved;}boolean hasFixedRotationTransform(){return mFixedRotationTransformState!=null;}
 Rect getFixedRotationTransformDisplayBounds(){return info==null?null:new Rect(0,0,info.logicalWidth,info.logicalHeight);}
 void applyFixedRotationTransform(Info i,Object f,Configuration c){starts++;info=i;applied=c;resolved=new Configuration(c);mFixedRotationTransformState=new Object();}
 Object getPendingTransaction(){return this;}Object getOrCreateFixedRotationLeash(Object t){leashes++;return this;}
 }
 // OS4 Task.getAdjustedChildPosition: a new ordinary Activity is inserted below
 // an always-on-top Activity. ConfigurationContainer clears the parent's flag
 // before merging token overrides; a copied rotation override must clear it too.
 static int nativeNewPagePosition(Configuration rotated){
  Configuration root=new Configuration(),next=new Configuration();
  Configuration[] children={root,rotated};
  int min=0,max=children.length-1;
  if(next.windowConfiguration.alwaysOnTop!=1)
   while(max>min&&children[max].windowConfiguration.alwaysOnTop==1)max--;
  return max+1;
 }
 public static void main(String[]args){
  for(boolean shell:new boolean[]{false,true})for(int r:new int[]{1,3}){
   Display d=new Display();Activity a=new Activity();a.mTransitionController.shell=shell;Rect carrier=new Rect(a.task.bounds);
   check(MiStepTopRotation.phone(d),"phone capability");MiStepTopRotation.apply(d,a,a.task,r);
   check(a.starts==1&&a.leashes==(shell?1:0),"native Shell/legacy path");
   check(nativeNewPagePosition(a.applied)==2,"new same-task page must be above the rotated page and able to resume");
   check(a.task.config.windowConfiguration.alwaysOnTop==1&&a.applied.windowConfiguration.alwaysOnTop==0,"only the Task keeps its own always-on-top policy");
   check(a.info.rotation==r&&a.info.logicalWidth==1725&&a.info.logicalHeight==3000,"local viewport not full display");
   check(a.applied.orientation==1&&a.applied.seq==0&&a.applied.windowConfiguration.rotation==r&&a.applied.windowConfiguration.displayRotation==r,"config direction");
   Object owned=a.mFixedRotationTransformState;Configuration previous=new Configuration(a.applied);previous.windowConfiguration.setDisplayRotation(0);
   check(MiStepTopRotation.shouldKeepLiveSurface(a,0x1580,previous,owned,false),"owned TOP rotation keeps the real Activity surface");
   check(!MiStepTopRotation.shouldKeepLiveSurface(a,0x1584,previous,owned,false),"simultaneous locale change still relaunches");
   check(!MiStepTopRotation.shouldKeepLiveSurface(a,0x1780,previous,owned,false),"simultaneous UI mode change still relaunches");
   previous.windowConfiguration.setDisplayRotation(r);check(!MiStepTopRotation.shouldKeepLiveSurface(a,0x1580,previous,owned,false),"unrelated relaunch with unchanged rotation remains native");
   a.resolved=new Configuration(a.task.config);a.mFixedRotationTransformState=null;previous=new Configuration(a.applied);
   check(MiStepTopRotation.shouldKeepLiveSurface(a,0x1580,previous,owned,true),"owned TOP release keeps the real surface until parent config dispatch");
   check(!MiStepTopRotation.shouldKeepLiveSurface(a,0x1580,previous,owned,false),"release guard requires its native finish scope");
   a.mFixedRotationTransformState=new Object();check(!MiStepTopRotation.shouldKeepLiveSurface(a,0x1580,previous,owned,true),"replaced native transform is never suppressed");
   a.mFixedRotationTransformState=owned;a.resolved=new Configuration(a.applied);
   check(a.applied.densityDpi==242&&a.info.logicalDensityDpi==242,"portrait inherits the landscape task density");
   check(a.applied.screenWidthDp==Math.round(1725*160f/242)&&a.applied.screenHeightDp==Math.round(3000*160f/242),"local dp follows the original task density");
   check(a.applied.windowConfiguration.app.equals(a.applied.windowConfiguration.bounds)&&a.applied.windowConfiguration.max.equals(a.applied.windowConfiguration.bounds),"app max bounds");
   check(a.task.bounds.equals(carrier)&&a.task.config.orientation==2&&a.task.config.densityDpi==242&&d.info.logicalDensityDpi==440&&d.info.rotation==0&&d.info.logicalWidth==1080,"landscape task and physical display density unchanged");
   check(!MiStepTopConfiguration.apply(a,a.task,a.task.config)&&a.resolved.orientation==1&&a.resolved.windowConfiguration.app.height()==3000,"parent cannot overwrite portrait viewport");
   a.resolved=new Configuration(a.task.config);
   check(MiStepTopConfiguration.applyRotated(a,a.task,a.applied)
           &&a.resolved.screenWidthDp==a.applied.screenWidthDp&&a.resolved.screenHeightDp==a.applied.screenHeightDp,
           "stale positive landscape dp values follow the portrait viewport");
   a.resolved=new Configuration(a.applied);a.resolved.screenHeightDp--;
   int insetHeight=a.resolved.screenHeightDp;
   check(MiStepTopConfiguration.applyRotated(a,a.task,a.applied)&&a.resolved.screenHeightDp==insetHeight,
           "already portrait inset-adjusted dp values remain intact");
   Object state=a.mFixedRotationTransformState;boolean rejected=false;try{MiStepTopRotation.apply(d,a,a.task,r);}catch(IllegalStateException e){rejected=true;}
   check(rejected&&a.mFixedRotationTransformState==state&&a.starts==1,"foreign/existing state untouched");
   // Native quarter-turn maps all four portrait corners into the wide carrier.
   for(int x:new int[]{0,1725})for(int y:new int[]{0,3000}){int px=r==1?3000-y:y,py=r==1?x:1725-x;check(px>=0&&px<=3000&&py>=0&&py<=1725,"surface/input corner fit");}
  }
  for(int dpi:new int[]{320,440,480,560}){
   Activity a=new Activity();a.task.config.densityDpi=dpi;Display physical=new Display();
   MiStepTopRotation.apply(physical,a,a.task,1);
   check(a.applied.densityDpi==dpi&&a.info.logicalDensityDpi==dpi,"portrait retains original density on differing native profiles");
   check(a.task.config.densityDpi==dpi&&physical.info.logicalDensityDpi==440,"native density never changed");
   // The production release bridge restores this parent Configuration. Re-entering
   // must use that untouched parent, rather than compounding the last portrait DPI.
   a.mFixedRotationTransformState=null;MiStepTopRotation.apply(physical,a,a.task,3);
   check(a.applied.densityDpi==dpi,"portrait re-entry retains original density");
  }
  Display d=new Display();d.info.logicalWidth=2880;d.info.logicalHeight=1800;d.info.logicalDensityDpi=320;check(!MiStepTopRotation.phone(d),"tablet path preserved");
  for(int r:new int[]{-1,0,1,2,3,4})check(MiStepTopRotation.rotation(r)==(r==1||r==3?r:0),"system direction mapping");
  Activity detached=new Activity();detached.attached=false;boolean refused=false;try{MiStepTopRotation.apply(new Display(),detached,detached.task,1);}catch(IllegalStateException e){refused=true;}check(refused&&detached.starts==0,"detached task rejected");
  System.out.println("PhoneRotation checks="+checks+" passed (production methods; native fakes, no visual acceptance)");
 }
}''',
}
sources=[]
for name,content in FILES.items():
 p=OUT/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');sources.append(str(p))
for name in ['MiStepTopRotation','MiStepTopConfiguration','MiStepSideRoleChange','ZoomChannelState','ZoomCommand','ZoomAnimCommand']:
 sources.append(str(ROOT/f'module/src/main/java/com/jiusis/mistep/{name}.java'))
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),*sources],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.PhoneRotationTest'],check=True)
