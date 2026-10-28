package com.genericlauncher.launcher.personality;

import com.genericlauncher.launcher.color.ColorPersonality;
import com.genericlauncher.launcher.personality.example.ColorGroupStrategy;

/**
 * The colour personality: a drawer sorted into rainbow sections.
 *
 * <p><b>This file is the entire difference</b> between the taste-free launcher and the colour one.
 * Put it beside {@code src/plain/}'s copy and the diff is five field initialisers. Everything it
 * points at — the strategy, the classifier, the spectrum backdrop, the complementary badges — is
 * new code in {@code src/color/}; not one line of {@code src/main/} is edited, overridden or
 * subclassed to make it work. That is the claim the whole base rests on, and the build enforces it:
 * a flavour source set cannot contribute a class that {@code main} also defines, so if this
 * personality had needed to reach into the launcher's own code, it would not compile.
 *
 * <p>Build it with {@code ./gradlew assembleColorDebug}; it installs alongside the plain build
 * (different applicationId suffix), which is what makes a side-by-side screenshot possible from
 * one checkout.
 */
public final class Personality {

    /** Apps grouped into colour sections, black through white. The identity, in one line. */
    public static final DrawerStrategy DRAWER =
            new ColorGroupStrategy(ColorPersonality::groupOf);

    /** The drawer's own spectrum, painted behind it. */
    public static final Background BACKGROUND = ColorPersonality.BACKGROUND;

    /** Each cell's icon tinted toward its own colour group. */
    public static final DrawerItemDecorator DECORATOR = ColorPersonality.DECORATOR;

    /**
     * Badges in the complement of the app's own icon colour, so a notification count reads against
     * the icon it sits on rather than against a red that may be the icon's own colour.
     */
    public static final BadgeAppearance BADGE = ColorPersonality.BADGE;

    /**
     * Where the colour actually comes from: one pass over the icons on the loader's worker thread,
     * filling a side table keyed by app. Without this hook a colour personality is unwritable
     * behind the seam — {@link DrawerApp} has no icon, by design.
     */
    public static final AppDataSink DATA = ColorPersonality.DATA;

    private Personality() {}
}
