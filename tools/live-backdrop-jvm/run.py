import os
"""Exercise the real wallpaper lease and start/finish visibility with owned fake surfaces."""
from pathlib import Path
import subprocess
import sys
import re

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
src = root / 'module/src/main/java/com/jiusis/mistep'
run = (src / 'MiStepLiveSwap64.java').read_text(encoding='utf8')
background = run[run.index('        void background('):run.index('        @Override public void prepare(')]
host = (src / 'MiStepHost.java').read_text(encoding='utf8')
swap = host[host.index('    private void startLiveSwap('):host.index('    private void drawLiveEmptyHints(')]
call = swap[swap.index('ticket.liveBackdrop = MiStepLiveBackdrop.create('):]
call = call[:call.index('canvas ->')]
side_crops = re.findall(r'new Rect\([\s\S]*?\)', call)
if len(side_crops) != 2:
    raise RuntimeError('Expected actual original/target wallpaper crop arguments')
a = host.index('    private void ensureCellBackground(')
b = host.index('    private void registerDefaultDisplayListener(', a)
ensure = host[a:b]
files = {
'android/graphics/Color.java': '''package android.graphics;public class Color {public static int red(int c){return 245;}public static int green(int c){return 245;}public static int blue(int c){return 245;}}''',
'android/graphics/Bitmap.java': '''package android.graphics; public class Bitmap {public boolean recycled;public int getWidth(){return 360;}public int getHeight(){return 820;}public boolean isRecycled(){return recycled;}}''',
'android/graphics/Canvas.java': '''package android.graphics; public class Canvas {public static Bitmap source;public static boolean fail;public void drawBitmap(Bitmap b,Rect s,Rect d,Paint p){if(fail)throw new IllegalStateException("draw");source=b;}}''',
'android/graphics/Paint.java': '''package android.graphics; public class Paint {public static int FILTER_BITMAP_FLAG=2;public Paint(int f){}}''',
'android/graphics/PixelFormat.java': '''package android.graphics; public class PixelFormat {public static int RGBA_8888=1;}''',
'android/graphics/Rect.java': '''package android.graphics; public class Rect {public int left,top,right,bottom;public Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}}''',
'android/view/View.java': '''package android.view;import java.util.*;public class View {
 public boolean attached=true,hardware=true;public List<OnAttachStateChangeListener>listeners=new ArrayList<>();public Observer observer=new Observer();public RootSurface rootSurface=new RootSurface();
 public static class RootSurface {public SurfaceControl.Transaction queued;public boolean accepted=true;public boolean applyTransactionOnDraw(SurfaceControl.Transaction t){queued=t;return accepted;}public void commit(){queued.committed.run();}}
 public RootSurface getRootSurfaceControl(){return rootSurface;}
 public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
 public static class Observer {public Runnable commit;public void registerFrameCommitCallback(Runnable r){commit=r;}}
 public boolean isAttachedToWindow(){return attached;}public boolean isHardwareAccelerated(){return hardware;}
 public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.add(l);}public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){listeners.remove(l);}
 public Observer getViewTreeObserver(){return observer;}public void invalidate(){}public boolean post(Runnable r){r.run();return true;}
 public void detach(){attached=false;for(var l:new ArrayList<>(listeners))l.onViewDetachedFromWindow(this);}
}''',
'android/view/Surface.java': '''package android.view; import android.graphics.*; public class Surface {public static Surface latest;public static boolean fail;public int releases,posts;public Surface(SurfaceControl c){if(fail)throw new IllegalStateException("producer");latest=this;}public Canvas lockCanvas(Rect r){return new Canvas();}public void unlockCanvasAndPost(Canvas c){posts++;}public void release(){releases++;}}''',
'android/view/SurfaceControl.java': '''package android.view;import java.util.*;public class SurfaceControl {
public static SurfaceControl latest;public int releases;public void release(){releases++;}
public static class Builder {public static Builder latest;public Object parent;public String name;public int width,height;public Builder(){latest=this;}public Builder setName(String n){name=n;return this;}public Builder setParent(Object p){parent=p;return this;}public Builder setBufferSize(int w,int h){width=w;height=h;return this;}public Builder setFormat(int f){return this;}public Builder setColorLayer(){return this;}public SurfaceControl build(){SurfaceControl.latest=new SurfaceControl();return SurfaceControl.latest;}}
public interface TransactionCommittedListener {void onTransactionCommitted();}
public static class Transaction {public static Transaction latest;public Runnable committed;public int applies,closes,removes,layer;public float scale;public android.graphics.Rect crop;public List<String>ops=new ArrayList<>();public Transaction(){latest=this;}public Transaction addTransactionCommittedListener(java.util.concurrent.Executor e,TransactionCommittedListener l){committed=()->e.execute(l::onTransactionCommitted);return this;}public Transaction setColor(Object s,float[]rgb){return this;}public Transaction setMatrix(Object s,float a,float b,float c,float d){scale=a;return this;}public Transaction setPosition(Object s,float x,float y){return this;}public Transaction setWindowCrop(Object s,Object r){crop=(android.graphics.Rect)r;return this;}public Transaction setLayer(Object s,int l){layer=l;return this;}public Transaction hide(Object s){ops.add("hide:"+System.identityHashCode(s));return this;}public Transaction show(Object s){ops.add("show:"+System.identityHashCode(s));return this;}public Transaction remove(Object s){removes++;return this;}public void apply(){applies++;}public void close(){closes++;}}
}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;public class XposedHelpers {public static Object callMethod(Object o,String n,Object...a){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new IllegalStateException(e);}}}''',
'com/jiusis/mistep/MiStepLiveHomeTask.java': '''package com.jiusis.mistep;class MiStepLiveHomeTask {static boolean valid(Object o){return o!=null;}}''',
'com/jiusis/mistep/BackdropRegression.java': r'''package com.jiusis.mistep;
import android.graphics.*;import android.view.*;import java.util.*;import de.robv.android.xposed.XposedHelpers;
public class BackdropRegression {
static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
static class HyperOsStyle {static int page(Object c){return 0;}}
static class Slot {int index=1;boolean occupied,parked;Object controller=new Object(),cellBackground,cellBackgroundParent;int[]rect={0,0,267,609};int cellBackgroundWidth,cellBackgroundHeight;}
static class Host {
 Bitmap wallpaperBackdrop;Object root=new Object(),ctx=new Object(),parent=new Object();static final int SLOT_MAIN=0;int removed;
 void applyWallpaperBackdrops(){}Object getSurfaceField(Object c,String f){return parent;}
 void removeCellBackground(Slot s){if(s.cellBackground!=null)removed++;s.cellBackground=null;s.cellBackgroundParent=null;}
 void destroyCellSurface(Object o){}void closeSurfaceObject(Object o,String n){if(o!=null)XposedHelpers.callMethod(o,n);}void log(String m){}
 ENSURE
}
static class Run {Object[]backgrounds;MiStepLiveBackdrop backdrop;Object home;boolean rollback;BACKGROUND}
static Rect[] actualHostCrops(MiStepLayout geometry,MiStepLayout target){int width=geometry.width,height=geometry.height;return new Rect[]{CALL_CROPS};}
static void coversGap(Rect crop,MiStepLayout g,String phase){
 double sx=360.0/g.width,sy=820.0/g.height;
 int gapLeft=g.mainOnRight?g.cellWidth:g.mainWidth;
 for(int x=gapLeft;x<gapLeft+g.gap;x++)check((x+.5)*sx>=crop.left&&(x+.5)*sx<crop.right,phase+" covers the main/side gap at physical x="+x);
 check(crop.top<=g.topStrip*sy&&crop.bottom>=g.height*sy,phase+" covers the entire gap height");
 int sideLeft=g.mainOnRight?0:g.mainWidth,sideRight=g.mainOnRight?g.sideWidth:g.width;
 check(crop.left>=Math.floor(sideLeft*sx)&&crop.right<=Math.ceil(sideRight*sx),phase+" does not expand into the main area beyond buffer rounding");
}
public static void main(String[]args)throws Exception {
 for(int[]wh:new int[][]{{1200,2608},{1080,2460},{1225,2601}})for(boolean right:new boolean[]{false,true}){
  MiStepLayout geometry=MiStepLayout.of(wh[0],wh[1],right,3f),target=MiStepLayout.of(wh[0],wh[1],!right,3f);
  Rect[]crops=actualHostCrops(geometry,target);MiStepLiveBackdrop gap=MiStepLiveBackdrop.create(new Object(),new Bitmap(),wh[0],wh[1],crops[0],crops[1],s->{});
  coversGap(SurfaceControl.Transaction.latest.crop,geometry,"before hiding Host drawings");
  SurfaceControl.Transaction swapped=new SurfaceControl.Transaction();gap.stage(swapped,true,false,true);coversGap(swapped.crop,target,"finish before restored Host buffer");
  SurfaceControl.Transaction rolledBack=new SurfaceControl.Transaction();gap.stage(rolledBack,true,true,true);coversGap(rolledBack.crop,geometry,"rollback before restored Host buffer");gap.close();
 }
 Host host=new Host();Slot slot=new Slot();SurfaceControl previous=SurfaceControl.latest;
 host.ensureCellBackground(slot);check(slot.cellBackground==null&&SurfaceControl.latest==previous,"empty slot allocates no opaque backing");
 slot.occupied=true;host.ensureCellBackground(slot);check(slot.cellBackground!=null,"occupied app keeps translucent-task backing");
 Object occupied=slot.cellBackground;host.ensureCellBackground(slot);check(slot.cellBackground==occupied,"same occupied generation reuses owned backing");
 slot.occupied=false;host.ensureCellBackground(slot);check(slot.cellBackground==null&&host.removed==1,"retiring app removes stale backing immediately");
 slot.occupied=true;host.ensureCellBackground(slot);slot.parked=true;host.ensureCellBackground(slot);check(slot.cellBackground==null&&host.removed==2,"parked task cannot leave opaque empty placeholder");
 Object parent=new Object();Bitmap wallpaper=new Bitmap();
 int[]painted={0};MiStepLiveBackdrop decorated=MiStepLiveBackdrop.create(parent,wallpaper,1080,2460,null,null,c->{check(Canvas.source==wallpaper,"empty controls paint after wallpaper in the same buffer");painted[0]++;},s->{});
 check(painted[0]==1&&Surface.latest.posts==1,"empty controls posted before any cell drawing is hidden");decorated.close();
 MiStepLiveBackdrop lease=MiStepLiveBackdrop.create(parent,wallpaper,1080,2460,s->{});
 SurfaceControl sc=(SurfaceControl)lease.surface;Surface producer=Surface.latest;SurfaceControl.Transaction prepared=SurfaceControl.Transaction.latest;
 check(SurfaceControl.Builder.latest.parent==parent&&SurfaceControl.Builder.latest.name.equals("MiStepLiveWallpaper"),"only owned Host child created");
 check(Canvas.source==wallpaper&&producer.posts==1,"existing wallpaper alone supplies the buffer");
 check(prepared.scale==3&&prepared.layer==-100&&prepared.ops.equals(List.of("show:"+System.identityHashCode(sc))),"wallpaper covers Host holes before hidden drawings commit");
 Run run=new Run();Object cell=new Object();run.backgrounds=new Object[]{cell};run.backdrop=lease;SurfaceControl.Transaction start=new SurfaceControl.Transaction();run.background(start,false);
 check(start.ops.equals(List.of("hide:"+System.identityHashCode(cell),"show:"+System.identityHashCode(sc)))&&start.applies==0,"same start transaction fills holes without another apply");
 SurfaceControl.Transaction finish=new SurfaceControl.Transaction();run.background(finish,true);
 check(finish.ops.equals(List.of("show:"+System.identityHashCode(cell),"show:"+System.identityHashCode(sc)))&&finish.applies==0,"same finish retains wallpaper until Host drawing commits");
 run.home=new Object();SurfaceControl.Transaction homeFinish=new SurfaceControl.Transaction();run.background(homeFinish,true);
 check(homeFinish.ops.equals(List.of("hide:"+System.identityHashCode(cell),"show:"+System.identityHashCode(sc))),"successful Home promotion never reveals retired cell backing");
 run.rollback=true;SurfaceControl.Transaction reverse=new SurfaceControl.Transaction();run.background(reverse,true);
 check(reverse.ops.equals(List.of("show:"+System.identityHashCode(cell),"show:"+System.identityHashCode(sc))),"Home rollback retains occupied cell backing");
 View restoredRoot=new View();lease.closeAfterFrame(restoredRoot);
 check(sc.releases==0&&restoredRoot.listeners.size()==1,"finish waits for restored Host draw instead of exposing empty SurfaceView holes");
 SurfaceControl.Transaction removal=restoredRoot.rootSurface.queued;check(removal.removes==1&&removal.applies==0&&removal.closes==0,"wallpaper removal queued with Host buffer, no standalone remove or early close");restoredRoot.rootSurface.commit();lease.close();
 check(restoredRoot.listeners.isEmpty(),"committed Host frame releases detach observer");
 check(removal.removes==1&&removal.applies==0&&removal.closes==1&&sc.releases==1&&producer.releases==1,"one idempotent release of owned layer and producer");
 check(!wallpaper.recycled&&lease.surface==null,"shared wallpaper and parent remain owned by Host");
 MiStepLiveBackdrop clipped=MiStepLiveBackdrop.create(parent,wallpaper,1080,2460,new Rect(813,622,1080,2460),new Rect(0,622,267,2460),s->{});
 Rect pre=SurfaceControl.Transaction.latest.crop;check(pre.left==271&&pre.right==360&&pre.top==207,"pre-start wallpaper occupies only original side, main remains visible");
 SurfaceControl.Transaction enter=new SurfaceControl.Transaction();clipped.stage(enter,false,false,true);check(enter.crop.left==0&&enter.crop.right==360&&enter.crop.top==0&&enter.applies==0,"full wallpaper appears atomically with real flip task reparent");
 SurfaceControl.Transaction end=new SurfaceControl.Transaction();clipped.stage(end,true,false,true);check(end.crop.left==0&&end.crop.right==89&&end.crop.top==207&&end.applies==0,"finish clips target side atomically before real main returns below Host");
 SurfaceControl.Transaction rollbackTx=new SurfaceControl.Transaction();clipped.stage(rollbackTx,true,true,true);check(rollbackTx.crop.left==271,"rollback retains original side wallpaper bounds");
 SurfaceControl.Transaction homeStart=new SurfaceControl.Transaction();clipped.stage(homeStart,false,false,false);check(homeStart.crop.left==271&&homeStart.crop.top==207,"Home main stays visible while side app expands above it");
 clipped.close();
 MiStepLiveBackdrop detached=MiStepLiveBackdrop.create(parent,wallpaper,1080,2460,s->{});SurfaceControl ds=(SurfaceControl)detached.surface;View leaving=new View();detached.closeAfterFrame(leaving);leaving.detach();check(ds.releases==1&&leaving.listeners.isEmpty(),"detach before commit releases pending wallpaper lease");leaving.rootSurface.commit();check(ds.releases==1,"late frame callback after detach is harmless");
 MiStepLiveBackdrop refused=MiStepLiveBackdrop.create(parent,wallpaper,1080,2460,s->{});SurfaceControl rs=(SurfaceControl)refused.surface;View refusal=new View();refusal.rootSurface.accepted=false;refused.closeAfterFrame(refusal);check(rs.releases==1&&refusal.listeners.isEmpty(),"refused Host draw cleans resources and listener");
 MiStepLiveBackdrop stopped=MiStepLiveBackdrop.create(parent,wallpaper,1080,2460,s->{});SurfaceControl ss=(SurfaceControl)stopped.surface;View gone=new View();gone.attached=false;stopped.closeAfterFrame(gone);check(ss.releases==1,"unavailable Host frame releases immediately");
 Surface.fail=true;try{MiStepLiveBackdrop.create(parent,wallpaper,1080,2460,s->{});throw new AssertionError("producer failure accepted");}catch(IllegalStateException e){check(SurfaceControl.latest.releases==1&&SurfaceControl.Transaction.latest.removes==1,"partial producer construction releases owned control");}finally{Surface.fail=false;}
 Canvas.fail=true;try{MiStepLiveBackdrop.create(parent,wallpaper,1080,2460,s->{});throw new AssertionError("draw failure accepted");}catch(IllegalStateException e){check(Surface.latest.posts==1&&Surface.latest.releases==1&&SurfaceControl.latest.releases==1,"draw failure posts/unlocks and cleans only owned resources");}finally{Canvas.fail=false;}
 try{MiStepLiveBackdrop.create(parent,null,1080,2460,s->{});throw new AssertionError("missing wallpaper accepted");}catch(IllegalStateException e){checks++;}
 System.out.println("actual wallpaper lease and same-transaction visibility checks="+checks+" passed");
}}
'''.replace('BACKGROUND', background).replace('ENSURE', ensure).replace('CALL_CROPS', ','.join(side_crops))
}
paths = []
for name, text in files.items():
    p = out / name
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf8')
    paths.append(str(p))
jdk = Path(os.environ["MISTEP_JAVA_BIN"])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out),
                str(src/'MiStepLiveBackdrop.java'), str(src/'MiStepLayout.java'), *paths], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.BackdropRegression'], check=True)
