package com.genericlauncher.launcher;

import android.app.Activity;
import android.app.Notification;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

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
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * "Notification bubble doesn't show up in folder view" — the badge on the member icons
 * <em>inside</em> an open folder popup.
 *
 * <p>The folder half of the badge story was already half-built and that is what made this hard to
 * see: the chip on the page carries a summed badge (see {@link FolderBadgeVisibilityTest}), and
 * {@code updateBadgeCounts} was already documented as safe to run "with the folder popup open".
 * But {@code buildPopupAppCell} set the raw {@code info.getIcon()} on its ImageView and never
 * built a {@link BadgeDrawable} at all, so opening the folder to find out <em>which</em> app the
 * chip's count was about showed nothing.
 *
 * <p>The counts here are deliberately unequal — 3 on the first member, 5 on the second, 8 on the
 * chip. A fixture where the members' counts and the folder's sum coincide cannot tell a member
 * badge from the folder badge copied onto every member, which is exactly the shape the binding
 * would have taken if it reused {@code badgeCountForItem(folder)}: {@code item} there is the
 * folder, so every member would read 8.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FolderPopupBadgeTest {

    private static final int W = 1080;
    private static final int H = 2424;

    private static final String FIRST  = "com.example.first";
    private static final String SECOND = "com.example.second";

    private ActivityController<Activity> controller;
    private Activity        activity;
    private HomeScreenStore store;
    private HomescreenView  view;
    private HomeItem        folder;
    private Map<String, AppInfo> apps;

    private int nextNotifId = 1;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        NotificationDotsService.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();
        store = HomeScreenStore.get(activity);

        Settings.setNotifDots(activity, true);
        Settings.setBadgeStyle(activity, BadgeStyle.FLAT);
        Settings.setBadgeSize(activity, BadgeSize.MEDIUM);
        Settings.setBadgeShimmer(activity, false);
        Settings.setBadgeGlow(activity, false);

        // Real icons, not null: a null base turns BadgeDrawable into its badge-only mode, which
        // is a different code path from the one a folder member actually takes.
        apps = new HashMap<>();
        apps.put(FIRST,  app(FIRST,  "First",  0xFF3366CC));
        apps.put(SECOND, app(SECOND, "Second", 0xFFCC3366));

        folder = HomeItem.folder(0, 0, 0, "Folder", Arrays.asList(FIRST, SECOND));
        store.replaceAll(new ArrayList<>(Arrays.asList(folder)));

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setAppMap(apps);
        root.addView(view, W, H);
        view.refresh(apps);
        layoutPass();
    }

    @After
    public void tearDown() {
        NotificationDotsService.resetForTesting();
        controller.pause().stop().destroy();
    }

    private static AppInfo app(String pkg, String label, int color) {
        return new AppInfo(label, pkg, new ColorDrawable(color));
    }

    private void layoutPass() {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    /** Posts {@code counts[i]} notifications for package {@code pkgs[i]} and pushes the result. */
    private void notify(String[] pkgs, int[] counts) {
        List<StatusBarNotification> posted = new ArrayList<>();
        for (int i = 0; i < pkgs.length; i++) {
            for (int n = 0; n < counts[i]; n++) {
                posted.add(new StatusBarNotification(pkgs[i], pkgs[i], nextNotifId++,
                        "tag" + nextNotifId, /*uid=*/1000, /*initialPid=*/0, /*score=*/0,
                        new Notification(), Process.myUserHandle(), System.currentTimeMillis()));
            }
        }
        NotificationDotsService.rebuild(
                posted.isEmpty() ? null : posted.toArray(new StatusBarNotification[0]));
        view.updateBadgeCounts();
    }

    /** The unequal fixture the class doc describes: 3 on the first member, 5 on the second. */
    private void notifyThreeAndFive() {
        notify(new String[]{FIRST, SECOND}, new int[]{3, 5});
    }

    // ---------------------------------------------------------------------
    // Walking into the open popup
    // ---------------------------------------------------------------------

    /** The member grid inside the open popup (overlay child 1 is the card; child 0 is the scrim). */
    private GridLayout memberGrid() {
        View overlay = view.getChildAt(view.getChildCount() - 1);
        assertTrue("test setup: no popup overlay on top", overlay instanceof FrameLayout);
        ViewGroup card = (ViewGroup) ((FrameLayout) overlay).getChildAt(1);
        View last = card.getChildAt(card.getChildCount() - 1);
        assertTrue("test setup: the card's last child should be the member ScrollView",
                last instanceof ScrollView);
        View grid = ((ScrollView) last).getChildAt(0);
        assertTrue("test setup: the ScrollView should hold the member grid",
                grid instanceof GridLayout);
        return (GridLayout) grid;
    }

    /** The icon drawable of member cell {@code index} inside the open popup. */
    private Drawable memberIcon(int index) {
        LinearLayout cell = (LinearLayout) memberGrid().getChildAt(index);
        assertNotNull("test setup: no member cell at index " + index, cell);
        View icon = cell.getChildAt(0);
        assertTrue("test setup: a member cell's first child should be its ImageView",
                icon instanceof ImageView);
        return ((ImageView) icon).getDrawable();
    }

    /** Asserts member {@code index} shows a badge of exactly {@code expected}. */
    private void assertMemberBadge(int index, int expected) {
        Drawable d = memberIcon(index);
        assertTrue("member " + index + " carries no notification badge at all — its icon is a "
                        + (d == null ? "null drawable" : d.getClass().getSimpleName()),
                d instanceof BadgeDrawable);
        assertEquals("member " + index + "'s badge count", expected, ((BadgeDrawable) d).getCount());
    }

    /** The summed badge on the home-screen chip, for contrast with the members' own counts. */
    private int chipBadgeCount() {
        LinearLayout cell = (LinearLayout) view.getPage(0).getChildAt(0);
        FrameLayout chip = (FrameLayout) cell.getChildAt(0);
        for (int i = 0; i < chip.getChildCount(); i++) {
            View child = chip.getChildAt(i);
            if (child instanceof ImageView
                    && ((ImageView) child).getDrawable() instanceof BadgeDrawable) {
                return ((BadgeDrawable) ((ImageView) child).getDrawable()).getCount();
            }
        }
        fail("test setup: the folder chip carries no badge overlay");
        return -1;
    }

    // ---------------------------------------------------------------------
    // The bug
    // ---------------------------------------------------------------------

    @Test
    public void aFolderMemberIconCarriesItsNotificationBadge() {
        notifyThreeAndFive();
        assertEquals("test setup: the count must be non-zero or the assertion proves nothing",
                3, NotificationDotsService.notificationCount(FIRST));

        view.openFolderPopup(folder);

        assertMemberBadge(0, 3);
    }

    /**
     * The heart of it: each member shows its <em>own</em> count. The chip's 8 is the sum, and a
     * member badge that read 8 would be the folder's badge stamped onto every icon.
     */
    @Test
    public void eachMemberShowsItsOwnCountNotTheFolderSum() {
        notifyThreeAndFive();
        assertEquals("test setup: the chip shows the sum, so 8 is a value a member could wrongly "
                + "have picked up", 8, chipBadgeCount());

        view.openFolderPopup(folder);

        assertMemberBadge(0, 3);
        assertMemberBadge(1, 5);
    }

    /** A member with nothing pending shows no badge, even while its neighbour does. */
    @Test
    public void aQuietMemberShowsNothingWhileANoisyOneDoes() {
        notify(new String[]{FIRST}, new int[]{4});

        view.openFolderPopup(folder);

        assertMemberBadge(0, 4);
        assertMemberBadge(1, 0);
    }

    // ---------------------------------------------------------------------
    // Staying live while the popup is open
    // ---------------------------------------------------------------------

    /**
     * A notification arriving while the folder is open has to reach the member icon. Nothing else
     * will: {@code refresh()} closes the popup rather than rebuilding it, so if
     * {@code updateBadgeCounts} does not know about these drawables the badge cannot appear until
     * the user closes and reopens the folder.
     */
    @Test
    public void aNotificationArrivingWhileThePopupIsOpenUpdatesTheMemberIcon() {
        view.openFolderPopup(folder);
        assertMemberBadge(0, 0);

        notifyThreeAndFive();

        assertMemberBadge(0, 3);
        assertMemberBadge(1, 5);
    }

    /** ...and a notification being dismissed takes the badge away again. */
    @Test
    public void clearingTheNotificationsClearsTheMemberBadges() {
        notifyThreeAndFive();
        view.openFolderPopup(folder);
        assertMemberBadge(0, 3);

        notify(new String[]{}, new int[]{});

        assertMemberBadge(0, 0);
        assertMemberBadge(1, 0);
    }

    // ---------------------------------------------------------------------
    // Binding lifetime — the popup's badges must not pile up
    // ---------------------------------------------------------------------

    /**
     * The popup's bindings live in their own list precisely because the grid is rebuilt without a
     * {@code refresh()}. Reopening the folder must replace them, not append: otherwise every open
     * leaves two dead {@link BadgeDrawable}s (each holding a {@link BadgePainter}'s worth of
     * Paints) behind, and {@code updateBadgeCounts} walks them forever.
     */
    @Test
    public void reopeningTheFolderReplacesTheMemberBindingsRatherThanAppending() {
        view.openFolderPopup(folder);
        assertEquals(2, view.popupBadgeBindingCountForTest());

        view.openFolderPopup(folder);   // closes the first popup on the way in
        assertEquals("reopening a folder must not leave the old members' bindings behind",
                2, view.popupBadgeBindingCountForTest());
    }

    @Test
    public void closingTheFolderDropsTheMemberBindings() {
        view.openFolderPopup(folder);
        assertEquals("test setup", 2, view.popupBadgeBindingCountForTest());

        view.closeFolderPopup();

        assertEquals("the member cells are gone; their bindings must go with them",
                0, view.popupBadgeBindingCountForTest());
    }

    /** With badges switched off the popup builds no counts, exactly as the pages do not. */
    @Test
    public void badgesOffMeansNoCountInThePopup() {
        Settings.setNotifDots(activity, false);
        notify(new String[]{FIRST, SECOND}, new int[]{3, 5});

        view.openFolderPopup(folder);

        assertMemberBadge(0, 0);
    }
}
