package com.genericlauncher.launcher;

import android.app.Activity;
import android.app.Notification;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

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
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * S3 — a folder's first notification must make its badge appear without rebuilding the page.
 *
 * <p>M5 stopped a notification from running a full {@code refresh()}, because re-inflating every
 * widget's RemoteViews on every message made the home screen flicker; {@code updateBadgeCounts()}
 * now just raises the count on each live {@link BadgeDrawable}. That works for an app icon, whose
 * badge drawable wraps the icon and so has a fixed intrinsic size.
 *
 * <p>It did not work for a folder chip. There the badge is a badge-only drawable in its own
 * {@link ImageView}, and at count 0 it reported no intrinsic size at all — so the ImageView
 * measured 1×1 px. {@code setCount} only invalidates; {@code ImageView.invalidateDrawable} updates
 * the cached drawable size and redraws but never calls {@code requestLayout()}, so the view stayed
 * 1×1 and the chip clipped the badge away. The folder's badge appeared only once some unrelated
 * refresh happened to rebuild the page.
 *
 * <p>The last two tests pin M5 itself: raising a count must still not cost a layout pass or a
 * widget rebuild, so the fix cannot be "simplified" back into an unconditional
 * {@code requestLayout()}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FolderBadgeVisibilityTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int WIDGET_ID = 7;

    private ActivityController<Activity> controller;
    private Activity        activity;
    private FrameLayout     root;
    private HomeScreenStore store;
    private HomescreenView  view;
    private Map<String, AppInfo> apps;

    private int nextNotifId = 1;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        root       = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        NotificationDotsService.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();
        store = HomeScreenStore.get(activity);

        apps = new HashMap<>();
        addApp("com.example.first");
        addApp("com.example.second");

        Settings.setNotifDots(activity, true);
        store.replaceAll(new ArrayList<>(Arrays.asList(
                HomeItem.folder(0, 0, 0, "Folder",
                        Arrays.asList("com.example.first", "com.example.second")))));

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setAppMap(apps);
        root.addView(view, W, H);
    }

    @After
    public void tearDown() {
        NotificationDotsService.resetForTesting();
        controller.pause().stop().destroy();
    }

    private void addApp(String pkg) {
        apps.put(pkg, new AppInfo(pkg, pkg, null));
    }

    private void buildAndLayout() {
        view.refresh(apps);
        layoutPass();
    }

    private void layoutPass() {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    private StatusBarNotification sbn(String pkg) {
        return new StatusBarNotification(pkg, pkg, nextNotifId++, "tag" + nextNotifId,
                /*uid=*/1000, /*initialPid=*/0, /*score=*/0, new Notification(),
                Process.myUserHandle(), System.currentTimeMillis());
    }

    /** Posts {@code n} notifications for the folder's first member and pushes the counts. */
    private void notify(int n) {
        StatusBarNotification[] posted = new StatusBarNotification[n];
        for (int i = 0; i < n; i++) posted[i] = sbn("com.example.first");
        NotificationDotsService.rebuild(n == 0 ? null : posted);
        view.updateBadgeCounts();
    }

    /** The badge-only overlay {@link ImageView} on page 0's folder chip. */
    private ImageView folderBadgeView() {
        View cell = view.getPage(0).getChildAt(0);
        FrameLayout chip = (FrameLayout) ((LinearLayout) cell).getChildAt(0);
        for (int i = 0; i < chip.getChildCount(); i++) {
            View child = chip.getChildAt(i);
            if (child instanceof ImageView
                    && ((ImageView) child).getDrawable() instanceof BadgeDrawable) {
                return (ImageView) child;
            }
        }
        fail("test setup: the folder chip carries no badge overlay");
        return null;
    }

    /** Asserts the badge view is big enough to show whatever its drawable currently wants to draw. */
    private static void assertBadgeFits(ImageView badgeView) {
        Drawable d = badgeView.getDrawable();
        assertTrue("the badge view must be able to show the badge, but the drawable wants "
                        + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight()
                        + " and the view is " + badgeView.getWidth() + "x" + badgeView.getHeight(),
                badgeView.getWidth()  >= d.getIntrinsicWidth()
                        && badgeView.getHeight() >= d.getIntrinsicHeight());
    }

    // ---------------------------------------------------------------------
    // S3 — the badge has to be measured before it has anything to show
    // ---------------------------------------------------------------------

    @Test
    public void aFolderBadgeViewIsMeasuredEvenWithNoNotifications() {
        buildAndLayout();
        ImageView badgeView = folderBadgeView();

        assertEquals("test setup: no notifications yet", 0,
                ((BadgeDrawable) badgeView.getDrawable()).getCount());
        assertTrue("a badge view measured at " + badgeView.getWidth() + "x"
                        + badgeView.getHeight() + " px can never show a badge that arrives later",
                badgeView.getWidth() > 1 && badgeView.getHeight() > 1);
    }

    @Test
    public void aFoldersFirstNotificationFitsTheViewAlreadyLaidOut() {
        buildAndLayout();
        ImageView badgeView = folderBadgeView();

        notify(2);

        assertEquals(2, ((BadgeDrawable) badgeView.getDrawable()).getCount());
        assertBadgeFits(badgeView);
    }

    @Test
    public void aBadgeWideningIntoATwoDigitPillStillFitsItsView() {
        buildAndLayout();
        ImageView badgeView = folderBadgeView();

        notify(9);
        layoutPass();   // the single-digit badge gets a layout pass of its own
        notify(12);     // ...and then widens into a pill without one

        assertBadgeFits(badgeView);
    }

    @Test
    public void aBadgeOnlyDrawableKeepsOneIntrinsicSizeAcrossCounts() {
        buildAndLayout();
        BadgeDrawable badge = (BadgeDrawable) folderBadgeView().getDrawable();
        int w = badge.getIntrinsicWidth();
        int h = badge.getIntrinsicHeight();

        assertTrue("a badge-only drawable must report a usable size at count 0", w > 0 && h > 0);
        for (int count : new int[]{1, 9, 12, 100}) {
            badge.setCount(count);
            assertEquals("intrinsic width must not depend on the count", w, badge.getIntrinsicWidth());
            assertEquals("intrinsic height must not depend on the count", h, badge.getIntrinsicHeight());
        }
    }

    // ---------------------------------------------------------------------
    // M5 — what the S3 fix must not undo
    // ---------------------------------------------------------------------

    @Test
    public void anOrdinaryCountChangeCostsNoLayoutPass() {
        buildAndLayout();
        ImageView badgeView = folderBadgeView();
        notify(1);
        layoutPass();
        assertFalse("test setup: the layout pass should have settled everything",
                badgeView.isLayoutRequested());

        notify(2);

        assertFalse("1 -> 2 must not re-measure the badge", badgeView.isLayoutRequested());
        assertFalse("nor the home screen", view.isLayoutRequested());
    }

    @Test
    public void aNotificationDoesNotRebuildAWidget() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = new ComponentName("com.example.clock", ".ClockProvider");
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_NONE;
        ShadowAppWidgetManager shadow = Shadows.shadowOf(AppWidgetManager.getInstance(activity));
        shadow.addBoundWidget(WIDGET_ID, provider);
        store.addItem(HomeItem.widget(0, 2, 2, 2, 2, WIDGET_ID, "com.example.clock/.ClockProvider"));

        final int[] created = {0};
        view.setWidgetHost(new AppWidgetHost(activity, 1024) {
            @Override
            protected AppWidgetHostView onCreateView(Context c, int id, AppWidgetProviderInfo i) {
                created[0]++;
                return super.onCreateView(c, id, i);
            }
        });
        buildAndLayout();
        assertEquals("test setup: the widget should have been inflated once", 1, created[0]);

        ImageView badgeView = folderBadgeView();
        notify(3);

        assertEquals(3, ((BadgeDrawable) badgeView.getDrawable()).getCount());
        assertEquals("a notification must not re-inflate the widget's RemoteViews", 1, created[0]);
    }
}
