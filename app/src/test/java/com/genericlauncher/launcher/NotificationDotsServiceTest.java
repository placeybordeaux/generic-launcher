package com.genericlauncher.launcher;

import android.app.Notification;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link NotificationDotsService}'s per-package count map, the
 * {@link NotificationDotsService#rebuild} recomputation contract, and the composite-key
 * badge-matching logic (via {@link UserKeys#packageOf}).
 *
 * <p>Robolectric is needed because {@link StatusBarNotification} and {@link Notification} are
 * real framework classes; {@code rebuild} is exercised with genuine instances rather than a test
 * seam, so the "recompute, never increment" guarantee is verified against the real API shape.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class NotificationDotsServiceTest {

    private int nextId = 1;

    @Before
    public void setUp() {
        NotificationDotsService.resetForTesting();
        nextId = 1;
    }

    @After
    public void tearDown() {
        NotificationDotsService.resetForTesting();
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** A non-ongoing notification for {@code pkg} with a distinct key. */
    private StatusBarNotification sbn(String pkg, String tag) {
        return sbn(pkg, tag, false);
    }

    private StatusBarNotification sbn(String pkg, String tag, boolean ongoing) {
        Notification n = new Notification();
        if (ongoing) n.flags |= Notification.FLAG_ONGOING_EVENT;
        return new StatusBarNotification(
                pkg, pkg, nextId++, tag, /*uid=*/1000, /*initialPid=*/0, /*score=*/0,
                n, Process.myUserHandle(), System.currentTimeMillis());
    }

    private static StatusBarNotification[] active(StatusBarNotification... items) {
        return items;
    }

    // -------------------------------------------------------------------------
    // Counts
    // -------------------------------------------------------------------------

    @Test
    public void notificationCount_empty_returnsZero() {
        assertEquals(0, NotificationDotsService.notificationCount("com.example.app"));
    }

    @Test
    public void notificationCount_nullPackage_returnsZero() {
        assertEquals(0, NotificationDotsService.notificationCount(null));
    }

    @Test
    public void rebuild_countsOneNotification() {
        NotificationDotsService.rebuild(active(sbn("com.example.app", "a")));
        assertEquals(1, NotificationDotsService.notificationCount("com.example.app"));
    }

    @Test
    public void rebuild_countsMultipleNotificationsForSamePackage() {
        NotificationDotsService.rebuild(active(
                sbn("com.example.app", "a"),
                sbn("com.example.app", "b"),
                sbn("com.example.app", "c")));
        assertEquals(3, NotificationDotsService.notificationCount("com.example.app"));
    }

    @Test
    public void rebuild_countsPerPackageIndependently() {
        NotificationDotsService.rebuild(active(
                sbn("com.example.app",   "a"),
                sbn("com.example.app",   "b"),
                sbn("com.example.other", "a")));
        assertEquals(2, NotificationDotsService.notificationCount("com.example.app"));
        assertEquals(1, NotificationDotsService.notificationCount("com.example.other"));
        assertEquals(0, NotificationDotsService.notificationCount("com.example.absent"));
    }

    @Test
    public void rebuild_nullArray_clearsMap() {
        NotificationDotsService.rebuild(active(sbn("com.example.app", "a")));
        NotificationDotsService.rebuild(null);
        assertEquals(0, NotificationDotsService.notificationCount("com.example.app"));
        assertTrue(NotificationDotsService.NOTIFIED_PKGS.isEmpty());
    }

    @Test
    public void rebuild_emptyArray_clearsMap() {
        NotificationDotsService.rebuild(active(sbn("com.example.app", "a")));
        NotificationDotsService.rebuild(active());
        assertEquals(0, NotificationDotsService.notificationCount("com.example.app"));
        assertTrue(NotificationDotsService.NOTIFIED_PKGS.isEmpty());
    }

    @Test
    public void rebuild_zeroCountPackagesAreAbsentNotZeroMapped() {
        NotificationDotsService.rebuild(active(sbn("com.example.app", "a")));
        NotificationDotsService.rebuild(active(sbn("com.example.other", "a")));
        assertFalse(NotificationDotsService.NOTIFIED_PKGS.containsKey("com.example.app"));
        assertEquals(1, NotificationDotsService.NOTIFIED_PKGS.size());
    }

    // -------------------------------------------------------------------------
    // Ongoing filtering
    // -------------------------------------------------------------------------

    @Test
    public void rebuild_ignoresOngoingNotifications() {
        NotificationDotsService.rebuild(active(sbn("com.music.player", "np", true)));
        assertEquals(0, NotificationDotsService.notificationCount("com.music.player"));
        assertFalse(NotificationDotsService.hasNotification("com.music.player"));
    }

    @Test
    public void rebuild_mixedOngoingAndNormal_countsOnlyNormal() {
        NotificationDotsService.rebuild(active(
                sbn("com.music.player", "np",   true),
                sbn("com.music.player", "song", false),
                sbn("com.music.player", "dl",   true)));
        assertEquals(1, NotificationDotsService.notificationCount("com.music.player"));
    }

    // -------------------------------------------------------------------------
    // The no-increment guarantee: a re-posted key must not inflate the count
    // -------------------------------------------------------------------------

    @Test
    public void repeatedRebuildWithSameActiveSet_doesNotInflateCount() {
        // Simulates a messaging app re-posting the SAME notification key over and over:
        // onNotificationPosted fires each time, but getActiveNotifications() still holds one
        // entry for that key, so recomputing must keep the count at 1.
        StatusBarNotification one = sbn("com.messaging.app", "chat-42");
        for (int i = 0; i < 25; i++) {
            NotificationDotsService.rebuild(active(one));
            assertEquals(1, NotificationDotsService.notificationCount("com.messaging.app"));
        }
    }

    @Test
    public void repeatedRebuild_multiKeyPackage_staysStable() {
        StatusBarNotification a = sbn("com.messaging.app", "chat-1");
        StatusBarNotification b = sbn("com.messaging.app", "chat-2");
        for (int i = 0; i < 10; i++) {
            NotificationDotsService.rebuild(active(a, b));
        }
        assertEquals(2, NotificationDotsService.notificationCount("com.messaging.app"));
    }

    @Test
    public void rebuild_reflectsGrowthAndShrinkOfTheActiveSet() {
        StatusBarNotification a = sbn("com.messaging.app", "chat-1");
        StatusBarNotification b = sbn("com.messaging.app", "chat-2");
        StatusBarNotification c = sbn("com.messaging.app", "chat-3");

        NotificationDotsService.rebuild(active(a));
        assertEquals(1, NotificationDotsService.notificationCount("com.messaging.app"));

        NotificationDotsService.rebuild(active(a, b, c));
        assertEquals(3, NotificationDotsService.notificationCount("com.messaging.app"));

        // Two dismissed — a plain recompute decrements correctly, no bookkeeping needed.
        NotificationDotsService.rebuild(active(b));
        assertEquals(1, NotificationDotsService.notificationCount("com.messaging.app"));
    }

    // -------------------------------------------------------------------------
    // Removal
    // -------------------------------------------------------------------------

    @Test
    public void removal_ofLastNotification_dropsThePackage() {
        StatusBarNotification a = sbn("com.example.app", "a");
        NotificationDotsService.rebuild(active(a));
        assertTrue(NotificationDotsService.hasNotification("com.example.app"));

        NotificationDotsService.rebuild(active());   // user swiped it away
        assertFalse(NotificationDotsService.hasNotification("com.example.app"));
        assertEquals(0, NotificationDotsService.notificationCount("com.example.app"));
    }

    @Test
    public void removal_ofOneOfSeveral_decrementsWithoutClearing() {
        StatusBarNotification a = sbn("com.example.app", "a");
        StatusBarNotification b = sbn("com.example.app", "b");
        NotificationDotsService.rebuild(active(a, b));
        assertEquals(2, NotificationDotsService.notificationCount("com.example.app"));

        NotificationDotsService.rebuild(active(a));
        assertEquals(1, NotificationDotsService.notificationCount("com.example.app"));
        assertTrue(NotificationDotsService.hasNotification("com.example.app"));
    }

    @Test
    public void removal_doesNotAffectOtherPackages() {
        NotificationDotsService.rebuild(active(
                sbn("com.example.app",   "a"),
                sbn("com.example.other", "a"),
                sbn("com.example.other", "b")));

        NotificationDotsService.rebuild(active(
                sbn("com.example.other", "a"),
                sbn("com.example.other", "b")));

        assertEquals(0, NotificationDotsService.notificationCount("com.example.app"));
        assertEquals(2, NotificationDotsService.notificationCount("com.example.other"));
    }

    // -------------------------------------------------------------------------
    // hasNotification agrees with count > 0
    // -------------------------------------------------------------------------

    @Test
    public void hasNotification_agreesWithCountGreaterThanZero() {
        String[] pkgs = {"com.example.app", "com.example.other", "com.example.absent"};
        NotificationDotsService.rebuild(active(
                sbn("com.example.app",   "a"),
                sbn("com.example.other", "a"),
                sbn("com.example.other", "b")));
        for (String p : pkgs) {
            assertEquals("mismatch for " + p,
                    NotificationDotsService.notificationCount(p) > 0,
                    NotificationDotsService.hasNotification(p));
        }
    }

    @Test
    public void hasNotification_empty_returnsFalse() {
        assertFalse(NotificationDotsService.hasNotification("com.example.app"));
    }

    @Test
    public void hasNotification_different_package_returnsFalse() {
        NotificationDotsService.NOTIFIED_PKGS.put("com.example.other", 1);
        assertFalse(NotificationDotsService.hasNotification("com.example.app"));
    }

    // -------------------------------------------------------------------------
    // Composite-key matching (badges apply to both personal and work entries whose
    // UserKeys.packageOf matches the bare notification package name)
    // -------------------------------------------------------------------------

    @Test
    public void badgeApplies_personalKey_barePackage() {
        String barePkg = "com.example.app";
        NotificationDotsService.rebuild(active(sbn(barePkg, "a"), sbn(barePkg, "b")));

        String personalKey = "com.example.app";          // bare — personal app
        assertEquals(barePkg, UserKeys.packageOf(personalKey));
        assertEquals(2, NotificationDotsService.notificationCount(
                UserKeys.packageOf(personalKey)));
    }

    @Test
    public void badgeApplies_workKey_sameBarePkg() {
        String barePkg = "com.example.app";
        NotificationDotsService.rebuild(active(sbn(barePkg, "a")));

        String workKey = UserKeys.compose("com.example.app", 10); // "com.example.app#10"
        assertEquals(barePkg, UserKeys.packageOf(workKey));
        assertTrue(NotificationDotsService.hasNotification(UserKeys.packageOf(workKey)));
    }

    @Test
    public void badgeDoesNotApply_differentPackage() {
        NotificationDotsService.rebuild(active(sbn("com.other.app", "a")));

        String workKey = "com.example.app#10";
        assertFalse(NotificationDotsService.hasNotification(UserKeys.packageOf(workKey)));
    }

    @Test
    public void badgeApplies_multipleWorkSerials_allMatch() {
        // Two work copies of the same package (e.g. cloned app with different serials).
        String barePkg = "com.example.shared";
        NotificationDotsService.rebuild(active(sbn(barePkg, "a"), sbn(barePkg, "b")));

        String work1 = UserKeys.compose(barePkg, 10);
        String work2 = UserKeys.compose(barePkg, 20);

        assertEquals(2, NotificationDotsService.notificationCount(UserKeys.packageOf(work1)));
        assertEquals(2, NotificationDotsService.notificationCount(UserKeys.packageOf(work2)));
    }

    // -------------------------------------------------------------------------
    // Listener add / remove (structural — just verifies no crash or leak)
    // -------------------------------------------------------------------------

    @Test
    public void addAndRemoveListener_noException() {
        List<String> calls = new ArrayList<>();
        NotificationDotsService.ChangeListener l = () -> calls.add("fired");

        NotificationDotsService.addListener(l);
        NotificationDotsService.removeListener(l);
        // After removal, static state should be clean (no crash on subsequent ops).
        NotificationDotsService.addListener(l);
        NotificationDotsService.removeListener(l);
    }

    @Test
    public void resetForTesting_clearsState() {
        NotificationDotsService.NOTIFIED_PKGS.put("com.foo", 3);
        NotificationDotsService.resetForTesting();
        assertFalse(NotificationDotsService.hasNotification("com.foo"));
        assertEquals(0, NotificationDotsService.notificationCount("com.foo"));
    }
}
