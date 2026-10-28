package com.genericlauncher.launcher;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.Collections;

import static org.junit.Assert.*;

/** Unit tests for {@link DefaultLauncherHelper}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DefaultLauncherHelperTest {

    private Context ctx() { return ApplicationProvider.getApplicationContext(); }

    private static Intent homeIntent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    private void setHomeResolvesTo(String pkg) {
        ShadowPackageManager spm = Shadows.shadowOf(ctx().getPackageManager());
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = pkg;
        ri.activityInfo.name = pkg + ".Home";
        spm.addResolveInfoForIntent(homeIntent(), ri);
    }

    @Test
    public void isDefaultTrueWhenHomeResolvesToUs() {
        setHomeResolvesTo(ctx().getPackageName());
        assertTrue(DefaultLauncherHelper.isDefault(ctx()));
    }

    @Test
    public void isDefaultFalseWhenHomeResolvesElsewhere() {
        setHomeResolvesTo("com.some.other.launcher");
        assertFalse(DefaultLauncherHelper.isDefault(ctx()));
    }

    @Test
    public void isDefaultFalseWhenResolveInfoHasNoActivityInfo() {
        // Test that isDefault() safely handles a malformed ResolveInfo with null activityInfo.
        // This covers the ri.activityInfo != null guard.
        // Note: The ri != null guard cannot be tested here because Robolectric's shadow
        // initializes with HOME resolving to the app under test (due to CATEGORY_HOME +
        // CATEGORY_DEFAULT in AndroidManifest), and attempts to clear this (via
        // removeResolveInfosForIntent + setResolveInfosForIntent with empty list) do not
        // reach resolveActivity() == null. The manifest-derived resolution is unreachable.
        ShadowPackageManager spm = Shadows.shadowOf(ctx().getPackageManager());
        ResolveInfo ri = new ResolveInfo();
        // Deliberately leave activityInfo as null
        spm.setResolveInfosForIntent(homeIntent(), Collections.singletonList(ri));

        assertFalse(DefaultLauncherHelper.isDefault(ctx()));
    }

    /**
     * No HOME role on offer (Robolectric's RoleManager advertises none unless told to): fall
     * back to a Settings deep link. Pinned at 29, the minSdk — lower is not a platform the app
     * runs on, and the guard that used to make 26 a separate code path is gone with it.
     */
    @Test
    @Config(sdk = 29)
    public void roleUnavailableFallsBackToSettingsDeepLink() {
        ShadowPackageManager spm = Shadows.shadowOf(ctx().getPackageManager());
        Intent home = new Intent(android.provider.Settings.ACTION_HOME_SETTINGS);
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = "com.android.settings";
        ri.activityInfo.name = "com.android.settings.Home";
        spm.addResolveInfoForIntent(home, ri);

        Intent got = DefaultLauncherHelper.requestIntent(ctx());
        assertNotNull(got);
        assertEquals(android.provider.Settings.ACTION_HOME_SETTINGS, got.getAction());
    }

    /** Nothing resolvable at all must return null rather than an intent that will crash. */
    @Test
    @Config(sdk = 29)
    public void roleUnavailableReturnsNullWhenNothingResolves() {
        assertNull(DefaultLauncherHelper.requestIntent(ctx()));
    }

    /**
     * The HOME role must <b>not</b> be requested through {@link RoleManager}, even where the role
     * reports itself available.
     *
     * <p>It is the documented one-tap path and it silently does nothing. Measured on the test
     * phone (OnePlus CPH2749, Android 16, 2026-09-03): the intent resolves and starts
     * PermissionController's {@code RequestRoleActivity}, which is {@code activityDestroyed} in
     * the same millisecond having never drawn. No exception, no result, no UI — so "Set as default
     * launcher" was a button that did nothing, and looked exactly like one that had missed the
     * touch.
     *
     * <p>This test exists to stop the obvious call coming back. Nothing observable from inside a
     * unit test distinguishes a role intent that works from one that self-destructs on the device,
     * so the guard has to be on the intent we build, not on what happens to it.
     */
    @Test
    @Config(sdk = 29)
    public void theHomeRoleIsNeverRequestedThroughRoleManager() {
        RoleManager roleManager = (RoleManager) ctx().getSystemService(Context.ROLE_SERVICE);
        Shadows.shadowOf(roleManager).addAvailableRole(RoleManager.ROLE_HOME);

        ShadowPackageManager spm = Shadows.shadowOf(ctx().getPackageManager());
        Intent homeSettings = new Intent(android.provider.Settings.ACTION_HOME_SETTINGS);
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = "com.android.settings";
        ri.activityInfo.name = "com.android.settings.Home";
        spm.addResolveInfoForIntent(homeSettings, ri);

        Intent got = DefaultLauncherHelper.requestIntent(ctx());
        assertNotNull(got);
        assertNotEquals("the role-request dialog is not shown for ROLE_HOME on this platform; it "
                        + "starts and destroys itself without drawing",
                "android.app.role.action.REQUEST_ROLE", got.getAction());
        assertEquals(android.provider.Settings.ACTION_HOME_SETTINGS, got.getAction());
    }

    @Test
    @Config(sdk = 29)
    public void theHomeSettingsScreenIsWhatWeSendTheUserTo() {
        ShadowPackageManager spm = Shadows.shadowOf(ctx().getPackageManager());
        Intent homeSettings = new Intent(android.provider.Settings.ACTION_HOME_SETTINGS);
        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        ri.activityInfo.packageName = "com.android.settings";
        ri.activityInfo.name = "com.android.settings.Home";
        spm.addResolveInfoForIntent(homeSettings, ri);

        Intent got = DefaultLauncherHelper.requestIntent(ctx());
        assertNotNull("expected the Home settings deep link", got);
        assertEquals(android.provider.Settings.ACTION_HOME_SETTINGS, got.getAction());
    }
}
