package com.jiusis.mistep;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** Minimal stable protobuf wire encoding; no text-config parser or downloaded runtime. */
final class RuntimeTraceFormat {
    static final String SF = "android.surfaceflinger.layers";
    static final String WM = "android.windowmanager";
    static final String SHELL = "com.android.wm.shell.transition";
    static Set<String> supported(String query) {
        Set<String> found = new LinkedHashSet<>();
        for (String name : new String[]{SF, WM, SHELL})
            if (java.util.regex.Pattern.compile("(?m)^\\s*" + java.util.regex.Pattern.quote(name) + "(?:\\s|$)").matcher(query).find()) found.add(name);
        return found;
    }
    static byte[] config(Set<String> sources, int durationMs) {
        if (durationMs < 1000 || durationMs > 60000) throw new IllegalArgumentException("duration");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        blob(out, 1, numbers(1, 65536, 4, 1)); // 64 MiB ring, caller-enabled only.
        blob(out, 1, numbers(1, 8192, 4, 1));
        number(out, 3, durationMs);
        for (String name : sources) {
            if (!List.of(SF, WM, SHELL).contains(name)) throw new IllegalArgumentException("source");
            ByteArrayOutputStream config = new ByteArrayOutputStream();
            blob(config, 1, name.getBytes(StandardCharsets.UTF_8));
            number(config, 2, name.equals(SF) ? 0 : 1);
            // DataSourceConfig.surfaceflinger_layers_config=121; ACTIVE=1.
            // Input is window geometry only, never android.input.inputevent.
            if (name.equals(SF)) blob(config, 121, numbers(1, 1, 2, 2, 2, 4));
            if (name.equals(WM)) blob(config, 130, numbers(1, 1, 2, 2, 3, 1)); // FRAME / DEBUG / full state.
            blob(out, 2, message(1, config.toByteArray()));
        }
        return out.toByteArray();
    }
    private static byte[] numbers(long... pairs) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i=0;i<pairs.length;i+=2) number(b,(int)pairs[i],pairs[i+1]);
        return b.toByteArray();
    }
    private static byte[] message(int field, byte[] value) { ByteArrayOutputStream b=new ByteArrayOutputStream(); blob(b,field,value); return b.toByteArray(); }
    private static void varint(ByteArrayOutputStream b,long n) { do { int v=(int)(n&127); n>>>=7; b.write(v|(n==0?0:128)); } while(n!=0); }
    private static void number(ByteArrayOutputStream b,int f,long n) { varint(b,(long)f<<3);varint(b,n); }
    private static void blob(ByteArrayOutputStream b,int f,byte[] v) { varint(b,((long)f<<3)|2);varint(b,v.length);b.write(v,0,v.length); }

    static final class Summary {
        long packets,sf,wm,shell,clocks,stats,dropFlags;
        long discardedChunks;
        final Map<Long,long[]> times = new TreeMap<>();
        final Map<String,Map<Long,long[]>> sourceTimes = new TreeMap<>();
        final Map<Integer,Map<Integer,Long>> buffers = new TreeMap<>();
        boolean hasGeometry() { return sf > 0; }
        boolean hasLoss() {
            if (discardedChunks > 0) return true;
            for (Map<Integer,Long> buffer : buffers.values())
                for (int field : new int[]{13,3,18,19,6,9,22})
                    if (buffer.getOrDefault(field,0L) > 0) return true;
            return false;
        }
        String status(Set<String> requested) {
            if (sf == 0 || requested.contains(WM) && wm == 0) return "INCOMPLETE";
            if (hasLoss()) return "PARTIAL";
            if (sf == 1) return "SNAPSHOT_ONLY";
            return buffers.isEmpty() ? "UNVERIFIED" : "OK";
        }
        void time(String source,long clock,long stamp) {
            if (stamp < 0) return;
            range(sourceTimes.computeIfAbsent(source,k->new TreeMap<>()),clock,stamp);
        }
        String text() {
            StringBuilder b=new StringBuilder("packets="+packets+"\nsfSnapshots="+sf+"\nwmRecords="+wm+"\nshellRecords="+shell+"\nclockSnapshots="+clocks+"\ntraceStatsPackets="+stats+"\npacketDropFlagPackets="+dropFlags+"\n");
            b.append("bufferCoverage=").append(hasLoss()?"PARTIAL":buffers.isEmpty()?"UNKNOWN":"NO_REPORTED_BUFFER_LOSS").append('\n');
            b.append("serviceDiscardedChunks=").append(discardedChunks).append('\n');
            buffers.forEach((index,values)->{
                b.append("buffer=").append(index);
                values.forEach((field,value)->b.append(' ').append(statName(field)).append('=').append(value));
                b.append('\n');
            });
            times.forEach((clock,t)->b.append("clockId=").append(clock).append(" firstNs=").append(t[0]).append(" lastNs=").append(t[1]).append('\n'));
            sourceTimes.forEach((source,byClock)->byClock.forEach((clock,t)->b.append("source=").append(source).append(" clockId=").append(clock).append(" firstNs=").append(t[0]).append(" lastNs=").append(t[1]).append(" records=").append(t[2]).append('\n')));
            return b+"原始ClockSnapshot和TraceStats保留在trace中；不同clockId不得直接相减。\n覆盖丢失、缺少统计或缺少请求来源会明确降级；无已报告丢失也不证明覆盖整个故障。\n系统logcat独立按4×1MiB滚动，可能覆盖早期记录；trace的覆盖状态不代表logcat完整。\npacketDropFlag包含会话初始化序列状态，不能直接当丢帧数；wrap次数本身不等于数据丢失。\n";
        }
    }
    private static void range(Map<Long,long[]> ranges,long clock,long stamp) {
        long[] t=ranges.get(clock);
        if(t==null)ranges.put(clock,new long[]{stamp,stamp,1});
        else {t[0]=Math.min(t[0],stamp);t[1]=Math.max(t[1],stamp);t[2]++;}
    }
    private static String statName(int field) {
        switch(field) {
            case 12:return "bufferSize";case 1:return "bytesWritten";case 13:return "bytesOverwritten";
            case 14:return "bytesRead";case 2:return "chunksWritten";case 3:return "chunksOverwritten";
            case 18:return "chunksDiscarded";case 17:return "chunksRead";case 4:return "writeWrapCount";
            case 19:return "writerPacketLoss";case 6:return "patchesFailed";case 9:return "abiViolations";
            case 22:return "oversizedPacketsDropped";default:return "field"+field;
        }
    }
    static Summary inspect(File file) throws IOException {
        if (!file.isFile() || file.length()==0 || file.length()>80L*1024*1024) throw new IOException("trace size");
        Summary s=new Summary();
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))) { trace(in,s,0); }
        if(s.packets==0) throw new IOException("No TracePacket");
        return s;
    }
    private static long read(InputStream in) throws IOException {
        long n=0;for(int shift=0;shift<64;shift+=7) {int x=in.read();if(x<0)throw new EOFException();n|=(long)(x&127)<<shift;if((x&128)==0)return n;}throw new IOException("varint");
    }
    private static byte[] bytes(InputStream in,long n) throws IOException {
        if(n<0||n>8*1024*1024)throw new IOException("packet size");
        byte[] b=in.readNBytes((int)n);if(b.length!=n)throw new EOFException();return b;
    }
    private static void skip(InputStream in,long n) throws IOException { if(n<0)throw new IOException("negative size");while(n>0){long k=in.skip(n);if(k==0){if(in.read()<0)throw new EOFException();k=1;}n-=k;} }
    private static void trace(InputStream in,Summary s,int depth) throws IOException {
        if(depth>2)throw new IOException("compression depth");
        while(true) {int first=in.read();if(first<0)return;
            if(first!=10)throw new IOException("Trace.packet tag");
            byte[] packet=bytes(in,read(in));s.packets++;packet(packet,s,depth);
        }
    }
    private static void packet(byte[] b,Summary s,int depth) throws IOException {
        InputStream in=new ByteArrayInputStream(b);long stamp=-1,clock=6;
        boolean sf=false,wm=false,shell=false;
        while(in.available()>0) {long tag=read(in);int field=(int)(tag>>>3),wire=(int)(tag&7);
            if(field==0)throw new IOException("field zero");
            if(wire==0) {long v=read(in);if(field==8)stamp=v;if(field==58)clock=v;if(field==42&&v!=0)s.dropFlags++;}
            else if(wire==2) {long n=read(in);
                if(field==50) {byte[] compressed=bytes(in,n);try(InputStream z=new InflaterInputStream(new ByteArrayInputStream(compressed))){trace(z,s,depth+1);}}
                else if(field==112) {wm=windowManager(bytes(in,n))||wm;}
                else if(field==35) {s.stats++;traceStats(bytes(in,n),s);}
                else {if(field==93)sf=true;if(field==96)shell=true;if(field==6)s.clocks++;skip(in,n);}
            } else if(wire==1)skip(in,8);else if(wire==5)skip(in,4);else throw new IOException("wire");
        }
        if(stamp>=0)range(s.times,clock,stamp);
        if(sf){s.sf++;s.time(SF,clock,stamp);}if(wm){s.wm++;s.time(WM,clock,stamp);}if(shell){s.shell++;s.time(SHELL,clock,stamp);}
    }
    // TracePacket.winscope_extensions(112) -> windowmanager(6) -> window_manager_service(3).
    private static boolean windowManager(byte[] bytes) throws IOException {
        InputStream in=new ByteArrayInputStream(bytes);boolean found=false;
        while(in.available()>0){long tag=read(in);int field=(int)(tag>>>3),wire=(int)(tag&7);
            if(field==6&&wire==2)found=containsMessage(bytes(in,read(in)),3)||found;
            else skipField(in,field,wire);
        }return found;
    }
    private static boolean containsMessage(byte[] bytes,int expected) throws IOException {
        InputStream in=new ByteArrayInputStream(bytes);boolean found=false;
        while(in.available()>0){long tag=read(in);int field=(int)(tag>>>3),wire=(int)(tag&7);
            if(field==expected&&wire==2){skip(in,read(in));found=true;}else skipField(in,field,wire);
        }return found;
    }
    private static void traceStats(byte[] bytes,Summary s) throws IOException {
        InputStream in=new ByteArrayInputStream(bytes);int index=0;
        while(in.available()>0){long tag=read(in);int field=(int)(tag>>>3),wire=(int)(tag&7);
            if(field==1&&wire==2){
                InputStream buffer=new ByteArrayInputStream(bytes(in,read(in)));
                Map<Integer,Long> values=s.buffers.computeIfAbsent(index++,k->new TreeMap<>());
                while(buffer.available()>0){long t=read(buffer);int f=(int)(t>>>3),w=(int)(t&7);
                    if(w==0){long v=read(buffer);if(List.of(12,1,13,14,2,3,18,17,4,19,6,9,22).contains(f))values.merge(f,v,Math::max);}
                    else skipField(buffer,f,w);
                }
            }else if(field==8&&wire==0)s.discardedChunks=Math.max(s.discardedChunks,read(in));
            else skipField(in,field,wire);
        }
    }
    private static void skipField(InputStream in,int field,int wire) throws IOException {
        if(field==0)throw new IOException("field zero");
        if(wire==0)read(in);else if(wire==2)skip(in,read(in));else if(wire==1)skip(in,8);
        else if(wire==5)skip(in,4);else throw new IOException("wire");
    }
}
