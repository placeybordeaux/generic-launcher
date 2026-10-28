package com.genericlauncher.launcher;

/**
 * How a notification badge is painted. Independent of the {@code shimmer}/{@code glow} effect
 * toggles, which compose with either.
 *
 * <p>These are painting <em>mechanics</em>, not taste: <b>which</b> colour a flat badge takes is
 * the {@link com.genericlauncher.launcher.personality.BadgeAppearance} seam's decision, and a
 * personality changes it there rather than by adding a value here. What is left is the one thing
 * a flat fill cannot express — a badge whose fill is a moving spectrum rather than a colour.
 *
 * <p>Ordinals are persisted in SharedPreferences ({@code badge_style}) — never reorder.
 */
public enum BadgeStyle {
    /** A single flat fill, supplied per app by the active {@code BadgeAppearance}. */
    FLAT,
    /** Hue driven by a shared global phase so every badge cycles in unison. */
    RAINBOW;

    /** Labels for the settings picker, index-aligned with {@link #values()}. */
    public static String[] labels() {
        return new String[]{
                "Flat  ·  one color per badge",
                "Rainbow  ·  slowly cycles through the spectrum"
        };
    }
}
