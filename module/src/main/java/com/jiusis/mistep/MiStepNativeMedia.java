package com.jiusis.mistep;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** An independent instance of OS4's notification player, fed by its native top-media selection. */
final class MiStepNativeMedia extends FrameLayout {
    private static final String BASE = "com.android.systemui.statusbar.notification.mediacontrol.";
    private static final String CONTROLLER = BASE + "MiuiMediaViewControllerImpl";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayList<WeakReference<MiStepNativeMedia>> listeners = new ArrayList<>();
    private static final ThreadLocal<Boolean> creating = new ThreadLocal<>();
    private static final ThreadLocal<Object> createdScope = new ThreadLocal<>();
    private static final ThreadLocal<WeakReference<Object>> freshlyConstructed = new ThreadLocal<>();
    private static Object factory;
    private static WeakReference<Object> source = new WeakReference<>(null);
    private static boolean installed;
    private final TextView empty, lyrics;
    private MediaController lyricController;
    private MediaController.Callback lyricCallback;
    private PlaybackState lyricPlayback;
    private MediaLyrics timeline = MediaLyrics.EMPTY;
    private String metadataLyrics = "", extraLyrics = "", currentLyrics = "";
    private String fallbackLyrics = "", lookupKey = "";
    private MediaLyricLookup lyricLookup;
    private boolean lookingUp;
    private final Runnable lyricTick = this::renderLyric;
    private final Runnable lookupStart = this::startLyricLookup;
    private Object controller, scope;
    private View player, seekBar;
    private boolean active, bound;
    private int nativeWidth, nativeHeight;
    private int lyricRows = 5;
    private float fit = 1f;
    private final Runnable lyricsLayoutRefresh = this::renderLyric;

