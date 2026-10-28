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

import java.io.File;

import static org.junit.Assert.*;

/**
 * Every other resize test puts a single widget on an otherwise empty page, so
 * {@code applyResize}'s final {@code isAreaFreeExcluding} guard always passes. A real home screen
 * is populated.
 *
 * <p>These tests add a neighbour and ask what a handle drag does then.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ResizeOnAPopulatedPageTest {

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

    /** 2×2 widget at (0,0), with {@code neighbourCellX} optionally holding an app icon. */
    private HomescreenView view(int neighbourCellX, int neighbourCellY) {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);

        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, PROVIDER));
        if (neighbourCellX >= 0) {
            store.addItem(HomeItem.app(0, neighbourCellX, neighbourCellY, "com.example.neighbor"));
        }

        HomescreenView v = new HomescreenView(activity);
        v.setStore(store);
        v.setWidgetHost(new AppWidgetHost(activity, 2048));
        root.addView(v, W, H);
        v.refresh();
        layout(v);
        v.showResizeFrame(widget());
        assertTrue("test setup: frame must be open", v.isResizeFrameOpen());
        layout(v);
        return v;
    }

    private static void layout(HomescreenView v) {
        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, W, H);
    }

    private HomeItem widget() {
        for (HomeItem i : store.getItems()) {
            if (i.getType() == HomeItem.Type.WIDGET) return i;
        }
        fail("test setup: widget vanished");
        return null;
    }

    private static int cellW(HomescreenView v) {
        // Must match production: the grid has horizontal page padding, so cells are NOT
        // pageWidth/cols. Dividing naively here left the fixture quietly disagreeing with the
        // view it was driving — forgiving 22dp edge strips hid it; a precise corner box does not.
        return CellLayout.cellWidth(v.getPage(0).getWidth(), 4,
                CellLayout.pagePadPx(v.getContext()));
    }
    private static int cellH(HomescreenView v) { return v.getPage(0).getHeight() / 5; }

    /**
     * X of the frame's one handle: the bottom-right corner grip. There are no edge handles any
     * more, so every resize drag starts here. Pass cellX + spanX.
     */
    private static float gripX(HomescreenView v, int rightCol) {
        return CellLayout.pagePadPx(v.getContext()) + rightCol * cellW(v);
    }
    /** Y of the corner grip. Pass cellY + spanY. */
    private static float gripY(HomescreenView v, int bottomRow) {
        return bottomRow * cellH(v);
    }

    private static void drag(HomescreenView v, float x, float y, float dx, float dy) {
        v.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, x, y));
        v.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, x + dx, y + dy));
        v.dispatchTouchEvent(event(MotionEvent.ACTION_UP,   x + dx, y + dy));
    }

    /** Control: with the page empty, growing works. This is what every other test asserts. */
    @Test
    public void growingIntoEmptySpaceWorks() {
        HomescreenView v = view(-1, -1);
        int cw = cellW(v), ch = cellH(v);
        drag(v, gripX(v, 2), gripY(v, 2), cw, 0);
        assertEquals(3, widget().getSpanX());
    }

    /**
     * The device bug: an icon in the next column used to block the grow entirely, so the handle
     * looked dead. There is genuinely no room here, so the span must stay — but it must stay
     * because the cell is taken, not because the guard bailed out.
     */
    @Test
    public void growingIntoAnOccupiedCellIsBlockedButNotByABailout() {
        HomescreenView v = view(2, 0);
        int cw = cellW(v), ch = cellH(v);
        drag(v, gripX(v, 2), gripY(v, 2), cw, 0);
        assertEquals("column 2 is taken, so 2 is the largest that fits",
                2, widget().getSpanX());
    }

    /**
     * The fix: with the neighbour two columns out, dragging three cells right must grow as far as
     * it can (to span 3) rather than refusing because span 4 would not fit. This is the assertion
     * that fails on the old code.
     */
    @Test
    public void growingClampsToTheLargestSpanThatFits() {
        HomescreenView v = view(3, 0);
        int cw = cellW(v), ch = cellH(v);
        drag(v, gripX(v, 2), gripY(v, 2), 2f * cw, 0);
        assertEquals("must stop against the neighbor, not do nothing",
                3, widget().getSpanX());
        assertEquals("clamping a grow must not move the widget", 0, widget().getCellX());
    }

    /** The same clamp on the left handle, which moves the origin as well as the span. */
    /** Even shrinking is worth checking — it needs no free space, so it should still work. */
    @Test
    public void shrinkingStillWorksWithANeighbour() {
        HomescreenView v = view(2, 0);
        int cw = cellW(v), ch = cellH(v);
        drag(v, gripX(v, 2), gripY(v, 2), -cw, 0);
        assertEquals("shrinking frees space, so it must not be blocked",
                1, widget().getSpanX());
    }

    /**
     * A neighbour overlapping only one row of the new strip still blocks that column — the
     * footprint is rectangular, so a single occupied cell is enough. Kept as a reminder that
     * clamping does not make resize L-shaped.
     */
    @Test
    public void aNeighbourOverlappingOnlyOneRowStillBlocksThatColumn() {
        HomescreenView v = view(2, 1);
        int cw = cellW(v), ch = cellH(v);
        drag(v, gripX(v, 2), gripY(v, 2), cw, 0);
        assertEquals(2, widget().getSpanX());
    }

    /**
     * {@code AppWidgetProviderInfo.minResizeWidth} is in pixels — the framework has already scaled
     * the provider XML's dp by display density. Treating it as dp and multiplying again made the
     * minimum span several times too large, clamping the widget to its current size so no drag
     * could ever apply. Every other resize test leaves these fields at 0, which is why the suite
     * never saw it.
     *
     * <p>Numbers are the real ones from the device trace: a Google search widget reporting
     * {@code minResizeWidth=280} px against a 318 px cell, so its true minimum is one column.
     */
    @Test
    public void aProviderMinimumInPixelsIsNotScaledByDensityAgain() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        provider.minResizeWidth  = 280;  // px, ~1 cell — NOT 280dp
        provider.minResizeHeight = 100;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);

        store.addItem(HomeItem.widget(0, 0, 0, 4, 2, WIDGET_ID, PROVIDER));

        HomescreenView v = new HomescreenView(activity);
        v.setStore(store);
        v.setWidgetHost(new AppWidgetHost(activity, 2048));
        root.addView(v, W, H);
        v.refresh();
        layout(v);
        v.showResizeFrame(widget());
        layout(v);

        int cw = cellW(v), ch = cellH(v);
        // The grip is genuinely on the widget's corner now: the frame is no longer clamped inward
        // off the gesture insets (page padding gives it room), so no fudge is needed to find it.
        drag(v, gripX(v, 4), gripY(v, 2), -cw, 0);

        assertEquals("a 280px minimum against a " + cw + "px cell must allow shrinking",
                3, widget().getSpanX());
    }

    /** A drag of less than half a cell rounds to zero, so nothing happens however you grab it. */
    @Test
    public void aDragShorterThanHalfACellDoesNothing() {
        HomescreenView v = view(-1, -1);
        int cw = cellW(v), ch = cellH(v);
        drag(v, gripX(v, 2), gripY(v, 2), (int) (cw * 0.4f), 0);
        assertEquals("under half a cell rounds to no change", 2, widget().getSpanX());
    }
}
