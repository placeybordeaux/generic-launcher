package com.genericlauncher.launcher;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A real, sectioned settings screen — replaces the nested {@code AlertDialog} chain that used
 * to live inline in {@code MainActivity}. Renders {@link SettingsRows}, the pure data model of
 * what's shown, and dispatches taps by row id.
 *
 * <p>Always finishes (well, is left) with {@code RESULT_OK} so the caller can refresh
 * unconditionally on return rather than tracking which specific setting changed.
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int RC_BACKUP_EXPORT   = 1003;
    private static final int RC_BACKUP_IMPORT   = 1004;
    private static final int RC_DEFAULT_LAUNCHER = 1005;

    /**
     * Result-intent extra set (to {@code true}) when {@link #performImport} completes
     * successfully. Read by {@code MainActivity.onActivityResult}'s {@code RC_SETTINGS} branch
     * to snap back to the homescreen so a restored layout is immediately visible — Settings is
     * only reachable from the drawer toolbar (see {@code activity_main.xml}'s
     * {@code toolbar_row}), so the drawer is always open when this fires.
     */
    static final String EXTRA_RESTORED_LAYOUT = "restored_layout";

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ROW    = 1;

    /**
     * Hard cap on an imported backup file. A real backup is a few KB — this is only here so a
     * mis-picked video can't be read into memory, doubled by the UTF-8 decode and doubled again
     * by the JSON parse before anything notices.
     */
    static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024;

    /** Per-file cap on crash-log text included in the share payload. */
    static final int MAX_CRASH_FILE_CHARS = 30_000;

    /**
     * Total cap on the share payload. {@code EXTRA_TEXT} is parcelled as UTF-16, so the worst
     * case ({@code MAX_CRASH_TOTAL_CHARS + MAX_CRASH_FILE_CHARS}) is ~300 KB against the 1 MB
     * Binder transaction limit.
     */
    static final int MAX_CRASH_TOTAL_CHARS = 120_000;

    private List<SettingsRows.Row> rows = new ArrayList<>();
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        // Unconditional: lets the caller refresh on return without tracking which specific
        // setting changed, if and however much of its own state it chooses to refresh.
        setResult(RESULT_OK);

        TextView title = findViewById(R.id.settings_title);
        title.setText("Settings  —  v" + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.GIT_HASH + ")");

        RecyclerView list = findViewById(R.id.settings_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The badges toggle sends the user to the system notification-access screen and they
        // come back here, not to MainActivity — so the deferred enable has to be finished here
        // too, before the rows are rebuilt, or the switch reads off after a successful grant.
        if (NotificationAccess.finishPendingEnable(this)) {
            Toast.makeText(this, "Notification badges enabled", Toast.LENGTH_SHORT).show();
        }
        rebuild();
    }

    private void rebuild() {
        rows = SettingsRows.build(this, DefaultLauncherHelper.isDefault(this));
        adapter.notifyDataSetChanged();
    }

    /** The rows currently bound to the list. Test-visibility only. */
    List<SettingsRows.Row> rowsForTest() { return rows; }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_DEFAULT_LAUNCHER) {
            rebuild();
        } else if (requestCode == RC_BACKUP_EXPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeBackup(data.getData());
            }
        } else if (requestCode == RC_BACKUP_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                confirmAndImport(data.getData());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Row dispatch
    // -------------------------------------------------------------------------

    private void onRowClick(SettingsRows.Row row) {
        switch (row.id) {
            case "default_launcher":   requestDefaultLauncher(); break;
            case "enabled_views":      showEnabledViewsDialog(); break;
            case "home_grid":          showHomeGridDialog();     break;
            case "notif_dots":         toggleNotifDots();        break;
            case "badge_style":        showBadgeStyleDialog();   break;
            case "badge_size":         showBadgeSizeDialog();    break;
            case "badge_shimmer":
                Settings.setBadgeShimmer(this, !Settings.getBadgeShimmer(this));
                rebuild();
                break;
            case "badge_glow":
                Settings.setBadgeGlow(this, !Settings.getBadgeGlow(this));
                rebuild();
                break;
            case "picker_layout":      showWidgetPickerLayoutDialog(); break;
            case "picker_fullscreen":
                Settings.setWidgetPickerFullscreen(
                        this, !Settings.getWidgetPickerFullscreen(this));
                rebuild();
                break;
            case "backup_export":      doExport();  break;
            case "backup_restore":     doImport();  break;
            case "crash_logs":         shareCrashLogs(); break;
            case "reset_home":         resetHomeScreenToDefaults(); break;
            default: break;
        }
    }

    private void requestDefaultLauncher() {
        if (DefaultLauncherHelper.isDefault(this)) return;
        Intent i = DefaultLauncherHelper.requestIntent(this);
        if (i == null) {
            Toast.makeText(this, "No default-app setting on this device",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivityForResult(i, RC_DEFAULT_LAUNCHER);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "Couldn't open that setting", Toast.LENGTH_SHORT).show();
        }
    }

    private void showBadgeSizeDialog() {
        BadgeSize[] sizes = BadgeSize.values();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Badge size")
                .setSingleChoiceItems(BadgeSize.labels(),
                        Settings.getBadgeSize(this).ordinal(), (dialog, which) -> {
                            Settings.setBadgeSize(this, sizes[which]);
                            dialog.dismiss();
                            rebuild();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -------------------------------------------------------------------------
    // Copied from MainActivity (verbatim except refreshBadges() -> rebuild(), and the
    // MainActivity-only view-state trims noted inline).
    // -------------------------------------------------------------------------

    /**
     * Multi-select of which layout modes the switch button cycles through (min one).
     *
     * <p>Adapted from {@code MainActivity.showEnabledViewsDialog}: that version indexes off
     * the private {@code Mode} enum and, after saving, also resyncs its own live {@code mode}
     * field (and the {@code drawer_mode} pref) if the mode it currently has open in the drawer
     * was just disabled. Neither the enum nor that field is reachable here, so this picker
     * instead drives directly off {@link MainActivity#MODE_LABELS} (already package-visible;
     * {@link SettingsRows} depends on it too) and {@link Settings#isModeEnabled}/
     * {@link Settings#setEnabledModesMask}.
     *
     * <p>{@link Settings#setEnabledModesMask} itself reconciles the persisted
     * {@code drawer_mode} pref so it never keeps pointing at a mode the new mask just disabled
     * (falling back to the lowest-ordinal enabled non-HOME mode, or the "unset" sentinel). What
     * that reconciliation does <em>not</em> cover: a {@code MainActivity} instance already
     * running when this dialog is used keeps its own in-memory {@code mode} field as-is — that
     * field is only reseeded from the pref in {@code onCreate} — so its drawer could keep
     * rendering the disabled mode until the activity is recreated. {@code setResult(RESULT_OK)}
     * does not by itself trigger that resync, but {@code MainActivity.onActivityResult}'s {@code
     * RC_SETTINGS} branch does: on every return from this activity it unconditionally checks
     * {@code !isEnabled(mode) || mode == Mode.HOME} and, if so, reassigns {@code mode} to the
     * first enabled non-HOME mode before its own unconditional {@code renderMode()} call — so a
     * paused-not-recreated {@code MainActivity} never renders a mode this dialog just disabled.
     */
    private void showEnabledViewsDialog() {
        String[] labels = MainActivity.MODE_LABELS;
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) checked[i] = Settings.isModeEnabled(this, i);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Enabled views")
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Save", (dialog, w) -> {
                    int mask = 0;
                    for (int i = 0; i < checked.length; i++) if (checked[i]) mask |= (1 << i);
                    if (mask == 0) mask = Settings.DEFAULT_ENABLED_MASK; // never disable everything
                    Settings.setEnabledModesMask(this, mask);
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Shows a picker for home grid columns (3–6) and rows (4–8).
     *
     * <p>The MainActivity original also calls {@code if (isHome) renderMode();} to redraw its
     * own live homescreen immediately. That view doesn't exist here, so this dialog only
     * persists the new size ({@code Settings.setGridCols}/{@code setGridRows} and
     * {@code store.reloadGridSize}) and refreshes this screen's own row summary via
     * {@link #rebuild()}. A running {@code MainActivity} picks up the new grid size regardless:
     * its {@code onActivityResult}'s {@code RC_SETTINGS} branch unconditionally calls {@code
     * homescreenView.refresh()} on every return from this activity, which re-renders with
     * whatever grid dimensions are current in {@link HomeScreenStore} at that point.
     */
    private void showHomeGridDialog() {
        HomeScreenStore store = HomeScreenStore.get(this);
        int[] selCols = {store.columns()};
        int[] selRows = {store.rows()};

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padH = dp(24);
        layout.setPadding(padH, dp(16), padH, dp(8));

        // Columns section
        android.widget.TextView colLabel = new android.widget.TextView(this);
        colLabel.setText("Columns (3–6)");
        colLabel.setTextColor(0x88FFFFFF);
        colLabel.setTextSize(12f);
        layout.addView(colLabel);

        layout.addView(buildChoiceRow(this, 3, 4, selCols));

        // Spacer
        android.view.View spacer = new android.view.View(this);
        layout.addView(spacer, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));

        // Rows section
        android.widget.TextView rowLabel = new android.widget.TextView(this);
        rowLabel.setText("Rows (4–8)");
        rowLabel.setTextColor(0x88FFFFFF);
        rowLabel.setTextSize(12f);
        layout.addView(rowLabel);

        layout.addView(buildChoiceRow(this, 4, 5, selRows));

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Home grid")
                .setView(layout)
                .setPositiveButton("Apply", (dialog, w) -> {
                    Settings.setGridCols(this, selCols[0]);
                    Settings.setGridRows(this, selRows[0]);
                    store.reloadGridSize(this);
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Minimum touch-target edge, per the Android accessibility guidance. */
    static final int MIN_TOUCH_DP = 48;

    /**
     * A horizontal row of radio buttons for the values {@code first .. first + count - 1},
     * writing the pick into {@code sel[0]}; the one equal to {@code sel[0]} starts checked.
     *
     * <p>Each button takes an equal share of the row's width and at least {@link #MIN_TOUCH_DP}
     * of height, so the whole row is touch target. A bare {@code RadioButton} is the circle and
     * its digit — about 30 x 24dp — and in a dialog that is easy to miss, so the tap has to land
     * on the circle to register. The check state is driven by hand rather than by the group's
     * own exclusivity: the buttons have no ids, and the listener needs the value anyway.
     */
    static android.widget.RadioGroup buildChoiceRow(android.content.Context c, int first,
                                                    int count, int[] sel) {
        float density = c.getResources().getDisplayMetrics().density;
        int minPx = (int) (MIN_TOUCH_DP * density + 0.5f);
        android.widget.RadioGroup group = new android.widget.RadioGroup(c);
        group.setOrientation(android.widget.RadioGroup.HORIZONTAL);
        android.widget.RadioButton[] buttons = new android.widget.RadioButton[count];
        for (int i = 0; i < count; i++) {
            int value = first + i;
            android.widget.RadioButton rb = new android.widget.RadioButton(c);
            rb.setText(String.valueOf(value));
            rb.setTextColor(0xFFFFFFFF);
            rb.setMinHeight(minPx);
            rb.setMinimumHeight(minPx);
            rb.setGravity(android.view.Gravity.CENTER_VERTICAL);
            buttons[i] = rb;
            rb.setOnClickListener(v -> {
                sel[0] = value;
                for (android.widget.RadioButton b : buttons) b.setChecked(b == v);
            });
            if (value == sel[0]) rb.setChecked(true);
            group.addView(rb, new android.widget.RadioGroup.LayoutParams(
                    0, android.widget.RadioGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return group;
    }

    private void showBadgeStyleDialog() {
        BadgeStyle[] styles = BadgeStyle.values();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Badge style")
                .setSingleChoiceItems(BadgeStyle.labels(),
                        Settings.getBadgeStyle(this).ordinal(), (dialog, which) -> {
                            Settings.setBadgeStyle(this, styles[which]);
                            dialog.dismiss();
                            rebuild();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showWidgetPickerLayoutDialog() {
        WidgetPickerLayout[] layouts = WidgetPickerLayout.values();
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Widget picker layout")
                .setSingleChoiceItems(WidgetPickerLayout.labels(),
                        Settings.getWidgetPickerLayout(this).ordinal(), (dialog, which) -> {
                            Settings.setWidgetPickerLayout(this, layouts[which]);
                            dialog.dismiss();
                            rebuild();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Toggles the notification-dots setting. If enabling and notification-listener access
     * has not been granted, opens ACTION_NOTIFICATION_LISTENER_SETTINGS so the user can
     * grant it; then enables the toggle. If disabling, turns it off immediately.
     */
    private void toggleNotifDots() {
        boolean current = Settings.getNotifDots(this);
        if (current) {
            Settings.setNotifDots(this, false);
            Toast.makeText(this, "Notification badges disabled", Toast.LENGTH_SHORT).show();
            rebuild();
        } else {
            // Check whether the notification-listener service has access.
            if (!NotificationAccess.isGranted(this)) {
                // Remember that an enable is what the user actually asked for. It is completed by
                // NotificationAccess.finishPendingEnable from onResume — this activity's and
                // MainActivity's — once access is detected, because there is no result code to
                // wait on here: the system settings screen is a one-way trip. Before this the
                // flow ended with the switch still off after a successful grant, which the user
                // reads as the grant having failed.
                Settings.setNotifDotsPending(this, true);
                Toast.makeText(this,
                        "Grant notification access — badges switch on when you come back",
                        Toast.LENGTH_LONG).show();
                try {
                    startActivity(NotificationAccess.settingsIntent());
                } catch (android.content.ActivityNotFoundException ignored) {}
                // Don't enable the toggle yet — it is finished on return, see above.
            } else {
                Settings.setNotifDots(this, true);
                Toast.makeText(this, "Notification badges enabled", Toast.LENGTH_SHORT).show();
                // Rebuild so existing icons pick up the new setting.
                rebuild();
            }
        }
    }

    /**
     * Shares the crash files written by {@link LauncherApp} as plain text (newest first),
     * so testers can send them over any channel without the app needing network access.
     */
    private void shareCrashLogs() {
        java.io.File dir = new java.io.File(getFilesDir(), "crashes");
        java.io.File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            Toast.makeText(this, "No crash logs recorded", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        StringBuilder sb = new StringBuilder();
        try {
            for (java.io.File f : files) {
                String body;
                try (InputStream is = new java.io.FileInputStream(f)) {
                    // 3 bytes per char is UTF-8's worst case for the BMP, so this always reads
                    // enough to let appendCrashLog do the exact truncation (and notice it).
                    body = new String(readAtMost(is, 3 * (MAX_CRASH_FILE_CHARS + 1)),
                            StandardCharsets.UTF_8);
                }
                if (!appendCrashLog(sb, f.getName(), body,
                        MAX_CRASH_FILE_CHARS, MAX_CRASH_TOTAL_CHARS)) {
                    break;
                }
            }
        } catch (Exception e) {
            Log.w("SettingsActivity", "reading crash logs failed", e);
            Toast.makeText(this, "Could not read crash logs: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Launcher crash logs — v"
                        + BuildConfig.VERSION_NAME + " (" + BuildConfig.GIT_HASH + ")")
                .putExtra(Intent.EXTRA_TEXT, sb.toString());
        try {
            startActivity(Intent.createChooser(send, "Share crash logs"));
        } catch (RuntimeException e) {
            // ActivityNotFoundException when nothing can share text; also the RuntimeException
            // the binder proxy wraps a TransactionTooLargeException in, which no payload we
            // build should now reach — but failing to share is not worth a crash either way.
            Log.w("SettingsActivity", "sharing crash logs failed", e);
            Toast.makeText(this, "Couldn't share the crash logs", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Appends one crash file to the share payload, truncated to {@code perFileChars} first.
     *
     * @return true if there is room for another file; false once {@code maxChars} is reached.
     *         Checking the cap <em>after</em> the append is the point: checking it first made
     *         the real bound {@code maxChars + sizeof(largest file)}, and a StackOverflowError
     *         trace runs to hundreds of KB — enough to push {@code EXTRA_TEXT} past the 1 MB
     *         Binder limit and have {@code startActivity} throw TransactionTooLargeException.
     */
    static boolean appendCrashLog(StringBuilder sb, String name, String body,
                                  int perFileChars, int maxChars) {
        sb.append("==== ").append(name).append(" ====\n");
        if (body.length() > perFileChars) {
            sb.append(body, 0, perFileChars).append("\n… truncated\n");
        } else {
            sb.append(body);
        }
        sb.append('\n');
        return sb.length() < maxChars;
    }

    /**
     * Settings action: confirm, then wipe the home screen layout and re-seed the default dock.
     * Deletes orphaned appWidgetIds so the host doesn't leak them — {@link DefaultHomeSeeder
     * #reseed}'s own javadoc makes deleting them the caller's responsibility.
     *
     * <p>{@code MainActivity} owns the long-lived, listening {@link LauncherWidgetHost} instance
     * (started in its {@code onStart}, stopped in its {@code onStop}), which this activity does
     * not have access to. But {@code deleteAppWidgetId} is serviced by the system keyed on
     * {@code (package, hostId)}, not on a particular {@code AppWidgetHost} object — so a second,
     * transient {@code LauncherWidgetHost} instance constructed here, sharing the same
     * {@link LauncherWidgetHost#HOST_ID}, can validly free ids the other instance allocated
     * without ever calling {@code startListening()}. This activity does not call
     * {@code homescreenView.refresh()} (no such view exists here); {@code MainActivity} redraws
     * with the reseeded layout when it regains focus.
     */
    void resetHomeScreenToDefaults() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Reset home screen?")
                .setMessage("This replaces your current home screen layout with the default one.")
                .setPositiveButton("Reset", (d, w) -> {
                    HomeScreenStore store = HomeScreenStore.get(this);
                    // Collect widget ids BEFORE reseed clears the items.
                    List<Integer> widgetIds = new ArrayList<>();
                    for (HomeItem item : store.getItems()) {
                        if (item.getType() == HomeItem.Type.WIDGET) {
                            widgetIds.add(item.getAppWidgetId());
                        }
                    }
                    DefaultHomeSeeder.reseed(this, store);
                    LauncherWidgetHost host = new LauncherWidgetHost(this);
                    for (int id : widgetIds) {
                        try {
                            host.deleteAppWidgetId(id);
                        } catch (RuntimeException ignored) {
                            // Best effort — a leaked id is harmless.
                        }
                    }
                    // Nothing on this screen changes when the layout is reseeded, so without
                    // this the reset is indistinguishable from a Cancel until the user goes home.
                    Toast.makeText(this, "Home screen reset", Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // -------------------------------------------------------------------------
    // Phase E — Layout backup / export / import
    // -------------------------------------------------------------------------

    /** The name the SAF picker proposes for a backup; restore reads whatever file is picked. */
    static String backupFileName(Date when) {
        return "launcher-layout-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(when)
                + ".json";
    }

    /** Launches the SAF file-creation picker to choose where to write the backup. */
    private void doExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, backupFileName(new Date()));
        try {
            startActivityForResult(intent, RC_BACKUP_EXPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file manager available", Toast.LENGTH_SHORT).show();
        }
    }

    /** Launches the SAF file-open picker to choose a backup file to restore. */
    private void doImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/json");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, RC_BACKUP_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file manager available", Toast.LENGTH_SHORT).show();
        }
    }

    /** Serialises the current home-screen state and writes it to the SAF-provided URI. */
    private void writeBackup(Uri uri) {
        try {
            String json = HomeScreenStore.get(this).toBackupJson(this).toString();
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new java.io.IOException("null output stream");
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, "Layout backed up", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.w("SettingsActivity", "layout backup failed", e);
            Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Shows a confirmation dialog before wiping and replacing the home screen. */
    private void confirmAndImport(Uri uri) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Restore layout?")
                .setMessage("Your current home screen will be replaced with the backup.")
                .setPositiveButton("Restore", (d, w) -> performImport(uri))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Reads and applies a backup file. Errors (oversized file, malformed JSON, unknown version,
     * I/O) produce a toast and abort — the existing layout is never partially modified on
     * failure. The read and parse run on a worker thread; {@link #applyImport} does the actual
     * mutation back on the main thread.
     *
     * <p>The MainActivity original forced {@code isHome = true} directly, since the restore
     * dialog ran as an in-process {@code AlertDialog}. This activity has no {@code isHome} field
     * to set, so instead it flags the result with {@link #EXTRA_RESTORED_LAYOUT}; {@code
     * MainActivity.onActivityResult}'s {@code RC_SETTINGS} branch checks that extra and sets its
     * own {@code isHome = true} (clearing the drawer overlay directly, rather than delegating to
     * the animated {@code goHome()}, so it lands before that branch's own unconditional {@code
     * loadApps()}/{@code renderMode()} calls run) before repainting — so the restored layout is
     * visible immediately even though Settings can only be opened from the drawer.
     */
    private void performImport(Uri uri) {
        // Read and parse off the main thread. ACTION_OPEN_DOCUMENT's MIME filter is advisory —
        // plenty of providers ignore it — so the user can hand us a multi-gigabyte file, and
        // both the SAF read and the JSON parse are unbounded work against an ANR timer.
        Toast.makeText(this, "Restoring…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            HomeScreenStore.ParsedBackup parsed = null;
            String error;
            try {
                long declared = declaredSize(uri);
                if (declared > MAX_IMPORT_BYTES) {
                    throw new java.io.IOException("file is " + declared
                            + " bytes — not a layout backup");
                }
                byte[] bytes;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    if (is == null) throw new java.io.IOException("null input stream");
                    bytes = readFully(is, MAX_IMPORT_BYTES);
                }
                parsed = HomeScreenStore.fromBackupJson(
                        new JSONObject(new String(bytes, StandardCharsets.UTF_8)));
                error = null;
            } catch (HomeScreenStore.BackupVersionException e) {
                error = "Unsupported backup version " + e.version + " — can't restore";
            } catch (Throwable t) {
                // Throwable, not Exception: an oversized pick throws OutOfMemoryError, which is
                // an Error, and letting it out of here kills the launcher process instead of
                // showing a toast. readFully's cap makes that unlikely now; belt and braces.
                Log.w("SettingsActivity", "layout restore failed", t);
                error = "Restore failed: " + t.getMessage();
            }

            final HomeScreenStore.ParsedBackup backup = parsed;
            final String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (backup == null) {
                    Toast.makeText(this, failure == null ? "Restore failed" : failure,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                applyImport(backup);
            });
        }, "layout-import").start();
    }

    /** Applies an already-parsed backup. Main thread — everything here is prefs and store work. */
    private void applyImport(HomeScreenStore.ParsedBackup backup) {
        try {
            // Apply grid settings first so reloadGridSize uses the restored values.
            Settings.setGridCols(this, backup.gridCols);
            Settings.setGridRows(this, backup.gridRows);

            // Atomically replace all home-screen items and re-apply grid dimensions.
            HomeScreenStore store = HomeScreenStore.get(this);
            store.replaceAll(backup.items);
            store.reloadGridSize(this);

            // Build result toast.
            String msg = backup.widgetSkipCount == 0
                    ? "Layout restored"
                    : "Layout restored · " + backup.widgetSkipCount + " widget"
                            + (backup.widgetSkipCount == 1 ? "" : "s")
                            + " skipped (re-add manually)";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();

            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESTORED_LAYOUT, true));
            rebuild();

        } catch (Throwable t) {
            Log.w("SettingsActivity", "layout restore failed", t);
            Toast.makeText(this, "Restore failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Best-effort size of a SAF document, or -1 when the provider won't say. Only a cheap early
     * reject — {@link #readFully}'s own cap is what actually holds when a provider lies.
     */
    private long declaredSize(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) {
            // Providers are free to reject the query, or not to implement it at all.
        }
        return -1;
    }

    /**
     * Reads all bytes from an InputStream without relying on Java 9+ InputStream.readAllBytes(),
     * refusing to buffer more than {@code maxBytes} — the size the provider declared is only a
     * claim, and the whole payload is about to be doubled by {@code new String(bytes, UTF_8)}
     * and doubled again by the JSON parse.
     */
    private static byte[] readFully(InputStream is, long maxBytes) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) {
            if (buf.size() + n > maxBytes) {
                throw new java.io.IOException("file exceeds " + maxBytes + " bytes");
            }
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    /** Reads at most {@code maxBytes}, silently discarding the rest. */
    private static byte[] readAtMost(InputStream is, int maxBytes) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while (buf.size() < maxBytes && (n = is.read(tmp)) != -1) {
            buf.write(tmp, 0, Math.min(n, maxBytes - buf.size()));
        }
        return buf.toByteArray();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type == SettingsRows.Type.HEADER
                    ? VIEW_TYPE_HEADER : VIEW_TYPE_ROW;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_HEADER) {
                View v = inflater.inflate(R.layout.item_settings_header, parent, false);
                return new HeaderHolder(v);
            }
            View v = inflater.inflate(R.layout.item_settings_row, parent, false);
            return new RowHolder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            SettingsRows.Row row = rows.get(position);
            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).title.setText(row.title);
                return;
            }
            RowHolder rh = (RowHolder) holder;
            rh.title.setText(row.title);
            if (row.summary != null) {
                rh.summary.setVisibility(View.VISIBLE);
                rh.summary.setText(row.summary);
            } else {
                rh.summary.setVisibility(View.GONE);
            }

            rh.switchView.setVisibility(row.type == SettingsRows.Type.TOGGLE
                    ? View.VISIBLE : View.GONE);
            if (row.type == SettingsRows.Type.TOGGLE) rh.switchView.setChecked(row.checked);

            rh.itemView.setEnabled(true);
            boolean showChevron = row.type == SettingsRows.Type.NAV;
            if (row.type == SettingsRows.Type.DEFAULT_LAUNCHER) {
                if (row.checked) {
                    rh.itemView.setEnabled(false);
                    rh.title.setText(row.title + "  ✓");
                } else {
                    showChevron = true;
                }
            }
            rh.chevron.setVisibility(showChevron ? View.VISIBLE : View.GONE);

            rh.itemView.setOnClickListener(v -> onRowClick(row));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        HeaderHolder(View v) {
            super(v);
            title = v.findViewById(R.id.header_title);
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView summary;
        final SwitchCompat switchView;
        final TextView chevron;
        RowHolder(View v) {
            super(v);
            title = v.findViewById(R.id.row_title);
            summary = v.findViewById(R.id.row_summary);
            switchView = v.findViewById(R.id.row_switch);
            chevron = v.findViewById(R.id.row_chevron);
        }
    }
}
