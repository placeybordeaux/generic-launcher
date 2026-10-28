package com.genericlauncher.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Decision logic for the "Tap to add Google search" placeholder: shown only when
 * seeded && !dismissed && top row free && a Google search widget provider exists.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SearchPlaceholderTest {

    private static final String GOOGLE = "com.google.android.googlequicksearchbox";

    @Test
    public void shownOnlyWhenAllConditionsHold() {
        assertTrue(HomescreenView.shouldShowSearchPlaceholder(true, false, true, true));
    }

    @Test
    public void hiddenWhenNotSeeded() {
        assertFalse(HomescreenView.shouldShowSearchPlaceholder(false, false, true, true));
    }

    @Test
    public void hiddenWhenDismissed() {
        assertFalse(HomescreenView.shouldShowSearchPlaceholder(true, true, true, true));
    }

    @Test
    public void hiddenWhenNoRowIsFree() {
        assertFalse(HomescreenView.shouldShowSearchPlaceholder(true, false, false, true));
    }

    @Test
    public void hiddenWhenNoProvider() {
        assertFalse(HomescreenView.shouldShowSearchPlaceholder(true, false, true, false));
    }

    private static AppWidgetProviderInfo provider(String pkg, String cls) {
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(pkg, cls);
        return info;
    }

    @Test
    public void findsGoogleSearchProvider() {
        List<AppWidgetProviderInfo> providers = Arrays.asList(
                provider("com.example.clock", ".ClockWidget"),
                provider("com.google.android.googlequicksearchbox",
                        "com.google.android.googlequicksearchbox.SearchWidgetProvider"));
        AppWidgetProviderInfo found = HomescreenView.findGoogleSearchProvider(providers);
        assertNotNull(found);
        assertEquals("com.google.android.googlequicksearchbox", found.provider.getPackageName());
    }

    /**
     * The Google app ships several widgets. On a Pixel 9a {@code getInstalledProviders()}
     * returns Smartspace ("At a Glance") BEFORE the search bar, so picking the first
     * provider owned by the package added the wrong widget.
     */
    @Test
    public void skipsAtAGlanceInFavourOfTheSearchBar() {
        List<AppWidgetProviderInfo> providers = Arrays.asList(
                provider(GOOGLE, "com.google.android.apps.gsa.staticplugins"
                        + ".smartspace.widget.SmartspaceWidgetProvider"),
                provider(GOOGLE, "com.google.android.apps.gsa.staticplugins"
                        + ".searchwidget.PremiumSearchWidgetProvider"),
                provider(GOOGLE, "com.google.android.googlequicksearchbox.SearchWidgetProvider"),
                provider(GOOGLE, "com.google.android.apps.gsa.staticplugins"
                        + ".searchwidget.GoogleSearchWidgetProvider"),
                provider(GOOGLE, "com.google.android.apps.search.widgets"
                        + ".stocks.StocksWidgetReceiver"));
        AppWidgetProviderInfo found = HomescreenView.findGoogleSearchProvider(providers);
        assertNotNull(found);
        assertEquals("com.google.android.googlequicksearchbox.SearchWidgetProvider",
                found.provider.getClassName());
    }

    /** Preference order holds however the platform happens to order the list. */
    @Test
    public void preferenceOrderIsIndependentOfListOrder() {
        AppWidgetProviderInfo classic =
                provider(GOOGLE, "com.google.android.googlequicksearchbox.SearchWidgetProvider");
        AppWidgetProviderInfo pixel = provider(GOOGLE, "com.google.android.apps.gsa"
                + ".staticplugins.searchwidget.GoogleSearchWidgetProvider");
        assertEquals(classic.provider,
                HomescreenView.findGoogleSearchProvider(Arrays.asList(classic, pixel)).provider);
        assertEquals(classic.provider,
                HomescreenView.findGoogleSearchProvider(Arrays.asList(pixel, classic)).provider);
    }

    /** A newer search-widget class we don't know by name still beats Smartspace. */
    @Test
    public void unknownSearchWidgetClassBeatsSmartspace() {
        List<AppWidgetProviderInfo> providers = Arrays.asList(
                provider(GOOGLE, "com.google.android.apps.gsa.staticplugins"
                        + ".smartspace.widget.SmartspaceWidgetProvider"),
                provider(GOOGLE, "com.google.android.apps.gsa.staticplugins"
                        + ".searchwidget.FutureSearchWidgetProvider"));
        AppWidgetProviderInfo found = HomescreenView.findGoogleSearchProvider(providers);
        assertNotNull(found);
        assertEquals("com.google.android.apps.gsa.staticplugins"
                + ".searchwidget.FutureSearchWidgetProvider", found.provider.getClassName());
    }

    /** No search widget → no placeholder, rather than offering some unrelated Google widget. */
    @Test
    public void returnsNullWhenOnlyNonSearchGoogleWidgetsExist() {
        assertNull(HomescreenView.findGoogleSearchProvider(Arrays.asList(
                provider(GOOGLE, "com.google.android.apps.gsa.staticplugins"
                        + ".smartspace.widget.SmartspaceWidgetProvider"),
                provider(GOOGLE, "com.google.android.apps.search.widgets"
                        + ".stocks.StocksWidgetReceiver"))));
    }

    @Test
    public void returnsNullWhenGoogleAppAbsent() {
        assertNull(HomescreenView.findGoogleSearchProvider(
                Collections.singletonList(provider("com.example.clock", ".ClockWidget"))));
        assertNull(HomescreenView.findGoogleSearchProvider(new ArrayList<>()));
        assertNull(HomescreenView.findGoogleSearchProvider(null));
    }

    /** A malformed provider entry must not take down the scan. */
    @Test
    public void skipsEntriesWithoutAComponent() {
        List<AppWidgetProviderInfo> providers = Arrays.asList(
                new AppWidgetProviderInfo(),
                provider(GOOGLE, "com.google.android.googlequicksearchbox.SearchWidgetProvider"));
        assertNotNull(HomescreenView.findGoogleSearchProvider(providers));
    }

    // ---------------------------------------------------------------------
    // End to end: what the pill actually hands to the bind flow
    // ---------------------------------------------------------------------
    //
    // The selector above can be right while the pill is wrong: the bug was in what
    // maybeAddSearchPlaceholder passed to OnAddWidgetListener, and a callsite that went back to
    // "first provider owned by the Google app" would leave every test above green. These render
    // the real view over a Pixel-9a-shaped provider list and click the real pill.

    private static final String SMARTSPACE = "com.google.android.apps.gsa.staticplugins"
            + ".smartspace.widget.SmartspaceWidgetProvider";
    private static final String SEARCH_BAR = "com.google.android.googlequicksearchbox"
            + ".SearchWidgetProvider";
    private static final String PILL_LABEL = "Tap to add Google search";

    private static final int W = 1080;
    private static final int H = 2424;

    private ActivityController<Activity> controller;
    private Activity activity;
    private FrameLayout root;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        root = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    /** Publishes {@code classes} as installed providers, in the order given. */
    private void installGoogleWidgets(String... classes) {
        ShadowAppWidgetManager shadow =
                Shadows.shadowOf(AppWidgetManager.getInstance(activity));
        for (String cls : classes) {
            shadow.addInstalledProvider(provider(GOOGLE, cls));
        }
    }

    /**
     * Renders a home screen whose page 0 is empty and seeded, so the placeholder's own
     * preconditions all hold and only the provider choice is under test.
     */
    private HomescreenView renderSeededHome() {
        Settings.setHomeSeeded(activity, true);
        Settings.setSearchPlaceholderDismissed(activity, false);

        HomescreenView view = new HomescreenView(activity);
        view.setStore(HomeScreenStore.get(activity));
        root.addView(view, W, H);
        view.refresh();
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
        return view;
    }

    /** The placeholder pill on page 0, or null when it wasn't added. */
    private static View findPill(HomescreenView view) {
        ViewGroup page = view.getPage(0);
        return page == null ? null : findPillIn(page);
    }

    private static View findPillIn(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup && hasLabel((ViewGroup) child)) return child;
        }
        return null;
    }

    private static boolean hasLabel(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView
                    && PILL_LABEL.contentEquals(((TextView) child).getText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The regression itself: with the Pixel 9a's provider order (Smartspace first), tapping the
     * pill must hand the bind flow the search bar, not At a Glance.
     */
    @Test
    public void tappingThePillPicksTheSearchBarNotAtAGlance() {
        installGoogleWidgets(SMARTSPACE, SEARCH_BAR);
        HomescreenView view = renderSeededHome();

        AtomicReference<AppWidgetProviderInfo> picked = new AtomicReference<>();
        view.setOnAddWidgetListener(picked::set);

        View pill = findPill(view);
        assertNotNull("the placeholder pill should be on page 0", pill);
        assertTrue("the pill must actually be clickable", pill.performClick());

        assertNotNull("tapping the pill must start a bind", picked.get());
        assertEquals(SEARCH_BAR, picked.get().provider.getClassName());
    }

    /**
     * With no search widget installed the pill must not appear at all — offering At a Glance
     * under a "Tap to add Google search" label is the same bug wearing a different hat.
     */
    @Test
    public void noPillWhenTheGoogleAppShipsNoSearchWidget() {
        installGoogleWidgets(SMARTSPACE);
        assertNull(findPill(renderSeededHome()));
    }

    // ---------------------------------------------------------------------
    // The offer is one-shot: occupancy moves it, it does not retract it (QA R5)
    // ---------------------------------------------------------------------

    private static CellLayout.LayoutParams pillCell(View pill) {
        assertTrue("the pill is not in a CellLayout",
                pill.getLayoutParams() instanceof CellLayout.LayoutParams);
        return (CellLayout.LayoutParams) pill.getLayoutParams();
    }

    /**
     * The drawer's "Add to Home screen" takes the first free cell, which on a fresh layout is
     * (0,0) — so the very first thing a new user is likely to do used to make the offer vanish,
     * with {@code search_placeholder_dismissed} still false.
     */
    @Test
    public void anAppInTheTopRowMovesThePillDownRatherThanRemovingIt() {
        installGoogleWidgets(SEARCH_BAR);
        HomeScreenStore.get(activity).addItem(HomeItem.app(0, 0, 0, "com.example.first"));

        View pill = findPill(renderSeededHome());

        assertNotNull("adding an app must not retract a one-shot offer", pill);
        assertEquals("the pill should have moved to the first free row", 1, pillCell(pill).cellY);
        assertEquals("and still span the row", 0, pillCell(pill).cellX);
        assertFalse("nothing here dismisses it",
                Settings.isSearchPlaceholderDismissed(activity));
    }

    /** A part-full row is not a row: the pill takes the first one that is free end to end. */
    @Test
    public void thePillSkipsAPartlyOccupiedRow() {
        installGoogleWidgets(SEARCH_BAR);
        HomeScreenStore store = HomeScreenStore.get(activity);
        store.addItem(HomeItem.app(0, 0, 0, "com.example.first"));
        store.addItem(HomeItem.app(0, 3, 1, "com.example.second"));

        View pill = findPill(renderSeededHome());

        assertNotNull(pill);
        assertEquals(2, pillCell(pill).cellY);
    }

    /** With every row occupied there is nowhere to draw it — but it is still not dismissed. */
    @Test
    public void noPillWhenNoRowIsFreeAndItIsStillNotDismissed() {
        installGoogleWidgets(SEARCH_BAR);
        HomeScreenStore store = HomeScreenStore.get(activity);
        for (int y = 0; y < store.rows(); y++) {
            store.addItem(HomeItem.app(0, 0, y, "com.example.row" + y));
        }

        assertNull(findPill(renderSeededHome()));
        assertFalse(Settings.isSearchPlaceholderDismissed(activity));
    }
}
