package com.genericlauncher.launcher;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import androidx.annotation.NonNull;

/**
 * Draws a notification count badge onto a canvas at a caller-supplied anchor.
 *
 * <p><b>Zero per-frame allocation.</b> Every {@link Paint}, {@link Path}, {@link RectF},
 * {@link Matrix} and {@link Shader} is created once in the constructor; {@link #draw} allocates
 * nothing. This is the same contract the old {@code DotDrawable} carried, and it matters because
 * a home screen can hold dozens of badges redrawn every frame while animated.
 *
 * <p>Not thread-safe: a painter owns mutable scratch state and is expected to be used from the
 * main (UI) thread only. Views that draw badges directly hold one painter instance each.
 */
class BadgePainter {

    // --- Geometry, all in px, derived from the icon size via setIconSize -------

    /** Horizontal padding either side of the count text, as a fraction of badge height. */
    private static final float PAD_H_RATIO   = 4.5f / 16f;
    /** Dark outline ring width, as a fraction of badge height. */
    private static final float OUTLINE_RATIO = 1.5f / 16f;
    /** Count text size, as a fraction of badge height. */
    private static final float TEXT_RATIO    = 10f  / 16f;
    /**
     * Width of the halo stroked behind the count text, as a fraction of the <em>text</em> size.
     *
     * <p>A stroke is centred on the glyph outline, and the filled glyph is drawn over the halo
     * afterwards, so only half of this shows: the visible halo is {@code TEXT_HALO_RATIO / 2} of
     * the text size outside each glyph, and the digits keep their full weight.
     */
    private static final float TEXT_HALO_RATIO = 0.24f;
    /** How far the breathing glow reaches beyond the badge edge, as a fraction of height. */
    private static final float GLOW_RATIO    = 3.5f / 16f;
    /** How far the badge sits outside the icon's corner, as a fraction of badge height. */
    private static final float OVERHANG_RATIO = 0.30f;

    private static final int OUTLINE_COLOR = 0xDD000000;

    /** Shimmer highlight travels for this fraction of each cycle, then rests. */
    private static final float SHIMMER_DUTY = 0.25f;   // ~1s of sweep per 4s cycle
    /** Effect cycles per rainbow period: 12s / 4s = 3. */
    private static final float CYCLES_PER_PERIOD = BadgePalette.RAINBOW_PERIOD_MS / 4000f;
    /** Number of concentric rings used to fake a soft glow (no BlurMaskFilter — HW-accel safe). */
    private static final int GLOW_RINGS = 3;

    private final float density;
    private final BadgeSize size;

    private float heightPx;
    private float padHPx;
    private float outlinePx;
    private float glowPx;
    /** Last icon size handed to {@link #setIconSize}; guards the recompute. */
    private float lastIconPx = -1f;

    // --- Pre-allocated draw state --------------------------------------------

    private final Paint fillPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF rect  = new RectF();
    private final RectF ring  = new RectF();
    private final Path  clip  = new Path();
    private final Matrix shimmerMatrix = new Matrix();
    private final LinearGradient shimmerGradient;
    private final Paint.FontMetrics fm = new Paint.FontMetrics();
    /** Scratch buffer for {@link BadgePalette#fillColor(BadgeStyle, int, float, float[])}. */
    private final float[] hsv = new float[3];

    /** Stops in the repeating spectrum. First == last so REPEAT tiling has no seam. */
    private static final int SPECTRUM_STOPS = 13;

    private final LinearGradient rainbowGradient;
    private final Matrix rainbowMatrix = new Matrix();

    /**
     * @param density display density scalar (dp → px)
     * @param size    badge size preset; geometry is derived from the icon size handed to
     *                {@link #setIconSize(float)}
     */
    BadgePainter(float density, BadgeSize size) {
        this.density = density;
        this.size = size != null ? size : BadgeSize.MEDIUM;

        fillPaint.setStyle(Paint.Style.FILL);

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setColor(OUTLINE_COLOR);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        // Same glyphs as textPaint, stroked instead of filled: the halo that keeps rainbow counts
        // legible over a fill whose hue is different under every part of the digit. Round join and
        // cap so the outline follows the glyph rather than growing spikes at its corners.
        haloPaint.setTextAlign(Paint.Align.CENTER);
        haloPaint.setFakeBoldText(true);
        haloPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeJoin(Paint.Join.ROUND);
        haloPaint.setStrokeCap(Paint.Cap.ROUND);

        glowPaint.setStyle(Paint.Style.STROKE);

        // Gradient authored in a unit-width space (0..1 along x); the per-frame local matrix
        // scales and slides it across the badge, so the gradient object itself is never rebuilt.
        shimmerGradient = new LinearGradient(
                0f, 0f, 1f, 0f,
                new int[]{0x00FFFFFF, 0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 0.5f, 0.65f, 1f},
                Shader.TileMode.CLAMP);
        shimmerPaint.setStyle(Paint.Style.FILL);
        shimmerPaint.setShader(shimmerGradient);

        // Full spectrum in unit-width space, tiled. REPEAT is what lets the phase drift without
        // bound: the local matrix can translate the gradient arbitrarily far without ever
        // running off the end, so there is no wrap seam to hide.
        int[] spectrum = new int[SPECTRUM_STOPS];
        float[] ramp = {0f, BadgePalette.RAINBOW_SAT, BadgePalette.RAINBOW_VAL};
        for (int i = 0; i < SPECTRUM_STOPS; i++) {
            float h = (360f * i) / (SPECTRUM_STOPS - 1);
            ramp[0] = h >= 360f ? 0f : h;
            spectrum[i] = Color.HSVToColor(ramp);
        }
        rainbowGradient = new LinearGradient(
                0f, 0f, 1f, 0f, spectrum, null, Shader.TileMode.REPEAT);

        // Start at the previous fixed-16dp equivalent so a painter is usable before any
        // setIconSize call; every real caller overrides this on its first draw.
        recompute(this.size.heightPx(52f * density, density));
    }

