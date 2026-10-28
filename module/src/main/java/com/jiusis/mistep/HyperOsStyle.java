package com.jiusis.mistep;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

/* JADX INFO: loaded from: classes.dex */
final class HyperOsStyle {
    private static final int DARK_PAGE = -15592682;
    private static final int DARK_TEXT = -657672;
    private static final int DARK_TEXT_SECONDARY = -5657163;
    private static final int FALLBACK_ACCENT_DARK = -8800001;
    private static final int FALLBACK_ACCENT_LIGHT = -13664019;
    private static final int LIGHT_PAGE = -657672;
    private static final int LIGHT_TEXT = -15263717;
    private static final int LIGHT_TEXT_SECONDARY = -8946814;

    private HyperOsStyle() {
    }

    static boolean dark(Context context) {
        return (context.getResources().getConfiguration().uiMode & 48) == 32;
    }

    static int textPrimary(Context context) {
        if (dark(context)) {
            return -657672;
        }
        return LIGHT_TEXT;
    }

    static int textSecondary(Context context) {
        return dark(context) ? DARK_TEXT_SECONDARY : LIGHT_TEXT_SECONDARY;
    }

    static int page(Context context) {
        if (dark(context)) {
            return DARK_PAGE;
        }
        return -657672;
    }

    static int scrim(Context context) {
        return dark(context) ? -1946157056 : 1376785176;
    }

    static int stroke(Context context) {
        return dark(context) ? 620756991 : 603979776;
    }

    static int accent(Context context) {
        int identifier = context.getResources().getIdentifier("system_accent1_200", "color", "android");
        if (identifier != 0) {
            try {
                return context.getResources().getColor(identifier, context.getTheme());
            } catch (Throwable th) {
            }
        }
        return dark(context) ? FALLBACK_ACCENT_DARK : FALLBACK_ACCENT_LIGHT;
    }

    static int accentSoft(Context context, int i) {
        return withAlpha(accent(context), i);
    }

