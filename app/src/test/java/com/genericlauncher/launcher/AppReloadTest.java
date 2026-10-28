package com.genericlauncher.launcher;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * How the launcher reacts to package broadcasts.
 *
 * <p>Reloading used to mean {@code progress} up and {@code contentContainer} gone — and
 * contentContainer is where the {@link HomescreenView} lives, so the whole home screen vanished
 * every time an app was installed, updated or changed in the background. A store batch update
 * did that repeatedly for minutes. Reloads must now repaint in place, arrive coalesced, and be
 * dropped entirely once the Activity is gone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AppReloadTest {

    private Context ctx;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        HomeScreenStore.resetForTesting();
        new java.io.File(ctx.getFilesDir(), "homescreen.json").delete();

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    private View content()  { return activity.findViewById(R.id.content_container); }
    private View progress() { return activity.findViewById(R.id.progress); }

    private void broadcastPackage(String action, String pkg) {
        ctx.sendBroadcast(new Intent(action, Uri.parse("package:" + pkg)));
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** The filter MainActivity registered for package events. */
    private IntentFilter packageFilter() {
        ShadowApplication app = shadowOf((Application) ctx);
        for (ShadowApplication.Wrapper w : app.getRegisteredReceivers()) {
            if (w.getIntentFilter().hasAction(Intent.ACTION_PACKAGE_ADDED)) {
                return w.getIntentFilter();
            }
        }
        fail("no receiver registered for ACTION_PACKAGE_ADDED");
        return null;
    }

    // -------------------------------------------------------------------------
    // H2 — the home screen must not blank on a reload
    // -------------------------------------------------------------------------

    @Test
    public void aReloadAfterTheFirstOneDoesNotBlankTheHomeScreen() {
        activity.applyLoadedApps(new ArrayList<>()); // the first load lands
        assertEquals("precondition", View.VISIBLE, content().getVisibility());

        activity.loadApps();

        assertEquals("hiding contentContainer hides the HomescreenView with it",
                View.VISIBLE, content().getVisibility());
        assertEquals("no spinner over a home screen that already has content",
                View.GONE, progress().getVisibility());
    }

    @Test
    public void repeatedReloadsNeverBlankTheHomeScreen() {
        activity.applyLoadedApps(new ArrayList<>());

        for (int i = 0; i < 5; i++) {
            activity.loadApps();
            assertEquals(View.VISIBLE, content().getVisibility());
        }
    }

    @Test
    public void aCompletedReloadLeavesTheContentVisible() {
        activity.applyLoadedApps(new ArrayList<>());
        activity.loadApps();

        activity.applyLoadedApps(new ArrayList<>());

        assertEquals(View.VISIBLE, content().getVisibility());
        assertEquals(View.GONE, progress().getVisibility());
    }

    // -------------------------------------------------------------------------
    // H2 — broadcasts are coalesced, and PACKAGE_CHANGED only counts for a whole app
    // -------------------------------------------------------------------------

    @Test
    public void aPackageBroadcastSchedulesACoalescedReloadInsteadOfReloadingNow() {
        activity.applyLoadedApps(new ArrayList<>());

        broadcastPackage(Intent.ACTION_PACKAGE_ADDED, "com.example.installed");

        assertTrue("the receiver must go through the debounce, not call loadApps directly",
                activity.reloadCoalescerForTests().hasPendingRequest());
    }

    @Test
    public void aBurstOfPackageBroadcastsLeavesASinglePendingReload() {
        activity.applyLoadedApps(new ArrayList<>());

        for (int i = 0; i < 10; i++) {
            broadcastPackage(Intent.ACTION_PACKAGE_REPLACED, "com.example.app" + i);
        }

        assertTrue(activity.reloadCoalescerForTests().hasPendingRequest());
        assertEquals("and the home screen stayed up throughout",
                View.VISIBLE, content().getVisibility());
    }

    @Test
    public void packageChangedIsWatchedForDisableAndEnable() {
        IntentFilter f = packageFilter();

        assertTrue(f.hasAction(Intent.ACTION_PACKAGE_ADDED));
        assertTrue(f.hasAction(Intent.ACTION_PACKAGE_REMOVED));
        assertTrue(f.hasAction(Intent.ACTION_PACKAGE_REPLACED));
        assertTrue("disabling an app is a PACKAGE_CHANGED and nothing else; without it a "
                        + "disabled app stayed in the drawer and on the home screen",
                f.hasAction(Intent.ACTION_PACKAGE_CHANGED));
        assertTrue("still scoped to package: URIs", f.hasDataScheme("package"));
    }

    @Test
    public void aComponentLevelPackageChangedDoesNotReload() {
        // Apps flip their own components on and off routinely in the background; that never
        // changed the launchable list and must not cost a reload.
        activity.applyLoadedApps(new ArrayList<>());
        assertFalse("precondition", activity.reloadCoalescerForTests().hasPendingRequest());

        broadcastChanged("com.example.app", "com.example.app.SyncService");

        assertFalse(activity.reloadCoalescerForTests().hasPendingRequest());
    }

    @Test
    public void disablingOrEnablingAnAppReloads() {
        activity.applyLoadedApps(new ArrayList<>());
        assertFalse("precondition", activity.reloadCoalescerForTests().hasPendingRequest());

        // The platform's signal for the app itself: its own name in the changed list.
        broadcastChanged("com.google.android.youtube", "com.google.android.youtube");

        assertTrue(activity.reloadCoalescerForTests().hasPendingRequest());
    }

    @Test
    public void isWholePackageChangeReadsTheChangedComponentList() {
        assertTrue(MainActivity.isWholePackageChange(changed("com.a", "com.a.Main", "com.a")));
        assertFalse(MainActivity.isWholePackageChange(changed("com.a", "com.a.Main")));
        assertFalse("no list at all is not a whole-package change",
                MainActivity.isWholePackageChange(
                        new Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:com.a"))));
        assertFalse("a different package's name does not count",
                MainActivity.isWholePackageChange(changed("com.a", "com.b")));
    }

    private static Intent changed(String pkg, String... components) {
        Intent i = new Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:" + pkg));
        i.putExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST, components);
        return i;
    }

    private void broadcastChanged(String pkg, String... components) {
        ctx.sendBroadcast(changed(pkg, components));
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void anUninstallStillPrunesTheHomeScreenImmediately() {
        // The cheap prune must stay inline — only the expensive reload is debounced.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.example.doomed"));
        assertEquals(1, store.getItems().size());

        broadcastPackage(Intent.ACTION_PACKAGE_REMOVED, "com.example.doomed");

        assertTrue("the ghost cell must be gone before the debounce elapses",
                store.getItems().isEmpty());
    }

    @Test
    public void onDestroyDropsAPendingReload() {
        broadcastPackage(Intent.ACTION_PACKAGE_ADDED, "com.example.installed");
        assertTrue("precondition", activity.reloadCoalescerForTests().hasPendingRequest());

        controller.destroy();

        assertFalse("a delayed reload must not fire against a destroyed Activity",
                activity.reloadCoalescerForTests().hasPendingRequest());
    }

    // -------------------------------------------------------------------------
    // M4 — a load result must never reach a destroyed Activity
    // -------------------------------------------------------------------------

    @Test
    public void onDestroyReleasesTheInFlightLoadCallback() {
        activity.loadApps();
        MainActivity.LoadCallback cb = activity.loadCallbackForTests();
        assertNotNull(cb);
        assertTrue("precondition", cb.holdsActivity());

        controller.destroy();

        assertFalse("the dead Activity, its view tree and every icon Drawable must be collectable",
                cb.holdsActivity());
    }

    @Test
    public void aResultArrivingAfterDestroyIsDropped() {
        MainActivity.LoadCallback cb = new MainActivity.LoadCallback(activity);
        View content = content();
        content.setVisibility(View.GONE);
        controller.destroy();

        cb.onAppsLoaded(new ArrayList<>());

        assertEquals("a destroyed Activity's views must not be mutated",
                View.GONE, content.getVisibility());
    }

    @Test
    public void aDetachedCallbackDropsItsResult() {
        MainActivity.LoadCallback cb = new MainActivity.LoadCallback(activity);
        View content = content();
        content.setVisibility(View.GONE);

        cb.detach();
        cb.onAppsLoaded(new ArrayList<>());

        assertEquals(View.GONE, content.getVisibility());
    }

    @Test
    public void startingANewLoadDetachesTheOneItSupersedes() {
        activity.loadApps();
        MainActivity.LoadCallback stale = activity.loadCallbackForTests();

        activity.loadApps();

        assertNotSame(stale, activity.loadCallbackForTests());
        assertFalse("a stale result must not repaint over a newer one", stale.holdsActivity());
    }

    // -------------------------------------------------------------------------
    // B1 — a partial enumeration must not be read as a mass uninstall
    // -------------------------------------------------------------------------

    @Test
    public void applyLoadedApps_keepsWorkIconsWhenTheWorkProfileEnumeratedNothing() {
        // A paused / quiet / locked work profile is still listed by getUserProfiles() but throws
        // from getActivityList, so the result carries no "#10" key at all. Deleting the items on
        // that basis wiped every work icon on the home screen, and the prune persists.
        registerProfile(10, 10L);
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.example.work#10"));
        store.addItem(HomeItem.app(0, 1, 0, "com.example.personal"));

        activity.applyLoadedApps(appsWithKeys("com.example.personal"));

        assertEquals("a paused work profile must not cost the user their work icons",
                2, store.getItems().size());
    }

    @Test
    public void applyLoadedApps_stillPrunesGhostsFromProfilesThatDidEnumerate() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.example.ghost"));
        store.addItem(HomeItem.app(0, 1, 0, "com.example.alive"));

        activity.applyLoadedApps(appsWithKeys("com.example.alive"));

        assertEquals(1, store.getItems().size());
        assertEquals("com.example.alive", store.getItems().get(0).getPackageName());
    }

    /** Makes {@code UserManager.getUserProfiles()} report a second profile with this serial. */
    private void registerProfile(int userId, long serial) {
        UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        UserHandle handle = UserHandle.getUserHandleForUid(userId * 100000);
        shadowOf(um).addUserProfile(handle);
        shadowOf(um).setSerialNumberForUser(handle, serial);
    }

    /** An app list as {@code AppLoader} would deliver it — only the key matters here. */
    private static List<AppInfo> appsWithKeys(String... keys) {
        List<AppInfo> apps = new ArrayList<>();
        for (String key : keys) apps.add(new AppInfo(key, key, null));
        return apps;
    }
}
