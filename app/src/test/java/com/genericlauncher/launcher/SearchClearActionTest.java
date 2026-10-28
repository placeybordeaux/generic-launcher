package com.genericlauncher.launcher;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
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
 * The search field's ✕ is a compound drawable, so it is not a view and had no accessibility node:
 * a screen-reader or switch-access user could see the query but had no way to clear it in one
 * move (QA findings R3 / B5). It stays a drawable — an extra ImageView would shift the toolbar's
 * buttons every time the query emptied — and carries a custom action on the field instead.
 *
 * <p>The action is offered only while there is text, so the node matches the ✕ a sighted user
 * sees; a node that always advertised "Clear search" would be its own small lie.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SearchClearActionTest {

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
        activity.applyLoadedApps(new ArrayList<>());
        activity.openDrawer();
    }

    @After
    public void tearDown() {
        if (controller != null) controller.close();
    }

    private EditText searchBox() { return activity.findViewById(R.id.search_box); }

    /** The custom action on the field's node right now, or null. */
    private AccessibilityNodeInfoCompat.AccessibilityActionCompat clearAction() {
        AccessibilityNodeInfo raw = searchBox().createAccessibilityNodeInfo();
        assertNotNull("the field has no node at all", raw);
        AccessibilityNodeInfoCompat info = AccessibilityNodeInfoCompat.wrap(raw);
        for (AccessibilityNodeInfoCompat.AccessibilityActionCompat a : info.getActionList()) {
            if (a.getId() == R.id.action_clear_search) return a;
        }
        return null;
    }

    @Test
    public void anEmptyFieldOffersNothingToClear() {
        assertNull("there is no ✕ on screen either", clearAction());
    }

    @Test
    public void aTypedQueryPutsAClearActionOnTheField() {
        searchBox().setText("map");

        AccessibilityNodeInfoCompat.AccessibilityActionCompat action = clearAction();
        assertNotNull("the ✕ is drawn but unreachable without this", action);
        assertEquals("Clear search", action.getLabel());
    }

    @Test
    public void performingItClearsTheQuery() {
        searchBox().setText("map");
        assertNotNull("precondition", clearAction());

        assertTrue("the action must report that it handled the request",
                searchBox().performAccessibilityAction(R.id.action_clear_search, null));

        assertEquals("", searchBox().getText().toString());
        assertNull("and the action goes with the ✕", clearAction());
    }

    @Test
    public void performingItLeavesTheFieldFocusedSoTheKeyboardStays() {
        searchBox().requestFocus();
        searchBox().setText("map");
        assertTrue("precondition: a search has the field focused", searchBox().isFocused());

        searchBox().performAccessibilityAction(R.id.action_clear_search, null);

        assertTrue("clearing must not dismiss the keyboard — the tap on the ✕ does not",
                searchBox().isFocused());
    }

    @Test
    public void everyOtherActionIsStillTheFieldsOwn() {
        searchBox().setText("map");

        // The delegate must forward what it does not recognise: a delegate that answered every
        // action itself would silently take the EditText's own text-editing actions away.
        searchBox().performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);

        assertEquals("only the clear action clears", "map", searchBox().getText().toString());
    }
}
