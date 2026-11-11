package com.jiusis.mistep;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;

public final class RuntimeTraceFormatTest {
    static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    static byte[] concat(byte[]... values)throws Exception {var b=new ByteArrayOutputStream();for(byte[] v:values)b.write(v);return b.toByteArray();}
    static void varint(ByteArrayOutputStream b,long v){do{int n=(int)(v&127);v>>>=7;b.write(n|(v==0?0:128));}while(v!=0);}
    static byte[] number(int f,long v){var b=new ByteArrayOutputStream();varint(b,(long)f<<3);varint(b,v);return b.toByteArray();}
    static byte[] blob(int f,byte[] v)throws Exception {var b=new ByteArrayOutputStream();varint(b,((long)f<<3)|2);varint(b,v.length);b.write(v);return b.toByteArray();}
    static byte[] packet(int source,long stamp,long clock,byte[] content)throws Exception{return blob(1,concat(number(8,stamp),blob(source,content),number(58,clock)));}
    public static void main(String[] args)throws Exception {
        if(args.length==3&&args[0].equals("--config")){
            Files.write(Path.of(args[2]),RuntimeTraceFormat.config(RuntimeTraceFormat.supported(Files.readString(Path.of(args[1]))),4000));return;
        }
        if(args.length==2&&args[0].equals("--inspect")){
            var s=RuntimeTraceFormat.inspect(new File(args[1]));System.out.print(s.text());check(s.hasGeometry());return;
        }
        String sf="    android.surfaceflinger.layers (descriptor)\n";
        check(RuntimeTraceFormat.supported(sf).equals(Set.of(RuntimeTraceFormat.SF)));
        check(RuntimeTraceFormat.supported("error mentioning android.surfaceflinger.layers\nandroid.surfaceflinger.layers.invalid\n").isEmpty());
        check(RuntimeTraceFormat.supported(sf+"android.windowmanager\ncom.android.wm.shell.transition\n").size()==3);
        for(int duration:new int[]{0,999,60001})try{RuntimeTraceFormat.config(Set.of(),duration);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        try{RuntimeTraceFormat.config(Set.of("android.input.inputevent"),60000);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        byte[] config=RuntimeTraceFormat.config(Set.of(RuntimeTraceFormat.SF),60000);
        check(!new String(config,java.nio.charset.StandardCharsets.ISO_8859_1).contains("android.input"));
        File trace=Files.createTempFile("mistep-diagnostic-test",".pftrace").toFile();
        try {
            byte[] twoSf=concat(packet(93,100,6,new byte[0]),packet(93,200,6,new byte[0]));
            byte[] actualWm=packet(112,150,6,blob(6,blob(3,new byte[0])));
            byte[] otherWinscope=packet(112,250,6,blob(7,new byte[0]));
            byte[] noLoss=packet(35,300,6,blob(1,concat(number(12,65536),number(4,5))));
            Files.write(trace.toPath(),concat(twoSf,actualWm,otherWinscope,noLoss,packet(96,10000,1,new byte[0])));
            var parsed=RuntimeTraceFormat.inspect(trace);
            check(parsed.sf==2&&parsed.wm==1&&parsed.shell==1);
            check(parsed.status(Set.of(RuntimeTraceFormat.WM)).equals("OK")&&!parsed.hasLoss());
            check(parsed.sourceTimes.get(RuntimeTraceFormat.SF).get(6L)[0]==100);
            check(parsed.sourceTimes.get(RuntimeTraceFormat.SF).get(6L)[1]==200);
            check(parsed.sourceTimes.get(RuntimeTraceFormat.SHELL).get(1L)[0]==10000);
            check(!parsed.sourceTimes.get(RuntimeTraceFormat.SF).containsKey(1L));
            check(parsed.text().contains("NO_REPORTED_BUFFER_LOSS")&&parsed.text().contains("writeWrapCount=5"));
            Files.write(trace.toPath(),concat(twoSf,otherWinscope,noLoss));
            check(RuntimeTraceFormat.inspect(trace).status(Set.of(RuntimeTraceFormat.WM)).equals("INCOMPLETE"));
            for(int field:new int[]{13,3,18,19,6,9,22}){
                byte[] loss=packet(35,300,6,blob(1,number(field,123)));
                Files.write(trace.toPath(),concat(twoSf,loss,loss));
                parsed=RuntimeTraceFormat.inspect(trace);
                check(parsed.hasLoss()&&parsed.status(Set.of()).equals("PARTIAL"));
                check(parsed.buffers.get(0).get(field)==123); // cumulative snapshots must not be added.
            }
            Files.write(trace.toPath(),concat(twoSf,packet(35,300,6,number(8,1))));
            check(RuntimeTraceFormat.inspect(trace).hasLoss());
            Files.write(trace.toPath(),twoSf);
            check(RuntimeTraceFormat.inspect(trace).status(Set.of()).equals("UNVERIFIED"));
            var zipped=new ByteArrayOutputStream();
            try(var z=new DeflaterOutputStream(zipped)){z.write(concat(twoSf,actualWm,noLoss));}
            Files.write(trace.toPath(),blob(1,blob(50,zipped.toByteArray())));
            parsed=RuntimeTraceFormat.inspect(trace);check(parsed.sf==2&&parsed.wm==1&&parsed.stats==1);
            for(byte[] bad:List.of(new byte[0],new byte[]{10,5,1},new byte[]{1,0},new byte[]{10,2,0,0})) {
                Files.write(trace.toPath(),bad);
                try{RuntimeTraceFormat.inspect(trace);throw new AssertionError();}catch(IOException expected){checks++;}
            }
            // Verify binary evidence is streamed unchanged, indexed by real bytes and SHA.
            byte[] evidence=new byte[100003];new Random(173).nextBytes(evidence);Files.write(trace.toPath(),evidence);
            var attachment=new RuntimeDiagnosticBundle.Attachment("23-window.pftrace",trace,"INCOMPLETE","opaque original evidence");
            var b=new RuntimeDiagnosticBundle("now",List.of(),List.of(attachment)).withDescription("原件校验");
            ByteArrayOutputStream out=new ByteArrayOutputStream();b.writeZip(out);
            Map<String,byte[]> entries=new HashMap<>();
            try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))){ZipEntry e;while((e=z.getNextEntry())!=null)entries.put(e.getName(),z.readAllBytes());}
            check(Arrays.equals(evidence,entries.get("23-window.pftrace")));
            Properties index=new Properties();index.load(new InputStreamReader(new ByteArrayInputStream(entries.get("manifest.properties")),java.nio.charset.StandardCharsets.UTF_8));
            check(index.getProperty("schema").equals("mistep-diagnostics-2"));
            check(index.getProperty("part.1.bytes").equals(""+evidence.length));
            check(index.getProperty("part.1.sha256").equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence))));
            check(index.getProperty("part.1.status").equals("INCOMPLETE"));
            check(b.withDescription("保持原件").attachments.size()==1);
            try{new RuntimeDiagnosticBundle.Attachment("../outside.pftrace",trace,"OK","");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        }finally{trace.delete();}
        String boot="01234567-0123-0123-0123-012345678901";
        var process=new RuntimeDiagnosticBundle.Part("04-processes.txt","OK","","bootId="+boot+"\nserverPID=41\nsystemUiPID=52\nversionName=test\n",0,0);
        var server=new RuntimeDiagnosticBundle.Part("24-loaded-server.txt","OK","","bootId="+boot+"\npid=41\n=== attached to system_server (android) build=test end ===\n",0,0);
        var ui=new RuntimeDiagnosticBundle.Part("25-loaded-systemui.txt","OK","","bootId="+boot+"\npid=52\n=== attached to com.android.systemui build=test end ===\n",0,0);
        check(RuntimeDiagnosticBundle.analyze(List.of(process,server,ui)).status.equals("OK"));
        var stale=new RuntimeDiagnosticBundle.Part(ui.name,"OK","",ui.text.replace(boot,"different-boot"),0,0);
        check(RuntimeDiagnosticBundle.analyze(List.of(process,server,stale)).status.equals("INCOMPLETE"));
        var wrongVersion=new RuntimeDiagnosticBundle.Part(ui.name,"OK","",ui.text.replace("build=test ","build=old "),0,0);
        check(RuntimeDiagnosticBundle.analyze(List.of(process,server,wrongVersion)).status.equals("INCOMPLETE"));
        System.out.println("RuntimeTraceFormat checks="+checks+" passed");
    }
}
