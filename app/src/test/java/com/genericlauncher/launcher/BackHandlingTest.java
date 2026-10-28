package com.genericlauncher.launcher;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Back is handled through the {@code OnBackPressedDispatcher}, so it works under predictive
 * back — and the launcher's only activity never finishes on it.
 *
 * <p>The bug: {@code MainActivity.onBackPressed()} was an override, and on Android 16 with
 * targetSdk 36 it was never called. Predictive back is on by default there; the system invokes
 * the window's {@code OnBackInvokedDispatcher} callback instead of sending KEYCODE_BACK, and with
 * no androidx callback enabled that was the framework's default, which finishes the activity.
 * The system relaunches a finished HOME activity at once, so every "Back closes X" looked right
 * while actually being a kill-and-recreate — and a folder renamed in its popup then closed with
 * Back kept its old name (QA F3), because the dying instance never ran {@code closeFolderPopup}.
 *
 * <p>These drive {@code getOnBackPressedDispatcher().onBackPressed()}, which is the entry point
 * the system callback uses, rather than {@code Activity.onBackPressed()}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BackHandlingTest {

    private static final int W = 1080;
    private static final int H = 2424;

    private Context ctx;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private HomeScreenStore store;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        Settings.setHomeSeeded(ctx, true);
        HomeScreenStore.resetForTesting();
        new java.io.File(ctx.getFilesDir(), "homescreen.json").delete();
        new java.io.File(ctx.getFilesDir(), "homescreen.json.tmp").delete();

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
        store      = HomeScreenStore.get(activity);
        activity.applyLoadedApps(new ArrayList<>()); // settle the initial load
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    private void back() {
        activity.getOnBackPressedDispatcher().onBackPressed();
    }

    private HomescreenView homescreen() {
        HomescreenView view = activity.homescreenViewForTests();
        assertNotNull("test setup: the home screen must be built", view);
        return view;
    }

    private static void layout(View view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    /** The name EditText inside the open popup (card is overlay child 1; scrim is child 0). */
    private static EditText popupNameEdit(HomescreenView view) {
        View overlay = view.getChildAt(view.getChildCount() - 1);
        assertTrue("test setup: no popup overlay on top", overlay instanceof FrameLayout);
        ViewGroup card = (ViewGroup) ((FrameLayout) overlay).getChildAt(1);
        assertTrue(card.getChildAt(0) instanceof EditText);
        return (EditText) card.getChildAt(0);
    }

    // ---------------------------------------------------------------------
    // The reported bug
    // ---------------------------------------------------------------------

    @Test
    public void renamingAFolderAndClosingItWithBackKeepsTheName() {
        HomeItem folder = HomeItem.folder(0, 0, 1, "Folder",
                Arrays.asList("com.example.first", "com.example.second"));
        store.addItem(folder);
        HomescreenView view = homescreen();
        view.refresh();
        layout(view);

        view.openFolderPopup(folder);
        popupNameEdit(view).setText("Work");

        back();

        assertFalse("Back must close the popup, not the launcher", activity.isFinishing());
        assertFalse("the popup should have been closed", view.isFolderPopupOpen());
        assertEquals("and the typed name kept", "Work", folder.getFolderName());
    }

    // ---------------------------------------------------------------------
    // Back never finishes the launcher
    // ---------------------------------------------------------------------

    @Test
    public void backWithNothingOpenDoesNotFinishTheActivity() {
        assertTrue("precondition", activity.isHomeForTests());

        back();

        assertFalse("the launcher's only activity must never finish on Back",
                activity.isFinishing());
        assertTrue(activity.isHomeForTests());
    }

    @Test
    public void backWithTheDrawerOpenReturnsHome() {
        activity.openDrawer();
        assertFalse("precondition", activity.isHomeForTests());

        back();

        assertTrue(activity.isHomeForTests());
        assertFalse(activity.isFinishing());
    }

    // ---------------------------------------------------------------------
    // Inside the drawer: one layer per press
    // ---------------------------------------------------------------------

    /** A query is its own layer: it clears, leaving the drawer open in the view it was showing. */
    @Test
    public void backWithASearchQueryClearsItAndLeavesTheDrawerOpen() {
        relaunchWithDrawerOpen(0 /* Mode.LIST */);
        EditText searchBox = activity.findViewById(R.id.search_box);
        searchBox.setText("XYZ");
        ShadowLooper.idleMainLooper();

        back();

        assertEquals("", searchBox.getText().toString());
        assertFalse("clearing the query must not also close the drawer",
                activity.isHomeForTests());
    }

    /** ...and only the press after that leaves. */
    @Test
    public void backAfterClearingTheQueryClosesTheDrawer() {
        relaunchWithDrawerOpen(0 /* Mode.LIST */);
        EditText searchBox = activity.findViewById(R.id.search_box);
        searchBox.setText("XYZ");
        ShadowLooper.idleMainLooper();

        back();
        back();

        assertTrue(activity.isHomeForTests());
        assertFalse(activity.isFinishing());
    }

    /** Starts a fresh activity in {@code ordinal} with apps loaded and the drawer open. */
    private void relaunchWithDrawerOpen(int ordinal) {
        controller.close();
        Settings.setDrawerMode(ctx, ordinal);
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
        activity.applyLoadedApps(drawerApps());
        activity.openDrawer();
        ShadowLooper.idleMainLooper();
    }

    /** A handful of apps, so the drawer has something to list. */
    private static java.util.List<AppInfo> drawerApps() {
        java.util.List<AppInfo> out = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            for (int k = 0; k < 3; k++) {
                out.add(new AppInfo("App" + i + k, "com.example.h" + i + ".a" + k,
                        new android.graphics.drawable.ColorDrawable(0xFF808080)));
            }
        }
        return out;
    }

    /**
     * What the accidental relaunch used to do, now done on purpose. Read at the instant of the
     * call: an animated scroll has committed {@code currentPage} but not yet moved
     * {@code scrollX}, and idling the looper would re-lay the view out at Robolectric's window
     * size (see WidgetAddPagePlacementTest).
     */
    @Test
    public void backOnALaterPageReturnsToTheFirstPage() {
        for (int screen = 0; screen < 3; screen++) {
            store.addItem(HomeItem.app(screen, 0, 0, "com.example.app" + screen));
        }
        HomescreenView view = homescreen();
        view.refresh();
        layout(view);
        for (int i = 0; i < 2; i++) {
            long t = SystemClock.uptimeMillis();
            view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 900, 1200, 0));
            view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, 200, 1200, 0));
            view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP,   200, 1200, 0));
            ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
            view.computeScroll();
            layout(view);
        }
        assertEquals("test setup: should be on page 2", 2, view.getCurrentPage());

        back();

        assertEquals(0, view.getCurrentPage());
        assertFalse(activity.isFinishing());
    }
}
