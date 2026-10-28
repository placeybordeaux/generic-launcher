package com.genericlauncher.launcher.personality;

/**
 * A framework-free view of one installed app, as the drawer needs to see it.
 *
 * <p>The launcher's real {@code AppInfo} implements this interface. Keeping the seam free of
 * any {@code android.*} type is the whole trick: a {@link DrawerStrategy} written against
 * {@code DrawerApp} is a pure function, so it compiles and unit-tests on a plain JVM with no
 * Android SDK — which is exactly the loop a personalizing agent wants (write a strategy, run
 * one check, get told precisely what it broke).
 */
public interface DrawerApp {

    /** User-visible label, e.g. "Calendar". Never null; may be empty. */
    String label();

    /** Stable unique key: the bare package for personal apps, {@code "pkg#serial"} for work. */
    String key();

    /** Foreground-usage score over the lookback window; {@code 0} when unknown. */
    long usageScore();

    /** True if this app lives in a managed (work) profile. */
    boolean isWork();
}
