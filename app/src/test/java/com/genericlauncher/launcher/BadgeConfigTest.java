package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Unit tests for {@link BadgeConfig} and the {@code badge_size} pref. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class BadgeConfigTest {

    private Context ctx() { return ApplicationProvider.getApplicationContext(); }

    @Test
    public void badgeSizeDefaultsToMedium() {
        assertEquals(BadgeSize.MEDIUM, Settings.getBadgeSize(ctx()));
    }

    @Test
    public void badgeSizeRoundTrips() {
        Settings.setBadgeSize(ctx(), BadgeSize.HUGE);
        assertEquals(BadgeSize.HUGE, Settings.getBadgeSize(ctx()));
    }

    /** A corrupt or future ordinal must clamp, never throw. */
    @Test
    public void badgeSizeClampsOutOfRangeOrdinal() {
        ctx().getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
                .edit().putInt("badge_size", 99).apply();
        assertEquals(BadgeSize.HUGE, Settings.getBadgeSize(ctx()));

        ctx().getSharedPreferences("color_picker_prefs", Context.MODE_PRIVATE)
                .edit().putInt("badge_size", -3).apply();
        assertEquals(BadgeSize.SMALL, Settings.getBadgeSize(ctx()));
    }

    @Test
    public void fromReadsAllFourPrefs() {
        Settings.setBadgeStyle(ctx(), BadgeStyle.FLAT);
        Settings.setBadgeSize(ctx(), BadgeSize.LARGE);
        Settings.setBadgeShimmer(ctx(), true);
        Settings.setBadgeGlow(ctx(), false);

        BadgeConfig cfg = BadgeConfig.from(ctx());
        assertEquals(BadgeStyle.FLAT, cfg.style);
        assertEquals(BadgeSize.LARGE, cfg.size);
        assertTrue(cfg.shimmer);
        assertFalse(cfg.glow);
    }

    @Test
    public void isAnimatedTruthTable() {
        assertTrue(new BadgeConfig(BadgeStyle.RAINBOW, BadgeSize.MEDIUM, false, false).isAnimated());
        assertTrue(new BadgeConfig(BadgeStyle.FLAT, BadgeSize.MEDIUM, true, false).isAnimated());
        assertTrue(new BadgeConfig(BadgeStyle.FLAT, BadgeSize.MEDIUM, false, true).isAnimated());
        assertFalse(new BadgeConfig(BadgeStyle.FLAT, BadgeSize.MEDIUM, false, false).isAnimated());
    }

    /** Badges switched off short-circuits everything, whatever the style says. */
    @Test
    public void animatorIsNotAnimatedWhenBadgesAreOff() {
        Settings.setBadgeStyle(ctx(), BadgeStyle.RAINBOW);
        Settings.setNotifDots(ctx(), false);
        assertFalse(BadgeAnimator.isAnimated(ctx()));

        Settings.setNotifDots(ctx(), true);
        assertTrue(BadgeAnimator.isAnimated(ctx()));
    }
}
