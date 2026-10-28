package com.genericlauncher.launcher;

import android.content.Context;
import android.graphics.Insets;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * The drawer toolbar's inset padding.
 *
 * <p>The layout used to hard-code {@code paddingTop="40dp"} — a guess that comes up short on a
 * Pixel 9a, leaving the search box tucked under the punch-hole status bar. It is now driven by
 * the real inset, and the listener must return the insets unconsumed so {@link HomescreenView}
 * (which lays its own grid out from them) still sees them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ToolbarInsetTest {

    private static final int STATUS_BAR = 130;
    private static final int NAV_BAR    = 63;
    private static final int BASE_PAD_DP = 8; // breathing room below the status bar

    private Context ctx;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        HomeScreenStore.resetForTesting();
        new java.io.File(ctx.getFilesDir(), "homescreen.json").delete();

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
    }

    private int dp(int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }

    private WindowInsets bars(int top, int bottom) {
        return new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, top, 0, bottom))
                .build();
    }

    private View root()    { return activity.findViewById(R.id.root_layout); }
    private LinearLayout toolbar() { return activity.findViewById(R.id.toolbar_row); }

    @Test
    public void toolbarDropsBelowTheStatusBar() {
        root().dispatchApplyWindowInsets(bars(STATUS_BAR, NAV_BAR));

        assertEquals(STATUS_BAR + dp(BASE_PAD_DP), toolbar().getPaddingTop());
    }

    @Test
    public void aTallerStatusBarPushesTheToolbarFurtherDown() {
        root().dispatchApplyWindowInsets(bars(STATUS_BAR, NAV_BAR));
        int normal = toolbar().getPaddingTop();

        root().dispatchApplyWindowInsets(bars(STATUS_BAR * 2, NAV_BAR));

        assertEquals(normal + STATUS_BAR, toolbar().getPaddingTop());
    }

    @Test
    public void repeatedDispatchesDoNotAccumulatePadding() {
        // The listener runs on every inset dispatch; padding must be set, not added.
        root().dispatchApplyWindowInsets(bars(STATUS_BAR, NAV_BAR));
        root().dispatchApplyWindowInsets(bars(STATUS_BAR, NAV_BAR));
        root().dispatchApplyWindowInsets(bars(STATUS_BAR, NAV_BAR));

        assertEquals(STATUS_BAR + dp(BASE_PAD_DP), toolbar().getPaddingTop());
    }

    @Test
    public void noStatusBarLeavesOnlyTheBasePadding() {
        root().dispatchApplyWindowInsets(bars(0, 0));

        assertEquals(dp(BASE_PAD_DP), toolbar().getPaddingTop());
    }

    @Test
    public void horizontalAndBottomToolbarPaddingAreLeftAlone() {
        LinearLayout toolbar = toolbar();
        int left   = toolbar.getPaddingLeft();
        int right  = toolbar.getPaddingRight();
        int bottom = toolbar.getPaddingBottom();

        root().dispatchApplyWindowInsets(bars(STATUS_BAR, NAV_BAR));

        assertEquals(left,   toolbar.getPaddingLeft());
        assertEquals(right,  toolbar.getPaddingRight());
        assertEquals(bottom, toolbar.getPaddingBottom());
    }

    @Test
    public void insetsAreReturnedUnconsumed() {
        // HomescreenView is a sibling: consuming here would put the grid back under the status bar.
        WindowInsets out = root().dispatchApplyWindowInsets(bars(STATUS_BAR, NAV_BAR));

        Insets remaining = out.getInsets(WindowInsets.Type.systemBars());
        assertEquals(STATUS_BAR, remaining.top);
        assertEquals(NAV_BAR, remaining.bottom);
    }

    @Test
    public void theLayoutItselfCarriesNoHardcodedTopPadding() {
        // Inflated raw, with no activity and so no inset listener: the XML must contribute
        // nothing. The old 40dp constant lived here and would be a second, wrong source of truth.
        // Inflated from the activity (its theme is needed for the toolbar's vector icons) but
        // never attached, so no inset listener is ever installed on this copy.
        View raw = android.view.LayoutInflater.from(activity).inflate(R.layout.activity_main, null);
        assertEquals(0, raw.findViewById(R.id.toolbar_row).getPaddingTop());
    }

    @Test
    public void aZeroInsetDispatchLeavesOnlyTheBasePadding() {
        // Activity attach dispatches insets before the window has real bars; the toolbar must
        // land on the base padding rather than any leftover constant.
        assertEquals(dp(BASE_PAD_DP), toolbar().getPaddingTop());
    }
}
