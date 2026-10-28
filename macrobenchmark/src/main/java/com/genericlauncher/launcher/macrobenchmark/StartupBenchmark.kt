package com.genericlauncher.launcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup and memory. The numbers `./perf.sh` gates on.
 *
 * <p><b>Which number matters.</b> `timeToInitialDisplayMs` is the launcher's first frame;
 * `timeToFullDisplayMs` is `reportFullyDrawn()`, which the app calls once the app list has
 * loaded and the homescreen is actually visible. Because `loadApps()` hides the content
 * container behind a spinner, TTID measures the spinner and TTFD measures the launcher. Track
 * both — a fix that moves work off the critical path shows up as TTFD falling toward TTID.
 *
 * <p><b>Why memory is in the startup suite and not off in its own file.</b> On a launcher, RSS
 * is a latency metric. The launcher process is resident essentially forever, so the only thing
 * that makes a Home press slow is the process having been killed — and what gets a process
 * killed is its memory footprint relative to everything else the user has open. Every megabyte
 * of retained icon bitmaps raises the probability that the next Home press is a cold start.
 * Regressing memory and regressing startup are the same regression, observed a day apart.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * Process death → usable launcher. The rare-but-worst case: after a reboot, or after the
     * low-memory killer reclaimed the launcher while the user was in a heavy app.
     */
    @Test
    fun startupCold() = measureStartup(StartupMode.COLD)

    /**
     * Process alive, activity destroyed. What happens after the system trims the activity but
     * keeps the process warm.
     */
    @Test
    fun startupWarm() = measureStartup(StartupMode.WARM)

    /**
     * Process and activity both alive — just bringing it back to the foreground. The
     * overwhelmingly common path in real use: every press of the Home button, dozens of times a
     * day.
     *
     * <p><b>Neither StartupTimingMetric nor FrameTimingMetric works here, and the reasons are
     * the finding.</b> Both were tried and both failed, in informative ways:
     *
     * <ul>
     *   <li>`StartupTimingMetric` reports *nothing at all* — not a zero. `MainActivity` is
     *       `launchMode="singleTask"`, so bringing it forward reuses the instance and runs
     *       `onNewIntent`; the framework emits no activity-launch event to measure. An earlier
     *       version of this test asked for it and produced an empty result that looked like a
     *       pass, which is the worst possible failure mode for a regression gate.</li>
     *   <li>`FrameTimingMetric` throws "Observed no renderthread slices" — because on this path
     *       nothing invalidates, so the launcher draws zero new frames coming forward.</li>
     * </ul>
     *
     * <p>Both absences say the same thing: a hot open costs only whatever runs synchronously in
     * `onNewIntent` and `onResume`. Those sections are therefore the metric, and if either ever
     * starts doing real work — a reload, a re-render — it will show up here as a section that
     * used to be sub-millisecond and is not any more.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun startupHot() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
            TraceSectionMetric("Launcher.newIntent", TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("Launcher.onResume", TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("Launcher.renderHome", TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.HOT,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.waitForIdle()
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun measureStartup(mode: StartupMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            // Mode.Last: the footprint once startup has settled, not the transient peak during
            // icon decoding. The steady-state number is what the low-memory killer scores.
            MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
        ) + STARTUP_SECTION_METRICS,
        // DEFAULT, not None()/Full(): compile the app the way the device actually would after a
        // Play install, so the number tracks what users experience. None() would be more
        // reproducible but would report a build nobody runs.
        compilationMode = CompilationMode.DEFAULT,
        startupMode = mode,
        iterations = ITERATIONS,
        setupBlock = {
            // Leave the app before each iteration. The target is not the default home app (it
            // carries the .benchmark suffix), so this hands control to the real launcher.
            pressHome()
        },
    ) {
        startActivityAndWait()
        waitUntilLauncherReady()
    }

    companion object {
        /**
         * Per-phase attribution for the startup path. These turn a "startup got 80ms slower"
         * report into "storeLoad got 80ms slower", which is the difference between a bisect and
         * a fix.
         *
         * <p>The strings must match the constants in `app/.../Perf.java` exactly — there is no
         * shared compilation unit between an app module and its `com.android.test` module, so
         * this is a duplicated contract. A typo here shows up as a metric that is silently
         * always zero, so treat an unexpectedly absent section as a bug in this list first.
         */
        @OptIn(ExperimentalMetricApi::class)
        private val STARTUP_SECTION_METRICS = listOf(
            "Launcher.onCreate",
            "Launcher.inflate",
            "Launcher.storeLoad",
            "Launcher.loadApps",
            "Launcher.queryActivities",
            // The priority phase, and the one `Perf.LOAD_HOME_APPS` calls "the one to watch": it
            // is what the user actually waits for on a cold start. It sits here in the slot the
            // launcher this base was extracted from used for its dominant-colour pass, which is
            // gone — leaving that name in the list would have measured a section nothing emits,
            // and a TraceSectionMetric with no matching section reports nothing rather than
            // failing, so it would have read as a permanent, unremarkable zero.
            "Launcher.loadHomeApps",
            "Launcher.loadIcon",
            "Launcher.renderHome",
        ).map { TraceSectionMetric(it, TraceSectionMetric.Mode.Sum) }
    }
}
