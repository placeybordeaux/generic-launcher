package com.genericlauncher.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.SizeF;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link WidgetSizeHelper}.
 *
 * <p>The dp-conversion math must be correct so that Glance widgets receive a
 * non-zero size on first bind. Tests use the same {@code @Config(sdk=33)} convention
 * as {@link WidgetSpanTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetSizeHelperTest {

    // -------------------------------------------------------------------------
    // computeSizeDp — dp conversion
    // -------------------------------------------------------------------------

    @Test
    public void computeSizeDp_basic_correctConversion() {
        // 2 cells * 100 px / 2.0 density = 100 dp each axis
        int[] dp = WidgetSizeHelper.computeSizeDp(2, 2, 100, 100, 2.0f);
        assertArrayEquals(new int[]{100, 100}, dp);
    }

    @Test
    public void computeSizeDp_asymmetric_axes() {
        // spanX=1 * 200 px / 2.0 = 100 dp; spanY=3 * 150 px / 2.0 = 225 dp
        int[] dp = WidgetSizeHelper.computeSizeDp(1, 3, 200, 150, 2.0f);
        assertArrayEquals(new int[]{100, 225}, dp);
    }

    @Test
    public void computeSizeDp_mdpiDensity_pixelsEqualDp() {
        // At mdpi (density=1), pixels and dp are the same.
        int[] dp = WidgetSizeHelper.computeSizeDp(1, 1, 320, 480, 1.0f);
        assertArrayEquals(new int[]{320, 480}, dp);
    }

    @Test
    public void computeSizeDp_highDensity() {
        // 3 * 300 px / 3.0 = 300 dp; 1 * 200 px / 3.0 = 66 dp (int truncation)
        int[] dp = WidgetSizeHelper.computeSizeDp(3, 1, 300, 200, 3.0f);
        assertEquals(300, dp[0]);
        assertEquals(66,  dp[1]);
    }

    // -------------------------------------------------------------------------
    // computeSizeDp — min-1dp guards
    // -------------------------------------------------------------------------

    @Test
    public void computeSizeDp_zeroCellWidth_widthIsMin1() {
        // Zero cell width → width must be at least 1 dp.
        int[] dp = WidgetSizeHelper.computeSizeDp(2, 2, 0, 100, 2.0f);
        assertEquals(1,   dp[0]);
        assertEquals(100, dp[1]);
    }

    @Test
    public void computeSizeDp_zeroCellHeight_heightIsMin1() {
        int[] dp = WidgetSizeHelper.computeSizeDp(2, 2, 100, 0, 2.0f);
        assertEquals(100, dp[0]);
        assertEquals(1,   dp[1]);
    }

    @Test
    public void computeSizeDp_bothZeroCellDims_bothMin1() {
        int[] dp = WidgetSizeHelper.computeSizeDp(0, 0, 0, 0, 2.0f);
        assertArrayEquals(new int[]{1, 1}, dp);
    }

    @Test
    public void computeSizeDp_tinyPixelHighDensity_min1() {
        // 1px cell / density 10 → 0.1 dp → rounds to 0 → must clamp to 1.
        int[] dp = WidgetSizeHelper.computeSizeDp(1, 1, 1, 1, 10.0f);
        assertArrayEquals(new int[]{1, 1}, dp);
    }

    @Test
    public void computeSizeDp_zeroDensityGuard_doesNotDivideByZero() {
        // Pathological: density=0 must not throw.
        int[] dp = WidgetSizeHelper.computeSizeDp(2, 2, 100, 100, 0.0f);
        assertTrue(dp[0] >= 1);
        assertTrue(dp[1] >= 1);
    }

    // -------------------------------------------------------------------------
    // SizeF list content (verifies the values we put into OPTION_APPWIDGET_SIZES)
    // -------------------------------------------------------------------------

    @Test
    public void computeSizeDp_valuesFitInSizeF() {
        // Verify that the dp values produced by computeSizeDp map into the SizeF
        // correctly — this is the content of the API-31 OPTION_APPWIDGET_SIZES list.
        int[] dp = WidgetSizeHelper.computeSizeDp(2, 3, 180, 160, 2.0f);
        // spanX=2 * 180 px / 2 = 180 dp; spanY=3 * 160 px / 2 = 240 dp
        assertEquals(180, dp[0]);
        assertEquals(240, dp[1]);

        // SizeF is API-21; always available in Robolectric SDK-33 tests.
        SizeF sf = new SizeF(dp[0], dp[1]);
        assertEquals(180f, sf.getWidth(),  0.001f);
        assertEquals(240f, sf.getHeight(), 0.001f);
    }

    @Test
    public void computeSizeDp_singleCell_sizeFIsPositive() {
        // A 1×1 widget at any density must produce a positive SizeF.
        int[] dp = WidgetSizeHelper.computeSizeDp(1, 1, 270, 180, 3.0f);
        // 1*270/3 = 90 dp; 1*180/3 = 60 dp
        assertEquals(90, dp[0]);
        assertEquals(60, dp[1]);
        SizeF sf = new SizeF(dp[0], dp[1]);
        assertTrue(sf.getWidth()  > 0f);
        assertTrue(sf.getHeight() > 0f);
    }

    // -------------------------------------------------------------------------
    // estimateCellPx — display-metrics + grid cell estimate (no laid-out view)
    // -------------------------------------------------------------------------

    @Test
    public void estimateCellPx_dividesUsableSpaceByGrid() {
        Context ctx = ApplicationProvider.getApplicationContext();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int[] cell = WidgetSizeHelper.estimateCellPx(ctx, 4, 5);
        float density = dm.density <= 0f ? 1f : dm.density;
        int dotPx  = Math.round(HomescreenView.DOT_AREA_DP    * density);
        int dockPx = Math.round(HomescreenView.DOCK_HEIGHT_DP * density);
        int expectW = dm.widthPixels / 4;
        int expectH = (dm.heightPixels - dotPx - dockPx) / 5;
        assertEquals(Math.max(1, expectW), cell[0]);
        assertEquals(Math.max(1, expectH), cell[1]);
    }

    @Test
    public void estimateCellPx_alwaysAtLeast1() {
        Context ctx = ApplicationProvider.getApplicationContext();
        // Pathological grid dimensions must never yield a 0-px cell.
        int[] cell = WidgetSizeHelper.estimateCellPx(ctx, 10000, 10000);
        assertTrue(cell[0] >= 1);
        assertTrue(cell[1] >= 1);
    }

    @Test
    public void estimateCellPx_zeroColsRows_doesNotDivideByZero() {
        Context ctx = ApplicationProvider.getApplicationContext();
        int[] cell = WidgetSizeHelper.estimateCellPx(ctx, 0, 0);
        assertTrue(cell[0] >= 1);
        assertTrue(cell[1] >= 1);
    }

    // -------------------------------------------------------------------------
    // buildOptions — full options bundle content
    // -------------------------------------------------------------------------

    @Test
    public void buildOptions_setsMinMaxWidthHeightToComputedDp() {
        // 2 cells * 100px / 2.0 = 100 dp width; 2 * 120 / 2.0 = 120 dp height.
        Bundle b = WidgetSizeHelper.buildOptions(2, 2, 100, 120, 2.0f);
        assertEquals(100, b.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH));
        assertEquals(100, b.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH));
        assertEquals(120, b.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT));
        assertEquals(120, b.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT));
    }

    @Test
    public void buildOptions_setsHomeScreenHostCategory() {
        Bundle b = WidgetSizeHelper.buildOptions(1, 1, 100, 100, 1.0f);
        assertEquals(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                b.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY));
    }

    @Test
    public void buildOptions_onS_setsSizesList() {
        // @Config(sdk = 33) is >= S, so OPTION_APPWIDGET_SIZES must be present.
        Bundle b = WidgetSizeHelper.buildOptions(2, 3, 90, 80, 1.0f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ArrayList<SizeF> sizes =
                    b.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            assertNotNull(sizes);
            assertEquals(1, sizes.size());
            // spanX=2 * 90 = 180 dp, spanY=3 * 80 = 240 dp at density 1.0.
            assertEquals(180f, sizes.get(0).getWidth(),  0.001f);
            assertEquals(240f, sizes.get(0).getHeight(), 0.001f);
        }
    }

    @Test
    public void buildOptions_valuesNeverZero() {
        // Even with zero cell dims the min/max dp are clamped to >= 1.
        Bundle b = WidgetSizeHelper.buildOptions(2, 2, 0, 0, 2.0f);
        assertTrue(b.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)  >= 1);
        assertTrue(b.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) >= 1);
    }
}