    /**
     * Sets the size of the icon this badge sits on, rescaling every dimension.
     *
     * <p>Guarded on the incoming value, so the steady state is one float comparison per draw
     * and the zero-per-frame-allocation contract is preserved: the {@link Paint} mutations
     * below happen only when the icon size actually changes, which in practice is once per
     * layout.
     */
    void setIconSize(float iconPx) {
        if (iconPx == lastIconPx || iconPx <= 0f) return;
        lastIconPx = iconPx;
        recompute(size.heightPx(iconPx, density));
    }

    private void recompute(float h) {
        heightPx  = h;
        padHPx    = h * PAD_H_RATIO;
        outlinePx = h * OUTLINE_RATIO;
        glowPx    = h * GLOW_RATIO;
        outlinePaint.setStrokeWidth(outlinePx);
        float textPx = h * TEXT_RATIO;
        textPaint.setTextSize(textPx);
        textPaint.getFontMetrics(fm);
        haloPaint.setTextSize(textPx);
        haloPaint.setStrokeWidth(textPx * TEXT_HALO_RATIO);
    }

    /** How far the badge extends past the icon's corner, in px. */
    float overhangPad() { return heightPx * OVERHANG_RATIO; }

    // -------------------------------------------------------------------------
    // Metrics
    // -------------------------------------------------------------------------

    /** Badge height in px (also the diameter of a single-digit circular badge). */
    float height() { return heightPx; }

    /**
     * Badge width in px for the given count: a circle for one digit, widening into a pill for
     * two or three characters. Returns 0 for counts that render nothing.
     */
    float widthFor(int count) {
        String s = BadgePalette.formatCount(count);
        if (s.isEmpty()) return 0f;
        if (s.length() <= 1) return heightPx;
        return Math.max(heightPx, textPaint.measureText(s) + 2f * padHPx);
    }

    /**
     * Width in px of the widest badge this painter can ever draw — the {@code "99+"} overflow
     * pill. Callers that must pick one box size up front and keep it (a host view whose measured
     * size only changes on a layout pass) size against this rather than against the current count.
     */
    float maxWidth() { return widthFor(Integer.MAX_VALUE); }

    /** Extra space the glow needs outside the badge rect, in px. */
    float glowPad() { return glowPx; }

    /**
     * Points {@link #rainbowGradient} so that canvas point {@code q} inside {@link #rect} samples
     * {@code BadgePalette.rainbowBadgeHue(q - rect.topLeft, phase, w, h, θ)}.
     *
     * <p>Anchored to the badge's own rect, never to the screen: one full spectrum spans each
     * badge corner to corner, so every badge of a given size paints identically wherever it sits
     * and the whole set advances in step as {@code phase} drifts.
     *
     * <p>Derivation. Android samples a shader at {@code M⁻¹q}, where {@code M} is the local
     * matrix. We want shader coordinate
     * {@code u(q) = ((q - o)·d)/λ + phase}, with {@code d = (cos θ, sin θ)}, {@code o} the badge's
     * top-left and {@code λ} its extent along {@code d}. So {@code M⁻¹ = A q + b} where
     * {@code A = R(-θ)/λ} and {@code b₀ = phase - (o·d)/λ}; inverting, {@code M = λR(θ)}
     * translated by {@code -λ·b₀·d}.
     *
     * <p>{@code o} is the top-left because θ is in the first quadrant, so both components of
     * {@code d} are positive and {@code q·d} is minimised at that corner.
     */
    private void applyRainbowField(float phase) {
        double a = Math.toRadians(BadgePalette.RAINBOW_BAND_ANGLE_DEG);
        float c = (float) Math.cos(a);
        float s = (float) Math.sin(a);

        float lambda = BadgePalette.rainbowSpanPx(
                rect.width(), rect.height(), BadgePalette.RAINBOW_BAND_ANGLE_DEG);
        if (lambda <= 0f) return;
        float b0 = phase - (rect.left * c + rect.top * s) / lambda;

        rainbowMatrix.setScale(lambda, 1f);
        rainbowMatrix.postRotate(BadgePalette.RAINBOW_BAND_ANGLE_DEG);
        rainbowMatrix.postTranslate(-lambda * b0 * c, -lambda * b0 * s);
        rainbowGradient.setLocalMatrix(rainbowMatrix);
    }

