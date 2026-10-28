package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Launching MainActivity on a fresh install runs the first-run seeder exactly once. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FirstRunSeedingTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        HomeScreenStore.resetForTesting();
        new java.io.File(ctx.getFilesDir(), "homescreen.json").delete();
    }

    @Test
    public void freshLaunch_setsSeededFlag() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        assertTrue(Settings.isHomeSeeded(ctx));
        controller.destroy();
    }

    @Test
    public void launchWithExistingLayout_doesNotTouchIt() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 2, 2, "com.example.existing"));

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();

        assertTrue(Settings.isHomeSeeded(ctx));
        assertEquals(1, store.getItems().size());
        assertEquals("com.example.existing", store.getItems().get(0).getPackageName());
        controller.destroy();
    }
}
