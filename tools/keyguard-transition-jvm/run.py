from pathlib import Path
import os, subprocess, sys
root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
src=Path(sys.argv[2]) if len(sys.argv)>2 else root/'module/src/main/java/com/jiusis/mistep'
files={
'de/robv/android/xposed/XC_MethodHook.java':r"""package de.robv.android.xposed;
public class XC_MethodHook {
 public static class MethodHookParam {public Object thisObject,result;public Object[] args;public boolean stopped;
 public void setResult(Object r){result=r;stopped=true;}}
 public class Unhook {public boolean removed;public void unhook(){removed=true;}}
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
 public void before(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}
}""",
'de/robv/android/xposed/XposedBridge.java':r"""package de.robv.android.xposed;
import java.util.*;public class XposedBridge {
 public static Map<String,XC_MethodHook> hooks=new LinkedHashMap<>();public static List<XC_MethodHook.Unhook> installed=new ArrayList<>();
 public static String fail;public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c,String n,XC_MethodHook h){
 if(n.equals(fail))throw new LinkageError("registration failed");hooks.put(n,h);XC_MethodHook.Unhook u=h.new Unhook();installed.add(u);return Set.of(u);}
 public static void log(String s){}
}""",
'de/robv/android/xposed/XposedHelpers.java':r"""package de.robv.android.xposed;
public class XposedHelpers {public static Class<?> findClass(String n,ClassLoader cl){return Object.class;}public static Object callMethod(Object o,String n,Object...a)throws Exception{return o.getClass().getMethod(n).invoke(o);}}""",
'com/jiusis/mistep/KeyguardTransitionRegression.java':r"""package com.jiusis.mistep;
import java.util.*;import de.robv.android.xposed.*;
public class KeyguardTransitionRegression {
 public static class Info {int flags;public Info(int f){flags=f;}public int getFlags(){return flags;}}
 public static class BadInfo {public int getFlags(){throw new LinkageError("unsupported ROM");}}
 static int checks;static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
 static Object owner=new Object();static boolean retained=true;
 static XC_MethodHook.MethodHookParam invoke(String name,int length,Object target,Object info)throws Throwable{
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=target;p.args=new Object[length];
  if(length>1)p.args[1]=info;for(int i=2;i<length;i++)p.args[i]=new Object();
  Object[] original=p.args.clone();XposedBridge.hooks.get(name).before(p);
  check(Arrays.equals(original,p.args),"native transactions, targets, and finish callback changed");return p;
 }
 public static void main(String[]a)throws Throwable{
  List<XC_MethodHook.Unhook> installed=MiStepKeyguardTransition.install(Object.class,o->o==owner&&retained);
  check(installed.size()==2&&XposedBridge.hooks.size()==2,"request and start hooks missing");
  for(int flags:new int[]{0x100,0x108,0x4100}){
   XC_MethodHook.MethodHookParam request=invoke("handleRequest",2,owner,new Info(flags));
   check(request.stopped&&request.result==null,"unlock request was claimed by TaskView");
   for(int count:new int[]{5,6}){
    Info info=new Info(flags);XC_MethodHook.MethodHookParam p=invoke("startAnimation",count,owner,info);
    int taskViewPrepare=0,taskViewFinish=0,nativeExit=0;
    if(!p.stopped){taskViewPrepare++;taskViewFinish++;p.result=true;}
    if(Boolean.FALSE.equals(p.result))nativeExit++;
    check(nativeExit==1&&taskViewPrepare==0&&taskViewFinish==0,"retained tasks swallowed native unlock callback");
    check(info.flags==flags,"native auth flags modified");
   }
  }
  for(int flags:new int[]{0,8,0x80,0x200,0x8000})for(String name:new String[]{"handleRequest","startAnimation"}){
   XC_MethodHook.MethodHookParam p=invoke(name,name.equals("handleRequest")?2:6,owner,new Info(flags));
   check(!p.stopped,"ordinary, appearing, occluding, or other transition intercepted");
  }
  for(Object info:new Object[]{null,new Object(),new BadInfo()})
   check(!invoke("startAnimation",5,owner,info).stopped,"unknown transition format intercepted");
  check(!invoke("startAnimation",5,new Object(),new Info(256)).stopped,"foreign TaskView owner intercepted");
  retained=false;check(!invoke("startAnimation",5,owner,new Info(256)).stopped,"inactive or unlocked session intercepted");retained=true;
  for(int length:new int[]{0,1,3,4,7})check(!invoke("startAnimation",length,owner,new Info(256)).stopped,"unknown start signature intercepted");
  for(int length:new int[]{0,1,3,6})check(!invoke("handleRequest",length,owner,new Info(256)).stopped,"unknown request signature intercepted");
  XposedBridge.hooks.clear();XposedBridge.installed.clear();XposedBridge.fail="startAnimation";
  try{MiStepKeyguardTransition.install(Object.class,o->true);throw new AssertionError("registration failure hidden");}
  catch(LinkageError expected){check(XposedBridge.installed.size()==1&&XposedBridge.installed.get(0).removed,"partial registration not rolled back");}
  System.out.println("Native retained keyguard transition: "+checks+" checks passed");
 }
}"""
}
paths=[]
for rel,body in files.items():
 p=out/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf8');paths.append(str(p))
paths.append(str(src/'MiStepKeyguardTransition.java'))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.KeyguardTransitionRegression'],check=True)
