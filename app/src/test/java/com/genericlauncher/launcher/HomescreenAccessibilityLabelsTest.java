package com.genericlauncher.launcher;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Every clickable thing on the home screen needs a name a screen reader can say. The page icons
 * and folder chips carry a visible label, so they come for free; the dock draws no label, and its
 * four icons were "unlabeled button" to TalkBack until they were given a content description.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class HomescreenAccessibilityLabelsTest {

    private static final int W = 1080, H = 2424;

    private ActivityController<Activity> controller;
    private HomescreenView view;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        Activity activity = controller.get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();
        HomeScreenStore store = HomeScreenStore.get(activity);

        Map<String, AppInfo> apps = new HashMap<>();
        apps.put("com.example.mail", new AppInfo("Mail", "com.example.mail", null));
        apps.put("com.example.maps", new AppInfo("Maps", "com.example.maps", null));
        store.replaceAll(new ArrayList<>(Arrays.asList(
                HomeItem.app(HomeItem.DOCK, 0, 0, "com.example.mail"),
                HomeItem.folder(HomeItem.DOCK, 1, 0, "Travel", Arrays.asList("com.example.maps")),
                HomeItem.app(0, 0, 0, "com.example.maps"))));

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setAppMap(apps);
        root.addView(view, W, H);
        view.refresh(apps);
        view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        HomeScreenStore.resetForTesting();
    }

    /** The dock is the one non-{@link CellLayout} child added by {@code buildDock()}. */
    private ViewGroup dock() {
        for (int i = 0; i < view.getChildCount(); i++) {
            View child = view.getChildAt(i);
            if (!(child instanceof CellLayout)) return (ViewGroup) child;
        }
        fail("no dock child found");
        return null;
    }

    /** The one view inside dock slot {@code slot}. */
    private View dockItem(int slot) {
        ViewGroup slotFrame = (ViewGroup) dock().getChildAt(slot);
        assertEquals("slot " + slot + " should hold exactly one item", 1, slotFrame.getChildCount());
        return slotFrame.getChildAt(0);
    }

    /** Every clickable view in the subtree has a name: its own text, a description, or a text child. */
    private static boolean hasAccessibleName(View v) {
        if (v.getContentDescription() != null && v.getContentDescription().length() > 0) return true;
        if (v instanceof android.widget.TextView
                && ((android.widget.TextView) v).getText().length() > 0) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (hasAccessibleName(g.getChildAt(i))) return true;
        }
        return false;
    }

    private static void collectClickable(View v, java.util.List<View> out) {
        if (v.isClickable()) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectClickable(g.getChildAt(i), out);
        }
    }

    @Test
    public void dockAppIconIsNamedAfterTheApp() {
        View icon = dockItem(0);
        assertTrue(icon instanceof ImageView);
        assertEquals("Mail", String.valueOf(icon.getContentDescription()));
    }

    @Test
    public void dockFolderChipIsNamedAfterTheFolder() {
        assertEquals("Travel", String.valueOf(dockItem(1).getContentDescription()));
    }

    @Test
    public void everyClickableViewOnTheHomeScreenHasAName() {
        java.util.List<View> clickable = new ArrayList<>();
        collectClickable(view, clickable);
        assertFalse("test setup: nothing clickable was built", clickable.isEmpty());
        for (View v : clickable) {
            assertTrue("unlabeled clickable view: " + v, hasAccessibleName(v));
        }
    }
}
