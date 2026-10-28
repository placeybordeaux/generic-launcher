package com.genericlauncher.launcher;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.LauncherApps;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLauncherApps;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * A tap on an app that can no longer be launched must not take the home screen down.
 *
 * <p>The app list is a snapshot and the world moves under it. A work-profile app updated so
 * that its main activity is no longer a launcher activity makes
 * {@code LauncherApps.startMainActivity} throw {@code SecurityException}; a personal app
 * disabled between load and tap makes {@code startActivity} throw
 * {@code ActivityNotFoundException}. Both are unchecked and both land on the main thread from a
 * click listener, where the framework's answer is process death — and this process is the HOME
 * app. Deep shortcuts already caught their launch; plain app launches did not, on either the
 * home screen or the drawer.
 *
 * <p>The work-profile tests go through a real tap on a real home-screen icon rather than calling
 * the launch helper directly, and the cooperating-host control checks the tap actually reached
 * {@code startMainActivity}. That control fails on the old listener for a second reason: it
 * looked the tapped key up in a map captured when the view was first built, which
 * {@code ensureHomescreenView} never rebuilt, so any app that arrived with a later load — as
 * this one does — was not found and fell through to a personal launch intent that a work-only
 * package does not have.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, shadows = AppLaunchFailureTest.ShadowRefusingLauncherApps.class)
public class AppLaunchFailureTest {

    /**
     * Robolectric's {@code ShadowLauncherApps} does not shadow {@code startMainActivity}, so the
     * real method runs against a null-proxy binder and silently succeeds. This one records the
     * call and, when told to, refuses it the way the framework does once the component has
     * stopped being a launcher activity.
     */
    @Implements(LauncherApps.class)
    public static class ShadowRefusingLauncherApps extends ShadowLauncherApps {
        static boolean refuse;
        static ComponentName started;

        @Implementation
        protected void startMainActivity(ComponentName component, UserHandle user,
                                         Rect sourceBounds, Bundle opts) {
            started = component;
            if (refuse) {
                throw new SecurityException(
                        "Attempt to launch activity without  category Intent.CATEGORY_LAUNCHER "
                                + component);
            }
        }
    }

    private static final String WORK_KEY   = "com.example.work#10";
    private static final String WORK_LABEL = "Work Chrome";
    private static final ComponentName WORK_COMPONENT =
            new ComponentName("com.example.work", "com.example.work.Main");

    private Context ctx;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        Settings.setHomeSeeded(ctx, true);
        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
        new File(ctx.getFilesDir(), "homescreen.json.tmp").delete();
        ShadowToast.reset();
        ShadowRefusingLauncherApps.refuse  = false;
        ShadowRefusingLauncherApps.started = null;

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
        ShadowRefusingLauncherApps.refuse  = false;
        ShadowRefusingLauncherApps.started = null;
    }

    // -------------------------------------------------------------------------
    // Home screen, work profile
    // -------------------------------------------------------------------------

    @Test
    public void tappingAWorkAppWhoseLauncherActivityIsGoneShowsAToastInsteadOfCrashing() {
        View icon = workAppOnTheHomeScreen();
        ShadowRefusingLauncherApps.refuse = true;

        icon.performClick();

        assertEquals("test setup: the tap must have reached the launch that refuses it",
                WORK_COMPONENT, ShadowRefusingLauncherApps.started);
        assertEquals("Couldn't open " + WORK_LABEL, ShadowToast.getTextOfLatestToast());
        assertFalse("and the home screen is still up", activity.isFinishing());
    }

    /** Control: the same tap on a cooperating profile launches, and says nothing. */
    @Test
    public void tappingAWorkAppLaunchesItThroughLauncherApps() {
        View icon = workAppOnTheHomeScreen();

        icon.performClick();

        assertEquals(WORK_COMPONENT, ShadowRefusingLauncherApps.started);
        assertEquals("nothing to report on a launch that worked", 0, ShadowToast.shownToastCount());
    }

    // -------------------------------------------------------------------------
    // Drawer, personal profile
    // -------------------------------------------------------------------------

    /** A Context whose {@code startActivity} is the framework's when the activity has gone. */
    private static final class VanishedActivityContext extends ContextWrapper {
        Intent attempted;

        VanishedActivityContext(Context base) { super(base); }

        @Override
        public void startActivity(Intent intent) {
            attempted = intent;
            throw new ActivityNotFoundException("Unable to find explicit activity class "
                    + intent.getComponent());
        }
    }

    @Test
    public void launchingAPersonalAppWhoseActivityVanishedShowsAToastInsteadOfCrashing() {
        // A package the resolver still knows a launch intent for, so the failure is
        // startActivity's rather than a null intent that never gets that far.
        ComponentName main = new ComponentName("com.example.gone", "com.example.gone.Main");
        ShadowPackageManager spm = shadowOf(activity.getPackageManager());
        spm.addActivityIfNotPresent(main);
        IntentFilter launcher = new IntentFilter(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        spm.addIntentFilterForActivity(main, launcher);
        assertNotNull("test setup: the package resolves to a launch intent",
                activity.getPackageManager().getLaunchIntentForPackage("com.example.gone"));

        VanishedActivityContext context = new VanishedActivityContext(activity);
        AppInfo app = new AppInfo("Gone", "com.example.gone", new ColorDrawable(0xFF336699));

        AppAdapter.launchApp(context, app);

        assertNotNull("test setup: the launch must have reached startActivity", context.attempted);
        assertEquals("Couldn't open Gone", ShadowToast.getTextOfLatestToast());
    }

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    /**
     * Puts one work app on page 0 and delivers it through {@code applyLoadedApps}, the way a
     * reload does, then returns the tappable icon view the home screen built for it.
     */
    private View workAppOnTheHomeScreen() {
        HomeScreenStore.get(activity).addItem(HomeItem.app(0, 0, 0, WORK_KEY));

        AppInfo work = new AppInfo(WORK_LABEL, WORK_KEY, new ColorDrawable(0xFF4285F4));
        work.setWork(true);
        work.setUser(UserHandle.getUserHandleForUid(10 * 100000));
        work.setComponentName(WORK_COMPONENT);
        List<AppInfo> loaded = new ArrayList<>();
        loaded.add(work);

        HomescreenView view = awaitHomescreenView();
        assertNotNull("test setup: MainActivity should have built its HomescreenView", view);
        activity.applyLoadedApps(loaded);
        ShadowLooper.idleMainLooper();

        TextView label = findLabel(view, WORK_LABEL);
        assertNotNull("test setup: the home screen should show the work app", label);
        // buildAppView's click listener is on the icon+label column, one level up.
        View icon = (View) label.getParent();
        assertTrue("test setup: the icon is what takes the tap", icon.hasOnClickListeners());
        return icon;
    }

    /** See WidgetAddPagePlacementTest#awaitHomescreenView for why this waits for presence. */
    private HomescreenView awaitHomescreenView() {
        for (int attempt = 0; attempt < 200; attempt++) {
            ShadowLooper.idleMainLooper();
            HomescreenView found = findHomescreenView(activity.findViewById(android.R.id.content));
            if (found != null) return found;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private static HomescreenView findHomescreenView(View root) {
        if (root instanceof HomescreenView) return (HomescreenView) root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                HomescreenView found = findHomescreenView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findLabel(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) {
            return (TextView) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = findLabel(g.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
