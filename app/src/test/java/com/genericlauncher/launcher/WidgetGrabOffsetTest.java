package com.genericlauncher.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.view.DragEvent;
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
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/**
 * A multi-cell widget keeps the cell it was picked up in under the finger.
 *
 * <p>The bug: {@code resolveTarget} mapped the pointer to a cell and {@code handleDrop} committed
 * the item's <em>top-left</em> there, with no memory of which cell of the item the finger had
 * started in. A 2x1 clock long-pressed in its second cell — the natural place to press a
 * two-cell widget — had its origin moved to that cell: one column right per long-press, with
 * the resize frame then opening around the new position. A 3x1 dragged by its middle landed a
 * column right of where it was let go. 1x1 icons were unaffected, because for them the pointer's
 * cell and the origin are the same cell, which is why every drop test so far passed.
 *
 * <p>Driven through real {@code dispatchDragEvent} calls the way {@link DragToRemoveTest} is;
 * the grab is captured from where DRAG_STARTED says the finger is.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetGrabOffsetTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int WIDGET_ID = 11;
    private static final String PROVIDER = "com.example.resizable/.Provider";

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
        File[] files = activity.getFilesDir().listFiles();
        if (files != null) {
            for (File f : files) if (f.getName().startsWith("homescreen.json")) f.delete();
        }
        store = HomeScreenStore.get(activity);

        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);
    }

    @After
    public void tearDown() {
        if (view != null) view.dismissResizeFrame();
        controller.pause().stop().destroy();
    }

    // ---------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------

    private HomescreenView viewWith(HomeItem widget) {
        store.addItem(widget);
        view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 2048));
        root.addView(view, W, H);
        view.refresh();
        layout();
        return view;
    }

    private void layout() {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    private static DragEvent drag(int action, float x, float y, boolean result) {
        try {
            for (Method m : DragEvent.class.getDeclaredMethods()) {
                if (!m.getName().equals("obtain")) continue;
                if (m.getParameterCount() != 11) continue;
                m.setAccessible(true);
                return (DragEvent) m.invoke(null, action, x, y, 0f, 0f, null,
                        null, null, null, null, result);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("DragEvent.obtain is not callable on this SDK", e);
        }
        throw new AssertionError("no 11-arg DragEvent.obtain on this SDK");
    }

    private float cellCentreX(int col) {
        int[] cell = view.measuredCellSize();
        return view.getPage(0).getLeft() + CellLayout.pagePadPx(activity)
                + col * cell[0] + cell[0] / 2f;
    }

    private float cellCentreY(int row) {
        int[] cell = view.measuredCellSize();
        return view.getPage(0).getTop() + row * cell[1] + cell[1] / 2f;
    }

    private HomeItem widget() {
        for (HomeItem i : store.getItems()) {
            if (i.getType() == HomeItem.Type.WIDGET) return i;
        }
        return null;
    }

    /** Picks the widget up with the finger in cell ({@code col},{@code row}) of the grid. */
    private void pickUpAt(int col, int row) {
        view.beginItemDrag(new View(activity), widget());
        assertTrue(view.dispatchDragEvent(
                drag(DragEvent.ACTION_DRAG_STARTED, cellCentreX(col), cellCentreY(row), false)));
    }

    /** Moves to and lets go at ({@code x},{@code y}); returns whether the drop was accepted. */
    private boolean dropAt(float x, float y) {
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION, x, y, false));
        boolean accepted = view.dispatchDragEvent(drag(DragEvent.ACTION_DROP, x, y, false));
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_ENDED, x, y, accepted));
        return accepted;
    }

    // ---------------------------------------------------------------------
    // The reported bug: a long-press in the second cell of a 2x1
    // ---------------------------------------------------------------------

    @Test
    public void aLongPressInTheSecondCellOpensTheFrameWithTheWidgetWhereItWas() {
        viewWith(HomeItem.widget(0, 0, 1, 2, 1, WIDGET_ID, PROVIDER));

        pickUpAt(1, 1);
        // The device repro: a 2px tremble on release, well inside touchSlop.
        boolean accepted = dropAt(cellCentreX(1) + 2, cellCentreY(1) + 2);

        assertFalse("a release in place is not a move", accepted);
        HomeItem w = widget();
        assertEquals("the widget must not have walked a column right", 0, w.getCellX());
        assertEquals(1, w.getCellY());
        assertTrue("and the long-press-without-movement must still open the frame",
                view.isResizeFrameOpen());
    }

    @Test
    public void aWidgetGrabbedInItsSecondCellLandsWithThatCellUnderTheFinger() {
        viewWith(HomeItem.widget(0, 0, 1, 2, 1, WIDGET_ID, PROVIDER));

        pickUpAt(1, 1);
        boolean accepted = dropAt(cellCentreX(3), cellCentreY(3));

        assertTrue("a moved drop onto free cells must be accepted", accepted);
        HomeItem w = widget();
        assertEquals("the finger let go over column 3 holding the widget's second cell, so the"
                + " origin is column 2", 2, w.getCellX());
        assertEquals(3, w.getCellY());
    }

    /** The outline drawn during the drag has to say the same thing the drop then does. */
    @Test
    public void theLivePreviewOutlinesTheCellTheDropWillUse() {
        viewWith(HomeItem.widget(0, 0, 1, 2, 1, WIDGET_ID, PROVIDER));

        pickUpAt(1, 1);
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION,
                cellCentreX(3), cellCentreY(3), false));

        int[] preview = view.dropPreviewCellForTest();
        assertNotNull("a drag over free cells must show a preview", preview);
        assertArrayEquals(new int[]{0, 2, 3}, preview);
    }

    /**
     * The clamp has to be applied after the grab is subtracted. With the pointer in the last
     * column holding the third cell of a 3x1, the origin is column 1; clamping the pointer's cell
     * to {@code cols - spanX} first put it at column 0.
     */
    @Test
    public void theGrabIsSubtractedBeforeTheSpanClamp() {
        viewWith(HomeItem.widget(0, 0, 1, 3, 1, WIDGET_ID, PROVIDER));

        pickUpAt(2, 1);
        boolean accepted = dropAt(cellCentreX(3), cellCentreY(3));

        assertTrue(accepted);
        assertEquals(1, widget().getCellX());
        assertEquals(3, widget().getCellY());
    }

    /** Picked up by its first cell, nothing changes from before. */
    @Test
    public void aWidgetGrabbedInItsFirstCellStillLandsOnThePointerCell() {
        viewWith(HomeItem.widget(0, 0, 1, 2, 1, WIDGET_ID, PROVIDER));

        pickUpAt(0, 1);
        assertTrue(dropAt(cellCentreX(1), cellCentreY(3)));

        assertEquals(1, widget().getCellX());
        assertEquals(3, widget().getCellY());
    }
}
