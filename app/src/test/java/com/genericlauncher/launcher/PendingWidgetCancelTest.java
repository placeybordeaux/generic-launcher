package com.genericlauncher.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Cancelling a pending widget on a host whose {@code deleteAppWidgetId} throws.
 *
 * <p>{@code AppWidgetHost.deleteAppWidgetId} throws on some OEM builds (CLAUDE.md's invariant).
 * The drag-to-remove path, the stale-widget prune and SettingsActivity's reset all wrap it;
 * {@code MainActivity}'s two cancellation sites — the bind/configure activity returning
 * {@code RESULT_CANCELED}, and {@code finalizeWidget} finding no free cell — called it bare, from
 * {@code onActivityResult} on the main thread. For the HOME app an exception there is not a
 * dialog, it is no home screen. Both paths must survive the throw <em>and</em> still clear the
 * pending id, or the next widget added inherits a stale one.
 *
 * <p>Each test first checks the host was actually asked to delete the id: a cancel path that
 * simply stopped calling {@code deleteAppWidgetId} would pass the survival assertions for the
 * wrong reason, and leak an id per cancel.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class PendingWidgetCancelTest {

    private static final String PROVIDER  = "com.example.clock/.ClockProvider";
    private static final int    WIDGET_ID = 57;

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private HomeScreenStore store;
    private ThrowingHost host;

    /** What an OEM host does: refuses the delete, and remembers being asked. */
    private static final class ThrowingHost extends LauncherWidgetHost {
        final List<Integer> attempted = new ArrayList<>();
        boolean throwing = true;

        ThrowingHost(Context context) { super(context); }

        @Override
        public void deleteAppWidgetId(int appWidgetId) {
            attempted.add(appWidgetId);
            if (throwing) {
                throw new IllegalStateException("deleteAppWidgetId refused by the OEM host");
            }
        }
    }

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        Settings.setHomeSeeded(ctx, true);
        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
        new File(ctx.getFilesDir(), "homescreen.json.tmp").delete();
        ShadowToast.reset();

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
        store      = HomeScreenStore.get(activity);
        host       = new ThrowingHost(activity);
        activity.setWidgetHostForTest(host);

        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    // -------------------------------------------------------------------------
    // Back out of the bind permission prompt
    // -------------------------------------------------------------------------

    @Test
    public void aCancelledBindOnAHostThatThrowsStillClearsThePendingWidget() {
        activity.beginPendingWidget(WIDGET_ID);
        assertEquals("test setup: the widget is pending", WIDGET_ID, pendingWidgetId());

        activity.onActivityResult(MainActivity.RC_WIDGET_BIND, Activity.RESULT_CANCELED, null);

        assertEquals("the id must have been offered back to the host, throw or not",
                List.of(WIDGET_ID), host.attempted);
        assertEquals("a refused delete must not leave the id pending", -1, pendingWidgetId());
        assertFalse("and the activity is still the home screen", activity.isFinishing());
    }

    @Test
    public void aCancelledConfigureOnAHostThatThrowsStillClearsThePendingWidget() {
        activity.beginPendingWidget(WIDGET_ID);

        activity.onActivityResult(MainActivity.RC_WIDGET_CONFIGURE, Activity.RESULT_CANCELED, null);

        assertEquals(List.of(WIDGET_ID), host.attempted);
        assertEquals(-1, pendingWidgetId());
    }

    /** Control: on a host that cooperates the same path deletes the id and clears the state. */
    @Test
    public void aCancelledBindOnAWellBehavedHostDeletesTheIdAndClearsThePendingWidget() {
        host.throwing = false;
        activity.beginPendingWidget(WIDGET_ID);

        activity.onActivityResult(MainActivity.RC_WIDGET_BIND, Activity.RESULT_CANCELED, null);

        assertEquals(List.of(WIDGET_ID), host.attempted);
        assertEquals(-1, pendingWidgetId());
    }

    // -------------------------------------------------------------------------
    // No free cell anywhere
    // -------------------------------------------------------------------------

    @Test
    public void noSpaceForTheWidgetOnAHostThatThrowsStillClearsThePendingWidget() {
        fillEveryCellOnEveryScreen();
        activity.beginPendingWidget(WIDGET_ID);

        activity.finalizeWidget();

        assertEquals("test setup: the no-space branch is the one that ran",
                "No space for widget", ShadowToast.getTextOfLatestToast());
        assertEquals("the id must have been offered back to the host, throw or not",
                List.of(WIDGET_ID), host.attempted);
        assertEquals("a refused delete must not leave the id pending", -1, pendingWidgetId());
        assertNull("and nothing was placed", placedWidget());
        assertFalse(activity.isFinishing());
    }

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    /**
     * The pending id as {@code onSaveInstanceState} writes it — the same field, read through the
     * seam that already exists rather than a getter added for the test.
     */
    private int pendingWidgetId() {
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        return state.getInt("pendingWidgetId", Integer.MIN_VALUE);
    }

    private void fillEveryCellOnEveryScreen() {
        for (int screen = 0; screen < HomeScreenStore.MAX_SCREENS; screen++) {
            for (int y = 0; y < store.rows(); y++) {
                for (int x = 0; x < store.columns(); x++) {
                    store.addItem(HomeItem.app(screen, x, y,
                            "com.example.fill.s" + screen + "x" + x + "y" + y));
                }
            }
        }
        for (int screen = 0; screen < HomeScreenStore.MAX_SCREENS; screen++) {
            assertNull("test setup: screen " + screen + " should be full",
                    store.firstFreeCell(screen, 1, 1));
        }
    }

    private HomeItem placedWidget() {
        for (HomeItem item : store.getItems()) {
            if (item.type == HomeItem.Type.WIDGET && item.getAppWidgetId() == WIDGET_ID) {
                return item;
            }
        }
        return null;
    }
}
