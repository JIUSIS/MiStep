import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = pathlib.Path(sys.argv[1]).resolve()
SOURCE = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / 'module/src/main/java/com/jiusis/mistep/MiStepNativeMedia.java'
OUT.mkdir(parents=True, exist_ok=True)
text = SOURCE.read_text(encoding='utf-8')
start = text.index('    private void syncLyricController()')
brace = text.index('{', start)
depth = 1
end = brace + 1
while depth:
    depth += (text[end] == '{') - (text[end] == '}')
    end += 1
method = text[start:end]
bench = r'''
import java.util.Objects;

public final class MediaLyricsBindingTest {
    static int checks;
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) {
        MiStepNativeMedia page = new MiStepNativeMedia();
        page.sync();
        check(page.clears == 0, "empty session does not start binding");
        MediaController first = new MediaController("session-a");
        page.controller.mediaController = first;
        page.sync();
        check(page.lyricController == first && first.registrations == 1, "first selected session is bound once");
        int clears = page.clears, prepares = page.prepares;
        page.inFlight = true;
        MediaController.Callback old = first.callback;
        for (int i = 0; i < 500; i++) {
            page.controller.mediaController = new MediaController("session-a");
            page.sync();
        }
        check(page.clears == clears && page.inFlight, "recreated wrappers do not cancel a lyric request");
        check(page.prepares == prepares, "repeated binding does not restart lookup");
        check(page.lyricController == first && first.registrations == 1, "the original session callback remains registered");
        old.onMetadataChanged(new MediaMetadata("session-a updated lyric"));
        check(page.metadataLyrics.equals("session-a updated lyric"), "retained callback still receives metadata changes");
        int refreshes = page.refreshes;
        page.controller.mediaController = new MediaController("session-b");
        page.sync();
        check(page.clears == clears + 1 && !page.inFlight, "a genuinely different session cancels the old request");
        old.onMetadataChanged(new MediaMetadata("stale a"));
        old.onExtrasChanged(new Bundle("stale extras"));
        check(page.refreshes == refreshes + 1 && !page.metadataLyrics.equals("stale a"), "callbacks from the old session cannot overwrite current lyrics");
        MediaController.Callback current = page.lyricCallback;
        page.active = false;
        int prepared = page.prepares;
        current.onMetadataChanged(new MediaMetadata("inactive"));
        check(page.prepares == prepared, "inactive page ignores metadata callbacks");
        page.active = true;
        page.bound = false;
        current.onMetadataChanged(new MediaMetadata("unbound"));
        check(page.prepares == prepared, "unbound page ignores metadata callbacks");
        page.bound = true;
        page.inFlight = true;
        page.controller.mediaController = null;
        page.sync();
        check(page.lyricController == null && !page.inFlight, "removed session releases binding and request");
        current.onSessionDestroyed();
        check(page.lyricController == null, "destroyed stale session does not recreate binding");
        page.legacy = true;
        page.controller.mController = new MediaController("os2-session");
        page.sync();
        check(page.lyricController == page.controller.mController, "OS2 follows its native mController");
        System.out.println("MediaLyricsBinding: " + checks + " checks passed (500 same-session wrappers)");
    }
}
class MiStepNativeMedia {
    final Holder controller = new Holder();
    static final Object MAIN = new Object();
    boolean active = true, bound = true, inFlight, legacy;
    int clears, prepares, refreshes;
    MediaController lyricController;
    MediaController.Callback lyricCallback;
    PlaybackState lyricPlayback;
    String metadataLyrics = "", extraLyrics = "";
    void sync() {syncLyricController();}
    void clearLyricController() {clears++; inFlight=false; lyricController=null; lyricCallback=null;}
    void renderLyric() {}
    void prepareLyricLookup(MediaMetadata value) {prepares++;}
    void refreshLyrics() {refreshes++;}
    static String readLyrics(MediaMetadata metadata, Bundle extras) {return metadata!=null?metadata.lyrics:extras!=null?extras.lyrics:"";}
__PRODUCTION_METHOD__
}
class Holder {MediaController mediaController, mController;}
class XposedHelpers {static Object getObjectField(Object holder,String name) {
    return name.equals("mController") ? ((Holder)holder).mController : ((Holder)holder).mediaController;
}}
class MediaMetadata {final String lyrics; MediaMetadata(String value){lyrics=value;}}
class Bundle {final String lyrics; Bundle(String value){lyrics=value;}}
class PlaybackState {}
class MediaController {
    final Token token;
    int registrations;
    Callback callback;
    MediaController(String id){token=new Token(id);}
    Token getSessionToken(){return token;}
    void registerCallback(Callback value,Object handler){registrations++;callback=value;}
    MediaMetadata getMetadata(){return new MediaMetadata("initial");}
    Bundle getExtras(){return new Bundle("");}
    PlaybackState getPlaybackState(){return new PlaybackState();}
    static class Callback {
        public void onMetadataChanged(MediaMetadata metadata){}
        public void onPlaybackStateChanged(PlaybackState state){}
        public void onExtrasChanged(Bundle extras){}
        public void onSessionDestroyed(){}
    }
}
class Token {
    final String id;
    Token(String value){id=value;}
    public boolean equals(Object other){return other instanceof Token&&id.equals(((Token)other).id);}
    public int hashCode(){return Objects.hash(id);}
}
'''.replace('__PRODUCTION_METHOD__', method)
source = OUT / 'MediaLyricsBindingTest.java'
source.write_text(bench, encoding='utf-8')
java_bin = os.environ.get('MISTEP_JAVA_BIN')
if not java_bin:
    java_home = os.environ.get('JAVA_HOME')
    if not java_home:
        raise SystemExit('Set MISTEP_JAVA_BIN or JAVA_HOME before running this suite.')
    java_bin = str(pathlib.Path(java_home) / 'bin')
jdk = pathlib.Path(java_bin)
for command in ([str(jdk / 'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(OUT), str(source)],
                [str(jdk / 'java.exe'), '-cp', str(OUT), 'MediaLyricsBindingTest']):
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30)
    print(result.stdout, end='')
    if result.returncode:
        print(result.stderr, end='')
        sys.exit(result.returncode)
