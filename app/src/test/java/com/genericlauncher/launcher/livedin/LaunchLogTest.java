package com.genericlauncher.launcher.livedin;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

/** The launcher's own launch record: what it counts, what it keeps, and what it refuses. */
@RunWith(RobolectricTestRunner.class)
public class LaunchLogTest {

    private Context ctx;
    private long now;
    private final LaunchLog.Clock clock = () -> now;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        now = 1_000L * Wear.DAY;
        LivedIn.resetForTests();
    }

    @After
    public void tearDown() {
        LivedIn.resetForTests();
    }

    private LaunchLog newLog(long installMs) {
        return new LaunchLog(ctx, clock, installMs);
    }

    @Test
    public void countsLaunchesAndRemembersTheLastOne() {
        LaunchLog log = newLog(now);
        assertEquals(0, log.count("com.a"));
        assertEquals(0, log.lastLaunch("com.a"));

        log.recordLaunch("com.a");
        now += 5_000;
        log.recordLaunch("com.a");

        assertEquals(2, log.count("com.a"));
        assertEquals(now, log.lastLaunch("com.a"));
        assertEquals(0, log.count("com.b"));
    }

    @Test
    public void survivesAReloadFromPrefs() {
        LaunchLog log = newLog(now);
        log.recordLaunch("com.a");
        log.recordLaunch("com.a");
        log.recordLaunch("com.work#10");

        LaunchLog reloaded = newLog(now);
        assertEquals(2, reloaded.count("com.a"));
        assertEquals(now, reloaded.lastLaunch("com.a"));
        assertEquals("work-profile keys are kept apart", 1, reloaded.count("com.work#10"));
    }

    @Test
    public void baselineIsTheInstallTimeAndThenFrozen() {
        long installed = now - 3 * Wear.DAY;
        assertEquals(installed, newLog(installed).baselineMs());

        // An update keeps prefs; a later "install time" must not move the baseline.
        now += 40 * Wear.DAY;
        assertEquals(installed, newLog(now).baselineMs());
    }

    @Test
    public void unknownOrFutureInstallTimeFallsBackToNow() {
        assertEquals(now, newLog(0).baselineMs());
        clearPrefs();
        assertEquals("a future install time would age everything negative",
                now, newLog(now + Wear.DAY).baselineMs());
    }

    @Test
    public void generationMovesOnEveryChange() {
        LaunchLog log = newLog(now);
        int g0 = log.generation();
        log.recordLaunch("com.a");
        int g1 = log.generation();
        assertNotEquals(g0, g1);
        log.clear();
        assertNotEquals(g1, log.generation());
    }

    @Test
    public void clearForgetsLaunchesButKeepsTheBaseline() {
        long installed = now - Wear.DAY;
        LaunchLog log = newLog(installed);
        log.recordLaunch("com.a");
        log.clear();
        assertEquals(0, log.count("com.a"));
        LaunchLog reloaded = newLog(now);
        assertEquals(0, reloaded.count("com.a"));
        assertEquals(installed, reloaded.baselineMs());
    }

    @Test
    public void corruptEntriesCostOnlyThatApp() {
        prefs().edit()
                .putString("com.bad", "not,a number")
                .putString("com.nocomma", "12")
                .putString("com.negative", "-1,5")
                .putInt("com.wrongtype", 3)
                .putString("com.good", "7,123")
                .commit();
        LaunchLog log = newLog(now);
        assertEquals(0, log.count("com.bad"));
        assertEquals(0, log.count("com.nocomma"));
        assertEquals(0, log.count("com.negative"));
        assertEquals(0, log.count("com.wrongtype"));
        assertEquals(7, log.count("com.good"));
        assertEquals(123, log.lastLaunch("com.good"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesTheReservedBaselineKey() {
        newLog(now).recordLaunch(LaunchLog.KEY_BASELINE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesAnEmptyKey() {
        newLog(now).recordLaunch("");
    }

    @Test
    public void singletonFollowsTheApplication() {
        LaunchLog a = LaunchLog.get(ctx);
        assertSame(a, LaunchLog.get(ctx));
        a.recordLaunch("com.a");
        assertEquals(1, LaunchLog.get(ctx).count("com.a"));
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(LaunchLog.PREFS, Context.MODE_PRIVATE);
    }

    private void clearPrefs() {
        prefs().edit().clear().commit();
    }
}
