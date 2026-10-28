package com.genericlauncher.launcher;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link AppLoader}'s personal/work key derivation.
 *
 * <p>Apps on the user this launcher actually runs as take a bare package key even when that user
 * is a secondary or guest user whose serial is not 0 — only other profiles in the group get a
 * composite one. Getting that backwards puts every app behind a "pkg#10" key while the home-screen
 * seeder writes bare names, and the dock renders empty.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AppLoaderTest {

    // -------------------------------------------------------------------------
    // appKey — bare for our own user, composite for other profiles
    // -------------------------------------------------------------------------

    @Test
    public void appKey_ownProfileOnPrimaryUser_isBare() {
        assertEquals("com.example.app", AppLoader.appKey("com.example.app", 0, 0));
    }

    @Test
    public void appKey_ownProfileOnSecondaryUser_isStillBare() {
        // The regression: on a secondary or guest user every app was keyed "pkg#10" while
        // DefaultHomeSeeder seeds bare names, so the dock resolved to nothing and rendered empty.
        assertEquals("com.example.app", AppLoader.appKey("com.example.app", 10, 10));
    }

    @Test
    public void appKey_workProfile_isComposite() {
        assertEquals("com.example.app#11", AppLoader.appKey("com.example.app", 11, 10));
        assertEquals("com.example.app#10", AppLoader.appKey("com.example.app", 10, 0));
    }

    @Test
    public void appKey_workProfileKeyRoundTripsThroughUserKeys() {
        String key = AppLoader.appKey("com.example.app", 11, 10);
        assertEquals("com.example.app", UserKeys.packageOf(key));
        assertEquals(11, UserKeys.serialOf(key));
        assertTrue(UserKeys.isWork(key));
    }
}
