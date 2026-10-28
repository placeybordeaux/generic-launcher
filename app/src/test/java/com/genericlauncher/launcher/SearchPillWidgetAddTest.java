package com.genericlauncher.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * A widget added through the "Tap to add Google search" pill retires the pill for good.
 *
 * <p>The bug: the pill's only conditions are "top row free" and "not dismissed", and only its
 * ✕ set the dismissed flag. Adding the widget through it left the flag alone, so the moment the
 * top row emptied again — the search bar moved to another page, say — the pill was back,
 * inviting a second search bar (QA F7). {@code finalizeWidget} now sets the same flag the ✕
 * does when the pending widget came from the pill, and only on success: cancelling the bind
 * dialog still keeps the pill.
 *
 * <p>Which pick is the pill's has to survive the bind dialog — another process's activity —
 * so it rides in the saved instance state next to {@code pendingWidgetPage}, and the last test
 * drives that round trip the way {@link WidgetAddPagePlacementTest} does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SearchPillWidgetAddTest {

    private static final String GOOGLE     = "com.google.android.googlequicksearchbox";
    private static final String SEARCH_BAR = GOOGLE + ".SearchWidgetProvider";
    private static final int    WIDGET_ID  = 41;
    private static final String PILL_LABEL = "Tap to add Google search";

    private static final int W = 1080;
    private static final int H = 2424;

    private Context ctx;
    private ActivityController<MainActivity> controller;
    private MainActivity    activity;
    private HomeScreenStore store;
    private AppWidgetProviderInfo provider;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        Settings.setHomeSeeded(ctx, true);
        Settings.setSearchPlaceholderDismissed(ctx, false);

        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
        new File(ctx.getFilesDir(), "homescreen.json.tmp").delete();

        provider = new AppWidgetProviderInfo();
        provider.provider   = new ComponentName(GOOGLE, SEARCH_BAR);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_HORIZONTAL;
        // In pixels, as the framework hands them over: 2.5 cells of any grid this suite lays
        // out, so the provider asks for 3 of 4 columns — the shipped off-centre placement.
        // Left at 0 the span path is unfalsifiable (see WidgetAddPagePlacementTest).
        provider.minWidth  = 200;
        provider.minHeight = 1;
        Shadows.shadowOf(AppWidgetManager.getInstance(ctx)).addInstalledProvider(provider);
        Shadows.shadowOf(AppWidgetManager.getInstance(ctx)).addBoundWidget(WIDGET_ID, provider);

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
        store      = HomeScreenStore.get(activity);
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    private HomeItem placedWidget() {
        for (HomeItem item : store.getItems()) {
            if (item.type == HomeItem.Type.WIDGET && item.getAppWidgetId() == WIDGET_ID) {
                return item;
            }
        }
        fail("the widget was never added to the store");
        return null;
    }

    // ---------------------------------------------------------------------
    // The reported bug
    // ---------------------------------------------------------------------

    @Test
    public void addingTheWidgetThroughThePillDismissesThePillForGood() {
        activity.beginPendingWidget(WIDGET_ID, true);
        activity.finalizeWidget();

        placedWidget();
        assertTrue("the pill must be retired once its widget has been added",
                Settings.isSearchPlaceholderDismissed(ctx));
    }

    @Test
    public void addingAWidgetThroughThePickerLeavesThePillAlone() {
        activity.beginPendingWidget(WIDGET_ID);
        activity.finalizeWidget();

        placedWidget();
        assertFalse("the picker is not the pill; an unrelated widget must not dismiss it",
                Settings.isSearchPlaceholderDismissed(ctx));
    }

    @Test
    public void aCancelledPillAddKeepsThePill() {
        activity.beginPendingWidget(WIDGET_ID, true);
        // What onActivityResult does when the bind dialog comes back RESULT_CANCELED.
        activity.onActivityResult(MainActivity.RC_WIDGET_BIND, android.app.Activity.RESULT_CANCELED,
                null);

        assertFalse("cancelling the bind must not retire the pill",
                Settings.isSearchPlaceholderDismissed(ctx));
        assertTrue("and nothing may have been added", store.getItems().stream()
                .noneMatch(i -> i.type == HomeItem.Type.WIDGET));
    }

    /** The pick's origin has to survive a recreation inside the bind dialog. */
    @Test
    public void thePillsPickSurvivesARecreationDuringTheBindDialog() {
        activity.beginPendingWidget(WIDGET_ID, true);

        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        ActivityController<MainActivity> rebuilt =
                Robolectric.buildActivity(MainActivity.class).setup(state);
        try {
            rebuilt.get().finalizeWidget();

            placedWidget();
            assertTrue("the rebuilt activity must still know the pick was the pill's",
                    Settings.isSearchPlaceholderDismissed(ctx));
        } finally {
            rebuilt.close();
        }
    }

    // ---------------------------------------------------------------------
    // The widget takes the row the pill occupied (QA F8)
    // ---------------------------------------------------------------------

    @Test
    public void thePillsWidgetFillsTheTopRowWhenItIsFree() {
        activity.beginPendingWidget(WIDGET_ID, true);
        activity.finalizeWidget();

        HomeItem w = placedWidget();
        assertEquals("the pill spans every column, so its widget should too",
                store.columns(), w.getSpanX());
        assertEquals(0, w.getScreen());
        assertEquals(0, w.getCellX());
        assertEquals(0, w.getCellY());
        assertEquals(1, w.getSpanY());
    }

    @Test
    public void thePickersWidgetKeepsTheSpanItsProviderAsksFor() {
        activity.beginPendingWidget(WIDGET_ID);
        activity.finalizeWidget();

        assertEquals("the picker did not promise a row; the provider's minimum stands",
                3, placedWidget().getSpanX());
    }

    /**
     * With the top row taken the pill is drawn on the first row that is free end to end, so the
     * widget fills that row instead. Both read {@code HomeScreenStore.firstFreeFullRow}, which is
     * the only reason the pill and its widget cannot land on different rows.
     */
    @Test
    public void thePillsWidgetFillsWhicheverRowThePillWasDrawnOn() {
        // Something in the top row: the pill moved down to row 1 rather than disappearing.
        store.addItem(HomeItem.app(0, 3, 0, "com.example.app"));

        activity.beginPendingWidget(WIDGET_ID, true);
        activity.finalizeWidget();

        HomeItem w = placedWidget();
        assertEquals(store.columns(), w.getSpanX());
        assertEquals(0, w.getCellX());
        assertEquals(1, w.getCellY());
    }

    /** No row free end to end: nothing was promised a whole row, so the provider's span stands. */
    @Test
    public void thePillsWidgetIsNotWidenedWhenNoRowIsFreeEndToEnd() {
        for (int y = 0; y < store.rows(); y++) {
            store.addItem(HomeItem.app(0, 3, y, "com.example.app" + y));
        }

        activity.beginPendingWidget(WIDGET_ID, true);
        activity.finalizeWidget();

        HomeItem w = placedWidget();
        assertEquals(3, w.getSpanX());
        assertEquals("placement otherwise as it was: the first cell the provider's span fits",
                0, w.getCellX());
        assertEquals(0, w.getCellY());
    }

    @Test
    public void aProviderThatForbidsHorizontalResizeIsNotWidened() {
        AppWidgetProviderInfo fixed = new AppWidgetProviderInfo();
        fixed.provider   = provider.provider;
        fixed.resizeMode = AppWidgetProviderInfo.RESIZE_NONE;
        fixed.minWidth   = provider.minWidth;
        fixed.minHeight  = provider.minHeight;
        Shadows.shadowOf(AppWidgetManager.getInstance(ctx)).addBoundWidget(WIDGET_ID, fixed);

        activity.beginPendingWidget(WIDGET_ID, true);
        activity.finalizeWidget();

        assertEquals("resizeMode is a constraint, not a suggestion", 3, placedWidget().getSpanX());
    }

    // ---------------------------------------------------------------------
    // The pill announces itself
    // ---------------------------------------------------------------------

    /** The pill has to reach the listener through the search-specific method, not the picker's. */
    @Test
    public void tappingThePillReportsASearchPickNotAPickerPick() {
        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        AtomicReference<String> route = new AtomicReference<>();
        view.setOnAddWidgetListener(new HomescreenView.OnAddWidgetListener() {
            @Override public void onPickWidget(AppWidgetProviderInfo p) { route.set("picker"); }
            @Override public void onPickSearchWidget(AppWidgetProviderInfo p) { route.set("pill"); }
        });
        view.refresh();
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);

        View pill = findPill(view.getPage(0));
        assertNotNull("test setup: the pill should be on page 0", pill);
        assertTrue(pill.performClick());
        ShadowLooper.idleMainLooper();

        assertEquals("pill", route.get());
    }

    private static View findPill(ViewGroup page) {
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child instanceof ViewGroup && hasLabel((ViewGroup) child)) return child;
        }
        return null;
    }

    private static boolean hasLabel(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView
                    && PILL_LABEL.contentEquals(((TextView) child).getText())) {
                return true;
            }
        }
        return false;
    }
}
