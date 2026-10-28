package com.genericlauncher.launcher;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.Personality;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Every seam is present, non-null, and behaves for whichever flavour is being compiled.
 *
 * <p>This is the cheapest guard against the way a personality actually breaks: not a wrong colour,
 * but a half-finished swap that leaves one seam null and takes the home screen down with it —
 * {@code MainActivity} is the HOME app, so an NPE here is "no home screen", not a dialog. Every
 * assertion holds for the taste-free default and must equally hold for any fork.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class PersonalityWiringTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void everySeamIsWired() {
        assertNotNull("Personality.DRAWER", Personality.DRAWER);
        assertNotNull("Personality.BACKGROUND", Personality.BACKGROUND);
        assertNotNull("Personality.DECORATOR", Personality.DECORATOR);
        assertNotNull("Personality.BADGE", Personality.BADGE);
        assertNotNull("Personality.DATA", Personality.DATA);
    }

    @Test
    public void dataSinkToleratesThePartialListsTheLoaderActuallyHandsIt() {
        // The loader calls this twice per load — the home screen's apps first, then everything —
        // and again on every install/uninstall. It runs on the worker thread where an escaping
        // exception is process death, so "must not throw" is the whole contract.
        Personality.DATA.onAppsLoaded(ctx(), java.util.Collections.emptyList());
        Personality.DATA.onAppsLoaded(ctx(), java.util.Arrays.asList(
                new AppInfo("Calendar", "com.cal", null),
                new AppInfo("", "com.unnamed", null),
                new AppInfo("Mail", "com.mail#10", null)));
    }

    @Test
    public void backgroundIsOptionalButTheSeamIsNot() {
        // createView() may legitimately return null ("nothing of my own"); the seam holding it
        // may not be null, which is why the default is Background.NONE and not a null field.
        assertNotNull(Personality.BACKGROUND.id());
        View v = Personality.BACKGROUND.createView(ctx());
        if (v != null) {
            assertFalse("a background that takes clicks steals them from the app grid",
                    v.isClickable());
        }
    }

    @Test
    public void decoratorToleratesBeingCalledOnAnyCell() {
        // Called on every (re)bind for every app, including odd ones. It must not throw: a
        // decorator that dies on an empty label takes out the whole drawer.
        ImageView icon = new ImageView(ctx());
        TextView label = new TextView(ctx());
        Personality.DECORATOR.decorate(icon, label, app("Calendar", "com.cal"));
        Personality.DECORATOR.decorate(icon, label, app("", "com.unnamed"));
        Personality.DECORATOR.decorate(icon, label, app("Mail", "com.mail#10"));
    }

    @Test
    public void badgeAppearanceIsOpaqueForEveryCountItCanBeAsked() {
        // A transparent badge fill is an invisible badge — the notification count would be drawn
        // onto nothing. Counts start at 1 (zero draws no badge at all) and saturate at "99+".
        for (int count : new int[]{1, 2, 9, 99, 100, 1000}) {
            int fill = Personality.BADGE.fillColor("com.example", count);
            assertEquals("badge fill must be fully opaque at count " + count,
                    0xFF, (fill >>> 24) & 0xFF);
        }
    }

    @Test
    public void badgeAppearanceAnswersForAnyKeyShape() {
        // Bare package, work-profile composite key, and the empty key a folder chip passes when
        // it has no members yet. None of these may throw.
        Personality.BADGE.fillColor("com.example", 1);
        Personality.BADGE.fillColor("com.example#10", 1);
        Personality.BADGE.fillColor("", 1);
    }

    private static DrawerApp app(String label, String key) {
        return new AppInfo(label, key, null);
    }
}
