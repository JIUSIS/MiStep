package com.jiusis.mistep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.SurfaceControl;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;

/** Physical wallpaper backing below the native wallpaper and real tasks. */
final class MiStepWorkspaceBackdrop {
    static final String ACTION = "com.jiusis.mistep.WORKSPACE_WALLPAPER_PARENT";

    /** Caller holds WM's lock. Only the live SystemUI Host can acquire this parent. */
    static Bundle capture(Object display, Intent intent) {
        Bundle request = intent.getBundleExtra("state");
        IBinder token = request == null ? null : request.getBinder("host");
        if (token == null || display == null) return rejected("host token/display missing");
        Object service = XposedHelpers.getObjectField(display, "mWmService");
        Object host = ((Map<?, ?>) XposedHelpers.getObjectField(service, "mWindowMap")).get(token);
        if (host == null) return rejected("host window not registered");
        if (!MiStepWindowIdentity.ownsHost(host, display))
            return rejected("host display/owner changed");
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(host, "isVisible")))
            return rejected("host surface not visible yet");
        Object attrs = XposedHelpers.getObjectField(host, "mAttrs");
        if (!"MiStepHost".contentEquals((CharSequence) XposedHelpers.callMethod(attrs, "getTitle"))
                || !"com.android.systemui".equals(XposedHelpers.getObjectField(attrs, "packageName"))) return rejected("host identity mismatch");
        Object controller=XposedHelpers.getObjectField(display,"mWallpaperController");
        Object tokens=XposedHelpers.getObjectField(controller,"mWallpaperTokens");
        if(!(tokens instanceof List<?>))return rejected("wallpaper token list unavailable");
        Object parent=null,anchor=null;
        for(Object candidate:(List<?>)tokens) {
            if(candidate==null || XposedHelpers.callMethod(candidate,"getDisplayContent")!=display)continue;
            Object anchorNode=candidate;
            Object candidateParent=XposedHelpers.callMethod(candidate,"getParent");
            // On first entry no magnification spec has been applied yet. Test
            // the native eligibility too, or this supposedly physical wallpaper
            // will shrink with its parent as soon as the main animation begins.
            for(Object node=candidateParent;node!=null;node=XposedHelpers.callMethod(node,"getParent")) {
                String name=(String)XposedHelpers.callMethod(node,"getName");
                boolean willMagnify=(name==null || !name.contains("OneHanded:"))
                        && Boolean.TRUE.equals(XposedHelpers.callMethod(node,"shouldMagnify"));
                if(XposedHelpers.getObjectField(node,"mLastMagnificationSpec")!=null || willMagnify) {
                    anchorNode=node;candidateParent=XposedHelpers.callMethod(node,"getParent");
                }
            }
            SurfaceControl candidateAnchor=(SurfaceControl)XposedHelpers.callMethod(anchorNode,"getSurfaceControl");
            SurfaceControl candidateContainer=candidateParent==null?null
                    :(SurfaceControl)XposedHelpers.callMethod(candidateParent,"getSurfaceControl");
            if(candidateAnchor==null || candidateContainer==null
                    || !candidateAnchor.isValid() || !candidateContainer.isValid())continue;
            parent=candidateContainer;anchor=candidateAnchor;break;
        }
        if (parent == null || anchor == null) return rejected("physical wallpaper anchor unavailable");
        Bundle reply = new Bundle();
        reply.putParcelable("parent", (SurfaceControl)parent);
        reply.putParcelable("anchor", (SurfaceControl)anchor);
        return reply;
    }

    static void acquire(Context context, Handler handler, IBinder host, Bitmap wallpaper,
            int width, int height, Consumer<MiStepLiveBackdrop> ready, Consumer<String> log) {
        AtomicBoolean done = new AtomicBoolean();
        Runnable timeout = () -> {
            if (done.compareAndSet(false, true)) {log.accept("workspace wallpaper parent timeout"); ready.accept(null);}
        };
        Bundle state = new Bundle(); state.putBinder("host", host);
        handler.postDelayed(timeout, 1000L);
        try {
            context.sendOrderedBroadcast(new Intent(ACTION).setPackage("android").putExtra("state", state),
                    android.Manifest.permission.WRITE_SECURE_SETTINGS, new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    Bundle result = getResultExtras(false);
                    SurfaceControl parent = result == null ? null : result.getParcelable("parent", SurfaceControl.class);
                    SurfaceControl anchor = result == null ? null : result.getParcelable("anchor", SurfaceControl.class);
                    MiStepLiveBackdrop backdrop = null;
                    try {
                        if (!done.compareAndSet(false, true)) return;
                        handler.removeCallbacks(timeout);
                        if (getResultCode() != -1 || parent == null || anchor == null)
                            throw new IllegalStateException("native wallpaper parent rejected");
                        backdrop = MiStepLiveBackdrop.create(parent, wallpaper, width, height, log);
                        try (SurfaceControl.Transaction tx=new SurfaceControl.Transaction()) {
                            // The native opaque wallpaper scales with the main area.
                            // Keep it above this full-screen blur so Home stays clear,
                            // while the backing still fills the surrounding gaps.
                            XposedHelpers.callMethod(tx,"setRelativeLayer",backdrop.surface,anchor,-1);
                            tx.apply();
                        }
                        log.accept("workspace wallpaper below real task area ready");
                    } catch (Throwable error) {
                        if (backdrop != null) {backdrop.close(); backdrop = null;}
                        log.accept("workspace wallpaper unavailable: " + error);
                    } finally {
                        if (parent != null) parent.release();
                        if (anchor != null) anchor.release();
                    }
                    ready.accept(backdrop);
                }
            }, handler, 0, null, null);
        } catch (Throwable error) {
            handler.removeCallbacks(timeout);
            if (done.compareAndSet(false, true)) {log.accept("workspace wallpaper request: " + error); ready.accept(null);}
        }
    }

    private static Bundle rejected(String reason) {
        XposedBridge.log("MiStepProbe: workspace wallpaper parent rejected gate=" + reason);
        return null;
    }

    private MiStepWorkspaceBackdrop() {}
}
