package com.genericlauncher.launcher;

import android.app.Activity;
import android.view.DragEvent;
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

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * A long-press on a drawer app released without moving shows the app's menu; only a drag that
 * moved lands on the home screen.
 *
 * <p>The bug: the drawer's long-press starts a cross-view drag at once and dismisses the drawer,
 * so by the time the finger lifts {@link HomescreenView} is under it, receives the drop, and
 * {@code handleDrop} routed every incoming app straight to {@code handleIncomingAppDrop} with no
 * movement check. Any long-press therefore added the app to the cell under the finger, and the
 * menu — App info, Uninstall, Add to Home screen, shortcuts — was unreachable from the drawer.
 * The hand-off to the menu lived in ACTION_DRAG_ENDED behind {@code !sawDragLocation}, which a
 * real release in place never satisfies: the home view gets DRAG_LOCATION for a finger that has
 * not gone anywhere.
 *
 * <p>Driven the way {@link DragToRemoveTest} is, through real {@code dispatchDragEvent} calls
 * with a {@link DrawerDragState} in the local state, so the accept/refuse decision and the
 * DRAG_ENDED routing under test are production's own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DrawerAppLongPressTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final String PKG = "com.example.app";

    private ActivityController<Activity> controller;
    private Activity        activity;
    private FrameLayout     root;
    private HomeScreenStore store;
    private HomescreenView  view;
    private final AtomicReference<String> menuShownFor = new AtomicReference<>();

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

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setAppMap(new HashMap<>());
        view.setOnDrawerDragCancelledListener((pkg, anchor) -> menuShownFor.set(pkg));
        root.addView(view, W, H);
        view.refresh();
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    // ---------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------

    /** A DragEvent as the framework would deliver it: no ClipData, a local state, view coords. */
    private static DragEvent drag(int action, float x, float y, Object localState, boolean result) {
        try {
            for (Method m : DragEvent.class.getDeclaredMethods()) {
                if (!m.getName().equals("obtain")) continue;
                if (m.getParameterCount() != 11) continue;
                m.setAccessible(true);
                return (DragEvent) m.invoke(null, action, x, y, 0f, 0f, localState,
                        null, null, null, null, result);
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("DragEvent.obtain is not callable on this SDK", e);
        }
        throw new AssertionError("no 11-arg DragEvent.obtain on this SDK");
    }

    private float cellCentreX(int col) {
        int[] cell = view.measuredCellSize();
        assertNotNull("test setup: the view must be laid out", cell);
        return view.getPage(0).getLeft() + CellLayout.pagePadPx(activity)
                + col * cell[0] + cell[0] / 2f;
    }

    private float cellCentreY(int row) {
        int[] cell = view.measuredCellSize();
        assertNotNull("test setup: the view must be laid out", cell);
        return view.getPage(0).getTop() + row * cell[1] + cell[1] / 2f;
    }

    /**
     * Runs a whole drawer drag: DRAG_STARTED where the long-press was, the pointer wherever the
     * finger went, then the drop and DRAG_ENDED with the framework's result.
     */
    private boolean drawerDrag(float startX, float startY, float dropX, float dropY,
                               boolean sendLocation) {
        DrawerDragState state = new DrawerDragState(PKG);
        assertTrue("the home view must accept a drawer drag",
                view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_STARTED, startX, startY,
                        state, false)));
        if (sendLocation) {
            view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION, dropX, dropY, state,
                    false));
        }
        boolean accepted = view.dispatchDragEvent(
                drag(DragEvent.ACTION_DROP, dropX, dropY, state, false));
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_ENDED, dropX, dropY, state, accepted));
        return accepted;
    }

    // ---------------------------------------------------------------------
    // The reported bug
    // ---------------------------------------------------------------------

    /**
     * The device repro: a long-press with a 2px tremble. That tremble is a DRAG_LOCATION event,
     * which is why a "no location event" test could not express it.
     */
    @Test
    public void aLongPressReleasedInPlaceShowsTheMenuAndAddsNothing() {
        float x = cellCentreX(1), y = cellCentreY(3);

        boolean accepted = drawerDrag(x, y, x + 2, y + 2, true);

        assertFalse("a release without movement must not be a drop", accepted);
        assertTrue("nothing may be added to the home screen (found "
                + store.getItems() + ")", store.getItems().isEmpty());
        assertEquals("and the app's menu must be shown instead", PKG, menuShownFor.get());
    }

    /**
     * What actually happens on the device, and what the test above cannot see. DRAG_STARTED is
     * delivered while the drawer is still up, with the home view laid out below the drawer's
     * toolbar row; dismissing the drawer hides that row and the view moves up under the finger.
     * DragEvent coordinates are view-local, so a finger that has not moved reads ~220px away from
     * where it started and the first fix — which compared local coordinates — still dropped the
     * app. Measured on the emulator: DRAG_STARTED at (410,1290), DROP at (410,1290), accepted.
     */
    @Test
    public void theViewMovingUnderAStationaryFingerIsNotMovement() {
        final int toolbar = 220;
        float x = cellCentreX(1), y = cellCentreY(3);
        DrawerDragState state = new DrawerDragState(PKG);

        // The drawer is up: the home view sits a toolbar's height further down the screen, so the
        // finger at screen (x, y + toolbar) is local (x, y).
        view.layout(0, toolbar, W, H + toolbar);
        assertTrue(view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_STARTED, x, y, state, false)));

        // The drawer is dismissed for the drag and the view is back at the top of the screen. The
        // same screen point is now local (x, y + toolbar).
        view.layout(0, 0, W, H);
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION, x, y + toolbar, state, false));
        boolean accepted = view.dispatchDragEvent(
                drag(DragEvent.ACTION_DROP, x, y + toolbar, state, false));
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_ENDED, x, y + toolbar, state, accepted));

        assertFalse("the finger never moved; the view did", accepted);
        assertTrue("nothing may be added (found " + store.getItems() + ")",
                store.getItems().isEmpty());
        assertEquals(PKG, menuShownFor.get());
    }

    /** A drag the system cancelled before any location was delivered still means the menu. */
    @Test
    public void aLongPressWithNoLocationEventAtAllStillShowsTheMenu() {
        float x = cellCentreX(1), y = cellCentreY(3);

        boolean accepted = drawerDrag(x, y, x, y, false);

        assertFalse(accepted);
        assertTrue(store.getItems().isEmpty());
        assertEquals(PKG, menuShownFor.get());
    }

    /** The other half: a drag that moved is a drop, and it lands where the finger let go. */
    @Test
    public void aDragThatMovedLandsWhereItWasDropped() {
        boolean accepted = drawerDrag(cellCentreX(1), cellCentreY(3),
                cellCentreX(3), cellCentreY(1), true);

        assertTrue("a moved drag must be accepted as a drop", accepted);
        HomeItem added = store.itemAt(0, 3, 1);
        assertNotNull("the app should sit in the cell it was dropped on; store has "
                + store.getItems(), added);
        assertEquals(PKG, added.getPackageName());
        assertNull("a completed drop must not also open the menu", menuShownFor.get());
    }

    // ---------------------------------------------------------------------
    // No Remove pill for an app that is not on the home screen
    // ---------------------------------------------------------------------

    /**
     * Remove used to be offered for a drawer drag: the pill lit up red under the finger and the
     * drop was then refused, so the drawer was gone and nothing had changed. With no pill there
     * the same drop is an ordinary grid drop onto row 0.
     */
    @Test
    public void aDropWhereTheRemovePillUsedToBeLandsOnTheGrid() {
        float density = activity.getResources().getDisplayMetrics().density;
        // Where DragToRemoveTest finds the single Remove pill: the bar's centre, 26dp down.
        float pillX = W / 2f;
        float pillY = Math.round(8 * density) + Math.round(36 * density) / 2f;

        boolean accepted = drawerDrag(cellCentreX(1), cellCentreY(3), pillX, pillY, true);

        assertTrue("with no Remove pill to refuse it, the drop is a plain grid drop", accepted);
        assertEquals("the app should have been added, not silently discarded",
                1, store.getItems().size());
        assertEquals("on the top row, under where the pill was drawn",
                0, store.getItems().get(0).getCellY());
    }
}
