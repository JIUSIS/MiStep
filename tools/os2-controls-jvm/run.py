"""Exercise actual OEM resource/host/drag compatibility methods, without Android UI claims."""
from pathlib import Path
import os
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = root / "module/src/main/java/com/jiusis/mistep"
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)


def member(text, signature):
    start = text.index(signature)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


controls = (src / "MiStepOs4Controls.java").read_text(encoding="utf-8-sig")
actions = (src / "MiStepSystemActions.java").read_text(encoding="utf-8-sig")
methods = "\n".join(member(controls, s) for s in (
    "    static Object inflate(", "    static View root(", "    static View field(",
    "    private static void configureSliderDrag(", "    private static void releaseSliderDrag("))
methods += "\n" + member(actions, "    private static Class<?> hostType(")
files = {
    "android/content/Context.java": '''package android.content;
public class Context {
 public final android.content.res.Resources resources=new android.content.res.Resources();
 public android.content.res.Resources getResources(){return resources;}
 public String getPackageName(){return "miui.systemui.plugin";}
 public ClassLoader getClassLoader(){return getClass().getClassLoader();}
}''',
    "android/content/res/Resources.java": '''package android.content.res;
public class Resources {
 public final java.util.Map<String,Integer> ids=new java.util.HashMap<>();
 public int getIdentifier(String name,String type,String pkg){
  if(!pkg.equals("miui.systemui.plugin"))throw new AssertionError(pkg);
  return ids.getOrDefault(type+"/"+name,0);
 }
}''',
    "android/view/View.java": '''package android.view;
public class View {
 public final android.content.Context context;
 public final java.util.Map<Integer,View> children=new java.util.HashMap<>();
 public View(android.content.Context c){context=c;}
 public android.content.Context getContext(){return context;}
 public View requireViewById(int id){
  View v=children.get(id);if(v==null)throw new IllegalArgumentException("Missing view "+id);return v;
 }
}''',
    "android/view/LayoutInflater.java": '''package android.view;
public class LayoutInflater {
 public static final java.util.Map<Integer,View> views=new java.util.HashMap<>();
 public static int calls;
 public static LayoutInflater from(android.content.Context c){return new LayoutInflater();}
 public View inflate(int id,Object parent,boolean attach){
  if(parent!=null||attach)throw new AssertionError("must be independent");
  calls++;return java.util.Objects.requireNonNull(views.get(id));
 }
}''',
    "miui/systemui/controlcenter/qs/tileview/QSTileItemView.java": '''
package miui.systemui.controlcenter.qs.tileview;
public class QSTileItemView extends android.view.View {
 public QSTileItemView(android.content.Context c){super(c);}
}''',
    "miui/systemui/controlcenter/widget/ToggleSliderView.java": '''
package miui.systemui.controlcenter.widget;
public class ToggleSliderView extends android.view.View {
 public ToggleSliderView(android.content.Context c){super(c);}
}''',
    "OemControlsTest.java": r'''
import android.content.Context;
import android.view.View;
import android.view.LayoutInflater;
import java.util.*;
import java.lang.reflect.*;
public class OemControlsTest {
 static int checks;
 static final String HOST="modern";
 static final Context context=new Context();
 static Context context(){return context;}
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static void rejects(Runnable r){try{r.run();throw new AssertionError("accepted");}
  catch(IllegalStateException|IllegalArgumentException expected){checks++;}}
 public static class Binding {
  public View tileLabel;public View view;static boolean fail;
  public static Binding inflate(LayoutInflater i){if(fail)throw new IllegalStateException("native failure");
   Binding b=new Binding();b.view=new View(context);b.tileLabel=new View(context);return b;}
  public View getRoot(){return view;}
 }
 public static class ModernHost {public Collection<Object> getTiles(){return List.of();}
  public Object createTile(String spec){return new Object();}}
 public static class IncompleteHost {public Object createTile(String spec){return null;}}
 public static class LegacyHost extends ModernHost {}
 public static class Drag {View view;int cleaned;public void setDragView(View v){view=v;}
  public void cleanAnim(){cleaned++;}}
 public static class OldSlider extends View {final Drag drag=new Drag();OldSlider(){super(OemControlsTest.context);}
  public Drag getDragAnim(){return drag;}}
 public static class NewSlider extends OldSlider {View direct;int reset;
  public void setDragView(View v){direct=v;}public void resetTouchAnimNow(){reset++;}}
 static class XposedHelpers {
  static final Map<String,Class<?>> types=new HashMap<>();
  static Class<?> findClassIfExists(String s,ClassLoader l){return types.get(s);}
  static Object getObjectField(Object o,String s){try{return o.getClass().getField(s).get(o);}
   catch(Exception e){throw new IllegalStateException(e);}}
  static Object callStaticMethod(Class<?> c,String s,Object...a){return invoke(c,null,s,a);}
  static Object callMethod(Object o,String s,Object...a){return invoke(o.getClass(),o,s,a);}
  static Object invoke(Class<?> c,Object o,String s,Object[] a){
   try{for(Method m:c.getMethods())if(m.getName().equals(s)&&m.getParameterCount()==a.length)return m.invoke(o,a);
    throw new NoSuchMethodException(s);
   }catch(InvocationTargetException e){throw (RuntimeException)e.getCause();}
   catch(Exception e){throw new IllegalStateException(e);}
  }
 }
 static class ShellCompat {static boolean hasMethod(Class<?> c,String name,int count){
  for(Method m:c.getMethods())if(m.getName().equals(name)&&m.getParameterCount()==count)return true;return false;}}
 __METHODS__
 public static void main(String[] args){
  String modern="miui.systemui.controlcenter.databinding.QsTileItemViewBinding";
  XposedHelpers.types.put(modern,Binding.class);
  Object binding=inflate("QsTileItemViewBinding");
  check(binding instanceof Binding&&root(binding)==((Binding)binding).view&&LayoutInflater.calls==0);
  check(field(binding,"tileLabel")==((Binding)binding).tileLabel);
  Binding.fail=true;rejects(()->inflate("QsTileItemViewBinding"));
  check(LayoutInflater.calls==0);Binding.fail=false;XposedHelpers.types.clear();
  View tile=new miui.systemui.controlcenter.qs.tileview.QSTileItemView(context);
  View slider=new miui.systemui.controlcenter.widget.ToggleSliderView(context);
  context.resources.ids.put("layout/qs_tile_item_view",1);LayoutInflater.views.put(1,tile);
  context.resources.ids.put("layout/toggle_slider_item_view",2);LayoutInflater.views.put(2,slider);
  check(inflate("QsTileItemViewBinding")==tile&&root(tile)==tile);
  check(inflate("ToggleSliderItemViewBinding")==slider);
  int id=10;
  for(String[] pair:new String[][]{{"tileLabel","tile_label"},{"iconFrame","icon_frame"},
   {"progressBg","progress_bg"},{"topText","top_text"},{"toggleSliderInner","toggle_slider_inner"},
   {"mark","mark"},{"slider","slider"},{"progress","progress"},{"icon","icon"}}){
   context.resources.ids.put("id/"+pair[1],id);View v=new View(context);tile.children.put(id++,v);
   check(field(tile,pair[0])==v);
  }
  rejects(()->field(tile,"absent"));
  context.resources.ids.put("id/missing_child",300);rejects(()->field(tile,"missingChild"));
  rejects(()->inflate("UnknownBinding"));
  LayoutInflater.views.put(1,new View(context));rejects(()->inflate("QsTileItemViewBinding"));
  context.resources.ids.remove("layout/qs_tile_item_view");rejects(()->inflate("QsTileItemViewBinding"));
  OldSlider old=new OldSlider();configureSliderDrag(old,tile);releaseSliderDrag(old);
  check(old.drag.view==tile&&old.drag.cleaned==1);
  NewSlider newer=new NewSlider();configureSliderDrag(newer,tile);releaseSliderDrag(newer);
  check(newer.direct==tile&&newer.reset==1&&newer.drag.view==null&&newer.drag.cleaned==0);
  String adapter="com.android.systemui.qs.QSHostAdapter",legacy="com.android.systemui.qs.QSTileHost";
  XposedHelpers.types.put(HOST,ModernHost.class);XposedHelpers.types.put(adapter,LegacyHost.class);
  check(hostType(OemControlsTest.class.getClassLoader())==ModernHost.class);
  XposedHelpers.types.put(HOST,IncompleteHost.class);
  check(hostType(OemControlsTest.class.getClassLoader())==LegacyHost.class);
  XposedHelpers.types.remove(adapter);XposedHelpers.types.put(legacy,LegacyHost.class);
  check(hostType(OemControlsTest.class.getClassLoader())==LegacyHost.class);
  XposedHelpers.types.clear();rejects(()->hostType(OemControlsTest.class.getClassLoader()));
  System.out.println("OEM controls compatibility checks="+checks+" passed");
 }
}
'''.replace("__METHODS__", methods),
}
paths = []
for name, value in files.items():
    p = out / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(value, encoding="utf-8")
    paths.append(str(p))
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk / "javac.exe"), "-encoding", "UTF-8", "--release", "17", "-d", str(out)] + paths, check=True)
subprocess.run([str(jdk / "java.exe"), "-cp", str(out), "OemControlsTest"], check=True)
