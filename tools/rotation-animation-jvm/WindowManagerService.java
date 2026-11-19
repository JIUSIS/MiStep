package com.android.server.wm;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
public class WindowManagerService {
    public Context mContext=new Context();
    public SurfaceAnimationRunner mSurfaceAnimationRunner=new SurfaceAnimationRunner();
    public float scale=1;
    public int index=-1;
    public boolean fail;
    public SurfaceAnimator lastAnimator;
    public List<SurfaceAnimator> animators=new ArrayList<>();
    public float getAnimationScale(int i) { index=i; return scale; }
}
