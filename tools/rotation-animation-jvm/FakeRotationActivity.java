package com.jiusis.mistep;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.SurfaceControl;
import com.android.server.wm.SurfaceAnimator;
import com.android.server.wm.WindowManagerService;
import com.android.server.wm.WindowState;
import java.util.ArrayList;
import java.util.List;

public class FakeRotationActivity {
    public static class Parent {
        public boolean transition;
        public Parent parent;
        public Configuration configuration=new Configuration();
        public boolean inTransition() { return transition; }
        public Parent getParent() { return parent; }
        public Configuration getConfiguration() { return configuration; }
    }
    public static class Task extends Parent {
        public Rect bounds=new Rect(0,0,3000,1725);
        public Rect getBounds() { return bounds; }
    }
    public static class State {
        public List<FakeRotationActivity> mAssociatedTokens=new ArrayList<>();
    }
    public WindowManagerService mWmService=new WindowManagerService();
    public SurfaceAnimator mSurfaceAnimator=new SurfaceAnimator();
    public int mLastLayer=7, commits, finishes, configurations, ensured;
    public boolean transition, detached;
    public WindowState window=new WindowState();
    public Task task=new Task();
    public SurfaceControl surface=new SurfaceControl(), parentSurface=new SurfaceControl(), fixed;
    public SurfaceControl.Transaction transaction=new SurfaceControl.Transaction();
    public State mFixedRotationTransformState;
    public Runnable finishCallback;
    public FakeRotationActivity() { surface.parent=parentSurface; }
    public WindowState findMainWindow() { return window; }
    public boolean inTransition() { return transition; }
    public Task getTask() { return task; }
    public Parent getParent() { return detached?null:task; }
    public SurfaceControl getParentSurfaceControl() { return parentSurface; }
    public SurfaceControl getSurfaceControl() { return surface; }
    public SurfaceControl getFixedRotationLeash() { return fixed; }
    public SurfaceControl.Builder makeSurface() { return new SurfaceControl.Builder(); }
    public SurfaceControl.Transaction getPendingTransaction() { return transaction; }
    public SurfaceControl.Transaction getSyncTransaction() { return transaction; }
    public void commitPendingTransaction() { commits++; }
    public void finishFixedRotationTransform(Runnable callback) {
        finishes++; finishCallback=callback;
        State state=mFixedRotationTransformState;
        if (callback!=null) callback.run();
        for (FakeRotationActivity token:state.mAssociatedTokens) token.mFixedRotationTransformState=null;
    }
    public void onConfigurationChanged(Configuration c) { configurations++; }
    public FakeRotationActivity asActivityRecord() { return this; }
    public boolean ensureActivityConfiguration() { ensured++; return true; }
}
