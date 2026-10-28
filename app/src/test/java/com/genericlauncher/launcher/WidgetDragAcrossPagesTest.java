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
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * A widget dropped after a drag lands, and nothing else happens — in particular the resize
 * frame does not open by itself.
 *
 * <p>The bug: {@code handleGridDropAt} set {@code pendingResizeItem} after every successful
 * widget move, so a widget dragged to the right edge, flipped onto a new page and dropped there
 * arrived with the frame already open (QA F5). The next drag on it was then a frame-body move
 * rather than a drag, and dismissing the frame looked like the widget vanishing. The frame
 * belongs to the long-press-without-movement gesture alone.
 *
 * <p>The page flip is driven the way the device does it: a DRAG_LOCATION in the edge strip,
 * the looper idled past {@code EDGE_DELAY_MS}, and the drop on the page that appeared.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetDragAcrossPagesTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int WIDGET_ID = 11;
    private static final String PROVIDER = "com.example.resizable/.Provider";

    private ActivityController<Activity> controller;
    private Activity        activity;
    private HomeScreenStore store;
    private HomescreenView  view;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        FrameLayout root = new FrameLayout(activity);
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
        store.addItem(HomeItem.widget(0, 0, 0, 3, 1, WIDGET_ID, PROVIDER));

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 2048));
        root.addView(view, W, H);
        view.refresh();
        layout();
    }

    @After
    public void tearDown() {
        view.dismissResizeFrame();
        controller.pause().stop().destroy();
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
        return CellLayout.pagePadPx(activity) + col * cell[0] + cell[0] / 2f;
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

    private void pickUp() {
        view.beginItemDrag(new View(activity), widget());
        assertTrue(view.dispatchDragEvent(
                drag(DragEvent.ACTION_DRAG_STARTED, cellCentreX(0), cellCentreY(0), false)));
    }

    private boolean dropAt(float x, float y) {
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION, x, y, false));
        boolean accepted = view.dispatchDragEvent(drag(DragEvent.ACTION_DROP, x, y, false));
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_ENDED, x, y, accepted));
        return accepted;
    }

    // ---------------------------------------------------------------------
    // The reported bug
    // ---------------------------------------------------------------------

    @Test
    public void aWidgetDroppedOnAnotherPageAfterAnEdgeFlipDoesNotOpenTheFrame() {
        assertNull("test setup: one page to start with", view.getPage(1));
        pickUp();

        // Hover in the right edge strip until the flip fires (EDGE_DELAY_MS = 600).
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION, W - 5, cellCentreY(1), false));
        ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
        view.computeScroll();
        layout(); // idling re-lays the view out at Robolectric's window size
        assertEquals("test setup: the edge flip must have created and moved to page 1",
                1, view.getCurrentPage());
        assertEquals("test setup: and scrolled the strip there", W, view.getScrollX());

        boolean accepted = dropAt(cellCentreX(0), cellCentreY(1));

        assertTrue("the drop on the new page must be accepted", accepted);
        assertEquals("the widget should be on page 1", 1, widget().getScreen());
        assertEquals(0, widget().getCellX());
        assertEquals(1, widget().getCellY());
        assertFalse("a drag that moved is not a long-press; the frame must stay closed",
                view.isResizeFrameOpen());
    }

    /** The same rule on one page: a move is a move, and only a long-press opens the frame. */
    @Test
    public void aWidgetMovedOnTheSamePageDoesNotOpenTheFrame() {
        pickUp();

        assertTrue(dropAt(cellCentreX(0), cellCentreY(3)));

        assertEquals(3, widget().getCellY());
        assertFalse(view.isResizeFrameOpen());
    }

    /** And the long-press-without-movement still does — the one route to the frame. */
    @Test
    public void aLongPressReleasedInPlaceStillOpensTheFrame() {
        pickUp();

        assertFalse(dropAt(cellCentreX(0) + 2, cellCentreY(0) + 2));

        assertEquals(0, widget().getScreen());
        assertTrue(view.isResizeFrameOpen());
    }
}
