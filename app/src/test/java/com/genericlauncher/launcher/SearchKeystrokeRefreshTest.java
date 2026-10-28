package com.genericlauncher.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.widget.EditText;
import android.widget.FrameLayout;

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
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * The home screen must not be rebuilt for a search keystroke.
 *
 * <p>{@code MainActivity.afterTextChanged} calls {@code renderMode()}, and {@code renderMode()}
 * used to hand the existing {@link HomescreenView} a fresh app map on every call — which is a
 * full {@code refresh()}: every page rebuilt, and for every widget a {@code getAppWidgetInfo}
 * binder call plus a {@code host.createView} RemoteViews inflation, on the main thread, behind
 * the drawer that hides the result. Per character typed.
 *
 * <p>The count is read from the view itself rather than inferred from what is on screen, because
 * a redundant refresh rebuilds the same pages it tore down: the screen after it is
 * indistinguishable from the screen before it, and only the call count can tell the two apart.
 *
 * <p>Animations are switched off for the whole class so {@code goHome()} rebuilds synchronously —
 * otherwise the refresh a pinned widget needs arrives 180 ms later from {@code finishDrawerClose},
 * and "the refresh never happened" and "the refresh has not happened yet" read the same.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SearchKeystrokeRefreshTest {

    private static final int WIDGET_ID = 41;
    private static final String PROVIDER = "com.example.widget/.Provider";

    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        Settings.setHomeSeeded(ctx, true); // no first-run seeding deciding what is on the pages
        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
        new File(ctx.getFilesDir(), "homescreen.json.tmp").delete();
        android.provider.Settings.Global.putFloat(ctx.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f);

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();

        // onCreate kicked off a real background load. Its two results would land on the main
        // looper at a time that depends on the machine, each one a legitimate refresh — so they
        // are dropped, and every load in this class is applied by hand.
        if (activity.priorityCallbackForTests() != null) activity.priorityCallbackForTests().detach();
        if (activity.loadCallbackForTests() != null) activity.loadCallbackForTests().detach();
        ShadowLooper.idleMainLooper();
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    private static AppInfo app(String pkg, float hue) {
        return new AppInfo(pkg, pkg, new ColorDrawable(0xFF808080));
    }

    private static List<AppInfo> apps() {
        List<AppInfo> out = new ArrayList<>();
        out.add(app("com.example.alpha", 10f));
        out.add(app("com.example.beta", 120f));
        out.add(app("com.example.gamma", 220f));
        return out;
    }

    /** The first load lands, which is what builds the HomescreenView in the first place. */
    private HomescreenView firstLoad() {
        activity.applyLoadedApps(apps());
        ShadowLooper.idleMainLooper();
        HomescreenView view = activity.homescreenViewForTests();
        assertNotNull("the first load should have built the HomescreenView", view);
        return view;
    }

    private EditText searchBox() {
        return activity.findViewById(R.id.search_box);
    }

    private void type(String text) {
        searchBox().setText(text);
        ShadowLooper.idleMainLooper();
    }

    // -------------------------------------------------------------------------
    // The regression
    // -------------------------------------------------------------------------

    @Test
    public void typingInTheSearchBoxDoesNotRefreshTheHomescreen() {
        HomescreenView view = firstLoad();
        activity.openDrawer();
        ShadowLooper.idleMainLooper();
        int before = view.refreshCountForTest();

        // Positive control on the keystroke path itself: renderMode() rebuilds the drawer
        // overlay from scratch on every call, so a fresh overlay per keystroke proves the
        // keystrokes did reach renderMode() and the count below is measuring something.
        FrameLayout overlayBefore = activity.drawerOverlayForTests();
        assertNotNull("test setup: the drawer should be open", overlayBefore);

        type("a");
        type("al");
        type("alp");
        type("al");   // backspace
        type("");     // cleared

        assertNotSame("the keystrokes never reached renderMode()",
                overlayBefore, activity.drawerOverlayForTests());
        assertEquals("a search keystroke changes nothing the home screen shows, so it must "
                + "not rebuild it", before, view.refreshCountForTest());
    }

    @Test
    public void switchingDrawerModeAndTogglingHeadersDoNotRefreshTheHomescreen() {
        HomescreenView view = firstLoad();
        activity.openDrawer();
        ShadowLooper.idleMainLooper();
        int before = view.refreshCountForTest();

        activity.findViewById(R.id.mode_switch).performClick();
        activity.findViewById(R.id.toggle_view).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(before, view.refreshCountForTest());
    }

    @Test
    public void openingAndClosingTheDrawerDoesNotRefreshTheHomescreen() {
        HomescreenView view = firstLoad();
        int before = view.refreshCountForTest();

        activity.openDrawer();
        ShadowLooper.idleMainLooper();
        activity.onBackPressed();
        ShadowLooper.idleMainLooper();

        assertTrue("test setup: back should have closed the drawer", activity.isHomeForTests());
        assertEquals(before, view.refreshCountForTest());
    }

    // -------------------------------------------------------------------------
    // Positive controls: the refreshes that are owed still happen, exactly once each
    // -------------------------------------------------------------------------

    @Test
    public void aCompletedLoadStillRefreshesExactlyOnce() {
        HomescreenView view = firstLoad();
        activity.openDrawer();
        ShadowLooper.idleMainLooper();
        int before = view.refreshCountForTest();

        activity.applyLoadedApps(apps());
        ShadowLooper.idleMainLooper();

        assertEquals(before + 1, view.refreshCountForTest());
    }

    @Test
    public void aPriorityLoadStillRefreshesExactlyOnce() {
        HomescreenView view = firstLoad();
        int before = view.refreshCountForTest();

        activity.applyPriorityApps(apps());
        ShadowLooper.idleMainLooper();

        assertEquals(before + 1, view.refreshCountForTest());
    }

    /**
     * The one refresh that a keystroke used to pay for and something else still needs: an item
     * added by this Activity rather than by the view. Without it, "Add to Home screen" returns
     * the user to a home screen that does not yet show the app they just pinned.
     */
    @Test
    public void pinningAnAppFromTheDrawerStillRefreshes() {
        HomescreenView view = firstLoad();
        activity.openDrawer();
        ShadowLooper.idleMainLooper();
        int before = view.refreshCountForTest();

        activity.onAddToHome(app("com.example.pinned", 300f));
        ShadowLooper.idleMainLooper();

        assertTrue("pinning returns to the home screen", activity.isHomeForTests());
        assertEquals(before + 1, view.refreshCountForTest());
    }

    /** Same shape as pinning, on the widget flow, which reaches the store through finalizeWidget. */
    @Test
    public void addingAWidgetStillRefreshes() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity)).addBoundWidget(WIDGET_ID, provider);

        HomescreenView view = firstLoad();
        activity.openDrawer();
        ShadowLooper.idleMainLooper();
        int before = view.refreshCountForTest();

        activity.beginPendingWidget(WIDGET_ID);
        activity.finalizeWidget();
        ShadowLooper.idleMainLooper();

        assertEquals(before + 1, view.refreshCountForTest());
    }
}
