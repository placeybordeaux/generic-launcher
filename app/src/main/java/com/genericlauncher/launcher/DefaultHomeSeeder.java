package com.genericlauncher.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.Telephony;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the first-run default home screen layout (see
 * docs/superpowers/specs/2026-07-20-default-home-screen-design.md).
 *
 * <p>Dock slots 0-3 are phone / messages / browser / camera, resolved from system
 * defaults — never hardcoded packages. Unresolvable roles leave their slot empty;
 * an app resolving for two roles keeps only the earlier slot. Runs at most once,
 * guarded by {@link Settings#isHomeSeeded}: a non-empty store (upgrading user) is
 * never touched but still marked seeded, and the search placeholder is marked
 * dismissed so it never appears over the upgrader's pre-existing layout.
 */
public final class DefaultHomeSeeder {

    private DefaultHomeSeeder() {}

    /**
     * Seeds the dock if this is a fresh install (empty store, never seeded).
     * Always leaves {@link Settings#isHomeSeeded} true afterwards.
     *
     * @return true if items were actually seeded
     */
    public static boolean seedIfNeeded(Context c, HomeScreenStore store) {
        if (Settings.isHomeSeeded(c)) return false;
        if (!store.getItems().isEmpty()) {
            // Upgrading user with an existing layout — never modify, never re-check. Also
            // mark the search placeholder dismissed so the pill never appears over a
            // pre-existing layout (the Settings "Reset home screen" action still restores
            // it via reseed(), which clears this pref).
            Settings.setHomeSeeded(c, true);
            Settings.setSearchPlaceholderDismissed(c, true);
            return false;
        }
        seedDock(c, store);
        // The flag must never outlive the data it guards: store writes are queued on a background
        // thread, and a kill between the two would leave isHomeSeeded true over an empty store —
        // no dock, ever, short of Settings → Reset. First run only, and the file is a few hundred
        // bytes, so blocking here is cheaper than the failure it prevents.
        store.flush();
        Settings.setHomeSeeded(c, true);
        return true;
    }

    /**
     * Explicit reset: clears the entire layout, resets the seeding prefs, and seeds
     * the default dock again. Only called from the Settings "Reset home screen to
     * defaults" action (after user confirmation). The caller is responsible for
     * deleting orphaned appWidgetIds and refreshing the view.
     */
    public static void reseed(Context c, HomeScreenStore store) {
        // An unreadable homescreen.json suppresses every save for the whole process, which made
        // this action silently do nothing while still committing home_seeded — the user's rebuilt
        // layout then vanished on the next launch. An explicit reset is the user telling us to
        // overwrite, so it is exactly the acknowledgement that suppression waits for.
        store.acknowledgeCorruptLoad();
        for (HomeItem item : store.getItems()) {
            store.removeItem(item);
        }
        Settings.setSearchPlaceholderDismissed(c, false);
        seedDock(c, store);
        store.flush(); // same ordering guarantee as seedIfNeeded()
        Settings.setHomeSeeded(c, true);
    }

    /** Writes the resolved dock packages into the store (slot index = list index). */
    private static void seedDock(Context c, HomeScreenStore store) {
        List<String> pkgs = resolveDockPackages(c);
        for (int slot = 0; slot < pkgs.size(); slot++) {
            String pkg = pkgs.get(slot);
            if (pkg == null) continue;
            store.addItem(HomeItem.app(HomeItem.DOCK, slot, 0, pkg));
        }
    }

    /**
     * Resolves the four dock roles in slot order: phone, messages, browser, camera.
     * Each entry is a launchable package name or null (missing role, non-launchable
     * resolver, or deduped repeat of an earlier slot).
     */
    static List<String> resolveDockPackages(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String> result = new ArrayList<>(4);
        String[] candidates = {
                resolveIntentPackage(pm, new Intent(Intent.ACTION_DIAL)),
                resolveMessages(c, pm),
                resolveIntentPackage(pm, new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))),
                resolveIntentPackage(pm, new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
        };
        for (String pkg : candidates) {
            if (pkg == null
                    || result.contains(pkg)
                    || pm.getLaunchIntentForPackage(pkg) == null) {
                result.add(null);
            } else {
                result.add(pkg);
            }
        }
        return result;
    }

    /** Default SMS app if the platform reports one, else the smsto: handler. */
    private static String resolveMessages(Context c, PackageManager pm) {
        try {
            String def = Telephony.Sms.getDefaultSmsPackage(c);
            if (def != null) return def;
        } catch (RuntimeException ignored) {
            // Some OEM builds throw for secondary profiles — fall through to intent resolution.
        }
        return resolveIntentPackage(pm, new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")));
    }

    /** Best-effort single resolution of an intent to its handler's package, or null. */
    private static String resolveIntentPackage(PackageManager pm, Intent intent) {
        try {
            ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return null;
            return ri.activityInfo.packageName;
        } catch (RuntimeException e) {
            Log.w("DefaultHomeSeeder", "resolveActivity failed", e);
            return null;
        }
    }
}
