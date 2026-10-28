package com.genericlauncher.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.View;

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

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * The Home key is the reset key: whatever is over the home screen goes away.
 *
 * <p>It arrives as a new intent on the singleTask HOME activity, so {@code onNewIntent} is the
 * whole handler. It closed the drawer, the chooser, the search query and an open folder — but not
 * an open resize frame, which then stayed up over whatever page it was opened on and swallowed the
 * next touch anywhere on the screen dismissing itself (QA R4). A long-press on empty wallpaper
 * straight after Home therefore produced no menu, which reads as the launcher having stopped
 * responding rather than as a frame nobody could see was still open.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class HomeKeyTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int WIDGET_ID = 21;
    private static final String PROVIDER = "com.example.resizable/.Provider";

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

    /** What the system delivers when Home is pressed on the activity that already holds the role. */
    private void pressHome() {
        controller.newIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
    }

    private HomescreenView homescreen() {
        HomescreenView view = activity.homescreenViewForTests();
        assertNotNull("test setup: the home screen must be built", view);
        return view;
    }

    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    /** A 2x2 freely-resizable widget on page 0, with its resize frame open. */
    private HomescreenView withAnOpenResizeFrame() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);

        HomeItem widget = HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, PROVIDER);
        store.addItem(widget);
        HomescreenView view = homescreen();
        view.refresh();
        layout(view);

        view.showResizeFrame(widget);
        assertTrue("test setup: the frame must be open", view.isResizeFrameOpen());
        return view;
    }

    @Test
    public void theHomeKeyClosesAnOpenResizeFrame() {
        HomescreenView view = withAnOpenResizeFrame();

        pressHome();

        assertFalse("Home must close the resize frame, or the next touch anywhere is spent on it",
                view.isResizeFrameOpen());
    }

    /** ...including when it is open underneath the drawer, which Home closes in the same press. */
    @Test
    public void theHomeKeyClosesAResizeFrameLeftOpenUnderTheDrawer() {
        HomescreenView view = withAnOpenResizeFrame();
        activity.openDrawer();
        assertFalse("test setup: the drawer should be open", activity.isHomeForTests());

        pressHome();

        assertTrue(activity.isHomeForTests());
        assertFalse("the frame must not be handed back when the drawer closes",
                view.isResizeFrameOpen());
    }

    // Positive controls: the layers Home already closed still close.

    @Test
    public void theHomeKeyStillClosesAnOpenFolder() {
        HomeItem folder = HomeItem.folder(0, 0, 1, "Folder",
                Arrays.asList("com.example.first", "com.example.second"));
        store.addItem(folder);
        HomescreenView view = homescreen();
        view.refresh();
        layout(view);
        view.openFolderPopup(folder);
        assertTrue("test setup", view.isFolderPopupOpen());

        pressHome();

        assertFalse(view.isFolderPopupOpen());
    }

    @Test
    public void theHomeKeyStillClosesTheDrawer() {
        activity.openDrawer();
        assertFalse("test setup", activity.isHomeForTests());

        pressHome();

        assertTrue(activity.isHomeForTests());
    }
}
