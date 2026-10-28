package com.genericlauncher.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.Nullable;

/**
 * Whether {@link NotificationDotsService} has been granted notification access, and how to ask.
 *
 * <p>Free of view code, mirroring {@link DefaultLauncherHelper} and {@link UsageStatsHelper}, so
 * the three grants the launcher needs are all read the same way. Every surface that asks this
 * question — today the Settings badges toggle and its return path — must answer it the same way.
 *
 * <p><b>There is no in-app dialog for this one.</b> Unlike the HOME role, which
 * {@code RoleManager} grants through a one-tap system dialog, notification access is only
 * reachable by throwing the user out to {@code ACTION_NOTIFICATION_LISTENER_SETTINGS} — which does
 * not come back on its own. Anything that asks for it has to handle the return itself.
 */
public final class NotificationAccess {

    /**
     * The secure setting Android stores the granted listeners in. Not a public constant on API
     * levels this app supports, so the string is the contract.
     */
    private static final String ENABLED_LISTENERS = "enabled_notification_listeners";

    private NotificationAccess() {}

    /** True when this package's notification listener has been granted access. */
    public static boolean isGranted(Context c) {
        return grantedTo(android.provider.Settings.Secure.getString(
                c.getContentResolver(), ENABLED_LISTENERS), c.getPackageName());
    }

    /**
     * True if {@code flat} — the colon-separated flattened component list Android stores in
     * {@code enabled_notification_listeners} — grants access to a service owned by {@code pkg}.
     *
     * <p>Matched by unflattening and comparing the package half exactly. A prefix match would
     * also accept any <em>other</em> installed package whose name merely starts with ours
     * ("com.genericlauncher.launcher.pro"), so the badges toggle would enable itself believing it had
     * access the listener was never actually granted.
     */
    static boolean grantedTo(@Nullable String flat, String pkg) {
        if (flat == null) return false;
        for (String component : flat.split(":")) {
            ComponentName cn = ComponentName.unflattenFromString(component.trim());
            if (cn != null && cn.getPackageName().equals(pkg)) return true;
        }
        return false;
    }

    /**
     * Finishes an enable that was deferred until access was granted — the badges toggle records {@code notif_dots_pending} before sending the user out to the
     * system screen, and whichever activity they come back to has to complete it. Returns true
     * when it just switched badges on, so the caller can say so.
     *
     * <p>Every activity that can be the one resumed after the grant must call this; a consumer in
     * {@code MainActivity} alone left the Settings path with the switch still off after a
     * successful grant, which the user reads as the grant having failed. Cheap when nothing is
     * pending: the secure setting is only read once the two prefs say a grant is being waited on.
     */
    public static boolean finishPendingEnable(Context c) {
        if (!Settings.isNotifDotsPending(c) || Settings.getNotifDots(c)) return false;
        if (!isGranted(c)) return false;
        Settings.setNotifDots(c, true);
        Settings.setNotifDotsPending(c, false);
        return true;
    }

    /** The system screen where the user grants it. Always leaves the app. */
    public static Intent settingsIntent() {
        return new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }
}
