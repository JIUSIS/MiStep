package com.jiusis.mistep;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/* JADX INFO: loaded from: classes.dex */
final class LiquidGlassDrawable extends Drawable {
    private static final String EDGE_SHADER = "uniform shader content;\nuniform float2 size;\nuniform float radius;\nuniform float refract;\nuniform float2 press;\nuniform float energy;\nuniform float3 tint;\nuniform float tintAlpha;\nuniform float2 lightDir;\n\nfloat sdRound(float2 p, float2 halfSize, float r) {\n  float2 q = abs(p) - (halfSize - float2(r));\n  return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n}\n\nhalf4 main(float2 p) {\n  float2 halfSize = size * 0.5;\n  float2 c = p - halfSize;\n  float sd = sdRound(c, halfSize, radius);\n  if (sd > 0.0) return half4(0.0);\n  float edge = clamp(-sd / max(refract, 1.0), 0.0, 1.0);\n  float lens = (1.0 - edge) * (1.0 - edge);\n  float2 normal = normalize(c + float2(0.0001));\n  float2 samplePoint = p + normal * lens * refract * 0.72;\n  half4 base = content.eval(samplePoint);\n  base.rgb = mix(base.rgb, tint, tintAlpha);\n  float rim = 1.0 - smoothstep(0.0, 0.24, edge);\n  float spec = rim * max(0.0, dot(normal, normalize(lightDir)));\n  base.rgb += spec * 0.30;\n  float shade = rim * max(0.0, dot(normal, -normalize(lightDir)));\n  base.rgb -= shade * 0.055;\n  float2 pd = p - press;\n  float glowRadius = max(8.0, min(size.x, size.y) * 0.62);\n  float glow = exp(-dot(pd, pd) / (2.0 * glowRadius * glowRadius));\n  base.rgb += glow * energy * 0.24;\n  base.a = 1.0;\n  return base;\n}\n";
    private final boolean accented;
    private final Context context;
    private RuntimeShader edgeShader;
    private float pressEnergy;
    private float pressX;
    private float pressY;
    private final float radius;
    private final float refraction;
    private int screenHeight;
    private int screenWidth;
    private int screenX;
    private int screenY;
    private boolean shaderFailed;
    private Bitmap wallpaper;
    private BitmapShader wallpaperShader;
    private final Paint shaderPaint = new Paint(1);
    private final Paint contentPaint = new Paint(7);
    private final Paint fillPaint = new Paint(1);
    private final Paint washPaint = new Paint(1);
    private final Paint rimPaint = new Paint(1);
    private final Paint shadePaint = new Paint(1);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private int alpha = 255;

    LiquidGlassDrawable(Context context, float f, boolean z) {
        this.context = context;
        this.radius = HyperOsStyle.dp(context, f);
        this.refraction = HyperOsStyle.dp(context, z ? 13.0f : 9.0f);
        this.accented = z;
        try {
            this.edgeShader = new RuntimeShader(EDGE_SHADER);
        } catch (Throwable th) {
            this.shaderFailed = true;
        }
    }

    void setWallpaper(Bitmap bitmap, int i, int i2) {
        this.wallpaper = bitmap;
        this.screenWidth = Math.max(1, i);
        this.screenHeight = Math.max(1, i2);
        rebuildWallpaperShader();
        invalidateSelf();
    }

    void setScreenPosition(int i, int i2) {
        if (this.screenX == i && this.screenY == i2) {
            return;
        }
        this.screenX = i;
        this.screenY = i2;
        updateWallpaperMatrix();
        invalidateSelf();
    }

    void setPress(float f, float f2, float f3) {
        this.pressX = f;
        this.pressY = f2;
        this.pressEnergy = Math.max(0.0f, Math.min(1.0f, f3));
        invalidateSelf();
    }

    void clearPress() {
        this.pressEnergy = 0.0f;
        invalidateSelf();
    }

