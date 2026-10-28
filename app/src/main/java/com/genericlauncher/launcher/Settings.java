package com.genericlauncher.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Small wrapper over SharedPreferences for user-tweakable options. */
public final class Settings {

    private static final String PREFS = "color_picker_prefs";
    private static final String KEY_ENABLED_MODES  = "enabled_modes";
    private static final String KEY_GRID_COLS      = "home_grid_cols";
    private static final String KEY_GRID_ROWS      = "home_grid_rows";
    private static final String KEY_NOTIF_DOTS     = "notif_dots";
    /** See {@link #isNotifDotsPending}: an enable waiting on a notification-access grant. */
    private static final String KEY_NOTIF_DOTS_PENDING = "notif_dots_pending";
    /** Last non-HOME drawer mode ordinal; -1 if never explicitly set. */
    private static final String KEY_DRAWER_MODE    = "drawer_mode";
    /** True once the first-run default layout has been seeded (or skipped for upgraders). */
    private static final String KEY_HOME_SEEDED = "home_seeded";
    /** True once the "add Google search widget" placeholder has been dismissed or fulfilled. */
    private static final String KEY_SEARCH_PLACEHOLDER_DISMISSED = "search_placeholder_dismissed";
    /** Notification badge visual style ordinal ({@link BadgeStyle}). */
    private static final String KEY_BADGE_STYLE   = "badge_style";
    /** Whether badges animate a specular shimmer sweep. */
    private static final String KEY_BADGE_SHIMMER = "badge_shimmer";
    /** Whether badges animate a breathing outer glow. */
    private static final String KEY_BADGE_GLOW    = "badge_glow";
    /** Notification badge size ordinal ({@link BadgeSize}). */
    private static final String KEY_BADGE_SIZE = "badge_size";
    /** Widget picker layout ordinal ({@link WidgetPickerLayout}). */
    private static final String KEY_WIDGET_PICKER_LAYOUT     = "widget_picker_layout";
    /** Whether the widget picker opens as a full-screen sheet with a search field. */
    private static final String KEY_WIDGET_PICKER_FULLSCREEN = "widget_picker_fullscreen";
    /** Whether the one-time oversized-widget span correction has already run. */
    private static final String KEY_WIDGET_SPANS_CORRECTED   = "widget_spans_corrected";

    /**
     * Default mode bitmask: List (0) + Home (2) enabled, Usage (1) off.
     *
     * <p>Usage is off by default because it is inert without the Usage Access special permission,
     * and a view that shows an empty list until the user visits system settings is not a default.
     */
    public static final int DEFAULT_ENABLED_MASK = (1 << 0) | (1 << 2);

    /** Sentinel for {@link #getDrawerMode}: the user has never explicitly opened the drawer. */
    public static final int DRAWER_MODE_UNSET = -1;

    /** Default home grid dimensions (columns 3–6, rows 4–8). */
    public static final int DEFAULT_GRID_COLS = 4;
    public static final int DEFAULT_GRID_ROWS = 5;

    /**
     * Ordinal of {@code Mode.HOME} (LIST=0, USAGE=1, HOME=2 — see
     * {@link #DEFAULT_ENABLED_MASK}'s javadoc). {@code Mode} itself is a private nested enum of
     * {@code MainActivity} and not visible here, so — matching this file's existing practice of
     * hardcoding mode-ordinal bit positions as raw ints — the boundary is duplicated as a named
     * constant rather than an enum reference. {@code drawer_mode} never legitimately holds this
     * value; see {@link #reconcileDrawerMode}.
     */
    private static final int HOME_MODE_ORDINAL = 2;

