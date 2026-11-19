"""Native-shaped contracts for the production live-window animation bridge, not visual acceptance."""
import os, subprocess, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)
JDK = Path(os.environ['MISTEP_JAVA_BIN'])
phone = ROOT/'tools/phone-rotation-jvm/run.py'
ns = {'__file__': str(phone), '__name__': 'fixture'}
exec(compile(phone.read_text(encoding='utf-8').split('sources=' + '[]')[0], str(phone), 'exec'), ns)
FILES = ns['FILES'].copy()
ANIMATION_FILES = {
'android/graphics/Point.java': '''package android.graphics;public class Point{public int x,y;public Point(){}public Point(Point p){x=p.x;y=p.y;}}''',
'android/content/Context.java': '''package android.content;import android.content.res.Resources;public class Context{public Resources resources=new Resources();public Resources getResources(){return resources;}}''',
'android/content/res/Resources.java': '''package android.content.res;public class Resources{public boolean missing;public String name,type,pkg;public int getIdentifier(String n,String t,String p){name=n;type=t;pkg=p;return missing?0:27;}}''',
'android/view/SurfaceControl.java': '''package android.view;
public class SurfaceControl{public boolean valid=true;public SurfaceControl parent;public String name;
 public boolean isValid(){return valid;}
 public static class Builder{public SurfaceControl result=new SurfaceControl();public Builder setParent(SurfaceControl p){result.parent=p;return this;}public Builder setContainerLayer(){return this;}public Builder setName(String n){result.name=n;return this;}public Builder setHidden(boolean h){return this;}public SurfaceControl build(){return result;}}
 public static class Transaction{public int width,height,layer,removes,shows;public float x,y;public SurfaceControl cropTarget,parentTarget,removed;
 public Transaction setWindowCrop(SurfaceControl s,int w,int h){cropTarget=s;width=w;height=h;return this;}
 public Transaction setPosition(SurfaceControl s,float a,float b){parentTarget=s;x=a;y=b;return this;}
 public Transaction setLayer(SurfaceControl s,int l){parentTarget=s;layer=l;return this;}
 public Transaction show(SurfaceControl s){shows++;return this;}
 public Transaction remove(SurfaceControl s){removes++;removed=s;return this;}
 }}''',
'android/view/animation/Animation.java': '''package android.view.animation;import android.graphics.Matrix;public class Animation{public int width,height,parentW,parentH;public float scale;public boolean extension;public Matrix frame=new Matrix();public void initialize(int w,int h,int pw,int ph){width=w;height=h;parentW=pw;parentH=ph;}public void scaleCurrentDuration(float s){scale=s;}public boolean getTransformation(long time,Transformation out){out.clear();out.getMatrix().set(frame);return time<500;}public long computeDurationHint(){return 500;}}''',
'android/view/animation/AlphaAnimation.java': '''package android.view.animation;public class AlphaAnimation extends Animation{}''',
'android/view/animation/AnimationSet.java': '''package android.view.animation;import java.util.*;public class AnimationSet extends Animation{private final List<Animation> children=new ArrayList<>();public AnimationSet(){}public AnimationSet(boolean shared){}public void addAnimation(Animation a){children.add(a);}public List<Animation> getAnimations(){return children;}public void initialize(int w,int h,int pw,int ph){super.initialize(w,h,pw,ph);for(Animation a:children)a.initialize(w,h,pw,ph);}public void scaleCurrentDuration(float s){super.scaleCurrentDuration(s);for(Animation a:children)a.scaleCurrentDuration(s);}public boolean getTransformation(long time,Transformation out){boolean more=false;out.clear();for(Animation a:children)more=a.getTransformation(time,out)||more;return more;}}''',
'android/view/animation/Transformation.java': '''package android.view.animation;import android.graphics.Matrix;public class Transformation{private final Matrix matrix=new Matrix();public Matrix getMatrix(){return matrix;}public void clear(){matrix.reset();}}''',
'android/view/animation/AnimationUtils.java': '''package android.view.animation;import android.content.Context;public class AnimationUtils{public static AnimationSet last;public static boolean extension;public static android.graphics.Matrix probe=new android.graphics.Matrix();public static Animation loadAnimation(Context c,int id){last=new AnimationSet();last.extension=extension;Animation rotate=new Animation();rotate.frame.set(probe);rotate.extension=extension;last.addAnimation(rotate);last.addAnimation(new AlphaAnimation());return last;}}''',
'com/android/server/wm/SurfaceAnimationRunner.java': '''package com.android.server.wm;public class SurfaceAnimationRunner{}''',
'com/android/server/wm/AnimationAdapter.java': '''package com.android.server.wm;public interface AnimationAdapter{}''',
'com/android/server/wm/WindowAnimationSpec.java': '''package com.android.server.wm;import android.graphics.Point;import android.view.animation.Animation;public class WindowAnimationSpec implements LocalAnimationAdapter.AnimationSpec{public Animation animation;public Point position;public boolean skip;public float radius;public WindowAnimationSpec(Animation a,Point p,boolean s,float r){animation=a;position=p;skip=s;radius=r;}public boolean hasExtension(){return animation.extension;}}''',
'com/android/server/wm/LocalAnimationAdapter.java': '''package com.android.server.wm;public class LocalAnimationAdapter implements AnimationAdapter{public interface AnimationSpec{}public AnimationSpec spec;public SurfaceAnimationRunner runner;public LocalAnimationAdapter(AnimationSpec s,SurfaceAnimationRunner r){spec=s;runner=r;}}''',
'com/android/server/wm/SurfaceAnimator.java': '''package com.android.server.wm;import android.view.SurfaceControl;public class SurfaceAnimator{public static final int ANIMATION_TYPE_SCREEN_ROTATION=32;public AnimationAdapter current;public int starts,cancels,type;public boolean fail,hidden;public SurfaceControl.Transaction transaction;public Object animatable;public SurfaceControl leash;public SurfaceAnimator(){}public SurfaceAnimator(Object a,Object callback,Object service){animatable=a;}public AnimationAdapter getAnimation(){return current;}public void startAnimation(SurfaceControl.Transaction t,AnimationAdapter a,boolean h,int y){transaction=t;current=a;starts++;hidden=h;type=y;if(animatable!=null){Object b=de.robv.android.xposed.XposedHelpers.callMethod(animatable,"makeAnimationLeash");de.robv.android.xposed.XposedHelpers.callMethod(b,"setParent",de.robv.android.xposed.XposedHelpers.callMethod(animatable,"getAnimationLeashParent"));leash=(SurfaceControl)de.robv.android.xposed.XposedHelpers.callMethod(b,"build");de.robv.android.xposed.XposedHelpers.callMethod(animatable,"onAnimationLeashCreated",t,leash);}if(fail)throw new IllegalStateException("native failure");}public void cancelAnimation(){cancels++;current=null;if(animatable!=null)de.robv.android.xposed.XposedHelpers.callMethod(animatable,"onAnimationLeashLost",new SurfaceControl.Transaction());}}''',
'com/android/server/wm/WindowManagerService.java': '''package com.android.server.wm;import android.content.Context;public class WindowManagerService{public Context mContext=new Context();public SurfaceAnimationRunner mSurfaceAnimationRunner=new SurfaceAnimationRunner();public float scale=1;public int index=-1;public float getAnimationScale(int i){index=i;return scale;}}''',
'com/android/server/wm/WindowState.java': '''package com.android.server.wm;import android.view.SurfaceControl;public class WindowState{public SurfaceAnimator mSurfaceAnimator=new SurfaceAnimator();public SurfaceControl surface=new SurfaceControl();public boolean visible=true;public boolean isVisible(){return visible;}public SurfaceControl getSurfaceControl(){return surface;}}''',
}
for name in ['SurfaceControl.java', 'SurfaceAnimator.java', 'WindowManagerService.java', 'XposedHelpers.java', 'FakeRotationActivity.java', 'Matrix.java', 'RectF.java']:
    text=(Path(__file__).resolve().parent/name).read_text(encoding='utf-8')
    import re
    package=re.search(r'package ([\w.]+);',text).group(1)
    ANIMATION_FILES[package.replace('.', '/')+'/'+name]=text
FILES.update(ANIMATION_FILES)
FILES.pop('com/jiusis/mistep/PhoneRotationTest.java',None)
FILES['com/jiusis/mistep/RotationAnimationTest.java']=(Path(__file__).resolve().parent/'RotationAnimationTest.java').read_text(encoding='utf-8')

sources=[]
for name, text in FILES.items():
    p = OUT/name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')
    sources.append(str(p))
sources.append(str(ROOT/'module/src/main/java/com/jiusis/mistep/MiStepRotationAnimation.java'))
cp = os.pathsep.join([str(OUT), os.environ['MISTEP_CLASSES'], os.environ['MISTEP_ANDROID_JAR'],
                      str(ROOT/'libs/xposed-api-82.jar')])
subprocess.run([str(JDK/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-cp', cp,
                '-d', str(OUT), *sources], check=True)
subprocess.run([str(JDK/'java.exe'), '-cp', cp, 'com.jiusis.mistep.RotationAnimationTest'], check=True)
