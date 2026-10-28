package com.genericlauncher.launcher;

import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.genericlauncher.launcher.personality.Personality;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads all launchable apps (personal + work profiles) and returns them as a flat list.
 * Work-profile apps carry a composite key ("pkg#serial") and a badged icon.
 *
 * <p><b>Deliberately unordered.</b> Ordering and grouping are the active
 * {@link com.genericlauncher.launcher.personality.DrawerStrategy}'s job, not the loader's — so a
 * personality can re-sort the drawer without touching the load path, and the loader does no work
 * that a strategy might immediately throw away.
 */
public class AppLoader {

    public interface Callback {
        void onAppsLoaded(List<AppInfo> apps);
    }

    public static void loadAsync(Context context, Callback callback) {
        loadAsync(context, null, null, callback);
    }

    /**
     * Loads apps in two phases so the home screen does not wait on the app drawer.
     *
     * <p>The home screen references a dozen or so packages; a real device has ~166 launchable
     * activities, and decoding all of their icons costs the better part of a second on a cold
     * start (measured: {@code Launcher.loadIcon} ≈ 780ms across 166 icons, inside a ~1370ms
     * {@code loadApps}, for a 1.5s time-to-usable-launcher). Loading everything before drawing
     * anything meant the user watched a spinner for that whole time.
     *
     * <p>So {@code priorityKeys} — from {@link HomeScreenStore#referencedAppKeys()} — are built
     * and delivered to {@code onPriority} first, which is enough to draw a complete home screen.
     * The full list follows on {@code onAll} for the drawer.
     *
     * <p>The enumeration ({@code getActivityList}) is deliberately repeated for the second phase
     * rather than shared: ~15ms against the ~780ms of icon decoding beside it, and keeping the
     * phases independent means neither can leave the other holding half-built state.
     *
     * <p>Both callbacks fire on the main thread, and {@code onPriority} always fires before
     * {@code onAll}. {@code onPriority} is skipped entirely when {@code priorityKeys} is null or
     * empty (an empty home screen), so callers must not rely on it having run.
     */
    public static void loadAsync(Context context, @Nullable Set<String> priorityKeys,
                                 @Nullable Callback onPriority, Callback onAll) {
        new AsyncTask<Void, Void, List<AppInfo>>() {
            @Override
            protected List<AppInfo> doInBackground(Void... voids) {
                // Trace sections are thread-scoped, so this opens and closes on the background
                // thread. The main-thread half of the wait is covered by reportFullyDrawn.
                Perf.begin(Perf.LOAD_APPS);
                try {
                    if (onPriority != null && priorityKeys != null && !priorityKeys.isEmpty()) {
                        Perf.begin(Perf.LOAD_HOME_APPS);
                        final List<AppInfo> subset;
                        try {
                            subset = loadApps(context, priorityKeys);
                        } finally {
                            Perf.end();
                        }
                        // Hand the home screen over now rather than at onPostExecute, which does
                        // not run until the full load below has finished.
                        new Handler(Looper.getMainLooper())
                                .post(() -> onPriority.onAppsLoaded(subset));
                    }
                    return loadApps(context, null);
                } finally {
                    Perf.end();
                }
            }

            @Override
            protected void onPostExecute(List<AppInfo> apps) {
                onAll.onAppsLoaded(apps);
            }
        }.execute();
    }

