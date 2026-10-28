package com.genericlauncher.launcher;

import android.content.Context;
import android.provider.Settings.Secure;

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

import static org.junit.Assert.*;

/**
 * The badges toggle sends the user to the system notification-access screen, and they come back
 * to <em>Settings</em>, not to the home screen. {@code HomeSetupCardTest} covers the return to
 * {@code MainActivity}; this covers the return here, which shipped without one: the only consumer
 * of {@code notif_dots_pending} was {@code MainActivity.onResume}, so a grant made from Settings
 * left the switch off until the user backed out to the home screen and came back in.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SettingsActivityGrantReturnTest {

    private static final String LISTENERS = "enabled_notification_listeners";

    private Context ctx;
    private ActivityController<SettingsActivity> controller;
    private SettingsActivity activity;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        Secure.putString(ctx.getContentResolver(), LISTENERS, "");
        ShadowToast.reset();
        controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        controller.close();
    }

    /** Grants notification access the way the system does: by listing our service. */
    private void grantNotificationAccess() {
        Secure.putString(ctx.getContentResolver(), LISTENERS,
                ctx.getPackageName() + "/com.genericlauncher.launcher.NotificationDotsService");
    }

    private SettingsRows.Row badgesRow() {
        for (SettingsRows.Row r : activity.rowsForTest()) if ("notif_dots".equals(r.id)) return r;
        fail("no Badges row");
        return null;
    }

    @Test
    public void grantingFromSettingsFinishesTheEnableWhenSettingsResumes() {
        assertFalse("test setup", Settings.getNotifDots(ctx));
        // What toggleNotifDots records before sending the user away.
        Settings.setNotifDotsPending(ctx, true);

        grantNotificationAccess();
        controller.pause().resume();

        assertTrue("badges still off after the grant the user asked for", Settings.getNotifDots(ctx));
        assertTrue("the Badges switch reads off after a successful grant", badgesRow().checked);
        assertFalse("the pending flag was not cleared, so a later grant would re-enable badges "
                + "the user had since turned off", Settings.isNotifDotsPending(ctx));
        assertEquals("the user was promised badges switch on when they come back",
                "Notification badges enabled", ShadowToast.getTextOfLatestToast());
    }

    /** A grant nobody asked for must not switch badges on behind the user's back. */
    @Test
    public void aGrantWithNoPendingRequestDoesNotEnableBadges() {
        grantNotificationAccess();
        controller.pause().resume();

        assertFalse(Settings.getNotifDots(ctx));
        assertFalse(badgesRow().checked);
        assertNull("nothing to announce", ShadowToast.getLatestToast());
    }

    /** Coming back without granting leaves the switch off and the request outstanding. */
    @Test
    public void returningWithoutGrantingLeavesBadgesOffAndTheRequestPending() {
        Settings.setNotifDotsPending(ctx, true);

        controller.pause().resume();

        assertFalse(Settings.getNotifDots(ctx));
        assertTrue("the request must survive a return without a grant, so granting later "
                + "still finishes it", Settings.isNotifDotsPending(ctx));
        assertNull(ShadowToast.getLatestToast());
    }
}