    static Drawable topBackground(Context context) {
        boolean zDark = dark(context);
        int i = zDark ? -15263716 : -394500;
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{blend(i, zDark ? -12371120 : -729880, zDark ? 0.07f : 0.1f), blend(i, accent(context), zDark ? 0.12f : 0.085f), blend(i, zDark ? -14857392 : -2493713, zDark ? 0.06f : 0.1f)});
    }

    static Drawable topWallpaperWash(Context context) {
        boolean zDark = dark(context);
        int i = zDark ? -652600544 : -956564739;
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{withAlpha(blend(i, zDark ? -12371120 : -729880, zDark ? 0.07f : 0.1f), 168), withAlpha(blend(i, accent(context), zDark ? 0.12f : 0.085f), 168), withAlpha(blend(i, zDark ? -14857392 : -2493713, zDark ? 0.06f : 0.1f), 168)});
    }

    static int wallpaperTint(Context context) {
        return dark(context) ? 1107296256 : 922746879;
    }

    static int topWallpaperTint(Context context) {
        return dark(context) ? 637534208 : 536870911;
    }

    static Bitmap blurredWallpaper(Context context, int i, int i2) throws Exception {
        Drawable drawable;
        if (i <= 0 || i2 <= 0 || (drawable = WallpaperManager.getInstance(context).getDrawable()) == null) {
            return null;
        }
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(i, i2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmapCreateBitmap);
        drawable.setBounds(0, 0, i, i2);
        drawable.draw(canvas);
        try {
            return blurredBitmap(bitmapCreateBitmap, i, i2);
        } finally {
            bitmapCreateBitmap.recycle();
        }
    }

    static Bitmap blurredBitmap(Bitmap bitmap, int i, int i2) {
        if (bitmap == null || bitmap.isRecycled() || i <= 0 || i2 <= 0) {
            return null;
        }
        int iMax = Math.max(1, i / 3);
        int iMax2 = Math.max(1, i2 / 3);
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(iMax, iMax2, Bitmap.Config.ARGB_8888);
        new Canvas(bitmapCreateBitmap).drawBitmap(bitmap, (Rect) null, new RectF(0.0f, 0.0f, iMax, iMax2), new Paint(6));
        int[] iArrBoxBlur = new int[iMax * iMax2];
        bitmapCreateBitmap.getPixels(iArrBoxBlur, 0, iMax, 0, 0, iMax, iMax2);
        int iMax3 = Math.max(4, Math.min(iMax, iMax2) / 55);
        for (int i3 = 0; i3 < 3; i3++) {
            iArrBoxBlur = boxBlur(iArrBoxBlur, iMax, iMax2, iMax3);
        }
        bitmapCreateBitmap.setPixels(iArrBoxBlur, 0, iMax, 0, 0, iMax, iMax2);
        Bitmap bitmapCreateBitmap2 = Bitmap.createBitmap(i, i2, Bitmap.Config.ARGB_8888);
        new Canvas(bitmapCreateBitmap2).drawBitmap(bitmapCreateBitmap, (Rect) null, new RectF(0.0f, 0.0f, i, i2), new Paint(6));
        bitmapCreateBitmap.recycle();
        return bitmapCreateBitmap2;
    }

    private static int[] boxBlur(int[] iArr, int i, int i2, int i3) {
        int[] iArr2 = new int[iArr.length];
        for (int i4 = 0; i4 < i2; i4++) {
            int i5 = i4 * i;
            for (int i6 = 0; i6 < i; i6++) {
                int iMin = Math.min(i - 1, i6 + i3);
                int iRed = 0;
                int i7 = 0;
                int iGreen = 0;
                int iBlue = 0;
                for (int iMax = Math.max(0, i6 - i3); iMax <= iMin; iMax++) {
                    int i8 = iArr[i5 + iMax];
                    iRed += Color.red(i8);
                    iGreen += Color.green(i8);
                    iBlue += Color.blue(i8);
                    i7++;
                }
                iArr2[i5 + i6] = Color.rgb(iRed / i7, iGreen / i7, iBlue / i7);
            }
        }
        int[] iArr3 = new int[iArr.length];
        for (int i9 = 0; i9 < i2; i9++) {
            int iMax2 = Math.max(0, i9 - i3);
            int iMin2 = Math.min(i2 - 1, i9 + i3);
            for (int i10 = 0; i10 < i; i10++) {
                int iRed2 = 0;
                int i11 = 0;
                int iGreen2 = 0;
                int iBlue2 = 0;
                for (int i12 = iMax2; i12 <= iMin2; i12++) {
                    int i13 = iArr2[(i12 * i) + i10];
                    iRed2 += Color.red(i13);
                    iGreen2 += Color.green(i13);
                    iBlue2 += Color.blue(i13);
                    i11++;
                }
                iArr3[(i9 * i) + i10] = Color.rgb(iRed2 / i11, iGreen2 / i11, iBlue2 / i11);
            }
        }
        return iArr3;
    }

    static Drawable glassPill(Context context, float f) {
        return glassPill(context, f, false);
    }

    static Drawable glassPill(Context context, float f, boolean z) {
        int i;
        int i2;
        if (dark(context)) {
            i = z ? -650887862 : -651481806;
            i2 = z ? -1037355966 : -1038213083;
        } else {
            i = z ? -219220481 : -218103809;
            i2 = z ? -572264449 : -571608844;
        }
        GradientDrawable gradientDrawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{i, i2});
        gradientDrawable.setCornerRadius(dp(context, f));
        gradientDrawable.setStroke(Math.max(1, dp(context, 0.75f)), stroke(context));
        return gradientDrawable;
    }

    static Drawable glassSheet(Context context, float f) {
        GradientDrawable gradientDrawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{dark(context) ? -265934803 : -117440513, dark(context) ? -317187554 : -353176329});
        gradientDrawable.setCornerRadius(dp(context, f));
        gradientDrawable.setStroke(Math.max(1, dp(context, 0.75f)), stroke(context));
        return gradientDrawable;
    }

    static Drawable glassButton(Context context, float f, boolean z) {
        GradientDrawable gradientDrawable = (GradientDrawable) (z ? glassPill(context, f, true) : glassPill(context, f));
        GradientDrawable gradientDrawable2 = new GradientDrawable();
        gradientDrawable2.setShape(0);
        gradientDrawable2.setCornerRadius(dp(context, f));
        gradientDrawable2.setColor(-1);
        return new RippleDrawable(ColorStateList.valueOf(accentSoft(context, dark(context) ? 112 : 74)), gradientDrawable, gradientDrawable2);
    }

    static Drawable pressableItem(Context context, float f) {
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setCornerRadius(dp(context, f));
        gradientDrawable.setColor(dark(context) ? 268435455 : 16777215);
        GradientDrawable gradientDrawable2 = new GradientDrawable();
        gradientDrawable2.setCornerRadius(dp(context, f));
        gradientDrawable2.setColor(-1);
        return new RippleDrawable(ColorStateList.valueOf(accentSoft(context, dark(context) ? 102 : 56)), gradientDrawable, gradientDrawable2);
    }

    static Drawable softButton(Context context, int i, float f) {
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setCornerRadius(dp(context, f));
        gradientDrawable.setColor(i);
        gradientDrawable.setStroke(Math.max(1, dp(context, 0.75f)), stroke(context));
        GradientDrawable gradientDrawable2 = new GradientDrawable();
        gradientDrawable2.setCornerRadius(dp(context, f));
        gradientDrawable2.setColor(-1);
        return new RippleDrawable(ColorStateList.valueOf(accentSoft(context, dark(context) ? 85 : 48)), gradientDrawable, gradientDrawable2);
    }

    static Drawable emptyCell(Context context) {
        int[] iArr;
        boolean zDark = dark(context);
        GradientDrawable.Orientation orientation = GradientDrawable.Orientation.TL_BR;
        if (zDark) {
            iArr = new int[]{-14408149, -15000287};
        } else {
            iArr = new int[]{-526086, -1183500};
        }
        GradientDrawable gradientDrawable = new GradientDrawable(orientation, iArr);
        gradientDrawable.setCornerRadius(0.0f);
        return gradientDrawable;
    }

    static int transitionBackground(Context context) {
        return dark(context) ? -15395045 : -789000;
    }

    static int dp(Context context, float f) {
        return Math.round(f * context.getResources().getDisplayMetrics().density);
    }

    static void pressScale(View view, float f) {
        view.animate().cancel();
        view.animate().scaleX(f).scaleY(f).setDuration(80L).start();
    }

    static void releaseScale(View view) {
        view.animate().cancel();
        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150L).start();
    }

    private static int withAlpha(int i, int i2) {
        return (i & 16777215) | ((i2 & 255) << 24);
    }

    private static int blend(int i, int i2, float f) {
        float fMax = Math.max(0.0f, Math.min(1.0f, f));
        return Color.rgb(Math.round(Color.red(i) + ((Color.red(i2) - Color.red(i)) * fMax)), Math.round(Color.green(i) + ((Color.green(i2) - Color.green(i)) * fMax)), Math.round(Color.blue(i) + ((Color.blue(i2) - Color.blue(i)) * fMax)));
    }
}
