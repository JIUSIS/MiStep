package android.view;

/** Only the fields used by the offline layer bridge; not an Android window implementation. */
public interface WindowManager extends ViewManager {
    class LayoutParams extends ViewGroup.LayoutParams {
        public LayoutParams() { super(0, 0); }
        public static final int TYPE_APPLICATION_OVERLAY = 2038;
        public String packageName;
        public int type;
        public CharSequence title;
        public CharSequence getTitle() { return title; }
    }
}
