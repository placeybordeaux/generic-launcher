package com.genericlauncher.launcher;

import android.appwidget.AppWidgetProviderInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for HomescreenView.computeSpan — a pure math helper that converts a widget's
 * minimum pixel dimensions into a home-screen cell span — and for spanForProvider, which is
 * where the units are actually decided.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetSpanTest {

    // Convenience: use the factory defaults so these tests cover the common case.
    private static final int MAX_COLS = HomeScreenStore.DEFAULT_COLS;
    private static final int MAX_ROWS = HomeScreenStore.DEFAULT_ROWS;

    private static AppWidgetProviderInfo provider(int minWidthPx, int minHeightPx) {
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.minWidth  = minWidthPx;
        info.minHeight = minHeightPx;
        return info;
    }

    /**
     * {@code AppWidgetProviderInfo.minWidth/minHeight} are in PIXELS — the framework has already
     * scaled the provider XML's dp by display density. Both call sites used to scale again, which
     * placed widgets several cells too large.
     *
     * <p>Numbers are the real ones measured on the CPH2749 (560dpi, 3.5x): the Google search
     * widget reports minWidth=980px against a 318×447 cell, so the correct span is 4×1. The old
     * double scaling asked for 875px of height and produced 4×2 — the "takes up a bunch of room"
     * symptom. computeSpan itself was always right; this conversion is what was untested.
     */
    @Test
    public void spanForProvider_treatsProviderMinimaAsPixels() {
        int[] span = HomescreenView.spanForProvider(provider(980, 250), 318, 447, 4, 5);
        assertEquals("980px over a 318px cell is 4 columns", 4, span[0]);
        assertEquals("250px fits inside one 447px row — it must not become 2", 1, span[1]);
    }

    @Test
    public void spanForProvider_doesNotMultiplyByDensity() {
        // Were anything to scale by 3.5 again, height would be 875px and this would be 2 rows.
        int[] span = HomescreenView.spanForProvider(provider(300, 250), 318, 447, 4, 5);
        assertArrayEquals(new int[]{1, 1}, span);
    }

    @Test
    public void spanForProvider_nullProviderIsOneByOne() {
        assertArrayEquals(new int[]{1, 1},
                HomescreenView.spanForProvider(null, 318, 447, 4, 5));
    }

    @Test
    public void spanForProvider_stillClampsToTheGrid() {
        int[] span = HomescreenView.spanForProvider(provider(99999, 99999), 318, 447, 4, 5);
        assertArrayEquals(new int[]{4, 5}, span);
    }

    @Test
    public void computeSpan_tiny_isOneByOne() {
        // Widget smaller than one cell maps to span 1x1.
        int[] span = HomescreenView.computeSpan(50, 60, 100, 120, MAX_COLS, MAX_ROWS);
        assertArrayEquals(new int[]{1, 1}, span);
    }

    @Test
    public void computeSpan_exactlyOneCell() {
        int[] span = HomescreenView.computeSpan(100, 120, 100, 120, MAX_COLS, MAX_ROWS);
        assertArrayEquals(new int[]{1, 1}, span);
    }

    @Test
    public void computeSpan_twoByTwo() {
        // ceil(200/100) = 2, ceil(240/120) = 2
        int[] span = HomescreenView.computeSpan(200, 240, 100, 120, MAX_COLS, MAX_ROWS);
        assertArrayEquals(new int[]{2, 2}, span);
    }

    @Test
    public void computeSpan_ceilRoundsUp() {
        // ceil(101/100) = 2 — one pixel over triggers the next span
        int[] span = HomescreenView.computeSpan(101, 121, 100, 120, MAX_COLS, MAX_ROWS);
        assertArrayEquals(new int[]{2, 2}, span);
    }

    @Test
    public void computeSpan_clampedToGridColumns() {
        // Wider than the grid → clamped to DEFAULT_COLS (4)
        int[] span = HomescreenView.computeSpan(99999, 1, 100, 120, MAX_COLS, MAX_ROWS);
        assertEquals(HomeScreenStore.DEFAULT_COLS, span[0]);
        assertEquals(1, span[1]);
    }

    @Test
    public void computeSpan_clampedToGridRows() {
        // Taller than the grid → clamped to DEFAULT_ROWS (5)
        int[] span = HomescreenView.computeSpan(1, 99999, 100, 120, MAX_COLS, MAX_ROWS);
        assertEquals(1, span[0]);
        assertEquals(HomeScreenStore.DEFAULT_ROWS, span[1]);
    }

    @Test
    public void computeSpan_zeroCellSize_returnsOneOne() {
        // Guard against division-by-zero when cell dimensions are not yet measured.
        int[] span = HomescreenView.computeSpan(200, 300, 0, 0, MAX_COLS, MAX_ROWS);
        assertArrayEquals(new int[]{1, 1}, span);
    }

    @Test
    public void computeSpan_asymmetricCells() {
        // Wide cell, tall widget: spanX small, spanY large
        int[] span = HomescreenView.computeSpan(100, 600, 200, 150, MAX_COLS, MAX_ROWS);
        // ceil(100/200)=1, ceil(600/150)=4
        assertArrayEquals(new int[]{1, 4}, span);
    }

    @Test
    public void computeSpan_respectsCustomGridLimits() {
        // A 6-column, 8-row grid; widget that would be 5×6 in the default grid → unclamped.
        int[] span = HomescreenView.computeSpan(500, 720, 100, 120, 6, 8);
        assertEquals(5, span[0]);
        assertEquals(6, span[1]);
    }
}
