package com.genericlauncher.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Search answers when it has nothing to show, launches a lone result on Enter, and clears on ✕.
 *
 * <p>A query with no matches used to leave an empty black area — indistinguishable from the
 * drawer having broken. Enter did nothing even with one visible result, and the only way to clear
 * the box was deleting characters one by one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SearchNoMatchTest {

    private static final String FIRST = "com.example.first";
    private static final String SECOND = "com.example.second";

    private Context ctx;
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private EditText searchBox;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
           .edit().clear().commit();
        Settings.setHomeSeeded(ctx, true);
        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
        android.provider.Settings.Global.putFloat(ctx.getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f);

        // A real launch intent for FIRST, so a launch is observable as a started activity.
        ComponentName cn = new ComponentName(FIRST, FIRST + ".Main");
        IntentFilter launcher = new IntentFilter(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);
        shadowOf(ctx.getPackageManager()).addActivityIfNotPresent(cn);
        shadowOf(ctx.getPackageManager()).addIntentFilterForActivity(cn, launcher);

        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity   = controller.get();
        if (activity.priorityCallbackForTests() != null) activity.priorityCallbackForTests().detach();
        if (activity.loadCallbackForTests() != null) activity.loadCallbackForTests().detach();
        activity.applyLoadedApps(apps());
        searchBox = activity.findViewById(R.id.search_box);
        activity.openDrawer();
        ShadowLooper.idleMainLooper();
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    private static AppInfo app(String pkg, String label) {
        return new AppInfo(label, pkg, new ColorDrawable(0xFF3366CC));
    }

    private static List<AppInfo> apps() {
        return new ArrayList<>(Arrays.asList(app(FIRST, "Firefly"), app(SECOND, "Second")));
    }

    private void type(String q) {
        searchBox.setText(q);
        ShadowLooper.idleMainLooper();
    }

    /** The "No apps match" label in the drawer overlay, or null. */
    private TextView noMatchLabel() {
        FrameLayout overlay = activity.drawerOverlayForTests();
        assertNotNull("drawer overlay", overlay);
        View v = overlay.findViewWithTag("no_match");
        return v instanceof TextView ? (TextView) v : null;
    }

    // -------------------------------------------------------------------------
    // No matches
    // -------------------------------------------------------------------------

    @Test
    public void aQueryWithNoMatchesSaysSo() {
        type("zzz");

        TextView label = noMatchLabel();
        assertNotNull("a no-match query must say so rather than show an empty area", label);
        assertEquals("No apps match", label.getText().toString());
    }

    @Test
    public void theMessageGoesAsSoonAsSomethingMatches() {
        type("zzz");
        assertNotNull("precondition", noMatchLabel());

        type("fire");

        assertNull("a query with results must not carry the no-match message", noMatchLabel());
    }

    // -------------------------------------------------------------------------
    // Enter
    // -------------------------------------------------------------------------

    @Test
    public void enterWithExactlyOneResultLaunchesIt() {
        type("fire");

        searchBox.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        ShadowLooper.idleMainLooper();

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("the lone result was not launched", started);
        assertEquals(FIRST, started.getComponent().getPackageName());
    }

    @Test
    public void enterWithSeveralResultsLaunchesNothing() {
        type("e");   // Firefly, Second
        assertEquals("test setup: two matches", 2, matchCount());

        searchBox.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        ShadowLooper.idleMainLooper();

        assertNull("Enter must not guess among several results",
                shadowOf(activity).getNextStartedActivity());
    }

    private int matchCount() {
        int n = 0;
        for (AppInfo a : apps()) if (a.getLabel().toLowerCase().contains(searchBox.getText().toString())) n++;
        return n;
    }

    // -------------------------------------------------------------------------
    // Clear
    // -------------------------------------------------------------------------

    @Test
    public void aClearControlAppearsWithTextAndClearsItOnTap() {
        assertNull("no text, no clear control", searchBox.getCompoundDrawablesRelative()[2]);

        type("fire");
        assertNotNull("text in the box must be clearable in one tap",
                searchBox.getCompoundDrawablesRelative()[2]);

        // Lay the box out so the tap can be placed on its end.
        searchBox.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY));
        searchBox.layout(0, 0, 800, 120);
        long t = SystemClock.uptimeMillis();
        float x = 800 - searchBox.getPaddingEnd() - 2;
        searchBox.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, 60, 0));
        searchBox.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x, 60, 0));
        ShadowLooper.idleMainLooper();

        assertEquals("", searchBox.getText().toString());
        assertNull("the control goes with the text", searchBox.getCompoundDrawablesRelative()[2]);
    }
}
