package com.genericlauncher.launcher;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for the pure guards {@link SettingsActivity} applies to data arriving from outside
 * the app: the crash-log share payload and the notification-listener grant string.
 *
 * <p>Robolectric is needed only for {@code ComponentName.unflattenFromString}; neither helper
 * touches an Activity instance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SettingsActivityGuardsTest {

    // -------------------------------------------------------------------------
    // M15 — crash-log payload bounding
    // -------------------------------------------------------------------------

    @Test
    public void appendCrashLog_truncatesAFileLargerThanThePerFileCap() {
        StringBuilder sb = new StringBuilder();
        SettingsActivity.appendCrashLog(sb, "crash-1.txt", repeat('x', 5_000), 100, 1_000_000);

        assertTrue(sb.toString().startsWith("==== crash-1.txt ===="));
        assertTrue("expected a truncation marker", sb.toString().contains("truncated"));
        assertTrue("per-file body must be clamped", sb.length() < 300);
    }

    @Test
    public void appendCrashLog_keepsShortFilesVerbatim() {
        StringBuilder sb = new StringBuilder();
        SettingsActivity.appendCrashLog(sb, "crash-1.txt", "boom\nat Foo.bar\n", 10_000, 1_000_000);

        assertTrue(sb.toString().contains("boom\nat Foo.bar\n"));
        assertFalse(sb.toString().contains("truncated"));
    }

    @Test
    public void appendCrashLog_signalsStopOnceTheTotalCapIsReached() {
        StringBuilder sb = new StringBuilder();
        assertTrue(SettingsActivity.appendCrashLog(sb, "a", repeat('x', 50), 1_000, 500));
        assertFalse(SettingsActivity.appendCrashLog(sb, "b", repeat('x', 600), 1_000, 500));
    }

    /**
     * The regression itself: with the cap checked <em>before</em> the append, one huge file
     * blew the bound wide open. The payload must stay within a small, predictable margin of
     * the total cap no matter how large the individual files are.
     */
    @Test
    public void appendCrashLog_totalStaysBoundedWithHugeFiles() {
        StringBuilder sb = new StringBuilder();
        String huge = repeat('x', 900_000); // a StackOverflowError trace
        for (int i = 0; i < 20; i++) {
            if (!SettingsActivity.appendCrashLog(sb, "crash-" + i + ".txt", huge,
                    SettingsActivity.MAX_CRASH_FILE_CHARS,
                    SettingsActivity.MAX_CRASH_TOTAL_CHARS)) {
                break;
            }
        }
        // Worst case is "cap was not yet reached, then one more whole truncated file landed".
        int bound = SettingsActivity.MAX_CRASH_TOTAL_CHARS
                + SettingsActivity.MAX_CRASH_FILE_CHARS + 1_000;
        assertTrue("payload was " + sb.length() + " chars, bound " + bound, sb.length() <= bound);
        // And that bound has to leave real headroom under the 1 MB Binder limit: EXTRA_TEXT is
        // parcelled as UTF-16, so every char costs two bytes.
        assertTrue("payload must stay well under the Binder limit", bound * 2 < 900_000);
    }

    // -------------------------------------------------------------------------
    // L3 — notification-listener grant matching
    // -------------------------------------------------------------------------

    @Test
    public void notificationListener_matchesOwnPackageExactly() {
        assertTrue(NotificationAccess.grantedTo(
                "com.genericlauncher.launcher/com.genericlauncher.launcher.NotificationDotsService",
                "com.genericlauncher.launcher"));
    }

    @Test
    public void notificationListener_matchesWithinAColonSeparatedList() {
        String flat = "com.other.app/com.other.app.Listener"
                + ":com.genericlauncher.launcher/com.genericlauncher.launcher.NotificationDotsService";
        assertTrue(NotificationAccess.grantedTo(flat, "com.genericlauncher.launcher"));
    }

    @Test
    public void notificationListener_rejectsPrefixImpostor() {
        // The bug: startsWith() said yes to any package sharing our prefix.
        assertFalse(NotificationAccess.grantedTo(
                "com.genericlauncher.launcher.pro/com.evil.Listener", "com.genericlauncher.launcher"));
        assertFalse(NotificationAccess.grantedTo(
                "com.genericlauncher.launcher2/com.evil.Listener", "com.genericlauncher.launcher"));
    }

    @Test
    public void notificationListener_toleratesJunkEntries() {
        assertFalse(NotificationAccess.grantedTo(null, "com.genericlauncher.launcher"));
        assertFalse(NotificationAccess.grantedTo("", "com.genericlauncher.launcher"));
        assertFalse(NotificationAccess.grantedTo("::", "com.genericlauncher.launcher"));
        assertFalse(NotificationAccess.grantedTo(
                "com.genericlauncher.launcher", "com.genericlauncher.launcher")); // no component half
    }

    @Test
    public void notificationListener_ignoresSurroundingWhitespace() {
        assertTrue(NotificationAccess.grantedTo(
                "  com.genericlauncher.launcher/com.genericlauncher.launcher.NotificationDotsService  ",
                "com.genericlauncher.launcher"));
    }

    private static String repeat(char c, int n) {
        char[] buf = new char[n];
        java.util.Arrays.fill(buf, c);
        return new String(buf);
    }
}
