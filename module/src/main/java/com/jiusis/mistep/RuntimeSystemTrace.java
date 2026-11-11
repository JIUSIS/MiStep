package com.jiusis.mistep;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** User-started, bounded native capture. Never changes geometry, logging levels or other sessions. */
final class RuntimeSystemTrace {
    static final int DURATION_MS=60000;
    static final class Session {
        final String token,dir;
        final List<RuntimeDiagnosticBundle.Part> parts=new ArrayList<>();
        final List<RuntimeDiagnosticBundle.Attachment> attachments=new ArrayList<>();
        Set<String> sources=Set.of();
        boolean started,finished;
        Session(String token){if(!token.matches("[a-f0-9]{32}"))throw new IllegalArgumentException();this.token=token;dir="/data/local/tmp/mistep-diagnostics-"+token;}
    }
    static Session start(Context context) {
        Session s=new Session(UUID.randomUUID().toString().replace("-",""));
        RuntimeDiagnosticBundle.Part query=RuntimeDiagnosticCollector.command("20-native-capabilities.txt","本机实际Perfetto数据源；手机/平板按能力选择","id -u; perfetto --query --long",12);
        s.parts.add(query);s.sources=RuntimeTraceFormat.supported(query.text);
        context.getSharedPreferences("mistep-diagnostic-session",0).edit().putString("token",s.token).putString("sources",String.join(",",s.sources)).putLong("epoch",System.currentTimeMillis()).apply();
        String config=java.util.Base64.getEncoder().encodeToString(RuntimeTraceFormat.config(s.sources,DURATION_MS));
        String script="set -e\n[ $(id -u) = 0 ]\numask 077\n"
                +"printf '%s' '"+config+"' | base64 -d > '"+s.dir+"/config.pb'\n"
                +identity(s)+" > '"+s.dir+"/identity.txt'\n"
                +"dumpsys window windows > '"+s.dir+"/before-window.txt'\n"
                +"logcat -b all -d -v epoch -t 6000 > '"+s.dir+"/before-logcat.txt'\n"
                +"logcat -b all -v epoch -T 1 -f '"+s.dir+"/system-logcat.txt' -r 1024 -n 3 >/dev/null 2>&1 &\necho $! > '"+s.dir+"/logcat.pid'\n"
                +"(sleep 65; p=$(cat '"+s.dir+"/logcat.pid'); case $p in ''|*[!0-9]*) exit;; esac; [ -r /proc/$p/cmdline ] && tr '\\000' ' ' < /proc/$p/cmdline | grep -Fq '"+s.dir+"/system-logcat.txt' && kill -TERM $p) </dev/null >/dev/null 2>&1 &\n"
                +broadcast(s,true)+"\ncat '"+s.dir+"/identity.txt'\n"
                +(s.sources.isEmpty()?"echo NATIVE_UNSUPPORTED; exit 4\n":"perfetto --background -c '"+s.dir+"/config.pb' -o '"+s.dir+"/window.pftrace' > '"+s.dir+"/perfetto.pid' 2> '"+s.dir+"/perfetto.log'\ncat '"+s.dir+"/perfetto.pid'\n");
        // Descendant pipes must never define readiness or hold the settings worker.
        // Detach one UUID-owned launcher, then inspect its explicit PID/result files.
        String encoded=java.util.Base64.getEncoder().encodeToString(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String readyFile=s.sources.isEmpty()?"logcat.pid":"perfetto.pid";
        String launch="set -e\numask 077\nmkdir '"+s.dir+"'\nprintf '%s' '"+encoded+"' | base64 -d > '"+s.dir+"/launch.sh'\n"
                +"setsid sh '"+s.dir+"/launch.sh' </dev/null > '"+s.dir+"/start-result.txt' 2>&1 &\n"
                +"for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do\n"
                +"if [ -s '"+s.dir+"/"+readyFile+"' ]; then cat '"+s.dir+"/start-result.txt'; echo CAPTURE_READY; exit 0; fi\nsleep 0.2\ndone\n"
                +"cat '"+s.dir+"/start-result.txt'\ncat '"+s.dir+"/perfetto.log' 2>/dev/null || true\nexit 3";
        RuntimeDiagnosticBundle.Part start=RuntimeDiagnosticCollector.command("03-recording-request.txt","60秒系统窗口录制；共享requestId；跨熄屏，不采原始输入或屏幕图像；独立launcher显式就绪",launch,12);
        s.parts.add(start);s.started="OK".equals(start.status)||"MISSING".equals(start.status);
        return s;
    }
    private static String broadcast(Session s,boolean start) {
        return "am broadcast --user 0 -a com.jiusis.mistep.DEBUG -p com.android.systemui --es diagnosticRequest "+s.token+" --ez diagnosticStart "+start+" --es diagnosticBootId \"$(cat /proc/sys/kernel/random/boot_id)\" >/dev/null 2>&1 || true";
    }
    private static String identity(Session s) {
        return "{ echo requestId="+s.token+"; date '+epoch=%s'; printf 'bootId='; cat /proc/sys/kernel/random/boot_id; printf 'elapsedSeconds='; cut -d ' ' -f 1 /proc/uptime; printf 'serverPID='; pidof system_server; printf 'systemUiPID='; pidof com.android.systemui; printf 'zoom='; settings get global mistep_zoom; }";
    }
    static void finish(Context context,Session s) {
        if(s==null||s.finished)return;
        s.finished=true;
        String stop="set +e\n";
        for(String name:new String[]{"perfetto","logcat"}) {
            String match=s.dir+(name.equals("perfetto")?"/config.pb":"/system-logcat.txt");
            stop+="p=$(cat '"+s.dir+"/"+name+".pid' 2>/dev/null); case $p in ''|*[!0-9]*) ;; *) if [ -r /proc/$p/cmdline ] && tr '\\000' ' ' < /proc/$p/cmdline | grep -Fq '"+match+"'; then kill -TERM $p; fi;; esac\n";
        }
        stop+="sleep 1\n"+broadcast(s,false)+"\n"+identity(s)+"\necho BEGIN_IDENTITY\ncat '"+s.dir+"/identity.txt'\ncat '"+s.dir+"/perfetto.log' 2>/dev/null\nls -ln '"+s.dir+"'\ntrue";
        s.parts.add(RuntimeDiagnosticCollector.command("21-system-recording.txt","同请求起止身份、原始文件大小与采集错误；系统日志原件单独入ZIP",stop,12));
        try {
            File folder=new File(context.getCacheDir(),"mistep-native-recordings");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("cache");
            File file=new File(folder,s.token+".pftrace");
            copy(s.dir+"/window.pftrace",file);
            RuntimeTraceFormat.Summary summary;
            try {summary=RuntimeTraceFormat.inspect(file);}catch(IOException unsupported){
                s.attachments.add(new RuntimeDiagnosticBundle.Attachment("23-window.pftrace",file,"UNVERIFIED","原始追踪保留；本地解析失败，不丢弃原件"));
                throw unsupported;
            }
            String report="requestId="+s.token+"\nrequestedSources="+s.sources+"\nbytes="+file.length()+"\n"+summary.text();
            for(String source:List.of(RuntimeTraceFormat.SF,RuntimeTraceFormat.WM,RuntimeTraceFormat.SHELL))
                if(!s.sources.contains(source))report+="UNSUPPORTED_SOURCE="+source+"\n";
            String status=summary.status(s.sources);
            if(s.sources.contains(RuntimeTraceFormat.SHELL)&&summary.shell==0)report+="NO_SHELL_RECORDS=可能无过渡，也可能未产出；不能认定已覆盖Shell过渡\n";
            s.parts.add(new RuntimeDiagnosticBundle.Part("22-native-trace-checks.txt",status,"检查真实来源记录、各钟域覆盖与TraceStats缓冲丢失；不代表故障复现或体验通过",report,0,0));
            s.attachments.add(new RuntimeDiagnosticBundle.Attachment("23-window.pftrace",file,status,"原始WM/SF/Shell追踪；无截图、视频、原始触摸或密码"));
        } catch(Exception error) {
            s.parts.add(new RuntimeDiagnosticBundle.Part("22-native-trace-checks.txt","INCOMPLETE","原生追踪未取得有效数据；不冒称成功","requestId="+s.token+"\n"+error.getClass().getSimpleName()+": "+error.getMessage(),-1,0));
        }
        File folder=new File(context.getCacheDir(),"mistep-native-recordings");
        if(folder.isDirectory()||folder.mkdirs()) {
            int i=29;
            for(String raw:List.of("before-window.txt","before-logcat.txt","system-logcat.txt.3","system-logcat.txt.2","system-logcat.txt.1","system-logcat.txt")) {
                String name=(i++)+"-"+raw.replace(".txt","")+".txt";File file=new File(folder,s.token+"-"+raw);
                try {copy(s.dir+"/"+raw,file);
                    s.attachments.add(new RuntimeDiagnosticBundle.Attachment(name,file,file.length()==0?"EMPTY":"OK","requestId="+s.token+"；原始有界系统记录，日志按4×1MiB滚动，较早记录可能被覆盖"));
                } catch(Exception e) {
                    file.delete();
                    s.parts.add(new RuntimeDiagnosticBundle.Part(name,"MISSING","原件未生成、不可读或超过容量；见21采集错误",e.getClass().getSimpleName()+": "+e.getMessage(),-1,0));
                }
            }
        }
        cleanup(s);
        context.getSharedPreferences("mistep-diagnostic-session",0).edit().remove("token").remove("sources").apply();
    }
    static Session previous(Context context) {
        String token=context.getSharedPreferences("mistep-diagnostic-session",0).getString("token","");
        if(!token.matches("[a-f0-9]{32}"))return null;
        Session s=new Session(token);
        String raw=context.getSharedPreferences("mistep-diagnostic-session",0).getString("sources","");
        s.sources=new LinkedHashSet<>(Arrays.asList(raw.split(",")));s.sources.remove("");
        return s;
    }
    private static void copy(String remote,File local) throws Exception {
        Process p=new ProcessBuilder("su","-c","cat '"+remote+"'").redirectError(new File("/dev/null")).start();
        Thread watchdog=new Thread(()->{try{Thread.sleep(15000);p.destroyForcibly();}catch(InterruptedException ignored){}},"MiStep-trace-copy-deadline");watchdog.setDaemon(true);watchdog.start();
        try(InputStream in=p.getInputStream();OutputStream out=new BufferedOutputStream(new FileOutputStream(local))) {
            byte[] b=new byte[32768];int n;long total=0;while((n=in.read(b))!=-1){total+=n;if(total>80L*1024*1024)throw new IOException("size limit");out.write(b,0,n);}
            if(!p.waitFor(1,TimeUnit.SECONDS)||p.exitValue()!=0)throw new IOException("root trace read");
        } finally {watchdog.interrupt();if(p.isAlive())p.destroy();}
    }
    private static void cleanup(Session s) {
        // Fixed files in one UUID-owned directory only. Never recursive/global removal.
        String script="";
        for(String f:List.of("launch.sh","start-result.txt","config.pb","window.pftrace","identity.txt","before-window.txt","before-logcat.txt","system-logcat.txt","system-logcat.txt.1","system-logcat.txt.2","system-logcat.txt.3","logcat.pid","perfetto.pid","perfetto.log"))script+="rm -f '"+s.dir+"/"+f+"'\n";
        RuntimeDiagnosticCollector.command("cleanup.txt","只清本次自有固定临时文件",script+"rmdir '"+s.dir+"'",5);
    }
}
