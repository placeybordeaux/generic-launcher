package com.genericlauncher.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for first-run dock seeding: role resolution (phone/messages/browser/camera),
 * dedup, skip-if-missing, and the seed-at-most-once guard.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DefaultHomeSeederTest {

    private Context ctx;
    private ShadowPackageManager spm;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        HomeScreenStore.resetForTesting();
        ctx.getFilesDir(); // ensure files dir exists
        // Every homescreen.json* file, not just the live one: a leftover .tmp/.bak is a valid
        // recovery source and would quietly change what a load in this class sees.
        java.io.File[] leftovers = ctx.getFilesDir().listFiles();
        if (leftovers != null) {
            for (java.io.File f : leftovers) {
                if (f.getName().startsWith("homescreen.json")) f.delete();
            }
        }
        spm = Shadows.shadowOf(ctx.getPackageManager());
    }

    /** Registers pkg with a launcher activity plus a resolver for the given role intent. */
    private void installLaunchableApp(String pkg, Intent roleIntent) {
        ComponentName launcherCn = new ComponentName(pkg, pkg + ".Main");
        spm.addActivityIfNotPresent(launcherCn);
        IntentFilter launcherFilter = new IntentFilter(Intent.ACTION_MAIN);
        launcherFilter.addCategory(Intent.CATEGORY_LAUNCHER);
        spm.addIntentFilterForActivity(launcherCn, launcherFilter);

        if (roleIntent != null) {
            ResolveInfo ri = new ResolveInfo();
            ri.activityInfo = new ActivityInfo();
            ri.activityInfo.packageName = pkg;
            ri.activityInfo.name = pkg + ".RoleActivity";
            ri.activityInfo.applicationInfo = new ApplicationInfo();
            ri.activityInfo.applicationInfo.packageName = pkg;
            spm.addResolveInfoForIntent(roleIntent, ri);
        }
    }

    private static Intent dialIntent()    { return new Intent(Intent.ACTION_DIAL); }
    private static Intent smsIntent()     { return new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")); }
    private static Intent browseIntent()  { return new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")); }
    private static Intent cameraIntent()  { return new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA); }

    // --- resolveDockPackages ---

    @Test
    public void resolvesAllFourRoles() {
        installLaunchableApp("com.example.phone",   dialIntent());
        installLaunchableApp("com.example.sms",     smsIntent());
        installLaunchableApp("com.example.browser", browseIntent());
        installLaunchableApp("com.example.camera",  cameraIntent());

        List<String> pkgs = DefaultHomeSeeder.resolveDockPackages(ctx);
        assertEquals(4, pkgs.size());
        assertEquals("com.example.phone",   pkgs.get(0));
        assertEquals("com.example.sms",     pkgs.get(1));
        assertEquals("com.example.browser", pkgs.get(2));
        assertEquals("com.example.camera",  pkgs.get(3));
    }

    @Test
    public void missingRole_yieldsNullSlot_noSubstitute() {
        installLaunchableApp("com.example.phone", dialIntent());
        // no sms/browser/camera installed

        List<String> pkgs = DefaultHomeSeeder.resolveDockPackages(ctx);
        assertEquals(4, pkgs.size());
        assertEquals("com.example.phone", pkgs.get(0));
        assertNull(pkgs.get(1));
        assertNull(pkgs.get(2));
        assertNull(pkgs.get(3));
    }

    @Test
    public void duplicateResolution_keepsEarlierSlot() {
        // One app is both phone and sms handler.
        installLaunchableApp("com.example.comms", dialIntent());
        installLaunchableApp("com.example.comms", smsIntent());
        installLaunchableApp("com.example.browser", browseIntent());

        List<String> pkgs = DefaultHomeSeeder.resolveDockPackages(ctx);
        assertEquals("com.example.comms", pkgs.get(0));
        assertNull(pkgs.get(1)); // deduped, not repeated
        assertEquals("com.example.browser", pkgs.get(2));
    }

    @Test
    public void nonLaunchablePackage_isSkipped() {
        // Role resolver exists but the package has no launcher activity.
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = "com.example.headless";
        ri.activityInfo.name = "com.example.headless.RoleActivity";
        ri.activityInfo.applicationInfo = new ApplicationInfo();
        ri.activityInfo.applicationInfo.packageName = "com.example.headless";
        spm.addResolveInfoForIntent(dialIntent(), ri);

        List<String> pkgs = DefaultHomeSeeder.resolveDockPackages(ctx);
        assertNull(pkgs.get(0));
    }

    // --- seedIfNeeded ---

    @Test
    public void seedIfNeeded_populatesDock_andSetsFlag() {
        installLaunchableApp("com.example.phone",   dialIntent());
        installLaunchableApp("com.example.browser", browseIntent());

        HomeScreenStore store = HomeScreenStore.get(ctx);
        boolean seeded = DefaultHomeSeeder.seedIfNeeded(ctx, store);

        assertTrue(seeded);
        assertTrue(Settings.isHomeSeeded(ctx));
        List<HomeItem> dock = store.dockItems();
        assertEquals(2, dock.size());
        // phone in slot 0, browser in slot 2 (slot preserved even with gaps)
        assertEquals("com.example.phone", dock.get(0).getPackageName());
        assertEquals(0, dock.get(0).getCellX());
        assertEquals("com.example.browser", dock.get(1).getPackageName());
        assertEquals(2, dock.get(1).getCellX());
    }

    @Test
    public void seedIfNeeded_noOp_whenFlagAlreadySet() {
        installLaunchableApp("com.example.phone", dialIntent());
        Settings.setHomeSeeded(ctx, true);

        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertFalse(DefaultHomeSeeder.seedIfNeeded(ctx, store));
        assertTrue(store.dockItems().isEmpty());
    }

    @Test
    public void seedIfNeeded_noOp_onNonEmptyStore_butSetsFlag() {
        installLaunchableApp("com.example.phone", dialIntent());
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 1, 1, "com.example.existing"));

        assertFalse(DefaultHomeSeeder.seedIfNeeded(ctx, store));
        assertTrue("upgraders are marked seeded so they are never seeded later",
                Settings.isHomeSeeded(ctx));
        assertTrue(store.dockItems().isEmpty());
        assertEquals(1, store.getItems().size());
    }

    @Test
    public void seedIfNeeded_upgrader_alsoDismissesPlaceholder() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 1, 1, "com.example.existing"));

        DefaultHomeSeeder.seedIfNeeded(ctx, store);

        assertTrue("pill must never appear over a pre-existing layout",
                Settings.isSearchPlaceholderDismissed(ctx));
    }

    @Test
    public void seedIfNeeded_dockReachesDiskBeforeTheSeededFlagIsSet() {
        installLaunchableApp("com.example.phone", dialIntent());

        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertTrue(DefaultHomeSeeder.seedIfNeeded(ctx, store));
        assertTrue(Settings.isHomeSeeded(ctx));

        // Stand in for the process being killed the instant seedIfNeeded returns: reload purely
        // from disk, with no flush of our own. If the flag can outrun the queued write, the next
        // launch sees isHomeSeeded == true over an empty store and there is no dock, ever.
        HomeScreenStore.resetForTesting();
        assertEquals(1, HomeScreenStore.get(ctx).dockItems().size());
    }

    @Test
    public void seedIfNeeded_setsFlag_evenWhenNothingResolved() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        DefaultHomeSeeder.seedIfNeeded(ctx, store);
        assertTrue(Settings.isHomeSeeded(ctx));
        assertTrue(store.dockItems().isEmpty());
    }

    // --- reseed ---

    @Test
    public void reseed_clearsExistingLayout_andSeedsDefaults() {
        installLaunchableApp("com.example.phone", dialIntent());
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 1, 1, "com.example.old"));
        store.addItem(HomeItem.app(HomeItem.DOCK, 3, 0, "com.example.olddock"));
        Settings.setHomeSeeded(ctx, true);
        Settings.setSearchPlaceholderDismissed(ctx, true);

        DefaultHomeSeeder.reseed(ctx, store);

        assertTrue(Settings.isHomeSeeded(ctx));
        assertFalse("placeholder returns after reset",
                Settings.isSearchPlaceholderDismissed(ctx));
        assertEquals(1, store.getItems().size());
        assertEquals("com.example.phone", store.dockItems().get(0).getPackageName());
    }

    @Test
    public void reseed_worksWhenStoreEmpty() {
        installLaunchableApp("com.example.phone", dialIntent());
        HomeScreenStore store = HomeScreenStore.get(ctx);
        DefaultHomeSeeder.reseed(ctx, store);
        assertEquals(1, store.dockItems().size());
    }

    // -------------------------------------------------------------------------
    // S4 — the save suppression H7 added had no way out
    // -------------------------------------------------------------------------

    @Test
    public void reseed_afterAnUnreadableLoad_actuallyReachesDisk() throws Exception {
        // With homescreen.json unreadable every scheduleSave() is a no-op for the whole process,
        // so Settings → Reset Home Screen silently did nothing while still committing
        // home_seeded — the user rebuilt a layout that vanished on the next launch.
        installLaunchableApp("com.example.phone", dialIntent());
        java.io.File live = new java.io.File(ctx.getFilesDir(), "homescreen.json");
        writeFile(live, "this is not json {{{");

        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertTrue("precondition", store.isLoadCorrupt());

        DefaultHomeSeeder.reseed(ctx, store);

        assertFalse("an explicit reset is the user asking us to overwrite", store.isLoadCorrupt());
        assertTrue("the reseeded dock must survive the next launch", live.exists());
        assertTrue(readFile(live).contains("com.example.phone"));
    }

    private static void writeFile(java.io.File f, String content) throws Exception {
        try (java.io.FileWriter fw = new java.io.FileWriter(f)) { fw.write(content); }
    }

    private static String readFile(java.io.File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
