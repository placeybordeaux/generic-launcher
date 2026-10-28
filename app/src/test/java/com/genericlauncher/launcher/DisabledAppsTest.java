package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * A system app (YouTube) cannot be uninstalled, only disabled, and a disabled app leaves the
 * drawer and home screen as an uninstalled one does.
 *
 * <p>Found on the phone 2026-10-26: "Uninstall" on YouTube did nothing useful, and after
 * disabling it from App info its icon stayed put until some unrelated reload.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DisabledAppsTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
    }

    private static AppInfo app(String key, boolean system, boolean work) {
        AppInfo a = new AppInfo(key, key, null);
        a.setSystem(system);
        a.setWork(work);
        return a;
    }

    private static List<AppInfo> loaded(String... keys) {
        List<AppInfo> out = new ArrayList<>();
        for (String k : keys) out.add(app(k, false, false));
        return out;
    }

    // --- what the menus and the drag bar offer ---

    @Test
    public void aSystemAppIsOfferedDisableNotUninstall() {
        assertEquals(AppRemoval.Kind.DISABLE, AppRemoval.kindFor(app("youtube", true, false)));
        assertEquals("Disable", AppRemoval.labelFor(app("youtube", true, false)));
    }

    @Test
    public void anOrdinaryAppIsStillOfferedUninstall() {
        assertEquals(AppRemoval.Kind.UNINSTALL, AppRemoval.kindFor(app("com.x", false, false)));
        assertEquals("Uninstall", AppRemoval.labelFor(app("com.x", false, false)));
    }

    @Test
    public void aWorkAppIsOfferedNeither() {
        assertNull(AppRemoval.labelFor(app("com.x#10", false, true)));
        assertNull(AppRemoval.labelFor(app("com.x#10", true, true)));
        assertNull(AppRemoval.labelFor(null));
    }

    @Test
    public void bothLabelsRouteToTheRemovalAction() {
        assertTrue(AppRemoval.isRemovalLabel("Uninstall"));
        assertTrue(AppRemoval.isRemovalLabel("Disable"));
        assertFalse(AppRemoval.isRemovalLabel("App info"));
    }

    @Test
    public void theDragBarOffersDisableForASystemApp() {
        HomeItem item = HomeItem.app(0, 0, 0, "youtube");
        assertEquals(Arrays.asList(HomescreenView.DropTarget.Zone.REMOVE,
                        HomescreenView.DropTarget.Zone.DISABLE),
                Arrays.asList(HomescreenView.availableActions(
                        item, app("youtube", true, false), true)));
    }

    // --- the full load's prune ---

    @Test
    public void aDisabledAppIsPrunedFromHomeLikeAnUninstalledOne() {
        // Disabled apps are absent from getActivityList, and that is all the prune looks at.
        // Deliberate: disabling is how a user gets rid of an app they cannot uninstall.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.google.android.youtube"));
        store.addItem(HomeItem.folder(0, 1, 0, "F",
                Arrays.asList("com.here", "com.google.android.youtube", "com.there")));
        store.addItem(HomeItem.app(0, 2, 0, "com.here"));

        assertTrue(store.removeUnknownApps(ctx, loaded("com.here", "com.there")));

        List<String> apps = new ArrayList<>();
        List<String> folderMembers = null;
        for (HomeItem i : store.getItems()) {
            if (i.getType() == HomeItem.Type.FOLDER) folderMembers = i.getPackageNames();
            else apps.add(i.getPackageName());
        }
        assertEquals(Arrays.asList("com.here"), apps);
        assertEquals(Arrays.asList("com.here", "com.there"), folderMembers);
    }
}
