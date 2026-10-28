package com.genericlauncher.launcher;

import android.app.Activity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Widgets saved before the placement fix carry a span several cells too large — the density
 * double-scaling wrote the Google search bar to disk as 4x2 when its minimum only asks for 4x1.
 * Fixing placement cannot fix layouts already on disk, so
 * {@link HomeScreenStore#shrinkOversizedWidgets} repairs them once.
 *
 * <p>The properties that matter are that it only ever shrinks (which is what makes it
 * collision-free) and that it leaves anything it cannot resolve alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class OversizedWidgetSpanRepairTest {

    private static final String SEARCH = "com.google.android.googlequicksearchbox/"
            + "com.google.android.apps.gsa.staticplugins.searchwidget.GoogleSearchWidgetProvider";
    private static final String OTHER  = "com.example.other/.Provider";

    private ActivityController<Activity> controller;
    private Activity activity;
    private HomeScreenStore store;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        HomeScreenStore.resetForTesting();
        for (File f : activity.getFilesDir().listFiles()) {
            if (f.getName().startsWith("homescreen.json")) f.delete();
        }
        store = HomeScreenStore.get(activity);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    /** Resolver returning the correct span for SEARCH and nothing for anything else. */
    private static HomeScreenStore.WidgetSpanResolver resolver() {
        Map<String, int[]> m = new HashMap<>();
        m.put(SEARCH, new int[]{4, 1});
        return m::get;
    }

    private HomeItem widget(String provider) {
        for (HomeItem i : store.getItems()) {
            if (i.getType() == HomeItem.Type.WIDGET && provider.equals(i.getProvider())) return i;
        }
        fail("widget vanished: " + provider);
        return null;
    }

    /** The real case measured on the device: 4x2 on disk, 4x1 asked for. */
    @Test
    public void anOversizedWidgetIsShrunkToItsProvidersSpan() {
        store.addItem(HomeItem.widget(0, 0, 0, 4, 2, 24, SEARCH));

        assertTrue(store.shrinkOversizedWidgets(resolver()));

        assertEquals(4, widget(SEARCH).getSpanX());
        assertEquals("2 rows must come down to the 1 the provider asks for",
                1, widget(SEARCH).getSpanY());
    }

    /**
     * The property that makes this collision-free: a widget the user deliberately made smaller
     * than its provider minimum must not be grown back into a neighbour.
     */
    @Test
    public void anUndersizedWidgetIsNeverGrown() {
        store.addItem(HomeItem.widget(0, 0, 0, 2, 1, 24, SEARCH));

        assertFalse("nothing to shrink", store.shrinkOversizedWidgets(resolver()));

        assertEquals(2, widget(SEARCH).getSpanX());
        assertEquals(1, widget(SEARCH).getSpanY());
    }

    /** An uninstalled or unrecognised provider must be left exactly as it is, not guessed at. */
    @Test
    public void anUnresolvableProviderIsLeftAlone() {
        store.addItem(HomeItem.widget(0, 0, 0, 4, 2, 25, OTHER));

        assertFalse(store.shrinkOversizedWidgets(resolver()));

        assertEquals(4, widget(OTHER).getSpanX());
        assertEquals(2, widget(OTHER).getSpanY());
    }

    /** Running twice must be a no-op the second time — the repair is not self-feeding. */
    @Test
    public void asecondPassChangesNothing() {
        store.addItem(HomeItem.widget(0, 0, 0, 4, 2, 24, SEARCH));

        assertTrue(store.shrinkOversizedWidgets(resolver()));
        assertFalse("already correct, so nothing to do", store.shrinkOversizedWidgets(resolver()));
        assertEquals(1, widget(SEARCH).getSpanY());
    }

    /** Non-widget items have no provider and must be untouched. */
    @Test
    public void appsAndFoldersAreUntouched() {
        store.addItem(HomeItem.app(0, 1, 1, "com.example.app"));
        store.addItem(HomeItem.widget(0, 0, 0, 4, 2, 24, SEARCH));

        store.shrinkOversizedWidgets(resolver());

        int apps = 0;
        for (HomeItem i : store.getItems()) if (i.getType() == HomeItem.Type.APP) apps++;
        assertEquals("the app must survive the repair", 1, apps);
    }

    @Test
    public void aNullResolverIsANoOp() {
        store.addItem(HomeItem.widget(0, 0, 0, 4, 2, 24, SEARCH));
        assertFalse(store.shrinkOversizedWidgets(null));
        assertEquals(2, widget(SEARCH).getSpanY());
    }

    /** The run-once flag defaults to false and survives being set. */
    @Test
    public void theRunOnceFlagPersists() {
        assertFalse(Settings.getWidgetSpansCorrected(activity));
        Settings.setWidgetSpansCorrected(activity, true);
        assertTrue("re-running would undo the user's own resizes",
                Settings.getWidgetSpansCorrected(activity));
    }
}
