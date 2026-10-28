package com.genericlauncher.launcher;

import android.content.Context;
import android.os.SystemClock;
import android.view.Choreographer;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * The single, process-wide animation clock for notification badges.
 *
 * <p>One {@link Choreographer.FrameCallback} drives <em>every</em> animated badge on screen —
 * never one animator per badge. Registrants hand in an {@code invalidate} runnable; each frame the
 * animator runs them all and reschedules itself. Everything derives its colour and effect timing
 * from {@link #phase()}, so all badges stay in lockstep.
 *
 * <p><b>Threading: main thread only.</b> {@link Choreographer#getInstance()} requires a
 * {@link android.os.Looper}, and the callback registry is a plain {@link ArrayList} with no
 * synchronisation. Every method here must be called from the UI thread. (Off-Looper calls degrade
 * silently to "no animation" rather than crashing — see {@link #schedule()}.)
 *
 * <p><b>Lifetime:</b> callbacks are held weakly and dead references are pruned on every frame and
 * on every register/unregister, mirroring {@link NotificationDotsService}'s listener registry — a
 * view that forgets to unregister is never leaked.
 *
 * <p><b>Gating:</b> the frame loop only runs while the registry is non-empty. Callers decide
 * whether animation is wanted at all via {@link #isAnimated(Context)} and simply do not register
 * when it returns false, so a static-badge configuration costs zero frames.
 */
public final class BadgeAnimator {

    private BadgeAnimator() {}

    /** Registered invalidate callbacks, held weakly. Main thread only. */
    private static final List<WeakReference<Runnable>> CALLBACKS = new ArrayList<>();

    /** True while a frame callback is posted and pending. */
    private static boolean scheduled;

    /** True after {@link #pause()} until {@link #resume()} — a hard stop, no frames at all. */
    private static boolean paused;

    private static final Choreographer.FrameCallback FRAME = frameTimeNanos -> {
        scheduled = false;
        boolean anyAlive = false;
        for (int i = CALLBACKS.size() - 1; i >= 0; i--) {
            Runnable r = CALLBACKS.get(i).get();
            if (r == null) {
                CALLBACKS.remove(i);   // dead ref — prune in place
            } else {
                anyAlive = true;
                r.run();
            }
        }
        if (anyAlive) schedule();
    };

    // -------------------------------------------------------------------------
    // Public API — main thread only
    // -------------------------------------------------------------------------

    /**
     * Registers an invalidate callback and starts the frame loop if it isn't already running.
     * Re-registering the same runnable is a no-op. Callers should first check
     * {@link #isAnimated(Context)} and skip registration entirely when nothing animates.
     */
    @MainThread
    public static void register(@Nullable Runnable invalidate) {
        if (invalidate == null) return;
        for (int i = CALLBACKS.size() - 1; i >= 0; i--) {
            Runnable r = CALLBACKS.get(i).get();
            if (r == null) CALLBACKS.remove(i);
            else if (r == invalidate) return;   // already registered
        }
        CALLBACKS.add(new WeakReference<>(invalidate));
        schedule();
    }

    /** Unregisters a callback (and prunes dead refs), stopping the loop when nothing is left. */
    @MainThread
    public static void unregister(@Nullable Runnable invalidate) {
        for (int i = CALLBACKS.size() - 1; i >= 0; i--) {
            Runnable r = CALLBACKS.get(i).get();
            if (r == null || r == invalidate) CALLBACKS.remove(i);
        }
        if (CALLBACKS.isEmpty()) stop();
    }

    /**
     * Hard stop: cancels the pending frame and refuses to reschedule until {@link #resume()}.
     * Wire this into {@code Activity.onPause} so a backgrounded launcher burns no frames.
     * Registrations survive a pause.
     */
    @MainThread
    public static void pause() {
        paused = true;
        stop();
    }

    /** Resumes the frame loop if anything is still registered. Wire into {@code onResume}. */
    @MainThread
    public static void resume() {
        paused = false;
        schedule();
    }

    /**
     * Whether the current badge settings need a frame loop at all: rainbow cycles its hue, and
     * shimmer/glow are animations in their own right. Red/complementary with both effects off is
     * a completely static badge.
     *
     * <p>Badges being switched off short-circuits everything — otherwise a user with badges
     * disabled but shimmer left on would drive a full-rate frame loop redrawing nothing.
     * {@link BadgeDrawable} reaches the same conclusion via its {@code count > 0} check, but
     * direct-canvas painters such as {@link RainbowFieldView} rely on this.
     */
    public static boolean isAnimated(@Nullable Context c) {
        if (c == null) return false;
        if (!Settings.getNotifDots(c)) return false;
        return BadgeConfig.from(c).isAnimated();
    }

    /**
     * The shared animation phase in {@code [0,1)}. Derived straight from
     * {@link SystemClock#uptimeMillis()} so every badge sampling it within a frame — including
     * ones drawn by views that aren't registered — gets the same value.
     */
    public static float phase() {
        return BadgePalette.rainbowPhase(SystemClock.uptimeMillis());
    }

    // -------------------------------------------------------------------------
    // Test helper
    // -------------------------------------------------------------------------

    /** Clears all state. For use in unit tests only. */
    static void resetForTesting() {
        stop();
        CALLBACKS.clear();
        paused = false;
    }

    /** Number of live registrations. Test-visibility only. */
    static int registeredCountForTesting() {
        int n = 0;
        for (WeakReference<Runnable> ref : CALLBACKS) if (ref.get() != null) n++;
        return n;
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private static void schedule() {
        if (scheduled || paused || CALLBACKS.isEmpty()) return;
        try {
            Choreographer.getInstance().postFrameCallback(FRAME);
            scheduled = true;
        } catch (RuntimeException e) {
            // No Looper prepared on this thread. The animator is documented main-thread-only;
            // degrade to static badges rather than taking the process down.
            scheduled = false;
        }
    }

    private static void stop() {
        if (!scheduled) return;
        scheduled = false;
        try {
            Choreographer.getInstance().removeFrameCallback(FRAME);
        } catch (RuntimeException ignored) {
            // Same no-Looper case as above — nothing was ever posted.
        }
    }
}
