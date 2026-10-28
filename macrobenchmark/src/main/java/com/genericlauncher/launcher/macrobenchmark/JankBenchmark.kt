package com.genericlauncher.launcher.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for the two interactions the user performs most: opening the drawer, and
 * scrolling it.
 *
 * <p>`FrameTimingMetric` reports `frameDurationCpuMs` at P50/P90/P99. Watch P99, not P50 — a
 * launcher that is smooth on average and drops one frame per swipe reads as "janky" to a human,
 * and only the tail shows that. On a 120Hz panel the budget is 8.3ms; on 60Hz, 16.7ms.
 *
 * <p>These run `StartupMode.WARM` rather than measuring from cold, so the measured block
 * contains the interaction and not the startup work — otherwise startup frames dominate the
 * distribution and the interaction's own jank disappears into the tail.
 */
@RunWith(AndroidJUnit4::class)
class JankBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    /**
     * The swipe-up that opens the app drawer from the homescreen. This animates a full-screen
     * overlay while `renderMode()` builds the drawer's contents on the main thread, so it is the
     * most likely place for a dropped frame to be visible.
     */
    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun openDrawer() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            FrameTimingMetric(),
            // Diagnostic, and worth keeping. Two different things can make this animation drop
            // frames now that the drawer is reachable before the full app load finishes:
            // background icon decoding competing for CPU (loadIcon), or the load landing
            // mid-animation and rebuilding the whole overlay under the user (renderDrawer).
            // The frame numbers alone cannot tell those apart, and they need opposite fixes.
            TraceSectionMetric("Launcher.renderDrawer", TraceSectionMetric.Mode.Sum),
            TraceSectionMetric("Launcher.loadIcon", TraceSectionMetric.Mode.Sum),
        ),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitUntilLauncherReady()
        },
    ) {
        swipeUpToOpenDrawer()
        device.waitForIdle()
    }

    /**
     * Scrolling the app grid. Exercises RecyclerView binding plus the per-icon BadgeDrawable
     * work, which is the part most likely to regress as badge features grow.
     */
    @Test
    fun scrollDrawer() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitUntilLauncherReady()
            swipeUpToOpenDrawer()
            device.waitForIdle()
        },
    ) {
        // swipeUpToOpenDrawer() in setupBlock already asserted the drawer is open, so a missing
        // container here means the drawer layout changed, not that the gesture failed.
        val content = device.findObject(By.res(TARGET_PACKAGE, "content_container"))
            ?: error(
                "content_container not found even though the drawer reported open — the drawer " +
                    "layout has changed; update this lookup."
            )
        // setGestureMargin keeps the fling clear of the system gesture insets at the screen
        // edges, which would otherwise be stolen by the navigation bar rather than reaching the
        // app — a silent no-op that reads as a suspiciously perfect frame time.
        content.setGestureMargin(device.displayWidth / 5)
        content.fling(Direction.DOWN)
        content.fling(Direction.UP)
    }
}
