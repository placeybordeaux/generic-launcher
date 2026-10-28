package com.genericlauncher.launcher;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.provider.Settings;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper for the per-app foreground-usage data that orders the Usage drawer mode.
 *
 * <p>{@link UsageStatsManager} requires the special PACKAGE_USAGE_STATS access, which the user
 * must grant manually from the "Usage access" settings screen — it cannot be requested with a
 * normal runtime permission dialog. Callers should check {@link #hasAccess(Context)} and, when it
 * returns false, send the user to {@link #openUsageAccessSettings(Context)}.
 */
public final class UsageStatsHelper {

    private static final long LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000; // ~30 days

    /**
     * A fake usage source for the suite: while set, {@link #hasAccess} is true and
     * {@link #queryUsageScores} answers with a copy of this instead of asking the system. Robolectric
     * never grants the usage op, so without this nothing that reads usage through an Activity can
     * be driven end to end. Reset it in tearDown.
     */
    @Nullable private static volatile Map<String, Long> fakeScoresForTests;

    private UsageStatsHelper() {}

    /** The copy is never mutated after this, so the loader's worker thread can read it freely. */
    static void setFakeScoresForTests(@Nullable Map<String, Long> scores) {
        fakeScoresForTests = scores == null ? null : new HashMap<>(scores);
    }

    /** Whether this app currently holds Usage Access. */
    public static boolean hasAccess(Context context) {
        if (fakeScoresForTests != null) return true;
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) return false;
        int mode = appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.getPackageName());
        if (mode == AppOpsManager.MODE_DEFAULT) {
            // Fall back to an actual permission check when the op defers to it.
            return context.checkCallingOrSelfPermission(
                    android.Manifest.permission.PACKAGE_USAGE_STATS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Opens the system Usage Access page, or says why it could not. Every route to the grant
     * goes through here:
     * {@code ACTION_USAGE_ACCESS_SETTINGS} has no resolver on some OEM, TV and Go builds, and an
     * unguarded start from the HOME activity is no home screen.
     */
    public static void openUsageAccessSettings(Context c) {
        try {
            c.startActivity(usageAccessSettingsIntent());
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(c, "This device has no usage access setting",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Intent that opens the system "Usage access" settings list; see {@link #openUsageAccessSettings}. */
    public static Intent usageAccessSettingsIntent() {
        return new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
    }

    /**
     * Returns a map of packageName -> total foreground time (ms) over the lookback window.
     * Empty if access is missing or no data is available.
     */
    public static Map<String, Long> queryUsageScores(Context context) {
        Map<String, Long> scores = new HashMap<>();
        Map<String, Long> fake = fakeScoresForTests;
        if (fake != null) {
            scores.putAll(fake);
            return scores;
        }
        if (!hasAccess(context)) return scores;

        UsageStatsManager usm =
                (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return scores;

        long now = System.currentTimeMillis();
        List<UsageStats> stats =
                usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - LOOKBACK_MILLIS, now);
        if (stats == null) return scores;

        for (UsageStats s : stats) {
            long time = s.getTotalTimeInForeground();
            if (time <= 0) continue;
            // queryUsageStats can return multiple buckets per package; accumulate.
            Long prev = scores.get(s.getPackageName());
            scores.put(s.getPackageName(), (prev == null ? 0 : prev) + time);
        }
        return scores;
    }
}
