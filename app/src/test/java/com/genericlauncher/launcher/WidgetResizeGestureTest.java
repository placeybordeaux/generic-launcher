package com.genericlauncher.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * "Resize is still not working for widgets" — the frame appears, but dragging a handle changes
 * nothing.
 *
 * <p>The frame is a full-screen overlay child of {@link HomescreenView}, so every MOVE it needs
 * passes through {@code HomescreenView.onInterceptTouchEvent} first. That method treats any
 * predominantly-horizontal movement past {@code touchSlop} as a page swipe: it dismisses the frame
 * and takes the stream. A horizontal handle drag <em>is</em> predominantly horizontal, and one grid
 * cell (270 px at 1080/4) is an order of magnitude past slop, so the frame was always dismissed
 * before a single cell of delta could accumulate. Same "who owns this gesture" question H9 answered
 * for the widget drag path.
 *
 * <p>These tests therefore drive {@code view.dispatchTouchEvent} — the real dispatch path — rather
 * than calling {@code ResizeFrameView.onTouchEvent} directly, because "who receives the MOVE" is
 * the whole bug.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetResizeGestureTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int WIDGET_ID = 11;
    private static final String PROVIDER = "com.example.resizable/.Provider";

    private ActivityController<Activity> controller;
    private Activity   activity;
    private FrameLayout root;
    private HomeScreenStore store;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        root       = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        for (File f : activity.getFilesDir().listFiles()) {
            if (f.getName().startsWith("homescreen.json")) f.delete();
        }
        store = HomeScreenStore.get(activity);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    private static MotionEvent event(int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    /**
     * A HomescreenView showing one 2x2 freely-resizable widget at cell (0,0) of page 0, laid out
     * at Pixel-9a size with the resize frame already open.
     */
    private HomescreenView viewWithOpenResizeFrame(int resizeMode) {
        return viewWithOpenResizeFrame(resizeMode, 0);
    }

    private HomescreenView viewWithOpenResizeFrame(int resizeMode, int cellX) {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = resizeMode;
        // Leave min*ResizeWidth/Height at 0 so the computed minimum span is 1 cell in both axes.
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);

        store.addItem(HomeItem.widget(0, cellX, 0, 2, 2, WIDGET_ID, PROVIDER));

        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 2048));
        root.addView(view, W, H);
        view.refresh();
        layout(view);

        view.showResizeFrame(item());
        assertTrue("test setup: the frame must be open before the drag", view.isResizeFrameOpen());
        layout(view); // the overlay is added by showResizeFrame, so lay it out too
        return view;
    }

    /**
     * Like {@link #viewWithOpenResizeFrame(int)}, but the widget lives on {@code page} and the view
     * has been swiped there, so {@code scrollX} is a page's worth of pixels rather than 0.
     *
     * <p>The page is reached by a real swipe rather than {@code scrollTo}, for the reason
     * {@code DragToRemoveTest} gives: {@code currentPage} has to agree with the scroll, and only
     * the gesture path keeps the two in step.
     */
    private HomescreenView viewWithOpenResizeFrameOnPage(int resizeMode, int page) {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = resizeMode;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);

        // Something on page 0 so the strip has a page to swipe away from.
        store.addItem(HomeItem.app(0, 0, 0, "com.example.page0"));
        store.addItem(HomeItem.widget(page, 0, 0, 2, 2, WIDGET_ID, PROVIDER));

        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 2048));
        root.addView(view, W, H);
        view.refresh();
        layout(view);

        for (int i = 0; i < page; i++) {
            view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 900, 1200));
            view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 200, 1200));
            view.dispatchTouchEvent(event(MotionEvent.ACTION_UP,   200, 1200));
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
            view.computeScroll();
        }
        layout(view); // idling the looper re-lays the view out at Robolectric's window size
        assertEquals("test setup: the swipe should have reached page " + page,
                page, view.getCurrentPage());
        assertEquals("test setup: and the strip must actually be scrolled there — a zero scroll"
                        + " is the very condition this fixture exists to escape",
                page * W, view.getScrollX());

        view.showResizeFrame(item());
        assertTrue("test setup: the frame must be open before the drag", view.isResizeFrameOpen());
        layout(view);
        return view;
    }

    private static void layout(HomescreenView view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    /** The one stored widget. Re-read each time: the store mutates it in place. */
    private HomeItem item() {
        for (HomeItem i : store.getItems()) {
            if (i.getType() == HomeItem.Type.WIDGET) return i;
        }
        fail("test setup: the widget vanished from the store");
        return null;
    }

    private static int cellW(HomescreenView view) {
        // Must match production: the grid has horizontal page padding, so cells are NOT
        // pageWidth/cols. Dividing naively here left the fixture quietly disagreeing with the
        // view it was driving — forgiving 22dp edge strips hid it; a precise corner box does not.
        return CellLayout.cellWidth(view.getPage(0).getWidth(), 4,
                CellLayout.pagePadPx(view.getContext()));
    }
    private static int cellH(HomescreenView view) { return view.getPage(0).getHeight() / 5; }

    /**
     * X of the frame's one handle: the bottom-right corner grip. There are no edge handles any
     * more, so every resize drag starts here. Pass cellX + spanX.
     */
    private static float gripX(HomescreenView view, int rightCol) {
        return CellLayout.pagePadPx(view.getContext()) + rightCol * cellW(view);
    }
    /** Y of the corner grip. Pass cellY + spanY. */
    private static float gripY(HomescreenView view, int bottomRow) {
        return bottomRow * cellH(view);
    }

    /** Drags a handle from {@code (x, y)} by {@code (dx, dy)} in one MOVE plus an UP. */
    private static void drag(HomescreenView view, float x, float y, float dx, float dy) {
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x + dx, y + dy));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP,   x + dx, y + dy));
    }

    // ---------------------------------------------------------------------
    // The reported bug
    // ---------------------------------------------------------------------

    @Test
    public void draggingTheRightHandleOneCellGrowsTheSpan() {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_BOTH);
        int cw = cellW(view), ch = cellH(view);

        // Right handle sits centred on the widget's right edge: x = spanX*cellW, y = midpoint.
        drag(view, gripX(view, 2), gripY(view, 2), cw, 0);

        assertEquals("dragging the right handle one cell right must widen the widget",
                3, item().getSpanX());
        assertEquals("a horizontal resize must not change the height", 2, item().getSpanY());
        assertEquals("growing to the right must not move the widget", 0, item().getCellX());
    }

    @Test
    public void draggingTheRightHandleInwardShrinksTheSpan() {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_BOTH);
        int cw = cellW(view), ch = cellH(view);

        drag(view, gripX(view, 2), gripY(view, 2), -cw, 0);

        assertEquals(1, item().getSpanX());
        assertEquals(0, item().getCellX());
    }

    /**
     * There is no left or top handle any more: the single corner grip is anchored to the widget's
     * top-left, so a resize can change the span but never move the origin. Replaces
     * draggingTheLeftHandleMovesTheOriginAndGrowsTheSpan, which exercised a capability the
     * one-grip model deliberately removes.
     */
    @Test
    public void aResizeNeverMovesTheWidgetsOrigin() {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_BOTH, 1);
        int cw = cellW(view), ch = cellH(view);
        int cellX0 = item().getCellX(), cellY0 = item().getCellY();

        drag(view, gripX(view, 3), gripY(view, 2), -2f * cw, -2f * ch);

        assertEquals("cellX must not move", cellX0, item().getCellX());
        assertEquals("cellY must not move", cellY0, item().getCellY());
    }

    /**
     * The drawn frame must track the finger 1:1, including across a commit.
     *
     * Reported on device as "when I expand the size it moves twice as fast as I drag it": the
     * committed cells were counted twice, once in the frame's committed edge (computeBounds reads
     * the item's current span) and again in the raw finger delta added on top. Asserting on
     * liveResidual alone cannot catch this — the bug was in how two individually correct values
     * were combined — so this drives a real drag and reads the rectangle the frame would draw.
     */
    @Test
    public void theDrawnFrameTracksTheFingerAcrossACommit() {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_BOTH);
        int cw = cellW(view), ch = cellH(view);
        float gx = gripX(view, 2), gy = gripY(view, 2);

        float[] before = view.resizeFrameRectForTest();
        assertNotNull("test setup: the frame must be open", before);

        // Far enough to commit a whole cell (0.66 threshold) and then some.
        float travel = 1.4f * cw;
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, gx, gy));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, gx + travel, gy));

        float[] during = view.resizeFrameRectForTest();
        assertNotNull(during);
        float moved = during[2] - before[2];   // right edge travel
        assertEquals("the frame's right edge must move exactly as far as the finger,"
                        + " not twice as far",
                travel, moved, 2f);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, gx + travel, gy));
    }

    /** One corner drag must resize both axes at once — that is why a single grip suffices. */
    @Test
    public void oneCornerDragResizesBothAxes() {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_BOTH);
        int cw = cellW(view), ch = cellH(view);
        assertEquals(2, item().getSpanX());
        assertEquals(2, item().getSpanY());

        drag(view, gripX(view, 2), gripY(view, 2), cw, ch);

        assertEquals("width must grow", 3, item().getSpanX());
        assertEquals("height must grow in the same gesture", 3, item().getSpanY());
    }

    @Test
    public void draggingTheBottomHandleOneCellGrowsTheSpan() {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_BOTH);
        int cw = cellW(view), ch = cellH(view);

        // Bottom handle: x = horizontal midpoint of the widget, y = spanY*cellH.
        drag(view, gripX(view, 2), gripY(view, 2), 0, ch);

        assertEquals(3, item().getSpanY());
        assertEquals("a vertical resize must not change the width", 2, item().getSpanX());
    }

    // ---------------------------------------------------------------------
    // The resized span has to survive a restart
    // ---------------------------------------------------------------------

    @Test
    public void aResizedSpanIsPersisted() throws Exception {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_BOTH);
        int cw = cellW(view), ch = cellH(view);

        drag(view, gripX(view, 2), gripY(view, 2), cw, 0);
        store.flush();

        File f = new File(activity.getFilesDir(), "homescreen.json");
        assertTrue("the store must have written the layout", f.exists());
        JSONArray items = new JSONObject(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                .getJSONArray("items");
        assertEquals(1, items.length());
        assertEquals("the new span must be on disk, not just in memory",
                3, items.getJSONObject(0).getInt("spanX"));
    }

    // ---------------------------------------------------------------------
    // The page pager must still win when the gesture is not a handle drag
    // ---------------------------------------------------------------------

    /**
     * The dismiss-on-swipe behaviour is deliberate, but the region it applies to shrank when the
     * frame learned to reposition: a drag that starts <em>inside</em> the outline now moves the
     * widget (see {@link WidgetRepositionInResizeFrameTest}), so only a swipe that starts off the
     * frame belongs to the pager.
     *
     * <p>This starts in the slack margin just outside the outline — near enough that the frame's
     * own tap-outside dismissal on DOWN does not fire, so the swipe itself is what closes it.
     */
    @Test
    public void aHorizontalSwipeStartingOffTheFrameStillDismissesIt() {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_BOTH);
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        int cw = cellW(view), ch = cellH(view);

        // 14dp past the widget's right edge: outside the 7dp outset the frame is drawn at, inside
        // the 22dp slack, and a long way from the corner grip's box.
        float x = gripX(view, 2) + dp(view, 14), y = 0.4f * ch;
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x - slop * 6, y));

        assertFalse("a page swipe must still dismiss the resize frame", view.isResizeFrameOpen());
    }

    private static int dp(HomescreenView view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    @Test
    public void aHandleDragDoesNotDismissTheFrame() {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_BOTH);
        int cw = cellW(view), ch = cellH(view);

        // Raw dispatch rather than drag(), so the grip coordinates are spelled out here too.
        float gx = gripX(view, 2), gy = gripY(view, 2);
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, gx, gy));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, gx + cw, gy));

        assertTrue("the frame must survive its own resize drag", view.isResizeFrameOpen());
        assertEquals("and the page must not have scrolled", 0, view.getScrollX());
    }

    // ---------------------------------------------------------------------
    // Provider constraints
    // ---------------------------------------------------------------------

    @Test
    public void aVerticalOnlyWidgetIgnoresAHorizontalHandleDrag() {
        HomescreenView view = viewWithOpenResizeFrame(AppWidgetProviderInfo.RESIZE_VERTICAL);
        int cw = cellW(view), ch = cellH(view);

        drag(view, gripX(view, 2), gripY(view, 2), cw, 0);

        assertEquals("RESIZE_VERTICAL draws no side handles, so there is nothing to grab",
                2, item().getSpanX());
    }

    // ---------------------------------------------------------------------
    // Not on page 0
    // ---------------------------------------------------------------------

    /**
     * Every other test in this class keeps its widget on page 0, where {@code scrollX == 0} and
     * the view's screen-local touch coordinates coincide with its content coordinates. At the
     * origin, two coordinate spaces are one coordinate space, and no test there can tell whether
     * the frame's hit test and drawing apply the scroll offset or forget it. That is the fixture
     * that hid the drag-to-remove bug: the Remove pill drew in the right place and its hit test
     * missed by exactly {@code scrollX} on every page but the first.
     *
     * <p>{@code ResizeFrameView.computeBounds} subtracts {@code scrollX}. This is the one test in
     * which removing that subtraction changes anything: the frame lands a page to the right, the
     * DOWN misses it, and the drag becomes a page swipe instead of a resize.
     */
    @Test
    public void theGripStillResizesOnAPageOtherThanTheFirst() {
        HomescreenView view = viewWithOpenResizeFrameOnPage(AppWidgetProviderInfo.RESIZE_BOTH, 1);
        int cw = cellW(view);

        drag(view, gripX(view, 2), gripY(view, 2), cw, 0);

        assertEquals("the grip is where the frame is drawn, on the page the user is looking at",
                3, item().getSpanX());
        assertEquals("and the widget stays on its page", 1, item().getScreen());
    }
}
