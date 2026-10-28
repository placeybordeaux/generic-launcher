package com.genericlauncher.launcher.livedin;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.Map;

/**
 * The launcher's own record of what it has launched: a count and a last-launched time per app key.
 *
 * <p>Needs no permission — every launch goes through the launcher, so it simply writes it down.
 * Usage Access (the USAGE drawer mode's source) can add to it, but nothing here depends on it.
 *
 * <p>Persisted in its own {@link SharedPreferences} file, one entry per app key
 * ({@code "com.example"} or {@code "com.example#10"} for a work profile), value
 * {@code "<count>,<lastLaunchMs>"}. Held in memory after the first read; reads happen on the main
 * thread at draw time, so they must stay a map lookup.
 */
public final class LaunchLog {

    /** Wall-clock source, injectable so tests can age the log without waiting. */
    public interface Clock { long now(); }

    static final String PREFS = "livedin_launches";
    /**
     * The "last seen" floor for every app. Reserved: it starts with a character no package name
     * can, so it can never collide with an app key.
     */
    static final String KEY_BASELINE = ".baseline";

    private static LaunchLog instance;

    private final Context owner;
    private final SharedPreferences prefs;
    private final Clock clock;
    private final long baselineMs;
    /** key → {count, lastLaunchMs}. Guarded by {@code this}. */
    private final Map<String, long[]> entries = new HashMap<>();

    /** Bumped on every change, so drawables can tell cheaply whether to look again. */
    private volatile int generation;

    /** The process-wide log. The first call reads prefs from disk; prefer a worker thread. */
    public static synchronized LaunchLog get(Context context) {
        Context app = context.getApplicationContext();
        // One Application per process in the field; Robolectric makes a new one per test, and a
        // log bound to the last test's prefs would leak launches between tests.
        if (instance == null || instance.owner != app) {
            instance = new LaunchLog(app, System::currentTimeMillis, launcherInstallTime(app));
        }
        return instance;
    }

    /** Test hook: forget the singleton so the next {@link #get} re-reads prefs. */
    static synchronized void resetForTests() { instance = null; }

    /**
     * @param installTimeMs the launcher's own install time, or {@code <= 0} if unknown. Becomes the
     *                      baseline the first time the log is created and is then frozen, so an
     *                      upgrade (which keeps prefs) does not move it.
     */
    LaunchLog(Context context, Clock clock, long installTimeMs) {
        this.owner = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.clock = clock;

        long stored = prefs.getLong(KEY_BASELINE, -1L);
        if (stored > 0) {
            baselineMs = stored;
        } else {
            long now = clock.now();
            // A clock-skewed or future install time would make every app look launched in the
            // future; clamp so the worst case is "fresh", never negative ages.
            baselineMs = installTimeMs > 0 ? Math.min(installTimeMs, now) : now;
            prefs.edit().putLong(KEY_BASELINE, baselineMs).apply();
        }

        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (KEY_BASELINE.equals(e.getKey())) continue;
            long[] parsed = parse(e.getValue());
            if (parsed != null) entries.put(e.getKey(), parsed);
        }
    }

    /**
     * Parses a stored {@code "count,last"}. Returns null for anything malformed rather than
     * guessing: a corrupt entry costs that one app its history, not the launcher its startup.
     */
    static long[] parse(Object value) {
        if (!(value instanceof String)) return null;
        String s = (String) value;
        int comma = s.indexOf(',');
        if (comma <= 0) return null;
        try {
            long count = Long.parseLong(s.substring(0, comma));
            long last = Long.parseLong(s.substring(comma + 1));
            if (count < 0 || last < 0) return null;
            return new long[]{count, last};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Records one launch of {@code key}, now. */
    public synchronized void recordLaunch(String key) {
        if (key == null || key.isEmpty() || KEY_BASELINE.equals(key)) {
            throw new IllegalArgumentException("not an app key: " + key);
        }
        long[] e = entries.get(key);
        if (e == null) {
            e = new long[2];
            entries.put(key, e);
        }
        e[0]++;
        e[1] = clock.now();
        prefs.edit().putString(key, e[0] + "," + e[1]).apply();
        generation++;
    }

    public synchronized long count(String key) {
        long[] e = entries.get(key);
        return e == null ? 0 : e[0];
    }

    /** Wall time of the last launch, 0 if never launched by this launcher. */
    public synchronized long lastLaunch(String key) {
        long[] e = entries.get(key);
        return e == null ? 0 : e[1];
    }

    /** When this launcher started keeping the log: no app can be older than this to it. */
    public long baselineMs() { return baselineMs; }

    public long now() { return clock.now(); }

    public int generation() { return generation; }

    /** Forgets every launch (the baseline stays). */
    public synchronized void clear() {
        entries.clear();
        prefs.edit().clear().putLong(KEY_BASELINE, baselineMs).apply();
        generation++;
    }

    private static long launcherInstallTime(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).firstInstallTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return 0;
        }
    }
}
