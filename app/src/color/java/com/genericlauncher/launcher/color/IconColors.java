package com.genericlauncher.launcher.color;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.palette.graphics.Palette;

/**
 * Pixels → one representative colour, for the colour personality.
 *
 * <p>This is the {@code android.graphics}-bound half of the colour identity, and the reason
 * {@link com.genericlauncher.launcher.personality.AppDataSink} exists: it needs the icon bitmap,
 * which the framework-free {@code DrawerApp} deliberately does not expose. The other half — HSB to
 * a named group — is pure maths in {@code ColorGroups} and unit-testable with no device.
 *
 * <p>Lives entirely in the {@code color} flavour. The generic launcher never decodes a pixel for
 * colour, which is exactly the ~780ms of cold-start icon analysis the base build does not pay.
 */
final class IconColors {

    private IconColors() {}

    /** Mean colour of an icon's sufficiently-opaque pixels, or grey when there is nothing to read. */
    static int dominantColor(Drawable icon) {
        Bitmap bitmap = toBitmap(icon, 48, 48);
        try {
            // Palette's dominant swatch with filters cleared: a white-background icon reads as
            // white rather than as its accent, which is what puts it in the White section where a
            // human would look for it.
            Palette palette = Palette.from(bitmap).clearFilters().maximumColorCount(24).generate();
            Palette.Swatch swatch = palette.getDominantSwatch();
            return swatch != null ? swatch.getRgb() : averageColor(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    /** Mean of all sufficiently-opaque pixels. */
    private static int averageColor(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] px = new int[w * h];
        bitmap.getPixels(px, 0, w, 0, 0, w, h);
        long r = 0, g = 0, b = 0, count = 0;
        for (int c : px) {
            if (Color.alpha(c) < 16) continue;
            r += Color.red(c);
            g += Color.green(c);
            b += Color.blue(c);
            count++;
        }
        if (count == 0) return Color.GRAY;
        return Color.rgb((int) (r / count), (int) (g / count), (int) (b / count));
    }

    /** {@code {hue 0–360, saturation 0–1, brightness 0–1}}. */
    static float[] toHsb(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv;
    }

    private static Bitmap toBitmap(Drawable drawable, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        // LauncherApps.getIcon can return null for a corrupt or partially-installed package. A
        // blank bitmap reads as grey, which is a far better outcome on the loader's worker thread
        // than an NPE — that thread's exceptions are process death, and this is the HOME app.
        if (drawable != null) {
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
        }
        return bitmap;
    }
}
