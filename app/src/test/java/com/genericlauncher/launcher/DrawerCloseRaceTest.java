package com.genericlauncher.launcher;

import android.content.Context;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * The drawer's 180 ms close animation used to be able to strand an overlay on screen forever.
 *
 * <p>The end action set {@code isHome = true}, removed the overlay it had captured and nulled the
 * {@code drawerOverlay} field. Anything that ran {@code renderMode()} inside that window — a
 * {@code loadApps()} callback, most easily — still saw {@code isHome == false}, took the drawer
 * branch and installed a <em>second</em> overlay. The end action then no-opped on the detached
 * first one and cleared the field while the second was still attached and visible. From there
 * back-press, {@code onNewIntent} and {@code renderMode()} all short-circuit on HOME, so nothing
 * could ever remove it and it swallowed every touch until the process was killed.
 *
 * <p>Two independent guards: {@code goHome()} flips to HOME synchronously so no replacement
 * overlay is built in the first place, and the end action only acts on the overlay it still owns.
 * The animation timing itself is not unit-testable — these drive the end action directly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DrawerCloseRaceTest {

    private Context ctx;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        HomeScreenStore.resetForTesting();
        new java.io.File(ctx.getFilesDir(), "homescreen.json").delete();

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
        activity.applyLoadedApps(new ArrayList<>()); // settle the initial load
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    private FrameLayout contentContainer() {
        return activity.findViewById(R.id.content_container);
    }

    @Test
    public void openDrawerInstallsAnOverlay() {
        activity.openDrawer();

        assertFalse(activity.isHomeForTests());
        assertNotNull(activity.drawerOverlayForTests());
        assertSame(contentContainer(), activity.drawerOverlayForTests().getParent());
    }

    @Test
    public void goHomeFlipsToHomeSynchronously() {
        activity.openDrawer();
        assertFalse("precondition", activity.isHomeForTests());

        activity.goHome();

        assertTrue("renderMode() during the close animation must take the HOME branch, or it "
                + "builds a second overlay the end action then orphans",
                activity.isHomeForTests());
    }

    @Test
    public void finishDrawerCloseTearsDownTheOverlayItStartedWith() {
        activity.openDrawer();
        FrameLayout overlay = activity.drawerOverlayForTests();

        activity.goHome();
        activity.finishDrawerClose(overlay);

        assertNull(activity.drawerOverlayForTests());
        assertNull("and it is detached", overlay.getParent());
        assertTrue(activity.isHomeForTests());
    }

    @Test
    public void aReplacementOverlaySurvivesTheOldCloseAnimation() {
        activity.openDrawer();
        FrameLayout first = activity.drawerOverlayForTests();

        activity.goHome();     // close animation starts on `first`
        activity.openDrawer(); // ...and the user reopens before it finishes
        FrameLayout second = activity.drawerOverlayForTests();
        assertNotSame("precondition: a genuinely new overlay", first, second);

        activity.finishDrawerClose(first); // the old animation's end action fires late

        assertSame("the live overlay must not be disowned", second,
                activity.drawerOverlayForTests());
        assertSame("and must stay attached", contentContainer(), second.getParent());
        assertFalse("the drawer is still open", activity.isHomeForTests());
    }

    @Test
    public void finishDrawerCloseIsANoOpAfterTheOverlayIsAlreadyGone() {
        activity.openDrawer();
        FrameLayout overlay = activity.drawerOverlayForTests();

        activity.goHome();
        activity.dismissDrawerForDrag(); // a drag starts mid-close and clears the overlay itself
        assertNull("precondition", activity.drawerOverlayForTests());

        activity.finishDrawerClose(overlay);

        assertNull(activity.drawerOverlayForTests());
        assertTrue(activity.isHomeForTests());
    }

    @Test
    public void finishDrawerCloseTwiceIsHarmless() {
        activity.openDrawer();
        FrameLayout overlay = activity.drawerOverlayForTests();
        activity.goHome();

        activity.finishDrawerClose(overlay);
        activity.finishDrawerClose(overlay);

        assertNull(activity.drawerOverlayForTests());
    }

    // -------------------------------------------------------------------------
    // Flipping isHome early must not make back-press fall through to super
    // -------------------------------------------------------------------------

    @Test
    public void aSecondBackPressInsideTheCloseWindowFinishesTheCloseNotTheActivity() {
        activity.openDrawer();
        activity.goHome();
        assertNotNull("precondition: still sliding down", activity.drawerOverlayForTests());

        activity.onBackPressed();

        assertFalse("the launcher's only activity must never finish on back",
                activity.isFinishing());
        assertNull("the second press completes the close", activity.drawerOverlayForTests());
    }

    /**
     * This used to assert {@code isFinishing()}: "back reaches the default handler". The
     * default handler finishes the launcher, and the system then relaunches it — a cold-ish
     * start on page 0 that looked like "Back returns to the first page". It is now handled
     * deliberately (see {@code MainActivity.handleBack}), and finishing is the one thing the
     * launcher's only activity must never do on Back.
     */
    @Test
    public void backOnASettledHomeScreenNeverFinishesTheActivity() {
        assertTrue("precondition", activity.isHomeForTests());
        assertNull("precondition", activity.drawerOverlayForTests());

        activity.onBackPressed();

        assertFalse("no overlay, no drawer — and still no finish",
                activity.isFinishing());
        assertTrue(activity.isHomeForTests());
    }

    @Test
    public void homePressInsideTheCloseWindowStillCompletesTheClose() {
        activity.openDrawer();
        activity.goHome();

        activity.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN));

        assertNull(activity.drawerOverlayForTests());
        assertTrue(activity.isHomeForTests());
    }
}
