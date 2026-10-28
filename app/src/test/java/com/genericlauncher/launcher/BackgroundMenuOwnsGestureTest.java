package com.genericlauncher.launcher;

import android.app.Activity;
import android.os.SystemClock;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPopupMenu;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Once the long-press background menu has opened, the rest of that touch stream belongs to it.
 *
 * <p>The bug: the menu is a PopupMenu in its own window, and the finger that opened it is still
 * down on {@link HomescreenView}, which keeps receiving the stream. Sliding upward from there
 * was still read as the open-drawer swipe (and sideways as a page drag), so after dismissing
 * the menu the user found the drawer open underneath it — or both on screen at once. The
 * detector's long-press is now the third owner of a home-screen long-press, next to the widget
 * drag timeout and {@code downOnWidget}, and it swallows what follows.
 *
 * <p>Same fixture as {@link WidgetLongPressBackgroundMenuTest}: a real DOWN, the looper idled
 * past the detector's timeout so the menu opens through production code, then real MOVE/UP.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BackgroundMenuOwnsGestureTest {

    private static final int W = 1080;
    private static final int H = 2424;
    /** Comfortably past the detector's tap-timeout + long-press-timeout (100 + 500 ms). */
    private static final long PAST_TIMEOUT_MS = 2000;

    private ActivityController<Activity> controller;
    private Activity        activity;
    private FrameLayout     root;
    private HomescreenView  view;
    private final AtomicInteger swipeUps = new AtomicInteger();

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        root       = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();
        ShadowPopupMenu.setLatestPopupMenu(null);

        HomeScreenStore store = HomeScreenStore.get(activity);
        // Two pages, so a sideways drag has somewhere to page to.
        store.addItem(HomeItem.app(0, 0, 0, "com.example.a"));
        store.addItem(HomeItem.app(1, 0, 0, "com.example.b"));

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setOnSwipeUpListener(swipeUps::incrementAndGet);
        root.addView(view, W, H);
        view.refresh();
        layout();
    }

    @After
    public void tearDown() {
        ShadowPopupMenu.setLatestPopupMenu(null);
        controller.pause().stop().destroy();
    }

    private void layout() {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    private static MotionEvent event(int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    /** Somewhere on page 0 with nothing under it: the app sits at (0,0), so row 3, column 2. */
    private float[] emptyWallpaper() {
        int[] cell = view.measuredCellSize();
        assertNotNull("test setup: the view must be laid out", cell);
        return new float[]{
                view.getPage(0).getLeft() + CellLayout.pagePadPx(activity) + 2.5f * cell[0],
                view.getPage(0).getTop() + 3.5f * cell[1]};
    }

    /** Presses on empty wallpaper and waits for the long-press menu to open. */
    private float[] openMenuByLongPress() {
        float[] p = emptyWallpaper();
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));
        ShadowLooper.idleMainLooper(PAST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        layout(); // idling re-lays the view out at Robolectric's window size
        assertNotNull("test setup: the long-press must have opened the background menu",
                ShadowPopupMenu.getLatestPopupMenu());
        return p;
    }

    // ---------------------------------------------------------------------
    // The reported bug
    // ---------------------------------------------------------------------

    @Test
    public void slidingUpAfterTheMenuOpenedDoesNotOpenTheDrawer() {
        float[] p = openMenuByLongPress();

        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0], p[1] - 350));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0], p[1] - 700));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP,   p[0], p[1] - 700));

        assertEquals("the swipe-up listener must not fire for a gesture the menu owns",
                0, swipeUps.get());
    }

    @Test
    public void slidingSidewaysAfterTheMenuOpenedDoesNotPage() {
        float[] p = openMenuByLongPress();

        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0] - 400, p[1]));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0] - 800, p[1]));
        assertEquals("the strip must not scroll under the menu", 0, view.getScrollX());
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, p[0] - 800, p[1]));
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
        view.computeScroll();

        assertEquals(0, view.getCurrentPage());
    }

    /** The ownership is per gesture: the next press on wallpaper is an ordinary gesture again. */
    @Test
    public void theNextGestureIsNotPoisonedByTheMenuHavingOwnedTheLastOne() {
        float[] p = openMenuByLongPress();
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, p[0], p[1]));

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0], p[1] - 350));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0], p[1] - 700));

        assertEquals("a fresh up-swipe must still open the drawer", 1, swipeUps.get());
    }

    /** Positive control: the same slide with no long-press first is the open-drawer swipe. */
    @Test
    public void anUpSwipeWithoutALongPressStillOpensTheDrawer() {
        float[] p = emptyWallpaper();

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0], p[1] - 350));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0], p[1] - 700));

        assertEquals(1, swipeUps.get());
    }
}
