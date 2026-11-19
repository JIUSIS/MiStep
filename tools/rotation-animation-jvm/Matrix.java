package android.graphics;

/** Test affine matrix with Android's column-vector / post-concat conventions. */
public class Matrix {
    private final float[] values = new float[9];
    public Matrix() { reset(); }
    public void reset() { setValues(new float[]{1,0,0,0,1,0,0,0,1}); }
    public void set(Matrix other) { other.getValues(values); }
    public void setValues(float[] source) { System.arraycopy(source,0,values,0,9); }
    public void getValues(float[] target) { System.arraycopy(values,0,target,0,9); }
    public boolean mapRect(RectF rect) {
        float[] x={rect.left,rect.right,rect.right,rect.left};
        float[] y={rect.top,rect.top,rect.bottom,rect.bottom};
        float left=Float.POSITIVE_INFINITY,top=left,right=Float.NEGATIVE_INFINITY,bottom=right;
        for(int i=0;i<4;i++) {
            float px=values[0]*x[i]+values[1]*y[i]+values[2];
            float py=values[3]*x[i]+values[4]*y[i]+values[5];
            left=Math.min(left,px);right=Math.max(right,px);
            top=Math.min(top,py);bottom=Math.max(bottom,py);
        }
        rect.set(left,top,right,bottom);
        return values[1]==0&&values[3]==0;
    }
    public boolean postScale(float x,float y,float px,float py) {
        values[0]*=x;values[1]*=x;values[2]=values[2]*x+px*(1-x);
        values[3]*=y;values[4]*=y;values[5]=values[5]*y+py*(1-y);
        return true;
    }
    public boolean postTranslate(float x,float y) { values[2]+=x;values[5]+=y;return true; }
}
