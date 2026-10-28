package com.genericlauncher.launcher;

import android.app.Activity;
import android.app.Notification;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.DragEvent;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.DragEventBuilder;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * M6, M8 and M5 — what happens when a reload, a detach or a notification lands at a moment the
 * gesture code doesn't expect.
 *
 * <ul>
 *   <li><b>M6</b>: a package broadcast mid-drag reached {@code refresh()}, which does
 *       {@code removeAllViews()} while {@code isDragging}/{@code draggedItem}/{@code dragSourceView}
 *       all survive — so the drop then committed against a view tree that no longer existed.</li>
 *   <li><b>M8</b>: {@code onDetachedFromWindow} unregistered the dot listener and nothing else,
 *       leaving a posted edge-page flip to run against a dead view and {@code isDragging} stuck
 *       true forever.</li>
 *   <li><b>M5</b>: every notification rebuilt the entire home screen, widgets included.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class HomescreenRefreshDeferralTest {

    private static final int W = 1080;
    private static final int H = 2424;

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
        store.addItem(HomeItem.app(0, 0, 0, "com.example.first"));

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setAppMap(apps);
        root.addView(view, W, H);
        view.refresh();
        layoutPass();
    }

    @After
    public void tearDown() {
        NotificationDotsService.resetForTesting();
        controller.pause().stop().destroy();
    }

    private void addApp(String pkg) {
        apps.put(pkg, new AppInfo(pkg, pkg, null));
    }

    private void layoutPass() {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    private static DragEvent dragEvent(int action, float x, float y) {
        return DragEventBuilder.newBuilder()
                .setAction(action)
                .setX(x)
                .setY(y)
                .setResult(false)
                .setLocalState(new DrawerDragState("com.example.second"))
                .build();
    }

    private void startDrag() {
        view.dispatchDragEvent(dragEvent(DragEvent.ACTION_DRAG_STARTED, 100, 1200));
        ShadowLooper.idleMainLooper(); // drains the deferred drawer-dismiss post
    }

    private void endDrag() {
        view.dispatchDragEvent(dragEvent(DragEvent.ACTION_DRAG_ENDED, 100, 1200));
        ShadowLooper.idleMainLooper();
    }

    private int iconsOnPageZero() {
        return view.getPage(0).getChildCount();
    }

    // ---------------------------------------------------------------------
    // M6 — a reload landing mid-drag
    // ---------------------------------------------------------------------

    @Test
    public void aReloadMidDragDoesNotTearDownTheViewTree() {
        startDrag();
        CellLayout pageBefore = view.getPage(0);
        int before = iconsOnPageZero();

        // A background install completes: MainActivity.loadApps() -> refresh(appMap).
        store.addItem(HomeItem.app(0, 2, 2, "com.example.second"));
        view.refresh(apps);

        assertSame("the page the drag is over must survive", pageBefore, view.getPage(0));
        assertEquals("no rebuild while a drag is in flight", before, iconsOnPageZero());
    }

    @Test
    public void theDeferredReloadIsReplayedWhenTheDragEnds() {
        startDrag();
        int before = iconsOnPageZero();

        store.addItem(HomeItem.app(0, 2, 2, "com.example.second"));
        view.refresh(apps);
        endDrag();

        assertEquals("the deferred reload must not be lost", before + 1, iconsOnPageZero());
    }

    @Test
    public void aReloadOutsideADragStillRunsImmediately() {
        int before = iconsOnPageZero();
        store.addItem(HomeItem.app(0, 2, 2, "com.example.second"));
        view.refresh(apps);
        assertEquals(before + 1, iconsOnPageZero());
    }

    @Test
    public void theDeferralRuleCoversDragsAndOpenResizeFrames() {
        assertTrue(HomescreenView.shouldDeferRefresh(true, false));
        assertTrue(HomescreenView.shouldDeferRefresh(false, true));
        assertFalse(HomescreenView.shouldDeferRefresh(false, false));
    }

    // ---------------------------------------------------------------------
    // M6 — a drop whose item was deleted from under it
    // ---------------------------------------------------------------------

    @Test
    public void aDropForAnItemStillInTheStoreIsCommittable() {
        HomeItem item = store.getItems().get(0);
        assertTrue(HomescreenView.canCommitDrop(store.getItems(), item));
    }

    @Test
    public void aDropForAnUninstalledItemIsNotCommittable() {
        HomeItem item = store.getItems().get(0);
        // The uninstall completes mid-drag; MainActivity's receiver calls removePackage.
        store.removePackage("com.example.first");
        assertFalse("committing would move an orphan, or fold a dead package into a new folder",
                HomescreenView.canCommitDrop(store.getItems(), item));
    }

    @Test
    public void aNullDraggedItemIsNotCommittable() {
        assertFalse(HomescreenView.canCommitDrop(store.getItems(), null));
    }

    // ---------------------------------------------------------------------
    // M8 — detach cleanup
    // ---------------------------------------------------------------------

    @Test
    public void detachingClearsTheDragStateSoADeferredReloadIsNotStranded() {
        startDrag();
        store.addItem(HomeItem.app(0, 2, 2, "com.example.second"));
        view.refresh(apps); // deferred

        root.removeView(view);

        // isDragging must have been reset, so the next refresh actually runs instead of being
        // deferred forever against a drag that can no longer end.
        view.refresh(apps);
        assertEquals(2, iconsOnPageZero());
    }

    @Test
    public void detachingCancelsAScheduledEdgePageFlip() {
        startDrag();
        // Hover the right edge to arm the edge-flip timer, then throw the view away.
        view.dispatchDragEvent(dragEvent(DragEvent.ACTION_DRAG_LOCATION, W - 5, 1200));
        root.removeView(view);

        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);

        assertNull("a detached view must not grow a new page from a stale posted callback",
                view.getPage(1));
    }

    @Test
    public void detachingIsIdempotent() {
        // onDetachedFromWindow recycles the velocity tracker; doing it twice must not double-free.
        root.removeView(view);
        view.onDetachedFromWindow();
    }

    // ---------------------------------------------------------------------
    // M5 — a notification must not rebuild the home screen
    // ---------------------------------------------------------------------

    /** The badge drawable on page 0's first icon. */
    private BadgeDrawable badgeOnFirstIcon() {
        View item = view.getPage(0).getChildAt(0);
        assertTrue("test setup: page 0 child 0 should be an app cell", item instanceof LinearLayout);
        View icon = ((LinearLayout) item).getChildAt(0);
        assertTrue("test setup: the app cell's first child should be the icon",
                icon instanceof ImageView);
        Drawable d = ((ImageView) icon).getDrawable();
        assertTrue("test setup: the icon should carry a BadgeDrawable", d instanceof BadgeDrawable);
        return (BadgeDrawable) d;
    }

    private StatusBarNotification sbn(String pkg) {
        return new StatusBarNotification(pkg, pkg, nextNotifId++, "tag" + nextNotifId,
                /*uid=*/1000, /*initialPid=*/0, /*score=*/0, new Notification(),
                Process.myUserHandle(), System.currentTimeMillis());
    }

    @Test
    public void aNotificationUpdatesTheBadgeCountInPlace() {
        Settings.setNotifDots(activity, true);
        view.refresh(apps); // rebuild with badges enabled

        CellLayout pageBefore = view.getPage(0);
        View       cellBefore = pageBefore.getChildAt(0);
        assertEquals(0, badgeOnFirstIcon().getCount());

        NotificationDotsService.rebuild(new StatusBarNotification[]{
                sbn("com.example.first"), sbn("com.example.first") });
        view.updateBadgeCounts();

        assertEquals("the badge count must follow the notification", 2,
                badgeOnFirstIcon().getCount());
        assertSame("the page must not be rebuilt for a notification", pageBefore, view.getPage(0));
        assertSame("nor the icon views", cellBefore, view.getPage(0).getChildAt(0));
    }

    @Test
    public void aClearedNotificationDropsTheBadgeBackToZero() {
        Settings.setNotifDots(activity, true);
        NotificationDotsService.rebuild(new StatusBarNotification[]{ sbn("com.example.first") });
        view.refresh(apps);
        assertEquals(1, badgeOnFirstIcon().getCount());

        NotificationDotsService.rebuild(null);
        view.updateBadgeCounts();

        assertEquals(0, badgeOnFirstIcon().getCount());
    }

    @Test
    public void aFoldersFirstNotificationRaisesItsBadgeWithoutARebuild() {
        // The folder badge has to exist at count 0 for this to be possible at all — that is why
        // addFolderBadge no longer early-returns on an empty count.
        Settings.setNotifDots(activity, true);
        store.replaceAll(new ArrayList<>(Arrays.asList(
                HomeItem.folder(0, 0, 0, "Folder",
                        Arrays.asList("com.example.first", "com.example.second")))));
        view.refresh(apps);

        CellLayout pageBefore = view.getPage(0);
        BadgeDrawable badge = folderBadge();
        assertEquals(0, badge.getCount());

        NotificationDotsService.rebuild(new StatusBarNotification[]{
                sbn("com.example.first"), sbn("com.example.second") });
        view.updateBadgeCounts();

        assertEquals("a folder badge sums its members", 2, badge.getCount());
        assertSame(pageBefore, view.getPage(0));
    }

    /** The badge-only drawable overlaid on page 0's folder chip. */
    private BadgeDrawable folderBadge() {
        View cell = view.getPage(0).getChildAt(0);
        FrameLayout chip = (FrameLayout) ((LinearLayout) cell).getChildAt(0);
        List<BadgeDrawable> found = new ArrayList<>();
        for (int i = 0; i < chip.getChildCount(); i++) {
            View child = chip.getChildAt(i);
            if (child instanceof ImageView
                    && ((ImageView) child).getDrawable() instanceof BadgeDrawable) {
                found.add((BadgeDrawable) ((ImageView) child).getDrawable());
            }
        }
        assertEquals("exactly one badge overlay per folder chip", 1, found.size());
        return found.get(0);
    }
}
