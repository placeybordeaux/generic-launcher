package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.genericlauncher.launcher.personality.Personality;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * The default build is deliberately opinion-free.
 *
 * <p>Lives in {@code src/testPlain/} rather than {@code src/test/}: these are assertions about
 * <em>this flavour's taste</em>, and a colour build is supposed to fail every one of them. The
 * flavour-agnostic checks — that the seams are wired and behave — are in
 * {@link PersonalityWiringTest}, which every flavour must pass.
 *
 * <p>Guarding the absence of taste is the unusual part and the reason this exists. "Improving" the
 * base's spacing, palette or animation is the standing temptation, and the base's whole value is
 * that it does none of that: taste is the fork's job. Making that a red test rather than a comment
 * means the decision has to be taken deliberately.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class PlainPersonalityTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    @Test
    public void drawerIsPlainAlphabetical() {
        assertEquals("alpha", Personality.DRAWER.id());
    }

    @Test
    public void backgroundDrawsNothingOfItsOwn() {
        assertEquals("none", Personality.BACKGROUND.id());
        assertNull(Personality.BACKGROUND.createView(ctx()));
    }

    @Test
    public void decoratorIsTheNoOp() {
        assertSame(com.genericlauncher.launcher.personality.DrawerItemDecorator.NONE,
                Personality.DECORATOR);
    }

    @Test
    public void badgeIsNotificationRedForEveryApp() {
        assertEquals(BadgePalette.RED_FILL, Personality.BADGE.fillColor("com.anything", 3));
        assertEquals(BadgePalette.RED_FILL, Personality.BADGE.fillColor("com.other#10", 99));
    }
}
