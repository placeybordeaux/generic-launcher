package com.genericlauncher.launcher;

import android.graphics.Color;

/**
 * Pure colour and count maths for notification badges.
 *
 * <p>Deliberately free of Android view classes — the only framework dependency is
 * {@link Color}, which Robolectric implements faithfully, so every rule here is unit-testable
 * on the JVM.
 *
 * <p>Allocation: {@link #fillColor(BadgeStyle, int, float)} allocates a single {@code float[3]}
 * scratch array. Draw-path callers that must not allocate (see {@link BadgePainter}) use the
 * package-private {@link #fillColor(BadgeStyle, int, float, float[])} overload with their own
 * pre-allocated scratch buffer. {@link #formatCount(int)} returns interned/cached strings, so it
 * never allocates either.
 */
public final class BadgePalette {

    private BadgePalette() {}

    /** The familiar iOS/Android notification red — what {@code BadgeAppearance.RED} hands back. */
    public static final int RED_FILL = 0xFFFF3B30;

    /** How long one full trip around the colour wheel takes for {@link BadgeStyle#RAINBOW}. */
    public static final long RAINBOW_PERIOD_MS = 12_000L;

    /** Rainbow badges run at fixed high saturation/value so every hue reads equally vividly. */
    static final float RAINBOW_SAT = 0.85f;
    static final float RAINBOW_VAL = 1.00f;

    /**
     * Perceived-luminance cut-off for {@link #textColor(int)}. Above it the fill is "light" and
     * takes near-black text; below it, white. 0.6 rather than 0.5 because white-on-colour stays
     * legible further up the luminance range than black-on-colour does going down.
     */
    static final float TEXT_CONTRAST_THRESHOLD = 0.60f;

    /** Text colour used on light fills. Not pure black — softer against a saturated background. */
    public static final int TEXT_DARK  = 0xFF14161A;
    /** Text colour used on dark fills. */
    public static final int TEXT_LIGHT = 0xFFFFFFFF;

    /**
     * Halo stroked behind the count text when {@link #needsTextHalo} says the fill cannot pick a
     * text colour for itself.
     *
     * <p>Pure black rather than {@link #TEXT_DARK}, and the difference is the whole guarantee.
     * White glyphs on a black halo cover the hue wheel: at every hue the rainbow can paint at
     * {@link #RAINBOW_SAT}/{@link #RAINBOW_VAL}, either the glyph or its halo clears WCAG 4.5:1
     * against the fill behind it, bottoming out at 4.60:1 near hue 278 where the two swap over —
     * and the glyph/halo edge itself is 21:1 everywhere, so the digit is always delineated. Swap
     * the halo for {@code TEXT_DARK} (relative luminance 0.008 rather than 0) and that worst case
     * drops to 4.26:1: a band of violets where neither token reaches the floor.
     */
    public static final int TEXT_HALO = 0xFF000000;

    /** Cached "0".."99" so {@link #formatCount} allocates nothing on the draw path. */
    private static final String[] COUNT_STRINGS = new String[100];
    private static final String OVERFLOW = "99+";
    private static final String EMPTY    = "";

    static {
        for (int i = 0; i < COUNT_STRINGS.length; i++) COUNT_STRINGS[i] = Integer.toString(i);
    }

    // -------------------------------------------------------------------------
    // Colour
    // -------------------------------------------------------------------------

    /**
     * Badge fill colour for the given style.
     *
     * @param style    badge style; {@code null} is treated as {@link BadgeStyle#FLAT}
     * @param seamFill the flat fill the active {@code BadgeAppearance} chose for this app —
     *                 used verbatim by {@link BadgeStyle#FLAT} and ignored by
     *                 {@link BadgeStyle#RAINBOW}, which derives its own hue from {@code phase}
     * @param phase    shared animation phase in {@code [0,1)}
     */
    public static int fillColor(BadgeStyle style, int seamFill, float phase) {
        return fillColor(style, seamFill, phase, new float[3]);
    }

    /**
     * Allocation-free variant of {@link #fillColor(BadgeStyle, int, float)}.
     *
     * @param hsv caller-owned scratch array of length &gt;= 3; contents are clobbered
     */
    static int fillColor(BadgeStyle style, int seamFill, float phase, float[] hsv) {
        if (style == BadgeStyle.RAINBOW) {
            hsv[0] = wrapPhase(phase) * 360f;
            hsv[1] = RAINBOW_SAT;
            hsv[2] = RAINBOW_VAL;
            return Color.HSVToColor(hsv);
        }
        return seamFill;
    }

    /**
     * White or near-black, whichever contrasts better against {@code fill}. Uses the standard
     * ITU-R BT.601 luma weights, which track perceived brightness far better than a plain
     * RGB average (green dominates, blue barely registers).
     */
    public static int textColor(int fill) {
        float r = Color.red(fill)   / 255f;
        float g = Color.green(fill) / 255f;
        float b = Color.blue(fill)  / 255f;
        float luma = 0.299f * r + 0.587f * g + 0.114f * b;
        return luma > TEXT_CONTRAST_THRESHOLD ? TEXT_DARK : TEXT_LIGHT;
    }

