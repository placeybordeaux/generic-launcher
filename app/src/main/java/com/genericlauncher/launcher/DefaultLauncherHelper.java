package com.genericlauncher.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.Nullable;

/**
 * Whether this launcher currently holds the HOME role, and how to ask for it.
 *
 * <p>Free of view code so it can be unit-tested. See {@link #requestIntent} for why the
 * {@code RoleManager} request-role dialog is not used, despite being the documented way.
 */
public final class DefaultLauncherHelper {

    private DefaultLauncherHelper() {}

    /**
     * True when this package is the current default HOME app.
     *
     * <p>Uses {@code resolveActivity} rather than {@code RoleManager.isRoleHeld} because it
     * behaves identically from API 26 up and needs no version branch.
     *
     * <p><b>This only works because the manifest queries MAIN/HOME.</b> Package visibility hides
     * other launchers otherwise, and {@code resolveActivity} then returns the only HOME activity
     * it can see — this one — so the answer was "default" on an emulator whose home was the stock
     * launcher (2026-09-07). {@code ManifestQueriesTest} keeps the query in place; Robolectric
     * cannot see the filtering itself.
     *
     * <p><strong>Coverage note:</strong> The {@code ri != null} guard is untested in unit tests
     * because Robolectric's manifest-derived HOME resolution (from {@code CATEGORY_HOME} +
     * {@code CATEGORY_DEFAULT} in AndroidManifest.xml) cannot be cleared to reach the
     * {@code resolveActivity() == null} case — {@code ShadowPackageManager.removeResolveInfosForIntent}
     * followed by {@code setResolveInfosForIntent} with an empty list were both tried and neither
     * dislodges the manifest-derived entry. The {@code ri.activityInfo != null} guard is tested
     * via a malformed ResolveInfo. In production, {@code ri == null} can occur on devices where
     * no application resolves the HOME intent.
     */
    public static boolean isDefault(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = c.getPackageManager()
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null
                && ri.activityInfo != null
                && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    /**
     * An intent that lets the user make this the default launcher, or {@code null} if the device
     * exposes no way to do it.
     *
     * <p><b>Deliberately not {@link RoleManager#createRequestRoleIntent}.</b> That is the obvious
     * call — a one-tap system dialog, and what this method used to return — and on the test phone
     * it silently does nothing at all. Measured on a OnePlus CPH2749, Android 16, 2026-09-03:
     * the intent resolves and starts
     * {@code com.google.android.permissioncontroller/…role.ui.RequestRoleActivity}, which is then
     * {@code activityDestroyed} in the same millisecond, having never drawn. No exception, no
     * result, no UI. The "Set as default launcher" row in Settings had therefore been dead for as
     * long as the device has been on Android 16, and nothing said so — pressing it looked exactly
     * like pressing a button that had not registered the touch.
     *
     * <p>PermissionController does not offer the request-role flow for {@code ROLE_HOME}. So the
     * Settings deep link is not a pre-API-29 fallback any more, it is the path: one extra tap,
     * and it lands on the "Default Home app" picker on every device that has a Settings app.
     * {@link android.provider.Settings#ACTION_HOME_SETTINGS} first, then the broader screens,
     * because OEM Settings apps do not all expose it.
     *
     * <p>If a future platform starts honouring the role request, prefer it again — but verify by
     * watching for {@code RequestRoleActivity} in logcat rather than by reading the docs, which
     * describe the behaviour this comment exists because the device does not have.
     */
    @Nullable
    public static Intent requestIntent(Context c) {
        String[] actions = {
                android.provider.Settings.ACTION_HOME_SETTINGS,
                android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                android.provider.Settings.ACTION_SETTINGS,
        };
        PackageManager pm = c.getPackageManager();
        for (String action : actions) {
            Intent i = new Intent(action);
            if (i.resolveActivity(pm) != null) return i;
        }
        return null;
    }
}
