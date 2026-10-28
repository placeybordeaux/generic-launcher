package com.genericlauncher.launcher;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notification listener that maintains a static map of package name → count of active,
 * non-ongoing notifications. Views subscribe via {@link #addListener} to receive callbacks
 * when the map changes so they can redraw their notification badges.
 *
 * <p>Service lifecycle: dormant when the user hasn't granted notification-listener access or
 * when the "Notification badges" toggle is off. In both cases the static map is empty so
 * {@link #hasNotification} always returns false and {@link #notificationCount} returns 0 —
 * no badges, no crashes.
 *
 * <p><b>Counting rule: always recompute, never increment.</b> {@code onNotificationPosted} fires
 * again for every <em>update</em> to an existing notification key — messaging apps re-post the
 * same key on each new message — so incrementing a counter would drift upwards without bound.
 * Every callback instead funnels through {@link #rebuild(StatusBarNotification[])}, which derives
 * the whole map from {@link #getActiveNotifications()}. That array holds one entry per live key,
 * making the count correct by construction no matter how often a key is re-posted.
 *
 * <p>Threading: all service callbacks (onNotificationPosted, etc.) are delivered on the main
 * thread. Listener add/remove likewise happens from view-lifecycle calls on the main thread.
 * The {@link #NOTIFIED_PKGS} map is a {@link ConcurrentHashMap} purely as a defensive measure;
 * the {@link #LISTENERS} list is accessed only on the main thread.
 */
public class NotificationDotsService extends NotificationListenerService {

    // -------------------------------------------------------------------------
    // Static shared state — thread-safe package counts + weak listener registry
    // -------------------------------------------------------------------------

    /**
     * Bare package name → number of active, non-ongoing notifications for it. Packages with no
     * notifications are absent rather than mapped to zero.
     * Package-private so tests can inspect/populate it directly.
     */
    static final ConcurrentHashMap<String, Integer> NOTIFIED_PKGS = new ConcurrentHashMap<>();

    /** Listeners held weakly — views are never leaked even if they forget to unregister. */
    private static final List<WeakReference<ChangeListener>> LISTENERS = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Callback fired on the main thread whenever the notified-package map changes.
     * Implement in views; register in onAttachedToWindow / onAttachedToRecyclerView and
     * unregister in the corresponding detach callback.
     */
    public interface ChangeListener {
        void onNotificationDotsChanged();
    }

    /**
     * Registers a listener. Dead weak-references are pruned on each registration.
     * Must be called on the main thread.
     */
    public static void addListener(ChangeListener l) {
        purgeDeadListeners();
        LISTENERS.add(new WeakReference<>(l));
    }

    /**
     * Unregisters a previously registered listener (and prunes any dead refs).
     * Must be called on the main thread.
     */
    public static void removeListener(ChangeListener l) {
        for (int i = LISTENERS.size() - 1; i >= 0; i--) {
            ChangeListener ref = LISTENERS.get(i).get();
            if (ref == null || ref == l) LISTENERS.remove(i);
        }
    }

    /**
     * Returns true if the given <em>bare</em> package name has at least one active
     * non-ongoing notification. Callers are responsible for stripping the UserKeys
     * composite suffix before calling: {@code hasNotification(UserKeys.packageOf(key))}.
     */
    public static boolean hasNotification(String barePkg) {
        return notificationCount(barePkg) > 0;
    }

    /**
     * Number of active non-ongoing notifications for the given <em>bare</em> package name;
     * 0 when the package has none. Same composite-key caveat as {@link #hasNotification}.
     */
    public static int notificationCount(String barePkg) {
        if (barePkg == null) return 0;
        Integer n = NOTIFIED_PKGS.get(barePkg);
        return n == null ? 0 : n;
    }

    // -------------------------------------------------------------------------
    // Recomputation — the one place the map is written
    // -------------------------------------------------------------------------

    /**
     * Recomputes {@link #NOTIFIED_PKGS} from the complete set of currently-active notifications.
     * Ongoing (FLAG_ONGOING_EVENT) notifications — media controls, downloads, foreground-service
     * chips — are excluded, as they aren't things the user needs to act on.
     *
     * <p>Pure with respect to the argument and does not notify listeners; callers do that. Static
     * and package-private so it is testable without standing up a real listener service.
     *
     * @param active every live notification, typically {@link #getActiveNotifications()}.
     *               {@code null} clears the map.
     */
    static void rebuild(StatusBarNotification[] active) {
        if (active == null || active.length == 0) {
            NOTIFIED_PKGS.clear();
            return;
        }
        // Tally into a local map first, then swap, so readers never observe a half-built state.
        Map<String, Integer> counts = new HashMap<>();
        for (StatusBarNotification sbn : active) {
            if (sbn == null || isOngoing(sbn)) continue;
            String pkg = sbn.getPackageName();
            if (pkg == null) continue;
            Integer prev = counts.get(pkg);
            counts.put(pkg, prev == null ? 1 : prev + 1);
        }
        NOTIFIED_PKGS.keySet().retainAll(counts.keySet());
        NOTIFIED_PKGS.putAll(counts);
    }

    // -------------------------------------------------------------------------
    // NotificationListenerService callbacks — all delivered on the main thread.
    // -------------------------------------------------------------------------

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        refresh();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // getActiveNotifications() already excludes the removed notification at this point, so a
        // plain recompute drops the package (or decrements it) without any special-casing.
        refresh();
    }

    @Override
    public void onListenerConnected() {
        refresh();
    }

    @Override
    public void onListenerDisconnected() {
        NOTIFIED_PKGS.clear();
        notifyListeners();
    }

    // -------------------------------------------------------------------------
    // Test helper
    // -------------------------------------------------------------------------

    /** Clears all state. For use in unit tests only. */
    static void resetForTesting() {
        NOTIFIED_PKGS.clear();
        LISTENERS.clear();
    }

    /**
     * Fans a change out to every registered listener, as {@link #refresh()} does after a rebuild.
     * For use in unit tests only: {@link #rebuild} is deliberately silent, so a test that wants a
     * view to <em>react</em> to a notification, not merely draw it, needs this.
     */
    static void notifyListenersForTesting() {
        notifyListeners();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Recomputes the map from the live notification set and fans the change out to views. */
    private void refresh() {
        StatusBarNotification[] active;
        try {
            active = getActiveNotifications();
        } catch (SecurityException | IllegalStateException e) {
            // Listener access revoked or not yet bound — treat as "nothing active".
            active = null;
        }
        rebuild(active);
        notifyListeners();
    }

    private static boolean isOngoing(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        return n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    private static void notifyListeners() {
        for (int i = LISTENERS.size() - 1; i >= 0; i--) {
            ChangeListener l = LISTENERS.get(i).get();
            if (l == null) {
                LISTENERS.remove(i); // dead ref — prune in place
            } else {
                l.onNotificationDotsChanged();
            }
        }
    }

    private static void purgeDeadListeners() {
        for (int i = LISTENERS.size() - 1; i >= 0; i--) {
            if (LISTENERS.get(i).get() == null) LISTENERS.remove(i);
        }
    }
}