    /**
     * Count text colour for a badge of the given {@code style} whose flat fill is {@code fill}.
     *
     * <p>{@link BadgeStyle#RAINBOW} is deliberately kept out of the luminance pick above. Two
     * independent reasons, either sufficient:
     *
     * <ul>
     *   <li><b>The fill is not one colour.</b> {@link BadgePainter} paints a full spectrum across
     *       every rainbow badge, so the glyphs sit on several hues at once and no single one of
     *       them can decide their contrast. {@code fill} is only the hue at the badge's phase.
     *   <li><b>The colour it would be picked against moves.</b> A hue sweeping the whole wheel
     *       every {@value #RAINBOW_PERIOD_MS}ms crosses {@link #TEXT_CONTRAST_THRESHOLD} twice a
     *       lap — into the light band near hue 24 and out of it near hue 198 — so a thresholded
     *       pick snaps white → dark → white every twelve seconds, mid-animation and with nothing
     *       else on the badge changing abruptly. That flicker is the bug this overload removes.
     * </ul>
     *
     * <p>Rainbow text is therefore fixed at {@link #TEXT_LIGHT} for the whole cycle, and its
     * legibility comes from the {@link #TEXT_HALO} stroked behind it rather than from guessing at
     * the fill. Interpolating between the two tokens instead would be worse, not better: the
     * midpoint of white and near-black is a mid grey, which contrasts with nothing — 1.0:1 against
     * its worst hue, where even a badly chosen flat token manages 2.4:1.
     */
    public static int textColor(BadgeStyle style, int fill) {
        return style == BadgeStyle.RAINBOW ? TEXT_LIGHT : textColor(fill);
    }

    /**
     * Whether the count text of this style must be stroked with {@link #TEXT_HALO} before being
     * filled.
     *
     * <p>True exactly when the fill cannot be reduced to the one colour {@link #textColor(int)}
     * needs — today, {@link BadgeStyle#RAINBOW}. The solid styles pick a text colour that already
     * contrasts with their own fill and want no outline around the digits.
     */
    public static boolean needsTextHalo(BadgeStyle style) {
        return style == BadgeStyle.RAINBOW;
    }

    // -------------------------------------------------------------------------
    // Counts
    // -------------------------------------------------------------------------

    /**
     * Badge label for a count: {@code ""} for zero or negative (draw nothing), the plain number
     * up to 99, and {@code "99+"} above that. Never allocates.
     */
    public static String formatCount(int count) {
        if (count <= 0)  return EMPTY;
        if (count >  99) return OVERFLOW;
        return COUNT_STRINGS[count];
    }

    // -------------------------------------------------------------------------
    // Animation phase
    // -------------------------------------------------------------------------

    /**
     * Global animation phase in {@code [0,1)} derived from an uptime-millis value. Every badge
     * on screen derives its colour from the same clock, so rainbow badges cycle in unison.
     * Negative inputs wrap forwards rather than producing a negative phase.
     */
    public static float rainbowPhase(long uptimeMillis) {
        long m = uptimeMillis % RAINBOW_PERIOD_MS;
        if (m < 0) m += RAINBOW_PERIOD_MS;
        return m / (float) RAINBOW_PERIOD_MS;
    }

    /** Folds an arbitrary float into {@code [0,1)}. */
    static float wrapPhase(float phase) {
        if (Float.isNaN(phase) || Float.isInfinite(phase)) return 0f;
        float p = phase % 1f;
        if (p < 0f) p += 1f;
        // p can round up to exactly 1f for phase values just under 1; clamp back into range.
        return p >= 1f ? 0f : p;
    }

    // -------------------------------------------------------------------------
    // Per-badge rainbow band
    // -------------------------------------------------------------------------

    /** Tilt of the rainbow band, in degrees clockwise from horizontal. */
    public static final float RAINBOW_BAND_ANGLE_DEG = 25f;

    /**
     * Hue in {@code [0,360)} of a rainbow band at point {@code (x,y)}.
     *
     * <p>The band is one continuous spectrum striped perpendicular to {@code angleDeg} and
     * repeating every {@code wavelengthPx}, drifting along its axis as {@code phase} advances.
     *
     * <p>This is the reference definition. {@link BadgePainter} reproduces it with a shader for
     * drawing; the two must agree. Callers wanting per-badge coordinates should go through
     * {@link #rainbowBadgeHue} rather than calling this directly.
     */
    public static float rainbowFieldHue(float x, float y, float phase,
                                        float wavelengthPx, float angleDeg) {
        if (wavelengthPx <= 0f) return 0f;
        double a = Math.toRadians(angleDeg);
        // Distance along the band direction; points perpendicular to it share a hue.
        float along = (float) (x * Math.cos(a) + y * Math.sin(a));
        return wrapPhase(along / wavelengthPx + phase) * 360f;
    }

    /**
     * Extent of a {@code w × h} badge measured along the band direction — the distance from the
     * corner the band enters to the corner it leaves.
     *
     * <p>Used as the wavelength so exactly one full spectrum spans each badge, corner to corner,
     * whatever its size or shape. A one-digit circle and a three-digit pill therefore show the
     * same complete rainbow rather than the pill showing a wider slice of it.
     */
    public static float rainbowSpanPx(float w, float h, float angleDeg) {
        double a = Math.toRadians(angleDeg);
        return w * (float) Math.abs(Math.cos(a)) + h * (float) Math.abs(Math.sin(a));
    }

    /**
     * Hue in {@code [0,360)} the badge shows at a point {@code (dx,dy)} from <em>its own</em>
     * top-left corner.
     *
     * <p>Deliberately badge-local: the arguments carry no screen position, so two badges of the
     * same size are painted identically no matter where they sit. The rainbow runs across each
     * individual badge and every badge advances in step, rather than each one sampling a
     * different slice of a screen-wide field.
     */
    public static float rainbowBadgeHue(float dx, float dy, float phase,
                                        float w, float h, float angleDeg) {
        return rainbowFieldHue(dx, dy, phase, rainbowSpanPx(w, h, angleDeg), angleDeg);
    }
}
