package com.genericlauncher.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
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
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Drag-to-remove, driven through the real {@code homeDragListener} and {@code handleDrop}.
 *
 * <p>This path had no test at all, which is how a regression in it reached a device. The reason
 * is mechanical: the drag deliberately carries a null {@code ClipData} (OEM drag panels hijack a
 * drag that advertises content — see {@code startItemDrag}), and Robolectric's window-session
 * shadow rejects exactly that with "Missing ClipData param". So {@code startDragAndDrop} can
 * never succeed here. {@link HomescreenView#beginItemDrag} is the state half of
 * {@code startItemDrag} split out from that one system call, and {@link DragEvent#obtain} builds
 * the events the framework would have delivered — so everything from ACTION_DRAG_STARTED to the
 * store write is production code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DragToRemoveTest {

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
        File[] files = activity.getFilesDir().listFiles();
        if (files != null) {
            for (File f : files) if (f.getName().startsWith("homescreen.json")) f.delete();
        }
        store = HomeScreenStore.get(activity);
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

    private HomescreenView viewWithWidget() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);

        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, PROVIDER));
        return laidOutView();
    }

    private HomescreenView viewWithApp() {
        store.addItem(HomeItem.app(0, 0, 0, "com.example.app"));
        return laidOutView();
    }

    private HomescreenView laidOutView() {
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

    private HomeItem widget() {
        for (HomeItem i : store.getItems()) {
            if (i.getType() == HomeItem.Type.WIDGET) return i;
        }
        return null;
    }

    private float density() { return activity.getResources().getDisplayMetrics().density; }
    private int dp(int v)   { return Math.round(v * density()); }

    /**
     * Centre of the Remove pill, mirroring {@code actionBarLayout} / {@code ActionBar.pillLeft}.
     * Remove is always pill 0. {@code zones} is how many pills the bar carries, which decides
     * where the bar is centred.
     */
    private float removePillX(int zones) {
        float pillW = dp(96), gap = dp(8);
        float total = zones * pillW + (zones - 1) * gap;
        return W / 2f - total / 2f + pillW / 2f;
    }
    private float removePillY() { return dp(8) + dp(36) / 2f; }

    /** Drives a whole drag: pick the item up, move onto the Remove pill, drop, end. */
    private void dragToRemove(HomescreenView view, HomeItem item, View source, int zones) {
        view.beginItemDrag(source, item);
        assertTrue(view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_STARTED, 0, 0, null, false)));
        float x = removePillX(zones), y = removePillY();
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION, x, y, null, false));
        boolean accepted = view.dispatchDragEvent(drag(DragEvent.ACTION_DROP, x, y, null, false));
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_ENDED, x, y, null, accepted));
        assertTrue("the drop on Remove was refused", accepted);
    }

    // ---------------------------------------------------------------------
    // The reported bug
    // ---------------------------------------------------------------------

    @Test
    public void droppingAWidgetOnRemoveDeletesIt() {
        HomescreenView view = viewWithWidget();
        dragToRemove(view, widget(), new View(activity), 1);
        assertNull("the widget is still on the home screen", widget());
        assertEquals(0, store.getItems().size());
    }

    /**
     * The action bar is pinned to the visible screen, so Remove has to work on every page — but
     * every fixture above puts its item on screen 0, where {@code scrollX == 0} makes the bar's
     * coordinate space and the drop's coincide. That is exactly why this shipped broken: the bar
     * was built in content space while {@code DropTarget.resolve} hit-tests screen-local, so a
     * drop on the pill from any page but the first missed it by the scroll offset and fell
     * through to the GRID branch. Measured on device: {@code DROP at (532,220) -> zone=GRID
     * page=1}, accepted=false, with the pill plainly under the finger.
     *
     * <p>The page is reached by a real swipe rather than {@code scrollTo}, so {@code currentPage}
     * and {@code scrollX} agree the way they do in the field.
     */
    @Test
    public void droppingAWidgetOnRemoveWorksOnAPageOtherThanTheFirst() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);

        store.addItem(HomeItem.app(0, 0, 0, "com.example.app"));
        store.addItem(HomeItem.widget(1, 0, 0, 2, 2, WIDGET_ID, PROVIDER));
        HomescreenView view = laidOutView();
        swipeToPage(view, 1);

        dragToRemove(view, widget(), new View(activity), 1);

        assertNull("the widget is still on the home screen", widget());
    }

    /** The same, two pages along — the offset scales with the scroll, so page 2 is a real case. */
    @Test
    public void droppingAnAppOnRemoveWorksTwoPagesAlong() {
        store.addItem(HomeItem.app(0, 0, 0, "com.example.app0"));
        store.addItem(HomeItem.app(1, 0, 0, "com.example.app1"));
        store.addItem(HomeItem.app(2, 0, 0, "com.example.app2"));
        HomescreenView view = laidOutView();
        swipeToPage(view, 2);

        HomeItem onPageTwo = null;
        for (HomeItem i : store.getItems()) if (i.getScreen() == 2) onPageTwo = i;
        assertNotNull(onPageTwo);

        dragToRemove(view, onPageTwo, new View(activity), 1);

        assertEquals(2, store.getItems().size());
        for (HomeItem i : store.getItems()) {
            assertNotEquals("the item dropped on Remove is still there", 2, i.getScreen());
        }
    }

    /**
     * Swipes the strip to {@code page} the way a finger would.
     *
     * <p>The trailing measure/layout is not decoration: idling the looper lets Robolectric run its
     * own traversal, which re-lays the view out at its default window size and discards the
     * {@link #W}×{@link #H} this fixture imposed.
     */
    private void swipeToPage(HomescreenView view, int page) {
        for (int i = 0; i < page; i++) {
            long t = SystemClock.uptimeMillis();
            view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 900, 1200, 0));
            view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, 200, 1200, 0));
            view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP,   200, 1200, 0));
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
            view.computeScroll();
        }
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);

        assertEquals("test setup: the swipe should have reached page " + page,
                page, view.getCurrentPage());
        assertEquals("test setup: and the strip should be scrolled there — a zero scroll is the"
                        + " very condition this test exists to escape",
                page * W, view.getScrollX());
    }

    @Test
    public void droppingAnAppOnRemoveDeletesIt() {
        HomescreenView view = viewWithApp();
        HomeItem app = store.getItems().get(0);
        // An app with no AppInfo in the map offers Remove only.
        dragToRemove(view, app, new View(activity), 1);
        assertEquals(0, store.getItems().size());
    }

    /**
     * The one call in the Remove path that reaches outside the app, and the failure it used to
     * cause. {@code AppWidgetHost.deleteAppWidgetId} throws on some OEM builds — the identical
     * pairing in {@code populatePage} and in SettingsActivity's reset are both wrapped for that
     * reason, and this one was not. An escaping exception unwinds out of ACTION_DROP before
     * {@code store.removeItem}, so the widget survives a drop on Remove and the pill just looks
     * dead.
     */
    @Test
    public void aThrowingWidgetHostStillLetsTheWidgetBeRemoved() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);
        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, PROVIDER));

        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 2048) {
            @Override
            public void deleteAppWidgetId(int appWidgetId) {
                throw new IllegalArgumentException("OEM host says no");
            }
        });
        root.addView(view, W, H);
        view.refresh();
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);

        dragToRemove(view, widget(), new View(activity), 1);
        assertNull("a leaked host id is fine; a widget that will not go away is not", widget());
    }

    // ---------------------------------------------------------------------
    // Drags that run before the widget's, and must leave no state behind
    // ---------------------------------------------------------------------

    /**
     * Runs a whole folder-member drag through the real listeners: the popup opens, the member's
     * drag starts, the pointer leaves the card (which hands the drag to the home screen and tears
     * the popup down), and the member is dropped on an empty cell.
     *
     * <p>Every event goes through {@code view.dispatchDragEvent}, so ViewGroup's own routing
     * decides which of the three listeners hears it — which is the entire mechanism under test.
     */
    private void pullAMemberOutOfAFolder(HomescreenView view, HomeItem folder,
                                          float dropX, float dropY) {
        view.openFolderPopup(folder);
        assertTrue("test setup: the popup must be open", view.isFolderPopupOpen());
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);

        Object state = view.folderDragStateForTest(folder, 0);
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_STARTED, 0, 0, state, false));
        // Far outside the card, in the scrim: the pull-out gesture.
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION, 5, H - 5, state, false));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); // the posted popup teardown
        assertFalse("the pull-out must have closed the popup", view.isFolderPopupOpen());

        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_LOCATION, dropX, dropY, state, false));
        boolean ok = view.dispatchDragEvent(drag(DragEvent.ACTION_DROP, dropX, dropY, state, false));
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_ENDED, dropX, dropY, state, ok));
        assertTrue("test setup: the pull-out drop was refused", ok);
    }

    /**
     * The regression guard. A pull-out sets up an incoming-app drag ({@code isIncomingApp},
     * {@code incomingAppPkg}, {@code pullOutFolder}); if any of it survives the drag, the next
     * drop is routed down the incoming-app path, where a Remove with no source folder is refused
     * — and a widget dropped on Remove silently stays put.
     */
    @Test
    public void aWidgetIsStillRemovableAfterAnAppWasDraggedOutOfAFolder() {
        HomescreenView view = viewWithWidget();
        store.addItem(HomeItem.folder(0, 3, 4, "Folder", java.util.Arrays.asList("a", "b", "c")));
        view.refresh();

        pullAMemberOutOfAFolder(view, store.itemAt(0, 3, 4), cellCentreX(2), cellCentreY(2));

        dragToRemove(view, widget(), new View(activity), 1);
        assertNull("the widget survived a drop on Remove", widget());
    }

    /** The same, for an app: Remove must still delete it after a pull-out. */
    @Test
    public void anAppIsStillRemovableAfterAnAppWasDraggedOutOfAFolder() {
        HomescreenView view = viewWithApp();
        store.addItem(HomeItem.folder(0, 3, 4, "Folder", java.util.Arrays.asList("a", "b", "c")));
        view.refresh();

        pullAMemberOutOfAFolder(view, store.itemAt(0, 3, 4), cellCentreX(2), cellCentreY(2));

        HomeItem app = store.itemAt(0, 0, 0);
        assertNotNull("test setup: the app is still at its cell", app);
        dragToRemove(view, app, new View(activity), 1);
        assertNull("the app survived a drop on Remove", store.itemAt(0, 0, 0));
    }

    /** A member drag that stays inside the popup is a reorder, and must also leave nothing behind. */
    @Test
    public void aWidgetIsStillRemovableAfterAnInFolderReorder() {
        HomescreenView view = viewWithWidget();
        store.addItem(HomeItem.folder(0, 3, 4, "Folder", java.util.Arrays.asList("a", "b", "c")));
        view.refresh();

        HomeItem folder = store.itemAt(0, 3, 4);
        view.openFolderPopup(folder);
        Object state = view.folderDragStateForTest(folder, 0);
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_STARTED, 0, 0, state, false));
        view.dispatchDragEvent(drag(DragEvent.ACTION_DRAG_ENDED, 0, 0, state, false));
        view.closeFolderPopup();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        dragToRemove(view, widget(), new View(activity), 1);
        assertNull("the widget survived a drop on Remove", widget());
    }

    private float cellCentreX(int col) {
        int pad   = CellLayout.pagePadPx(activity);
        int cellW = CellLayout.cellWidth(W, 4, pad);
        return pad + col * cellW + cellW / 2f;
    }
    private float cellCentreY(int row) {
        int cellH = (H - dp(72) - dp(24)) / 5;   // page height / rows, near enough for a mid-grid cell
        return row * cellH + cellH / 2f;
    }
}
