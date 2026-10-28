package com.genericlauncher.launcher;

import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Every way out of the drawer takes the search keyboard with it.
 *
 * <p>The keyboard belongs to the window, not to the overlay: removing the overlay, hiding the
 * toolbar and going HOME all leave an open IME exactly where it was. Only Back closes it on its
 * own. The first case found was the drawer→home drag ({@link MainActivity#dismissDrawerForDrag});
 * emulator QA then found the same keyboard standing over the wallpaper after the Home key and
 * after launching an app, with no text field under it and the drawer's swipe landing on the keys.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DismissDrawerKeyboardTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private EditText searchBox;
    private InputMethodManager imm;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        // Instant transitions, so goHome() is observable at the moment of the call.
        android.provider.Settings.Global.putFloat(ctx.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        searchBox = activity.findViewById(R.id.search_box);
        imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    @After
    public void tearDown() {
        controller.destroy();
    }

    /** Opens the drawer and puts the keyboard up over it, as a tap on the search box does. */
    private void openDrawerWithKeyboard() {
        activity.openDrawer();
        searchBox.requestFocus();
        imm.showSoftInput(searchBox, 0);
        assertTrue("precondition: keyboard visible", shadowOf(imm).isSoftInputVisible());
    }

    private void assertKeyboardGone(String route) {
        assertFalse("keyboard should be hidden by " + route, shadowOf(imm).isSoftInputVisible());
        assertFalse("search box should lose focus so it stops driving the IME after " + route,
                searchBox.hasFocus());
    }

    @Test
    public void dismissDrawerForDrag_hidesKeyboardAndClearsSearchFocus() {
        openDrawerWithKeyboard();

        activity.dismissDrawerForDrag();

        assertKeyboardGone("the drag");
    }

    @Test
    public void goHome_hidesKeyboardAndClearsSearchFocus() {
        openDrawerWithKeyboard();

        activity.goHome();

        assertKeyboardGone("goHome");
    }

    /** The Home key arrives as a new intent on the singleTask HOME activity. */
    @Test
    public void theHomeKey_hidesKeyboardAndClearsSearchFocus() {
        openDrawerWithKeyboard();

        controller.newIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));

        assertTrue("test setup: the Home key closes the drawer", activity.isHomeForTests());
        assertKeyboardGone("the Home key");
    }

    @Test
    public void launchingAnApp_hidesKeyboardAndClearsSearchFocus() {
        openDrawerWithKeyboard();

        activity.launchForTests(new AppInfo("Nowhere", "com.example.nowhere", null));

        assertKeyboardGone("an app launch");
    }
}
