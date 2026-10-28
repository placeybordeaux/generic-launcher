package com.genericlauncher.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

import java.util.Arrays;
import java.util.List;

import static com.genericlauncher.launcher.HomescreenView.DropTarget.Zone;
import static org.junit.Assert.*;

/**
 * Which action pills appear on the drag action bar, and the provider check behind the Resize pill.
 *
 * <p>The pills are the fix for "can't uninstall from the home screen" and "can't resize the
 * search widget" — both actions existed but were only reachable by a long-press released without
 * moving. Getting the visibility rule wrong is invisible until someone drags a real icon, so the
 * rule is tested here rather than left to on-device discovery.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DragActionsTest {

    private static final int WIDGET_ID = 7;

    private Context ctx;
    private HomescreenView view;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        view = new HomescreenView(ctx);
    }

    private static AppInfo appInfo(String pkg, boolean work) {
        AppInfo info = new AppInfo("Label", pkg, null);
        info.setWork(work);
        return info;
    }

    /** The pills for an item that is on the home screen — every drag except one from the drawer. */
    private static List<Zone> zones(HomeItem item, AppInfo info) {
        return Arrays.asList(HomescreenView.availableActions(item, info, true));
    }

    /** The pills for an app dragged out of the drawer, which is not on the home screen yet. */
    private static List<Zone> drawerZones(HomeItem item, AppInfo info) {
        return Arrays.asList(HomescreenView.availableActions(item, info, false));
    }

    /** Binds a widget id to a provider with the given resizeMode, as the system would. */
    private void bindWidget(int resizeMode) {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = new ComponentName("com.google.android.googlequicksearchbox",
                                                ".SearchWidgetProvider");
        provider.resizeMode = resizeMode;
        ShadowAppWidgetManager shadow =
                Shadows.shadowOf(AppWidgetManager.getInstance(ctx));
        shadow.addBoundWidget(WIDGET_ID, provider);
    }

    // ---------------------------------------------------------------------
    // Which pills show
    // ---------------------------------------------------------------------

    @Test
    public void removeIsAlwaysOffered() {
        assertEquals(Arrays.asList(Zone.REMOVE), zones(null, null));
    }

    @Test
    public void personalApp_offersRemoveThenUninstall() {
        HomeItem app = HomeItem.app(0, 1, 1, "com.example.app");
        assertEquals(Arrays.asList(Zone.REMOVE, Zone.UNINSTALL),
                zones(app, appInfo("com.example.app", false)));
    }

    @Test
    public void workApp_offersRemoveOnly() {
        // ACTION_DELETE + EXTRA_USER is unreliable across OEMs; offering it would be a dead button.
        HomeItem app = HomeItem.app(0, 1, 1, "com.example.app#11");
        assertEquals(Arrays.asList(Zone.REMOVE),
                zones(app, appInfo("com.example.app#11", true)));
    }

    @Test
    public void appMissingFromTheAppMap_offersRemoveOnly() {
        // Uninstalled between the layout being saved and the drag starting: no Uninstall pill.
        HomeItem app = HomeItem.app(0, 1, 1, "com.example.gone");
        assertEquals(Arrays.asList(Zone.REMOVE), zones(app, null));
    }

    /**
     * The Resize pill is gone: a widget's long-press-without-movement already opens the frame
     * (see {@code endDrag}), so the pill was a second way to reach the same place, sitting over
     * grid row 0 and widening the strip a drop cannot land on.
     */
    @Test
    public void widget_offersRemoveOnly() {
        HomeItem widget = HomeItem.widget(0, 0, 0, 4, 1, WIDGET_ID, "com.example/.W");
        assertEquals(Arrays.asList(Zone.REMOVE), zones(widget, null));
    }

    @Test
    public void widgetNeverOffersUninstall() {
        // Uninstalling the host app from a widget drag would be a surprising, destructive action.
        HomeItem widget = HomeItem.widget(0, 0, 0, 4, 1, WIDGET_ID, "com.example/.W");
        assertFalse(zones(widget, appInfo("com.example", false)).contains(Zone.UNINSTALL));
    }

    @Test
    public void folder_offersRemoveOnly() {
        HomeItem folder = HomeItem.folder(0, 0, 0, "Folder", Arrays.asList("a", "b"));
        assertEquals(Arrays.asList(Zone.REMOVE), zones(folder, appInfo("a", false)));
    }

    @Test
    public void removeIsAlwaysFirstSoItsPositionNeverMoves() {
        // Muscle memory: the pill you already know must not shift when a second one appears.
        HomeItem app    = HomeItem.app(0, 1, 1, "com.example.app");
        HomeItem widget = HomeItem.widget(0, 0, 0, 4, 1, WIDGET_ID, "com.example/.W");
        assertEquals(Zone.REMOVE, zones(app, appInfo("com.example.app", false)).get(0));
        assertEquals(Zone.REMOVE, zones(widget, null).get(0));
        assertEquals(Zone.REMOVE, zones(null, null).get(0));
    }

    // ---------------------------------------------------------------------
    // An app dragged out of the drawer is not on the home screen
    // ---------------------------------------------------------------------

    /**
     * Remove was offered for a drawer drag, lit up red under the finger, and the drop was then
     * refused ({@code commitIncomingDrop} has nothing to remove) — the drawer was gone and nothing
     * had changed, with no message. Uninstall is the only pill that means something there.
     */
    @Test
    public void drawerApp_offersUninstallOnly() {
        HomeItem app = HomeItem.app(0, 0, 0, "com.example.app");
        assertEquals(Arrays.asList(Zone.UNINSTALL),
                drawerZones(app, appInfo("com.example.app", false)));
    }

    @Test
    public void drawerWorkApp_offersNoPillsAtAll() {
        HomeItem app = HomeItem.app(0, 0, 0, "com.example.work");
        assertTrue(drawerZones(app, appInfo("com.example.work", true)).isEmpty());
    }

    @Test
    public void drawerAppMissingFromTheAppMap_offersNoPillsAtAll() {
        HomeItem app = HomeItem.app(0, 0, 0, "com.example.app");
        assertTrue(drawerZones(app, null).isEmpty());
    }

    @Test
    public void atMostTwoPillsAreEverShown() {
        // The bar sits over grid row 0; a third pill would widen the undroppable strip.
        for (HomeItem item : new HomeItem[]{
                HomeItem.app(0, 1, 1, "com.example.app"),
                HomeItem.widget(0, 0, 0, 4, 1, WIDGET_ID, "com.example/.W"),
                HomeItem.folder(0, 0, 0, "F", Arrays.asList("a"))}) {
            assertTrue(zones(item, appInfo("com.example.app", false)).size() <= 2);
        }
    }
}
