package com.genericlauncher.launcher.livedin;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashSet;
import java.util.Set;

import static com.genericlauncher.launcher.livedin.Wear.DAY;
import static org.junit.Assert.*;

/**
 * Age bucketing. Pure maths, but run under Robolectric with the rest of the suite so one command
 * covers it.
 */
@RunWith(RobolectricTestRunner.class)
public class WearTest {

    private static final long NOW = 2_000L * DAY;

    private static int full(long launches, long lastLaunch, long seenSince) {
        return Wear.look(Wear.Variant.FULL, "com.example", launches, lastLaunch, seenSince, 0,
                NOW, 0);
    }

    @Test
    public void packRoundTrips() {
        int look = Wear.pack(3, 2, 1);
        assertEquals(3, Wear.patinaOf(look));
        assertEquals(2, Wear.dustOf(look));
        assertEquals(1, Wear.cobwebOf(look));
        assertEquals(Wear.PRISTINE, Wear.pack(0, 0, 0));
    }

    @Test
    public void freshInstallShowsNoAgeAtAll() {
        // The launcher was installed just now and has launched nothing: nothing may look old.
        assertEquals(Wear.PRISTINE, full(0, 0, NOW));
        assertEquals(Wear.PRISTINE, full(0, 0, NOW - 3 * DAY));
    }

    @Test
    public void dustGathersWithNeglect() {
        assertEquals(0, Wear.dustOf(full(0, 0, NOW - 3 * DAY)));
        assertEquals(1, Wear.dustOf(full(0, 0, NOW - 4 * DAY)));
        assertEquals(2, Wear.dustOf(full(0, 0, NOW - 10 * DAY)));
        assertEquals(3, Wear.dustOf(full(0, 0, NOW - 21 * DAY)));
        assertEquals(3, Wear.dustOf(full(0, 0, NOW - 400 * DAY)));
    }

    @Test
    public void cobwebsNeedAMonth() {
        assertEquals(0, Wear.cobwebOf(full(0, 0, NOW - 29 * DAY)));
        assertEquals(1, Wear.cobwebOf(full(0, 0, NOW - 30 * DAY)));
        assertEquals(2, Wear.cobwebOf(full(0, 0, NOW - 75 * DAY)));
    }

    @Test
    public void aLaunchBlowsTheDustOff() {
        long longAgo = NOW - 90 * DAY;
        int dusty = full(1, longAgo, longAgo);
        int launched = full(2, NOW, longAgo);
        assertTrue(Wear.dustOf(dusty) > 0);
        assertTrue(Wear.cobwebOf(dusty) > 0);
        assertEquals(0, Wear.dustOf(launched));
        assertEquals(0, Wear.cobwebOf(launched));
        assertTrue(Wear.dustCleared(dusty, launched));
        assertFalse(Wear.dustCleared(launched, dusty));
    }

    @Test
    public void theLaterOfLaunchAndBaselineCounts() {
        // Launched long ago, but the app was (re)installed yesterday: it is not dusty.
        assertEquals(0, Wear.dustOf(full(5, NOW - 60 * DAY, NOW - DAY)));
    }

    @Test
    public void patinaComesWithUse() {
        assertEquals(0, Wear.patinaOf(full(7, NOW, 0)));
        assertEquals(1, Wear.patinaOf(full(8, NOW, 0)));
        assertEquals(2, Wear.patinaOf(full(25, NOW, 0)));
        assertEquals(3, Wear.patinaOf(full(80, NOW, 0)));
        assertEquals(3, Wear.patinaOf(full(100_000, NOW, 0)));
    }

    @Test
    public void usageAccessForegroundTimeAddsToPatina() {
        long hour = 60 * Wear.MINUTE;
        // 2 hours in the foreground ≈ 40 uses, with no launches through the launcher at all.
        int look = Wear.look(Wear.Variant.FULL, "com.x", 0, NOW, NOW, 2 * hour, NOW, 0);
        assertEquals(2, Wear.patinaOf(look));
    }

