package com.genericlauncher.launcher;

import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Layout-level tests for {@link HomescreenView}'s window-inset handling and the dock's
 * scroll-cancelling translation.
 *
 * <p>Two regressions from Pixel 9a feedback live here:
 * <ul>
 *   <li>At {@code targetSdk 35} the window is edge-to-edge, and the view laid its pages out from
 *       y=0 — so the top row of icons (and the "At a Glance" widget) sat under the status bar.</li>
 *   <li>{@code onLayout} pinned the dock with {@code translationX = 0}. The parent scrolls its
 *       content, so 0 meant "scroll away with page 0" and the dock vanished on pages 2+.</li>
 * </ul>
 *
 * Sizes are Pixel 9a's 1080x2424 with plausible system bars.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class HomescreenInsetLayoutTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int STATUS_BAR = 130; // punch-hole status bar, px
    private static final int NAV_BAR    = 63;  // gesture pill, px

    private Context ctx;
    private HomeScreenStore store;
    private HomescreenView view;
    private float density;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
        new File(ctx.getFilesDir(), "homescreen.json.tmp").delete();
        store = HomeScreenStore.get(ctx);
        density = ctx.getResources().getDisplayMetrics().density;

        view = new HomescreenView(ctx);
        view.setStore(store);
        view.refresh();
    }

    private int dp(int v) { return (int) (v * density); }

    /** Reserved strip below the grid: dock pill + page-dot area. */
    private int chromeHeight() {
        return dp(HomescreenView.DOCK_HEIGHT_DP) + dp(HomescreenView.DOT_AREA_DP);
    }

    private void applyInsets(int top, int bottom) {
        view.onApplyWindowInsets(new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, top, 0, bottom))
                .build());
    }

    private void measureAndLayout() {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    /** The dock is the one non-{@link CellLayout} child added by {@code buildDock()}. */
    private View dock() {
        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (!(child instanceof CellLayout)) return child;
        }
        fail("no dock child found");
        return null;
    }

    private CellLayout page(int i) {
        return view.getPage(i);
    }

    // ---------------------------------------------------------------------
    // Insets -> page geometry
    // ---------------------------------------------------------------------

    @Test
    public void pagesStartBelowTheStatusBar() {
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();

        assertEquals("top row of icons must clear the status bar",
                STATUS_BAR, page(0).getTop());
    }

    @Test
    public void pageHeightExcludesBothBarsAndTheDockStrip() {
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();

        assertEquals(H - STATUS_BAR - NAV_BAR - chromeHeight(),
                page(0).getHeight());
    }

    @Test
    public void withoutInsetsTheLayoutIsUnchangedFromTheLegacyGeometry() {
        // Devices/configurations that report no system bars must behave exactly as before.
        applyInsets(0, 0);
        measureAndLayout();

        assertEquals(0, page(0).getTop());
        assertEquals(H - chromeHeight(), page(0).getHeight());
    }

    @Test
    public void dockSitsBetweenTheGridAndTheGestureBar() {
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();

        View dock = dock();
        assertEquals("dock starts where the grid ends",
                page(0).getBottom(), dock.getTop());
        assertEquals("dock clears the gesture pill and the dot strip",
                H - NAV_BAR - dp(HomescreenView.DOT_AREA_DP), dock.getBottom());
    }

    @Test
    public void insetChangeRelaysOutTheGrid() {
        applyInsets(0, 0);
        measureAndLayout();
        assertEquals(0, page(0).getTop());

        // A later dispatch (first real dispatch after attach, rotation, IME) must take effect.
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();
        assertEquals(STATUS_BAR, page(0).getTop());
    }

    @Test
    public void measuredCellSizeAgreesWithTheLaidOutGrid() {
        // Widget span estimation reads this instead of re-deriving the arithmetic; if it drifts,
        // widgets get the wrong span after a grid-size change.
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();

        int[] cell = view.measuredCellSize();
        assertNotNull(cell);

        // Compare against what CellLayout actually laid out, not against a re-derivation of the
        // formula. This assertion used to be `W / store.columns()`, which silently went stale the
        // moment the grid gained horizontal page padding — a drift test that restates the
        // arithmetic it is meant to police cannot catch the arithmetic changing.
        CellLayout p = page(0);
        android.view.View probe = new android.view.View(p.getContext());
        p.addView(probe, new CellLayout.LayoutParams(1, 0, 1, 1));
        measureAndLayout();
        assertEquals("measuredCellSize must match the width CellLayout gives a 1x1 child",
                probe.getWidth(), cell[0]);
        assertEquals("a column-1 child must start one cell in from the page padding",
                CellLayout.pagePadPx(p.getContext()) + cell[0], probe.getLeft());
        p.removeView(probe);

        assertEquals(page(0).getHeight() / store.rows(), cell[1]);
    }

    // ---------------------------------------------------------------------
    // Dock pinning across pages
    // ---------------------------------------------------------------------

    @Test
    public void dockCancelsTheScrollSoItStaysOnScreen() {
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();

        view.scrollTo(W, 0); // paged to screen 2
        assertEquals("dock must translate with the scroll to stay put",
                (float) W, dock().getTranslationX(), 0.5f);

        view.scrollTo(2 * W, 0);
        assertEquals((float) 2 * W, dock().getTranslationX(), 0.5f);
    }

    @Test
    public void dockStaysPinnedMidFling() {
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();

        // Partway between pages the dock must track the scroll continuously, not snap.
        view.scrollTo(W / 3, 0);
        assertEquals((float) (W / 3), dock().getTranslationX(), 0.5f);
    }

    @Test
    public void pagesThemselvesDoNotCancelTheScroll() {
        // The pages are the thing that is supposed to move; only overlay children cancel.
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();

        view.scrollTo(W, 0);
        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (child instanceof CellLayout) {
                assertEquals("page " + i + " must scroll with the strip",
                        0f, child.getTranslationX(), 0.5f);
            }
        }
    }

    @Test
    public void everyOverlayChildIsPinnedNotJustTheDock() {
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();
        view.scrollTo(W, 0);

        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (child instanceof CellLayout) continue;
            assertEquals("overlay child " + i + " must cancel the scroll",
                    (float) W, child.getTranslationX(), 0.5f);
        }
    }

    @Test
    public void dockIsFullWidthLessItsMargins() {
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();

        View dock = dock();
        assertTrue("dock pill should span the screen minus its margins",
                dock.getWidth() > 0 && dock.getWidth() < W);
        assertEquals("margins symmetric", dock.getLeft(), W - dock.getRight());
    }

    @Test
    public void viewDoesNotConsumeInsetsSoSiblingsStillSeeThem() {
        WindowInsets in = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, STATUS_BAR, 0, NAV_BAR))
                .build();
        WindowInsets out = view.onApplyWindowInsets(in);

        Insets bars = out.getInsets(WindowInsets.Type.systemBars());
        assertEquals(STATUS_BAR, bars.top);
        assertEquals(NAV_BAR, bars.bottom);
    }

    @Test
    public void tallerStatusBarLeavesLessGridNotAnOverlap() {
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();
        int normal = page(0).getHeight();

        applyInsets(STATUS_BAR * 2, NAV_BAR);
        measureAndLayout();

        assertEquals(STATUS_BAR * 2, page(0).getTop());
        assertEquals(normal - STATUS_BAR, page(0).getHeight());
        assertTrue("grid must never extend past the dock",
                page(0).getBottom() <= dock().getTop());
    }

    @Test
    public void nothingIsLaidOutUnderEitherSystemBar() {
        applyInsets(STATUS_BAR, NAV_BAR);
        measureAndLayout();

        assertTrue(page(0).getTop() >= STATUS_BAR);
        assertTrue(dock().getBottom() <= H - NAV_BAR);
    }
}
