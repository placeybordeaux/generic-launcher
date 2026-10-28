package com.genericlauncher.launcher;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The rows shown by {@code SettingsActivity}, as plain data.
 *
 * <p>Kept separate from the activity so the screen's content — which sections exist, what each
 * row currently reads, which toggles are on — is unit-testable without inflating a view.
 */
public final class SettingsRows {

    public enum Type { HEADER, DEFAULT_LAUNCHER, NAV, TOGGLE, ACTION }

    /** One row. {@code summary} is the current value for NAV rows, {@code checked} for TOGGLEs. */
    public static final class Row {
        public final Type type;
        public final String id;
        public final String title;
        @Nullable public final String summary;
        public final boolean checked;

        Row(Type type, String id, String title, @Nullable String summary, boolean checked) {
            this.type = type;
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.checked = checked;
        }
    }

    private SettingsRows() {}

    /** Leading word of a label like {@code "Rainbow  ·  slowly cycles…"}. */
    private static String shortLabel(String label) {
        return label.split(" {2}·")[0].trim();
    }

    /** {@code MainActivity.MODE_LABELS} is {@code {"Rainbow","Wheel","Usage","Home"}} — plain
     *  names, not the {@code "Name  ·  description"} form the other label arrays use, so they
     *  are joined verbatim. */
    private static String enabledViewsSummary(Context c) {
        StringBuilder sb = new StringBuilder();
        String[] names = MainActivity.MODE_LABELS;
        for (int i = 0; i < names.length; i++) {
            if (Settings.isModeEnabled(c, i)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(names[i]);
            }
        }
        return sb.length() == 0 ? "None" : sb.toString();
    }

    /**
     * Builds the full row list for the current settings.
     *
     * @param isDefaultLauncher result of {@link DefaultLauncherHelper#isDefault(Context)};
     *                          passed in rather than read here so the model stays pure and the
     *                          activity can re-check it on resume
     */
    public static List<Row> build(Context c, boolean isDefaultLauncher) {
        List<Row> rows = new ArrayList<>();

        rows.add(new Row(Type.DEFAULT_LAUNCHER, "default_launcher",
                isDefaultLauncher ? "Default launcher" : "Set as default launcher",
                isDefaultLauncher ? null : "This launcher is not currently your home app",
                isDefaultLauncher));

        rows.add(new Row(Type.HEADER, "h_appearance", "APPEARANCE", null, false));
        rows.add(new Row(Type.NAV, "enabled_views", "Enabled views",
                enabledViewsSummary(c), false));
        rows.add(new Row(Type.NAV, "home_grid", "Home grid",
                Settings.getGridCols(c) + " × " + Settings.getGridRows(c), false));

        rows.add(new Row(Type.HEADER, "h_badges", "NOTIFICATION BADGES", null, false));
        rows.add(new Row(Type.TOGGLE, "notif_dots", "Badges",
                "Show notification counts on icons", Settings.getNotifDots(c)));
        rows.add(new Row(Type.NAV, "badge_style", "Style",
                shortLabel(BadgeStyle.labels()[Settings.getBadgeStyle(c).ordinal()]), false));
        rows.add(new Row(Type.NAV, "badge_size", "Size",
                shortLabel(BadgeSize.labels()[Settings.getBadgeSize(c).ordinal()]), false));
        rows.add(new Row(Type.TOGGLE, "badge_shimmer", "Shimmer sweep",
                null, Settings.getBadgeShimmer(c)));
        rows.add(new Row(Type.TOGGLE, "badge_glow", "Breathing glow",
                null, Settings.getBadgeGlow(c)));

        rows.add(new Row(Type.HEADER, "h_widgets", "WIDGETS", null, false));
        rows.add(new Row(Type.NAV, "picker_layout", "Picker layout",
                shortLabel(WidgetPickerLayout.labels()[
                        Settings.getWidgetPickerLayout(c).ordinal()]), false));
        rows.add(new Row(Type.TOGGLE, "picker_fullscreen", "Full-screen with search",
                null, Settings.getWidgetPickerFullscreen(c)));

        rows.add(new Row(Type.HEADER, "h_backup", "BACKUP", null, false));
        rows.add(new Row(Type.ACTION, "backup_export", "Back up layout",
                "Save your home screen layout to a file you choose", false));
        // Widgets are skipped on restore by design (their ids belong to the install that made
        // the backup), and the "N widgets skipped" toast is too late to be the first the user
        // hears of it.
        rows.add(new Row(Type.ACTION, "backup_restore", "Restore layout",
                "Replace the current layout from a backup file. Widgets aren't backed up and "
                        + "need re-adding.", false));

        rows.add(new Row(Type.HEADER, "h_trouble", "TROUBLESHOOTING", null, false));
        rows.add(new Row(Type.ACTION, "crash_logs", "Share crash logs", null, false));
        rows.add(new Row(Type.ACTION, "reset_home", "Reset home screen to defaults", null, false));

        return rows;
    }
}
