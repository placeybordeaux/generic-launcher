package com.genericlauncher.launcher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * The one answer to "what does getting rid of this app mean?", shared by every app menu and the
 * home screen's drag action bar so they cannot disagree.
 *
 * <p>A system app (YouTube, Chrome, most of an OEM's set) cannot be uninstalled. Handing one to
 * {@code ACTION_DELETE} either does nothing visible or offers to "uninstall updates", which leaves
 * the app exactly where it was. What the user can do is <em>disable</em> it, and a launcher has no
 * permission to do that itself: the Disable button lives on the system App info page. So for a
 * system app the action is named Disable and opens App info, and a toast says which button to
 * press, because landing on App info after tapping "Disable" otherwise reads as a misfire.
 *
 * <p>Work apps get neither: {@code ACTION_DELETE} + {@code EXTRA_USER} is unreliable across OEMs
 * and MDM-installed work apps usually cannot be removed by the user anyway.
 *
 * <p>A disabled app is then treated exactly like an uninstalled one: it leaves the drawer, and
 * the next full load prunes it from the home screen and its folders. Re-enabling brings it back
 * to the drawer only. That is deliberate and matches stock launchers — disabling is how a user
 * gets rid of an app the phone will not let them uninstall, so its spot is not worth keeping.
 */
final class AppRemoval {

    enum Kind { NONE, UNINSTALL, DISABLE }

    static final String UNINSTALL_LABEL = "Uninstall";
    static final String DISABLE_LABEL = "Disable";

    private AppRemoval() {}

    static Kind kindFor(@Nullable AppInfo app) {
        if (app == null || app.isWork()) return Kind.NONE;
        return app.isSystem() ? Kind.DISABLE : Kind.UNINSTALL;
    }

    /** Menu label for {@code app}, or null when the menu should offer nothing. */
    @Nullable
    static String labelFor(@Nullable AppInfo app) {
        switch (kindFor(app)) {
            case UNINSTALL: return UNINSTALL_LABEL;
            case DISABLE:   return DISABLE_LABEL;
            default:        return null;
        }
    }

    /** True for either menu label, so menus can route a tap without repeating the decision. */
    static boolean isRemovalLabel(Object label) {
        return UNINSTALL_LABEL.equals(label) || DISABLE_LABEL.equals(label);
    }

    /**
     * Starts the removal for {@code app}. Nothing is changed in the launcher here: the user has
     * not confirmed yet, and MainActivity's package receiver hears the uninstall or disable if
     * and when it happens.
     */
    static void start(Context c, AppInfo app) {
        switch (kindFor(app)) {
            case UNINSTALL: startUninstall(c, app.getPackageName()); break;
            case DISABLE:   openDisable(c, app.getPackageName()); break;
            default:        break;
        }
    }

    /** {@code packageKey} may be a {@link UserKeys} composite; the system wants the bare name. */
    static void startUninstall(Context c, String packageKey) {
        if (packageKey == null) return;
        Intent intent = new Intent(Intent.ACTION_DELETE,
                Uri.parse("package:" + UserKeys.packageOf(packageKey)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, "Can't uninstall this app", Toast.LENGTH_SHORT).show();
        }
    }

    static void openDisable(Context c, String packageKey) {
        if (packageKey == null) return;
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + UserKeys.packageOf(packageKey)));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(intent);
            Toast.makeText(c, "This app came with the phone — tap Disable to hide it",
                    Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(c, "Can't open App info for this app", Toast.LENGTH_SHORT).show();
        }
    }
}
