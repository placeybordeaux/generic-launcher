package com.genericlauncher.launcher.personality;

/**
 * Seam: the colour of a notification badge.
 *
 * <p>Plain Java, but it pairs with the badge view code, so it is declared here in the app module
 * with the other view-side seams rather than in the pure-JVM {@code src/} tree; see
 * {@code docs/SEAM.md}.
 *
 * <p>Only the <em>colour</em> is a personality choice. The count geometry, text, and contrast maths
 * stay in {@code BadgePalette} / {@code BadgePainter} — they are launcher mechanics, not taste. The
 * taste-free default is a fixed notification-red ({@link #RED}); a colour build swaps in a
 * per-app complementary colour here and changes nothing else.
 */
public interface BadgeAppearance {

    /**
     * ARGB fill for the badge on {@code app} showing {@code count} notifications.
     *
     * @param appKey the app's {@link DrawerApp#key()} (bare package or {@code "pkg#serial"})
     * @param count  current notification count (always ≥ 1 when a badge is drawn)
     */
    int fillColor(String appKey, int count);

    /** The familiar notification red, for every app — the taste-free default. */
    BadgeAppearance RED =
            (appKey, count) -> com.genericlauncher.launcher.BadgePalette.RED_FILL;
}
