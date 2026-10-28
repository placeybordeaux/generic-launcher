package com.genericlauncher.launcher.livedin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Paints a {@link Wear} look onto an icon. Two entry points with different budgets:
 *
 * <ul>
 *   <li>{@link #render} composes a whole look into a bitmap. It allocates freely (shaders, paths,
 *       a {@code Canvas}) because it runs once per app × look × size and the result is cached.</li>
 *   <li>{@link #drawPuff} runs every frame of the dust-puff animation and allocates nothing.</li>
 * </ul>
 *
 * <p>Taste notes, since this is where the taste lives: every effect is confined to the icon's own
 * pixels except the cobweb, which is strung across the bounding box's corner, in the gap a round
 * icon leaves — which is where a real one would be. Dust is warm grey, never brown; patina is a
 * sheen and a softening, never a stain.
 */
final class WearPainter {

    static final long PUFF_MS = 900;
    static final long PUFF_DELAY_MS = 300;
    private static final int PUFF_PARTICLES = 16;
    private static final int PUFF_COLOR = 0xFFEAE4D8;

    private static final int DUST_COLOR = 0xD8D2C6;      // rgb only; alpha per use
    private static final int SPECK_LIGHT = 0xF4F0E8;
    private static final int SPECK_DARK = 0x7C756B;

    private WearPainter() {}

    /** Draws {@code base} with {@code look} applied into {@code target}, which it fills. */
    static void render(Bitmap target, Drawable base, int look, Wear.Style style, int seed) {
        int w = target.getWidth();
        int h = target.getHeight();
        Canvas c = new Canvas(target);
        float p = Wear.patinaOf(look) / 3f;
        int dust = Wear.dustOf(look);

        // Outer layer: everything that is confined to the icon's own pixels (SRC_ATOP onto it).
        int outer = c.saveLayer(0, 0, w, h, null);

        // Inner layer: the platform icon through the colour shift. Its own layer so the shift
        // applies to the icon and not to the sheen and dust painted over it.
        Paint shift = new Paint();
        shift.setColorFilter(new ColorMatrixColorFilter(colorShift(p, dust, style)));
        int inner = c.saveLayer(0, 0, w, h, shift);
        Rect old = base.copyBounds();
        base.setBounds(0, 0, w, h);
        base.draw(c);
        base.setBounds(old);
        c.restoreToCount(inner);

        Paint atop = new Paint(Paint.ANTI_ALIAS_FLAG);
        atop.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        if (p > 0) paintPatina(c, w, h, p, Wear.patinaOf(look), style, seed, atop);
        if (dust > 0) paintDust(c, w, h, dust, seed, atop);
        c.restoreToCount(outer);

        int cobweb = Wear.cobwebOf(look);
        if (cobweb > 0) paintCobweb(c, w, h, cobweb, seed);
    }

    // -------------------------------------------------------------------------
    // Colour: handled brass goes warm and a little flat; dust goes grey and dim.
    // -------------------------------------------------------------------------

    static ColorMatrix colorShift(float p, int dust, Wear.Style style) {
        ColorMatrix m = new ColorMatrix();
        if (p > 0) {
            switch (style) {
                case LEATHER:
                    m.postConcat(saturation(1f - 0.30f * p));
                    m.postConcat(scale(1f - 0.04f * p, 1f - 0.12f * p, 1f - 0.24f * p, -6f * p));
                    break;
                case KEEPSAKE: {
                    float k = 0.6f * p;
                    m.postConcat(new ColorMatrix(new float[]{
                            lerp(1, .393f, k), lerp(0, .769f, k), lerp(0, .189f, k), 0, 0,
                            lerp(0, .349f, k), lerp(1, .686f, k), lerp(0, .168f, k), 0, 0,
                            lerp(0, .272f, k), lerp(0, .534f, k), lerp(1, .131f, k), 0, 0,
                            0, 0, 0, 1, 0}));
                    m.postConcat(scale(1f - 0.1f * p, 1f - 0.1f * p, 1f - 0.1f * p, 22f * p));
                    break;
                }
                case BRASS:
                default:
                    m.postConcat(saturation(1f - 0.25f * p));
                    m.postConcat(scale(1f + 0.04f * p, 1f, 1f - 0.16f * p, 6f * p));
                    break;
            }
        }
        if (dust > 0) {
            m.postConcat(saturation(1f - 0.18f * dust));
            float s = 1f - 0.07f * dust;
            m.postConcat(scale(s, s, s, 14f * dust));
        }
        return m;
    }

    private static ColorMatrix saturation(float sat) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(sat);
        return m;
    }

    private static ColorMatrix scale(float r, float g, float b, float offset) {
        return new ColorMatrix(new float[]{
                r, 0, 0, 0, offset,
                0, g, 0, 0, offset,
                0, 0, b, 0, offset,
                0, 0, 0, 1, 0});
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    // -------------------------------------------------------------------------
    // Patina: a thumb-polished sheen low-centre where a finger lands, worn lighter edges, and at
    // the higher levels a few fine scuffs.
    // -------------------------------------------------------------------------

    private static void paintPatina(Canvas c, int w, int h, float p, int level, Wear.Style style,
                                    int seed, Paint atop) {
        int sheen;
        int edge;
        float sheenA;
        float edgeA;
        switch (style) {
            case LEATHER:
                sheen = 0xFFE0B5; edge = 0x3B2416; sheenA = 0.42f; edgeA = 0.40f; break;
            case KEEPSAKE:
                sheen = 0xFFF8E8; edge = 0xFFF4DC; sheenA = 0.30f; edgeA = 0.50f; break;
            case BRASS:
            default:
                sheen = 0xFFF3D0; edge = 0xFFE7B3; sheenA = 0.55f; edgeA = 0.38f; break;
        }

        // Worn edges: a ring that only shows near the rim of a round icon.
        float r = 0.5f * Math.max(w, h) * 1.04f;
        atop.setShader(new RadialGradient(w / 2f, h / 2f, r,
                new int[]{0, 0, argb(edgeA * p, edge)},
                new float[]{0f, 0.70f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, atop);

        // The thumb spot: an oval a little below centre, where a tap lands.
        float cx = w * 0.5f;
        float cy = h * 0.60f;
        c.save();
        c.scale(1f, 0.78f, cx, cy);
        atop.setShader(new RadialGradient(cx, cy, w * 0.34f,
                new int[]{argb(sheenA * p, sheen), argb(sheenA * p * 0.45f, sheen), 0},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, w * 0.34f, atop);
        c.restore();

        // Burnish: a broad soft highlight across the upper left, as rubbed metal catches light.
        atop.setShader(new LinearGradient(0, 0, w * 0.6f, h * 0.6f,
                argb(0.18f * p, 0xFFFFFF), 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, atop);
        atop.setShader(null);

        if (level >= 2) {
            atop.setStyle(Paint.Style.STROKE);
            atop.setStrokeCap(Paint.Cap.ROUND);
            atop.setStrokeWidth(Math.max(1f, w / 160f));
            atop.setColor(argb(0.30f, sheen));
            int scuffs = level == 2 ? 3 : 6;
            for (int i = 0; i < scuffs; i++) {
                int hh = Wear.mix(seed + 0x51ed27 * (i + 1));
                float x = w * (0.2f + 0.6f * u(hh, 0));
                float y = h * (0.2f + 0.6f * u(hh, 8));
                float len = w * (0.06f + 0.10f * u(hh, 16));
                double a = Math.PI * u(hh, 24);
                c.drawLine(x, y, x + (float) Math.cos(a) * len, y + (float) Math.sin(a) * len,
                        atop);
            }
            atop.setStyle(Paint.Style.FILL);
        }
    }

    // -------------------------------------------------------------------------
    // Dust: a film heavier at the top (dust settles), and soft speckles.
    // -------------------------------------------------------------------------

    private static void paintDust(Canvas c, int w, int h, int level, int seed, Paint atop) {
        atop.setShader(new LinearGradient(0, 0, 0, h,
                argb(0.08f + 0.10f * level, DUST_COLOR), argb(0.04f * level, DUST_COLOR),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, atop);
        atop.setShader(null);

        int specks = 14 + 26 * level;
        for (int i = 0; i < specks; i++) {
            int hh = Wear.mix(seed ^ (0x9E3779B9 * (i + 1)));
            float x = w * u(hh, 0);
            // Biased toward the top, where dust would settle on a shelf.
            float y = h * (float) Math.pow(u(hh, 8), 1.6);
            float rad = w * (0.006f + 0.011f * u(hh, 16));
            boolean dark = (hh & 0x7) < 2;
            float a = 0.45f + 0.40f * u(hh, 20);
            atop.setColor(argb(a, dark ? SPECK_DARK : SPECK_LIGHT));
            c.drawCircle(x, y, rad, atop);
        }
    }

    // -------------------------------------------------------------------------
    // Cobweb: spokes fanned from a top corner, sagging threads between them, and on the oldest
    // icons a small resident.
    // -------------------------------------------------------------------------

    private static void paintCobweb(Canvas c, int w, int h, int level, int seed) {
        int s = Math.min(w, h);
        float reach = s * (level >= 2 ? 0.50f : 0.40f);
        boolean right = (seed & 0x100) != 0;

        c.save();
        if (right) {
            c.translate(w, 0);
            c.scale(-1f, 1f);
        }

        Path web = new Path();
        int spokes = 5;
        float[] ang = new float[spokes];
        float[] len = new float[spokes];
        for (int i = 0; i < spokes; i++) {
            int hh = Wear.mix(seed + 0x2545F491 * (i + 3));
            // Spread across the quarter, jittered so no two webs are alike.
            ang[i] = (float) (Math.PI / 2 * (i + 0.15f + 0.7f * u(hh, 0) * 0.4f) / (spokes - 0.4f));
            len[i] = reach * (0.85f + 0.2f * u(hh, 8));
            web.moveTo(0, 0);
            web.lineTo((float) Math.cos(ang[i]) * len[i], (float) Math.sin(ang[i]) * len[i]);
        }
        // Anchor lines to the edges, so the web is strung rather than floating.
        web.moveTo(0, 0);
        web.lineTo(reach * 1.05f, 0);
        web.moveTo(0, 0);
        web.lineTo(0, reach * 1.05f);

        float[] rings = {0.22f, 0.42f, 0.62f, 0.82f};
        for (float ring : rings) {
            for (int i = 0; i < spokes - 1; i++) {
                float r0 = len[i] * ring;
                float r1 = len[i + 1] * ring;
                float x0 = (float) Math.cos(ang[i]) * r0;
                float y0 = (float) Math.sin(ang[i]) * r0;
                float x1 = (float) Math.cos(ang[i + 1]) * r1;
                float y1 = (float) Math.sin(ang[i + 1]) * r1;
                // Sag toward the corner, as a thread under its own weight would.
                float mid = (ang[i] + ang[i + 1]) / 2f;
                float rm = (r0 + r1) / 2f * 0.80f;
                web.moveTo(x0, y0);
                web.quadTo((float) Math.cos(mid) * rm, (float) Math.sin(mid) * rm, x1, y1);
            }
        }

        Paint thread = new Paint(Paint.ANTI_ALIAS_FLAG);
        thread.setStyle(Paint.Style.STROKE);
        thread.setStrokeCap(Paint.Cap.ROUND);
        float sw = Math.max(1f, s / 130f);
        // A faint shadow first, so the web reads on white icons and pale wallpapers too.
        thread.setStrokeWidth(sw * 1.6f);
        thread.setColor(0x30000000);
        c.save();
        c.translate(sw * 0.6f, sw * 0.8f);
        c.drawPath(web, thread);
        c.restore();
        thread.setStrokeWidth(sw);
        thread.setColor(0xE6FFFFFF);
        c.drawPath(web, thread);

        if (level >= 2) {
            // The resident, dangling on a thread from the middle spoke.
            float ax = (float) Math.cos(ang[2]) * len[2] * 0.62f;
            float ay = (float) Math.sin(ang[2]) * len[2] * 0.62f;
            float body = s * 0.034f;
            float sy = ay + s * 0.16f;
            c.drawLine(ax, ay, ax, sy - body, thread);

            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(0xFF34343C);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeWidth(Math.max(1f, s / 150f));
            for (int side = -1; side <= 1; side += 2) {
                for (int leg = 0; leg < 4; leg++) {
                    float ly = sy - body * 0.5f + leg * body * 0.38f;
                    float kx = ax + side * body * 1.5f;
                    float ky = ly - body * (0.6f - leg * 0.25f);
                    c.drawLine(ax + side * body * 0.5f, ly, kx, ky, ink);
                    c.drawLine(kx, ky, kx + side * body * 0.5f, ky + body * 0.9f, ink);
                }
            }
            ink.setStyle(Paint.Style.FILL);
            c.drawCircle(ax, sy, body, ink);
            c.drawCircle(ax, sy - body * 1.1f, body * 0.62f, ink);
            ink.setColor(0xFFFFFFFF);
            c.drawCircle(ax - body * 0.25f, sy - body * 1.15f, body * 0.2f, ink);
            c.drawCircle(ax + body * 0.25f, sy - body * 1.15f, body * 0.2f, ink);
        }
        c.restore();
    }

    // -------------------------------------------------------------------------
    // The puff: a little cloud of dust lifting off as the icon comes clean. Per-frame — no
    // allocation, all randomness from the seed.
    // -------------------------------------------------------------------------

    static void drawPuff(Canvas c, Rect b, int seed, float t, Paint paint) {
        float w = b.width();
        float h = b.height();
        float ease = 1f - (1f - t) * (1f - t);
        int alpha = (int) (210 * Math.pow(1f - t, 1.3));
        for (int i = 0; i < PUFF_PARTICLES; i++) {
            int hh = Wear.mix(seed + 0x632BE5AB * (i + 1));
            // Upward spray: angles between 200° and 340° (screen y grows downward).
            double a = Math.toRadians(200 + 140 * u(hh, 0));
            float sx = b.left + w * (0.2f + 0.6f * u(hh, 8));
            float sy = b.top + h * (0.15f + 0.45f * u(hh, 16));
            float dist = w * (0.12f + 0.22f * u(hh, 24)) * ease;
            float x = sx + (float) Math.cos(a) * dist;
            float y = sy + (float) Math.sin(a) * dist - h * 0.08f * t;
            float rad = w * (0.022f + 0.022f * u(hh, 4)) * (0.6f + 0.9f * t);
            paint.setColor(PUFF_COLOR);
            paint.setAlpha(alpha);
            c.drawCircle(x, y, rad, paint);
        }
    }

    // -------------------------------------------------------------------------

    /** A uniform-ish value in [0, 1) from 8 bits of {@code h} starting at {@code shift}. */
    private static float u(int h, int shift) {
        return ((h >>> shift) & 0xFF) / 256f;
    }

    private static int argb(float alpha, int rgb) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return a << 24 | (rgb & 0xFFFFFF);
    }
}
