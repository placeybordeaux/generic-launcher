package com.genericlauncher.launcher;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Immutable snapshot of every badge appearance setting.
 *
 * <p>Exists because {@link BadgeDrawable}'s constructor was accumulating one parameter per
 * setting across four call sites. Reading the prefs once into a value object keeps the
 * constructors short and guarantees all four settings come from the same read.
 *
 * <p>Deliberately does <em>not</em> carry the master {@code notif_dots} toggle: that governs
 * whether a badge exists at all, which callers decide before they build one.
 */
public final class BadgeConfig {

    public final BadgeStyle style;
    public final BadgeSize  size;
    public final boolean    shimmer;
    public final boolean    glow;

    public BadgeConfig(@NonNull BadgeStyle style, @NonNull BadgeSize size,
                       boolean shimmer, boolean glow) {
        this.style   = style;
        this.size    = size;
        this.shimmer = shimmer;
        this.glow    = glow;
    }

    /** Reads the current badge appearance settings. */
    public static BadgeConfig from(Context c) {
        return new BadgeConfig(
                Settings.getBadgeStyle(c),
                Settings.getBadgeSize(c),
                Settings.getBadgeShimmer(c),
                Settings.getBadgeGlow(c));
    }

    /**
     * Whether this configuration needs a per-frame redraw: rainbow cycles its hue, and
     * shimmer/glow are animations in their own right.
     */
    public boolean isAnimated() {
        return style == BadgeStyle.RAINBOW || shimmer || glow;
    }
}
