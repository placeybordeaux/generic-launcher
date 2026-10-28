package com.genericlauncher.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPopupMenu;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * A long-press that lands on a widget must not open the home screen's background menu.
 *
 * <p>The bug: {@link HomescreenView}'s own {@code GestureDetector} is fed from
 * {@code onTouchEvent}, and a widget whose RemoteViews root consumes no ACTION_DOWN — a clock, a
 * weather tile, the padding around the Google search pill — lets the whole touch stream fall
 * through to exactly there. The detector therefore fired {@code onLongPress} for a press that was
 * plainly *on* a widget, popping "Add widget / Change wallpaper / Settings" over the top of the
 * widget's own long-press-to-drag and stealing focus mid-gesture.
 *
 * <p>It was a race, not a straight duplicate: when the wrapper's drag does start,
 * {@code isDragging} suppresses the menu and the bug hides. It shows whenever the drag does not —
 * the H9 cancellation got there first, or {@code startDragAndDrop} was refused. So these tests
 * pin the ownership decision itself ({@link HomescreenView#widgetWrapperAt}) as well as the
 * end-to-end behaviour, and the end-to-end case asserts the drag is *not* running so a pass
 * cannot come from the wrong guard.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetLongPressBackgroundMenuTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int WIDGET_ID = 11;
    /** Comfortably past the detector's tap-timeout + long-press-timeout (100 + 500 ms). */
    private static final long PAST_TIMEOUT_MS = 2000;

    private ActivityController<Activity> controller;
    private Activity   activity;
    private FrameLayout root;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        root       = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();

        // Static across the JVM — a menu left over from another test would read as a failure here.
        ShadowPopupMenu.setLatestPopupMenu(null);
    }

    @After
    public void tearDown() {
        ShadowPopupMenu.setLatestPopupMenu(null);
        controller.pause().stop().destroy();
    }

    private static MotionEvent event(int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    private static void idlePastTimeout() {
        ShadowLooper.idleMainLooper(PAST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------------
    // Fixture: one 2x2 non-interactive widget at cell (0,0) on page 0
    // ---------------------------------------------------------------------

    private HomescreenView viewWithWidget() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = new ComponentName("com.example.clock", ".ClockProvider");
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_NONE;
        ShadowAppWidgetManager shadow =
                Shadows.shadowOf(AppWidgetManager.getInstance(activity));
        shadow.addBoundWidget(WIDGET_ID, provider);

        HomeScreenStore store = HomeScreenStore.get(activity);
        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID,
                "com.example.clock/.ClockProvider"));

        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 2048));
        root.addView(view, W, H);
        view.refresh();
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
        return view;
    }

    private static HomescreenView.WidgetWrapper wrapperIn(HomescreenView view) {
        CellLayout page = view.getPage(0);
        assertNotNull("test setup: page 0 must exist", page);
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child instanceof HomescreenView.WidgetWrapper) {
                return (HomescreenView.WidgetWrapper) child;
            }
        }
        fail("test setup: no WidgetWrapper was built for the stored widget");
        return null;
    }

    /** Centre of the widget's footprint, in HomescreenView coordinates. */
    private static float[] overWidget(HomescreenView view) {
        HomescreenView.WidgetWrapper wrapper = wrapperIn(view);
        CellLayout page = view.getPage(0);
        return new float[]{
                page.getLeft() + (wrapper.getLeft() + wrapper.getRight()) / 2f,
                page.getTop()  + (wrapper.getTop()  + wrapper.getBottom()) / 2f };
    }

    /** Centre of a page-0 cell that no child view covers, in HomescreenView coordinates. */
    private static float[] overEmptyWallpaper(HomescreenView view) {
        CellLayout page = view.getPage(0);
        int[] cell = view.measuredCellSize();
        assertNotNull("test setup: the view must be laid out before hit-testing it", cell);
        // Bottom-right first: the widget sits at the top-left and the search placeholder, when it
        // appears at all, takes the top row.
        for (int row = HomeScreenStore.DEFAULT_ROWS - 1; row >= 0; row--) {
            for (int col = HomeScreenStore.DEFAULT_COLS - 1; col >= 0; col--) {
                float px = col * cell[0] + cell[0] / 2f;
                float py = row * cell[1] + cell[1] / 2f;
                if (childCovering(page, px, py) == null) {
                    return new float[]{ page.getLeft() + px, page.getTop() + py };
                }
            }
        }
        fail("test setup: page 0 has no free cell to press");
        return null;
    }

    private static View childCovering(CellLayout page, float px, float py) {
        for (int i = 0; i < page.getChildCount(); i++) {
            View c = page.getChildAt(i);
            if (px >= c.getLeft() && px < c.getRight()
                    && py >= c.getTop() && py < c.getBottom()) return c;
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // The ownership question itself
    // ---------------------------------------------------------------------

    @Test
    public void aPointOverAWidgetResolvesToItsWrapper() {
        HomescreenView view = viewWithWidget();
        float[] p = overWidget(view);

        assertSame(wrapperIn(view), view.widgetWrapperAt(p[0], p[1]));
    }

    @Test
    public void aPointOverEmptyWallpaperResolvesToNoWrapper() {
        HomescreenView view = viewWithWidget();
        float[] p = overEmptyWallpaper(view);

        assertNull(view.widgetWrapperAt(p[0], p[1]));
    }

    @Test
    public void aPointAboveThePageStripResolvesToNoWrapper() {
        // The status-bar inset strip is outside every page, and the widget starts at the page top.
        HomescreenView view = viewWithWidget();
        CellLayout page = view.getPage(0);
        assertTrue("test setup: this only means anything with a non-zero top inset",
                page.getTop() >= 0);

        assertNull(view.widgetWrapperAt(overWidget(view)[0], page.getTop() - 1f));
    }

    @Test
    public void theHitTestFollowsTheScrollOffset() {
        // Touch coordinates reaching onTouchEvent are pre-scroll; ViewGroup adds mScrollX back
        // when it hit-tests children. Without the same correction every press would be a page out
        // the moment the user scrolled.
        HomescreenView view = viewWithWidget();
        float[] p = overWidget(view);

        view.scrollTo(W, 0);

        assertNull("the widget has scrolled off to the left — nothing is under that point now",
                view.widgetWrapperAt(p[0], p[1]));
        assertSame("the same widget, now one page-width further left on screen",
                wrapperIn(view), view.widgetWrapperAt(p[0] - W, p[1]));
    }

    // ---------------------------------------------------------------------
    // End to end
    // ---------------------------------------------------------------------

    @Test
    public void aLongPressOnAWidgetDoesNotOpenTheBackgroundMenu() {
        HomescreenView view = viewWithWidget();
        float[] p = overWidget(view);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));
        assertTrue("test setup: the widget must have taken the DOWN's long-press",
                wrapperIn(view).hasPendingLongPress());

        // The device repro: the widget's own drag never starts (H9 got there first, or the system
        // refused the drag), so isDragging stays false and nothing but the new ownership check
        // stands between the detector and the menu.
        wrapperIn(view).cancelPendingLongPress();

        idlePastTimeout();

        assertNotEquals("test guard: a running drag would suppress the menu for the wrong reason",
                View.INVISIBLE, wrapperIn(view).getVisibility());
        assertNull("a long-press on a widget belongs to the widget, not to the wallpaper",
                ShadowPopupMenu.getLatestPopupMenu());
    }

    @Test
    public void aLongPressOnEmptyWallpaperStillOpensTheBackgroundMenu() {
        HomescreenView view = viewWithWidget();
        float[] p = overEmptyWallpaper(view);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));
        idlePastTimeout();

        android.widget.PopupMenu menu = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull("the background menu is the whole point of the background long-press", menu);
        assertEquals(3, menu.getMenu().size());
        assertEquals("Add widget",       menu.getMenu().getItem(0).getTitle().toString());
        assertEquals("Change wallpaper", menu.getMenu().getItem(1).getTitle().toString());
        assertEquals("Settings",         menu.getMenu().getItem(2).getTitle().toString());
    }

    @Test
    public void aSecondPressOnEmptyWallpaperIsNotPoisonedByTheFirstOneBeingOnAWidget() {
        // downOnWidget is per-gesture state; a stale true would silently kill the background menu
        // for the rest of the session.
        HomescreenView view = viewWithWidget();

        float[] onWidget = overWidget(view);
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, onWidget[0], onWidget[1]));
        wrapperIn(view).cancelPendingLongPress();
        idlePastTimeout(); // the detector's long-press fires here and must be swallowed
        assertNull("the widget press must not have opened the menu",
                ShadowPopupMenu.getLatestPopupMenu());
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, onWidget[0], onWidget[1]));

        float[] onEmpty = overEmptyWallpaper(view);
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, onEmpty[0], onEmpty[1]));
        idlePastTimeout();

        assertNotNull("the next press on wallpaper must still open the menu",
                ShadowPopupMenu.getLatestPopupMenu());
    }
}
