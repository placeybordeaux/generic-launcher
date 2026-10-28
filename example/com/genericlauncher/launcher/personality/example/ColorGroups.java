package com.genericlauncher.launcher.personality.example;

/**
 * The colour classifier from the launcher this was extracted from, ported to pure Java (no
 * {@code android.graphics}).
 *
 * <p>The pixel→HSB extraction stays in the Android app module (it needs the icon bitmap), but the
 * classification — HSB → named colour group — is pure maths and lives here so it can be unit-tested
 * on a plain JVM. This is deliberately a faithful port, boundaries unchanged, so the example build
 * behaves exactly like the launcher it was extracted from.
 *
 * <p><b>Known-wrong on purpose (see the source repo's docs/TODO.md):</b> group {@link #BROWN} is
 * unreachable — no hue branch returns it — so browns scatter into Red/Orange. {@code SeamCheck}
 * pins this as a characterization test: the demo reproduces the real behaviour, warts and all,
 * rather than quietly "fixing" it and diverging from the shipped app.
 */
public final class ColorGroups {

    public static final int BLACK = 0, RED = 1, ORANGE = 2, YELLOW = 3, GREEN = 4, TEAL = 5,
            BLUE = 6, PURPLE = 7, PINK = 8, BROWN = 9, GRAY = 10, WHITE = 11, RAINBOW = 12;

    private ColorGroups() {}

    /**
     * Maps an HSB colour to one of the named groups. Verbatim port of
     * {@code ColorUtils.colorGroupIndex(float,float,float)}.
     *
     * @param hue        0–360
     * @param saturation 0–1
     * @param brightness 0–1
     */
    public static int of(float hue, float saturation, float brightness) {
        // Achromatic: very low saturation or very dark/bright.
        if (saturation < 0.15f) {
            if (brightness < 0.3f) return BLACK;
            if (brightness > 0.85f) return WHITE;
            return GRAY;
        }
        // Chromatic groups by hue (rainbow order).
        if (hue < 15)  return RED;
        if (hue < 40)  return ORANGE;
        if (hue < 70)  return YELLOW;
        if (hue < 150) return GREEN;
        if (hue < 190) return TEAL;
        if (hue < 250) return BLUE;
        if (hue < 290) return PURPLE;
        if (hue < 330) return PINK;
        return RED; // wraps around
    }

    /** Group display name, verbatim port of {@code ColorUtils.colorGroupName}. */
    public static String name(int index) {
        switch (index) {
            case BLACK:   return "Black";
            case RED:     return "Red";
            case ORANGE:  return "Orange";
            case YELLOW:  return "Yellow";
            case GREEN:   return "Green";
            case TEAL:    return "Teal";
            case BLUE:    return "Blue";
            case PURPLE:  return "Purple";
            case PINK:    return "Pink";
            case BROWN:   return "Brown";
            case GRAY:    return "Gray";
            case WHITE:   return "White";
            case RAINBOW: return "Rainbow";
            default:      return "Other";
        }
    }
}
