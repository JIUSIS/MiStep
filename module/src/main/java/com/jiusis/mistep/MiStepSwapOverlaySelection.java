package com.jiusis.mistep;

import android.view.WindowManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import de.robv.android.xposed.XposedHelpers;

/** WMS lock only. A toolbox may be magnified at its WindowState or its own WindowToken. */
final class MiStepSwapOverlaySelection {
    static MiStepSwapOverlayFrames.Row[] capture(Object display,LiveSwapZoom.Zoom old) {
        Object spec=XposedHelpers.getObjectField(display,"mMagnificationSpec");
        if(spec==null || !old.sameTuple(number(spec,"scale"),number(spec,"offsetX"),number(spec,"offsetY")))
            throw new IllegalStateException("overlay native zoom changed");
        Object service=XposedHelpers.getObjectField(display,"mWmService");
        Map<?,?> windows=(Map<?,?>)XposedHelpers.getObjectField(service,"mWindowMap");
        ArrayList<MiStepSwapOverlayFrames.Row> rows=new ArrayList<>();HashSet<Integer> seen=new HashSet<>();
        for(Object window:windows.values()) {
            WindowManager.LayoutParams attrs=(WindowManager.LayoutParams)XposedHelpers.getObjectField(window,"mAttrs");
            if(!"com.miui.securitycenter".equals(attrs.packageName)
                    || XposedHelpers.getIntField(window,"mOwnerUid")!=1000
                    || XposedHelpers.callMethod(window,"getDisplayContent")!=display
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(window,"isVisible")))continue;
            String title=String.valueOf(attrs.getTitle());
            if(!((attrs.type==2003 && "com.miui.securitycenter/.FloatingWindow".equals(title))
                    || (attrs.type==2026 && "DockAssistantView".equals(title))))continue;
            Object token=XposedHelpers.getObjectField(window,"mToken");
            Object owner=null;
            for(Object candidate:new Object[]{token,window}) {
                if(candidate!=null && XposedHelpers.getObjectField(candidate,"mLastMagnificationSpec")==spec) {
                    owner=candidate;break;
                }
            }
            // Never move a shared display-area ancestor, which may also contain other apps.
            if(owner==null) {
                if(attrs.type==2003)throw new IllegalStateException("toolbox has no independent magnification owner");
                continue; // A native unmagnified trigger has no main-region translation to follow.
            }
            Object surface=XposedHelpers.getObjectField(owner,"mSurfaceControl");
            if(surface==null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface,"isValid")))continue;
            int id=((Number)XposedHelpers.callMethod(surface,"getLayerId")).intValue();
            if(!seen.add(id))continue;
            Object position=XposedHelpers.getObjectField(owner,"mLastSurfacePosition");
            rows.add(new MiStepSwapOverlayFrames.Row(surface,
                    XposedHelpers.getIntField(position,"x"),XposedHelpers.getIntField(position,"y")));
            if(rows.size()>8)throw new IllegalStateException("unexpected toolbox window count");
        }
        return rows.toArray(new MiStepSwapOverlayFrames.Row[0]);
    }
    private static float number(Object object,String field) {
        return ((Number)XposedHelpers.getObjectField(object,field)).floatValue();
    }
}
