package com.genericlauncher.launcher;

import android.os.Handler;
import android.os.Looper;

/**
 * Collapses a burst of reload requests into a single delayed run.
 *
 * <p>Installing or updating one app produces several package broadcasts, and a Play Store batch
 * update multiplies that by every app in the batch. Reloading on each of them queues a full app
 * re-enumeration plus icon/colour pass on {@code AsyncTask}'s <em>serial</em> executor, so the
 * launcher spends minutes working through a backlog of identical loads. Requests are therefore
 * rescheduled rather than queued: only the last one in a burst survives.
 *
 * <p>Not thread-safe — every caller is a main-thread {@code BroadcastReceiver}.
 */
final class ReloadCoalescer {

    /**
     * Long enough to swallow the ADDED/REPLACED pair a single install emits and the gaps between
     * apps in a store batch, short enough that a deliberate install still appears to be live.
     */
    static final long DEFAULT_DELAY_MS = 500L;

    private final Handler handler;
    private final long delayMs;
    private final Runnable dispatch;
    private boolean pending;

    ReloadCoalescer(Runnable task) {
        this(new Handler(Looper.getMainLooper()), task, DEFAULT_DELAY_MS);
    }

    ReloadCoalescer(Handler handler, Runnable task, long delayMs) {
        this.handler = handler;
        this.delayMs = delayMs;
        this.dispatch = () -> {
            pending = false;
            task.run();
        };
    }

    /** Schedules the task, discarding any earlier request that has not run yet. */
    void request() {
        handler.removeCallbacks(dispatch);
        handler.postDelayed(dispatch, delayMs);
        pending = true;
    }

    /**
     * Drops any pending request. Must be called from {@code onDestroy} — a delayed message
     * outlives the Activity that posted it and would otherwise run against a dead view tree.
     */
    void cancel() {
        handler.removeCallbacks(dispatch);
        pending = false;
    }

    /** True while a requested run is still waiting for its delay. Package-private for tests. */
    boolean hasPendingRequest() {
        return pending;
    }
}
