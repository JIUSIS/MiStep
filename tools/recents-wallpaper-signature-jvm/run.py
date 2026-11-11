"""Exercise the production native target-argument adapter for both actual signatures."""
from pathlib import Path
import os, subprocess, sys
root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
source=(root/'module/src/main/java/com/jiusis/mistep/MiStepRecentsWallpaper.java').read_text(encoding='utf-8-sig')
start=source.index('    static int argumentOffset(');end=source.index('{',start)+1;depth=1
while depth:
    if source[end]=='{':depth+=1
    elif source[end]=='}':depth-=1
    end+=1
code='''package com.jiusis.mistep;import java.util.*;
public class RecentsSignatureRegression {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static class MiStepRecentsWallpaper{''' + source[start:end] + '''}
 public static void main(String[]args){
  Object change=new Object(),info=new Object(),tx=new Object();var leashes=new HashMap<Object,Object>();
  Object[] modern={change,7,info,tx,leashes},legacy={change,7,true,info,tx,leashes};
  for(Object[] nativeArgs:new Object[][]{modern,legacy}){
   int i=MiStepRecentsWallpaper.argumentOffset(nativeArgs);
   check(i==(nativeArgs.length==6?1:0),"actual native signature recognized");
   check(nativeArgs[2+i]==info&&nativeArgs[3+i]==tx&&nativeArgs[4+i]==leashes,"same native info transaction and canonical map preserved");
   for(int n:new int[]{0,1,2+i,3+i,4+i}){var bad=nativeArgs.clone();bad[n]=null;check(MiStepRecentsWallpaper.argumentOffset(bad)==-1,"incomplete native inputs excluded");}
  }
  check(MiStepRecentsWallpaper.argumentOffset(null)==-1,"null args excluded");
  check(MiStepRecentsWallpaper.argumentOffset(new Object[]{change,7,new Object(),info,tx,leashes})==-1,"sixth-argument shape requires native boolean");
  check(MiStepRecentsWallpaper.argumentOffset(new Object[]{change,7,tx,true})==-1,"native low-level four-arg overload excluded");
  check(MiStepRecentsWallpaper.argumentOffset(new Object[]{change,7,info,tx,new Object()})==-1,"non-map ownership rejected");
  System.out.println("actual recents wallpaper signature checks="+checks+" passed");
 }
}'''
p=out/'com/jiusis/mistep/RecentsSignatureRegression.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(code,encoding='utf-8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(p)],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.RecentsSignatureRegression'],check=True)
