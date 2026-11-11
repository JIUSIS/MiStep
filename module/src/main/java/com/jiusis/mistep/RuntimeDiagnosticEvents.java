package com.jiusis.mistep;

import android.content.*;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;

/** Bounded state events; never captures MotionEvent, credentials or frame buffers. */
final class RuntimeDiagnosticEvents {
    private static final ScheduledExecutorService IO=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"MiStep-diagnostic-events");t.setDaemon(true);return t;});
    private static final StringBuilder events=new StringBuilder();
    private static String marker="",boot="UNKNOWN",role="",request="NONE";
    private static File directory;
    private static boolean pending,initialized;
    static synchronized void loaded(String text) { if(text.startsWith("=== attached to ")){marker=text;flushSoon();} }
    static synchronized void initialize(Context context,boolean server) {
        if(initialized)return;initialized=true;role=server?"system_server":"com.android.systemui";
        directory=server?new File("/data/system/mistep-diagnostics"):new File(context.createDeviceProtectedStorageContext().getFilesDir(),"mistep-diagnostics");
        IO.execute(()->{try{String id=Files.readString(new File("/proc/sys/kernel/random/boot_id").toPath()).trim();synchronized(RuntimeDiagnosticEvents.class){boot=id;flushSoon();}}catch(Exception ignored){}});
        event("PROCESS_CONTEXT_READY");
        try { IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_USER_PRESENT);
            context.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){
                String state="";
                try {android.os.PowerManager p=c.getSystemService(android.os.PowerManager.class);
                    android.app.KeyguardManager k=c.getSystemService(android.app.KeyguardManager.class);
                    state=" interactive="+p.isInteractive()+" keyguardLocked="+k.isKeyguardLocked()
                            +" zoom="+android.provider.Settings.Global.getString(c.getContentResolver(),"mistep_zoom");
                }catch(Exception ignored){}
                event("LIFECYCLE action="+i.getAction()+state);
            }},filter,Context.RECEIVER_NOT_EXPORTED);
        } catch(Exception e){event("LIFECYCLE_RECEIVER_FAILED "+e);}
    }
    static synchronized void event(String text) {
        if(text.length()>1800)text=text.substring(0,1800);
        events.append("epochMs=").append(System.currentTimeMillis()).append(" elapsedMs=").append(SystemClock.elapsedRealtime()).append(" uptimeMs=").append(SystemClock.uptimeMillis()).append(" requestId=").append(request).append(' ').append(text.replace('\n',' ')).append('\n');
        if(events.length()>65536)events.delete(0,events.length()-65536);flushSoon();
    }
    static synchronized void request(String token, boolean start,String bootId) {
        if(token==null||!token.matches("[a-f0-9]{32}"))return;
        if(bootId!=null&&bootId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {boot=bootId;flushSoon();}
        if(start){request=token;event("REQUEST_BEGIN");}
        else if(request.equals(token)){event("REQUEST_END");request="NONE";}
    }
    static void fromHostLog(String text) {
        loaded(text);String low=text.toLowerCase(java.util.Locale.ROOT);
        if(low.contains("zoom")||low.contains("lock")||low.contains("retain")||low.contains("restor")||low.contains("failed")||low.contains("unavailable")||low.contains("refused")||low.contains("timeout")||low.contains("back "))event(text);
    }
    private static synchronized void flushSoon() { if(pending||directory==null)return;pending=true;IO.schedule(RuntimeDiagnosticEvents::flush,500,TimeUnit.MILLISECONDS); }
    private static void flush() {
        File dir;String identity,body;
        synchronized(RuntimeDiagnosticEvents.class){pending=false;dir=directory;identity="role="+role+"\npid="+android.os.Process.myPid()+"\nuid="+android.os.Process.myUid()+"\nbootId="+boot+"\n"+marker+"\n";body=events.toString();}
        if(dir==null)return;
        try{if(!dir.isDirectory()&&!dir.mkdirs())return;write(dir,"loaded.txt",identity);write(dir,"events.txt",identity+"BOUNDED_RECENT_EVENTS maxChars=65536; earlier events may be overwritten\n"+body);}catch(Exception ignored){}
    }
    private static void write(File dir,String name,String text) throws Exception {File temp=new File(dir,name+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(text.getBytes(StandardCharsets.UTF_8));}if(!temp.renameTo(new File(dir,name)))temp.delete();}
}
