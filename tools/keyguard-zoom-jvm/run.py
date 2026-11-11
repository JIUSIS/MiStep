from pathlib import Path
import os,subprocess,sys
root=Path(__file__).resolve().parents[2]
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;public class XC_MethodHook {
public static class MethodHookParam {public Object[] args;}
protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
public void before(Object...args)throws Throwable{var p=new MethodHookParam();p.args=args;beforeHookedMethod(p);}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed;public class XposedBridge {public static int errors;public static void log(String s){errors++;}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers {
public static XC_MethodHook hook;public static Object findAndHookMethod(String name,ClassLoader cl,String method,Object...args){if(!name.equals("com.android.server.wm.KeyguardController")||!method.equals("setKeyguardShown")||args[0]!=int.class||args[1]!=boolean.class||args[2]!=boolean.class)throw new AssertionError("wrong native hook");hook=(XC_MethodHook)args[3];return null;}}''',
'com/jiusis/mistep/KeyguardZoomRegression.java':'''package com.jiusis.mistep;import de.robv.android.xposed.*;
public class KeyguardZoomRegression {
static int checks;static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
public static void main(String[]args)throws Throwable {
var gate=new MiStepKeyguardZoom();int[] resets={0};boolean[] nativeVisible={false};
gate.install(KeyguardZoomRegression.class.getClassLoader(),()->{check(gate.blocked(),"barrier must precede reset");check(!nativeVisible[0],"reset must precede native visibility");resets[0]++;});
check(!gate.blocked(),"fresh gate");XposedHelpers.hook.before(4,true,true);check(!gate.blocked()&&resets[0]==0,"secondary display cannot control main geometry");
XposedHelpers.hook.before(0,true,false);nativeVisible[0]=true;check(gate.blocked()&&resets[0]==1,"native lock entry must synchronously reset");
XposedHelpers.hook.before(2,false,false);check(gate.blocked(),"foreign display cannot release lock barrier");
nativeVisible[0]=false;XposedHelpers.hook.before(0,false,true);check(gate.blocked()&&resets[0]==2,"AOD keeps physical geometry");
XposedHelpers.hook.before(0,false,false);check(!gate.blocked()&&resets[0]==2,"native clear removes barrier without opening/restoring a workspace");
gate.install(KeyguardZoomRegression.class.getClassLoader(),()->{throw new IllegalStateException("native reset failed");});
XposedHelpers.hook.before(0,true,false);check(gate.blocked()&&XposedBridge.errors==1,"reset failure cannot open gate or prevent native authentication");
XposedHelpers.hook.before(0,false,false);check(!gate.blocked(),"native unlock remains authoritative after failure");
XposedHelpers.hook.before(0,true,false);check(gate.blocked(),"new lock rearms after prior unlock");
check(!gate.onAuthenticatedReveal(false)&&gate.blocked(),"unauthenticated reveal cannot clear stale controller barrier");
check(gate.onAuthenticatedReveal(true)&&!gate.blocked(),"authenticated native Host handoff releases stale showing barrier");
XposedHelpers.hook.before(0,true,false);check(gate.blocked(),"next native lock rearms after authenticated handoff");
System.out.println("Production native keyguard barrier: "+checks+" checks passed");}}
'''
}
paths=[]
for name,body in files.items():
 p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');paths.append(str(p))
paths.append(str(root/'module/src/main/java/com/jiusis/mistep/MiStepKeyguardZoom.java'))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.KeyguardZoomRegression'],check=True)