    private void rebuildWallpaperShader() {
        if (this.wallpaper == null || this.wallpaper.isRecycled()) {
            this.wallpaperShader = null;
        } else {
            this.wallpaperShader = new BitmapShader(this.wallpaper, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            updateWallpaperMatrix();
        }
    }

    private void updateWallpaperMatrix() {
        if (this.wallpaperShader == null || this.wallpaper == null || this.wallpaper.isRecycled()) {
            return;
        }
        float fMax = this.screenWidth / Math.max(1, this.wallpaper.getWidth());
        float fMax2 = this.screenHeight / Math.max(1, this.wallpaper.getHeight());
        this.matrix.reset();
        this.matrix.setScale(fMax, fMax2);
        this.matrix.postTranslate(-this.screenX, -this.screenY);
        this.wallpaperShader.setLocalMatrix(this.matrix);
    }

    @Override // android.graphics.drawable.Drawable
    public void draw(Canvas canvas) {
        boolean z;
        RectF rectF = new RectF(getBounds());
        if (rectF.isEmpty()) {
            return;
        }
        this.rect.set(rectF);
        this.path.reset();
        this.path.addRoundRect(this.rect, this.radius, this.radius, Path.Direction.CW);
        if (!this.shaderFailed && this.edgeShader != null && this.wallpaperShader != null) {
            z = true;
            try {
                this.edgeShader.setInputShader("content", this.wallpaperShader);
                this.edgeShader.setFloatUniform("size", rectF.width(), rectF.height());
                this.edgeShader.setFloatUniform("radius", this.radius);
                this.edgeShader.setFloatUniform("refract", this.refraction);
                this.edgeShader.setFloatUniform("press", this.pressX - rectF.left, this.pressY - rectF.top);
                this.edgeShader.setFloatUniform("energy", this.pressEnergy);
                int iAccent = this.accented ? HyperOsStyle.accent(this.context) : -1;
                this.edgeShader.setFloatUniform("tint", Color.red(iAccent) / 255.0f, Color.green(iAccent) / 255.0f, Color.blue(iAccent) / 255.0f);
                this.edgeShader.setFloatUniform("tintAlpha", HyperOsStyle.dark(this.context) ? 0.18f : 0.2f);
                this.edgeShader.setFloatUniform("lightDir", -0.72f, -0.69f);
                this.shaderPaint.setShader(this.edgeShader);
                this.shaderPaint.setAlpha(this.alpha);
                canvas.drawPath(this.path, this.shaderPaint);
            } catch (Throwable th) {
                this.shaderFailed = true;
                this.shaderPaint.setShader(null);
                z = false;
            }
        } else {
            z = false;
        }
        if (!z) {
            drawFallback(canvas, rectF);
        }
        this.fillPaint.setShader(new LinearGradient(rectF.left, rectF.top, rectF.right, rectF.bottom, new int[]{colorWithAlpha(-1, (this.alpha * 34) / 255), colorWithAlpha(-1, (this.alpha * 12) / 255), colorWithAlpha(-1, (this.alpha * 26) / 255)}, new float[]{0.0f, 0.58f, 1.0f}, Shader.TileMode.CLAMP));
        canvas.drawPath(this.path, this.fillPaint);
        this.washPaint.setShader(new LinearGradient(rectF.left, rectF.top, rectF.right, rectF.bottom, new int[]{colorWithAlpha(-1, (this.alpha * 92) / 255), colorWithAlpha(-1, (this.alpha * 18) / 255), colorWithAlpha(-8089187, (this.alpha * 34) / 255)}, new float[]{0.0f, 0.42f, 1.0f}, Shader.TileMode.CLAMP));
        canvas.drawPath(this.path, this.washPaint);
        int iSave = canvas.save();
        canvas.clipPath(this.path);
        this.shadePaint.setShader(new LinearGradient(0.0f, 0.66f * rectF.bottom, 0.0f, rectF.bottom, new int[]{0, colorWithAlpha(-14076867, (this.alpha * 36) / 255)}, new float[]{0.0f, 1.0f}, Shader.TileMode.CLAMP));
        canvas.drawRect(rectF, this.shadePaint);
        canvas.restoreToCount(iSave);
        this.rimPaint.setStyle(Paint.Style.STROKE);
        this.rimPaint.setStrokeWidth(Math.max(1.0f, HyperOsStyle.dp(this.context, 1.1f)));
        this.rimPaint.setShader(null);
        this.rimPaint.setColor(colorWithAlpha(-10325635, (this.alpha * 32) / 255));
        RectF rectF2 = new RectF(rectF);
        rectF2.inset(this.rimPaint.getStrokeWidth() * 0.5f, this.rimPaint.getStrokeWidth() * 0.5f);
        canvas.drawRoundRect(rectF2, this.radius - (this.rimPaint.getStrokeWidth() * 0.5f), this.radius - (this.rimPaint.getStrokeWidth() * 0.5f), this.rimPaint);
        this.rimPaint.setStyle(Paint.Style.STROKE);
        this.rimPaint.setStrokeWidth(Math.max(1.0f, HyperOsStyle.dp(this.context, 0.7f)));
        this.rimPaint.setShader(new LinearGradient(rectF.left, rectF.top, rectF.right, rectF.bottom, new int[]{colorWithAlpha(-1, (this.alpha * 205) / 255), colorWithAlpha(-1, (this.alpha * 58) / 255), colorWithAlpha(-2760474, (this.alpha * 96) / 255)}, (float[]) null, Shader.TileMode.CLAMP));
        RectF rectF3 = new RectF(rectF);
        rectF3.inset(HyperOsStyle.dp(this.context, 1.5f), HyperOsStyle.dp(this.context, 1.5f));
        canvas.drawRoundRect(rectF3, this.radius - HyperOsStyle.dp(this.context, 1.5f), this.radius - HyperOsStyle.dp(this.context, 1.5f), this.rimPaint);
    }

    private void drawFallback(Canvas canvas, RectF rectF) {
        if (this.wallpaperShader != null) {
            this.contentPaint.setShader(this.wallpaperShader);
            this.contentPaint.setAlpha(this.alpha);
            canvas.drawPath(this.path, this.contentPaint);
            this.contentPaint.setShader(null);
        }
        this.washPaint.setShader(null);
        this.washPaint.setColor(HyperOsStyle.dark(this.context) ? 822083583 : 889192447);
        this.washPaint.setAlpha(this.alpha);
        canvas.drawPath(this.path, this.washPaint);
    }

    private static int colorWithAlpha(int i, int i2) {
        return (i & 16777215) | (Math.max(0, Math.min(255, i2)) << 24);
    }

    @Override // android.graphics.drawable.Drawable
    public void setAlpha(int i) {
        this.alpha = Math.max(0, Math.min(255, i));
        invalidateSelf();
    }

    @Override // android.graphics.drawable.Drawable
    public void setColorFilter(ColorFilter colorFilter) {
        this.shaderPaint.setColorFilter(colorFilter);
        this.contentPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override // android.graphics.drawable.Drawable
    public int getOpacity() {
        return -3;
    }
}
