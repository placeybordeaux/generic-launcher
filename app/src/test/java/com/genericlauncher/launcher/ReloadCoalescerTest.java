package com.genericlauncher.launcher;

import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The package-broadcast debounce.
 *
 * <p>A Play Store batch update fires several broadcasts per app. Each one used to start a full
 * app re-enumeration on AsyncTask's serial executor, so the launcher blanked to a spinner over
 * and over for minutes. Only the last request in a burst may survive.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ReloadCoalescerTest {

    private static final long DELAY = 500L;

    private int runs;
    private ReloadCoalescer coalescer;

    @Before
    public void setUp() {
        runs = 0;
        coalescer = new ReloadCoalescer(
                new Handler(Looper.getMainLooper()), () -> runs++, DELAY);
    }

    private void idle(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    @Test
    public void aBurstOfRequestsRunsTheTaskExactlyOnce() {
        for (int i = 0; i < 20; i++) coalescer.request();

        idle(DELAY);

        assertEquals(1, runs);
    }

    @Test
    public void nothingRunsBeforeTheDelayElapses() {
        coalescer.request();

        idle(DELAY - 1);

        assertEquals(0, runs);
    }

    @Test
    public void aSteadyDripKeepsPushingTheRunBack() {
        // The shape of a store batch: a broadcast every ~400 ms for as long as it takes.
        coalescer.request();
        idle(400);
        coalescer.request();
        idle(400);
        coalescer.request();
        idle(400);
        assertEquals("a request inside the window must reschedule, not queue", 0, runs);

        idle(DELAY); // the batch finishes; the run lands once

        assertEquals(1, runs);
    }

    @Test
    public void requestsSpacedWiderThanTheDelayEachRun() {
        coalescer.request();
        idle(DELAY);
        coalescer.request();
        idle(DELAY);

        assertEquals(2, runs);
    }

    @Test
    public void cancelDropsAPendingRequest() {
        coalescer.request();

        coalescer.cancel();
        idle(DELAY * 10);

        assertEquals("a delayed reload must not outlive onDestroy", 0, runs);
    }

    @Test
    public void cancelIsSafeWithNothingPending() {
        coalescer.cancel();
        coalescer.cancel();

        idle(DELAY * 10);

        assertEquals(0, runs);
    }

    @Test
    public void hasPendingRequestTracksTheWindow() {
        assertFalse(coalescer.hasPendingRequest());

        coalescer.request();
        assertTrue(coalescer.hasPendingRequest());

        idle(DELAY);
        assertFalse(coalescer.hasPendingRequest());
    }

    @Test
    public void hasPendingRequestClearsOnCancel() {
        coalescer.request();

        coalescer.cancel();

        assertFalse(coalescer.hasPendingRequest());
    }

    @Test
    public void aRequestMadeFromInsideTheTaskSchedulesAFreshRun() {
        // Guards against removeCallbacks/postDelayed being ordered so a re-entrant request is
        // swallowed — the reload path itself can trigger a package broadcast.
        ReloadCoalescer[] holder = new ReloadCoalescer[1];
        int[] count = {0};
        holder[0] = new ReloadCoalescer(new Handler(Looper.getMainLooper()), () -> {
            if (count[0]++ == 0) holder[0].request();
        }, DELAY);

        holder[0].request();
        idle(DELAY);
        assertEquals(1, count[0]);

        idle(DELAY);
        assertEquals(2, count[0]);
    }
}
