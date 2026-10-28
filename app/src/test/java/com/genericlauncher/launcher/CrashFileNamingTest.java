package com.genericlauncher.launcher;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import static org.junit.Assert.*;

/**
 * Crash files must never overwrite each other.
 *
 * <p>The stamp was second-resolution, so a crash cascade — the same failure hit on two threads, or
 * a rethrow a few milliseconds later — left one file where there should have been two, and the
 * survivor was usually the least informative one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CrashFileNamingTest {

    private LauncherApp app;
    private File crashDir;

    @Before
    public void setUp() {
        app = (LauncherApp) ApplicationProvider.<Application>getApplicationContext();
        crashDir = new File(app.getFilesDir(), "crashes");
        File[] existing = crashDir.listFiles();
        if (existing != null) for (File f : existing) assertTrue(f.delete());
    }

    private File[] crashFiles() {
        File[] files = crashDir.listFiles();
        return files == null ? new File[0] : files;
    }

    @Test
    public void backToBackCrashesEachGetTheirOwnFile() {
        app.writeCrashFile(Thread.currentThread(), new IllegalStateException("first"));
        app.writeCrashFile(Thread.currentThread(), new IllegalStateException("second"));
        app.writeCrashFile(Thread.currentThread(), new IllegalStateException("third"));

        assertEquals("each crash keeps its own trace", 3, crashFiles().length);
    }

    @Test
    public void aSingleCrashStillWritesOneFile() {
        app.writeCrashFile(Thread.currentThread(), new RuntimeException("boom"));

        File[] files = crashFiles();
        assertEquals(1, files.length);
        assertTrue(files[0].getName().startsWith("crash-"));
        assertTrue(files[0].getName().endsWith(".txt"));
        assertTrue("the trace must actually be in there", files[0].length() > 0);
    }

    @Test
    public void theCapIsStillHonouredAcrossASameMillisecondBurst() {
        // 20 crashes fast enough to share timestamps must not blow past the 10-file cap.
        for (int i = 0; i < 20; i++) {
            app.writeCrashFile(Thread.currentThread(), new RuntimeException("crash " + i));
        }

        assertTrue("kept " + crashFiles().length + " files", crashFiles().length <= 10);
    }
}
