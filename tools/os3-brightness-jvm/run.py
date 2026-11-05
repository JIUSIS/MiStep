"""Run actual brightness range/dispatch behavior against the two inspected ROM signatures."""
import ast,os,subprocess,sys
from pathlib import Path
r=Path(__file__).resolve().parents[2];out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True);jdk=Path(os.environ['MISTEP_JAVA_BIN'])
tree=ast.parse((r/'tools/live-swap-jvm/run.py').read_text(encoding='utf8'));fakes=next(ast.literal_eval(n.value)for n in tree.body if isinstance(n,ast.Assign)and any(isinstance(t,ast.Name)and t.id=='FILES'for t in n.targets))
helper=fakes['de/robv/android/xposed/XposedHelpers.java'];pos=helper.rfind('}')
helper=helper[:pos]+r"""
 public static Class<?> findClass(String name,ClassLoader l){try{return Class.forName(name,true,l);}catch(Exception e){throw new IllegalStateException(e);}}
 public static float getFloatField(Object o,String n){return (Float)getObjectField(o,n);}
 public static Object callStaticMethod(Class<?> c,String n,Object...a){try{for(java.lang.reflect.Method m:c.getDeclaredMethods()){if(!m.getName().equals(n)||m.getParameterCount()!=a.length)continue;Class<?>[]ts=m.getParameterTypes();boolean ok=true;for(int i=0;i<a.length;i++){Class<?>t=ts[i];if(t==float.class)t=Float.class;if(t==int.class)t=Integer.class;if(!t.isInstance(a[i]))ok=false;}if(ok){m.setAccessible(true);return m.invoke(null,a);}}throw new NoSuchMethodException(n);}catch(Exception e){throw new IllegalStateException(e);}}
"""+helper[pos:]
files={'de/robv/android/xposed/XposedHelpers.java':helper,
'android/view/Display.java':r"""package android.view;public class Display {public static final int DEFAULT_DISPLAY=0;public static class Info {public float brightness=.4f,brightnessMinimum=.1f,brightnessMaximum=.8f;}public Object getBrightnessInfo(){return new Info();}}""",
'android/hardware/display/DisplayManager.java':r"""package android.hardware.display;public class DisplayManager {public int writes;public float value;public android.view.Display getDisplay(int id){return new android.view.Display();}public void setBrightness(int id,float v){writes++;value=v;}}""",
'com/android/settingslib/display/BrightnessUtils.java':r"""package com.android.settingslib.display;public class BrightnessUtils {public static int calls,progress;public static float min,max;public static float convertGammaToLinearFloat(float a,float b,int p){calls++;min=a;max=b;progress=p;return .45f;}}""",
'com/jiusis/mistep/BrightnessRegression.java':r"""package com.jiusis.mistep;import android.hardware.display.DisplayManager;
public class BrightnessRegression {static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError();}
 public static class Os3 {public static int convertLinearToGammaFloat(float v,float min,float max){check(v==.4f&&min==.1f&&max==.8f);return 321;}}
 public static class Os4 extends Os3 {public static float convertGammaToLinearFloat(int progress,float min,float max){check(progress==456&&min==.1f&&max==.8f);return 1.2f;}}
 public static class Reject {public static float convertGammaToLinearFloat(int p,float a,float b){throw new IllegalStateException("native rejected");}}
 public static class Invalid {public static float convertGammaToLinearFloat(int p,float a,float b){return Float.NaN;}}
 public static void main(String[]args){DisplayManager d=new DisplayManager();MiStepBrightness b=MiStepBrightness.read(d);check(b.progress(Os3.class)==321);b.write(d,Os3.class,123);check(d.writes==1&&d.value==.45f);check(com.android.settingslib.display.BrightnessUtils.calls==1&&com.android.settingslib.display.BrightnessUtils.progress==123&&com.android.settingslib.display.BrightnessUtils.min==.1f&&com.android.settingslib.display.BrightnessUtils.max==.8f);b.write(d,Os4.class,456);check(d.writes==2&&d.value==.8f);try{b.write(d,Reject.class,1);throw new AssertionError();}catch(IllegalStateException expected){}check(d.writes==2&&com.android.settingslib.display.BrightnessUtils.calls==1);try{b.write(d,Invalid.class,1);throw new AssertionError();}catch(IllegalStateException expected){}check(d.writes==2);System.out.println("actual OS3/OS4 brightness dispatch checks="+checks+" passed");}}
"""}
paths=[]
for n,b in files.items():p=out/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(b,encoding='utf8');paths.append(str(p))
paths += [str(r/'module/src/main/java/com/jiusis/mistep'/n)for n in ['ShellCompat.java','MiStepBrightness.java']]
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.BrightnessRegression'],check=True)
