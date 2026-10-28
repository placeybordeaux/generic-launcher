package com.genericlauncher.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The rainbow badge's count text must not flicker.
 *
 * <p>The bug: {@link BadgePainter} coloured the count with
 * {@link BadgePalette#textColor(int)}, thresholding the luminance of the badge's current fill.
 * A rainbow fill sweeps the whole hue wheel every {@link BadgePalette#RAINBOW_PERIOD_MS}ms, crossing
 * that threshold each way on the way round — into the light band near hue 24 and out of it near
 * hue 198 — so the digits snapped white → near-black → white every twelve seconds.
 *
 * <p>These tests hold the fix to two properties, and the second is the one that matters — the
 * flicker could also be "fixed" by making the text illegible at some hues:
 *
 * <ol>
 *   <li>the text colour is <em>constant</em> across a full cycle (the limit case of "changes
 *       smoothly": zero change per frame, no snap), and
 *   <li>contrast stays above the WCAG 4.5:1 floor against <em>every</em> hue the badge can paint,
 *       carried by the halo stroked behind the glyphs.
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BadgeTextContrastTest {

    /** WCAG 2.x AA floor for body text. Anything below this is the "hard to read" outcome. */
    private static final double MIN_CONTRAST = 4.5;

    /** Samples per rainbow cycle — every 50ms of a 12s lap, far finer than the eye. */
    private static final int SAMPLES = 240;

    /** A 52dp icon at density 3, i.e. the everyday home-screen badge. */
    private static final float DENSITY = 3f;
    private static final float ICON_PX = 52f * DENSITY;

    @Before
    public void reset() {
        BadgeAnimator.resetForTesting();
    }

    // -------------------------------------------------------------------------
    // Contrast maths (WCAG 2.x relative luminance) — test-local on purpose: production
    // picks text colours with a BT.601 luma threshold and must keep exactly one such rule.
    // This is a yardstick for the assertions, not a second convention for choosing colours.
    // -------------------------------------------------------------------------

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static double relativeLuminance(int color) {
        return 0.2126 * channel(Color.red(color))
             + 0.7152 * channel(Color.green(color))
             + 0.0722 * channel(Color.blue(color));
    }

    private static double contrast(int a, int b) {
        double la = relativeLuminance(a), lb = relativeLuminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    // -------------------------------------------------------------------------
    // Draw-path capture
    // -------------------------------------------------------------------------

    /** One recorded {@code drawText} call: what colour, filled or stroked, how wide. */
    private static final class Glyph {
        final int color;
        final Paint.Style style;
        final float strokeWidth;

        Glyph(Paint p) {
            this.color = p.getColor();
            this.style = p.getStyle();
            this.strokeWidth = p.getStrokeWidth();
        }
    }

    /**
     * Records every {@code drawText} the painter issues. Snapshots the {@link Paint} at the call
     * rather than keeping a reference: {@link BadgePainter} reuses one paint per role and mutates
     * it between frames, so a retained reference would read the last frame's colour for all of them.
     */
    private static final class RecordingCanvas extends Canvas {
        final List<Glyph> glyphs = new ArrayList<>();

        RecordingCanvas() {
            super(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888));
        }

        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            glyphs.add(new Glyph(paint));
        }
    }

    private static BadgePainter painter() {
        BadgePainter p = new BadgePainter(DENSITY, BadgeSize.MEDIUM);
        p.setIconSize(ICON_PX);
        return p;
    }

    /** Draws one badge and returns the text calls it made, in order. */
    private static List<Glyph> drawOnce(BadgePainter p, BadgeStyle style) {
        RecordingCanvas c = new RecordingCanvas();
        p.draw(c, 100f, 100f, 7, style, Color.BLUE, false, false);
        return c.glyphs;
    }

    /**
     * The filled glyph — the digits the user actually reads. Identified by draw order (it is
     * always drawn last) rather than by paint style, so a failure here means the count text was
     * not drawn at all rather than that it was drawn with an unexpected style.
     */
    private static Glyph fillGlyph(List<Glyph> glyphs) {
        if (glyphs.isEmpty()) throw new AssertionError("no count text was drawn at all");
        if (glyphs.size() > 2) throw new AssertionError("unexpected text passes: " + glyphs.size());
        Glyph last = glyphs.get(glyphs.size() - 1);
        // Not asserted as FILL: the painter leaves textPaint on the default style, and
        // Robolectric's shadow Paint reports null for a style that was never set explicitly.
        assertNotEquals("the count text itself must be filled, not stroked",
                Paint.Style.STROKE, last.style);
        return last;
    }

    /** The halo stroked behind it, or null when the style draws no halo. */
    private static Glyph haloGlyph(List<Glyph> glyphs) {
        if (glyphs.size() < 2) return null;
        Glyph first = glyphs.get(0);
        assertEquals("the halo must be a stroked pass under the fill",
                Paint.Style.STROKE, first.style);
        return first;
    }

    /** Advances the shared badge clock by one sample of a rainbow cycle. */
    private static void advanceOneSample() {
        ShadowSystemClock.advanceBy(
                Duration.ofMillis(BadgePalette.RAINBOW_PERIOD_MS / SAMPLES));
    }

    // -------------------------------------------------------------------------
    // 1. The text colour does not move across the cycle
    // -------------------------------------------------------------------------

    @Test
    public void rainbowCountText_holdsOneColourForAWholeCycle() {
        BadgePainter p = painter();
        Set<Integer> textColors = new LinkedHashSet<>();
        Set<Integer> haloColors = new LinkedHashSet<>();
        Set<Integer> fills = new LinkedHashSet<>();

        for (int i = 0; i < SAMPLES; i++) {
            List<Glyph> glyphs = drawOnce(p, BadgeStyle.RAINBOW);
            textColors.add(fillGlyph(glyphs).color);
            Glyph halo = haloGlyph(glyphs);
            assertNotNull("rainbow count text must be haloed on every frame", halo);
            haloColors.add(halo.color);
            fills.add(BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, BadgeAnimator.phase()));
            advanceOneSample();
        }

        // A constant is only interesting if the thing driving it moved. Without this the test
        // would pass just as happily on a clock that never advanced.
        assertTrue("the badge clock did not advance — only " + fills.size()
                        + " distinct fills over a full cycle, so the constancy below proves nothing",
                fills.size() >= SAMPLES - 40);

        assertEquals("count text snapped between colors during the cycle: " + textColors,
                1, textColors.size());
        assertEquals(BadgePalette.TEXT_LIGHT, (int) textColors.iterator().next());
        assertEquals("halo snapped between colors during the cycle: " + haloColors,
                1, haloColors.size());
        assertEquals(BadgePalette.TEXT_HALO, (int) haloColors.iterator().next());
    }

    /**
     * The regression this guards against, still measurable: sampled at the same rate, the old rule
     * ({@link BadgePalette#textColor(int)} against the badge's current fill) snaps white → dark
     * → white once every lap, as the hue enters the light band near 24° and leaves it near 198°.
     * If it ever stops flipping there is nothing left for the fix above to be fixing, and the
     * constancy test becomes vacuous.
     */
    @Test
    public void thresholdedPick_wouldStillSnapEachWayEveryLap() {
        int flips = 0;
        int previous = BadgePalette.textColor(BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, 0f));
        for (int i = 1; i <= SAMPLES; i++) {
            float phase = i / (float) SAMPLES;
            int now = BadgePalette.textColor(BadgePalette.fillColor(BadgeStyle.RAINBOW, 0, phase));
            if (now != previous) flips++;
            previous = now;
        }
        assertTrue("expected the old thresholded pick to snap both ways each lap, saw " + flips,
                flips >= 2);
    }

    // -------------------------------------------------------------------------
    // 2. Contrast holds at every hue — the flicker is not fixed by hiding the text
    // -------------------------------------------------------------------------

    /** The fill at hue {@code h}, exactly as the rainbow spectrum paints it. */
    private static int spectrum(int hue) {
        return Color.HSVToColor(
                new float[]{hue, BadgePalette.RAINBOW_SAT, BadgePalette.RAINBOW_VAL});
    }

    /**
     * The legibility guarantee, stated as the glyph/halo pair rather than as one colour: at every
     * hue the rainbow can paint, <em>either</em> the white glyph or the black halo around it
     * clears 4.5:1 against the fill — and the glyph-to-halo edge clears it everywhere, so the
     * digit is delineated even at the hues where the glyph body alone would wash out.
     *
     * <p>Every hue, not merely the one at the current phase: {@link BadgePainter} paints a whole
     * spectrum across each badge, so one digit sits on several hues at once and the guarantee has
     * to cover the wheel.
     */
    @Test
    public void glyphAndHalo_clearTheContrastFloorAtEveryHueInTheSpectrum() {
        int text = BadgePalette.textColor(BadgeStyle.RAINBOW, /* ignored */ Color.BLUE);
        int halo = BadgePalette.TEXT_HALO;

        assertTrue("glyph vs halo is " + contrast(halo, text) + ":1",
                contrast(halo, text) >= MIN_CONTRAST);

        double worst = Double.MAX_VALUE;
        int worstHue = -1;
        for (int h = 0; h < 360; h++) {
            int fill = spectrum(h);
            double best = Math.max(contrast(text, fill), contrast(halo, fill));
            if (best < worst) { worst = best; worstHue = h; }
        }
        assertTrue("contrast bottoms out at " + worst + ":1 against hue " + worstHue,
                worst >= MIN_CONTRAST);
    }

    /**
     * Why the pair, and why the halo is pure black. Ruled out here with the same yardstick:
     *
     * <ul>
     *   <li>any flat text colour on its own — white, near-black, or the mid grey a cross-fade
     *       would pass through — drops below the floor somewhere on the wheel;
     *   <li>and pairing white with {@link BadgePalette#TEXT_DARK} instead of pure black leaves a
     *       band of violets where neither reaches it.
     * </ul>
     */
    @Test
    public void theRuledOutAlternatives_failTheSameFloor() {
        int crossfadeMidpoint = Color.rgb(
                (Color.red(BadgePalette.TEXT_LIGHT)   + Color.red(BadgePalette.TEXT_DARK))   / 2,
                (Color.green(BadgePalette.TEXT_LIGHT) + Color.green(BadgePalette.TEXT_DARK)) / 2,
                (Color.blue(BadgePalette.TEXT_LIGHT)  + Color.blue(BadgePalette.TEXT_DARK))  / 2);

        for (int flat : new int[]{BadgePalette.TEXT_LIGHT, BadgePalette.TEXT_DARK,
                BadgePalette.TEXT_HALO, crossfadeMidpoint}) {
            double worst = Double.MAX_VALUE;
            for (int h = 0; h < 360; h++) worst = Math.min(worst, contrast(flat, spectrum(h)));
            assertTrue("unhaloed #" + Integer.toHexString(flat)
                            + " unexpectedly cleared every hue (worst " + worst + ":1)",
                    worst < MIN_CONTRAST);
        }

        double worstNearBlackPair = Double.MAX_VALUE;
        for (int h = 0; h < 360; h++) {
            int fill = spectrum(h);
            worstNearBlackPair = Math.min(worstNearBlackPair,
                    Math.max(contrast(BadgePalette.TEXT_LIGHT, fill),
                             contrast(BadgePalette.TEXT_DARK, fill)));
        }
        assertTrue("a TEXT_DARK halo now clears the floor (" + worstNearBlackPair
                        + ":1) — TEXT_HALO could be simplified to the other text token",
                worstNearBlackPair < MIN_CONTRAST);
    }

    /** The halo has to be wide enough to see: a stroke proportional to the text it outlines. */
    @Test
    public void halo_isStrokedWideEnoughToRead() {
        BadgePainter p = painter();
        Glyph halo = haloGlyph(drawOnce(p, BadgeStyle.RAINBOW));
        assertNotNull(halo);
        // Text size is 10/16 of badge height; the halo must be a visible fraction of that.
        float textPx = p.height() * (10f / 16f);
        assertTrue("halo stroke " + halo.strokeWidth + "px on " + textPx + "px text",
                halo.strokeWidth >= textPx * 0.15f);
    }

    // -------------------------------------------------------------------------
    // 3. The solid styles are untouched
    // -------------------------------------------------------------------------

    @Test
    public void solidStyles_keepTheContrastPickAndDrawNoHalo() {
        BadgePainter p = painter();

        List<Glyph> red = drawOnce(p, BadgeStyle.FLAT);
        assertNull("solid fills need no outline around the digits", haloGlyph(red));
        assertEquals(BadgePalette.textColor(BadgePalette.RED_FILL), fillGlyph(red).color);

        // A pale yellow icon's complement is a deep blue fill, which still takes white text;
        // what matters is that the pick is the fill-driven one, not the rainbow override.
        int icon = Color.rgb(255, 240, 120);
        int fill = BadgePalette.fillColor(BadgeStyle.FLAT, icon, 0f);
        RecordingCanvas c = new RecordingCanvas();
        p.draw(c, 100f, 100f, 7, BadgeStyle.FLAT, icon, false, false);
        assertNull(haloGlyph(c.glyphs));
        assertEquals(BadgePalette.textColor(fill), fillGlyph(c.glyphs).color);
    }

    @Test
    public void styleAwareTextColor_delegatesForEverythingButRainbow() {
        assertEquals(BadgePalette.textColor(Color.WHITE),
                BadgePalette.textColor(BadgeStyle.FLAT, Color.WHITE));
        assertEquals(BadgePalette.textColor(Color.BLACK),
                BadgePalette.textColor(BadgeStyle.FLAT, Color.BLACK));
        assertEquals(BadgePalette.textColor(Color.YELLOW),
                BadgePalette.textColor(null, Color.YELLOW));

        // Rainbow ignores the fill entirely — including a fill the threshold would call "light".
        assertEquals(BadgePalette.TEXT_LIGHT,
                BadgePalette.textColor(BadgeStyle.RAINBOW, Color.YELLOW));
        assertEquals(BadgePalette.TEXT_LIGHT,
                BadgePalette.textColor(BadgeStyle.RAINBOW, Color.BLACK));

        assertTrue(BadgePalette.needsTextHalo(BadgeStyle.RAINBOW));
        assertFalse(BadgePalette.needsTextHalo(BadgeStyle.FLAT));
        assertFalse(BadgePalette.needsTextHalo(BadgeStyle.FLAT));
        assertFalse(BadgePalette.needsTextHalo(null));

        // Opaque: a translucent halo would let the hue through and weaken the guarantee above.
        assertEquals(0xFF, Color.alpha(BadgePalette.TEXT_HALO));
    }
}
