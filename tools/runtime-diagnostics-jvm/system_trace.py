"""Execute actual capture cleanup on root-denied/missing files, independent of Android."""
from pathlib import Path
import subprocess
def run(root,out,jdk):
    folder=out/'native-capture-failure'
    files={
        'android/content/SharedPreferences.java':'''package android.content; public class SharedPreferences {
public String getString(String k,String d){return d;}public Editor edit(){return new Editor();}
public static class Editor {public Editor putString(String k,String v){return this;}public Editor putLong(String k,long v){return this;}
public Editor remove(String k){return this;}public void apply(){}}}''',
        'android/content/Context.java':'''package android.content;public class Context {
public java.io.File getCacheDir(){return new java.io.File(System.getProperty("capture.cache"));}
public SharedPreferences getSharedPreferences(String n,int mode){return new SharedPreferences();}}''',
        'com/jiusis/mistep/RuntimeDiagnosticCollector.java':'''package com.jiusis.mistep; class RuntimeDiagnosticCollector {
static RuntimeDiagnosticBundle.Part command(String name,String scope,String script,int seconds) {
if(script.contains("pkill")||script.contains("killall"))throw new AssertionError("global session mutation");
return new RuntimeDiagnosticBundle.Part(name,"ERROR",scope,"root unavailable",1,1);}}''',
        'com/jiusis/mistep/NativeCaptureFailureTest.java':'''package com.jiusis.mistep;import java.util.*;
public class NativeCaptureFailureTest {public static void main(String[] args){
var context=new android.content.Context();
var s=new RuntimeSystemTrace.Session("0123456789abcdef0123456789abcdef");
RuntimeSystemTrace.finish(context,s);
if(s.parts.size()!=8)throw new AssertionError("Failure evidence omitted: "+s.parts.size());
if(s.parts.stream().noneMatch(p->p.name.equals("31-system-logcat.3.txt")))throw new AssertionError("Rotation file index");
var bundle=new RuntimeDiagnosticBundle("test",s.parts,s.attachments);
if(!bundle.status().contains("8"))throw new AssertionError("Failure state hidden");
RuntimeSystemTrace.finish(context,s);
if(s.parts.size()!=8)throw new AssertionError("Non-idempotent stop");
try{new RuntimeSystemTrace.Session("../external");throw new AssertionError();}catch(IllegalArgumentException expected){}
System.out.println("Production native capture missing/root-denied/rotated-log/idempotence checks passed");
}}'''
    }
    folder.mkdir(parents=True,exist_ok=True)
    for name,data in files.items():
        p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data,encoding='utf8')
    source=root/'module/src/main/java/com/jiusis/mistep'
    subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(folder),
        *[str(folder/name)for name in files],
        *[str(source/name)for name in ['RuntimeSystemTrace.java','RuntimeTraceFormat.java','RuntimeDiagnosticBundle.java','RuntimeLogText.java']]],check=True)
    subprocess.run([str(jdk/'java.exe'),'-Dcapture.cache='+str(folder/'cache'),'-cp',str(folder),'com.jiusis.mistep.NativeCaptureFailureTest'],check=True)
