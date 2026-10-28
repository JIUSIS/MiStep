package com.jiusis.mistep;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import java.util.function.Consumer;

/** Public, bounded lyric fallback; never blocks the UI or reads a player's private storage. */
final class MediaLyricLookup {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"MiStepLyrics");t.setDaemon(true);return t;
    });
    private static final LinkedHashMap<String,String> CACHE=new LinkedHashMap<>(16,.75f,true);
    private static volatile Consumer<String> diagnostics;
    private final Executor delivery;
    private final String title,artist,packageName,mediaId,key;
    private final long duration;
    private volatile boolean cancelled;
    private volatile HttpURLConnection connection;
    private Future<?> future;
    static void setDiagnostics(Consumer<String> sink) {diagnostics=sink;}
    private MediaLyricLookup(Executor delivery,String title,String artist,long duration,String pkg,String mediaId) {
        this.delivery=delivery;this.title=limit(title);this.artist=limit(artist);this.duration=duration;
        this.packageName=pkg==null?"":pkg;this.mediaId=limit(mediaId);
        this.key=this.title+'\n'+this.artist+'\n'+duration+'\n'+this.packageName+'\n'+this.mediaId;
    }
    static MediaLyricLookup start(Executor delivery,String title,String artist,long duration,String pkg,String mediaId,Consumer<String> result) {
        MediaLyricLookup task=new MediaLyricLookup(delivery,title,artist,duration,pkg,mediaId);
        task.future=WORKER.submit(()->{
            String lyrics="";
            synchronized(CACHE) {lyrics=CACHE.get(task.key);}
            if(lyrics==null) {
                lyrics=task.fetch();
                if(!lyrics.isEmpty()&&!task.cancelled)synchronized(CACHE) {
                    CACHE.put(task.key,lyrics);
                    while(CACHE.size()>12)CACHE.remove(CACHE.keySet().iterator().next());
                }
            }
            String resolved=lyrics;
            if(!task.cancelled)task.delivery.execute(()->{if(!task.cancelled)result.accept(resolved);});
        });
        return task;
    }
    void cancel() {
        cancelled=true;
        HttpURLConnection c=connection;if(c!=null)c.disconnect();
        Future<?> f=future;if(f!=null)f.cancel(true);
    }
    private static String limit(String text) {return text==null?"":text.substring(0,Math.min(256,text.length())).trim();}
    private static String encode(String text) {return URLEncoder.encode(text,StandardCharsets.UTF_8);}
    private String fetch() {
        // Soda's MediaSession id is the same public Luna track id used by its
        // search/detail API. This path runs in SystemUI and never injects Soda.
        if(packageName.equals("com.luna.music")&&mediaId.matches("[0-9]{1,20}")) {
            String direct=qishuiById(mediaId);
            if(!direct.isEmpty())return direct;
        }
        if(title.isEmpty()||artist.isEmpty()||duration<=0||cancelled)return "";
        if(packageName.equals("com.luna.music")) {
            String soda=qishuiSearch();
            if(!soda.isEmpty())return soda;
        }
        if(cancelled)return "";
        if(packageName.equals("com.kugou.android")||packageName.equals("com.kugou.android.lite")) {
            String kugou=kugouSearch();
            if(!kugou.isEmpty())return kugou;
        }
        if(cancelled)return "";
        // NetEase's numeric media ID belongs to its own catalog, not another player's ID.
        if(packageName.equals("com.netease.cloudmusic")&&mediaId.matches("[0-9]{1,15}")) {
            String direct=neteaseById(mediaId);
            if(!direct.isEmpty())return direct;
        }
        if(cancelled)return "";
        // The same catalog also serves the other players and is substantially broader for
        // Chinese and soundtrack releases than the legacy KuGou lyric search.
        String netease=neteaseSearch();
        if(!netease.isEmpty())return netease;
        if(cancelled)return "";
        String kugou=kugouSearch();
        if(!kugou.isEmpty())return kugou;
        if(cancelled)return "";
        try {
            JSONArray rows=new JSONArray(get("https://lrclib.net/api/search?track_name="+encode(title)+"&artist_name="+encode(artist)));
            for(int i=0;i<Math.min(20,rows.length());i++) {
                JSONObject row=rows.optJSONObject(i);
                if(row==null||!MediaLyricMatch.matches(title,artist,duration,row.optString("trackName"),
                        row.optString("artistName"),Math.round(row.optDouble("duration")*1000)))continue;
                String raw=row.optString("syncedLyrics","");
                if(usable(raw))return raw;
            }
        } catch(Exception error) {note("lrclib lyric",error);}
        return "";
    }
    private String kugouSearch() {
        try {
            String url="https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword="
                    +encode(title+" "+artist)+"&duration="+duration;
            // KuGou's MediaSession id can be a KRC/content hash. The lyric
            // service accepts it as an optional direct discriminator.
            if(packageName.startsWith("com.kugou.android")&&mediaId.matches("[0-9A-Fa-f]{16,64}"))
                url+="&hash="+encode(mediaId);
            JSONObject data=new JSONObject(get(url));
            JSONArray candidates=data.optJSONArray("candidates");
            if(candidates!=null)for(int i=0;i<Math.min(20,candidates.length());i++) {
                JSONObject candidate=candidates.optJSONObject(i);
                if(candidate==null||!MediaLyricMatch.matches(title,artist,duration,candidate.optString("song"),
                        candidate.optString("singer"),candidate.optLong("duration")))continue;
                String id=candidate.optString("id"),access=candidate.optString("accesskey");
                if(id.isEmpty()||access.isEmpty())continue;
                JSONObject content=new JSONObject(get("https://lyrics.kugou.com/download?ver=1&client=pc&id="
                        +encode(id)+"&accesskey="+encode(access)+"&fmt=lrc&charset=utf8"));
                String raw=new String(Base64.getDecoder().decode(content.optString("content")),StandardCharsets.UTF_8);
                if(usable(raw))return raw;
            }
        } catch(Exception error) {note("kugou lyric",error);}
        return "";
    }
    private String qishuiSearch() {
        try {
            String stamp=UUID.randomUUID().toString().replace("-","").substring(0,20);
            long now=System.currentTimeMillis();
            String url="https://api.qishui.com/luna/search/track?aid=386088&app_name=luna"
                    +"&version_code=100198030&package=com.luna.music&device_id="+stamp+"&iid="+stamp
                    +"&q="+encode(title+" "+artist)+"&cursor=0&count=20&_rticket="+now;
            JSONObject data=new JSONObject(get(url,
                    "com.luna.music/100198030 (Linux; U; Android 15) -","https://www.qishui.com/"));
            JSONArray groups=data.optJSONArray("result_groups");
            String bestId="";
            long bestDistance=Long.MAX_VALUE;
            if(groups!=null)for(int g=0;g<groups.length();g++) {
                JSONObject group=groups.optJSONObject(g);
                JSONArray rows=group==null?null:group.optJSONArray("data");
                if(rows==null)continue;
                for(int i=0;i<rows.length();i++) {
                    JSONObject row=rows.optJSONObject(i);
                    JSONObject entity=row==null?null:row.optJSONObject("entity");
                    JSONObject track=entity==null?null:entity.optJSONObject("track");
                    if(track==null)continue;
                    StringBuilder artists=new StringBuilder();
                    JSONArray names=track.optJSONArray("artists");
                    if(names!=null)for(int n=0;n<names.length();n++) {
                        JSONObject artist=names.optJSONObject(n);
                        if(artist==null)continue;
                        if(artists.length()>0)artists.append('/');
                        artists.append(artist.optString("name"));
                    }
                    long candidateDuration=track.optLong("duration");
                    if(!MediaLyricMatch.matches(title,artist,duration,track.optString("name"),
                            artists.toString(),candidateDuration))continue;
                    long distance=Math.abs(duration-candidateDuration);
                    if(distance<bestDistance) {
                        bestDistance=distance;
                        bestId=track.optString("id");
                    }
                }
            }
            return bestId.matches("[0-9]{1,20}")?qishuiById(bestId):"";
        } catch(Exception error) {
            note("qishui search",error);
            return "";
        }
    }
    private String qishuiById(String id) {
        try {
            JSONObject data=new JSONObject(get(
                    "https://beta-luna.douyin.com/luna/h5/seo_track?track_id="+encode(id)+"&device_platform=web",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
                            +" (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
                    "https://www.qishui.com/"));
            JSONObject lyric=data.optJSONObject("lyric");
            String raw=lyric==null?"":lyric.optString("content","").trim();
            return usable(raw)?raw:"";
        } catch(Exception error) {
            note("qishui lyric",error);
            return "";
        }
    }
    private String neteaseSearch() {
        try {
            JSONObject data=new JSONObject(get("https://music.163.com/api/search/get?s="
                    +encode(title+" "+artist)+"&type=1&limit=15"));
            JSONObject result=data.optJSONObject("result");
            JSONArray songs=result==null?null:result.optJSONArray("songs");
            String bestId="";
            long bestDistance=Long.MAX_VALUE;
            if(songs!=null)for(int i=0;i<Math.min(15,songs.length());i++) {
                JSONObject song=songs.optJSONObject(i);
                if(song==null)continue;
                StringBuilder names=new StringBuilder();
                JSONArray artists=song.optJSONArray("artists");
                if(artists!=null)for(int j=0;j<artists.length();j++) {
                    JSONObject item=artists.optJSONObject(j);
                    if(item==null)continue;
                    if(names.length()>0)names.append('/');
                    names.append(item.optString("name"));
                }
                long candidateDuration=song.optLong("duration");
                if(!MediaLyricMatch.matches(title,artist,duration,song.optString("name"),
                        names.toString(),candidateDuration))continue;
                long distance=Math.abs(duration-candidateDuration);
                if(distance<bestDistance) {
                    bestDistance=distance;
                    bestId=song.optString("id");
                }
            }
            return bestId.matches("[0-9]{1,15}")?neteaseById(bestId):"";
        } catch(Exception error) {note("netease search",error);return "";}
    }
    private String neteaseById(String id) {
        try {
            JSONObject data=new JSONObject(get("https://music.163.com/api/song/lyric?id="+id+"&lv=-1&tv=-1"));
            String raw=lyric(data,"lrc"),translation=lyric(data,"tlyric");
            if(usable(raw))return raw+(usable(translation)?"\n"+translation:"");
        } catch(Exception error) {note("netease lyric",error);}
        return "";
    }
    private static String lyric(JSONObject data,String channel) {
        JSONObject item=data.optJSONObject(channel);return item==null?"":item.optString("lyric","");
    }
    private static boolean usable(String raw) {
        return raw!=null&&raw.length()<=262144&&(raw.matches("(?s).*\\[\\d{1,4}:\\d{2}(?:[.,:]\\d{1,3})?\\].*")
                ||raw.matches("(?s).*\\[\\d{1,12},\\d{1,12}\\].*"))
                &&MediaLyrics.isLyricText(raw)&&MediaLyrics.parse(raw).hasLyrics()&&hasSubstantiveLine(raw);
    }
    private static boolean hasSubstantiveLine(String raw) {
        for(String row:raw.split("\\r?\\n",4097)) {
            String text=row.replaceFirst("^\\s*(?:\\[[^\\]]+\\]\\s*)+","").trim();
            if(text.isEmpty())continue;
            String lower=text.toLowerCase(java.util.Locale.ROOT);
            if(text.matches("^(?:作词|作曲|编曲|制作人|统筹|营销|吉他|混音|母带|录音|和声|监制|出品|发行|OP|SP|词|曲)\\s*[:：].*$"))
                continue;
            if(lower.matches("^(?:lyrics?|composer|producer|arranger|mixed|mastered|recorded|written)\\s*[:：].*$"))
                continue;
            return true;
        }
        return false;
    }
    private static void note(String label,Throwable error) {
        Consumer<String> sink=diagnostics;
        if(sink!=null)try {sink.accept(label+": "+error);}catch(Throwable ignored) {}
    }
    private String get(String address) throws Exception {
        return get(address,"MiStep/2.9 (lyrics display)","https://music.163.com/");
    }
    private String get(String address,String userAgent,String referer) throws Exception {
        if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
        connection=c;
        try {
            c.setConnectTimeout(4000);c.setReadTimeout(4000);c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent",userAgent);
            if(referer!=null)c.setRequestProperty("Referer",referer);
            c.setRequestProperty("Accept","application/json,text/plain,*/*");
            if(c.getResponseCode()!=200)throw new java.io.IOException("Lyric HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int size;
                while((size=in.read(buffer))!=-1) {
                    if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();
                    if(out.size()+size>524288)throw new java.io.IOException("Lyric response limit");
                    out.write(buffer,0,size);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } finally {c.disconnect();if(connection==c)connection=null;}
    }
}
