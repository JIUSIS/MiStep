package android.graphics;

/** JVM-only geometry fake. Never packaged into the application. */
public class Rect {
    public int left, top, right, bottom;
    public Rect(int left, int top, int right, int bottom) {
        this.left=left; this.top=top; this.right=right; this.bottom=bottom;
    }
    public int width() { return right-left; }
    public int height() { return bottom-top; }
    public boolean isEmpty() { return left>=right || top>=bottom; }
    @Override public boolean equals(Object other) {
        if (!(other instanceof Rect)) return false;
        Rect rect=(Rect)other;
        return left==rect.left && top==rect.top && right==rect.right && bottom==rect.bottom;
    }
    @Override public int hashCode() { return java.util.Objects.hash(left,top,right,bottom); }
}
