package com.genericlauncher.launcher;

import android.app.Application;
import android.os.Build;
import android.os.StrictMode;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Application entry point. Installs a crash handler that writes each uncaught exception to
 * {@code files/crashes/} before chaining to the system handler, so tester crashes can be pulled
 * with adb (see dev-debug.sh) even after logcat has rotated. Keeps the newest
 * {@link #MAX_CRASH_FILES} files.
 */
public final class LauncherApp extends Application {

    private static final String TAG = "LauncherApp";
    private static final int MAX_CRASH_FILES = 10;

    @Override
    public void onCreate() {
        super.onCreate();
        enableStrictModeIfDebug();
        final Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            writeCrashFile(thread, e);
            if (system != null) system.uncaughtException(thread, e);
        });
    }

    /**
     * Debug-only StrictMode. Catches the two classes of regression that a benchmark would
     * otherwise show only as unexplained drift: blocking disk/network I/O on the main thread,
     * and leaked Activities.
     *
     * <p><b>penaltyLog, never penaltyDeath.</b> This app is the default HOME screen on the
     * developer's daily driver — a StrictMode kill would take the home screen down mid-use — and
     * some violations originate in framework and OEM code we do not control. Violations are for
     * reading via {@code ./dev-debug.sh strict}, not for crashing on.
     *
     * <p>Release builds skip this entirely: StrictMode's instrumentation is itself a measurable
     * cost, and leaving it on would make benchmark numbers describe the wrong binary.
     */
    private void enableStrictModeIfDebug() {
        if (!BuildConfig.DEBUG) return;
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build());
    }

    /** Package-private so the naming rule can be tested without staging a real crash. */
    void writeCrashFile(Thread thread, Throwable e) {
        try {
            File dir = new File(getFilesDir(), "crashes");
            if (!dir.exists() && !dir.mkdirs()) return;
            trimOldest(dir);
            File out = uniqueCrashFile(dir);
            try (PrintWriter w = new PrintWriter(new FileOutputStream(out))) {
                w.println("build: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.GIT_HASH + ")");
                w.println("device: " + Build.MANUFACTURER + " " + Build.MODEL
                        + ", API " + Build.VERSION.SDK_INT);
                w.println("thread: " + thread.getName());
                e.printStackTrace(w);
            }
        } catch (Throwable t) {
            // Never let the crash handler itself crash — that would mask the original exception.
            Log.e(TAG, "failed to write crash file", t);
        }
    }

    /**
     * A crash filename that is not already taken.
     *
     * <p>The stamp used to be second-resolution, so a crash cascade — a bad broadcast handled on
     * two threads, or a rethrow chain — silently overwrote the trace that mattered. Milliseconds
     * make a collision unlikely; the suffix loop makes overwriting impossible. Names still sort
     * chronologically to the millisecond, which is what {@link #trimOldest} relies on.
     */
    private static File uniqueCrashFile(File dir) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File f = new File(dir, "crash-" + stamp + ".txt");
        for (int i = 2; f.exists() && i <= MAX_CRASH_FILES; i++) {
            f = new File(dir, "crash-" + stamp + "-" + i + ".txt");
        }
        return f;
    }

    /** Deletes oldest files so at most MAX_CRASH_FILES - 1 remain (one slot for the new file). */
    private static void trimOldest(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length < MAX_CRASH_FILES) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (int i = 0; i <= files.length - MAX_CRASH_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
