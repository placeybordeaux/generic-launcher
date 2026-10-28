package com.genericlauncher.launcher;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Round-trip tests for the first-run seeding flags. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SettingsSeedFlagsTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
    }

    @Test
    public void homeSeeded_defaultsFalse() {
        assertFalse(Settings.isHomeSeeded(ctx));
    }

    @Test
    public void homeSeeded_roundTrip() {
        Settings.setHomeSeeded(ctx, true);
        assertTrue(Settings.isHomeSeeded(ctx));
        Settings.setHomeSeeded(ctx, false);
        assertFalse(Settings.isHomeSeeded(ctx));
    }

    @Test
    public void searchPlaceholderDismissed_defaultsFalse() {
        assertFalse(Settings.isSearchPlaceholderDismissed(ctx));
    }

    @Test
    public void searchPlaceholderDismissed_roundTrip() {
        Settings.setSearchPlaceholderDismissed(ctx, true);
        assertTrue(Settings.isSearchPlaceholderDismissed(ctx));
    }
}
