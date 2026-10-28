package com.genericlauncher.launcher;

/**
 * How large a notification badge is, expressed as a fraction of the icon it sits on.
 *
 * <p>A ratio rather than an absolute dp because that is what both AOSP
 * ({@code DotRenderer.SIZE_PERCENTAGE = 0.228f}) and iOS (~0.33) do: a fixed dp that looks
 * right on a 52dp home icon is oversized on the 30dp icons {@link RainbowFieldView} draws.
 *
 * <p>{@link #MEDIUM} (0.33) is the iOS ratio and the default; it reproduces the previous
 * fixed 16dp badge to within a pixel on a 52dp icon.
 *
 * <p>Ordinals are persisted in SharedPreferences ({@code badge_size}) — never reorder.
 */
public enum BadgeSize {
    SMALL(0.26f),
    MEDIUM(0.33f),
    LARGE(0.42f),
    HUGE(0.52f);

    /**
     * Minimum badge height, in dp, regardless of how small the icon is.
     *
     * <p>{@link RainbowFieldView} sizes icons down to 30dp, where Small would be 7.8dp and
     * Medium 9.9dp — neither can hold two legible digits plus the outline ring.
     */
    static final float MIN_HEIGHT_DP = 11f;

    /** Badge height as a fraction of icon size. */
    public final float ratio;

    BadgeSize(float ratio) {
        this.ratio = ratio;
    }

    /**
     * Badge height in px for an icon {@code iconPx} px across, never below
     * {@link #MIN_HEIGHT_DP}.
     *
     * @param iconPx  icon width in px
     * @param density display density scalar (dp → px)
     */
    public float heightPx(float iconPx, float density) {
        return Math.max(MIN_HEIGHT_DP * density, iconPx * ratio);
    }

    /** Labels for the settings picker, index-aligned with {@link #values()}. */
    public static String[] labels() {
        return new String[]{
                "Small  ·  subtle, close to a stock dot",
                "Medium  ·  easy to read  (default)",
                "Large  ·  hard to miss",
                "Huge  ·  as big as it gets"
        };
    }
}