    /**
     * Builds the app list for one load phase.
     *
     * <p>Package-private rather than private so the suite can run the whole thing synchronously.
     * The colour cache's invalidation only exists inside this method — whether an entry is read
     * back or recomputed is not observable from {@code loadAsync}, which hands back the same
     * {@code AppInfo} list either way — so {@code ColorCacheVersionTest} calls it directly with a
     * fixture app registered on {@code ShadowLauncherApps}. Going through {@code loadAsync} would
     * also put the work on a real background thread.
     *
     * @param onlyKeys when non-null, only apps whose composite key is in this set are built.
     *                 Enumeration still covers every profile; the filter skips the expensive
     *                 per-app work (label, icon decode), which is the point.
     */
    static List<AppInfo> loadApps(Context context, @Nullable Set<String> onlyKeys) {
        PackageManager pm = context.getPackageManager();
        LauncherApps launcherApps =
                (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        UserManager userManager =
                (UserManager) context.getSystemService(Context.USER_SERVICE);

        List<UserHandle> profiles = userManager.getUserProfiles();
        // "Personal" means the user this launcher instance is actually running as — not
        // profiles.get(0), whose order the platform does not guarantee. On a secondary or guest
        // user that put every app behind a "pkg#10" key while DefaultHomeSeeder seeds bare package
        // names, so nothing resolved and the dock rendered empty; a work-profile-first ordering
        // would have blanked the whole home screen.
        long personalSerial = userManager.getSerialNumberForUser(Process.myUserHandle());

        // Per-app foreground usage (empty without usage access; used by the Most used mode).
        // Usage stats only cover the personal profile.
        Map<String, Long> usage = UsageStatsHelper.queryUsageScores(context);

        List<AppInfo> apps = new ArrayList<>();

        // The launcher is not an app to launch from its own drawer: tapping it just closes the
        // drawer. Worse, as the HOME app it accrues more foreground time than anything else, so
        // it would top the usage ordering wherever usage access was granted.
        String ownPackage = context.getPackageName();

        for (UserHandle user : profiles) {
            long serial = userManager.getSerialNumberForUser(user);
            boolean isWork = (serial != personalSerial);

            List<LauncherActivityInfo> activities;
            try {
                Perf.begin(Perf.QUERY_ACTIVITIES);
                try {
                    activities = launcherApps.getActivityList(null, user);
                } finally {
                    Perf.end();
                }
            } catch (SecurityException e) {
                // Not the default launcher — skip this profile gracefully.
                Log.w("AppLoader", "getActivityList denied for user serial " + serial
                        + "; skipping profile", e);
                continue;
            }

            for (LauncherActivityInfo info : activities) {
                // getLabel()/getIcon() go through the target app's Resources and throw
                // Resources$NotFoundException for a stripped split APK, a corrupt install, or a
                // package being replaced mid-enumeration. This runs on AsyncTask's worker thread,
                // where an escaping RuntimeException is process death — and for the default HOME
                // app that means no home screen at all. One bad package must only cost that
                // package.
                String pkg = null;
                try {
                    pkg = info.getApplicationInfo().packageName;
                    if (ownPackage.equals(pkg)) continue;

                    // Filter FIRST. Everything below reads resources out of the target APK
                    // (label, icon decode); skipping it for apps the home screen does not
                    // reference is the entire saving of the priority phase.
                    if (onlyKeys != null
                            && !onlyKeys.contains(appKey(pkg, serial, personalSerial))) {
                        continue;
                    }

                    String label = info.getLabel().toString();

                    // Icon: raw icon from LauncherApps; badge work-profile icons so they are
                    // visually distinguished in the drawer and homescreen.
                    Perf.begin(Perf.LOAD_ICON);
                    Drawable icon;
                    try {
                        Drawable rawIcon = info.getIcon(0);
                        icon = isWork ? pm.getUserBadgedIcon(rawIcon, user) : rawIcon;
                    } finally {
                        Perf.end();
                    }

                    // HomeItem key: composite for other profiles, bare for our own (see UserKeys).
                    AppInfo appInfo =
                            new AppInfo(label, appKey(pkg, serial, personalSerial), icon);
                    appInfo.setUser(user);
                    appInfo.setWork(isWork);
                    appInfo.setSystem((info.getApplicationInfo().flags
                            & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0);
                    appInfo.setComponentName(info.getComponentName());

                    if (!isWork) {
                        // Usage stats are personal-profile only; work apps get score 0 and sort last.
                        Long score = usage.get(pkg);
                        if (score != null) appInfo.setUsageScore(score);
                    }

                    apps.add(appInfo);
                } catch (RuntimeException e) {
                    Log.w("AppLoader", "Skipping app whose label or icon failed to load: " + pkg, e);
                }
            }
        }

        // The personality's one chance to derive its own per-app data, while we are still on the
        // worker thread and still holding the icons. Guarded because this is third-party-ish code
        // on the thread where an escaping exception is process death, and for the HOME app that
        // means no home screen: a broken personality must cost its own feature, not the launcher.
        try {
            Personality.DATA.onAppsLoaded(context, apps);
        } catch (RuntimeException e) {
            Log.w("AppLoader", "Personality.DATA.onAppsLoaded failed; "
                    + "its personality will render with whatever defaults it falls back to", e);
        }

        // Returned in enumeration order. The drawer sorts through its DrawerStrategy and the
        // home screen looks apps up by key, so neither has any use for an order chosen here.
        return apps;
    }

    /**
     * The home-screen key for {@code pkg} on the profile with {@code serial}, given
     * {@code personalSerial} — the serial of the user this launcher instance runs as.
     *
     * <p>Apps on our own user always take the bare key, even when that user is a secondary or guest
     * user whose serial is not 0; only other profiles in the group (work) get a composite key.
     */
    static String appKey(String pkg, long serial, long personalSerial) {
        return serial == personalSerial ? pkg : UserKeys.compose(pkg, serial);
    }

}
