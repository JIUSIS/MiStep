package android.graphics;

/** JVM-only geometry fake. Never packaged into the application. */
public class Rect {
    public int left, top, right, bottom;
    public Rect() {}
    public Rect(int left, int top, int right, int bottom) {
        this.left=left; this.top=top; this.right=right; this.bottom=bottom;
    }
    public void set(Rect other) {
        left=other.left; top=other.top; right=other.right; bottom=other.bottom;
    }
    public void setEmpty() {
        left=0; top=0; right=0; bottom=0;
    }
    public int width() { return right-left; }
    public int height() { return bottom-top; }
    public boolean isEmpty() { return left>=right || top>=bottom; }
    public boolean intersect(Rect other) {
        if (left < other.right && other.left < right
                && top < other.bottom && other.top < bottom) {
            left = Math.max(left, other.left); top = Math.max(top, other.top);
            right = Math.min(right, other.right); bottom = Math.min(bottom, other.bottom);
            return true;
        }
        return false;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof Rect)) return false;
        Rect rect=(Rect)other;
        return left==rect.left && top==rect.top && right==rect.right && bottom==rect.bottom;
    }
    @Override public int hashCode() { return java.util.Objects.hash(left,top,right,bottom); }
}
