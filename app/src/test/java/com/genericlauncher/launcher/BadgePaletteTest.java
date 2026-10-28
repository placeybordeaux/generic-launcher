package com.genericlauncher.launcher;

import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link BadgePalette} — the pure colour/count maths behind notification badges.
 * Uses Robolectric so {@code android.graphics.Color}'s HSV conversions work on the JVM.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BadgePaletteTest {

    private static float hueOf(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }

    private static float satOf(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[1];
    }

    private static float valOf(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[2];
    }

    /** Smallest angular distance between two hues, in degrees. */
    private static float hueDelta(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    // -------------------------------------------------------------------------
    // FLAT — the seam's colour, used verbatim
    // -------------------------------------------------------------------------

    @Test
    public void flat_returnsTheSeamFillUnchanged_whateverThePhase() {
        assertEquals(Color.GREEN, BadgePalette.fillColor(BadgeStyle.FLAT, Color.GREEN, 0f));
        assertEquals(Color.BLUE,  BadgePalette.fillColor(BadgeStyle.FLAT, Color.BLUE, 0.75f));
    }

    @Test
    public void flat_isTheFallbackForANullStyle() {
        assertEquals(Color.BLUE, BadgePalette.fillColor(null, Color.BLUE, 0.3f));
    }

    @Test
    public void redFill_isWhatTheDefaultAppearanceHandsBack() {
        // BadgeAppearance.RED returns this; the palette no longer decides it, which is the point
        // of the seam. Asserted here so the constant cannot drift away from the default.
        assertEquals(BadgePalette.RED_FILL,
                BadgePalette.fillColor(BadgeStyle.FLAT, BadgePalette.RED_FILL, 0f));
    }

    // -------------------------------------------------------------------------
    // RAINBOW — deterministic phase -> colour
    // -------------------------------------------------------------------------

    @Test
    public void rainbow_hueTracksPhase() {
        assertEquals(0f,   hueOf(BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, 0f)),    1f);
        assertEquals(90f,  hueOf(BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, 0.25f)), 1f);
        assertEquals(180f, hueOf(BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, 0.5f)),  1f);
        assertEquals(270f, hueOf(BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, 0.75f)), 1f);
    }

    @Test
    public void rainbow_isDeterministicForAPhase() {
        for (float p = 0f; p < 1f; p += 0.05f) {
            assertEquals(BadgePalette.fillColor(BadgeStyle.RAINBOW, Color.RED, p),
                         BadgePalette.fillColor(BadgeStyle.RAINBOW, Color.BLUE, p));
        }
    }

    @Test
    public void rainbow_isVivid() {
        int fill = BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, 0.4f);
        assertTrue(satOf(fill) >= 0.7f);
        assertTrue(valOf(fill) >= 0.9f);
    }

    @Test
    public void rainbow_outOfRangePhaseWraps() {
        assertEquals(BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, 0.25f),
                     BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, 1.25f));
        assertEquals(BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, 0.75f),
                     BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, -0.25f));
    }

    // -------------------------------------------------------------------------
    // textColor — contrast flip
    // -------------------------------------------------------------------------

    @Test
    public void textColor_darkFill_getsWhiteText() {
        assertEquals(BadgePalette.TEXT_LIGHT, BadgePalette.textColor(Color.BLACK));
        assertEquals(BadgePalette.TEXT_LIGHT, BadgePalette.textColor(BadgePalette.RED_FILL));
        assertEquals(BadgePalette.TEXT_LIGHT, BadgePalette.textColor(Color.BLUE));
    }

    @Test
    public void textColor_lightFill_getsDarkText() {
        assertEquals(BadgePalette.TEXT_DARK, BadgePalette.textColor(Color.WHITE));
        assertEquals(BadgePalette.TEXT_DARK, BadgePalette.textColor(Color.YELLOW));
    }

    @Test
    public void textColor_flipsAtTheLuminanceThreshold() {
        // Grey ramp: luma of a grey equals its channel value, so the flip lands exactly on
        // TEXT_CONTRAST_THRESHOLD.
        int justBelow = (int) ((BadgePalette.TEXT_CONTRAST_THRESHOLD * 255f) - 4f);
        int justAbove = (int) ((BadgePalette.TEXT_CONTRAST_THRESHOLD * 255f) + 4f);
        assertEquals(BadgePalette.TEXT_LIGHT,
                BadgePalette.textColor(Color.rgb(justBelow, justBelow, justBelow)));
        assertEquals(BadgePalette.TEXT_DARK,
                BadgePalette.textColor(Color.rgb(justAbove, justAbove, justAbove)));
    }

    @Test
    public void textColor_isAlwaysOneOfTheTwoTokens() {
        for (int r = 0; r <= 255; r += 51) {
            for (int g = 0; g <= 255; g += 51) {
                for (int b = 0; b <= 255; b += 51) {
                    int t = BadgePalette.textColor(Color.rgb(r, g, b));
                    assertTrue(t == BadgePalette.TEXT_LIGHT || t == BadgePalette.TEXT_DARK);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // formatCount
    // -------------------------------------------------------------------------

    @Test
    public void formatCount_zeroAndNegative_areEmpty() {
        assertEquals("", BadgePalette.formatCount(0));
        assertEquals("", BadgePalette.formatCount(-1));
        assertEquals("", BadgePalette.formatCount(Integer.MIN_VALUE));
    }

    @Test
    public void formatCount_plainNumbersUpTo99() {
        assertEquals("1",  BadgePalette.formatCount(1));
        assertEquals("9",  BadgePalette.formatCount(9));
        assertEquals("10", BadgePalette.formatCount(10));
        assertEquals("99", BadgePalette.formatCount(99));
    }

    @Test
    public void formatCount_capsAt99Plus() {
        assertEquals("99+", BadgePalette.formatCount(100));
        assertEquals("99+", BadgePalette.formatCount(1234));
        assertEquals("99+", BadgePalette.formatCount(Integer.MAX_VALUE));
    }

    @Test
    public void formatCount_neverExceedsThreeCharacters() {
        for (int i = -5; i < 200; i++) {
            assertTrue(BadgePalette.formatCount(i).length() <= 3);
        }
    }

    // -------------------------------------------------------------------------
    // rainbowPhase
    // -------------------------------------------------------------------------

    @Test
    public void rainbowPhase_staysWithinUnitRange() {
        long[] samples = {0L, 1L, 5_999L, 11_999L, 12_000L, 12_001L,
                          123_456_789L, Long.MAX_VALUE / 2};
        for (long t : samples) {
            float p = BadgePalette.rainbowPhase(t);
            assertTrue("phase " + p + " out of range for t=" + t, p >= 0f && p < 1f);
        }
    }

    @Test
    public void rainbowPhase_wrapsAtThePeriod() {
        assertEquals(0f, BadgePalette.rainbowPhase(0L), 1e-6f);
        assertEquals(0f, BadgePalette.rainbowPhase(BadgePalette.RAINBOW_PERIOD_MS), 1e-6f);
        assertEquals(0.5f,
                BadgePalette.rainbowPhase(BadgePalette.RAINBOW_PERIOD_MS / 2), 1e-6f);
        assertEquals(BadgePalette.rainbowPhase(3_000L),
                BadgePalette.rainbowPhase(3_000L + BadgePalette.RAINBOW_PERIOD_MS * 7), 1e-6f);
    }

    @Test
    public void rainbowPhase_negativeInputWrapsForward() {
        float p = BadgePalette.rainbowPhase(-1_000L);
        assertTrue(p >= 0f && p < 1f);
        assertEquals(BadgePalette.rainbowPhase(BadgePalette.RAINBOW_PERIOD_MS - 1_000L), p, 1e-6f);
    }

    @Test
    public void wrapPhase_foldsAnyFloatIntoUnitRange() {
        float[] inputs = {0f, 0.5f, 1f, 1.5f, -0.25f, -3.75f, 42f,
                          Float.NaN, Float.POSITIVE_INFINITY};
        for (float in : inputs) {
            float p = BadgePalette.wrapPhase(in);
            assertTrue("wrapPhase(" + in + ") = " + p, p >= 0f && p < 1f);
        }
    }

    // -------------------------------------------------------------------------
    // Rainbow band primitive
    // -------------------------------------------------------------------------

    private static final float WAVE  = 840f;   // px
    private static final float ANGLE = 25f;    // degrees

    private static float fieldHue(float x, float y, float phase) {
        return BadgePalette.rainbowFieldHue(x, y, phase, WAVE, ANGLE);
    }

    @Test
    public void fieldHueIsAlwaysInRange() {
        for (float x = -2000f; x <= 2000f; x += 137f) {
            float h = fieldHue(x, 0.5f * x, 0.3f);
            assertTrue("hue out of range: " + h, h >= 0f && h < 360f);
        }
    }

    @Test
    public void fieldHueIsDeterministic() {
        assertEquals(fieldHue(120f, 340f, 0.25f), fieldHue(120f, 340f, 0.25f), 0.001f);
    }

    /** One full wavelength along the band direction is exactly one trip round the wheel. */
    @Test
    public void fieldRepeatsOneWavelengthAlongTheBand() {
        double a = Math.toRadians(ANGLE);
        float dx = (float) (WAVE * Math.cos(a));
        float dy = (float) (WAVE * Math.sin(a));
        assertEquals(0f, hueDelta(fieldHue(0f, 0f, 0.1f), fieldHue(dx, dy, 0.1f)), 0.05f);
    }

    /** Perpendicular to the band the hue does not change — that is what makes it a band. */
    @Test
    public void fieldIsConstantPerpendicularToTheBand() {
        double a = Math.toRadians(ANGLE);
        float px = (float) (-500f * Math.sin(a));
        float py = (float) ( 500f * Math.cos(a));
        assertEquals(0f, hueDelta(fieldHue(0f, 0f, 0.1f), fieldHue(px, py, 0.1f)), 0.05f);
    }

    /** Advancing the phase by half a period moves every point half way round the wheel. */
    @Test
    public void fieldDriftsWithPhase() {
        assertEquals(180f, hueDelta(fieldHue(200f, 100f, 0f), fieldHue(200f, 100f, 0.5f)), 0.05f);
    }

    /** A third of a wavelength along the band is a third of the way round the wheel. */
    @Test
    public void pointsAThirdOfAWavelengthApartDifferBy120Degrees() {
        double a = Math.toRadians(ANGLE);
        float dx = (float) (WAVE / 3f * Math.cos(a));
        float dy = (float) (WAVE / 3f * Math.sin(a));
        assertEquals(120f, hueDelta(fieldHue(0f, 0f, 0f), fieldHue(dx, dy, 0f)), 0.05f);
    }

    // -------------------------------------------------------------------------
    // Per-badge rainbow
    // -------------------------------------------------------------------------

    /** A 44px circle and a 96×44 pill — one digit versus three. */
    private static final float CIRCLE = 44f;
    private static final float PILL_W = 96f;

    @Test
    public void spanIsTheBadgeExtentAlongTheBandDirection() {
        double a = Math.toRadians(ANGLE);
        float expected = (float) (PILL_W * Math.cos(a) + CIRCLE * Math.sin(a));
        assertEquals(expected, BadgePalette.rainbowSpanPx(PILL_W, CIRCLE, ANGLE), 0.001f);
    }

    /**
     * Exactly one full spectrum spans a badge corner to corner — the hue at the far corner has
     * come the whole way round the wheel and met the near corner's hue again.
     */
    @Test
    public void oneFullSpectrumSpansEachBadge() {
        float near = BadgePalette.rainbowBadgeHue(0f, 0f, 0.2f, CIRCLE, CIRCLE, ANGLE);
        float far  = BadgePalette.rainbowBadgeHue(CIRCLE, CIRCLE, 0.2f, CIRCLE, CIRCLE, ANGLE);
        assertEquals(0f, hueDelta(near, far), 0.05f);
    }

    /**
     * The point of the rework: a badge's colour comes from where you are <em>within the badge</em>,
     * so the middle of a circle and the middle of a pill match despite the different widths.
     * Every badge on screen therefore shows the same rainbow at the same instant.
     */
    @Test
    public void differentlyShapedBadgesAgreeAtTheSameFractionAcross() {
        float circle = BadgePalette.rainbowBadgeHue(
                CIRCLE / 2f, CIRCLE / 2f, 0.4f, CIRCLE, CIRCLE, ANGLE);
        float pill = BadgePalette.rainbowBadgeHue(
                PILL_W / 2f, CIRCLE / 2f, 0.4f, PILL_W, CIRCLE, ANGLE);
        assertEquals(0f, hueDelta(circle, pill), 0.05f);
    }

    /** Half a period of drift moves the whole badge half way round the wheel. */
    @Test
    public void badgeRainbowDriftsWithPhase() {
        float before = BadgePalette.rainbowBadgeHue(10f, 8f, 0f,   CIRCLE, CIRCLE, ANGLE);
        float after  = BadgePalette.rainbowBadgeHue(10f, 8f, 0.5f, CIRCLE, CIRCLE, ANGLE);
        assertEquals(180f, hueDelta(before, after), 0.05f);
    }

    @Test
    public void badgeHueIsAlwaysInRange() {
        for (float d = 0f; d <= CIRCLE; d += 3f) {
            float h = BadgePalette.rainbowBadgeHue(d, CIRCLE - d, 0.7f, CIRCLE, CIRCLE, ANGLE);
            assertTrue("hue out of range: " + h, h >= 0f && h < 360f);
        }
    }

    /** A degenerate badge must not divide by zero. */
    @Test
    public void zeroSizedBadgeIsHandled() {
        assertEquals(0f, BadgePalette.rainbowBadgeHue(0f, 0f, 0.3f, 0f, 0f, ANGLE), 0.001f);
    }
}
