package com.genericlauncher.launcher.livedin;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.Nullable;

import com.genericlauncher.launcher.AppInfo;
import com.genericlauncher.launcher.UserKeys;
import com.genericlauncher.launcher.personality.AppDataSink;
import com.genericlauncher.launcher.personality.IconTreatment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lived-in: a launcher whose icons age with use. Wired in through two seams —
 * {@link #TREATMENT} ({@link IconTreatment}: the look, and hearing about launches) and
 * {@link #DATA} ({@link AppDataSink}: each app's install time and Usage Access foreground time,
 * gathered off the main thread). See {@code LIVEDIN.md}.
 *
 * <p>Everything here except {@link #DATA} runs on the main thread.
 */
public final class LivedIn {

    private static final String TAG = "LivedIn";

    static final String PREFS = "livedin";
    static final String KEY_VARIANT = "variant";
    static final String KEY_STYLE = "style";
    static final String KEY_TIMELAPSE_START = "timelapse_start";

    /** Deliberately on: this branch exists to show the effect. */
    static final Wear.Variant DEFAULT_VARIANT = Wear.Variant.FULL;
    static final Wear.Style DEFAULT_STYLE = Wear.Style.BRASS;

    /** How long after the launcher returns a dusty icon may still play its puff. */
    static final long PUFF_WINDOW_MS = 2500;
    /** Time-lapse re-checks every icon this often while the launcher is in front. */
    static final long TIMELAPSE_TICK_MS = 4000;

    // ---- settings, cached: read on every draw, so never from prefs there ----
    private static volatile Wear.Variant variant;
    private static volatile Wear.Style style;
    private static volatile long timelapseStartMs;
    /** Bumped when settings change, so every drawable re-derives its look. */
    private static int settingsGeneration;

    /** key → {firstInstallMs, foregroundMs}, filled by {@link #DATA} on the loader's thread. */
    static final Map<String, long[]> APP_FACTS = new ConcurrentHashMap<>();

    // ---- live drawables and the puff ----
    private static final WeakHashMap<LivedInDrawable, Boolean> LIVE = new WeakHashMap<>();
    /**
     * key → {look the icon had when it was launched, {@link #resumeCount} at that moment}. Drives
     * the puff on the <em>next</em> return to the launcher.
     */
    private static final Map<String, long[]> PENDING_PUFF = new HashMap<>();
    private static boolean resumed;
    private static long resumeCount;
    private static long resumedAtUptime;
    @Nullable private static Context appContext;
    /** Made on resume rather than at class load, so it is bound to the looper that is live. */
    @Nullable private static Handler main;
    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            refresh();
            Handler h = main;
            if (resumed && h != null && getVariantCached() == Wear.Variant.TIMELAPSE) {
                h.postDelayed(this, TIMELAPSE_TICK_MS);
            }
        }
    };

    /**
     * Rendered looks, keyed by app, look, style and pixel size. Shared by every surface, so a
     * drawer scroll that rebinds a cell finds its bitmap already made. Sized in bytes.
     */
    static final LruCache<String, Bitmap> RENDERED = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getAllocationByteCount(); }
    };

    private LivedIn() {}

    // -------------------------------------------------------------------------
    // The seams
    // -------------------------------------------------------------------------

    public static final IconTreatment TREATMENT = new IconTreatment() {
        @Override
        public Drawable treat(Context context, @Nullable Drawable icon, String appKey) {
            if (icon == null) return null;
            remember(context);
            // Wrapped even when the variant is OFF, so switching it on in Settings reaches icons
            // that are already on screen.
            LivedInDrawable d = new LivedInDrawable(icon, appKey);
            LIVE.put(d, Boolean.TRUE);
            return d;
        }

        @Override
        public void onLaunched(Context context, String appKey) {
            try {
                remember(context);
                LaunchLog log = LaunchLog.get(context);
                int before = lookFor(appKey);
                log.recordLaunch(appKey);
                if (Wear.dustCleared(before, lookFor(appKey))) {
                    PENDING_PUFF.put(appKey, new long[]{before, resumeCount});
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "could not record launch of " + appKey, e);
            }
        }

        @Override
        public void onResume(Context context) {
            remember(context);
            resumed = true;
            resumeCount++;
            resumedAtUptime = SystemClock.uptimeMillis();
            if (main != null) main.removeCallbacks(TICK);
            main = new Handler(Looper.getMainLooper());
            // Also what plays the puff and catches up on time passed while away: every icon
            // whose look changed redraws.
            TICK.run();
        }

        @Override
        public void onPause() {
            resumed = false;
            if (main != null) main.removeCallbacks(TICK);
        }
    };

    public static final AppDataSink DATA = (context, apps) -> {
        // Warm the log here, on the worker, so the first draw does not read prefs from disk.
        try {
            LaunchLog.get(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not load the launch log", e);
        }
        for (AppInfo app : apps) {
            try {
                String key = app.getPackageName();
                long installed = 0;
                if (!UserKeys.isWork(key)) {
                    installed = context.getPackageManager()
                            .getPackageInfo(UserKeys.packageOf(key), 0).firstInstallTime;
                }
                APP_FACTS.put(key, new long[]{installed, Math.max(0, app.getUsageScore())});
            } catch (Exception e) {
                // A package gone between list and lookup: it keeps the launcher-wide baseline.
            }
        }
    };

    // -------------------------------------------------------------------------
    // Looks
    // -------------------------------------------------------------------------

    /** The current look for {@code key}. Allocation-free; called from draw. */
    static int lookFor(String key) {
        Context c = appContext;
        if (c == null) return Wear.PRISTINE;
        Wear.Variant v = getVariantCached();
        if (v == Wear.Variant.OFF) return Wear.PRISTINE;
        LaunchLog log = LaunchLog.get(c);
        long[] facts = APP_FACTS.get(key);
        long seenSince = log.baselineMs();
        long foreground = 0;
        if (facts != null) {
            seenSince = Math.max(seenSince, facts[0]);
            foreground = facts[1];
        }
        return Wear.look(v, key, log.count(key), log.lastLaunch(key), seenSince, foreground,
                log.now(), timelapseStartMs);
    }

    /** Changes when anything a look depends on (other than the clock) changes. */
    static int generation() {
        Context c = appContext;
        int logGen = c == null ? 0 : LaunchLog.get(c).generation();
        return settingsGeneration * 31 + logGen;
    }

    /**
     * The look this icon should puff away from, or -1. Only within {@link #PUFF_WINDOW_MS} of the
     * launcher coming back to the front — a puff played while the launched app is opening over
     * the launcher is a puff nobody sees.
     */
    static int pendingPuffFrom(String key) {
        if (!resumed) return -1;
        long[] p = PENDING_PUFF.get(key);
        // Launched during this very resume: the launcher has not been away and back yet.
        if (p == null || p[1] == resumeCount) return -1;
        if (SystemClock.uptimeMillis() - resumedAtUptime > PUFF_WINDOW_MS) {
            PENDING_PUFF.clear();
            return -1;
        }
        return (int) p[0];
    }

    /** Identifies the current stay in front, so one return plays a puff once per drawable. */
    static long resumeToken() { return resumeCount; }

    static boolean puffPending(String key) { return PENDING_PUFF.containsKey(key); }

    /** Re-derives every live icon's look and redraws the ones that changed. Main thread. */
    public static void refresh() {
        // Copied: an invalidate can bind views, which can create drawables, which add to LIVE.
        List<LivedInDrawable> live = new ArrayList<>(LIVE.keySet());
        for (LivedInDrawable d : live) d.refresh();
    }

    // -------------------------------------------------------------------------
    // Settings
    // -------------------------------------------------------------------------

    private static void remember(Context context) {
        Context app = context.getApplicationContext();
        if (app != appContext) {
            // A different Application (only ever a new Robolectric test): its prefs, its settings.
            appContext = app;
            variant = null;
            style = null;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static Wear.Variant getVariantCached() {
        Wear.Variant v = variant;
        if (v == null) {
            Context c = appContext;
            if (c == null) return DEFAULT_VARIANT;
            v = getVariant(c);
        }
        return v;
    }

    static Wear.Style getStyleCached() {
        Wear.Style s = style;
        if (s == null) {
            Context c = appContext;
            if (c == null) return DEFAULT_STYLE;
            s = getStyle(c);
        }
        return s;
    }

    public static Wear.Variant getVariant(Context c) {
        SharedPreferences p = prefs(c);
        Wear.Variant[] all = Wear.Variant.values();
        int i = p.getInt(KEY_VARIANT, DEFAULT_VARIANT.ordinal());
        Wear.Variant v = all[Math.max(0, Math.min(i, all.length - 1))];
        timelapseStartMs = p.getLong(KEY_TIMELAPSE_START, 0);
        variant = v;
        return v;
    }

    public static void setVariant(Context c, Wear.Variant v) {
        SharedPreferences.Editor e = prefs(c).edit().putInt(KEY_VARIANT, v.ordinal());
        if (v == Wear.Variant.TIMELAPSE) {
            // Each time it is chosen the demo starts over from its invented history.
            timelapseStartMs = LaunchLog.get(c).now();
            e.putLong(KEY_TIMELAPSE_START, timelapseStartMs);
        }
        e.apply();
        variant = v;
        settingsChanged(c);
    }

    public static Wear.Style getStyle(Context c) {
        Wear.Style[] all = Wear.Style.values();
        int i = prefs(c).getInt(KEY_STYLE, DEFAULT_STYLE.ordinal());
        Wear.Style s = all[Math.max(0, Math.min(i, all.length - 1))];
        style = s;
        return s;
    }

    public static void setStyle(Context c, Wear.Style s) {
        prefs(c).edit().putInt(KEY_STYLE, s.ordinal()).apply();
        style = s;
        settingsChanged(c);
    }

    /** Forgets every recorded launch — for demos and for second thoughts. */
    public static void forgetUsage(Context c) {
        LaunchLog.get(c).clear();
        settingsChanged(c);
    }

    private static void settingsChanged(Context c) {
        remember(c);
        settingsGeneration++;
        PENDING_PUFF.clear();
        refresh();
    }

    /** Test hook: drop every cached setting and live drawable. */
    static void resetForTests() {
        variant = null;
        style = null;
        timelapseStartMs = 0;
        appContext = null;
        settingsGeneration = 0;
        resumed = false;
        resumeCount = 0;
        LIVE.clear();
        PENDING_PUFF.clear();
        APP_FACTS.clear();
        RENDERED.evictAll();
        if (main != null) main.removeCallbacks(TICK);
        main = null;
        LaunchLog.resetForTests();
    }

    static int liveCountForTests() { return LIVE.size(); }
}