    // -------------------------------------------------------------------------
    // Draw
    // -------------------------------------------------------------------------

    /**
     * Draws the badge with its <em>top-right</em> corner at {@code (anchorRightX, anchorTopY)}.
     * A count of zero or less draws nothing.
     *
     * @param style    badge painting style
     * @param seamFill  flat fill chosen for this app by the active {@code BadgeAppearance};
     *                  ignored by {@link BadgeStyle#RAINBOW}
     * @param shimmer   sweep a specular highlight across the badge
     * @param glow      breathe a soft outer ring around the badge
     */
    void draw(@NonNull Canvas c, float anchorRightX, float anchorTopY, int count,
              BadgeStyle style, int seamFill, boolean shimmer, boolean glow) {

        String label = BadgePalette.formatCount(count);
        if (label.isEmpty()) return;

        float w = widthFor(count);
        float h = heightPx;
        rect.set(anchorRightX - w, anchorTopY, anchorRightX, anchorTopY + h);
        float r = h / 2f;

        float phase = BadgeAnimator.phase();
        int fill = BadgePalette.fillColor(style, seamFill, phase, hsv);

        if (glow) drawGlow(c, fill, r, phase);

        // Outline sits half-outside the fill so the fill stays its nominal size.
        c.drawRoundRect(rect, r, r, outlinePaint);

        if (style == BadgeStyle.RAINBOW) {
            applyRainbowField(phase);
            fillPaint.setShader(rainbowGradient);
            fillPaint.setColor(0xFF000000);   // ignored while a shader is set; keeps alpha at full
        } else {
            fillPaint.setShader(null);
            fillPaint.setColor(fill);
        }
        c.drawRoundRect(rect, r, r, fillPaint);
        fillPaint.setShader(null);

        if (shimmer) drawShimmer(c, r, phase);

        int text = BadgePalette.textColor(style, fill);
        textPaint.setColor(text);
        // Vertically centre by the font's own metrics rather than its bounding box.
        float baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f;
        // Halo first, filled glyphs over it: the visible result is an outlined digit whose
        // contrast comes from the halo, not from a colour picked against a fill that is moving
        // (and, for RAINBOW, is several hues at once). See BadgePalette#textColor(BadgeStyle,int).
        if (BadgePalette.needsTextHalo(style)) {
            haloPaint.setColor(BadgePalette.TEXT_HALO);
            c.drawText(label, rect.centerX(), baseline, haloPaint);
        }
        c.drawText(label, rect.centerX(), baseline, textPaint);
    }

    /**
     * Soft outer glow: {@value #GLOW_RINGS} concentric stroked rounded-rects of decreasing alpha.
     * Deliberately not a {@code BlurMaskFilter} — blur mask filters are unsupported on a
     * hardware-accelerated canvas on several API levels and would silently vanish.
     */
    private void drawGlow(Canvas c, int fill, float r, float phase) {
        // Breathe between 45% and 100% extent on the shared 4s effect cycle.
        double angle = 2 * Math.PI * (phase * CYCLES_PER_PERIOD);
        float breath = 0.45f + 0.55f * (float) ((Math.sin(angle) + 1) / 2);

        int base = Color.rgb(Color.red(fill), Color.green(fill), Color.blue(fill));
        for (int i = 0; i < GLOW_RINGS; i++) {
            float t = (i + 1) / (float) GLOW_RINGS;          // 1/3 .. 1
            float spread = glowPx * breath * t;
            int alpha = (int) (110 * breath * (1f - t * 0.7f));
            if (alpha <= 0) continue;
            glowPaint.setColor(base);
            glowPaint.setAlpha(alpha);
            glowPaint.setStrokeWidth(Math.max(1f, glowPx * breath / GLOW_RINGS));
            ring.set(rect.left - spread, rect.top - spread,
                     rect.right + spread, rect.bottom + spread);
            c.drawRoundRect(ring, r + spread, r + spread, glowPaint);
        }
    }

    /**
     * Specular highlight sweeping left-to-right across the badge, clipped to the badge shape.
     * Active for {@link #SHIMMER_DUTY} of each 4-second cycle, then idle.
     */
    private void drawShimmer(Canvas c, float r, float phase) {
        float cycle = BadgePalette.wrapPhase(phase * CYCLES_PER_PERIOD);
        if (cycle > SHIMMER_DUTY) return;                    // resting part of the cycle
        float t = cycle / SHIMMER_DUTY;                      // 0 → 1 across the sweep

        float band = rect.width() * 1.6f;
        // Slide the unit-width gradient from fully left of the badge to fully right of it.
        float startX = rect.left - band + t * (rect.width() + 2f * band);
        shimmerMatrix.setScale(band, 1f);
        shimmerMatrix.postTranslate(startX, rect.top);
        shimmerGradient.setLocalMatrix(shimmerMatrix);

        int save = c.save();
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        c.clipPath(clip);
        c.drawRect(rect, shimmerPaint);
        c.restoreToCount(save);
    }
}
