package com.genericlauncher.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Set;

/**
 * The two-phase app load (see {@link AppLoader#loadAsync}) exists so the home screen renders
 * without waiting for every installed app's icon to be decoded — the difference between a
 * usable launcher in ~0.2s and ~1.5s on a cold start.
 *
 * <p>These are the invariants that make the priority phase both correct and worth having. The
 * timing itself is verified on-device by the macrobenchmark suite; what is checked here is the
 * cheap, device-free half: that the priority set stays small and stays complete.
 */
@RunWith(RobolectricTestRunner.class)
public class PriorityLoadTest {

    private Context ctx;
    private HomeScreenStore store;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        HomeScreenStore.resetForTesting();
        new java.io.File(ctx.getFilesDir(), "homescreen.json").delete();
        store = HomeScreenStore.get(ctx);
    }

    @Test
    public void referencedKeysCoverPlainAppItems() {
        store.addItem(HomeItem.app(0, 0, 0, "com.example.alpha"));
        store.addItem(HomeItem.app(HomeItem.DOCK, 1, 0, "com.example.beta"));

        Set<String> keys = store.referencedAppKeys();

        assertTrue("home-screen app must be loaded in the priority phase",
                keys.contains("com.example.alpha"));
        assertTrue("dock app must be loaded in the priority phase",
                keys.contains("com.example.beta"));
    }

    /**
     * Folder members are drawn as a mini-grid on the home screen, so their icons are needed just
     * as much as a top-level item's. Missing them would leave folders rendering empty until the
     * full load landed — a regression that only shows as a visual glitch, never as a failure.
     */
    @Test
    public void referencedKeysIncludeFolderMembers() {
        store.addItem(HomeItem.folder(0, 1, 1, "Tools",
                java.util.Arrays.asList("com.example.inner1", "com.example.inner2")));

        Set<String> keys = store.referencedAppKeys();

        assertTrue(keys.contains("com.example.inner1"));
        assertTrue(keys.contains("com.example.inner2"));
    }

    /**
     * The whole point: the priority set must scale with the home screen, not with the device.
     * If a change ever makes this return every installed app, the two-phase load silently
     * degrades back into the single-phase one and cold start regresses to where it started.
     */
    @Test
    public void referencedKeysAreLimitedToWhatTheHomeScreenUses() {
        store.addItem(HomeItem.app(0, 0, 0, "com.example.onscreen"));

        Set<String> keys = store.referencedAppKeys();

        assertEquals("only the home screen's own apps belong in the priority phase",
                1, keys.size());
        assertFalse("an app that is merely installed must not be in the priority set",
                keys.contains("com.example.not.on.home.screen"));
    }

    /**
     * Widgets are hosted views with no {@link AppInfo} behind them. Including their provider
     * package would add icon-decoding work to the critical path for something that never
     * displays an icon.
     */
    @Test
    public void widgetsContributeNoKeys() {
        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, 42, "com.example.widget/.Provider"));

        assertTrue("widgets need no app icon", store.referencedAppKeys().isEmpty());
    }
}
