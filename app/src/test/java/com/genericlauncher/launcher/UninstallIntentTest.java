package com.genericlauncher.launcher;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import static org.junit.Assert.*;

/**
 * The uninstall intent fired by the Uninstall pill (and the deep-shortcut popup entry).
 *
 * <p>The trap being pinned here: home items carry {@link UserKeys} composite package keys
 * ({@code pkg#serial}) for work-profile apps. Feeding that string straight into a
 * {@code package:} URI produces an intent the package installer silently ignores — an
 * Uninstall button that appears to do nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class UninstallIntentTest {

    private Context ctx;
    private HomescreenView view;

    @Before
    public void setUp() {
        ctx  = ApplicationProvider.getApplicationContext();
        view = new HomescreenView(ctx);
        ShadowApplication shadow = Shadows.shadowOf((android.app.Application) ctx);
        shadow.clearNextStartedActivities();
    }

    private Intent lastStarted() {
        return Shadows.shadowOf((android.app.Application) ctx).getNextStartedActivity();
    }

    @Test
    public void firesTheSystemUninstallConfirmation() {
        view.startUninstall("com.example.app");

        Intent sent = lastStarted();
        assertNotNull("no activity was started", sent);
        assertEquals(Intent.ACTION_DELETE, sent.getAction());
        assertEquals("package:com.example.app", sent.getDataString());
    }

    @Test
    public void carriesNewTaskSoItLaunchesFromTheLauncher() {
        // Started from a View's context, not an Activity task — without this the launch throws.
        view.startUninstall("com.example.app");
        assertTrue((lastStarted().getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void compositeWorkProfileKey_isReducedToThePlainPackage() {
        view.startUninstall("com.example.app#11");
        assertEquals("package:com.example.app", lastStarted().getDataString());
    }

    @Test
    public void nullPackage_startsNothing() {
        view.startUninstall(null);
        assertNull(lastStarted());
    }

    @Test
    public void theManifestRequestsDeletePackages() throws Exception {
        // Without this permission the system silently drops the request: the installer activity
        // starts and finishes immediately, so the Uninstall action looks like it does nothing.
        // Confirmed on a device — the intent was correct and the flow still failed until it was
        // declared. Normal protection level, so it is granted at install with no runtime prompt.
        android.content.pm.PackageInfo info = ctx.getPackageManager().getPackageInfo(
                ctx.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS);
        assertNotNull("manifest declares no permissions at all", info.requestedPermissions);
        assertTrue("REQUEST_DELETE_PACKAGES missing from the manifest",
                java.util.Arrays.asList(info.requestedPermissions)
                        .contains("android.permission.REQUEST_DELETE_PACKAGES"));
    }

    @Test
    public void theUriAlwaysMatchesUserKeysPackageOf() {
        // Guards against the two implementations drifting apart.
        for (String key : new String[]{"com.a.b", "com.a.b#0", "com.a.b#4294967296"}) {
            Shadows.shadowOf((android.app.Application) ctx).clearNextStartedActivities();
            view.startUninstall(key);
            assertEquals("package:" + UserKeys.packageOf(key), lastStarted().getDataString());
        }
    }
}
