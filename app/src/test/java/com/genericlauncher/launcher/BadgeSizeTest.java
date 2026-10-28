package com.genericlauncher.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/** Unit tests for {@link BadgeSize} — pure ratio maths, no Android framework needed. */
public class BadgeSizeTest {

    private static final float EPS = 0.01f;

    @Test
    public void ratiosAreTheDesignedPresets() {
        assertEquals(0.26f, BadgeSize.SMALL.ratio,  EPS);
        assertEquals(0.33f, BadgeSize.MEDIUM.ratio, EPS);
        assertEquals(0.42f, BadgeSize.LARGE.ratio,  EPS);
        assertEquals(0.52f, BadgeSize.HUGE.ratio,   EPS);
    }

    /** On the 52dp icons both surfaces use, every preset clears the floor. */
    @Test
    public void scalesWithIconOn52dpIcons() {
        float density = 2f;
        float icon = 52f * density;
        assertEquals(27.04f, BadgeSize.SMALL.heightPx(icon, density),  EPS);
        assertEquals(34.32f, BadgeSize.MEDIUM.heightPx(icon, density), EPS);
        assertEquals(43.68f, BadgeSize.LARGE.heightPx(icon, density),  EPS);
        assertEquals(54.08f, BadgeSize.HUGE.heightPx(icon, density),   EPS);
    }

    /**
     * RainbowFieldView sizes icons down to 30dp. Small (7.8dp) and Medium (9.9dp) fall
     * under the 11dp floor there; Large (12.6dp) and Huge (15.6dp) do not.
     */
    @Test
    public void floorEngagesForSmallAndMediumOnTinyIcons() {
        float density = 2f;
        float icon = 30f * density;
        float floor = 11f * density;
        assertEquals(floor,  BadgeSize.SMALL.heightPx(icon, density),  EPS);
        assertEquals(floor,  BadgeSize.MEDIUM.heightPx(icon, density), EPS);
        assertEquals(25.2f,  BadgeSize.LARGE.heightPx(icon, density),  EPS);
        assertEquals(31.2f,  BadgeSize.HUGE.heightPx(icon, density),   EPS);
    }

    @Test
    public void labelsAreIndexAlignedWithValues() {
        assertEquals(BadgeSize.values().length, BadgeSize.labels().length);
    }
}
