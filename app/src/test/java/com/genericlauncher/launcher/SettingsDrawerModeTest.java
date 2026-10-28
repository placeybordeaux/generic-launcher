package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for the drawer-mode persistence helpers in {@link Settings}.
 * <p>
 * These cover the KEY_DRAWER_MODE round-trip that drives the "last non-HOME mode" UX:
 * the app always starts on the homescreen and swipe-up reopens whichever drawer mode the
 * user was last using.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SettingsDrawerModeTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        // Clear shared prefs between tests so each test starts from a clean slate.
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
    }

    // -------------------------------------------------------------------------
    // getDrawerMode — defaults
    // -------------------------------------------------------------------------

    @Test
    public void getDrawerMode_returnsMinusOne_whenNeverSet() {
        assertEquals(Settings.DRAWER_MODE_UNSET, Settings.getDrawerMode(ctx));
    }

    // -------------------------------------------------------------------------
    // setDrawerMode / getDrawerMode — round-trip
    // -------------------------------------------------------------------------

    @Test
    public void setThenGet_list() {
        int ordinal = 0; // Mode.LIST ordinal
        Settings.setDrawerMode(ctx, ordinal);
        assertEquals(ordinal, Settings.getDrawerMode(ctx));
    }

    @Test
    public void setThenGet_usage() {
        int ordinal = 1; // Mode.USAGE ordinal
        Settings.setDrawerMode(ctx, ordinal);
        assertEquals(ordinal, Settings.getDrawerMode(ctx));
    }

    @Test
    public void lastWriteWins() {
        Settings.setDrawerMode(ctx, 0); // LIST
        Settings.setDrawerMode(ctx, 1); // USAGE — should win
        assertEquals(1, Settings.getDrawerMode(ctx));
    }

    // -------------------------------------------------------------------------
    // Independence from other Settings keys
    // -------------------------------------------------------------------------

    @Test
    public void drawerMode_doesNotAffectEnabledModesMask() {
        int originalMask = Settings.getEnabledModesMask(ctx);
        Settings.setDrawerMode(ctx, 0);
        assertEquals(originalMask, Settings.getEnabledModesMask(ctx));
    }

    @Test
    public void drawerMode_doesNotAffectTheBadgeStyle() {
        BadgeStyle original = Settings.getBadgeStyle(ctx);
        Settings.setDrawerMode(ctx, 1);
        assertEquals(original, Settings.getBadgeStyle(ctx));
    }

    // -------------------------------------------------------------------------
    // Boundary: the HOME ordinal may be stored but the caller must ignore it
    // -------------------------------------------------------------------------

    @Test
    public void setDrawerMode_canStoreHomeOrdinal_butCallerShouldFilter() {
        // Settings.setDrawerMode is a dumb setter — it does not filter HOME out.
        // The caller (MainActivity) must treat ordinal >= HOME.ordinal() as "not set".
        int homeOrdinal = 2; // Mode.HOME ordinal
        Settings.setDrawerMode(ctx, homeOrdinal);
        assertEquals(homeOrdinal, Settings.getDrawerMode(ctx));
        // Caller contract: treat values >= 3 as "no drawer mode saved"
        assertTrue("Caller must filter HOME ordinal", Settings.getDrawerMode(ctx) >= homeOrdinal);
    }

    // -------------------------------------------------------------------------
    // setEnabledModesMask reconciles drawer_mode against the new mask
    //
    // Without this, disabling the mode the drawer currently has open leaves drawer_mode
    // (and, for a caller that mirrors it into a live field the way MainActivity.mode does,
    // that field too) pointing at a mode nothing re-checks after MainActivity's one-time
    // onCreate guard. If it was also the only enabled non-HOME mode, the mode-switch button
    // that would otherwise offer an escape is hidden as well — see SettingsActivity's
    // showEnabledViewsDialog javadoc.
    // -------------------------------------------------------------------------

    @Test
    public void setEnabledModesMask_fallsDrawerModeBack_whenItsModeIsDisabled() {
        // Drawer is showing Usage (ordinal 2); Rainbow (0) and Wheel (1) are also enabled.
        Settings.setDrawerMode(ctx, 2);
        Settings.setEnabledModesMask(ctx, (1 << 0) | (1 << 1) | (1 << 2) | (1 << 3));

        // The user now disables Usage from Settings — Rainbow and Wheel (and Home) remain.
        Settings.setEnabledModesMask(ctx, (1 << 0) | (1 << 1) | (1 << 3));

        assertNotEquals("drawer_mode must not keep pointing at the just-disabled mode",
                2, Settings.getDrawerMode(ctx));
        assertEquals("falls back to the lowest-ordinal enabled non-HOME mode",
                0, Settings.getDrawerMode(ctx));
    }

    @Test
    public void setEnabledModesMask_fallsBackToUnset_whenNoNonHomeModeSurvives() {
        Settings.setDrawerMode(ctx, 2); // Usage
        Settings.setEnabledModesMask(ctx, (1 << 2) | (1 << 3)); // only Usage + Home

        // Disabling Usage leaves no non-HOME mode enabled at all.
        Settings.setEnabledModesMask(ctx, (1 << 3)); // Home only

        assertEquals("falls back to the unset sentinel when nothing else qualifies",
                Settings.DRAWER_MODE_UNSET, Settings.getDrawerMode(ctx));
    }

    @Test
    public void setEnabledModesMask_leavesDrawerMode_whenItsModeStaysEnabled() {
        Settings.setDrawerMode(ctx, 1); // Wheel
        Settings.setEnabledModesMask(ctx, (1 << 0) | (1 << 1) | (1 << 3));

        // Disable Rainbow, but Wheel (the drawer's current mode) stays enabled.
        Settings.setEnabledModesMask(ctx, (1 << 1) | (1 << 3));

        assertEquals(1, Settings.getDrawerMode(ctx));
    }

    @Test
    public void setEnabledModesMask_leavesUnsetDrawerMode_unset() {
        // Never explicitly opened the drawer — nothing to reconcile.
        Settings.setEnabledModesMask(ctx, (1 << 0));
        assertEquals(Settings.DRAWER_MODE_UNSET, Settings.getDrawerMode(ctx));
    }
}