    static void install(ClassLoader loader) {
        if (installed) return;
        installed = true;
        MediaLyricLookup.setDiagnostics(message -> XposedBridge.log("[Mi Step] lyric " + message));
        try {
            Class<?> provider = XposedHelpers.findClass(
                    "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent$ReferenceSysUIComponentImpl$SwitchingProvider", loader);
            XposedBridge.hookAllMethods(provider, "get7", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object result = p.getResult();
                    WeakReference<Object> fresh = freshlyConstructed.get();
                    if (!Boolean.TRUE.equals(creating.get()) && result != null
                            && fresh != null && fresh.get() == result) {
                        // Only the inner provider that actually constructed a fresh controller.
                        // Outer providers returning the cached notification singleton must not win.
                        factory = p.thisObject;
                        freshlyConstructed.remove();
                    }
                }
            });
            Class<?> type = XposedHelpers.findClass(CONTROLLER, loader);
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!Boolean.TRUE.equals(creating.get())) return;
                    // Give this player its own cancellable scope and button jobs. Shared native
                    // seek progress supports multiple consumers and remains owned by SystemUI.
                    try {
                        if (p.args.length != 18) throw new IllegalStateException("OS4 media constructor changed");
                        Object context = XposedHelpers.callMethod(p.args[14], "getCoroutineContext");
                        Object key = XposedHelpers.getStaticObjectField(XposedHelpers.findClass("kotlinx.coroutines.Job$Key", loader), "$$INSTANCE");
                        context = XposedHelpers.callMethod(context, "minusKey", key);
                        Object own = XposedHelpers.callStaticMethod(XposedHelpers.findClass("kotlinx.coroutines.CoroutineScopeKt", loader),
                                "CoroutineScope", context);
                        createdScope.set(own);
                        p.args[14] = own;
                        XposedHelpers.setObjectField(p.args[11], "uiScope", own);
                    } catch (Throwable e) {
                        // Xposed otherwise logs a callback exception and still invokes the
                        // original ctor, which would leak a job into the application scope.
                        p.setThrowable(e);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable() && !Boolean.TRUE.equals(creating.get()))
                        freshlyConstructed.set(new WeakReference<>(p.thisObject));
                }
            });
            Class<?> notifications = XposedHelpers.findClass(BASE + "MiuiMediaNotificationControllerImpl", loader);
            XposedBridge.hookAllConstructors(notifications, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) source = new WeakReference<>(p.thisObject);
                }
            });
            XposedBridge.hookAllMethods(notifications, "access$setTopMediaData", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length != 2) return;
                    source = new WeakReference<>(p.args[0]);
                    MAIN.post(() -> {
                        for (WeakReference<MiStepNativeMedia> ref : new ArrayList<>(listeners)) {
                            MiStepNativeMedia page = ref.get();
                            if (page != null && page.active) page.update();
                        }
                    });
                }
            });
        } catch (Throwable e) { MiStepOs4Controls.failed("native music hooks", e); }
    }

    MiStepNativeMedia(Context context) {
        super(context);
        setClipChildren(true);
        setClipToPadding(true);
        GradientDrawable material = new GradientDrawable();
        material.setColor(0xb2353f49);
        material.setCornerRadius(MiStepOs4Controls.cornerRadius(context));
        setBackground(material);
        setClipToOutline(true);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), MiStepOs4Controls.cornerRadius(context));
            }
        });
        empty = new TextView(context);
        empty.setText("暂无播放内容");
        empty.setTextSize(14);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(HyperOsStyle.textPrimary(context));
        addView(empty, new LayoutParams(-1, -1));
        lyrics = new TextView(context);
        lyrics.setText("暂无歌词");
        lyrics.setTextColor(HyperOsStyle.textPrimary(context));
        lyrics.setGravity(Gravity.CENTER);
        lyrics.setMaxLines(5);
        lyrics.setEllipsize(TextUtils.TruncateAt.END);
        lyrics.setIncludeFontPadding(false);
        lyrics.setClickable(false);
        lyrics.setFocusable(false);
        lyrics.setVisibility(View.GONE);
        addView(lyrics, new LayoutParams(0, 0));
        setContentDescription("原生音乐控件，左右滑动切换控制页");
    }

    void setActive(boolean value) {
        if (active == value) return;
        active = value;
        if (value && isAttachedToWindow()) update();
        else unbind();
    }

    private void update() {
        if (!active || !isAttachedToWindow()) return;
        try {
            Object original = source.get();
            Object data = original == null ? null : XposedHelpers.getObjectField(original, "topMediaData");
            if (data == null) {
                unbind();
                if (player == null) createPlayer();
                showPlaceholder();
                return;
            }
            if (player == null) createPlayer();
            if (controller == null) createController();
            if (!bound) {
                enablePlayer();
                bound = true;
                XposedHelpers.callMethod(controller, "attach", XposedHelpers.getAdditionalInstanceField(player, "mistep.media.holder"));
            }
            XposedHelpers.callMethod(controller, "bindMediaData", data);
            try { syncLyricController(); }
            catch (Throwable e) {
                clearLyricController();
                MiStepOs4Controls.failed("native music lyric binding", e);
            }
            renderLyric();
            player.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
            requestLayout();
        } catch (Throwable e) {
            releasePlayer();
            empty.setText("原生音乐控件未就绪");
            MiStepOs4Controls.failed("native music", e);
        }
    }

    private void createController() throws Exception {
        Object provider = factory;
        if (provider == null) throw new IllegalStateException("OS4 media factory not ready");
        ClassLoader loader = getContext().getClassLoader();
        creating.set(true);
        try {
            controller = XposedHelpers.callMethod(provider, "get7");
        } finally {
            scope = createdScope.get();
            creating.remove();
            createdScope.remove();
        }
        if (controller == null || scope == null || !CONTROLLER.equals(controller.getClass().getName()))
            throw new IllegalStateException("OS4 independent media controller unavailable");
    }

    private void createPlayer() throws Exception {
        ClassLoader loader = getContext().getClassLoader();
        View root = LayoutInflater.from(getContext()).inflate(resource("layout", "miui_media_session"), this, false);
        Object holder = allocate(XposedHelpers.findClass(BASE + "MiuiMediaViewHolder", loader));
        XposedHelpers.setObjectField(holder, "player", root);
        String[][] fields = {
                {"mediaBg", "media_bg"}, {"elapsedTimeView", "media_elapsed_time"}, {"totalTimeView", "media_total_time"},
                {"albumView", "album_art"}, {"albumImageView", "album_art_image"}, {"appIcon", "icon"},
                {"titleText", "header_title"}, {"artistText", "header_artist"}, {"seamless", "media_seamless"},
                {"seamlessIcon", "media_seamless_image"}, {"seamlessButton", "media_seamless_button"},
                {"seekBar", "media_progress_bar"}, {"action0", "action0"}, {"action1", "action1"},
                {"action2", "action2"}, {"action3", "action3"}, {"action4", "action4"}};
        for (String[] entry : fields)
            XposedHelpers.setObjectField(holder, entry[0], root.requireViewById(resource("id", entry[1])));
        round(root.requireViewById(resource("id", "album_art")), "album_art_bg_radius");
        round(root.requireViewById(resource("id", "icon")), "media_app_icon_bg_radius");
        round(root.requireViewById(resource("id", "media_bg")), "notification_item_bg_radius");
        applyConstraints(root, "miui_media_session_normal");
        anchorHeader(root);
        applyConstraints(root.requireViewById(resource("id", "album_art")), "miui_media_session_album_normal");
        seekBar = root.requireViewById(resource("id", "media_progress_bar"));
        seekBar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        XposedHelpers.setAdditionalInstanceField(root, "mistep.media.holder", holder);
        player = root;
        addView(root, 0, new LayoutParams(-1, -2));
    }

    private void syncLyricController() {
        // Follow exactly the player selected by SystemUI, without a package allowlist.
        Object value = XposedHelpers.getObjectField(controller, "mediaController");
        MediaController next = value instanceof MediaController ? (MediaController) value : null;
        if (next == lyricController) return;
        clearLyricController();
        if (next == null) { renderLyric(); return; }
        lyricController = next;
        lyricCallback = new MediaController.Callback() {
            private boolean current() { return active && bound && lyricController == next; }
            @Override public void onMetadataChanged(MediaMetadata metadata) {
                if (!current()) return;
                metadataLyrics = readLyrics(metadata, null);
                // Session extras may belong to the previous song until refreshed.
                extraLyrics = "";
                prepareLyricLookup(metadata);
                refreshLyrics();
            }
            @Override public void onPlaybackStateChanged(PlaybackState state) {
                if (!current()) return;
                lyricPlayback = state;
                renderLyric();
            }
            @Override public void onExtrasChanged(Bundle extras) {
                if (!current()) return;
                extraLyrics = readLyrics(null, extras);
                refreshLyrics();
            }
            @Override public void onSessionDestroyed() {
                if (!current()) return;
                clearLyricController();
                renderLyric();
            }
        };
        next.registerCallback(lyricCallback, MAIN);
        metadataLyrics = readLyrics(next.getMetadata(), null);
        extraLyrics = readLyrics(null, next.getExtras());
        lyricPlayback = next.getPlaybackState();
        prepareLyricLookup(next.getMetadata());
        refreshLyrics();
    }

    private void prepareLyricLookup(MediaMetadata metadata) {
        String key = songKey(metadata);
        if (key.equals(lookupKey)) return;
        cancelLyricLookup();
        lookupKey = key;
        fallbackLyrics = "";
        if (!key.isEmpty() && metadataLyrics.isEmpty() && extraLyrics.isEmpty()) {
            lookingUp = true;
            MAIN.postDelayed(lookupStart, 400);
        }
    }

    private String songKey(MediaMetadata metadata) {
        if (metadata == null || lyricController == null) return "";
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(title) || TextUtils.isEmpty(artist)) return "";
        return lyricController.getPackageName()+"\n"+title+"\n"+artist+"\n"
                +metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)+"\n"+metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
    }

    private void startLyricLookup() {
        if (!active || !bound || lyricController == null || !metadataLyrics.isEmpty() || !extraLyrics.isEmpty()) return;
        final MediaController selected = lyricController;
        final String key = lookupKey;
        MediaMetadata metadata = selected.getMetadata();
        if (metadata == null || !key.equals(songKey(metadata))) return;
        lyricLookup = MediaLyricLookup.start(r -> MAIN.post(r), metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST), metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                selected.getPackageName(), metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), raw -> {
                    if (!active || !bound || lyricController != selected || !key.equals(lookupKey)) return;
                    lookingUp = false;
                    lyricLookup = null;
                    fallbackLyrics = raw;
                    refreshLyrics();
                });
    }

    private void cancelLyricLookup() {
        MAIN.removeCallbacks(lookupStart);
        if (lyricLookup != null) { lyricLookup.cancel(); lyricLookup = null; }
        lookingUp = false;
    }

    private static String readLyrics(MediaMetadata metadata, Bundle extras) {
        try {
            ArrayList<String> keys = new ArrayList<>();
            for (String key : metadata != null ? metadata.keySet() : extras != null ? extras.keySet() : Collections.<String>emptySet())
                if (key != null && MediaLyrics.isLyricKey(key)) keys.add(key);
            Collections.sort(keys);
            // Prefer conventional full-song metadata, then discover other publishers' fields.
            String[] preferred = {"android.media.metadata.LYRIC", "android.media.metadata.LYRICS", "ucar.media.metadata.LYRICS_WHOLE"};
            for (int i=preferred.length-1; i>=0; i--) if (keys.remove(preferred[i])) keys.add(0, preferred[i]);
            for (String key : keys) {
                try {
                    Object raw = metadata != null ? metadata.getText(key) : extras.get(key);
                    if (!(raw instanceof CharSequence)) continue;
                    String text = raw.toString().trim();
                    if (text.length() > 262144) text = text.substring(0,262144);
                    if (MediaLyrics.isLyricText(text) && MediaLyrics.parse(text).hasLyrics()) return text;
                } catch (RuntimeException ignored) { /* A non-text custom field must not mask another publisher's lyrics. */ }
            }
        } catch (Throwable e) { MiStepOs4Controls.failed("native music lyric data", e); }
        return "";
    }

    private void refreshLyrics() {
        String raw = !metadataLyrics.isEmpty() ? metadataLyrics : !extraLyrics.isEmpty() ? extraLyrics : fallbackLyrics;
        if (!metadataLyrics.isEmpty() || !extraLyrics.isEmpty()) cancelLyricLookup();
        if (!raw.equals(currentLyrics)) {
            currentLyrics = raw;
            timeline = MediaLyrics.parse(raw);
        }
        renderLyric();
    }

    private void renderLyric() {
        MAIN.removeCallbacks(lyricTick);
        if (!active || !isAttachedToWindow()) return;
        boolean playing = lyricPlayback != null && lyricPlayback.getState() == PlaybackState.STATE_PLAYING;
        float speed = lyricPlayback == null ? 0 : lyricPlayback.getPlaybackSpeed();
        long position = lyricPlayback == null ? 0 : MediaLyrics.position(lyricPlayback.getPosition(),
                lyricPlayback.getLastPositionUpdateTime(), SystemClock.elapsedRealtime(), speed, playing);
        MediaLyrics.Window window=timeline.hasLyrics()
                ? timeline.windowAt(position,lyricRows)
                : new MediaLyrics.Window(lookingUp ? "正在获取歌词…" : "暂无歌词",0,1);
        String text=window.text.isEmpty()?"♪":window.text;
        if (!TextUtils.equals(lyrics.getText(), text) || window.currentLine >= 0) {
            if (timeline.hasLyrics() && window.lineCount > 1 && player != null) {
                SpannableStringBuilder styled=new SpannableStringBuilder(text);
                int start=0;
                for (int row=0;row<window.lineCount;row++) {
                    int end=start;
                    while(end<text.length()&&text.charAt(end)!='\n')end++;
                    int base=((TextView)child("header_title")).getCurrentTextColor();
                    int alpha=row==window.currentLine?255:92;
                    styled.setSpan(new ForegroundColorSpan(Color.argb(alpha,Color.red(base),Color.green(base),Color.blue(base))),
                            start,end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    start=Math.min(text.length(),end+1);
                }
                lyrics.setText(styled);
            } else lyrics.setText(text);
        }
        lyrics.setAlpha(timeline.hasLyrics() ? 1f : .55f);
        lyrics.setVisibility(View.VISIBLE);
        if (player != null) lyrics.setTextColor(((TextView)child("header_title")).getTextColors());
        long delay = timeline.nextDelay(position, speed, playing);
        if (bound && delay >= 0) MAIN.postDelayed(lyricTick, delay);
    }

    private void clearLyricController() {
        cancelLyricLookup();
        MAIN.removeCallbacks(lyricTick);
        MediaController old = lyricController;
        MediaController.Callback callback = lyricCallback;
        lyricController = null;
        lyricCallback = null;
        if (old != null && callback != null) try { old.unregisterCallback(callback); }
        catch (Throwable e) { MiStepOs4Controls.failed("native music lyric detach", e); }
        lyricPlayback = null;
        metadataLyrics = extraLyrics = currentLyrics = "";
        fallbackLyrics = lookupKey = "";
        timeline = MediaLyrics.EMPTY;
        lyrics.setText("暂无歌词");
        lyrics.setVisibility(View.GONE);
    }

    private View child(String name) {
        return player.requireViewById(resource("id", name));
    }

    private void enablePlayer() {
        player.setEnabled(true);
        player.setAlpha(1f);
        ImageView album = (ImageView) child("album_art_image");
        album.setImageTintList(null);
        album.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        album.setScaleType(ImageView.ScaleType.CENTER_CROP);
        for (String name : new String[]{"media_progress_bar", "media_seamless", "media_seamless_button",
                "media_seamless_image", "action0", "action1", "action2", "action3", "action4"}) {
            View view = child(name);
            view.setEnabled(true);
            view.setAlpha(1f);
            // The empty LaunchableLinearLayout is only an output-dialog anchor.
            // Its native GONE state must not become a separate accent-colored pill.
            if (!"media_seamless_button".equals(name)) view.setVisibility(View.VISIBLE);
            if (name.startsWith("action")) ((ImageView) view).setImageTintList(null);
        }
    }

    private void showPlaceholder() {
        // Keep the OEM layout and its native drawables even before a media session exists.
        // No fabricated MediaData/token/actions: controls remain inert until native binding.
        player.setOnClickListener(null);
        player.setClickable(false);
        player.setEnabled(false);
        applyConstraints(player, "miui_media_session_normal");
        anchorHeader(player);
        applyConstraints(child("album_art"), "miui_media_session_album_normal");
        ((TextView) child("header_title")).setText("暂无播放内容");
        ((TextView) child("header_artist")).setText("");
        ((TextView) child("media_elapsed_time")).setText("00:00");
        ((TextView) child("media_total_time")).setText("00:00");
        ImageView album = (ImageView) child("album_art_image");
        album.setImageResource(resource("drawable", "ic_music_note"));
        album.setImageTintList(android.content.res.ColorStateList.valueOf(0x99ffffff));
        album.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        album.setBackgroundColor(0x18ffffff);
        ((ImageView) child("icon")).setImageDrawable(null);
        ((ImageView) child("media_bg")).setImageDrawable(null);
        String[] icons = {"miuix_action_icon_favorite_normal_light", "ic_media_prev", "ic_media_play_light", "ic_media_next"};
        for (int i = 0; i < 5; i++) {
            ImageView action = (ImageView) child("action" + i);
            action.setOnClickListener(null);
            action.setEnabled(false);
            action.setAlpha(.45f);
            action.setVisibility(i < icons.length ? View.VISIBLE : View.INVISIBLE);
            action.setImageDrawable(i < icons.length ? getContext().getDrawable(resource("drawable", icons[i])) : null);
            action.setImageTintList(android.content.res.ColorStateList.valueOf(0xffffffff));
        }
        View output = child("media_seamless_button");
        output.setVisibility(View.GONE);
        output.setEnabled(false);
        output.setAlpha(.45f);
        ImageView outputIcon = (ImageView) child("media_seamless_image");
        outputIcon.setImageResource(resource("drawable", "ic_media_seamless"));
        outputIcon.setImageTintList(android.content.res.ColorStateList.valueOf(0xffffffff));
        outputIcon.setEnabled(false);
        outputIcon.setOnClickListener(null);
        child("media_seamless").setEnabled(false);
        SeekBar seek = (SeekBar) seekBar;
        seek.setOnTouchListener(null);
        seek.setOnSeekBarChangeListener(null);
        seek.setEnabled(false);
        seek.setProgress(0);
        seek.setSecondaryProgress(0);
        seek.setAlpha(.45f);
        player.setVisibility(View.VISIBLE);
        empty.setVisibility(View.GONE);
        renderLyric();
        requestLayout();
    }

    private void round(View view, String dimension) {
        int id = getResources().getIdentifier(dimension, "dimen", "com.android.systemui");
        if (id == 0) return;
        float radius = getResources().getDimension(id);
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }

    private int resource(String type, String name) {
        int id = getResources().getIdentifier(name, type, "com.android.systemui");
        if (id == 0) throw new IllegalStateException("Missing native media " + type + "/" + name);
        return id;
    }

    private void applyConstraints(View root, String xml) {
        Class<?> type = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintSet", getContext().getClassLoader());
        Object set = XposedHelpers.newInstance(type);
        XposedHelpers.callMethod(set, "load", getContext(), resource("xml", xml));
        XposedHelpers.callMethod(set, "applyTo", root);
    }

    private void anchorHeader(View root) {
        Class<?> type = XposedHelpers.findClass("androidx.constraintlayout.widget.ConstraintSet", getContext().getClassLoader());
        Object set = XposedHelpers.newInstance(type);
        XposedHelpers.callMethod(set, "clone", root);
        // OEM cover END <-> title START forms a spread chain. Fixed title widths
        // would spread its free space before BOTH widgets and squeeze the lyrics.
        // Keep the native START/top margins, with no horizontal end dependency.
        for (String name : new String[]{"album_art", "header_title", "header_artist"})
            XposedHelpers.callMethod(set, "clear", resource("id", name), 7); // ConstraintSet.END
        XposedHelpers.callMethod(set, "applyTo", root);
    }

    private static Object allocate(Class<?> type) throws Exception {
        // R8 inlined this data holder's Object constructor, so no declared ctor remains.
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        for (Field field : unsafeType.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == unsafeType) {
                field.setAccessible(true);
                return XposedHelpers.callMethod(field.get(null), "allocateInstance", type);
            }
        }
        throw new IllegalStateException("Native holder allocation unavailable");
    }

    private void unbind() {
        clearLyricController();
        if (bound) {
            bound = false;
            try { XposedHelpers.callMethod(controller, "detach"); }
            catch (Throwable e) { MiStepOs4Controls.failed("native music detach", e); }
        }
        if (player != null) player.setVisibility(View.GONE);
        empty.setVisibility(View.VISIBLE);
    }

    private void releasePlayer() {
        unbind();
        if (player != null) {
            XposedHelpers.removeAdditionalInstanceField(player, "mistep.media.holder");
            removeView(player);
        }
        player = seekBar = null;
        controller = null;
        if (scope != null) try {
            XposedHelpers.callStaticMethod(XposedHelpers.findClass("kotlinx.coroutines.CoroutineScopeKt", getContext().getClassLoader()),
                    "cancel", new Class<?>[]{XposedHelpers.findClass("kotlinx.coroutines.CoroutineScope", getContext().getClassLoader()),
                            CancellationException.class}, scope, null);
        } catch (Throwable e) { MiStepOs4Controls.failed("native music scope", e); }
        scope = null;
    }

    boolean ownsHorizontalGesture(float x, float y) {
        if (!bound || seekBar == null || !seekBar.isShown()) return false;
        Rect bounds = new Rect();
        if (!seekBar.getGlobalVisibleRect(bounds)) return false;
        int[] origin = new int[2];
        getLocationOnScreen(origin);
        bounds.offset(-origin[0], -origin[1]);
        bounds.inset(0, -Math.round(8 * getResources().getDisplayMetrics().density));
        return bounds.contains(Math.round(x), Math.round(y));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        listeners.add(new WeakReference<>(this));
        if (active) update();
    }

    @Override protected void onDetachedFromWindow() {
        listeners.removeIf(ref -> ref.get() == null || ref.get() == this);
        releasePlayer();
        super.onDetachedFromWindow();
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        empty.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        if (player != null && player.getVisibility() != View.GONE && w > 0 && h > 0) {
            MediaPanelGeometry panel = panelGeometry(w,h);
            int leftWidth = panel.playerWidth;
            int minimum = Math.round(300 * getResources().getDisplayMetrics().density);
            nativeWidth = Math.max(leftWidth,minimum);
            fitHeader(nativeWidth);
            player.measure(MeasureSpec.makeMeasureSpec(nativeWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            nativeHeight = Math.max(1, player.getMeasuredHeight());
            fit = Math.max(.001f,Math.min(leftWidth / (float)nativeWidth, h / (float) nativeHeight));
            nativeWidth = Math.max(minimum, Math.round(leftWidth / fit));
            fitHeader(nativeWidth);
            player.measure(MeasureSpec.makeMeasureSpec(nativeWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            nativeHeight = Math.max(1, player.getMeasuredHeight());
            fit = Math.min(leftWidth / (float) nativeWidth, h / (float) nativeHeight);
        }
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r-l, h = b-t;
        empty.layout(0, 0, w, h);
        if (player != null && player.getVisibility() != View.GONE) {
            MediaPanelGeometry panel = panelGeometry(w,h);
            int x = Math.round((panel.playerWidth - nativeWidth * fit) / 2), y = Math.round((h - nativeHeight * fit) / 2);
            player.layout(x, y, x+nativeWidth, y+nativeHeight);
            player.setPivotX(0); player.setPivotY(0);
            player.setScaleX(fit); player.setScaleY(fit);
            layoutLyrics(panel);
        }
    }

    private MediaPanelGeometry panelGeometry(int width,int height) {
        float density=getResources().getDisplayMetrics().density;
        return new MediaPanelGeometry(width,height,Math.round(12*density),Math.round(14*density));
    }

    private void fitHeader(int width) {
        ViewGroup.MarginLayoutParams cover = (ViewGroup.MarginLayoutParams) child("album_art").getLayoutParams();
        ViewGroup.MarginLayoutParams output = (ViewGroup.MarginLayoutParams) child("media_seamless").getLayoutParams();
        ViewGroup.MarginLayoutParams title = (ViewGroup.MarginLayoutParams) child("header_title").getLayoutParams();
        int available = width - player.getPaddingStart() - player.getPaddingEnd() - cover.getMarginStart()
                - Math.max(0,cover.width) - title.getMarginStart() - output.getMarginEnd()
                - Math.max(0,output.width) - title.getMarginEnd();
        int metadataWidth = Math.max(0,available);
        for (String name : new String[]{"header_title", "header_artist"}) {
            View text = child(name);
            ViewGroup.LayoutParams lp = text.getLayoutParams();
            if (lp.width != metadataWidth) { lp.width = metadataWidth; text.setLayoutParams(lp); }
        }
    }

    private void layoutLyrics(MediaPanelGeometry panel) {
        TextView nativeArtist = (TextView)child("header_artist"), nativeTitle = (TextView)child("header_title");
        float scaledDensity=getResources().getDisplayMetrics().scaledDensity;
        lyrics.setTextSize(TypedValue.COMPLEX_UNIT_PX,nativeTitle.getTextSize()*fit+scaledDensity);
        lyrics.setTextColor(nativeTitle.getTextColors());
        lyrics.setTypeface(nativeArtist.getTypeface());
        lyrics.setLineSpacing(0,1.12f);
        int availableHeight=Math.max(1,panel.lyricBottom-panel.lyricTop);
        lyricRows=Math.max(3,Math.min(7,availableHeight/Math.max(1,lyrics.getLineHeight()+Math.round(3*scaledDensity))));
        lyrics.setMaxLines(lyricRows);
        lyrics.measure(MeasureSpec.makeMeasureSpec(panel.lyricRight-panel.lyricLeft,MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(panel.lyricBottom-panel.lyricTop,MeasureSpec.EXACTLY));
        lyrics.layout(panel.lyricLeft,panel.lyricTop,panel.lyricRight,panel.lyricBottom);
        MAIN.removeCallbacks(lyricsLayoutRefresh);
        MAIN.post(lyricsLayoutRefresh);
    }
}
