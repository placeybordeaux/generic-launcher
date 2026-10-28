package com.genericlauncher.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * What the drawer toolbar's buttons call themselves to a screen reader. The palette button said
 * "Switch layout mode" while every other surface — Settings rows, the strip, the chooser — says
 * "view"; screen-reader-only text is the one place a renamed concept survives unnoticed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ToolbarLabelsTest {
    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        HomeScreenStore.resetForTesting();
        new java.io.File(ctx.getFilesDir(), "homescreen.json").delete();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
    }

    @After
    public void tearDown() {
        controller.close();
    }

    @Test
    public void thePaletteButtonIsCalledAViewSwitchLikeEverythingElse() {
        View button = controller.get().findViewById(R.id.mode_switch);
        assertEquals("Switch view", String.valueOf(button.getContentDescription()));
    }

    @Test
    public void noToolbarButtonStillSaysLayoutMode() {
        StringBuilder found = new StringBuilder();
        collect(controller.get().findViewById(android.R.id.content), found);
        assertFalse(found.toString(), found.toString().toLowerCase().contains("layout mode"));
    }

    private static void collect(View v, StringBuilder out) {
        if (v.getContentDescription() != null) out.append(v.getContentDescription()).append(" | ");
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }
}
