package com.jiusis.mistep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.view.SurfaceControl;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedBridge;

/** One protected request before a flip, no per-frame IPC or second animation clock. */
final class MiStepSwapOverlayBridge {
    static final String ACTION="com.jiusis.mistep.SWAP_OVERLAY_SURFACES";
    static Bundle capture(Object display,Intent intent) {
        Bundle out=new Bundle();
        LiveSwapZoom.Zoom old=new LiveSwapZoom.Zoom(intent.getLongExtra("generation",0L),
                intent.getFloatExtra("scale",0),intent.getFloatExtra("x",0),intent.getFloatExtra("y",0));
        MiStepSwapOverlayFrames.Row[] selected=MiStepSwapOverlaySelection.capture(display,old);
        ArrayList<Bundle> rows=new ArrayList<>();
        for(MiStepSwapOverlayFrames.Row row:selected) {
            Bundle item=new Bundle();item.putParcelable("surface",(Parcelable)row.surface);
            item.putFloat("x",row.x);item.putFloat("y",row.y);rows.add(item);
        }
        out.putLong("generation",old.generation);out.putParcelableArrayList("rows",rows);
        XposedBridge.log("MiStepProbe: live toolbox native owners="+rows.size()+" generation="+old.generation);
        return out;
    }
    static void acquire(Context context,Handler handler,LiveSwapZoom.Zoom old,LiveSwapZoom.Zoom target,
            Consumer<LiveTaskSwapDriver.Overlays> ready,Consumer<String> failed) {
        AtomicBoolean completed=new AtomicBoolean();
        Runnable timeout=()->{if(completed.compareAndSet(false,true))failed.accept("toolbox surface request timeout");};
        try {
            handler.postDelayed(timeout,1200L);
            context.sendOrderedBroadcast(new Intent(ACTION).setPackage("android")
                    .putExtra("generation",old.generation).putExtra("scale",old.scale)
                    .putExtra("x",old.x).putExtra("y",old.y),android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context c,Intent i) {
                            ArrayList<Bundle> rows=null;LiveTaskSwapDriver.Overlays lease=null;
                            ArrayList<SurfaceControl> received=new ArrayList<>();
                            try {
                                Bundle result=getResultExtras(false);
                                if(result!=null)rows=result.getParcelableArrayList("rows",Bundle.class);
                                if(rows!=null) {
                                    if(rows.size()>8)throw new IllegalStateException("unexpected overlay reply size");
                                    MiStepSwapOverlayFrames.Row[] items=new MiStepSwapOverlayFrames.Row[rows.size()];
                                    for(int n=0;n<items.length;n++) {
                                        Bundle row=rows.get(n);SurfaceControl surface=row.getParcelable("surface",SurfaceControl.class);
                                        if(surface!=null)received.add(surface);
                                        items[n]=new MiStepSwapOverlayFrames.Row(surface,row.getFloat("x",Float.NaN),row.getFloat("y",Float.NaN));
                                    }
                                    lease=new MiStepSwapOverlayFrames(items,old,target);
                                }
                                if(!completed.compareAndSet(false,true)) {if(lease!=null)lease.close();return;}
                                handler.removeCallbacks(timeout);
                                if(getResultCode()!=-1 || lease==null || result.getLong("generation",-1)!=old.generation) {
                                    if(lease!=null)lease.close();failed.accept("toolbox native surface lease rejected");return;
                                }
                                ready.accept(lease);
                            } catch(Throwable error) {
                                if(lease!=null)lease.close();
                                else for(SurfaceControl surface:received)surface.release();
                                if(completed.compareAndSet(false,true)) {handler.removeCallbacks(timeout);failed.accept("toolbox surface decode: "+error);}
                            }
                        }
                    },handler,0,null,null);
        } catch(Throwable error) {
            handler.removeCallbacks(timeout);
            if(completed.compareAndSet(false,true))failed.accept("toolbox surface request: "+error);
        }
    }
}