    @Test
    public void wellWornAndAbandonedShowsBoth() {
        int look = full(200, NOW - 40 * DAY, 0);
        assertEquals(3, Wear.patinaOf(look));
        assertEquals(3, Wear.dustOf(look));
        assertEquals(1, Wear.cobwebOf(look));
    }

    @Test
    public void variantsSwitchTheirHalvesOff() {
        long longAgo = NOW - 90 * DAY;
        int off = Wear.look(Wear.Variant.OFF, "k", 200, longAgo, longAgo, 0, NOW, 0);
        int patina = Wear.look(Wear.Variant.PATINA, "k", 200, longAgo, longAgo, 0, NOW, 0);
        int dust = Wear.look(Wear.Variant.DUST, "k", 200, longAgo, longAgo, 0, NOW, 0);
        assertEquals(Wear.PRISTINE, off);
        assertEquals(Wear.pack(3, 0, 0), patina);
        assertEquals(Wear.pack(0, 3, 2), dust);
    }

    @Test
    public void timelapseShowsEveryBucketOnAFreshInstall() {
        // The point of the demo: nothing launched, installed this second, and still a screenful
        // of icons shows worn, dusty and cobwebbed ones side by side.
        Set<Integer> patina = new HashSet<>();
        Set<Integer> dust = new HashSet<>();
        Set<Integer> cobweb = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            int look = Wear.look(Wear.Variant.TIMELAPSE, "com.app" + i, 0, 0, NOW, 0, NOW, NOW);
            patina.add(Wear.patinaOf(look));
            dust.add(Wear.dustOf(look));
            cobweb.add(Wear.cobwebOf(look));
        }
        assertEquals(4, patina.size());
        assertEquals(4, dust.size());
        assertEquals(3, cobweb.size());
    }

    @Test
    public void timelapseIsStablePerApp() {
        int a = Wear.look(Wear.Variant.TIMELAPSE, "com.same", 0, 0, NOW, 0, NOW, NOW);
        int b = Wear.look(Wear.Variant.TIMELAPSE, "com.same", 0, 0, NOW, 0, NOW, NOW);
        assertEquals(a, b);
    }

    @Test
    public void timelapseAgesADayAMinute() {
        long start = NOW;
        String key = freshTimelapseKey(start);
        // Launched at the start of the demo, then left alone for four real minutes: four days.
        int after3 = Wear.look(Wear.Variant.TIMELAPSE, key, 1, start, NOW, 0,
                start + 3 * Wear.MINUTE, start);
        int after4 = Wear.look(Wear.Variant.TIMELAPSE, key, 1, start, NOW, 0,
                start + 4 * Wear.MINUTE, start);
        assertEquals(0, Wear.dustOf(after3));
        assertEquals(1, Wear.dustOf(after4));
    }

    @Test
    public void timelapseLaunchesCountExtraTowardPatina() {
        String key = freshTimelapseKey(NOW);
        int base = Wear.patinaOf(Wear.look(Wear.Variant.TIMELAPSE, key, 0, 0, NOW, 0, NOW, NOW));
        int tapped = Wear.patinaOf(Wear.look(Wear.Variant.TIMELAPSE, key, 3, NOW, NOW, 0, NOW, NOW));
        assertTrue("three demo taps should visibly polish an icon", tapped > base);
    }

    @Test
    public void bucketsAreMonotonic() {
        int prev = 0;
        for (long d = 0; d < 120; d++) {
            int level = Wear.dustLevel(d * DAY) + Wear.cobwebLevel(d * DAY);
            assertTrue(level >= prev);
            prev = level;
        }
        prev = 0;
        for (int uses = 0; uses < 200; uses++) {
            int level = Wear.patinaLevel(uses);
            assertTrue(level >= prev);
            prev = level;
        }
    }

    /** A key whose invented history has no uses, so a test can see its own launches. */
    private static String freshTimelapseKey(long start) {
        for (int i = 0; i < 1000; i++) {
            String key = "com.fresh" + i;
            if (Wear.fakeUses(Wear.mix(key.hashCode())) == 0) return key;
        }
        throw new AssertionError("no zero-use key in 1000 tries");
    }
}
