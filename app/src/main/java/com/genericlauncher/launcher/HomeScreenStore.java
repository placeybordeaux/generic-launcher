package com.genericlauncher.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.os.UserManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Singleton store for home-screen items. Persists as JSON to {@code homescreen.json} in internal
 * storage. Saves are atomic (write temp file, then rename) and run on a background thread. First
 * access loads synchronously — the file is small so this is safe on the calling thread.
 *
 * <p>Grid dimensions are configurable: use {@link #columns()} / {@link #rows()} for all math.
 * {@link #DEFAULT_COLS} / {@link #DEFAULT_ROWS} are the factory defaults (4 × 5).
 * Call {@link #reloadGridSize(Context)} after a settings change; it also runs
 * {@link #repackOutOfBounds()} so stranded items are relocated automatically.
 */
public final class HomeScreenStore {

    /** Factory defaults — used by views before a store instance is available. */
    public static final int DEFAULT_COLS = 4;
    public static final int DEFAULT_ROWS = 5;

    /** Maximum number of home screens (mirrors HomescreenView.MAX_PAGES). */
    public static final int MAX_SCREENS = 9;

    /**
     * Widest / tallest grid the app can ever be configured for — {@link Settings#setGridCols} and
     * {@link Settings#setGridRows} clamp to these, so nothing legitimate ever exceeds them.
     * Used to bound geometry arriving from a file rather than from the UI.
     */
    private static final int MAX_COLS = 6;
    private static final int MAX_ROWS = 8;

    /**
     * Hard cap on items accepted from a backup file. A completely full install is
     * 9 screens × 6 × 8 cells plus a 6-slot dock = 438, so this leaves headroom while keeping a
     * hand-edited or hostile file from turning into unbounded work.
     */
    private static final int MAX_IMPORT_ITEMS = 512;

    /**
     * Schema version written into {@code homescreen.json}. Bump when the on-disk shape changes;
     * older builds read unknown entries through the preserve-verbatim path in {@link #toJsonString}
     * rather than silently dropping them.
     */
    private static final int FORMAT_VERSION = 1;

    private static final String TAG = "HomeScreenStore";

    private static final String FILE_NAME = "homescreen.json";
    private static final String FILE_TMP  = "homescreen.json.tmp";
    private static final String FILE_BAK  = "homescreen.json.bak";
    /** Prefix given to an unreadable layout file when it is renamed aside. */
    private static final String CORRUPT_SUFFIX = ".corrupt-";

    private static HomeScreenStore sInstance;

    /**
     * Forces the atomic rename in {@link #writeToFile} to fail so the recovery path can be
     * exercised. Unit tests only — never set in production code.
     */
    static volatile boolean sFailRenameForTesting = false;

    private final File file;
    private final File fileTmp;
    private final File fileBak;
    // Single-thread executor keeps saves ordered; daemon thread avoids blocking JVM shutdown.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "homescreen-save");
        t.setDaemon(true);
        return t;
    });

    private final List<HomeItem> items = new ArrayList<>();

    /**
     * Entries whose {@code type} this build does not recognise, kept exactly as they were read.
     * {@link #toJsonString} re-emits them so installing an older APK over a newer one does not
     * permanently erase items the newer one wrote.
     */
    private final List<JSONObject> unknownEntries = new ArrayList<>();

    // Instance grid dimensions — backed by Settings and refreshed via reloadGridSize().
    private int cols;
    private int rows;

    /** True when the layout was recovered from a fallback file and should be rewritten. */
    private boolean needsRewrite;
    /** True while an unreadable layout has not been acknowledged — suppresses all saves. */
    private boolean loadCorrupt;
    /** Name the unreadable layout was renamed to, or null. */
    private String corruptFileName;

    private HomeScreenStore(Context c) {
        File dir = c.getFilesDir();
        file    = new File(dir, FILE_NAME);
        fileTmp = new File(dir, FILE_TMP);
        fileBak = new File(dir, FILE_BAK);
        cols = Settings.getGridCols(c);
        rows = Settings.getGridRows(c);
        loadSync();
        // Clamp/relocate any items that are out of bounds for the saved grid size;
        // save the corrected layout only if something actually changed. Evaluate the repack
        // first so it always runs, then OR in whatever loadSync() already decided.
        boolean repacked = repackOutOfBounds();
        if (repacked || needsRewrite) scheduleSave();
    }

    /** Current grid width (columns). Always reflects the live Settings value after reloadGridSize(). */
    public synchronized int columns() { return cols; }

    /** Current grid height (rows). Always reflects the live Settings value after reloadGridSize(). */
    public synchronized int rows() { return rows; }

    /**
     * Re-reads {@link Settings#getGridCols} / {@link Settings#getGridRows} and runs
     * {@link #repackOutOfBounds()} to clamp widget spans and relocate stranded items.
     * Call this immediately after the user changes grid settings; then re-render HOME.
     */
    public synchronized void reloadGridSize(Context c) {
        cols = Settings.getGridCols(c);
        rows = Settings.getGridRows(c);
        if (repackOutOfBounds()) scheduleSave();
    }

    public static synchronized HomeScreenStore get(Context c) {
        if (sInstance == null) sInstance = new HomeScreenStore(c.getApplicationContext());
        return sInstance;
    }

    /** Clears the cached singleton. Call from {@code @Before} in unit tests. */
    static synchronized void resetForTesting() {
        sInstance = null;
    }

    // --- Load / Save ---

    /**
     * Populates the model from disk, preferring the live file and falling back to the leftovers of
     * an interrupted save. An <em>absent</em> file is a fresh install; a <em>present but
     * unreadable</em> one is a data-loss event, so its bytes are renamed aside and saving is
     * suppressed (see {@link #isLoadCorrupt()}) rather than quietly overwritten by the first
     * icon the user places.
     */
    private void loadSync() {
        // Blocking disk read on whichever thread first calls get() — in practice the main
        // thread, from MainActivity.onCreate. Traced so it shows up as its own slice instead of
        // hiding inside whatever triggered the first store access. Measured at ~0.3ms, i.e. not
        // the startup problem it looks like it might be.
        Perf.begin(Perf.STORE_LOAD);
        try {
            loadSyncInner();
        } finally {
            Perf.end();
        }
    }

    private void loadSyncInner() {
        if (file.exists() && file.length() > 0) {
            if (tryLoadFrom(file)) return;
            // Present but unreadable. Keep the bytes; they are the only copy of the layout.
            quarantine();
            if (tryLoadFrom(fileTmp) || tryLoadFrom(fileBak)) {
                needsRewrite = true;
                return;
            }
            loadCorrupt = true;
            return;
        }
        // Absent or zero-length. A leftover .tmp is a fully-written save whose rename never
        // landed, and .bak is the previous good layout — either beats starting empty.
        if (tryLoadFrom(fileTmp) || tryLoadFrom(fileBak)) needsRewrite = true;
    }

    /**
     * Parses one candidate file into {@link #items}. Returns false — leaving the model empty —
     * if the file is missing, empty or structurally unreadable, so the caller can try the next
     * candidate.
     */
    private boolean tryLoadFrom(File f) {
        if (!f.exists() || f.length() == 0) return false;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            JSONArray arr = root.getJSONArray("items");
            int dropped = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject entry = arr.optJSONObject(i);
                if (entry == null) { dropped++; continue; }
                try {
                    HomeItem item = HomeItem.fromJson(entry);
                    // Geometry from disk is not necessarily geometry this app produced: an
                    // out-of-range screen index drives screenCount(), which HomescreenView turns
                    // into one CellLayout allocation per screen.
                    if (isWithinLimits(item)) items.add(item); else dropped++;
                } catch (JSONException | RuntimeException e) {
                    if (isUnknownType(entry)) unknownEntries.add(entry); else dropped++;
                }
            }
            if (dropped > 0) Log.w(TAG, "dropped " + dropped + " unusable entries from " + f.getName());
            return true;
        } catch (Exception e) {
            items.clear();
            unknownEntries.clear();
            return false;
        }
    }

    /**
     * True if the entry names a type this build does not know, as opposed to being malformed.
     * Such entries are preserved verbatim; genuinely broken ones are not.
     */
    private static boolean isUnknownType(JSONObject entry) {
        String name = entry.optString("type", "");
        if (name.isEmpty()) return false;
        for (HomeItem.Type t : HomeItem.Type.values()) {
            if (t.name().equals(name)) return false;
        }
        return true;
    }

    /**
     * True if an item's geometry is inside the largest grid and screen count this build can
     * render. Out-of-range values are dropped rather than clamped: clamping a screen index of
     * 1000000 down to 8 just piles unrelated items on top of each other, and repackOutOfBounds()
     * only relocates items whose <em>cell</em> is out of bounds.
     */
    private static boolean isWithinLimits(HomeItem item) {
        if (item.screen < HomeItem.DOCK || item.screen >= MAX_SCREENS) return false;
        if (item.cellX < 0 || item.cellY < 0) return false;
        if (item.cellX >= MAX_COLS || item.cellY >= MAX_ROWS) return false;
        if (item.spanX < 1 || item.spanY < 1) return false;
        return item.spanX <= MAX_COLS && item.spanY <= MAX_ROWS;
    }

    /** Renames an unreadable layout file aside so no later save can overwrite it. */
    private void quarantine() {
        File dest = new File(file.getParentFile(), FILE_NAME + CORRUPT_SUFFIX + System.currentTimeMillis());
        if (file.renameTo(dest)) {
            corruptFileName = dest.getName();
            Log.e(TAG, "unreadable " + FILE_NAME + " kept as " + dest.getName());
        } else {
            Log.e(TAG, "unreadable " + FILE_NAME + " could not be renamed aside");
        }
    }

    /**
     * True when {@code homescreen.json} was present but could not be read. The original bytes are
     * kept under {@link #corruptFileName()} and every save is suppressed until
     * {@link #acknowledgeCorruptLoad()} runs, so the user's layout is not overwritten before they
     * have been told it could not be read. Self-limiting: the unreadable file has been renamed
     * aside, so the next process start takes the fresh-install path and saves normally.
     */
    public synchronized boolean isLoadCorrupt() { return loadCorrupt; }

    /**
     * Name (inside {@code getFilesDir()}) of the file the unreadable layout was kept as, or null
     * if there was none — or if even the rename failed.
     */
    public synchronized String corruptFileName() { return corruptFileName; }

    /**
     * Clears {@link #isLoadCorrupt()} and re-enables saving, persisting whatever the user has
     * built since. Call once they have actually been told the old layout could not be read.
     */
    public synchronized void acknowledgeCorruptLoad() {
        if (!loadCorrupt) return;
        loadCorrupt = false;
        scheduleSave();
    }

    /**
     * Must be called while holding {@code this} lock. Serialises the current item list to a JSON
     * string synchronously, then hands the string off to the background executor for file I/O.
     * This keeps file I/O off the lock so mutations never block on disk.
     */
    private void scheduleSave() {
        // Never write over a layout we failed to read — the quarantined copy is the user's only
        // one, and an empty model is not a layout worth persisting on top of it.
        if (loadCorrupt) return;
        final String json = toJsonString();
        executor.execute(() -> writeToFile(json));
    }

    private String toJsonString() {
        // Called while holding 'this' — safe to read mutable item fields
        try {
            JSONArray arr = new JSONArray();
            for (HomeItem item : items) arr.put(item.toJson());
            // Entries a newer build wrote ride along untouched instead of being erased by the
            // first full-file rewrite an older build performs.
            for (JSONObject raw : unknownEntries) arr.put(raw);
            return new JSONObject().put("version", FORMAT_VERSION).put("items", arr).toString();
        } catch (JSONException e) {
            return "{\"version\":" + FORMAT_VERSION + ",\"items\":[]}";
        }
    }

    private void writeToFile(String json) {
        try {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(fileTmp)) {
                fos.write(bytes);
                fos.getFD().sync();
            }
            // Keep the layout we are about to replace as .bak: one small copy buys loadSync() a
            // second source when the live file turns out to be unreadable. Copy rather than
            // rename so the live file is never momentarily absent.
            if (file.exists()) {
                try {
                    Files.copy(file.toPath(), fileBak.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    Log.w(TAG, "could not refresh " + FILE_BAK, e);
                }
            }
            if (sFailRenameForTesting || !fileTmp.renameTo(file)) {
                // Do NOT reopen the live file and write it directly: that truncates it to zero
                // before writing, and whatever failed the rename (ENOSPC, IO error) can fail the
                // write too, leaving the user with a zero-byte layout. The fully-written temp
                // file stays on disk, loadSync() prefers it over an unusable live file, and the
                // next mutation retries the rename.
                Log.w(TAG, "atomic rename failed; keeping " + FILE_TMP + " for recovery");
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to persist home-screen layout", e);
        }
    }

    /**
     * Blocks until all pending saves have been written to disk. Used by unit tests and by
     * {@link DefaultHomeSeeder}, which must not mark the home screen seeded until it is.
     */
    void flush() {
        try {
            executor.submit(() -> {}).get();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Query ---

    /** Returns a snapshot of all items. */
    public synchronized List<HomeItem> getItems() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    /**
     * Every app key the home screen and dock actually reference, folder members included.
     *
     * <p>This is the set needed to draw a complete home screen — typically a dozen keys, against
     * the ~166 launchable activities on a real device. {@link AppLoader} loads these first and
     * renders without waiting for the rest, which is the difference between the launcher being
     * usable in ~0.2s and in ~1.5s on a cold start.
     *
     * <p>Keys are in the same space as {@link AppInfo#getPackageName()} — bare package name for
     * this user's own profile, {@code pkg#serial} for others (see {@link UserKeys}) — so the
     * result matches loaded apps directly with no normalisation.
     *
     * <p>WIDGET items contribute nothing: they are hosted views, not app icons.
     */
    public synchronized Set<String> referencedAppKeys() {
        Set<String> keys = new HashSet<>();
        for (HomeItem item : items) {
            switch (item.getType()) {
                case APP:
                    if (item.getPackageName() != null) keys.add(item.getPackageName());
                    break;
                case FOLDER:
                    if (item.getPackageNames() != null) keys.addAll(item.getPackageNames());
                    break;
                default:
                    break; // WIDGET — no icon needed
            }
        }
        return keys;
    }

    /**
     * Returns a snapshot of all dock items (screen == {@link HomeItem#DOCK}).
     * Ordered by insertion order; slot index is {@link HomeItem#getCellX()}.
     */
    public synchronized List<HomeItem> dockItems() {
        List<HomeItem> result = new ArrayList<>();
        for (HomeItem item : items) {
            if (item.screen == HomeItem.DOCK) result.add(item);
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * True if dock slot {@code slot} (0-based, range 0..{@link #columns()}-1) is unoccupied.
     * Uses the same coordinate space as {@link #isAreaFree} with screen={@link HomeItem#DOCK}.
     */
    public synchronized boolean isDockSlotFree(int slot) {
        return isAreaFree(HomeItem.DOCK, slot, 0, 1, 1);
    }

    /**
     * Returns the index of the first free dock slot (0-based), or -1 when all
     * {@link #columns()} slots are occupied.
     */
    public synchronized int firstFreeDockSlot() {
        for (int slot = 0; slot < cols; slot++) {
            if (isDockSlotFree(slot)) return slot;
        }
        return -1;
    }

    /** Returns the item whose bounding rectangle contains (screen, x, y), or null. */
    public synchronized HomeItem itemAt(int screen, int x, int y) {
        for (HomeItem item : items) {
            if (item.screen == screen
                    && x >= item.cellX && x < item.cellX + item.spanX
                    && y >= item.cellY && y < item.cellY + item.spanY) {
                return item;
            }
        }
        return null;
    }

    /**
     * True if the spanX×spanY rectangle starting at (x, y) on the given screen is entirely
     * within the grid bounds and not overlapped by any existing item.
     */
    public synchronized boolean isAreaFree(int screen, int x, int y, int spanX, int spanY) {
        if (x < 0 || y < 0 || x + spanX > cols || y + spanY > rows) return false;
        for (HomeItem item : items) {
            if (item.screen != screen) continue;
            if (x < item.cellX + item.spanX && x + spanX > item.cellX
                    && y < item.cellY + item.spanY && y + spanY > item.cellY) {
                return false;
            }
        }
        return true;
    }

    /**
     * Like {@link #isAreaFree} but ignores {@code exclude} — used during drag-and-drop so
     * the dragged item's original footprint does not block its own new position.
     */
    public synchronized boolean isAreaFreeExcluding(int screen, int x, int y,
                                                      int spanX, int spanY, HomeItem exclude) {
        if (x < 0 || y < 0 || x + spanX > cols || y + spanY > rows) return false;
        for (HomeItem item : items) {
            if (item == exclude) continue;
            if (item.screen != screen) continue;
            if (x < item.cellX + item.spanX && x + spanX > item.cellX
                    && y < item.cellY + item.spanY && y + spanY > item.cellY) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {x, y} of the first cell on the given screen where a spanX×spanY item fits,
     * scanning left-to-right then top-to-bottom. Returns null if no space is available.
     */
    public synchronized int[] firstFreeCell(int screen, int spanX, int spanY) {
        for (int y = 0; y <= rows - spanY; y++) {
            for (int x = 0; x <= cols - spanX; x++) {
                if (isAreaFree(screen, x, y, spanX, spanY)) return new int[]{x, y};
            }
        }
        return null;
    }

    /**
     * The first row of {@code screen} whose whole width is free for {@code spanY} rows, or -1.
     *
     * <p>The "Tap to add Google search" pill draws across a whole row and the widget it adds
     * fills that row, so the two must be given the same answer — a pill on row 2 and a widget
     * widened only when row 0 is free is a pill that promises something it does not do. It is
     * also why the pill scans instead of insisting on row 0: it is a one-shot offer, and keying
     * it on "row 0 free" made it disappear the moment anything landed there and come back if that
     * cell was later emptied (QA R5).
     */
    public synchronized int firstFreeFullRow(int screen, int spanY) {
        for (int y = 0; y <= rows - spanY; y++) {
            if (isAreaFree(screen, 0, y, cols, spanY)) return y;
        }
        return -1;
    }

    /**
     * Like {@link #firstFreeCell} but ignores {@code exclude} — used during repack so a stranded
     * item's own stale footprint does not block its candidate relocation cells.
     * Must only be called while holding {@code this} lock.
     */
    private int[] firstFreeCellExcluding(int screen, int spanX, int spanY, HomeItem exclude) {
        for (int y = 0; y <= rows - spanY; y++) {
            for (int x = 0; x <= cols - spanX; x++) {
                if (isAreaFreeExcluding(screen, x, y, spanX, spanY, exclude))
                    return new int[]{x, y};
            }
        }
        return null;
    }

    /**
     * Clamps widget spans to the current grid and relocates any grid item whose bounding rect no
     * longer fits within {@link #cols} × {@link #rows}. Items are relocated to the first free
     * cell starting from their current screen; screens up to {@link #MAX_SCREENS} are tried.
     *
     * <p>Dock items (screen == {@link HomeItem#DOCK}) are excluded from the grid repack; a
     * separate dock pass handles slots that became out-of-range when the grid shrank.
     *
     * @return true if any item was mutated (triggering a save by the caller)
     */
    private boolean repackOutOfBounds() {
        boolean changed = false;

        // Step 1: clamp oversized widget spans (dock items are never WIDGET, but guard anyway).
        for (HomeItem item : items) {
            if (item.screen == HomeItem.DOCK) continue;
            if (item.type == HomeItem.Type.WIDGET) {
                if (item.spanX > cols) { item.spanX = cols; changed = true; }
                if (item.spanY > rows) { item.spanY = rows; changed = true; }
            }
        }

        // Step 2: collect grid items (screen >= 0) whose position is out of bounds.
        List<HomeItem> stranded = new ArrayList<>();
        for (HomeItem item : items) {
            if (item.screen == HomeItem.DOCK) continue; // dock handled separately
            if (item.cellX < 0 || item.cellY < 0
                    || item.cellX + item.spanX > cols || item.cellY + item.spanY > rows) {
                stranded.add(item);
            }
        }

        // Step 3: relocate each stranded grid item — start from its own screen, then later ones.
        // Use firstFreeCellExcluding so the item's own stale footprint is not counted as
        // occupied; without this a spanning widget at e.g. (1,0) blocks (0,0) and lands
        // suboptimally at (0,1) instead.
        for (HomeItem item : stranded) {
            int startScreen = Math.max(0, item.screen);
            for (int s = startScreen; s < MAX_SCREENS; s++) {
                int[] cell = firstFreeCellExcluding(s, item.spanX, item.spanY, item);
                if (cell != null) {
                    item.screen = s;
                    item.cellX  = cell[0];
                    item.cellY  = cell[1];
                    changed = true;
                    break;
                }
            }
            // If no free cell found across all screens, the item stays stranded;
            // it will not render but won't crash.
        }

        // Step 4: handle dock items whose slot index fell out of range (grid shrink).
        // Dock slot count == cols; if a dock item's cellX >= cols it must move.
        for (HomeItem item : items) {
            if (item.screen != HomeItem.DOCK) continue;
            if (item.cellX < cols) continue; // still within range
            // Try the first free dock slot.
            boolean placed = false;
            for (int slot = 0; slot < cols; slot++) {
                if (isDockSlotFree(slot)) {
                    item.cellX = slot;
                    changed = true;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                // Dock is full — promote the item to the grid (screen 0 onward).
                for (int s = 0; s < MAX_SCREENS; s++) {
                    int[] cell = firstFreeCell(s, 1, 1);
                    if (cell != null) {
                        item.screen = s;
                        item.cellX  = cell[0];
                        item.cellY  = cell[1];
                        changed = true;
                        break;
                    }
                }
            }
        }

        return changed;
    }

    /**
     * Number of screens in use: max(item.screen)+1, always at least 1.
     * Trailing empty screens are implicitly pruned because screen count is item-derived.
     */
    public synchronized int screenCount() {
        int max = -1;
        for (HomeItem item : items) if (item.screen > max) max = item.screen;
        return Math.max(max + 1, 1);
    }

    // --- Mutation (each method saves after changing state) ---

    public synchronized void addItem(HomeItem item) {
        items.add(item);
        scheduleSave();
    }

    public synchronized void removeItem(HomeItem item) {
        items.remove(item);
        scheduleSave();
    }

    /** Changes (screen, cellX, cellY) of an existing item; span is unchanged. */
    public synchronized void moveItem(HomeItem item, int toScreen, int toCellX, int toCellY) {
        item.screen = toScreen;
        item.cellX  = toCellX;
        item.cellY  = toCellY;
        scheduleSave();
    }

    /** Updates the span of an existing widget item and schedules a save. */
    public synchronized void setSpan(HomeItem item, int spanX, int spanY) {
        item.spanX = spanX;
        item.spanY = spanY;
        scheduleSave();
    }

    /** Supplies the span a provider should have been placed at, or null if it can't be resolved. */
    public interface WidgetSpanResolver {
        int[] correctSpan(String provider);
    }

    /**
     * Shrinks widgets that were placed oversized back to the span their provider actually asks
     * for. Returns whether anything changed.
     *
     * <p>Widget placement used to scale {@code AppWidgetProviderInfo}'s already-in-pixels minimum
     * size by display density a second time, so widgets landed several cells too large — on a
     * 560dpi phone the Google search bar was written to disk as 4x2 when its minimum only asks
     * for 4x1. Fixing the placement code cannot fix layouts already saved, which is what this is
     * for.
     *
     * <p><b>Only ever shrinks.</b> That makes it collision-free by construction: a smaller
     * footprint inside an existing one cannot overlap a neighbour. An unresolvable provider is
     * skipped rather than guessed at.
     *
     * <p>Must run <b>once</b>, not on every launch. Now that resize works, a deliberately
     * enlarged widget is indistinguishable from a mis-placed one, so re-running would keep
     * undoing the user's own resizes — see {@code Settings.getWidgetSpansCorrected}.
     */
    public synchronized boolean shrinkOversizedWidgets(WidgetSpanResolver resolver) {
        if (resolver == null) return false;
        boolean changed = false;
        for (HomeItem item : items) {
            if (item.type != HomeItem.Type.WIDGET || item.provider == null) continue;
            int[] span = resolver.correctSpan(item.provider);
            if (span == null || span.length < 2) continue;
            int newX = Math.min(item.spanX, Math.max(1, span[0]));
            int newY = Math.min(item.spanY, Math.max(1, span[1]));
            if (newX != item.spanX || newY != item.spanY) {
                item.spanX = newX;
                item.spanY = newY;
                changed = true;
            }
        }
        if (changed) scheduleSave();
        return changed;
    }

    /** Renames a FOLDER item and persists the change. */
    public synchronized void renameFolder(HomeItem folder, String newName) {
        folder.folderName = newName;
        scheduleSave();
    }

    /** Appends {@code pkg} to a FOLDER item's member list and persists the change. No-op if already a member. */
    public synchronized void addToFolder(HomeItem folder, String pkg) {
        if (folder.type != HomeItem.Type.FOLDER) return;
        if (folder.packageNames.contains(pkg)) return; // duplicate guard
        folder.packageNames.add(pkg);
        scheduleSave();
    }

    /**
     * Moves the member at index {@code from} to index {@code to} within a FOLDER's ordered
     * member list and persists the change. Out-of-range indices and no-op moves are ignored,
     * so callers can hand over raw hit-test results without pre-validating them.
     *
     * @return true if the order actually changed
     */
    public synchronized boolean reorderFolder(HomeItem folder, int from, int to) {
        if (folder.type != HomeItem.Type.FOLDER) return false;
        int n = folder.packageNames.size();
        if (from < 0 || from >= n || to < 0 || to >= n || from == to) return false;
        folder.packageNames.add(to, folder.packageNames.remove(from));
        scheduleSave();
        return true;
    }

    /**
     * Removes {@code pkg} from a FOLDER item. Cascade rules match {@link #removePackage}:
     * <ul>
     *   <li>0 remaining members → folder removed.</li>
     *   <li>1 remaining member → folder collapses to plain APP at folder's cell.</li>
     *   <li>2+ remaining members → folder survives.</li>
     * </ul>
     */
    public synchronized void removeFromFolder(HomeItem folder, String pkg) {
        if (folder.type != HomeItem.Type.FOLDER) return;
        folder.packageNames.remove(pkg);
        int remaining = folder.packageNames.size();
        if (remaining == 0) {
            items.remove(folder);
        } else if (remaining == 1) {
            items.remove(folder);
            items.add(HomeItem.app(folder.screen, folder.cellX, folder.cellY,
                    folder.packageNames.get(0)));
        }
        // remaining >= 2: folder survives, already mutated above
        scheduleSave();
    }

    // -------------------------------------------------------------------------
    // Phase E — Layout backup / export / import
    // -------------------------------------------------------------------------

    /** Signals that the backup file uses a format version this code doesn't understand. */
    public static final class BackupVersionException extends RuntimeException {
        /** The unsupported version number read from the backup file. */
        public final int version;
        public BackupVersionException(int v) {
            super("unknown major version: " + v);
            this.version = v;
        }
    }

    /** Parsed result of {@link #fromBackupJson}. */
    public static final class ParsedBackup {
        /** Home items to restore (WIDGET entries already removed). */
        public final List<HomeItem> items;
        /** Grid columns from the backup. */
        public final int gridCols;
        /** Grid rows from the backup. */
        public final int gridRows;
        /** Number of WIDGET items that were skipped during import (appWidgetIds are host-local). */
        public final int widgetSkipCount;

        ParsedBackup(List<HomeItem> items, int gridCols, int gridRows, int widgetSkipCount) {
            this.items = items;
            this.gridCols = gridCols;
            this.gridRows = gridRows;
            this.widgetSkipCount = widgetSkipCount;
        }
    }

    /**
     * Serialises the current store state plus grid settings into a portable backup JSON object
     * (format version 1). Write this to a user-chosen file via SAF.
     *
     * <p>Format:
     * <pre>
     * {
     *   "version": 1,
     *   "gridCols": 4,
     *   "gridRows": 5,
     *   "items": [ { ...HomeItem JSON... }, ... ]
     * }
     * </pre>
     *
     * <p>A personality's own per-app data (a colour table, a category map) is deliberately not in
     * here. It is derived from the installed apps, so it regenerates on the restoring device —
     * and shipping it would make the backup format a personality's business rather than the
     * launcher's.
     *
     * <p>WIDGET items are included here; they are skipped on import because appWidgetIds are
     * host-local and cannot be re-bound from a backup.
     */
    public synchronized JSONObject toBackupJson(Context c) throws JSONException {
        JSONArray arr = new JSONArray();
        for (HomeItem item : items) arr.put(item.toJson());

        return new JSONObject()
                .put("version", 1)
                .put("gridCols", cols)
                .put("gridRows", rows)
                .put("items", arr);
    }

    /**
     * Parses and validates a backup JSON object. WIDGET items are filtered out (host-local IDs
     * cannot be re-bound); their count is reported in {@link ParsedBackup#widgetSkipCount}.
     * Corrupt or unknown-type item entries are silently skipped (same policy as {@link #loadSync}).
     *
     * <p>Geometry is bounded here, not later: the result goes straight into {@link #replaceAll},
     * so an item claiming {@code "screen": 1000000} would be persisted and then make
     * {@code screenCount()} report a million screens on every launch. Items outside
     * {@link #isWithinLimits} are dropped and the list is capped at {@link #MAX_IMPORT_ITEMS}.
     *
     * @throws JSONException          if the JSON is structurally invalid (e.g. missing "items")
     * @throws BackupVersionException if the major version field is not 1
     */
    public static ParsedBackup fromBackupJson(JSONObject root) throws JSONException {
        int version = root.getInt("version");
        if (version != 1) throw new BackupVersionException(version);

        int gridCols = root.has("gridCols") ? root.getInt("gridCols") : DEFAULT_COLS;
        int gridRows = root.has("gridRows") ? root.getInt("gridRows") : DEFAULT_ROWS;
        JSONArray arr = root.getJSONArray("items"); // throws JSONException if absent
        List<HomeItem> resultItems = new ArrayList<>();
        int widgetSkipCount = 0;
        int dropped = 0;
        for (int i = 0; i < arr.length() && resultItems.size() < MAX_IMPORT_ITEMS; i++) {
            try {
                HomeItem item = HomeItem.fromJson(arr.getJSONObject(i));
                if (item.getType() == HomeItem.Type.WIDGET) {
                    widgetSkipCount++;
                } else if (isWithinLimits(item)) {
                    resultItems.add(item);
                } else {
                    dropped++;
                }
            } catch (JSONException | RuntimeException e) {
                // skip corrupt or unknown-type entries; keep processing the rest
            }
        }
        if (dropped > 0) Log.w(TAG, "backup: dropped " + dropped + " out-of-range items");

        return new ParsedBackup(resultItems, gridCols, gridRows, widgetSkipCount);
    }

    /**
     * Atomically replaces all home-screen items with {@code newItems} and schedules a save.
     * Grid dimensions are unchanged — call {@link #reloadGridSize} before or after if needed.
     */
    public synchronized void replaceAll(List<HomeItem> newItems) {
        items.clear();
        // A restore replaces the whole layout, including entries this build could not interpret.
        unknownEntries.clear();
        items.addAll(newItems);
        scheduleSave();
    }

    /**
     * Removes every reference to the key {@code key} from the home screen. Called from
     * MainActivity's package receiver when an app is uninstalled, with the bare package name.
     *
     * <p>The match is on the whole key, not on {@link UserKeys#packageOf}. Package broadcasts
     * are delivered per user, so the only {@code ACTION_PACKAGE_REMOVED} this launcher can
     * receive is the personal profile's — and a personal uninstall says nothing about the work
     * copy of the same package, which is keyed {@code "pkg#serial"} and is still installed.
     * Matching on the package name deleted work Chrome's icon whenever personal Chrome was
     * uninstalled. Work-profile entries that really have gone away are pruned by
     * {@link #removeUnknownApps(java.util.Set, java.util.Set)} on the next full app load, which
     * every package broadcast schedules; that path knows which profiles it enumerated, which
     * this one cannot.
     * <ul>
     *   <li>APP items whose key equals {@code key} are removed.</li>
     *   <li>FOLDER items: {@code key} is removed from the member list.
     *       <ul>
     *         <li>0 remaining members → folder is removed.</li>
     *         <li>1 remaining member → folder collapses to a plain APP at the folder's cell.</li>
     *         <li>2+ remaining members → folder survives (mutated in-place).</li>
     *       </ul>
     *   </li>
     * </ul>
     */
    public synchronized void removePackage(String key) {
        List<HomeItem> toAdd = new ArrayList<>();
        Iterator<HomeItem> it = items.iterator();
        while (it.hasNext()) {
            HomeItem item = it.next();
            if (item.type == HomeItem.Type.APP && key.equals(item.packageName)) {
                it.remove();
            } else if (item.type == HomeItem.Type.FOLDER) {
                // Every occurrence of exactly this key (legacy folders may hold duplicates).
                item.packageNames.removeIf(key::equals);
                int remaining = item.packageNames.size();
                if (remaining == 0) {
                    it.remove();
                } else if (remaining == 1) {
                    it.remove();
                    toAdd.add(HomeItem.app(item.screen, item.cellX, item.cellY,
                            item.packageNames.get(0)));
                }
                // remaining >= 2: folder survives, already mutated above
            }
        }
        items.addAll(toAdd);
        scheduleSave();
    }

    /**
     * Removes APP items and folder members whose {@link UserKeys#serialOf composite serial} is
     * non-zero and not present in {@code validSerials}. Call this when a managed profile is deleted
     * to prevent ghost items permanently blocking home-screen cells.
     *
     * <p>Cascade rules mirror {@link #removePackage}: 0 remaining folder members → folder removed;
     * 1 remaining member → folder collapses to a plain APP at the folder's cell. Personal items
     * (bare keys, serial == 0) are never touched.
     *
     * @param validSerials serial numbers currently reported by {@code UserManager.getUserProfiles()}
     */
    public synchronized void removeUnknownUserSerials(java.util.Set<Long> validSerials) {
        List<HomeItem> toAdd = new ArrayList<>();
        Iterator<HomeItem> it = items.iterator();
        while (it.hasNext()) {
            HomeItem item = it.next();
            if (item.type == HomeItem.Type.APP) {
                long serial = UserKeys.serialOf(item.packageName);
                if (serial != 0 && !validSerials.contains(serial)) {
                    it.remove();
                }
            } else if (item.type == HomeItem.Type.FOLDER) {
                item.packageNames.removeIf(k -> {
                    long s = UserKeys.serialOf(k);
                    return s != 0 && !validSerials.contains(s);
                });
                int remaining = item.packageNames.size();
                if (remaining == 0) {
                    it.remove();
                } else if (remaining == 1) {
                    it.remove();
                    toAdd.add(HomeItem.app(item.screen, item.cellX, item.cellY,
                            item.packageNames.get(0)));
                }
            }
        }
        items.addAll(toAdd);
        scheduleSave();
    }

    /**
     * Removes APP items and folder members whose key is not in {@code knownAppKeys} — apps that
     * were uninstalled while nothing was alive to receive the package broadcast. Without this the
     * item renders nothing but still occupies its cell forever: {@link #isAreaFree} counts it, so
     * the cell can never be reused, and {@link #itemAt} returns it, so a drop there builds a
     * folder around a package that no longer exists.
     *
     * <p>A key is only pruned when its owning profile is in {@code enumeratedSerials} — the
     * profiles this load actually and fully enumerated, <em>not</em> the profiles
     * {@code UserManager.getUserProfiles()} happens to list. A paused, quiet, locked or
     * policy-denied work profile is still listed there while enumerating no apps at all, and
     * treating that as evidence of an uninstall deleted every work icon and folder member on the
     * home screen. An empty {@code knownAppKeys} is treated as a failed enumeration and does
     * nothing at all.
     *
     * <p>Cascade rules mirror {@link #removePackage}: 0 remaining folder members → folder removed;
     * 1 remaining member → folder collapses to a plain APP at the folder's cell.
     *
     * @param knownAppKeys composite keys ({@link UserKeys#compose}) of every app currently installed
     * @param enumeratedSerials serials of the profiles whose enumeration succeeded; 0 stands for
     *                          the user this launcher runs as, whose apps carry bare keys
     * @return true if anything was pruned
     */
    public synchronized boolean removeUnknownApps(java.util.Set<String> knownAppKeys,
                                                  java.util.Set<Long> enumeratedSerials) {
        if (knownAppKeys == null || knownAppKeys.isEmpty()) return false;
        boolean changed = false;
        List<HomeItem> toAdd = new ArrayList<>();
        Iterator<HomeItem> it = items.iterator();
        while (it.hasNext()) {
            HomeItem item = it.next();
            if (item.type == HomeItem.Type.APP) {
                if (isGhostKey(item.packageName, knownAppKeys, enumeratedSerials)) {
                    it.remove();
                    changed = true;
                }
            } else if (item.type == HomeItem.Type.FOLDER) {
                if (!item.packageNames.removeIf(
                        k -> isGhostKey(k, knownAppKeys, enumeratedSerials))) {
                    continue;
                }
                changed = true;
                int remaining = item.packageNames.size();
                if (remaining == 0) {
                    it.remove();
                } else if (remaining == 1) {
                    it.remove();
                    toAdd.add(HomeItem.app(item.screen, item.cellX, item.cellY,
                            item.packageNames.get(0)));
                }
            }
        }
        items.addAll(toAdd);
        if (changed) scheduleSave();
        return changed;
    }

    /**
     * Context overload of {@link #removeUnknownApps(java.util.Set, java.util.Set)} that derives
     * both sets itself, so the caller needs only the app list it has just loaded.
     *
     * <p>The enumerated serials come from the loaded apps themselves rather than from
     * {@code UserManager.getUserProfiles()}: a profile that is listed but contributed no apps
     * proves nothing — it is exactly what a paused or locked work profile looks like — whereas a
     * profile that contributed at least one app definitely enumerated. The cost is that the last
     * remaining app of a profile can never be pruned; that leaves a ghost cell, which is the
     * pre-existing behaviour and far cheaper than deleting a layout there is no undo for.
     */
    public boolean removeUnknownApps(Context c, List<AppInfo> knownApps) {
        if (knownApps == null || knownApps.isEmpty()) return false;
        java.util.Set<String> keys = new java.util.HashSet<>();
        java.util.Set<Long> enumeratedSerials = new java.util.HashSet<>();
        for (AppInfo a : knownApps) {
            keys.add(a.getPackageName());
            enumeratedSerials.add(UserKeys.serialOf(a.getPackageName()));
        }
        return removeUnknownApps(keys, enumeratedSerials);
    }

    /**
     * Rewrites keys of the form {@code "pkg#ourSerial"} back to the bare {@code "pkg"} the current
     * build writes for our own user, and returns true if anything changed.
     *
     * <p>Builds before M12 derived "personal" from {@code getUserProfiles().get(0)}, so on a
     * secondary or guest user (serial != 0) every key a drag wrote — for that user's <em>own</em>
     * apps — was composite. Those items no longer resolve to anything after the upgrade, so the
     * layout renders blank and the cells cannot be reclaimed.
     *
     * <p>Safe on a genuine work-profile key: a work profile's serial is by definition not our own,
     * so {@code "pkg#ourSerial"} is never one. Idempotent — a second pass finds nothing to rewrite
     * — and a no-op for the primary user, whose keys were always bare.
     *
     * <p>The one thing it cannot tell apart is a backup exported on another user and imported
     * here, whose work-profile serial happens to equal our own user's. That costs a badge on one
     * icon; leaving a secondary user's whole layout unresolvable costs the layout.
     */
    public synchronized boolean migrateOwnUserKeys(long ourSerial) {
        if (ourSerial == 0) return false;
        boolean changed = false;
        for (int i = 0; i < items.size(); i++) {
            HomeItem item = items.get(i);
            if (item.type == HomeItem.Type.APP) {
                if (item.packageName != null && UserKeys.serialOf(item.packageName) == ourSerial) {
                    items.set(i, HomeItem.app(item.screen, item.cellX, item.cellY,
                            UserKeys.packageOf(item.packageName)));
                    changed = true;
                }
            } else if (item.type == HomeItem.Type.FOLDER) {
                for (int m = 0; m < item.packageNames.size(); m++) {
                    String key = item.packageNames.get(m);
                    if (UserKeys.serialOf(key) != ourSerial) continue;
                    String bare = UserKeys.packageOf(key);
                    // The seeded dock used bare names while drags used the composite form, so the
                    // two spellings of one app can meet here; keeping both would draw it twice.
                    if (item.packageNames.contains(bare)) {
                        item.packageNames.remove(m--);
                    } else {
                        item.packageNames.set(m, bare);
                    }
                    changed = true;
                }
            }
        }
        if (changed) scheduleSave();
        return changed;
    }

    /**
     * Context overload of {@link #migrateOwnUserKeys(long)} that looks up the serial of the user
     * this launcher instance runs as. A missing or throwing {@code UserManager} means no
     * migration rather than a guess.
     */
    public boolean migrateOwnUserKeys(Context c) {
        try {
            UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
            if (um == null) return false;
            return migrateOwnUserKeys(um.getSerialNumberForUser(Process.myUserHandle()));
        } catch (RuntimeException e) {
            Log.w(TAG, "could not resolve our own user serial; skipping key migration", e);
            return false;
        }
    }

    /**
     * True if {@code key} names an app that is gone <em>and</em> whose profile enumerated, so the
     * absence is real. Serial 0 is not special-cased: a bare key belongs to whichever user we run
     * as, and if that user is the one that failed to enumerate, its items are no more proven gone
     * than a work profile's.
     */
    private static boolean isGhostKey(String key, java.util.Set<String> knownAppKeys,
                                      java.util.Set<Long> enumeratedSerials) {
        if (knownAppKeys.contains(key)) return false;
        return enumeratedSerials.contains(UserKeys.serialOf(key));
    }
}
