package com.genericlauncher.launcher;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import com.genericlauncher.launcher.personality.DrawerApp;

/**
 * One installed, launchable app.
 *
 * <p>For work-profile apps, {@link #packageName} holds a composite key of the form
 * {@code "pkg#serial"} (see {@link UserKeys}). Personal apps carry the bare package name.
 *
 * <p>Implements {@link DrawerApp}, the framework-free view of an app that the drawer seam is
 * written against. That interface is the whole reason a {@code DrawerStrategy} can be unit-tested
 * on a bare JVM: strategies see only label/key/usage/work and never this class.
 *
 * <p><b>A personality must not add fields here.</b> This is the launcher's model, not the fork's.
 * A personality that needs extra per-app data (a colour, a category, a score) keeps its own side
 * table keyed by {@link #key()} — which is exactly how the colour build re-adds the per-app
 * dominant colour that used to live on this class.
 */
public class AppInfo implements DrawerApp {
    private final String label;
    /** Bare pkg for personal apps; "pkg#serial" composite key for work-profile apps. */
    private final String packageName;
    private final Drawable icon;

    /** Foreground-usage score (e.g. total ms in foreground over the lookback window). 0 if unknown. */
    private long usageScore;

    /** The user this app belongs to. */
    private UserHandle user;

    /** True if this app lives in a managed (work) profile. */
    private boolean isWork;

    /**
     * True for an app that ships on the system image ({@code FLAG_SYSTEM}), updated or not. These
     * cannot be uninstalled — {@code ACTION_DELETE} on one either fails or quietly offers to
     * "uninstall updates" — so the menus offer Disable instead (see {@link AppRemoval}).
     */
    private boolean isSystem;

    /**
     * Main-activity component used for {@code LauncherApps.startMainActivity}. Null for personal
     * apps (which launch via {@code PackageManager.getLaunchIntentForPackage}).
     */
    private ComponentName componentName;

    public AppInfo(String label, String packageName, Drawable icon) {
        this.label = label;
        this.packageName = packageName;
        this.icon = icon;
    }

    public String getLabel() { return label; }
    public String getPackageName() { return packageName; }
    /**
     * The shared icon instance. <b>A caller that sets its bounds owns it for that draw only.</b>
     *
     * <p>One {@code Drawable} has one set of bounds, and every surface here holds the same
     * instance, so a canvas painter that calls {@code setBounds(x, y, ...)} before drawing (any
     * view that paints apps onto its own canvas does, at absolute view coordinates)
     * silently repositions the icon for everyone else. That is fine for another canvas painter,
     * which sets bounds again on its next frame, and fine for a {@link BadgeDrawable}, which
     * re-establishes them inside its own {@code draw}. It is <b>not</b> fine for an
     * {@link android.widget.ImageView}: that configures the drawable's bounds once, when the
     * drawable is set, and never again — so a later mutation moves the icon out of the view and
     * it draws nothing.
     *
     * <p>Hence the rule: <b>anything handing an icon to a View calls {@link #newIconForView()}
     * instead.</b> Upstream, in the launcher this was extracted from, a drawer preview that
     * painted the real app list mutated every icon's bounds, and the home screen's folder chips — the only View surface holding a
     * raw instance — were left empty until something rebuilt them.
     */
    public Drawable getIcon() { return icon; }

    /**
     * An icon instance for a View to own, with bounds of its own. Shares the underlying bitmap
     * through the constant state, so it costs a wrapper, not a copy of the pixels. Falls back to
     * the shared instance for a drawable with no constant state, which is the best available
     * answer and no worse than before.
     *
     * @see #getIcon()
     */
    public Drawable newIconForView() {
        if (icon == null) return null;
        Drawable.ConstantState state = icon.getConstantState();
        return state != null ? state.newDrawable() : icon;
    }
    public long getUsageScore() { return usageScore; }
    public void setUsageScore(long usageScore) { this.usageScore = usageScore; }
    public UserHandle getUser() { return user; }
    public void setUser(UserHandle user) { this.user = user; }
    @Override public boolean isWork() { return isWork; }
    public void setWork(boolean isWork) { this.isWork = isWork; }
    public boolean isSystem() { return isSystem; }
    public void setSystem(boolean isSystem) { this.isSystem = isSystem; }
    public ComponentName getComponentName() { return componentName; }
    public void setComponentName(ComponentName componentName) { this.componentName = componentName; }

    // --- DrawerApp: the framework-free view a DrawerStrategy sees ---------------

    // isWork() above already satisfies the interface; the launcher's getter and the seam's
    // accessor happen to want the same name, so there is nothing to add for it here.
    @Override public String label()      { return label; }
    @Override public String key()        { return packageName; }
    @Override public long   usageScore() { return usageScore; }
}
