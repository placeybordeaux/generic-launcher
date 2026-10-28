package com.genericlauncher.launcher.personality;

import android.content.Context;

import com.genericlauncher.launcher.AppInfo;

import java.util.List;

/**
 * Seam: the one chance a personality gets to derive its own per-app data.
 *
 * <p><b>Why this exists.</b> {@link DrawerApp} shows a strategy only label, key, usage and work
 * flag — no icon, no {@code Context}, nothing from {@code android.*}. That is what keeps
 * {@link DrawerStrategy} a pure function testable on a bare JVM, and it is worth keeping. But it
 * also means a personality that wants to group by something the platform knows and the launcher
 * does not — the icon's dominant colour, an app's category, its install date — has no way to find
 * it out. Without this hook such a personality is unwritable behind the seam, and the fork would
 * have to reach into the launcher's own load path instead. That is the seam being wrong, not the
 * fork.
 *
 * <p>So: whenever the app list is (re)loaded, the active sink is handed every {@link AppInfo} —
 * icon included — and can build whatever side table it needs, keyed by {@link DrawerApp#key()}.
 * The strategy then reads that table, and stays pure.
 *
 * <p><b>A side table, never a field on {@code AppInfo}.</b> The launcher's model belongs to the
 * launcher; a personality that needed to add a field to it would be a personality the seam cannot
 * hold.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li><b>Called on a worker thread</b>, before the loaded list reaches the UI — do the expensive
 *       work here, not in {@code organize()}.</li>
 *   <li><b>Must not throw.</b> This runs on the same worker as {@code AppLoader}, where an
 *       escaping exception is process death — and this app is the HOME app, so that means no home
 *       screen at all. Catch per app; one bad package must cost only that package.</li>
 *   <li><b>Called more than once</b>, and with partial lists: the loader delivers the home
 *       screen's apps first and the full set after, and reloads on every install/uninstall. Treat
 *       it as "merge these in", never "this is everything that exists".</li>
 *   <li>Whatever it publishes must be safe to read from the main thread immediately afterwards.</li>
 * </ul>
 */
public interface AppDataSink {

    /**
     * Derive and store whatever this personality needs about {@code apps}. Worker thread; see the
     * contract above.
     */
    void onAppsLoaded(Context context, List<AppInfo> apps);

    /** Derives nothing — the taste-free default. */
    AppDataSink NONE = new AppDataSink() {
        @Override public void onAppsLoaded(Context context, List<AppInfo> apps) { }
    };
}
