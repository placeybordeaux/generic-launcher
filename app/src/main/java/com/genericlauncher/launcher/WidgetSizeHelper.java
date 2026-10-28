package com.genericlauncher.launcher;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.SizeF;

import java.util.ArrayList;

/**
 * Static helpers that supply Jetpack-Glance-compatible size options to
 * {@link AppWidgetHostView} and to the {@link AppWidgetManager} option bundle.
 *
 * <p>Glance providers compose against the list in
 * {@code AppWidgetManager.OPTION_APPWIDGET_SIZES} (API 31+) and against the
 * classic {@code OPTION_APPWIDGET_MIN/MAX_WIDTH/HEIGHT} bundle. When those
 * options are absent the host cannot select a sized RemoteViews to apply, and
 * {@code AppWidgetHostView.applyRemoteViews} throws in-host, producing the
 * framework error layout ("Couldn't add widget."). Real launchers set the full
 * options bundle immediately after binding a widget — BEFORE the first
 * {@code setAppWidget} apply — and whenever the widget's cell footprint changes.
 *
 * <p>This class is the <em>single source of truth</em> for that logic so the
 * finalize path, the populate path, and the post-layout refine path all agree.
 */
public final class WidgetSizeHelper {

    private WidgetSizeHelper() {}

    /**
     * Converts a widget's cell span and the cell pixel dimensions to dp values.
     *
     * @param spanX        number of columns the widget occupies (&ge; 0)
     * @param spanY        number of rows    the widget occupies (&ge; 0)
     * @param cellWidthPx  width of one grid cell in pixels
     * @param cellHeightPx height of one grid cell in pixels
     * @param density      screen density ({@link android.util.DisplayMetrics#density})
     * @return {@code int[]{widthDp, heightDp}}, both guaranteed &ge; 1.
     */
    public static int[] computeSizeDp(int spanX, int spanY,
                                      int cellWidthPx, int cellHeightPx,
                                      float density) {
        if (density <= 0f) density = 1f;
        int wDp = (spanX > 0 && cellWidthPx > 0)
                ? (int) (spanX * cellWidthPx / density) : 0;
        int hDp = (spanY > 0 && cellHeightPx > 0)
                ? (int) (spanY * cellHeightPx / density) : 0;
        return new int[]{Math.max(1, wDp), Math.max(1, hDp)};
    }

    /**
     * Estimates a single grid cell's pixel size from display metrics + grid
     * dimensions WITHOUT requiring a laid-out view. Mirrors the fallback
     * estimate in {@code MainActivity.computeWidgetSpan}: the usable height
     * subtracts the dock and dots insets so the estimate matches the real page.
     *
     * <p>Guaranteed to return values &ge; 1 so callers never feed 0-px cells
     * (and hence 0-dp sizes) to the provider.
     *
     * @return {@code int[]{cellWidthPx, cellHeightPx}}
     */
    public static int[] estimateCellPx(Context context, int cols, int rows) {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float density = dm.density <= 0f ? 1f : dm.density;
        int dotPx  = Math.round(HomescreenView.DOT_AREA_DP    * density);
        int dockPx = Math.round(HomescreenView.DOCK_HEIGHT_DP * density);
        int usableH = dm.heightPixels - dotPx - dockPx;
        int cellW = (cols > 0) ? dm.widthPixels / cols : dm.widthPixels;
        int cellH = (rows > 0) ? usableH / rows : usableH;
        return new int[]{Math.max(1, cellW), Math.max(1, cellH)};
    }

    /**
     * Applies size options to {@code view} using the best available API:
     * <ul>
     *   <li><b>API 31+</b>: {@code updateAppWidgetSize(Bundle, List&lt;SizeF&gt;)}, which
     *       sets {@code OPTION_APPWIDGET_SIZES} — required by Jetpack Glance.</li>
     *   <li><b>Pre-31</b>: {@code updateAppWidgetSize(null, wDp, hDp, wDp, hDp)}.</li>
     * </ul>
     *
     * <p>Returns immediately (no-op) when {@code cellWidthPx} or {@code cellHeightPx}
     * is &le; 0, so callers are safe to invoke before the first layout pass without
     * risk of delivering 0-dp sizes to the provider.
     */
    public static void applySize(AppWidgetHostView view,
                                 int spanX, int spanY,
                                 int cellWidthPx, int cellHeightPx,
                                 float density) {
        if (cellWidthPx <= 0 || cellHeightPx <= 0) return;
        int[] dp  = computeSizeDp(spanX, spanY, cellWidthPx, cellHeightPx, density);
        int wDp   = dp[0];
        int hDp   = dp[1];
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31
            ArrayList<SizeF> sizes = new ArrayList<>(1);
            sizes.add(new SizeF(wDp, hDp));
            view.updateAppWidgetSize(new Bundle(), sizes);
        } else {
            view.updateAppWidgetSize(null, wDp, hDp, wDp, hDp);
        }
    }

    /**
     * Builds the full widget options bundle for a given cell footprint. This is
     * the bundle the framework consults when applying a provider's RemoteViews,
     * so it MUST be present before the first {@code setAppWidget} apply.
     *
     * <p>Includes the classic min/max width/height ints, the host category, and —
     * on API 31+ — the {@code OPTION_APPWIDGET_SIZES} {@code ArrayList<SizeF>}.
     * Values are guaranteed &ge; 1 dp.
     */
    public static Bundle buildOptions(int spanX, int spanY,
                                      int cellWidthPx, int cellHeightPx,
                                      float density) {
        int[] dp = computeSizeDp(spanX, spanY, cellWidthPx, cellHeightPx, density);
        int wDp = dp[0];
        int hDp = dp[1];
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,  wDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,  wDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31
            ArrayList<SizeF> sizes = new ArrayList<>(1);
            sizes.add(new SizeF(wDp, hDp));
            options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes);
        }
        return options;
    }

    /**
     * Sets the FULL options bundle on the widget via
     * {@link AppWidgetManager#updateAppWidgetOptions(int, Bundle)}. Unlike
     * {@link #applySize(AppWidgetHostView, int, int, int, int, float)} this does
     * not require a hostview to exist and works before the first apply.
     *
     * <p>No-op when {@code cellWidthPx} or {@code cellHeightPx} is &le; 0.
     */
    public static void applyFullOptions(Context context, int appWidgetId,
                                        int spanX, int spanY,
                                        int cellWidthPx, int cellHeightPx,
                                        float density) {
        if (cellWidthPx <= 0 || cellHeightPx <= 0) return;
        Bundle options = buildOptions(spanX, spanY, cellWidthPx, cellHeightPx, density);
        AppWidgetManager.getInstance(context).updateAppWidgetOptions(appWidgetId, options);
    }
}
