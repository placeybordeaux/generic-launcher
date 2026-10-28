package com.genericlauncher.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

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
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * A widget must land on the page it was added from.
 *
 * <p>{@code finalizeWidget}'s free-cell scan started at screen 0 unconditionally, so adding a
 * widget while looking at page 2 put it on page 0 whenever page 0 still had room — which it
 * normally does. The user was left staring at an unchanged page.
 *
 * <p>The page is driven here by a real swipe through {@code dispatchTouchEvent} rather than by
 * poking {@code currentPage}, because the thing under test is precisely that the page the *view*
 * is showing is the page the placement uses. The pick is entered through
 * {@link MainActivity#beginPendingWidget(int)} — the state half of {@code onPickWidget}, split
 * from the one {@code AppWidgetManager.bindAppWidgetIdIfAllowed} call that cannot succeed under
 * the suite — so the capture being tested is production's own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetAddPagePlacementTest {

    private static final String PROVIDER  = "com.example.clock/.ClockProvider";
    private static final int    WIDGET_ID = 41;

    private static final int W = 1080;
    private static final int H = 2424;

    private ActivityController<MainActivity> controller;
    private MainActivity    activity;
    private HomeScreenStore store;
    private HomescreenView  view;
    private AppWidgetProviderInfo provider;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        // Don't let first-run seeding decide what is on the pages under test.
        Settings.setHomeSeeded(ctx, true);

        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
        new File(ctx.getFilesDir(), "homescreen.json.tmp").delete();

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
        store      = HomeScreenStore.get(activity);

        // minWidth/minHeight are left at 0 here on purpose: most of this class is about *which
        // page* the widget lands on, and a 1x1 widget keeps that question clean. The span tests
        // below set them, because with both at 0 the whole span path is unfalsifiable — see
        // aWidgetTakesTheSpanItsProviderAsksFor.
        provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    /** Three pages with a single app apiece, laid out and ready to swipe. */
    private void threePages() {
        for (int screen = 0; screen < 3; screen++) {
            store.addItem(HomeItem.app(screen, 0, 0, "com.example.app" + screen));
        }
        view = awaitHomescreenView();
        assertNotNull("MainActivity should have a HomescreenView on the home mode", view);
        view.refresh();
        layoutPass();
    }

    private void layoutPass() {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    /**
     * Waits for MainActivity to build its HomescreenView, rather than assuming it already has.
     *
     * <p>{@code AppLoader} runs on an {@code AsyncTask} worker and posts its result back to the
     * main looper, and {@code renderMode()} only builds the HomescreenView once the priority load
     * lands. So the view is not guaranteed to exist the instant {@code buildActivity().setup()}
     * returns: that is a real background thread, and how much of it has run by then depends on
     * the machine.
     *
     * <p>It raced. This class passed on two developer machines and on the Linux build host — full
     * suite, {@code --no-daemon}, 903/0 — and failed <em>4 of its 10</em> tests on the 2-core
     * GitHub runner, every one of them on the {@code assertNotNull} below. All ten tests call
     * {@link #threePages()}, so a real breakage would have failed all ten; a partial failure is
     * what identifies it as timing rather than logic. It surfaced during the targetSdk 36 bump,
     * but nothing in that change created it — the race was always here, and a slower, more
     * contended machine is all it ever needed.
     *
     * <p>Waiting for the view to <em>exist</em> is deliberate, and is the same lesson as the
     * macrobenchmark readiness wait in {@code Target.kt}: an absence-based wait cannot tell
     * "finished" from "never started", but waiting for a thing to appear can.
     */
    private HomescreenView awaitHomescreenView() {
        for (int attempt = 0; attempt < 200; attempt++) {
            ShadowLooper.idleMainLooper();
            HomescreenView found = findHomescreenView(activity.findViewById(android.R.id.content));
            if (found != null) return found;
            try {
                // The worker is a real thread; yield to it rather than spinning the looper.
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private static HomescreenView findHomescreenView(View root) {
        if (root instanceof HomescreenView) return (HomescreenView) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                HomescreenView found = findHomescreenView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static MotionEvent event(int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    /** Swipes right-to-left once and lets the snap settle. */
    private void swipeToNextPage() {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 900, 1200));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 200, 1200));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP,   200, 1200));
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
        view.computeScroll();
        layoutPass();
    }

    private void swipeTo(int page) {
        while (view.getCurrentPage() < page) {
            int before = view.getCurrentPage();
            swipeToNextPage();
            assertTrue("test setup: the swipe did not advance the page past " + before,
                    view.getCurrentPage() > before);
        }
        assertEquals("test setup: the view should be showing page " + page,
                page, view.getCurrentPage());
    }

    /** Runs the whole add flow bar the one system call the suite cannot make. */
    private void addWidget() {
        activity.beginPendingWidget(WIDGET_ID);
        activity.finalizeWidget();
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

    // -------------------------------------------------------------------------
    // The bug
    // -------------------------------------------------------------------------

    @Test
    public void aWidgetAddedFromPageTwoLandsOnPageTwo() {
        threePages();
        swipeTo(2);

        addWidget();

        assertEquals("the widget must land on the page it was added from",
                2, placedWidget().screen);
    }

    @Test
    public void aWidgetAddedFromPageOneLandsOnPageOne() {
        threePages();
        swipeTo(1);

        addWidget();

        assertEquals(1, placedWidget().screen);
    }

    @Test
    public void aWidgetAddedFromTheMainPageStillLandsOnTheMainPage() {
        threePages();
        assertEquals("test setup: should start on page 0", 0, view.getCurrentPage());

        addWidget();

        assertEquals(0, placedWidget().screen);
    }

    // -------------------------------------------------------------------------
    // Overflow
    // -------------------------------------------------------------------------

    @Test
    public void aFullPageSpillsForwardOntoTheNextPageNotBackOntoPageZero() {
        threePages();
        swipeTo(2);
        fillScreen(2);

        addWidget();

        assertEquals("a full page must spill onto the next page along, where the user can see it,"
                        + " not back onto an earlier page",
                3, placedWidget().screen);
    }

    @Test
    public void aFullLastPageWrapsRoundRatherThanLosingTheWidget() {
        threePages();
        swipeTo(2);
        for (int screen = 2; screen < HomeScreenStore.MAX_SCREENS; screen++) fillScreen(screen);

        addWidget();

        assertEquals("with every page from the current one to the last full, placement wraps",
                0, placedWidget().screen);
    }

    // -------------------------------------------------------------------------
    // The span
    // -------------------------------------------------------------------------

    /**
     * Every other test in this class binds a provider whose {@code minWidth}/{@code minHeight} are
     * {@code 0}, so every widget they add is 1x1. That is a degenerate fixture for the span half of
     * {@code finalizeWidget}: with both spans equal to 1, passing {@code (1, 1)} to the free-cell
     * scan instead of the computed span, transposing {@code spanX}/{@code spanY} in the stored
     * item, or rounding the minimum down instead of up all produce exactly the same widget. None
     * of the page-placement tests can fail for any of those.
     *
     * <p>This test asks for a 3x1 widget through the provider's real pixel minimum. 3 and 1 differ,
     * so a transposition shows; and {@code 2 * cellW + 1} is a hair over two cells, so rounding
     * instead of ceiling shows too. The cell size is read from the view, which is where
     * {@code computeWidgetSpan} reads it.
     */
    @Test
    public void aWidgetTakesTheSpanItsProviderAsksFor() {
        threePages();
        askForAThreeByOneWidget();

        addWidget();

        HomeItem widget = placedWidget();
        assertEquals("a minimum a hair over two cells wide needs three columns", 3, widget.getSpanX());
        assertEquals("and one row", 1, widget.getSpanY());
    }

    /**
     * A page with free cells but no free cell *large enough* must spill, exactly as a full page
     * does. The page-placement tests above cannot express this: their 1x1 widget fits any hole,
     * so "full" and "no room for this widget" are the same condition for them.
     */
    @Test
    public void aPageWithOnlyNarrowHolesSpillsForwardLikeAFullOne() {
        threePages();
        swipeTo(2);
        fillScreenLeavingTheLastColumnFree(2);
        askForAThreeByOneWidget();
        assertNotNull("test setup: page 2 must still have a 1x1 hole",
                store.firstFreeCell(2, 1, 1));

        addWidget();

        assertEquals("a one-wide hole cannot take a three-wide widget; it spills to the next page",
                3, placedWidget().screen);
    }

    /** Sets the provider minimum so {@code computeWidgetSpan} yields 3x1 on the laid-out grid. */
    private void askForAThreeByOneWidget() {
        int[] cell = view.measuredCellSize();
        assertNotNull("test setup: the view must be laid out before the span can be predicted", cell);
        assertTrue("test setup: a three-wide widget needs a grid at least two columns wide",
                store.columns() >= 2);
        provider.minWidth  = 2 * cell[0] + 1;  // px, as the framework reports it — NOT dp
        provider.minHeight = 1;
    }

    // -------------------------------------------------------------------------
    // Showing the user where it went
    // -------------------------------------------------------------------------

    @Test
    public void spillingOntoTheNextPageScrollsTheUserToIt() {
        threePages();
        swipeTo(2);
        fillScreen(2);

        addWidget();
        settle();

        assertEquals("the widget went to page 3, so page 3 is what the user must end up on",
                3, view.getCurrentPage());
        assertEquals("and the strip must actually be scrolled there, not merely believe it is",
                3 * W, view.getScrollX());
    }

    @Test
    public void wrappingRoundScrollsTheUserBackToPageZero() {
        threePages();
        swipeTo(2);
        for (int screen = 2; screen < HomeScreenStore.MAX_SCREENS; screen++) fillScreen(screen);

        addWidget();
        settle();

        assertEquals(0, view.getCurrentPage());
        assertEquals(0, view.getScrollX());
    }

    @Test
    public void landingOnThePageAlreadyShowingDoesNotMoveTheStrip() {
        threePages();
        swipeTo(2);

        addWidget();
        settle();

        assertEquals("the widget landed on the page in front of the user; nothing should move",
                2, view.getCurrentPage());
        assertEquals(2 * W, view.getScrollX());
    }

    /**
     * The trip has to animate, not teleport — showing the user the pages go by is the point.
     *
     * <p>Read at the instant of the add, before any clock advance: an animated scroll has already
     * committed the page but has not moved the strip, while a {@code scrollTo} would be at the
     * destination. Swapping {@code startScroll} for {@code scrollTo} fails the second assertion.
     */
    @Test
    public void theTripToTheLandingPageIsAnimatedRatherThanAJump() {
        threePages();
        swipeTo(2);
        fillScreen(2);

        addWidget();

        assertEquals("the page is committed immediately", 3, view.getCurrentPage());
        assertEquals("but the strip is still where the user was — it travels, it does not jump",
                2 * W, view.getScrollX());

        settle();
        assertEquals(3 * W, view.getScrollX());
    }

    /**
     * Runs the page animation to completion.
     *
     * <p>The trailing {@code layoutPass} is not decoration: idling the looper lets Robolectric run
     * a real traversal, which lays the view out at its own default window size rather than the
     * {@link #W}×{@link #H} this test measured it at. Without re-imposing the geometry, every
     * scroll assertion would be against a 320px page.
     */
    private void settle() {
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        view.computeScroll();
        layoutPass();
    }

    /** Fills {@code screen} except its last column, leaving holes exactly one cell wide. */
    private void fillScreenLeavingTheLastColumnFree(int screen) {
        for (int y = 0; y < store.rows(); y++) {
            for (int x = 0; x < store.columns() - 1; x++) {
                store.addItem(HomeItem.app(screen, x, y, "com.example.filler" + screen + x + y));
            }
        }
    }

    private void fillScreen(int screen) {
        for (int y = 0; y < store.rows(); y++) {
            for (int x = 0; x < store.columns(); x++) {
                store.addItem(HomeItem.app(screen, x, y, "com.example.filler" + screen + x + y));
            }
        }
    }

    // -------------------------------------------------------------------------
    // The page has to survive the trip through the bind / configure activities
    // -------------------------------------------------------------------------

    @Test
    public void theAddPageSurvivesARecreationDuringTheConfigureActivity() {
        threePages();
        swipeTo(2);
        activity.beginPendingWidget(WIDGET_ID);

        // What a rotation inside the configure activity does: the state is saved, the activity is
        // rebuilt, and the rebuilt HomescreenView is back on page 0.
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        assertEquals("test setup: the pending page should have been saved",
                2, state.getInt("pendingWidgetPage", -1));

        ActivityController<MainActivity> rebuilt =
                Robolectric.buildActivity(MainActivity.class).setup(state);
        try {
            MainActivity restored = rebuilt.get();
            HomescreenView restoredView =
                    findHomescreenView(restored.findViewById(android.R.id.content));
            assertNotNull(restoredView);
            assertEquals("test setup: the rebuilt view is back on page 0",
                    0, restoredView.getCurrentPage());

            restored.finalizeWidget();

            assertEquals("the page the user added from must survive the configure round trip",
                    2, placedWidget().screen);
            assertEquals("and the rebuilt view must be taken to it rather than left on page 0",
                    2, restoredView.getCurrentPage());
        } finally {
            rebuilt.close();
        }
    }
}
