package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileWriter;

import static org.junit.Assert.*;

/**
 * S4 — an unreadable {@code homescreen.json} suppresses every save for the whole process, and
 * nothing in production ever cleared that. The user saw a blank home screen with no message,
 * rebuilt it, and lost the rebuild on the next launch. The load must be reported once and then
 * acknowledged so saving resumes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CorruptLoadNoticeTest {

    private Context ctx;
    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        HomeScreenStore.resetForTesting();
        File[] leftovers = ctx.getFilesDir().listFiles();
        if (leftovers != null) {
            for (File f : leftovers) {
                if (f.getName().startsWith("homescreen.json")) f.delete();
            }
        }
        ShadowToast.reset();
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    private void launch() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
    }

    @Test
    public void anUnreadableLayoutIsReportedAndAcknowledged() throws Exception {
        writeFile(new File(ctx.getFilesDir(), "homescreen.json"), "this is not json {{{");
        HomeScreenStore store = HomeScreenStore.get(ctx);
        String quarantined = store.corruptFileName();
        assertTrue("precondition", store.isLoadCorrupt());
        assertNotNull("precondition", quarantined);

        launch();

        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("the user must be told their layout could not be read", toast);
        assertTrue("the toast names the file the old bytes were kept as: " + toast,
                toast.contains(quarantined));
        assertFalse("having been told, the user's rebuild must persist", store.isLoadCorrupt());
    }

    @Test
    public void theRebuiltLayoutSurvivesAfterTheNotice() throws Exception {
        File live = new File(ctx.getFilesDir(), "homescreen.json");
        writeFile(live, "{\"items\": broken");
        HomeScreenStore store = HomeScreenStore.get(ctx);

        launch();
        store.addItem(HomeItem.app(0, 0, 0, "com.example.rebuilt"));
        store.flush();

        assertTrue(live.exists());
        assertTrue(readFile(live).contains("com.example.rebuilt"));
    }

    @Test
    public void aNormalLaunchShowsNoNotice() {
        launch();

        assertEquals("nothing to report when the layout loaded fine",
                0, ShadowToast.shownToastCount());
    }

    @Test
    public void theNoticeIsShownOnlyOnce() throws Exception {
        writeFile(new File(ctx.getFilesDir(), "homescreen.json"), "not json");
        HomeScreenStore.get(ctx);

        launch();
        assertEquals("precondition", 1, ShadowToast.shownToastCount());
        controller.recreate();

        assertEquals("recreating the Activity must not re-report an acknowledged load",
                1, ShadowToast.shownToastCount());
    }

    private static void writeFile(File f, String content) throws Exception {
        try (FileWriter fw = new FileWriter(f)) { fw.write(content); }
    }

    private static String readFile(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
