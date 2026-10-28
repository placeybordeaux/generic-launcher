package com.genericlauncher.launcher.personality;

import com.genericlauncher.launcher.personality.strategy.AlphabeticalStrategy;

/**
 * The one place a fork chooses its personality — the taste-free default wiring.
 *
 * <p><b>Editing this file is the product.</b> A fork personalizes by pointing these four fields
 * at different implementations, not by adding settings toggles. Everything else is the launcher
 * spine; leave it alone. See {@code docs/SEAM.md} for the seam manifest and the intent→file
 * recipe table.
 *
 * <p><b>Why this file lives in {@code src/plain/} rather than {@code src/main/}:</b> it is the
 * one file a personality must replace, so it lives in a product-flavour source set. The
 * {@code color} flavour ships its own copy of this class and <em>nothing else</em> is
 * duplicated or overridden — which is what makes "re-add the whole colour identity with zero
 * edits to the launcher core" a claim the build system enforces rather than one a README
 * asserts. Two flavours cannot both contribute the same class, so if the seam ever leaked and
 * a personality needed to override a spine class too, the build would fail rather than quietly
 * accept it.
 *
 * <p>Only {@link #DRAWER} is framework-free; it is declared in the pure-Java {@code src/} tree
 * beside its strategies so {@code SeamCheck} can exercise it on a bare JVM with no Android SDK.
 * The other three touch {@code android.*} types and are declared in the app module.
 */
public final class Personality {

    /**
     * Active drawer ordering/grouping. Swap this line to re-skin the whole drawer.
     *
     * <p>Default: A–Z under single-letter headers — the most boring correct thing a drawer can
     * do, which is the point. {@code new AlphabeticalStrategy(false)} drops the headers;
     * {@code new UsageStrategy()} sorts most-used first.
     */
    public static final DrawerStrategy DRAWER = new AlphabeticalStrategy(true);

    /**
     * What renders behind the home screen and drawer. Default: nothing of our own — the theme's
     * window background and the system wallpaper show through, which is what a launcher with no
     * opinion looks like.
     */
    public static final Background BACKGROUND = Background.NONE;

    /** How one drawer cell is drawn after icon and label are bound. Default: untouched. */
    public static final DrawerItemDecorator DECORATOR = DrawerItemDecorator.NONE;

    /** Notification-badge fill colour. Default: the familiar notification red, for every app. */
    public static final BadgeAppearance BADGE = BadgeAppearance.RED;

    /**
     * Per-app data this personality derives for itself when apps load. Default: none — the
     * taste-free drawer needs nothing the launcher does not already hand it.
     */
    public static final AppDataSink DATA = AppDataSink.NONE;

    private Personality() {}
}
