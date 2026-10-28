package com.genericlauncher.launcher.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/**
 * The `benchmark` variant of the launcher — note the `.benchmark` suffix from
 * app/build.gradle. Never the plain `com.genericlauncher.launcher`: these benchmarks force-stop and
 * drop caches for the target repeatedly, and pointing that at the daily driver's real home
 * screen would be destructive.
 */
const val TARGET_PACKAGE = "com.genericlauncher.launcher.benchmark"

/** How many iterations each benchmark runs. Enough for macrobenchmark's median to settle. */
const val ITERATIONS = 10

/**
 * Waits until the launcher has actually finished starting, rather than until its first frame.
 *
 * <p>This distinction is the whole reason the app calls `reportFullyDrawn()`. `MainActivity`
 * draws its window almost immediately, but `loadApps()` hides the content container behind a
 * progress spinner until every installed app has been enumerated and colour-analysed — so a
 * benchmark that stopped at first frame would report a fast startup while the user is still
 * looking at a spinner.
 *
 * <p>Waiting on the content container becoming visible keeps the *measured* window honest even
 * for metrics that do not read `reportFullyDrawn` themselves (FrameTimingMetric, memory).
 */
fun MacrobenchmarkScope.waitUntilLauncherReady() {
    // Wait for the HomescreenView to EXIST — a positive signal, deliberately.
    //
    // This used to wait for the progress spinner to disappear. That worked only by accident:
    // it depended on the spinner being shown during load, and when the two-phase load stopped
    // showing one on the home screen, `Until.gone` matched nothing and returned immediately.
    // Benchmarks then swiped before `renderMode()` had wired up the swipe listener, and the
    // drawer never opened. An absence-based wait cannot distinguish "finished" from "never
    // started"; an existence-based one can.
    //
    // HomescreenView is built at the end of renderMode(), after the priority load lands, so its
    // presence means the home screen is drawn and interactive.
    val ready = device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "homescreen_view")), 10_000)
    check(ready) {
        "HomescreenView never appeared within 10s — the launcher did not finish starting, so " +
            "nothing measured after this point would be meaningful."
    }
    device.waitForIdle()
}

/**
 * Number of interpolated motion steps in the drawer-opening swipe.
 *
 * <p>UiAutomator emits roughly one `ACTION_MOVE` every 5ms per step, so this is a ~400ms drag.
 * That duration is not cosmetic — it was measured. `HomescreenView`'s `GestureDetector` decides
 * from intermediate `ACTION_MOVE` events, and a swipe of the same distance over ~200ms produces
 * too few of them: the drawer simply never opens.
 *
 * <p>The original value here was 10 steps (~50ms), and it silently measured an idle homescreen —
 * `FrameTimingMetric` happily reported ~19 frames of nothing. Hence [assertDrawerOpen].
 */
private const val SWIPE_STEPS = 80

/**
 * Performs the swipe-up gesture that opens the app drawer (see `HomescreenView`'s
 * `OnSwipeUpListener`, wired in `MainActivity.onCreate`), then verifies it worked.
 *
 * <p>Swipes between 75% and 25% of screen height — clear of the bottom system-gesture inset,
 * which would hand the drag to the navigation bar, and of the notification shade at the top.
 */
fun MacrobenchmarkScope.swipeUpToOpenDrawer() {
    val w = device.displayWidth
    val h = device.displayHeight
    device.swipe(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.25).toInt(), SWIPE_STEPS)
    assertDrawerOpen()
}

/**
 * Fails loudly if the drawer is not open.
 *
 * <p>This exists because the alternative is failing quietly. A gesture that does not register
 * leaves the launcher sitting on the homescreen, and every frame metric still returns a
 * perfectly plausible number — measured from an idle screen. There is no value that looks wrong,
 * so nothing catches it except an explicit check.
 *
 * <p>`toolbar_row` is the signal: `renderMode()` sets it VISIBLE for drawer modes and GONE on
 * the homescreen, and a GONE view is absent from the UiAutomator hierarchy entirely.
 */
fun MacrobenchmarkScope.assertDrawerOpen() {
    val opened = device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "toolbar_row")), 3_000)
    check(opened) {
        "Drawer did not open — the swipe was not recognised, so any frame metric collected here " +
            "would describe an idle homescreen. Check SWIPE_STEPS against HomescreenView's " +
            "GestureDetector, and that the swipe avoids the system gesture insets."
    }
}
