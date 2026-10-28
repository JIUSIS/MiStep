import os
from pathlib import Path
import subprocess,sys
ROOT=Path(__file__).resolve().parents[2];OUT=Path(sys.argv[1]);OUT.mkdir(parents=True,exist_ok=True);JDK=Path(os.environ['MISTEP_JAVA_BIN'])
test=OUT/'com/jiusis/mistep/PanelInputRegression.java';test.parent.mkdir(parents=True,exist_ok=True)
test.write_text(r'''package com.jiusis.mistep;
public class PanelInputRegression {
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static boolean accepts(boolean active,int display,int type,String pkg,String title,int r){return MiStepPanelInputCoordinates.accepts(active,display,type,pkg,title,r);}
 public static void main(String[]args)throws Exception{
  check(accepts(true,0,2000,"com.android.systemui","StatusBar",1),"rotated native bar rejected");
  check(accepts(true,0,2040,"com.android.systemui","NotificationShade",3),"reverse landscape panel rejected");
  check(accepts(true,0,2017,"com.android.systemui","NotificationModalWindowManager",1),"native detail rejected");
  check(!accepts(false,0,2000,"com.android.systemui","StatusBar",1),"outside workspace admitted");
  check(accepts(true,0,2000,"com.android.systemui","StatusBar",0),"scaled native portrait panel rejected");
  check(!accepts(false,0,2000,"com.android.systemui","StatusBar",0),"inactive portrait input was changed");
  check(!accepts(true,0,2000,"com.android.systemui","StatusBar",-1),"undefined window rotation admitted");
  check(!accepts(true,1,2000,"com.android.systemui","StatusBar",1),"external display admitted");
  check(!accepts(true,0,2000,"foreign","StatusBar",1),"foreign panel admitted");
  check(!accepts(true,0,2017,"com.android.systemui","FlaresView",1),"permission view input changed");
  check(!accepts(true,0,2017,"com.android.systemui",null,1),"untitled panel admitted");
  check(!accepts(true,0,2038,"com.android.systemui","StatusBar",1),"native floating overlay admitted");
  int[] ids={0,7};float[] x={639.76074f,900f},y={22.655273f,38f};
  MiStepPanelInputCoordinates down=new MiStepPanelInputCoordinates(393225,393225,4,4098,0,ids,x,y);
  check(down.raw(393225,393225,4,4098,0,0,false)==22.655273f,"real trace rawY remains1100 instead of logical22.65");
  check(down.raw(393225,393225,4,4098,0,0,true)==639.76074f,"entry split still uses physical790");
  x[0]=10;y[0]=20;ids[0]=99;
  check(down.raw(393225,393225,4,4098,0,0,false)==22.655273f,"child local offsets changed root raw sample");
  check(down.raw(393225,393225,4,4098,0,7,true)==900f,"reordered pointer lookup lost ID");
  MiStepPanelInputCoordinates up=new MiStepPanelInputCoordinates(393225,393679,4,4098,0,new int[]{0},new float[]{639.76074f},new float[]{544.63574f});
  check(up.raw(393225,393679,4,4098,0,0,false)==544.63574f,"real trace up rawY did not advance");
  check(up.raw(393225,393679,4,4098,0,0,true).equals(down.raw(393225,393225,4,4098,0,0,true)),"straight gesture rawX drifted");
  check(up.raw(393226,393679,4,4098,0,0,false)==null,"different gesture admitted");
  check(up.raw(393225,393680,4,4098,0,0,false)==null,"different sample admitted");
  check(up.raw(393225,393679,5,4098,0,0,false)==null,"different input device admitted");
  check(up.raw(393225,393679,4,8194,0,0,false)==null,"different input source admitted");
  check(up.raw(393225,393679,4,4098,1,0,false)==null,"different event display admitted");
  check(up.raw(393225,393679,4,4098,0,7,false)==null,"unknown pointer ID admitted");
  System.out.println("actual native panel sample/trace checks="+checks+" passed");
 }
}''',encoding='utf8')
subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(OUT),str(test),str(ROOT/'module/src/main/java/com/jiusis/mistep/MiStepPanelInputCoordinates.java')],check=True)
subprocess.run([str(JDK/'java.exe'),'-cp',str(OUT),'com.jiusis.mistep.PanelInputRegression'],check=True)
