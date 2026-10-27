package com.jianguo.mistep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded LRC timeline, independent of player package or Android UI. */
final class MediaLyrics {
    static final MediaLyrics EMPTY = new MediaLyrics(new long[0], new String[0], "");
    private static final Pattern TIME = Pattern.compile("\\[(?:(\\d{1,3}):)?(\\d{1,4}):(\\d{2})(?:[.,:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:([+-]?\\d{1,9})\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TIME = Pattern.compile("<(?:(?:\\d{1,3}):)?\\d{1,4}:\\d{2}(?:[.,:]\\d{1,3})?>");
    private static final Pattern KRC_LINE = Pattern.compile("^\\[(\\d{1,12}),(\\d{1,12})\\](.*)$");
    private static final Pattern KRC_WORD_TIME = Pattern.compile("<[^>]{1,64}>");
    private final long[] times;
    private final String[] lines;
    private final String plain;
    private MediaLyrics(long[] times, String[] lines, String plain) {
        this.times=times; this.lines=lines; this.plain=plain;
    }
    static boolean isLyricKey(String key) {
        if (key == null) return false;
        String name=key.toLowerCase(Locale.ROOT);
        if (!(name.contains("lyric") || name.equals("lrc") || name.endsWith(".lrc") || name.endsWith("_lrc"))) return false;
        for (String other:new String[]{"url","uri","path","offset","enabled","has_","show_","provider","copyright"})
            if (name.contains(other)) return false;
        return true;
    }
    static boolean isLyricText(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower=text.toLowerCase(Locale.ROOT);
        return !(lower.startsWith("http:") || lower.startsWith("https:") || lower.startsWith("content:")
                || lower.startsWith("file:") || lower.startsWith("/") || lower.equals("true") || lower.equals("false")
                || lower.matches("[+-]?\\d+"));
    }
    private static final class Line {
        long time; String text;
        Line(long time, String text) { this.time=time; this.text=text; }
    }
    static final class Window {
        final String text;
        final int currentLine;
        final int lineCount;
        Window(String text,int currentLine,int lineCount) {
            this.text=text;this.currentLine=currentLine;this.lineCount=lineCount;
        }
    }
    static MediaLyrics parse(String source) {
        if (source == null || source.isEmpty()) return EMPTY;
        String raw=source.substring(0,Math.min(source.length(),262144)).replace("\ufeff", "");
        MediaLyrics krc=parseKrc(raw);
        if(krc!=null)return krc;
        long offset=0;
        Matcher adjustment=OFFSET.matcher(raw);
        if (adjustment.find()) offset=Long.parseLong(adjustment.group(1));
        ArrayList<Line> parsed=new ArrayList<>();
        for (String row:raw.split("\\r?\\n",4097)) {
            row=row.trim();
            Matcher tags=TIME.matcher(row);
            ArrayList<Long> stamps=new ArrayList<>();
            int end=0;
            while (tags.find() && tags.start()==end && stamps.size()<8) {
                int seconds=Integer.parseInt(tags.group(3));
                if (seconds>=60) break;
                long hours=tags.group(1)==null?0:Long.parseLong(tags.group(1));
                long minutes=Long.parseLong(tags.group(2));
                String fraction=tags.group(4);
                long ms=fraction==null?0:Integer.parseInt((fraction+"00").substring(0,3));
                stamps.add(Math.max(0,(hours*3600+minutes*60+seconds)*1000+ms+offset));
                end=tags.end();
            }
            String text=limit(WORD_TIME.matcher(row.substring(end)).replaceAll("").trim());
            for (long stamp:stamps) {
                if (parsed.size()>=4096) break;
                parsed.add(new Line(stamp,text));
            }
            if (parsed.size()>=4096) break;
        }
        if (parsed.isEmpty()) {
            String text=raw.trim();
            if (text.startsWith("[") || text.startsWith("{") || text.startsWith("<")) return EMPTY;
            return new MediaLyrics(new long[0],new String[0],limit(text));
        }
        parsed.sort(Comparator.comparingLong(line->line.time));
        ArrayList<Line> unique=new ArrayList<>();
        for (Line line:parsed) {
            if (!unique.isEmpty() && unique.get(unique.size()-1).time==line.time) {
                Line previous=unique.get(unique.size()-1);
                if (!line.text.isEmpty() && !("\n"+previous.text+"\n").contains("\n"+line.text+"\n"))
                    previous.text=limit(previous.text.isEmpty()?line.text:previous.text+"\n"+line.text);
            } else unique.add(line);
        }
        long[] times=new long[unique.size()]; String[] lines=new String[unique.size()];
        for (int i=0;i<times.length;i++) { times[i]=unique.get(i).time; lines[i]=unique.get(i).text; }
        return new MediaLyrics(times,lines,"");
    }
    private static MediaLyrics parseKrc(String raw) {
        ArrayList<Line> parsed=new ArrayList<>();
        for(String row:raw.split("\\r?\\n",4097)) {
            Matcher match=KRC_LINE.matcher(row.trim());
            if(!match.matches())continue;
            long time;
            try {time=Long.parseLong(match.group(1));}catch(NumberFormatException error){continue;}
            String text=limit(KRC_WORD_TIME.matcher(match.group(3)).replaceAll("").trim());
            parsed.add(new Line(Math.max(0,time),text));
            if(parsed.size()>=4096)break;
        }
        if(parsed.isEmpty())return null;
        parsed.sort(Comparator.comparingLong(line->line.time));
        ArrayList<Line> unique=new ArrayList<>();
        for(Line line:parsed) {
            if(!unique.isEmpty()&&unique.get(unique.size()-1).time==line.time) {
                Line previous=unique.get(unique.size()-1);
                if(!line.text.isEmpty()&&!("\n"+previous.text+"\n").contains("\n"+line.text+"\n"))
                    previous.text=limit(previous.text.isEmpty()?line.text:previous.text+"\n"+line.text);
            } else unique.add(line);
        }
        long[] times=new long[unique.size()];String[] lines=new String[unique.size()];
        for(int i=0;i<times.length;i++){times[i]=unique.get(i).time;lines[i]=unique.get(i).text;}
        return new MediaLyrics(times,lines,"");
    }
    private static String limit(String text) {
        int n=Math.min(512,text.length());
        if (n<text.length() && n>0 && Character.isHighSurrogate(text.charAt(n-1))) n--;
        return text.substring(0,n);
    }
    boolean hasLyrics() { return times.length>0 || !plain.isEmpty(); }
    private int index(long position) {
        int low=0,high=times.length;
        while(low<high) {int mid=(low+high)>>>1;if(times[mid]<=position)low=mid+1;else high=mid;}
        return low-1;
    }
    String lineAt(long position) {int at=index(Math.max(0,position));return times.length==0?plain:at<0?"":lines[at];}
    Window windowAt(long position,int maxLines) {
        int wanted=Math.max(1,Math.min(9,maxLines));
        if(times.length==0) {
            if(plain.isEmpty())return new Window("",0,0);
            String[] rows=limit(plain).split("\\r?\\n",wanted+1);
            int count=Math.min(wanted,rows.length);
            return new Window(String.join("\n",java.util.Arrays.copyOf(rows,count)),count==1?0:Math.min(count-1,count/2),count);
        }
        ArrayList<Integer> visible=new ArrayList<>();
        for(int i=0;i<lines.length&&visible.size()<4096;i++)if(!lines[i].isEmpty())visible.add(i);
        if(visible.isEmpty())return new Window("",0,0);
        int at=index(Math.max(0,position));
        int currentVisible=0;
        for(int i=0;i<visible.size();i++) {
            if(visible.get(i)<=at)currentVisible=i;
            else break;
        }
        int count=Math.min(wanted,visible.size());
        int start=Math.max(0,currentVisible-count/2);
        if(start+count>visible.size())start=visible.size()-count;
        StringBuilder text=new StringBuilder();
        for(int i=0;i<count;i++) {
            if(i>0)text.append('\n');
            text.append(lines[visible.get(start+i)]);
        }
        return new Window(text.toString(),currentVisible-start,count);
    }
    long nextDelay(long position, float speed, boolean playing) {
        if (!playing || !Float.isFinite(speed) || speed<=0 || times.length==0) return -1;
        int next=index(Math.max(0,position))+1;
        if(next>=times.length)return -1;
        return Math.max(16, (long)Math.ceil((times[next]-Math.max(0,position))/(double)speed));
    }
    static long position(long base, long updated, long now, float speed, boolean playing) {
        if (!playing || updated<=0 || !Float.isFinite(speed) || speed<=0) return Math.max(0,base);
        double value=Math.max(0,base)+Math.max(0,now-updated)*(double)speed;
        return (long)Math.max(0,Math.min(Long.MAX_VALUE,value));
    }
}
