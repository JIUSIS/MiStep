package com.jianguo.mistep;

import android.content.*;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import de.robv.android.xposed.XposedHelpers;

/** Reference layout with independent native OS4 tiles and vertical sliders. */
final class MiStepControlCenter extends FrameLayout {
    private final Context context;
    private final AudioManager audio;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View[] cells = new View[4];
    private final FrameLayout firstPage;
    private final MiStepNativeMedia musicPage;
    private final ControlCenterPages pages = new ControlCenterPages();
    private ValueAnimator pageAnimator;
    private VelocityTracker pageVelocity;
    private int page;
    private float pageOffset;
    private final View connectivityCard;
    private final ArrayList<Runnable> releases = new ArrayList<>();
    private final ArrayList<Rect> cutouts = new ArrayList<>();
    private final TextView model;
    private final View scanner;
    private final View payment, transit;
    private MiStepOs4Controls.Slider volume, brightness;
    private ConnectivityManager networks;
    private boolean listening, updating, volumeTracking, brightnessTracking;
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { refreshValues(); }
    };
    private final ContentObserver brightnessObserver = new ContentObserver(main) {
        public void onChange(boolean selfChange) { refreshValues(); }
    };
    private final DisplayManager.DisplayListener brightnessDisplayListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) {}
        public void onDisplayRemoved(int id) {}
        public void onDisplayChanged(int id) { if (listening && id == Display.DEFAULT_DISPLAY) refreshValues(); }
    };
    private final ConnectivityManager.NetworkCallback vpnCallback = new ConnectivityManager.NetworkCallback() {
        public void onAvailable(Network n) { main.post(MiStepControlCenter.this::refreshVpn); }
        public void onLost(Network n) { main.post(MiStepControlCenter.this::refreshVpn); }
        public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { main.post(MiStepControlCenter.this::refreshVpn); }
    };

    MiStepControlCenter(Context context) {
        super(context);
        this.context = context;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        setClipChildren(true);
        setClipToPadding(true);
        firstPage = new FrameLayout(context);
        addView(firstPage, new FrameLayout.LayoutParams(-1, -1));
        musicPage = new MiStepNativeMedia(context);
        addView(musicPage, new FrameLayout.LayoutParams(-1, -1));
        musicPage.setVisibility(View.INVISIBLE);
        musicPage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setContentDescription("控制中心第1页，左右滑动切换音乐控件");
        connectivityCard = new View(context);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xb2353f49);
        panel.setCornerRadius(MiStepOs4Controls.cornerRadius(context));
        panel.setStroke(dp(.5f), 0x26ffffff);
        connectivityCard.setBackground(panel);
        connectivityCard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        firstPage.addView(connectivityCard);
        String[] names = {"Wi-Fi", "数据", "蓝牙", "VPN"};
        String[] specs = {"wifi", "cell", "bt"};
        for (int i = 0; i < cells.length; i++) {
            final int index = i;
            cells[i] = tile(names[i], () -> {
                if (index == 3) MiStepSystemActions.openVpn(context);
                else MiStepSystemActions.toggleQuickSetting(context, specs[index]);
            }, true);
            firstPage.addView(cells[i]);
        }
        model = new TextView(context);
        model.setText(deviceName());
        model.setTextColor(HyperOsStyle.textPrimary(context));
        model.setTextSize(10);
        model.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        model.setGravity(Gravity.CENTER);
        model.setIncludeFontPadding(false);
        model.setMaxLines(3);
        model.setAutoSizeTextTypeUniformWithConfiguration(8, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        model.setContentDescription("机型：" + model.getText());
        firstPage.addView(model);
        scanner = tile("扫一扫", this::openScanner);
        firstPage.addView(scanner);
        payment = tile("付款码", () -> MiStepSystemActions.openCode(getContext(), paymentAnchor(), MiStepCodes.PAYMENT));
        transit = tile("乘车码", () -> MiStepSystemActions.openCode(getContext(), transitAnchor(), MiStepCodes.TRANSIT));
        firstPage.addView(payment); firstPage.addView(transit);
        try {
            volume = new MiStepOs4Controls.Slider(context, "音量", "ic_volume_slider_animate_icon");
            volume.slider.setMax(audio == null ? 15 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            firstPage.addView(volume.root);
            volume.slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    volume.updateProgressShape();
                    volume.text.setText("音量 " + Math.round(value * 100f / Math.max(1, bar.getMax())) + "%");
                    if (fromUser && !updating && audio != null) try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                    } catch (Throwable e) { MiStepOs4Controls.failed("volume", e); }
                }
                public void onStartTrackingTouch(SeekBar bar) { volumeTracking = true; }
                public void onStopTrackingTouch(SeekBar bar) { volumeTracking = false; refreshValues(); }
            });
            brightness = new MiStepOs4Controls.Slider(context, "亮度", "ic_brightness_slider_animate_icon");
            brightness.slider.setMax(XposedHelpers.getStaticIntField(brightnessUtils(), "GAMMA_SPACE_MAX"));
            firstPage.addView(brightness.root);
            brightness.slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                    brightness.updateProgressShape();
                    brightness.text.setText("亮度 " + Math.round(value * 100f / Math.max(1, bar.getMax())) + "%");
                    if (fromUser && !updating) setBrightness(value);
                }
                public void onStartTrackingTouch(SeekBar bar) { brightnessTracking = true; }
                public void onStopTrackingTouch(SeekBar bar) { brightnessTracking = false; refreshValues(); }
            });
        } catch (Throwable e) { MiStepOs4Controls.failed("sliders", e); }
        try {
            MiStepOs4Controls.state(scanner, MiStepOs4Controls.simpleState(context, "扫一扫", false,
                    "ic_qr_code_scanner", "ic_qs_qr_code_scanner", "ic_qs_scanner"));
        } catch (Throwable e) { MiStepOs4Controls.failed("scanner icon", e); }
        try {
            MiStepOs4Controls.state(payment, MiStepOs4Controls.simpleState(context, "付款码", false,
                    "ic_qs_wallet", "ic_wallet", "ic_qs_nfc"));
            MiStepOs4Controls.state(transit, MiStepOs4Controls.simpleState(context, "乘车码", false,
                    "ic_qs_mi_pay", "ic_qs_wallet", "ic_qs_nfc"));
        } catch (Throwable e) { MiStepOs4Controls.failed("code icon", e); }
        refreshValues();
    }
    private View tile(String label, Runnable action) {
        return tile(label, action, false);
    }
    private View tile(String label, Runnable action, boolean iconOnly) {
        try { return MiStepOs4Controls.tile(context, label, action, iconOnly); }
        catch (Throwable e) {
            MiStepOs4Controls.failed("tile " + label, e);
            TextView unavailable = new TextView(context);
            unavailable.setText(iconOnly ? "—" : label + "\n系统控件未就绪");
            unavailable.setTextSize(10);
            unavailable.setTextColor(HyperOsStyle.textSecondary(context));
            unavailable.setGravity(Gravity.CENTER);
            unavailable.setContentDescription(label + "系统控件未就绪");
            return unavailable;
        }
    }
    void setWallpaper(Bitmap bitmap, int width, int height) {
        // Native views own their material; the parent already draws wallpaper.
    }
    private void openScanner() { MiStepSystemActions.openScanner(getContext(), scanner); }
    private View paymentAnchor() { return payment; }
    private View transitAnchor() { return transit; }
    Rect codeBounds() {
        Rect bounds = new Rect(payment.getLeft(), payment.getTop(), payment.getRight(), payment.getBottom());
        bounds.union(scanner.getLeft(), scanner.getTop(), scanner.getRight(), scanner.getBottom());
        bounds.union(transit.getLeft(), transit.getTop(), transit.getRight(), transit.getBottom());
        return bounds;
    }
    void coverCodeButtons(boolean covered, boolean animate) {
        for (View button : new View[]{payment, scanner, transit}) {
            button.animate().cancel();
            if (animate) button.animate().alpha(covered ? 0f : 1f).setDuration(covered ? 180 : 140).start();
            else button.setAlpha(covered ? 0f : 1f);
        }
    }
    void setCutouts(List<Rect> bounds, int originX, int originY) {
        cutouts.clear();
        for (Rect rect : bounds) { Rect local = new Rect(rect); local.offset(-originX, -originY); cutouts.add(local); }
        requestLayout();
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        ControlCenterGeometry g = new ControlCenterGeometry(width, height, dp(8));
        FrameLayout.LayoutParams card = new FrameLayout.LayoutParams(g.networkWidth, g.networkWidth);
        card.topMargin = g.cardTop;
        connectivityCard.setLayoutParams(card);
        int iconSize = g.connectivityIconSize(dp(44));
        for (int i = 0; i < cells.length; i++)
            size(cells[i], g.connectivityLeft(i, iconSize), g.connectivityTop(i, iconSize), iconSize, iconSize);
        size(model, g.modelLeft, g.cardTop + g.panelPadding, g.modelWidth, g.tileHeight);
        size(scanner, g.scanLeft, g.secondRow, g.codeWidth, g.tileHeight);
        size(payment, g.scanLeft - g.codeWidth - g.codeGap, g.secondRow, g.codeWidth, g.tileHeight);
        size(transit, g.scanLeft + g.codeWidth + g.codeGap, g.secondRow, g.codeWidth, g.tileHeight);
        if (volume != null) size(volume.root, g.volumeLeft, 0, g.sliderWidth, g.controlsHeight);
        if (brightness != null) size(brightness.root, g.brightnessLeft, 0, g.sliderWidth, g.controlsHeight);
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cancelPageAnimation();
        pageOffset = page * pageSpan();
        renderPages();
    }

    private void renderPages() {
        firstPage.setTranslationX(-pageOffset);
        musicPage.setTranslationX(pageSpan() - pageOffset);
    }

    private float pageSpan() {
        return ControlCenterPages.span(getWidth(), dp(12));
    }

    private void cancelPageAnimation() {
        ValueAnimator old = pageAnimator;
        pageAnimator = null;
        if (old != null) old.cancel();
    }

    private void finishPage() {
        firstPage.setVisibility(page == 0 ? View.VISIBLE : View.INVISIBLE);
        musicPage.setVisibility(page == 1 ? View.VISIBLE : View.INVISIBLE);
        firstPage.setImportantForAccessibility(page == 0 ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        musicPage.setImportantForAccessibility(page == 1 ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        musicPage.setActive(page == 1);
        setContentDescription(page == 0 ? "控制中心第1页，左右滑动切换音乐控件" : "原生音乐第2页，左右滑动返回控制中心");
    }

    private void settlePage(int target) {
        cancelPageAnimation();
        page = Math.max(0, Math.min(1, target));
        firstPage.setVisibility(View.VISIBLE);
        musicPage.setVisibility(View.VISIBLE);
        if (page == 1) musicPage.setActive(true);
        ValueAnimator next = ValueAnimator.ofFloat(pageOffset, page * pageSpan());
        pageAnimator = next;
        next.setDuration(200);
        next.setInterpolator(new DecelerateInterpolator());
        next.addUpdateListener(value -> { pageOffset = (float) value.getAnimatedValue(); renderPages(); });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (pageAnimator != animation) return;
                pageAnimator = null;
                finishPage();
            }
        });
        next.start();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelPageAnimation();
            pageOffset = page * pageSpan();
            renderPages(); finishPage();
            pages.begin(event.getX(), event.getY(), page, getWidth(), dp(12), ViewConfiguration.get(context).getScaledTouchSlop(),
                    page == 1 && musicPage.ownsHorizontalGesture(event.getX(), event.getY()));
            if (pageVelocity != null) pageVelocity.recycle();
            pageVelocity = VelocityTracker.obtain();
        }
        if (pageVelocity != null) pageVelocity.addMovement(event);
        try { return super.dispatchTouchEvent(event); }
        finally {
            if ((event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
                    && pageVelocity != null) { pageVelocity.recycle(); pageVelocity = null; }
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() > 1 && !pages.dragging) pages.blocked = true;
            pages.move(event.getX(), event.getY());
            return pages.dragging;
        }
        return false;
    }

    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        // Native vertical sliders claim DOWN before its direction is known. Only
        // the horizontal pager may cancel them; native horizontal seeking owns its region.
        if (!disallow || pages.blocked || pages.vertical) super.requestDisallowInterceptTouchEvent(disallow);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getPointerCount() > 1 && pages.dragging) {
            pages.dragging = false; pages.blocked = true;
            settlePage(page);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            boolean wasVisible = musicPage.getVisibility() == View.VISIBLE;
            pageOffset = pages.move(event.getX(), event.getY());
            if (pages.dragging) {
                firstPage.setVisibility(View.VISIBLE); musicPage.setVisibility(View.VISIBLE);
                if (!wasVisible) musicPage.setActive(true);
                renderPages();
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (pages.dragging) {
                float speed = 0;
                if (pageVelocity != null) { pageVelocity.computeCurrentVelocity(1000); speed = pageVelocity.getXVelocity(); }
                settlePage(pages.finish(event.getX(), speed, dp(600), event.getActionMasked() == MotionEvent.ACTION_CANCEL));
            }
        }
        return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setScrollable(true);
        info.addAction(page == 0 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && page == 0) { settlePage(1); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && page == 1) { settlePage(0); return true; }
        return super.performAccessibilityAction(action, args);
    }
    private void size(View view, int x, int y, int width, int height) {
        int localTop = 0;
        // Reserve only the intersecting camera cell, never a full-width status-bar band.
        for (Rect hole : cutouts)
            if (hole.left < x + width && hole.right > x && hole.bottom > y && hole.top < y + height)
                localTop = Math.max(localTop, Math.min(height - 1, hole.bottom - y + dp(3)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, width), Math.max(1, height - localTop));
        lp.leftMargin = x; lp.topMargin = y + localTop;
        view.setLayoutParams(lp);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        finishPage();
        if (listening) return;
        listening = true;
        String[] specs = {"wifi", "cell", "bt"};
        for (int i = 0; i < specs.length; i++) {
            View cell = cells[i];
            releases.add(MiStepSystemActions.observeQuickSetting(specs[i], state -> {
                if (listening && !(cell instanceof TextView)) MiStepOs4Controls.state(cell, state.nativeState);
            }));
        }
        try {
            context.registerReceiver(volumeReceiver, new IntentFilter("android.media.VOLUME_CHANGED_ACTION"), Context.RECEIVER_EXPORTED);
            releases.add(() -> context.unregisterReceiver(volumeReceiver));
        } catch (Throwable e) { MiStepOs4Controls.failed("volume observer", e); }
        try {
            context.getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, brightnessObserver);
            context.getContentResolver().registerContentObserver(Settings.System.getUriFor("screen_brightness_float"), false, brightnessObserver);
            releases.add(() -> context.getContentResolver().unregisterContentObserver(brightnessObserver));
        } catch (Throwable e) { MiStepOs4Controls.failed("brightness observer", e); }
        try {
            DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            // The native controller uses EVENT_FLAG_DISPLAY_BRIGHTNESS (32), including range changes.
            XposedHelpers.callMethod(displays, "registerDisplayListener", brightnessDisplayListener, main, 32L);
            releases.add(() -> displays.unregisterDisplayListener(brightnessDisplayListener));
        } catch (Throwable e) { MiStepOs4Controls.failed("brightness display observer", e); }
        try {
            networks = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            networks.registerNetworkCallback(new NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), vpnCallback, main);
            releases.add(() -> networks.unregisterNetworkCallback(vpnCallback));
        } catch (Throwable e) { MiStepOs4Controls.failed("vpn observer", e); }
        refreshVpn(); refreshValues();
        de.robv.android.xposed.XposedBridge.log("[Mi Step] OS4 top controls attached model=" + deviceName()
                + " volume=" + (volume == null ? "none" : volume.slider.getClass().getName())
                + " brightness=" + (brightness == null ? "none" : brightness.slider.getClass().getName()));
    }
    @Override protected void onDetachedFromWindow() {
        cancelPageAnimation();
        if (pageVelocity != null) { pageVelocity.recycle(); pageVelocity = null; }
        pageOffset = page * pageSpan();
        musicPage.setActive(false);
        listening = false;
        for (Runnable release : releases) try { release.run(); } catch (Throwable e) { MiStepOs4Controls.failed("release", e); }
        releases.clear();
        volumeTracking = brightnessTracking = false;
        for (View cell : cells) if (!(cell instanceof TextView)) try { XposedHelpers.callMethod(cell, "recycle"); } catch (Throwable ignored) {}
        if (!(scanner instanceof TextView)) try { XposedHelpers.callMethod(scanner, "recycle"); } catch (Throwable ignored) {}
        for (View code : new View[]{payment, transit}) if (!(code instanceof TextView))
            try { XposedHelpers.callMethod(code, "recycle"); } catch (Throwable ignored) {}
        super.onDetachedFromWindow();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) { refreshValues(); refreshVpn(); }
    }
    private void refreshVpn() {
        if (!listening || cells[3] instanceof TextView) return;
        boolean active = false;
        try {
            if (networks != null) for (Network network : networks.getAllNetworks()) {
                NetworkCapabilities capabilities = networks.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { active = true; break; }
            }
            MiStepOs4Controls.state(cells[3], MiStepOs4Controls.simpleState(context, "VPN", active,
                    "ic_qs_vpn", "stat_sys_vpn_ic", "stat_sys_vpn_ic_dark"));
        } catch (Throwable e) { MiStepOs4Controls.failed("vpn state", e); }
    }
    private void refreshValues() {
        updating = true;
        try {
            if (volume != null && audio != null && !volumeTracking) {
                int value = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                volume.value(value, "音量 " + Math.round(value * 100f / Math.max(1, volume.slider.getMax())) + "%");
            }
            if (brightness != null && !brightnessTracking) {
                DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                int gamma = MiStepBrightness.read(displays).progress(brightnessUtils());
                brightness.value(gamma, "亮度 " + Math.round(gamma * 100f / Math.max(1, brightness.slider.getMax())) + "%");
            }
        } catch (Throwable e) { MiStepOs4Controls.failed("refresh", e); }
        finally { updating = false; }
    }
    private Class<?> brightnessUtils() {
        return XposedHelpers.findClass("com.android.systemui.controlcenter.policy.BrightnessUtils", context.getClassLoader());
    }
    private void setBrightness(int value) {
        try {
            DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            MiStepBrightness.read(displays).write(displays, brightnessUtils(), value);
        } catch (Throwable e) { MiStepOs4Controls.failed("brightness", e); }
    }
    private String deviceName() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            for (String property : new String[]{"ro.product.marketname", "ro.product.vendor.marketname", "ro.product.system.marketname"}) {
                String name = (String) XposedHelpers.callStaticMethod(properties, "get", property, "");
                if (name != null && !name.trim().isEmpty()) return name.trim();
            }
        } catch (Throwable ignored) {}
        return Build.MODEL;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
