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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * The resize frame repositions as well as resizes: dragging the corner grip changes the span,
 * dragging anywhere inside the outline slides the widget to another cell.
 *
 * <p>Which of the two a stream is gets decided once, on ACTION_DOWN, from where the finger landed
 * — the same rule the pager and the widget long-press follow, because by the first MOVE a grip
 * drag, a body drag and a page swipe are indistinguishable. That makes "who received the MOVE"
 * the interesting question again, so these tests drive {@code view.dispatchTouchEvent} rather
 * than calling {@code ResizeFrameView.onTouchEvent}: a reposition that the pager steals looks
 * exactly like a reposition that was never implemented.
 *
 * <p>Note the fixture's {@link #moveDrag} sends two MOVEs, not one. A body drag only becomes a
 * move after it passes touchSlop, and it re-anchors there so the frame does not jump a slop's
 * worth the instant it starts — so the MOVE that crosses slop carries no displacement of its own
 * and a one-MOVE drag would assert nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetRepositionInResizeFrameTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int WIDGET_ID = 11;
    private static final String PROVIDER = "com.example.resizable/.Provider";

    private ActivityController<Activity> controller;
    private Activity activity;
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

    /** A 2x2 freely-resizable widget at (0,0) of page 0, frame already open. */
    private HomescreenView viewWithOpenResizeFrame() {
        return viewWithOpenResizeFrame(-1, -1);
    }

    /**
     * As above, plus an app icon at {@code (neighbourCellX, neighbourCellY)} when it is >= 0.
     * A widget alone on a page can be dropped anywhere, so the occupancy clamp is only ever
     * exercised with something else on the grid — the same fixture gap that let a resize guard
     * refuse every grow on a real home screen while the suite stayed green.
     */
    private HomescreenView viewWithOpenResizeFrame(int neighbourCellX, int neighbourCellY) {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);

        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, PROVIDER));
        if (neighbourCellX >= 0) {
            store.addItem(HomeItem.app(0, neighbourCellX, neighbourCellY, "com.example.neighbor"));
        }

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
        // pageWidth/cols.
        return CellLayout.cellWidth(view.getPage(0).getWidth(), 4,
                CellLayout.pagePadPx(view.getContext()));
    }
    private static int cellH(HomescreenView view) { return view.getPage(0).getHeight() / 5; }

    /** A point well inside the widget's box and nowhere near the bottom-right grip. */
    private static float bodyX(HomescreenView view) {
        return CellLayout.pagePadPx(view.getContext()) + cellW(view) * 0.5f;
    }
    private static float bodyY(HomescreenView view) { return cellH(view) * 0.5f; }

    private int slop() { return ViewConfiguration.get(activity).getScaledTouchSlop(); }

    /**
     * Drags the widget's body from {@code (x, y)} by {@code (dx, dy)}.
     *
     * <p>The first MOVE only crosses touchSlop, which is what arms the move and re-anchors it;
     * the second carries the displacement being asserted on. A real drag streams MOVEs at 60Hz
     * and gets both for free.
     */
    private void moveDrag(HomescreenView view, float x, float y, float dx, float dy) {
        float nudge = slop() + 1f;
        float nx = x + (dx == 0 ? 0 : Math.signum(dx) * nudge);
        float ny = y + (dy == 0 ? 0 : Math.signum(dy) * nudge);
        if (dx == 0 && dy == 0) ny = y + nudge;   // still has to arm somehow
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, nx, ny));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, nx + dx, ny + dy));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP,   nx + dx, ny + dy));
    }

    // ---------------------------------------------------------------------
    // The requested behaviour
    // ---------------------------------------------------------------------

    @Test
    public void draggingInsideTheFrameMovesTheWidgetOneCell() {
        HomescreenView view = viewWithOpenResizeFrame();
        int cw = cellW(view);

        moveDrag(view, bodyX(view), bodyY(view), cw, 0);

        assertEquals("a drag inside the outline must slide the widget across", 1, item().getCellX());
        assertEquals("a horizontal reposition must not change the row", 0, item().getCellY());
    }

    @Test
    public void aRepositionNeverChangesTheSpan() {
        HomescreenView view = viewWithOpenResizeFrame();
        int cw = cellW(view), ch = cellH(view);

        moveDrag(view, bodyX(view), bodyY(view), cw, ch);

        assertEquals("moving is not resizing", 2, item().getSpanX());
        assertEquals("moving is not resizing", 2, item().getSpanY());
    }

    @Test
    public void oneBodyDragMovesBothAxes() {
        HomescreenView view = viewWithOpenResizeFrame();
        int cw = cellW(view), ch = cellH(view);

        moveDrag(view, bodyX(view), bodyY(view), cw, ch);

        assertEquals(1, item().getCellX());
        assertEquals(1, item().getCellY());
    }

    /**
     * The frame is the affordance for both gestures, so it has to survive the one that moves the
     * widget just as it survives the one that resizes it — otherwise a reposition is a single
     * shot and the user has to long-press again to nudge it once more.
     */
    @Test
    public void aRepositionDoesNotDismissTheFrameOrPageTheScreen() {
        HomescreenView view = viewWithOpenResizeFrame();
        int cw = cellW(view);

        moveDrag(view, bodyX(view), bodyY(view), cw, 0);

        assertTrue("the frame must survive its own move drag", view.isResizeFrameOpen());
        assertEquals("and the page must not have scrolled", 0, view.getScrollX());
    }

    /**
     * A body drag is predominantly horizontal and a whole cell is an order of magnitude past
     * touchSlop, so onInterceptTouchEvent's page-swipe test matches it every time. Unless the
     * frame claims the stream at DOWN, the pager dismisses the frame and scrolls instead — the
     * exact failure the corner grip already had.
     */
    @Test
    public void theFrameClaimsTheStreamBeforeThePagerCanTakeIt() {
        HomescreenView view = viewWithOpenResizeFrame();
        float x = bodyX(view), y = bodyY(view);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x + slop() * 6, y));

        assertTrue("a drag inside the frame belongs to the frame, not the pager",
                view.isResizeFrameOpen());
    }

    // ---------------------------------------------------------------------
    // A tap is not a move
    // ---------------------------------------------------------------------

    @Test
    public void aTapInsideTheFrameLeavesTheWidgetWhereItIs() {
        HomescreenView view = viewWithOpenResizeFrame();
        float x = bodyX(view), y = bodyY(view);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x + slop() - 1, y));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP,   x + slop() - 1, y));

        assertEquals("tremble under slop must not walk the widget off its cell",
                0, item().getCellX());
        assertEquals(0, item().getCellY());
        assertTrue("and a tap inside the frame must not close it", view.isResizeFrameOpen());
    }

    // ---------------------------------------------------------------------
    // Grid and neighbour limits
    // ---------------------------------------------------------------------

    @Test
    public void aRepositionStopsAtTheGridEdge() {
        HomescreenView view = viewWithOpenResizeFrame();
        int cw = cellW(view), ch = cellH(view);

        // Far further than the 4x5 grid can take a 2x2 widget: max cell is (2, 3).
        moveDrag(view, bodyX(view), bodyY(view), 6f * cw, 6f * ch);

        assertEquals("cellX + spanX may not exceed the column count", 2, item().getCellX());
        assertEquals("cellY + spanY may not exceed the row count",    3, item().getCellY());
    }

    @Test
    public void aRepositionCannotBeDraggedOffTheTopLeft() {
        HomescreenView view = viewWithOpenResizeFrame();
        int cw = cellW(view), ch = cellH(view);

        moveDrag(view, bodyX(view), bodyY(view), -3f * cw, -3f * ch);

        assertEquals(0, item().getCellX());
        assertEquals(0, item().getCellY());
    }

    /**
     * The neighbour case, kept for the reason {@code ResizeOnAPopulatedPageTest} exists: on an
     * empty page every occupancy check passes, so a guard that refuses outright looks correct
     * here and refuses almost everything on a real home screen.
     */
    @Test
    public void aRepositionStopsAgainstANeighbourRatherThanDoingNothing() {
        // Icon at column 3, row 0. A 2x2 widget moving right can reach column 1 (cols 1-2) but
        // not column 2 (cols 2-3, which the icon occupies).
        HomescreenView view = viewWithOpenResizeFrame(3, 0);
        int cw = cellW(view);

        moveDrag(view, bodyX(view), bodyY(view), 2f * cw, 0);

        assertEquals("must stop against the neighbor, not refuse the whole drag",
                1, item().getCellX());
    }

    @Test
    public void aBlockedRepositionLeavesTheWidgetWhereItWas() {
        // Icon at column 2, row 0: the widget cannot move right at all.
        HomescreenView view = viewWithOpenResizeFrame(2, 0);
        int cw = cellW(view);

        moveDrag(view, bodyX(view), bodyY(view), cw, 0);

        assertEquals(0, item().getCellX());
        assertEquals(0, item().getCellY());
    }

    /**
     * A blocked diagonal degrades into the axis that is free rather than into nothing: the clamp
     * retracts X first, so the vertical half of the drag still lands.
     */
    @Test
    public void aBlockedDiagonalStillAppliesTheAxisThatFits() {
        // Icon at (2,1). The diagonal target (1,1) spans columns 1-2 and rows 1-2, so the icon
        // sits in it; (0,1) — the same drag with its sideways half retracted — is clear.
        HomescreenView view = viewWithOpenResizeFrame(2, 1);
        int cw = cellW(view), ch = cellH(view);

        moveDrag(view, bodyX(view), bodyY(view), cw, ch);

        assertEquals("the sideways half cannot land", 0, item().getCellX());
        assertEquals("but the downward half can", 1, item().getCellY());
    }

    // ---------------------------------------------------------------------
    // The drawn frame
    // ---------------------------------------------------------------------

    /**
     * The frame must track the finger 1:1 across a commit, the same way the resize frame does.
     *
     * <p>Resize hit this as "when I expand the size it moves twice as fast as I drag it": the
     * committed cells live in the frame's own rect (computeBounds reads the item's position), so
     * adding the raw finger delta on top counts them twice. A move has a second way to get it
     * wrong that resize does not — when the occupancy clamp refuses, the committed cell count and
     * the cells the widget actually took disagree, and subtracting the former drags the frame off
     * the finger by exactly the refused distance.
     */
    @Test
    public void theDrawnFrameTracksTheFingerAcrossACommit() {
        HomescreenView view = viewWithOpenResizeFrame();
        int cw = cellW(view);
        float x = bodyX(view), y = bodyY(view);
        float nudge = slop() + 1f;

        float[] before = view.resizeFrameRectForTest();
        assertNotNull("test setup: the frame must be open", before);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x + nudge, y)); // arms and re-anchors

        float[] armed = view.resizeFrameRectForTest();
        assertEquals("re-anchoring at the slop crossing means the frame has not jumped yet",
                before[0], armed[0], 2f);

        // Past the 0.66-cell threshold, so a whole cell commits under it.
        float travel = 1.4f * cw;
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x + nudge + travel, y));

        float[] during = view.resizeFrameRectForTest();
        assertEquals("the frame must travel exactly as far as the finger, not twice as far",
                travel, during[0] - before[0], 2f);
        assertEquals("a move slides the whole outline, so its width is unchanged",
                before[2] - before[0], during[2] - during[0], 2f);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x + nudge + travel, y));

        // Release no longer teleports: it eases onto the committed cell over SETTLE_MS (see
        // ResizeFrameFeedbackTest). So the outline is still under the finger at the instant of
        // UP, and it is the end of that animation that has to land on the cell.
        assertEquals("the frame must not have jumped the moment the finger lifted",
                travel, view.resizeFrameRectForTest()[0] - before[0], 2f);
        view.finishResizeSettleForTest();

        float[] after = view.resizeFrameRectForTest();
        assertEquals("and the settle must end on the committed cell",
                before[0] + cw, after[0], 2f);
    }

    /**
     * A blocked move must not drag the frame away from the finger. Without measuring the residual
     * against the cells the widget ACTUALLY took, the refused cell is subtracted anyway and the
     * outline lags the finger by a whole cell.
     */
    @Test
    public void theDrawnFrameStillTracksTheFingerWhenTheMoveIsRefused() {
        HomescreenView view = viewWithOpenResizeFrame(2, 0);  // right-hand move is blocked
        int cw = cellW(view);
        float x = bodyX(view), y = bodyY(view);
        float nudge = slop() + 1f;

        float[] before = view.resizeFrameRectForTest();
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x + nudge, y));
        float travel = 1.4f * cw;
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x + nudge + travel, y));

        float[] during = view.resizeFrameRectForTest();
        assertEquals("the widget did not move, but the frame must still follow the finger",
                travel, during[0] - before[0], 2f);
        assertEquals("test setup: the move really was refused", 0, item().getCellX());

        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, x + nudge + travel, y));
        // A refused move settles all the way back — the committed cell count is zero here, so the
        // whole 1.4 cells of travel is residual and the animation has the furthest to run.
        view.finishResizeSettleForTest();
        float[] after = view.resizeFrameRectForTest();
        assertEquals("and it settles back onto the widget on release", before[0], after[0], 2f);
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    @Test
    public void aRepositionedCellIsPersisted() throws Exception {
        HomescreenView view = viewWithOpenResizeFrame();
        int cw = cellW(view);

        moveDrag(view, bodyX(view), bodyY(view), cw, 0);
        store.flush();

        File f = new File(activity.getFilesDir(), "homescreen.json");
        assertTrue("the store must have written the layout", f.exists());
        JSONArray items = new JSONObject(
                new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))
                .getJSONArray("items");
        JSONObject widget = null;
        for (int i = 0; i < items.length(); i++) {
            if (items.getJSONObject(i).has("appWidgetId")) widget = items.getJSONObject(i);
        }
        assertNotNull("the widget must be on disk", widget);
        assertEquals("the new cell must be on disk, not just in memory", 1, widget.getInt("cellX"));
        assertEquals("and the span must be untouched", 2, widget.getInt("spanX"));
    }
}
