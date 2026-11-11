"""Verify native OS2 selection, dependency ownership, dimensions, and exactly-once release."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / "module/src/main/java/com/jiusis/mistep/MiStepLegacyMedia.java"
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
source = src.read_text(encoding="utf-8-sig")


def member(signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


methods = "\n".join(member(s) for s in ("    static Object[] selected(", "    static Object create(",
                                         "    static void measure(", "    static void release(",
                                         "    private static void applyPlaceholderState("))
bench = r'''
import java.util.*;
import java.lang.reflect.*;
public class LegacyMediaTest {
 static final String UI="ui.";
 static final ThreadLocal<Boolean> creating=new ThreadLocal<>();
 static final Set<Object> owned=Collections.newSetFromMap(new IdentityHashMap<>());
 static Object[] arguments;static Class<?> panelType,playerData;static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check="+checks);}
 static void rejects(Runnable r){try{r.run();throw new AssertionError("accepted");}
  catch(IllegalStateException expected){checks++;}}
 static class View {static class MeasureSpec {
  static final int EXACTLY=0x40000000,UNSPECIFIED=0;
  static int makeMeasureSpec(int size,int mode){return size|mode;}}}
 public static class Unit {public static final Unit INSTANCE=new Unit();}
 public static class Seek {public final Object bgExecutor,falsingManager;
  public Seek(Object executor,Object falsing){bgExecutor=executor;falsingManager=falsing;}}
 public static class Manager {public final Map<Object,Object> mediaHostStates=new HashMap<>();
  public final Set<Object> controllers=new HashSet<>();}
 public static class State {public boolean visible;public float expansion,squishFraction;
  public Input input;public void setExpansion(float value){expansion=value;}
  public void setMeasurementInput(Input value){input=value;}}
 public static class Input {final int width,height;public Input(int w,int h){width=w;height=h;}}
 public static class Model {
  public final Object context,configurationController,logger,mainExecutor,mediaFlags,globalSettings;
  public final Manager mediaHostStatesManager;public final Seek seek;public Object sizeChangedListener;
  public int destroyed,sets;static Model last;
  public Model(Object c,Object config,Manager manager,Object log,Seek s,Object exec,Object flags,Object global){
   context=c;configurationController=config;mediaHostStatesManager=manager;logger=log;seek=s;
   mainExecutor=exec;mediaFlags=flags;globalSettings=global;manager.controllers.add(this);last=this;}
  public void onDestroy(){destroyed++;mediaHostStatesManager.controllers.remove(this);}
  public void setCurrentState(int a,int b,float f,boolean immediate,boolean skip){
   check(a==0&&b==0&&f==1f&&immediate&&!skip);sets++;}
 }
 public static class Panel {
  public Model mMediaViewController;public Seek mSeekBarViewModel;public Object mMediaData;
  public final Object[] args;public int destroyed;
  Panel(Object[] a){args=a.clone();mMediaViewController=(Model)a[5];mSeekBarViewModel=(Seek)a[6];}
  public void onDestroy(){destroyed++;mMediaViewController.onDestroy();}
 }
 public static class Handler {public int visibleMediaIndex;}
 public static class Carousel {public Handler mediaCarouselScrollHandler=new Handler();}
 public static class Key {public String key;Key(String s){key=s;}}
 public static class Data {
  public static Map<Object,Object> visibleMediaPlayers=new LinkedHashMap<>();
  public static Map<Object,Object> mediaPlayers=new HashMap<>();
 }
 public static class Root {
  final Object nativeState=new Object();Object measureState,currentState;
  int step;
  public Object calculateViewState(Object input,Object constraints,Object state){check(step++==0);return nativeState;}
  public void setMeasureState(Object state){check(step++==1&&state==nativeState);measureState=state;}
  public void setState(Object state){check(step++==2&&state==nativeState);currentState=state;}
 }
 static class XposedHelpers {
  static boolean failPanel;
  static Class<?> findClass(String s,ClassLoader loader){
   return switch(s){
    case "ui.controller.MediaHostStatesManager"->Manager.class;
    case "ui.view.MediaHost$MediaHostStateHolder"->State.class;
    case "com.android.systemui.util.animation.MeasurementInput"->Input.class;
    case "kotlin.jvm.functions.Function0"->kotlin.jvm.functions.Function0.class;
    case "kotlin.Unit"->Unit.class;
    default->throw new IllegalStateException(s);};
  }
  static Object newInstance(Class<?> c,Object...a){
   if(c==Panel.class){if(failPanel)throw new IllegalStateException("panel failed");return new Panel(a);}
   try{for(Constructor<?> ctor:c.getDeclaredConstructors())if(ctor.getParameterCount()==a.length){
    ctor.setAccessible(true);return ctor.newInstance(a);}
    throw new IllegalStateException("ctor "+c);
   }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
  }
  static Object getObjectField(Object o,String s){try{return o.getClass().getField(s).get(o);}
   catch(Exception e){throw new IllegalStateException(e);}}
  static int getIntField(Object o,String s){return (Integer)getObjectField(o,s);}
  static Object getStaticObjectField(Class<?> c,String s){try{return c.getField(s).get(null);}
   catch(Exception e){throw new IllegalStateException(e);}}
  static void setObjectField(Object o,String s,Object v){try{o.getClass().getField(s).set(o,v);}
   catch(Exception e){throw new IllegalStateException(e);}}
  static void setBooleanField(Object o,String s,boolean v){setObjectField(o,s,v);}
  static void setFloatField(Object o,String s,float v){setObjectField(o,s,v);}
  static Object callMethod(Object o,String s,Object...a){try{
   for(Method m:o.getClass().getMethods())if(m.getName().equals(s)&&m.getParameterCount()==a.length)return m.invoke(o,a);
   throw new IllegalStateException(s);
  }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
 }
 __METHODS__
 public static void main(String[] ignored){
  panelType=Panel.class;playerData=Data.class;check(selected()==null);rejects(()->create());
  Manager nativeManager=new Manager();Seek nativeSeek=new Seek(new Object(),new Object());
  Model nativeView=new Model(new Object(),new Object(),nativeManager,new Object(),nativeSeek,
   new Object(),new Object(),new Object());
  Object[] a=new Object[23];for(int i=0;i<a.length;i++)a[i]=new Object();
  Carousel carousel=new Carousel();a[5]=nativeView;a[6]=nativeSeek;a[9]=carousel;arguments=a;
  Panel original=new Panel(a);original.mMediaData="native-data-a";
  Panel other=new Panel(a);other.mMediaData="native-data-b";
  Key first=new Key("a"),second=new Key("b");
  Data.visibleMediaPlayers.put("native-position-a",first);Data.visibleMediaPlayers.put("native-position-b",second);
  Data.mediaPlayers.put(first,original);Data.mediaPlayers.put(second,other);
  Object[] selection=selected();check(selection[0]==original.mMediaData&&selection[1].equals("a"));
  carousel.mediaCarouselScrollHandler.visibleMediaIndex=1;
  selection=selected();check(selection[0]==other.mMediaData&&selection[1].equals("b"));
  carousel.mediaCarouselScrollHandler.visibleMediaIndex=2;check(selected()==null);
  carousel.mediaCarouselScrollHandler.visibleMediaIndex=-1;check(selected()==null);
  carousel.mediaCarouselScrollHandler.visibleMediaIndex=1;other.mMediaData=null;check(selected()==null);
  other.mMediaData="native-data-b";
  Panel own=(Panel)create();Model model=own.mMediaViewController;Manager manager=model.mediaHostStatesManager;
  check(own!=original&&owned.contains(own)&&model!=nativeView&&own.mSeekBarViewModel!=nativeSeek);
  check(manager!=nativeManager&&manager.controllers.contains(model)&&nativeManager.controllers.size()==1);
  check(model.seek==own.mSeekBarViewModel&&model.seek.bgExecutor==nativeSeek.bgExecutor);
  check(model.seek.falsingManager==nativeSeek.falsingManager&&model.configurationController==nativeView.configurationController);
  for(int i=0;i<a.length;i++)if(i!=5&&i!=6)check(own.args[i]==a[i]);
  check(arguments[5]==nativeView&&arguments[6]==nativeSeek&&!Boolean.TRUE.equals(creating.get()));
  check(((kotlin.jvm.functions.Function0<?>)model.sizeChangedListener).invoke()==Unit.INSTANCE);
  measure(own,900);State state=(State)manager.mediaHostStates.get(0);
  check(state.visible&&state.expansion==1&&state.squishFraction==1);
  check(state.input.width==(900|View.MeasureSpec.EXACTLY)&&state.input.height==0);
  measure(own,1200);check(manager.mediaHostStates.get(0)==state&&state.input.width==(1200|View.MeasureSpec.EXACTLY));
  check(nativeManager.mediaHostStates.isEmpty()&&nativeView.sets==0);
  rejects(()->measure(original,100));release(original);check(original.destroyed==0&&nativeView.destroyed==0);
  release(own);release(own);check(own.destroyed==1&&model.destroyed==1&&!owned.contains(own));
  check(manager.controllers.isEmpty()&&nativeManager.controllers.contains(nativeView));
  rejects(()->measure(own,100));
  XposedHelpers.failPanel=true;rejects(()->create());
  check(Model.last!=nativeView&&Model.last.destroyed==1&&Model.last.mediaHostStatesManager.controllers.isEmpty());
  check(owned.isEmpty()&&!Boolean.TRUE.equals(creating.get())&&nativeView.destroyed==0);
  Root root=new Root();applyPlaceholderState(root,new Object(),new Object(),new Object());
  check(root.step==3&&root.measureState==root.nativeState&&root.currentState==root.nativeState);
  System.out.println("OS2 native media ownership checks="+checks+" passed; rendering needs device");
 }
}
'''.replace("__METHODS__", methods)
test = out / "LegacyMediaTest.java"
test.write_text(bench, encoding="utf-8")
function = out / "kotlin/jvm/functions/Function0.java"
function.parent.mkdir(parents=True, exist_ok=True)
function.write_text("package kotlin.jvm.functions; public interface Function0<T> { T invoke(); }", encoding="utf-8")
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk / "javac.exe"), "-encoding", "UTF-8", "--release", "17", "-d", str(out), str(test), str(function)], check=True)
subprocess.run([str(jdk / "java.exe"), "-cp", str(out), "LegacyMediaTest"], check=True)