    private Settings() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Bitmask of which layout modes are enabled (bit i = mode ordinal i). Never zero. */
    public static int getEnabledModesMask(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains(KEY_ENABLED_MODES)) {
            return DEFAULT_ENABLED_MASK; // fresh install — already includes HOME bit
        }
        int mask = p.getInt(KEY_ENABLED_MODES, DEFAULT_ENABLED_MASK);
        if (mask == 0) mask = DEFAULT_ENABLED_MASK;
        return mask;
    }

    public static void setEnabledModesMask(Context c, int mask) {
        int effectiveMask = mask == 0 ? DEFAULT_ENABLED_MASK : mask;
        SharedPreferences p = prefs(c);
        p.edit().putInt(KEY_ENABLED_MODES, effectiveMask).apply();
        reconcileDrawerMode(p, effectiveMask);
    }

    /**
     * Keeps {@code drawer_mode} pointing at a mode the new mask still enables.
     *
     * <p>Without this, disabling the mode a caller currently has open in the drawer leaves
     * {@code drawer_mode} — and, for any caller that mirrors it into a live in-memory field the
     * way {@code MainActivity.mode} does, that field too, until it is independently
     * resynchronised — pointing at a mode nothing re-checks after the one-time enabled-check in
     * {@code MainActivity.onCreate}. If that was also the only enabled non-HOME mode, the
     * mode-switch button that would otherwise offer an escape is hidden as well. Reconciling
     * here, in the pref layer both {@code enabled_modes} and {@code drawer_mode} live in, fixes
     * every caller of this setter at once rather than requiring each call site to remember to
     * do it — {@code MainActivity}'s own "Enabled views" dialog already does its own additional
     * live-field resync on top of this; this method only guarantees the persisted pref is sane.
     */
    private static void reconcileDrawerMode(SharedPreferences p, int mask) {
        int drawerMode = p.getInt(KEY_DRAWER_MODE, DRAWER_MODE_UNSET);
        if (drawerMode == DRAWER_MODE_UNSET) return; // nothing to reconcile
        if ((mask & (1 << drawerMode)) != 0) return; // still enabled — leave it

        // Fall back to the lowest-ordinal enabled non-HOME mode, or the "unset" sentinel if
        // none qualify (MainActivity's onCreate fallback chain handles UNSET correctly).
        int fallback = DRAWER_MODE_UNSET;
        for (int ordinal = 0; ordinal < HOME_MODE_ORDINAL; ordinal++) {
            if ((mask & (1 << ordinal)) != 0) { fallback = ordinal; break; }
        }
        p.edit().putInt(KEY_DRAWER_MODE, fallback).apply();
    }

    public static boolean isModeEnabled(Context c, int ordinal) {
        return (getEnabledModesMask(c) & (1 << ordinal)) != 0;
    }

    /** Grid columns stored in prefs (clamped to 3–6). */
    public static int getGridCols(Context c) {
        int v = prefs(c).getInt(KEY_GRID_COLS, DEFAULT_GRID_COLS);
        return Math.max(3, Math.min(6, v));
    }

    public static void setGridCols(Context c, int cols) {
        prefs(c).edit().putInt(KEY_GRID_COLS, Math.max(3, Math.min(6, cols))).apply();
    }

    /** Grid rows stored in prefs (clamped to 4–8). */
    public static int getGridRows(Context c) {
        int v = prefs(c).getInt(KEY_GRID_ROWS, DEFAULT_GRID_ROWS);
        return Math.max(4, Math.min(8, v));
    }

    public static void setGridRows(Context c, int rows) {
        prefs(c).edit().putInt(KEY_GRID_ROWS, Math.max(4, Math.min(8, rows))).apply();
    }

    // -------------------------------------------------------------------------
    // Notification dots (Phase D)
    // -------------------------------------------------------------------------

    /** Whether notification badges are enabled. Default off. */
    public static boolean getNotifDots(Context c) {
        return prefs(c).getBoolean(KEY_NOTIF_DOTS, false);
    }

    public static void setNotifDots(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_NOTIF_DOTS, enabled).apply();
    }

    /**
     * Whether the user asked to turn badges on and was sent to grant notification access first.
     *
     * <p>Set when the badges toggle bounces the user out to
     * {@code ACTION_NOTIFICATION_LISTENER_SETTINGS}, cleared when the enable is completed on
     * their return. Without it the flow ends with the switch still off: the user grants access,
     * comes back, and has to toggle a second time — which reads as the grant having failed.
     */
    public static boolean isNotifDotsPending(Context c) {
        return prefs(c).getBoolean(KEY_NOTIF_DOTS_PENDING, false);
    }

    public static void setNotifDotsPending(Context c, boolean pending) {
        prefs(c).edit().putBoolean(KEY_NOTIF_DOTS_PENDING, pending).apply();
    }

    /** Badge visual style. Clamped to the valid range so a corrupt pref can't throw. */
    public static BadgeStyle getBadgeStyle(Context c) {
        int i = prefs(c).getInt(KEY_BADGE_STYLE, BadgeStyle.FLAT.ordinal());
        BadgeStyle[] values = BadgeStyle.values();
        return values[Math.max(0, Math.min(i, values.length - 1))];
    }

    public static void setBadgeStyle(Context c, BadgeStyle style) {
        prefs(c).edit().putInt(KEY_BADGE_STYLE, style.ordinal()).apply();
    }

    /** Whether badges animate a specular shimmer sweep. Default off. */
    public static boolean getBadgeShimmer(Context c) {
        return prefs(c).getBoolean(KEY_BADGE_SHIMMER, false);
    }

    public static void setBadgeShimmer(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_BADGE_SHIMMER, enabled).apply();
    }

    /** Whether badges animate a breathing outer glow. Default off. */
    public static boolean getBadgeGlow(Context c) {
        return prefs(c).getBoolean(KEY_BADGE_GLOW, false);
    }

    public static void setBadgeGlow(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_BADGE_GLOW, enabled).apply();
    }

    /** Badge size. Clamped to the valid range so a corrupt pref can't throw. */
    public static BadgeSize getBadgeSize(Context c) {
        int i = prefs(c).getInt(KEY_BADGE_SIZE, BadgeSize.MEDIUM.ordinal());
        BadgeSize[] values = BadgeSize.values();
        return values[Math.max(0, Math.min(i, values.length - 1))];
    }

    public static void setBadgeSize(Context c, BadgeSize size) {
        prefs(c).edit().putInt(KEY_BADGE_SIZE, size.ordinal()).apply();
    }

    // -------------------------------------------------------------------------
    // Widget picker
    // -------------------------------------------------------------------------

    /**
     * Whether the one-time correction of oversized widget spans has run.
     *
     * <p>It must not repeat: once resize works, a widget the user deliberately enlarged looks
     * exactly like one placed too large, and re-running would keep undoing their resizes.
     */
    public static boolean getWidgetSpansCorrected(Context c) {
        return prefs(c).getBoolean(KEY_WIDGET_SPANS_CORRECTED, false);
    }

    public static void setWidgetSpansCorrected(Context c, boolean done) {
        prefs(c).edit().putBoolean(KEY_WIDGET_SPANS_CORRECTED, done).apply();
    }

    /** Widget picker layout. Clamped to the valid range so a corrupt pref can't throw. */
    public static WidgetPickerLayout getWidgetPickerLayout(Context c) {
        int i = prefs(c).getInt(
                KEY_WIDGET_PICKER_LAYOUT, WidgetPickerLayout.PREVIEW_CARDS.ordinal());
        WidgetPickerLayout[] values = WidgetPickerLayout.values();
        return values[Math.max(0, Math.min(i, values.length - 1))];
    }

    public static void setWidgetPickerLayout(Context c, WidgetPickerLayout layout) {
        prefs(c).edit().putInt(KEY_WIDGET_PICKER_LAYOUT, layout.ordinal()).apply();
    }

    /** Whether the picker opens full-screen with a search field. Default off (dialog). */
    public static boolean getWidgetPickerFullscreen(Context c) {
        return prefs(c).getBoolean(KEY_WIDGET_PICKER_FULLSCREEN, false);
    }

    public static void setWidgetPickerFullscreen(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(KEY_WIDGET_PICKER_FULLSCREEN, enabled).apply();
    }

    // -------------------------------------------------------------------------
    // Drawer mode (last non-HOME mode opened via swipe-up)
    // -------------------------------------------------------------------------

    /**
     * Returns the saved drawer-mode ordinal, or {@code -1} if the user has never explicitly
     * opened the drawer (signals that the caller should fall back to a sensible default).
     */
    public static int getDrawerMode(Context c) {
        return prefs(c).getInt(KEY_DRAWER_MODE, DRAWER_MODE_UNSET);
    }

    /**
     * Persists the drawer-mode ordinal. Call whenever the user switches modes inside the drawer
     * so the next swipe-up reopens the same view.
     */
    public static void setDrawerMode(Context c, int ordinal) {
        prefs(c).edit().putInt(KEY_DRAWER_MODE, ordinal).apply();
    }

    public static boolean isHomeSeeded(Context c) {
        return prefs(c).getBoolean(KEY_HOME_SEEDED, false);
    }

    public static void setHomeSeeded(Context c, boolean seeded) {
        prefs(c).edit().putBoolean(KEY_HOME_SEEDED, seeded).apply();
    }

    public static boolean isSearchPlaceholderDismissed(Context c) {
        return prefs(c).getBoolean(KEY_SEARCH_PLACEHOLDER_DISMISSED, false);
    }

    public static void setSearchPlaceholderDismissed(Context c, boolean dismissed) {
        prefs(c).edit().putBoolean(KEY_SEARCH_PLACEHOLDER_DISMISSED, dismissed).apply();
    }

}
