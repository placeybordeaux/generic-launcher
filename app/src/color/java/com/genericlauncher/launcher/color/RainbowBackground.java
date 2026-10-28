package com.genericlauncher.launcher.color;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.Nullable;

import com.genericlauncher.launcher.personality.Background;
import com.genericlauncher.launcher.personality.example.ColorGroups;

/**
 * The colour build's backdrop: the drawer's own spectrum, painted behind it.
 *
 * <p>One vertical gradient through the group accents in the order the sections appear (Black, Red,
 * Orange … White), darkened so icons stay readable on top. Scrolling the drawer moves the sections
 * across a fixed spectrum, so the colour under any section is the section's own colour.
 *
 * <p><b>Deliberately not scroll-reactive.</b> The launcher this was extracted from repainted the
 * gradient from the grid's scroll listener, tracking only the sections currently on screen. The
 * {@link Background} seam hands a personality a {@code Context} and takes a {@link View} — it has
 * no scroll position and no per-frame callback, on purpose: a background that runs code on every
 * frame of every scroll is a background that can make the drawer janky, and the seam should not
 * make that the easy thing to write. A static full-spectrum gradient is the same idea within the
 * budget the seam allows, and it costs one shader for the drawer's lifetime instead of one
 * comparison per frame.
 */
final class RainbowBackground implements Background {

    @Override
    public View createView(Context context) {
        return new SpectrumView(context);
    }

    @Override
    public String id() { return "rainbow"; }

    private static final class SpectrumView extends View {

        /** Cached gradient, rebuilt only when the view height changes. */
        @Nullable private Shader shader;
        private int shaderH = -1;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors = spectrum();

        SpectrumView(Context c) {
            super(c);
            // Contract: a background must not take touches meant for the app grid above it.
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            canvas.drawColor(0xFF0E0E0E);
            if (shader == null || shaderH != h) {
                shader = new LinearGradient(0, 0, 0, h, colors, null, Shader.TileMode.CLAMP);
                shaderH = h;
            }
            paint.setShader(shader);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }

        /** The group accents, in section order, darkened to sit under icons. */
        private static int[] spectrum() {
            int[] groups = {
                    ColorGroups.BLACK, ColorGroups.RED, ColorGroups.ORANGE, ColorGroups.YELLOW,
                    ColorGroups.GREEN, ColorGroups.TEAL, ColorGroups.BLUE, ColorGroups.PURPLE,
                    ColorGroups.PINK, ColorGroups.GRAY, ColorGroups.WHITE,
            };
            int[] out = new int[groups.length];
            for (int i = 0; i < groups.length; i++) out[i] = darken(GroupAccents.of(groups[i]));
            return out;
        }

        /** Rich, but dark enough that icons on their dark backing stay readable. */
        private static int darken(int c) {
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[2] = hsv[2] * 0.5f;
            return Color.HSVToColor(hsv);
        }
    }
}
