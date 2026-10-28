package com.genericlauncher.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * M2 — a widget whose {@code getAppWidgetInfo} comes back null must only be deleted when its
 * provider package is genuinely gone.
 *
 * <p>Null is also what the platform returns for a suspended package (Digital Wellbeing, device
 * policy), an app on unmounted storage, and the window in the middle of PACKAGE_REPLACED. The old
 * code treated all of those as "uninstalled", removed the item and persisted immediately — so
 * unpausing an app never brought its widget back. It also never paired the removal with
 * {@code host.deleteAppWidgetId}, leaking an id per removal.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class StaleWidgetProviderTest {

    private static final String PROVIDER_PKG = "com.example.clock";
    private static final String PROVIDER     = PROVIDER_PKG + "/.ClockProvider";
    private static final int    WIDGET_ID    = 7;

    private static final int W = 1080;
    private static final int H = 2424;

    private ActivityController<Activity> controller;
    private Activity        activity;
    private FrameLayout     root;
    private HomeScreenStore store;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        root       = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();
        store = HomeScreenStore.get(activity);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    /** Makes {@code pkg} look installed to PackageManager without publishing any widget provider. */
    private void installPackage(String pkg) {
        PackageInfo info = new PackageInfo();
        info.packageName = pkg;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = pkg;
        ShadowPackageManager spm = Shadows.shadowOf(activity.getPackageManager());
        spm.installPackage(info);
    }

    // ---------------------------------------------------------------------
    // The provider-package decision
    // ---------------------------------------------------------------------

    @Test
    public void providerPackageIsTakenFromTheFlattenedComponent() {
        assertEquals(PROVIDER_PKG, HomescreenView.providerPackageOf(PROVIDER));
    }

    @Test
    public void aBarePackageStringIsAcceptedAsIs() {
        assertEquals(PROVIDER_PKG, HomescreenView.providerPackageOf(PROVIDER_PKG));
    }

    @Test
    public void anAbsentProviderStringYieldsNoPackage() {
        assertNull(HomescreenView.providerPackageOf(null));
        assertNull(HomescreenView.providerPackageOf(""));
    }

    @Test
    public void anInstalledPackageIsNotAbsent() {
        installPackage(PROVIDER_PKG);
        assertFalse(HomescreenView.isPackageAbsent(activity.getPackageManager(), PROVIDER_PKG));
    }

    @Test
    public void anUninstalledPackageIsAbsent() {
        assertTrue(HomescreenView.isPackageAbsent(
                activity.getPackageManager(), "com.example.definitely.not.installed"));
    }

    @Test
    public void anUnknownPackageNameIsNeverTreatedAsAbsent() {
        // A layout entry with no usable provider string cannot prove anything, and the cost of a
        // wrong "absent" is a permanently deleted widget.
        PackageManager pm = activity.getPackageManager();
        assertFalse(HomescreenView.isPackageAbsent(pm, null));
        assertFalse(HomescreenView.isPackageAbsent(pm, ""));
        assertFalse(HomescreenView.isPackageAbsent(null, PROVIDER_PKG));
    }

    // ---------------------------------------------------------------------
    // What that means for a render pass
    // ---------------------------------------------------------------------

    /** Builds and lays out a HomescreenView over a store holding one unbound widget. */
    private HomescreenView renderWithUnboundWidget() {
        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, PROVIDER));

        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 1024));
        root.addView(view, W, H);
        view.refresh();
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
        return view;
    }

    @Test
    public void aSuspendedProviderKeepsItsWidgetAndShowsAPlaceholder() {
        // Package present, but getAppWidgetInfo returns null (nothing bound this id).
        installPackage(PROVIDER_PKG);

        HomescreenView view = renderWithUnboundWidget();

        assertEquals("the widget must survive a transient null provider",
                1, store.getItems().size());
        assertEquals("and something must hold its cells", 1, view.getPage(0).getChildCount());
    }

    @Test
    public void anUninstalledProviderDropsItsWidget() {
        // PROVIDER_PKG deliberately not installed.
        renderWithUnboundWidget();

        assertEquals("a genuinely uninstalled provider's widget is dropped",
                0, store.getItems().size());
    }

    @Test
    public void aWidgetWithNoProviderStringIsKept() {
        // Older layout files carry an empty provider; absence cannot be proven, so keep it.
        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, ""));

        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 1024));
        root.addView(view, W, H);
        view.refresh();

        assertEquals(1, store.getItems().size());
    }

    @Test
    public void droppingAWidgetReleasesItsHostId() {
        // The drag-to-remove path and SettingsActivity's reset both call deleteAppWidgetId; this
        // path did not, so every stale-widget drop leaked an id for the install's lifetime.
        RecordingHost host = new RecordingHost(activity);
        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, PROVIDER));

        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(host);
        root.addView(view, W, H);
        view.refresh();

        assertEquals("test setup: the widget should have been dropped", 0, store.getItems().size());
        assertEquals("the host id must be released with the model entry",
                Collections.singletonList(WIDGET_ID), host.deleted);
    }

    @Test
    public void aKeptWidgetDoesNotReleaseItsHostId() {
        installPackage(PROVIDER_PKG);
        RecordingHost host = new RecordingHost(activity);
        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, PROVIDER));

        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(host);
        root.addView(view, W, H);
        view.refresh();

        assertTrue("a suspended provider must not have its id freed", host.deleted.isEmpty());
    }

    /** An {@link AppWidgetHost} that records deallocations instead of talking to the system. */
    private static final class RecordingHost extends AppWidgetHost {
        final List<Integer> deleted = new ArrayList<>();

        RecordingHost(Context context) { super(context, 1024); }

        @Override
        public void deleteAppWidgetId(int appWidgetId) { deleted.add(appWidgetId); }
    }
}
