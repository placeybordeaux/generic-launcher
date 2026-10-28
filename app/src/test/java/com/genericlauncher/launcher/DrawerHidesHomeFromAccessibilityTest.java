package com.genericlauncher.launcher;

import android.content.Context;
import android.view.View;
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
 * The home screen must leave the accessibility tree while the drawer overlay covers it.
 *
 * <p>The overlay is a sibling of the home content, not a dialog, so the framework never treated
 * it as modal: with the drawer open, the dock icons and the "Tap to add Google search"
 * placeholder were all still focusable and clickable underneath it. A screen reader swiping past the
 * last drawer item walked straight into them.
 *
 * <p>Every test here asserts the flag in <em>both</em> directions on one route out of the drawer:
 * a fix that hid the home screen and never gave it back would be worse than the bug, and a test
 * that only asserted the hide could not tell the two apart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DrawerHidesHomeFromAccessibilityTest {

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

    /** True when every child of the content container except the drawer overlay is hidden. */
    private boolean homeHidden() {
        FrameLayout container = contentContainer();
        FrameLayout overlay = activity.drawerOverlayForTests();
        int checked = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == overlay) continue;
            checked++;
            if (child.getImportantForAccessibility()
                    != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return false;
        }
        // A vacuous pass would be the same silent oracle as an absence-based wait: with nothing
        // behind the overlay there is nothing this test can prove.
        assertTrue("no home content to hide — the fixture cannot express the bug", checked > 0);
        return true;
    }

    private boolean homeExposed() {
        FrameLayout container = contentContainer();
        FrameLayout overlay = activity.drawerOverlayForTests();
        int checked = 0;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child == overlay) continue;
            checked++;
            if (child.getImportantForAccessibility()
                    == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return false;
        }
        assertTrue("nothing on the home screen to expose", checked > 0);
        return true;
    }

    @Test
    public void theHomeScreenIsExposedWithTheDrawerClosed() {
        assertTrue("precondition", activity.isHomeForTests());
        assertTrue(homeExposed());
    }

    @Test
    public void openingTheDrawerHidesTheHomeScreen() {
        activity.openDrawer();

        assertNotNull("precondition: an overlay is up", activity.drawerOverlayForTests());
        assertTrue("the home screen is behind the drawer and must not be reachable", homeHidden());
    }

    @Test
    public void theDrawerOverlayItselfStaysExposed() {
        activity.openDrawer();

        assertNotEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                activity.drawerOverlayForTests().getImportantForAccessibility());
    }

    @Test
    public void backGivesTheHomeScreenBack() {
        activity.openDrawer();
        FrameLayout overlay = activity.drawerOverlayForTests();
        assertTrue("precondition", homeHidden());

        activity.onBackPressed();
        activity.finishDrawerClose(overlay);   // the slide-down's end action, which is not unit-testable

        assertNull("precondition: the overlay is gone", activity.drawerOverlayForTests());
        assertTrue(homeExposed());
    }

    @Test
    public void theHomeScreenStaysHiddenForTheLengthOfTheCloseAnimation() {
        activity.openDrawer();

        activity.onBackPressed();

        assertNotNull("precondition: still sliding down", activity.drawerOverlayForTests());
        assertTrue("isHome is already true here, but the overlay is still covering everything",
                homeHidden());
    }

    @Test
    public void homeGivesTheHomeScreenBack() {
        activity.openDrawer();
        FrameLayout overlay = activity.drawerOverlayForTests();
        assertTrue("precondition", homeHidden());

        activity.onNewIntent(new android.content.Intent(android.content.Intent.ACTION_MAIN));
        activity.finishDrawerClose(overlay);

        assertNull("precondition", activity.drawerOverlayForTests());
        assertTrue(homeExposed());
    }

    @Test
    public void theDragDismissalGivesTheHomeScreenBack() {
        activity.openDrawer();
        assertTrue("precondition", homeHidden());

        activity.dismissDrawerForDrag();

        assertNull("precondition", activity.drawerOverlayForTests());
        assertTrue("this route never calls renderMode(), so it has to restore on its own",
                homeExposed());
    }

    @Test
    public void aRebuildWhileTheDrawerIsOpenKeepsTheHomeScreenHidden() {
        activity.openDrawer();

        // A search keystroke, a reload or a mode switch all land here, and each replaces the
        // overlay — and, through ensureHomescreenView, can replace the home view underneath it.
        activity.renderModeForTests();

        assertTrue(homeHidden());
    }

}
