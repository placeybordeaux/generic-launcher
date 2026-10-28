package com.genericlauncher.launcher;

import android.os.Trace;

/**
 * Named trace sections for the startup and interaction paths.
 *
 * <p>Every section here is an assertable metric: the macrobenchmark module
 * ({@code StartupBenchmark}, {@code JankBenchmark}) reads these names back out of the Perfetto
 * trace via {@code TraceSectionMetric}, so <b>renaming a constant breaks a benchmark</b>. The
 * names are the contract; treat them like public API.
 *
 * <p><b>Cost when not tracing.</b> {@link Trace#beginSection} checks a cached, process-local
 * tag-enabled flag and returns immediately when tracing is off — no allocation, no syscall.
 * That is why these can sit in the hot startup path unguarded, and why they ship in release
 * builds rather than being stripped: a trace you can only capture in debug measures the wrong
 * binary (debug is not AOT-compiled the same way and skips R8 entirely).
 *
 * <p><b>Section names must be constants.</b> Do not build them by concatenation — string
 * building in the startup path is exactly the cost we are here to measure, and Perfetto groups
 * slices by name, so a name that varies per call produces one bucket per call and no aggregate.
 */
final class Perf {

    private Perf() {}

    // --- Startup path. Nested roughly in the order they run. ---

    /** {@code MainActivity.onCreate} end to end. */
    static final String ACTIVITY_ON_CREATE = "Launcher.onCreate";
    /** Inflating {@code activity_main.xml} and resolving its views. */
    static final String INFLATE = "Launcher.inflate";
    /** First read of {@code homescreen.json} off disk (synchronous, main thread). */
    static final String STORE_LOAD = "Launcher.storeLoad";
    /** First-run default dock seeding. No-op after the first launch. */
    static final String SEED = "Launcher.seed";
    /** The whole background app load: enumerate, fetch icons, hand the list on. */
    static final String LOAD_APPS = "Launcher.loadApps";
    /**
     * The priority phase: just the apps the home screen references.
     *
     * <p>This is the number that governs how long the user waits to see a usable launcher on a
     * cold start, so it is the one to watch. It should stay proportional to the number of items
     * on the home screen, never to the number of apps installed — if it starts tracking the
     * latter, the two-phase load in {@link AppLoader} has been defeated.
     */
    static final String LOAD_HOME_APPS = "Launcher.loadHomeApps";
    /** Enumerating launchable activities across all profiles. */
    static final String QUERY_ACTIVITIES = "Launcher.queryActivities";
    /**
     * Fetching (and, for work profiles, badging) one app's icon drawable.
     *
     * <p>Nothing caches this: every launch decodes every installed app's icon from its APK. Added
     * to confirm by measurement what the unattributed remainder of {@link #LOAD_APPS} actually is,
     * rather than inferring it — and it is now most of it, since the dominant-colour pass this base
     * was extracted without (~780ms across 166 icons) is gone.
     */
    static final String LOAD_ICON = "Launcher.loadIcon";
    /** Building the homescreen pages and dock views. */
    static final String RENDER_HOME = "Launcher.renderHome";
    /** Building the drawer overlay for the current mode. */
    static final String RENDER_DRAWER = "Launcher.renderDrawer";

    // --- Hot-resume path: pressing Home while the launcher is already running. ---

    /**
     * {@code onNewIntent} — the Home press when the activity is already alive.
     *
     * <p>This exists because the framework gives us nothing here. {@code MainActivity} is
     * {@code launchMode="singleTask"}, so bringing it forward reuses the existing instance and
     * emits no activity-launch event; {@code StartupTimingMetric} consequently reports no
     * time-to-display at all for a hot start. Since a hot start is by far the most common way
     * the user opens this launcher, the path needs its own section or it is unmeasured.
     */
    static final String NEW_INTENT = "Launcher.newIntent";
    /** {@code onResume}, including the usage-access check that can trigger a reload. */
    static final String ON_RESUME = "Launcher.onResume";

    static void begin(String section) {
        Trace.beginSection(section);
    }

    static void end() {
        Trace.endSection();
    }
}
