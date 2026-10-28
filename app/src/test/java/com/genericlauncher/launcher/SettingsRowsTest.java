package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

/** Unit tests for {@link SettingsRows} — the pure model behind SettingsActivity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SettingsRowsTest {

    private Context ctx() { return ApplicationProvider.getApplicationContext(); }

    private static SettingsRows.Row byId(List<SettingsRows.Row> rows, String id) {
        for (SettingsRows.Row r : rows) if (id.equals(r.id)) return r;
        fail("no row with id " + id);
        return null;
    }

    @Test
    public void defaultLauncherRowIsFirstAndReflectsState() {
        List<SettingsRows.Row> notDefault = SettingsRows.build(ctx(), false);
        assertEquals(SettingsRows.Type.DEFAULT_LAUNCHER, notDefault.get(0).type);
        assertFalse(notDefault.get(0).checked);

        List<SettingsRows.Row> isDefault = SettingsRows.build(ctx(), true);
        assertTrue(isDefault.get(0).checked);
    }

    @Test
    public void everySectionHeaderIsPresentInOrder() {
        StringBuilder headers = new StringBuilder();
        for (SettingsRows.Row r : SettingsRows.build(ctx(), false)) {
            if (r.type == SettingsRows.Type.HEADER) headers.append(r.title).append('|');
        }
        assertEquals("APPEARANCE|LIVED-IN|NOTIFICATION BADGES|WIDGETS|BACKUP|TROUBLESHOOTING|",
                headers.toString());
    }

    @Test
    public void enabledViewsRowListsTheEnabledModes() {
        // DEFAULT_ENABLED_MASK is List(0) + Home(2); Usage(1) is off until its permission exists.
        Settings.setEnabledModesMask(ctx(), Settings.DEFAULT_ENABLED_MASK);
        assertEquals("List, Home",
                byId(SettingsRows.build(ctx(), false), "enabled_views").summary);
    }

    @Test
    public void badgeStyleRowSummarisesTheCurrentStyle() {
        Settings.setBadgeStyle(ctx(), BadgeStyle.RAINBOW);
        assertEquals("Rainbow", byId(SettingsRows.build(ctx(), false), "badge_style").summary);
    }

    @Test
    public void badgeSizeRowSummarisesTheCurrentSize() {
        Settings.setBadgeSize(ctx(), BadgeSize.HUGE);
        assertEquals("Huge", byId(SettingsRows.build(ctx(), false), "badge_size").summary);
    }

    @Test
    public void homeGridRowSummarisesColsByRows() {
        Settings.setGridCols(ctx(), 5);
        Settings.setGridRows(ctx(), 6);
        assertEquals("5 × 6", byId(SettingsRows.build(ctx(), false), "home_grid").summary);
    }

    @Test
    public void togglesReflectStoredPrefs() {
        Settings.setNotifDots(ctx(), true);
        Settings.setBadgeShimmer(ctx(), false);
        Settings.setBadgeGlow(ctx(), true);
        Settings.setWidgetPickerFullscreen(ctx(), true);

        List<SettingsRows.Row> rows = SettingsRows.build(ctx(), false);
        assertTrue(byId(rows, "notif_dots").checked);
        assertFalse(byId(rows, "badge_shimmer").checked);
        assertTrue(byId(rows, "badge_glow").checked);
        assertTrue(byId(rows, "picker_fullscreen").checked);

        for (String id : new String[]{
                "notif_dots", "badge_shimmer", "badge_glow", "picker_fullscreen"}) {
            assertEquals(id, SettingsRows.Type.TOGGLE, byId(rows, id).type);
        }
    }

    @Test
    public void actionRowsArePresent() {
        List<SettingsRows.Row> rows = SettingsRows.build(ctx(), false);
        for (String id : new String[]{
                "backup_export", "backup_restore", "crash_logs", "reset_home"}) {
            assertEquals(id, SettingsRows.Type.ACTION, byId(rows, id).type);
        }
    }

    @Test
    public void everyRowIdIsUnique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SettingsRows.Row r : SettingsRows.build(ctx(), false)) {
            if (r.type == SettingsRows.Type.HEADER) continue;
            assertTrue("duplicate row id: " + r.id, seen.add(r.id));
        }
    }
}
