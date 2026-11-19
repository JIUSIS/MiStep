package android.view;

public class SurfaceControl {
    public boolean valid = true;
    public SurfaceControl parent;
    public String name;
    public int width, height, layer;
    public float x, y;
    public boolean shown;
    public boolean isValid() { return valid; }
    public static class Builder {
        public SurfaceControl result = new SurfaceControl();
        public Builder setParent(SurfaceControl p) { result.parent = p; return this; }
        public Builder setContainerLayer() { return this; }
        public Builder setName(String n) { result.name = n; return this; }
        public Builder setHidden(boolean h) { result.shown = !h; return this; }
        public SurfaceControl build() { return result; }
    }
    public static class Transaction {
        public int removes, reparents;
        public SurfaceControl removed;
        public Transaction setWindowCrop(SurfaceControl s, int w, int h) { s.width=w; s.height=h; return this; }
        public Transaction setPosition(SurfaceControl s, float x, float y) { s.x=x; s.y=y; return this; }
        public Transaction setLayer(SurfaceControl s, int layer) { s.layer=layer; return this; }
        public Transaction show(SurfaceControl s) { s.shown=true; return this; }
        public Transaction reparent(SurfaceControl s, SurfaceControl p) { reparents++; s.parent=p; return this; }
        public Transaction remove(SurfaceControl s) {
            if (s.parent != null && s == s.parent.parent) throw new AssertionError("cycle");
            removes++; removed=s; s.valid=false; return this;
        }
    }
}
