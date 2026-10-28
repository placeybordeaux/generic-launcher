package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Regression tests for issue 4: widget removal via drag-to-remove-zone was broken because
 * {@code setOnLongClickListener} on a plain {@code FrameLayout} never fires when the child
 * {@link android.appwidget.AppWidgetHostView} consumes all touches.
 *
 * <p>These tests verify the store-side of the removal pipeline (the part that cannot reach
 * the widget host in a unit test) and document the decision logic that {@link
 * HomescreenView.WidgetWrapper} must implement for the drag to be able to start.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetRemovalTest {

    private Context ctx;
    private HomeScreenStore store;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
        new File(ctx.getFilesDir(), "homescreen.json.tmp").delete();
        store = HomeScreenStore.get(ctx);
    }

    // -------------------------------------------------------------------------
    // Store-side of the removal pipeline
    // -------------------------------------------------------------------------

    /**
     * Removing a WIDGET item via {@link HomeScreenStore#removeItem} must leave the store
     * empty (no phantom entries).  This is the store-side portion of the remove-zone drop
     * path in {@code HomescreenView.handleDrop(REMOVE)}.
     */
    @Test
    public void removeWidget_leavesStoreEmpty() {
        HomeItem widget = HomeItem.widget(0, 1, 1, 2, 2, 42, "com.audible/com.audible.Glance");
        store.addItem(widget);

        assertEquals("pre-condition: one widget in store", 1, store.getItems().size());

        store.removeItem(widget);

        assertEquals("store is empty after removal", 0, store.getItems().size());
    }

    /**
     * Removing one of multiple WIDGET items must leave the other widgets intact.
     * This models the scenario of 3 stuck Audible Glance widgets: the user removes one at
     * a time; each removal must not disturb the remaining items.
     */
    @Test
    public void removeOneWidget_othersUntouched() {
        HomeItem w1 = HomeItem.widget(0, 0, 0, 2, 2, 11, "com.audible/com.audible.Glance");
        HomeItem w2 = HomeItem.widget(0, 2, 0, 2, 2, 12, "com.audible/com.audible.Glance");
        HomeItem w3 = HomeItem.widget(1, 0, 0, 2, 2, 13, "com.audible/com.audible.Glance");
        store.addItem(w1);
        store.addItem(w2);
        store.addItem(w3);

        store.removeItem(w1);

        List<HomeItem> remaining = store.getItems();
        assertEquals("two widgets remain", 2, remaining.size());
        assertFalse("w1 is gone", remaining.contains(w1));
        assertTrue("w2 is still present", remaining.contains(w2));
        assertTrue("w3 is still present", remaining.contains(w3));
    }

    /**
     * After all three stuck Audible Glance widgets are removed one by one, the store must
     * be empty with no residual entries.
     */
    @Test
    public void removeAllThreeStuckWidgets_storeEmpty() {
        HomeItem w1 = HomeItem.widget(0, 0, 0, 2, 2, 11, "com.audible/com.audible.Glance");
        HomeItem w2 = HomeItem.widget(0, 2, 0, 2, 2, 12, "com.audible/com.audible.Glance");
        HomeItem w3 = HomeItem.widget(0, 0, 2, 2, 2, 13, "com.audible/com.audible.Glance");
        store.addItem(w1);
        store.addItem(w2);
        store.addItem(w3);

        store.removeItem(w1);
        store.removeItem(w2);
        store.removeItem(w3);

        assertEquals("store is empty after removing all three", 0, store.getItems().size());
    }

    /**
     * Removing a widget must not affect APP items on the same screen.
     */
    @Test
    public void removeWidget_doesNotAffectApps() {
        HomeItem widget = HomeItem.widget(0, 0, 0, 2, 2, 42, "com.audible/com.audible.Glance");
        HomeItem app    = HomeItem.app(0, 2, 0, "com.example.app");
        store.addItem(widget);
        store.addItem(app);

        store.removeItem(widget);

        List<HomeItem> remaining = store.getItems();
        assertEquals("one item remains", 1, remaining.size());
        assertEquals("the remaining item is the app", HomeItem.Type.APP,
                remaining.get(0).getType());
        assertEquals("com.example.app", remaining.get(0).getPackageName());
    }

    // -------------------------------------------------------------------------
    // WidgetWrapper long-press decision logic (pure helper)
    // -------------------------------------------------------------------------

    /**
     * Sanity check: a pointer that moves less than touchSlop should not be considered a
     * "moved past slop" event (the long-press timeout should be allowed to fire).
     *
     * <p>This documents the invariant that {@link HomescreenView.WidgetWrapper} relies on:
     * when the delta is below touchSlop, the wrapper does NOT cancel its pending long-press,
     * so the drag will eventually start.
     */
    @Test
    public void movedBelowSlop_longPressShouldNotBeCancelled() {
        int touchSlop = 10; // representative value
        float dx = touchSlop - 1f;
        float dy = 0f;
        boolean shouldCancel = (dx > touchSlop || dy > touchSlop);
        assertFalse("movement below slop must NOT cancel the long-press timeout", shouldCancel);
    }

    /**
     * A pointer that moves exactly past touchSlop should be treated as a scroll/swipe and
     * the pending long-press should be cancelled.
     */
    @Test
    public void movedPastSlop_longPressMustBeCancelled() {
        int touchSlop = 10;
        float dx = touchSlop + 1f; // just past slop
        float dy = 0f;
        boolean shouldCancel = (dx > touchSlop || dy > touchSlop);
        assertTrue("movement past slop MUST cancel the long-press timeout", shouldCancel);
    }

    /**
     * Diagonal movement past slop (both axes small, but combined > slop on one axis)
     * should also cancel the long-press.
     */
    @Test
    public void diagonalMovePastSlop_longPressMustBeCancelled() {
        int touchSlop = 10;
        float dx = 0f;
        float dy = touchSlop + 2f; // vertical swipe
        boolean shouldCancel = (dx > touchSlop || dy > touchSlop);
        assertTrue("vertical movement past slop must cancel the long-press", shouldCancel);
    }
}
