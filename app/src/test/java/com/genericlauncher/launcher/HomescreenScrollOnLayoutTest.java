package com.genericlauncher.launcher;

import android.app.Activity;
import android.os.SystemClock;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.DragEventBuilder;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * H10 — {@code onLayout} must not abort the scroller just because some descendant asked for a
 * layout pass.
 *
 * <p>{@code View.layout} calls {@code onLayout} whenever {@code PFLAG_LAYOUT_REQUIRED} is set,
 * which it is for every ancestor in a descendant-initiated pass — and an {@code AppWidgetHostView}
 * applying new RemoteViews starts one every time a clock widget ticks. The unconditional
 * {@code abortAnimation() + scrollTo(currentPage * w)} therefore killed flings, undid the finger's
 * own scroll while drag-paging, and made {@code flipEdgePage} teleport instead of animate.
 *
 * <p>The "a widget ticked" event is modelled the way the framework produces it: a
 * {@code requestLayout()} on a page followed by a measure/layout pass at unchanged bounds.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class HomescreenScrollOnLayoutTest {

    private static final int W = 1080;
    private static final int H = 2424;

    private ActivityController<Activity> controller;
    private Activity        activity;
    private FrameLayout     root;
    private HomeScreenStore store;
    private HomescreenView  view;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        root       = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();
        store = HomeScreenStore.get(activity);

        // Three pages, so there is somewhere to scroll to.
        Map<String, AppInfo> apps = new HashMap<>();
        for (int screen = 0; screen < 3; screen++) {
            String pkg = "com.example.app" + screen;
            store.addItem(HomeItem.app(screen, 0, 0, pkg));
            apps.put(pkg, new AppInfo("App" + screen, pkg, null));
        }

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setAppMap(apps);
        root.addView(view, W, H);
        view.refresh();
        layoutPass();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    private void layoutPass() {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    /** What a self-updating widget does: ask for a layout from inside the tree, same bounds. */
    private void widgetTicks() {
        view.getPage(0).requestLayout();
        layoutPass();
    }

    private static MotionEvent event(int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    // ---------------------------------------------------------------------
    // The rule itself
    // ---------------------------------------------------------------------

    @Test
    public void aRealBoundsChangeAlwaysSnaps() {
        assertTrue(HomescreenView.shouldSnapScrollOnLayout(true, false, true, true));
    }

    @Test
    public void anIdleViewSnaps() {
        assertTrue(HomescreenView.shouldSnapScrollOnLayout(false, true, false, false));
    }

    @Test
    public void aRunningSnapAnimationIsNotAborted() {
        assertFalse(HomescreenView.shouldSnapScrollOnLayout(false, false, false, false));
    }

    @Test
    public void anItemDragOwnsTheScroll() {
        // flipEdgePage requestLayouts to place the new page, then starts the scroll the layout
        // pass would otherwise abort — trigger C.
        assertFalse(HomescreenView.shouldSnapScrollOnLayout(false, false, true, false));
    }

    @Test
    public void aFingerOwnsTheScroll() {
        assertFalse(HomescreenView.shouldSnapScrollOnLayout(false, true, false, true));
    }

    // ---------------------------------------------------------------------
    // Trigger A — a widget update mid-fling
    // ---------------------------------------------------------------------

    @Test
    public void aWidgetUpdateDoesNotKillTheSnapAnimation() {
        // Drag most of the way to page 1 and lift: snapToPage starts an animated scroll.
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 900, 1200));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 200, 1200));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 200, 1200));

        int midFlight = view.getScrollX();
        assertTrue("test setup: the snap should still have distance to run",
                midFlight != view.getCurrentPage() * W);

        widgetTicks();

        assertEquals("the in-flight snap must survive a descendant's layout request",
                midFlight, view.getScrollX());
    }

    // ---------------------------------------------------------------------
    // Trigger B — a widget update while the finger is dragging pages
    // ---------------------------------------------------------------------

    @Test
    public void aWidgetUpdateDoesNotYankThePagesFromUnderTheFinger() {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 900, 1200));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 500, 1200));

        int underFinger = view.getScrollX();
        assertEquals("test setup: the finger should have scrolled the strip", 400, underFinger);

        widgetTicks();

        assertEquals("the finger's scroll must not be undone", underFinger, view.getScrollX());
    }

    @Test
    public void theScrollIsReleasedAgainWhenTheFingerLifts() {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 900, 1200));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 500, 1200));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 500, 1200));
        // Let the snap animation finish, then a plain relayout must re-pin the page.
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
        view.computeScroll();

        view.scrollTo(137, 0); // something nudged the offset
        widgetTicks();

        assertEquals("an idle view must still snap back to its page",
                view.getCurrentPage() * W, view.getScrollX());
    }

    // ---------------------------------------------------------------------
    // Trigger C — creating a page during an edge-drag
    // ---------------------------------------------------------------------

    @Test
    public void anEdgeDragPageFlipAnimatesInsteadOfTeleporting() {
        startIncomingDrag();
        // Hover the right edge long enough for the edge-flip timer to fire.
        view.dispatchDragEvent(dragEvent(DragEvent.ACTION_DRAG_LOCATION, W - 5, 1200));
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);

        assertEquals("test setup: the edge flip should have moved to the next page",
                1, view.getCurrentPage());
        // flipEdgePage started a scroll; the requestLayout it issued must not have aborted it.
        assertEquals("the page flip must animate, not teleport", 0, view.getScrollX());

        layoutPass(); // the layout pass flipEdgePage's requestLayout scheduled
        assertEquals("and the layout pass must leave the animation alone",
                0, view.getScrollX());
    }

    private void startIncomingDrag() {
        view.dispatchDragEvent(dragEvent(DragEvent.ACTION_DRAG_STARTED, 100, 1200));
        ShadowLooper.idleMainLooper(); // drains the deferred drawer-dismiss post
    }

    private static DragEvent dragEvent(int action, float x, float y) {
        return DragEventBuilder.newBuilder()
                .setAction(action)
                .setX(x)
                .setY(y)
                .setLocalState(new DrawerDragState("com.example.dragged"))
                .build();
    }
}
