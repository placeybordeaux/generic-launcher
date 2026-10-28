package com.genericlauncher.launcher;

import android.content.Context;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowUserManager;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Unit tests for HomeItem JSON serialization and HomeScreenStore logic.
 * Robolectric provides a real file system (in-memory) and Context.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class HomeScreenStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        HomeScreenStore.resetForTesting();
        HomeScreenStore.sFailRenameForTesting = false;
        // Clean any leftover files from a previous test
        File[] leftovers = ctx.getFilesDir().listFiles();
        if (leftovers != null) {
            for (File f : leftovers) {
                if (f.getName().startsWith("homescreen.json")) f.delete();
            }
        }
    }

    @After
    public void tearDown() {
        HomeScreenStore.sFailRenameForTesting = false;
    }

    // -------------------------------------------------------------------------
    // JSON round-trip — HomeItem
    // -------------------------------------------------------------------------

    @Test
    public void jsonRoundTrip_app() throws Exception {
        HomeItem original = HomeItem.app(1, 2, 3, "com.example.app");
        HomeItem restored = HomeItem.fromJson(original.toJson());

        assertEquals(HomeItem.Type.APP, restored.getType());
        assertEquals(1, restored.getScreen());
        assertEquals(2, restored.getCellX());
        assertEquals(3, restored.getCellY());
        assertEquals(1, restored.getSpanX());
        assertEquals(1, restored.getSpanY());
        assertEquals("com.example.app", restored.getPackageName());
    }

    @Test
    public void jsonRoundTrip_folder() throws Exception {
        List<String> pkgs = Arrays.asList("com.a", "com.b", "com.c");
        HomeItem original = HomeItem.folder(0, 0, 0, "Work", pkgs);
        HomeItem restored = HomeItem.fromJson(original.toJson());

        assertEquals(HomeItem.Type.FOLDER, restored.getType());
        assertEquals("Work", restored.getFolderName());
        assertEquals(pkgs, restored.getPackageNames());
        assertEquals(1, restored.getSpanX());
        assertEquals(1, restored.getSpanY());
    }

    @Test
    public void jsonRoundTrip_widget() throws Exception {
        HomeItem original = HomeItem.widget(2, 1, 0, 2, 2, 42, "com.example/com.example.Widget");
        HomeItem restored = HomeItem.fromJson(original.toJson());

        assertEquals(HomeItem.Type.WIDGET, restored.getType());
        assertEquals(2, restored.getScreen());
        assertEquals(1, restored.getCellX());
        assertEquals(0, restored.getCellY());
        assertEquals(2, restored.getSpanX());
        assertEquals(2, restored.getSpanY());
        assertEquals(42, restored.getAppWidgetId());
        assertEquals("com.example/com.example.Widget", restored.getProvider());
    }

    // -------------------------------------------------------------------------
    // Store round-trip (save → reload)
    // -------------------------------------------------------------------------

    @Test
    public void storeRoundTrip_allTypes() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.app"));
        store.addItem(HomeItem.folder(0, 1, 0, "Games", Arrays.asList("com.g1", "com.g2")));
        store.addItem(HomeItem.widget(0, 2, 0, 2, 1, 7, "com.w/com.w.W"));
        store.flush();

        HomeScreenStore.resetForTesting();
        HomeScreenStore reloaded = HomeScreenStore.get(ctx);
        List<HomeItem> items = reloaded.getItems();

        assertEquals(3, items.size());

        HomeItem app = itemOfType(items, HomeItem.Type.APP);
        assertEquals("com.app", app.getPackageName());

        HomeItem folder = itemOfType(items, HomeItem.Type.FOLDER);
        assertEquals("Games", folder.getFolderName());
        assertEquals(Arrays.asList("com.g1", "com.g2"), folder.getPackageNames());

        HomeItem widget = itemOfType(items, HomeItem.Type.WIDGET);
        assertEquals(7, widget.getAppWidgetId());
        assertEquals(2, widget.getSpanX());
    }

    // -------------------------------------------------------------------------
    // isAreaFree
    // -------------------------------------------------------------------------

    @Test
    public void isAreaFree_emptyGrid() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertTrue(store.isAreaFree(0, 0, 0, 1, 1));
        assertTrue(store.isAreaFree(0, 3, 4, 1, 1));
        assertTrue(store.isAreaFree(0, 0, 0, store.columns(), store.rows()));
    }

    @Test
    public void isAreaFree_singleOccupied() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 1, 1, "com.x"));

        assertFalse(store.isAreaFree(0, 1, 1, 1, 1));
        assertTrue(store.isAreaFree(0, 0, 1, 1, 1));
        assertTrue(store.isAreaFree(0, 2, 1, 1, 1));
        assertTrue(store.isAreaFree(1, 1, 1, 1, 1)); // different screen
    }

    @Test
    public void isAreaFree_multiSpanWidget() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.widget(0, 1, 1, 2, 2, 1, "p/c")); // occupies (1,1),(2,1),(1,2),(2,2)

        assertFalse(store.isAreaFree(0, 1, 1, 1, 1));
        assertFalse(store.isAreaFree(0, 2, 1, 1, 1));
        assertFalse(store.isAreaFree(0, 1, 2, 1, 1));
        assertFalse(store.isAreaFree(0, 2, 2, 1, 1));
        assertTrue(store.isAreaFree(0, 0, 0, 1, 1));
        assertTrue(store.isAreaFree(0, 3, 3, 1, 1));
    }

    @Test
    public void isAreaFree_outOfBounds() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Right edge overflow
        assertFalse(store.isAreaFree(0, 3, 0, 2, 1));
        // Bottom edge overflow
        assertFalse(store.isAreaFree(0, 0, 4, 1, 2));
        // Negative coordinates
        assertFalse(store.isAreaFree(0, -1, 0, 1, 1));
    }

    @Test
    public void isAreaFree_cornerOverlapWithWidget() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.widget(0, 2, 2, 2, 2, 1, "p/c")); // (2,2)-(3,3)

        // A 2x2 at (1,1) overlaps corner (2,2)
        assertFalse(store.isAreaFree(0, 1, 1, 2, 2));
        // A 1x1 at (1,1) does not overlap
        assertTrue(store.isAreaFree(0, 1, 1, 1, 1));
    }

    // -------------------------------------------------------------------------
    // firstFreeCell
    // -------------------------------------------------------------------------

    @Test
    public void firstFreeCell_emptyGrid() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        int[] cell = store.firstFreeCell(0, 1, 1);
        assertArrayEquals(new int[]{0, 0}, cell);
    }

    @Test
    public void firstFreeCell_firstCellOccupied() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.x"));
        int[] cell = store.firstFreeCell(0, 1, 1);
        assertArrayEquals(new int[]{1, 0}, cell);
    }

    @Test
    public void firstFreeCell_firstRowFull_wrapsToNextRow() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        for (int x = 0; x < store.columns(); x++) {
            store.addItem(HomeItem.app(0, x, 0, "com.r" + x));
        }
        int[] cell = store.firstFreeCell(0, 1, 1);
        assertArrayEquals(new int[]{0, 1}, cell);
    }

    @Test
    public void firstFreeCell_noSpaceForWideWidget() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Fill columns 0-2 on row 0; a 3x1 widget fits at (0,1), but let's check (0,0) is blocked
        store.addItem(HomeItem.app(0, 0, 0, "com.a"));
        store.addItem(HomeItem.app(0, 1, 0, "com.b"));
        // first free 3-wide slot on row 0 starts at x=2? No: COLUMNS=4, so 3-wide needs x<=1.
        // x=0 blocked (com.a), x=1: cells 1,2,3 — 1 is blocked — not free.
        // So first free 3-wide is at (0,1)
        int[] cell = store.firstFreeCell(0, 3, 1);
        assertArrayEquals(new int[]{0, 1}, cell);
    }

    @Test
    public void firstFreeCell_gridFull_returnsNull() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        for (int y = 0; y < store.rows(); y++) {
            for (int x = 0; x < store.columns(); x++) {
                store.addItem(HomeItem.app(0, x, y, "com." + x + "_" + y));
            }
        }
        assertNull(store.firstFreeCell(0, 1, 1));
    }

    @Test
    public void firstFreeCell_multiSpanWidget() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Block top-left 2x2 with a widget
        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, 1, "p/c"));
        // First free 2x2 should be at (2, 0)
        int[] cell = store.firstFreeCell(0, 2, 2);
        assertArrayEquals(new int[]{2, 0}, cell);
    }

    // -------------------------------------------------------------------------
    // removePackage cascade
    // -------------------------------------------------------------------------

    @Test
    public void removePackage_removesAppItem() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.gone"));
        store.addItem(HomeItem.app(0, 1, 0, "com.keep"));
        store.removePackage("com.gone");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals("com.keep", items.get(0).getPackageName());
    }

    @Test
    public void removePackage_fromFolder_folderSurvives() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.folder(0, 0, 0, "F", Arrays.asList("com.a", "com.b", "com.c")));
        store.removePackage("com.a");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.FOLDER, items.get(0).getType());
        assertEquals(Arrays.asList("com.b", "com.c"), items.get(0).getPackageNames());
    }

    @Test
    public void removePackage_collapsesFolderToApp_oneMemberLeft() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.folder(0, 2, 3, "F", Arrays.asList("com.a", "com.b")));
        store.removePackage("com.a");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        HomeItem collapsed = items.get(0);
        assertEquals(HomeItem.Type.APP, collapsed.getType());
        assertEquals("com.b", collapsed.getPackageName());
        // Collapsed item inherits folder's cell
        assertEquals(0, collapsed.getScreen());
        assertEquals(2, collapsed.getCellX());
        assertEquals(3, collapsed.getCellY());
    }

    @Test
    public void removePackage_removesFolderWhenEmpty() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.folder(0, 0, 0, "Solo", Collections.singletonList("com.only")));
        store.removePackage("com.only");

        assertTrue(store.getItems().isEmpty());
    }

    @Test
    public void removePackage_removesFromMultipleFoldersAndApps() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.pkg"));
        store.addItem(HomeItem.folder(0, 1, 0, "F1", Arrays.asList("com.pkg", "com.other")));
        store.addItem(HomeItem.folder(0, 2, 0, "F2", Arrays.asList("com.pkg")));
        store.removePackage("com.pkg");

        List<HomeItem> items = store.getItems();
        // APP removed; F1 collapses to APP(com.other); F2 removed
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.APP, items.get(0).getType());
        assertEquals("com.other", items.get(0).getPackageName());
    }

    @Test
    public void removePackage_noOp_whenPackageAbsent() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.keep"));
        store.removePackage("com.nothere");
        assertEquals(1, store.getItems().size());
    }

    // -------------------------------------------------------------------------
    // screenCount / screen pruning
    // -------------------------------------------------------------------------

    @Test
    public void screenCount_empty_isOne() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertEquals(1, store.screenCount());
    }

    @Test
    public void screenCount_derivedFromItems() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.a"));
        store.addItem(HomeItem.app(2, 0, 0, "com.b")); // screen 2 — implies screens 0,1,2
        assertEquals(3, store.screenCount());
    }

    @Test
    public void screenCount_afterRemoveHighestScreen_prunesTrailing() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem s0 = HomeItem.app(0, 0, 0, "com.a");
        HomeItem s2 = HomeItem.app(2, 0, 0, "com.b");
        store.addItem(s0);
        store.addItem(s2);
        assertEquals(3, store.screenCount());

        store.removeItem(s2);
        assertEquals(1, store.screenCount()); // only screen 0 remains → count=1
    }

    @Test
    public void screenCount_afterRemovePackage_prunesTrailing() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.keep"));
        store.addItem(HomeItem.app(3, 0, 0, "com.gone"));
        assertEquals(4, store.screenCount());

        store.removePackage("com.gone");
        assertEquals(1, store.screenCount());
    }

    // -------------------------------------------------------------------------
    // Resilience: missing / corrupt file
    // -------------------------------------------------------------------------

    @Test
    public void load_missingFile_yieldsEmptyModel() {
        // File does not exist (deleted in setUp)
        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertTrue(store.getItems().isEmpty());
        assertEquals(1, store.screenCount());
    }

    @Test
    public void load_corruptJson_yieldsEmptyModel_butKeepsTheOriginalBytes() throws Exception {
        // This test previously asserted only the empty model and treated it as correct. An empty
        // model is still what the user sees — nothing can be recovered from garbage — but the
        // file itself must survive, because the old behaviour let the very next save overwrite
        // the only copy of the layout.
        File f = new File(ctx.getFilesDir(), "homescreen.json");
        writeFile(f, "this is not json {{{");

        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertTrue(store.getItems().isEmpty());
        assertEquals(1, store.screenCount());

        assertTrue("a present-but-unreadable file is a data-loss event", store.isLoadCorrupt());
        assertNotNull(store.corruptFileName());
        File kept = new File(ctx.getFilesDir(), store.corruptFileName());
        assertEquals("this is not json {{{", readFile(kept));
        assertFalse("the unreadable file must not be left where a save would overwrite it",
                f.exists());
    }

    @Test
    public void load_corruptJson_laterSaveDoesNotClobberTheQuarantinedCopy() throws Exception {
        File f = new File(ctx.getFilesDir(), "homescreen.json");
        writeFile(f, "{\"items\": broken");

        HomeScreenStore store = HomeScreenStore.get(ctx);
        File kept = new File(ctx.getFilesDir(), store.corruptFileName());

        // Placing an icon must not resurrect homescreen.json while the user has not been told.
        store.addItem(HomeItem.app(0, 0, 0, "com.new"));
        store.flush();

        assertFalse("saves stay suppressed until the corrupt load is acknowledged", f.exists());
        assertEquals("{\"items\": broken", readFile(kept));
    }

    @Test
    public void acknowledgeCorruptLoad_reEnablesSaving() throws Exception {
        File f = new File(ctx.getFilesDir(), "homescreen.json");
        writeFile(f, "not json at all");

        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.new"));
        store.flush();
        assertFalse(f.exists());

        store.acknowledgeCorruptLoad();
        store.flush();

        assertFalse(store.isLoadCorrupt());
        assertTrue("acknowledging persists what the user has built since", f.exists());
        assertTrue(readFile(f).contains("com.new"));
    }

    @Test
    public void load_corruptJson_recoversFromBakInsteadOfStartingEmpty() throws Exception {
        JSONObject root = new JSONObject().put("items",
                new JSONArray().put(HomeItem.app(0, 1, 1, "com.fromBak").toJson()));
        writeFile(new File(ctx.getFilesDir(), "homescreen.json.bak"), root.toString());
        writeFile(new File(ctx.getFilesDir(), "homescreen.json"), "corrupt");

        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertEquals(1, store.getItems().size());
        assertEquals("com.fromBak", store.getItems().get(0).getPackageName());
        assertFalse("recovery is not a data-loss event; saving stays enabled",
                store.isLoadCorrupt());
    }

    @Test
    public void load_unknownTypeEntry_skipsItAndLoadsValid() throws Exception {
        // Fix 6: Type.valueOf throws IllegalArgumentException (RuntimeException), not JSONException.
        // The per-entry catch must cover RuntimeException so valid neighbors survive.
        JSONArray arr = new JSONArray();
        arr.put(HomeItem.app(0, 0, 0, "com.first").toJson());
        JSONObject unknown = new JSONObject();
        unknown.put("type", "UNKNOWN_FUTURE_TYPE");
        unknown.put("screen", 0); unknown.put("cellX", 1); unknown.put("cellY", 0);
        unknown.put("spanX", 1);  unknown.put("spanY", 1);
        arr.put(unknown);
        arr.put(HomeItem.app(0, 2, 0, "com.second").toJson());
        JSONObject root = new JSONObject();
        root.put("items", arr);

        File f = new File(ctx.getFilesDir(), "homescreen.json");
        try (FileWriter fw = new FileWriter(f)) { fw.write(root.toString()); }

        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Both valid entries must be loaded; the unknown-type one must be silently skipped.
        assertEquals(2, store.getItems().size());
    }

    @Test
    public void load_partiallyCorruptJson_skipsCorruptEntries() throws Exception {
        // Valid envelope with one good and one bad entry
        JSONArray arr = new JSONArray();
        arr.put(HomeItem.app(0, 0, 0, "com.good").toJson());
        JSONObject bad = new JSONObject();
        bad.put("type", "APP"); // missing required fields
        arr.put(bad);
        JSONObject root = new JSONObject();
        root.put("items", arr);

        File f = new File(ctx.getFilesDir(), "homescreen.json");
        try (FileWriter fw = new FileWriter(f)) {
            fw.write(root.toString());
        }
        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertEquals(1, store.getItems().size());
        assertEquals("com.good", store.getItems().get(0).getPackageName());
    }

    // -------------------------------------------------------------------------
    // moveItem
    // -------------------------------------------------------------------------

    @Test
    public void moveItem_updatesPosition() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem app = HomeItem.app(0, 0, 0, "com.x");
        store.addItem(app);
        store.moveItem(app, 1, 3, 4);

        assertEquals(1, app.getScreen());
        assertEquals(3, app.getCellX());
        assertEquals(4, app.getCellY());
        // Old position is now free
        assertTrue(store.isAreaFree(0, 0, 0, 1, 1));
        // New position is occupied
        assertSame(app, store.itemAt(1, 3, 4));
    }

    // -------------------------------------------------------------------------
    // itemAt
    // -------------------------------------------------------------------------

    @Test
    public void itemAt_multiSpanWidget_allCellsReturnWidget() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem w = HomeItem.widget(0, 1, 1, 2, 2, 5, "p/c");
        store.addItem(w);

        assertSame(w, store.itemAt(0, 1, 1));
        assertSame(w, store.itemAt(0, 2, 1));
        assertSame(w, store.itemAt(0, 1, 2));
        assertSame(w, store.itemAt(0, 2, 2));
        assertNull(store.itemAt(0, 0, 0));
        assertNull(store.itemAt(0, 3, 1));
        assertNull(store.itemAt(1, 1, 1)); // different screen
    }

    // -------------------------------------------------------------------------
    // removeFromFolder
    // -------------------------------------------------------------------------

    @Test
    public void removeFromFolder_folderSurvivesWithTwoOrMoreMembers() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(0, 0, 0, "F", Arrays.asList("com.a", "com.b", "com.c"));
        store.addItem(folder);
        store.removeFromFolder(folder, "com.a");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.FOLDER, items.get(0).getType());
        assertEquals(Arrays.asList("com.b", "com.c"), items.get(0).getPackageNames());
    }

    @Test
    public void removeFromFolder_collapsesFolderToAppWhenOneMemberLeft() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(0, 2, 3, "F", Arrays.asList("com.a", "com.b"));
        store.addItem(folder);
        store.removeFromFolder(folder, "com.b");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        HomeItem collapsed = items.get(0);
        assertEquals(HomeItem.Type.APP, collapsed.getType());
        assertEquals("com.a", collapsed.getPackageName());
        assertEquals(0, collapsed.getScreen());
        assertEquals(2, collapsed.getCellX());
        assertEquals(3, collapsed.getCellY());
    }

    @Test
    public void removeFromFolder_removesFolderWhenNoMembersLeft() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(0, 1, 1, "F", Collections.singletonList("com.only"));
        store.addItem(folder);
        store.removeFromFolder(folder, "com.only");

        assertTrue(store.getItems().isEmpty());
    }

    @Test
    public void removeFromFolder_noOpWhenPackageNotInFolder() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(0, 0, 0, "F", Arrays.asList("com.a", "com.b"));
        store.addItem(folder);
        store.removeFromFolder(folder, "com.nothere");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(2, items.get(0).getPackageNames().size());
    }

    @Test
    public void removeFromFolder_persistedAfterReload() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(0, 0, 0, "Work", Arrays.asList("com.a", "com.b", "com.c"));
        store.addItem(folder);
        store.removeFromFolder(folder, "com.b");
        store.flush();

        HomeScreenStore.resetForTesting();
        HomeScreenStore reloaded = HomeScreenStore.get(ctx);
        List<HomeItem> items = reloaded.getItems();
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.FOLDER, items.get(0).getType());
        assertEquals(Arrays.asList("com.a", "com.c"), items.get(0).getPackageNames());
    }

    @Test
    public void renameFolder_updatesNameAndPersists() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(0, 0, 0, "OldName", Arrays.asList("com.a", "com.b"));
        store.addItem(folder);
        store.renameFolder(folder, "NewName");
        store.flush();

        HomeScreenStore.resetForTesting();
        HomeScreenStore reloaded = HomeScreenStore.get(ctx);
        List<HomeItem> items = reloaded.getItems();
        assertEquals(1, items.size());
        assertEquals("NewName", items.get(0).getFolderName());
    }

    // -------------------------------------------------------------------------
    // addToFolder
    // -------------------------------------------------------------------------

    @Test
    public void addToFolder_appendsPackageToMemberList() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(0, 0, 0, "F", Arrays.asList("com.a"));
        store.addItem(folder);
        store.addToFolder(folder, "com.b");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.FOLDER, items.get(0).getType());
        assertEquals(Arrays.asList("com.a", "com.b"), items.get(0).getPackageNames());
    }

    @Test
    public void addToFolder_persistedAfterReload() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(0, 0, 0, "F", Arrays.asList("com.a"));
        store.addItem(folder);
        store.addToFolder(folder, "com.b");
        store.flush();

        HomeScreenStore.resetForTesting();
        HomeScreenStore reloaded = HomeScreenStore.get(ctx);
        assertEquals(1, reloaded.getItems().size());
        assertEquals(Arrays.asList("com.a", "com.b"),
                reloaded.getItems().get(0).getPackageNames());
    }

    @Test
    public void addToFolder_noOpOnNonFolder() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem app = HomeItem.app(0, 0, 0, "com.app");
        store.addItem(app);
        store.addToFolder(app, "com.other"); // should be silently ignored
        assertEquals(1, store.getItems().size());
        assertEquals("com.app", store.getItems().get(0).getPackageName());
    }

    @Test
    public void addToFolder_duplicateIsNoOp() {
        // Fix 5a: adding a package that is already a member must not create a duplicate.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(0, 0, 0, "F", Arrays.asList("com.a", "com.b"));
        store.addItem(folder);
        store.addToFolder(folder, "com.a"); // already a member — should be ignored
        List<String> pkgs = store.getItems().get(0).getPackageNames();
        assertEquals(2, pkgs.size());
        assertEquals(Arrays.asList("com.a", "com.b"), pkgs);
    }

    @Test
    public void removePackage_removesAllDuplicateOccurrencesInFolder() {
        // Fix 5c: legacy folders that somehow contain a package twice must be fully cleaned up.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Manually build a folder with a duplicate member (simulating corrupt legacy data).
        HomeItem folder = HomeItem.folder(0, 0, 0, "F",
                Arrays.asList("com.pkg", "com.other", "com.pkg"));
        store.addItem(folder);
        store.removePackage("com.pkg");
        // Both occurrences removed → 1 member left → collapses to APP (no phantom).
        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.APP, items.get(0).getType());
        assertEquals("com.other", items.get(0).getPackageName());
    }

    // -------------------------------------------------------------------------
    // repackOutOfBounds — Phase A
    // -------------------------------------------------------------------------

    @Test
    public void repackOutOfBounds_itemAtCol5_relocatedWhenShrinkTo4Cols() {
        // Create store with a 6-column layout.
        Settings.setGridCols(ctx, 6);
        HomeScreenStore store = HomeScreenStore.get(ctx);

        // Fill columns 0–3 on row 0 so the stranded item can't land there.
        store.addItem(HomeItem.app(0, 0, 0, "com.a"));
        store.addItem(HomeItem.app(0, 1, 0, "com.b"));
        store.addItem(HomeItem.app(0, 2, 0, "com.c"));
        store.addItem(HomeItem.app(0, 3, 0, "com.d"));
        // Item at x=5 is valid in a 6-col grid.
        store.addItem(HomeItem.app(0, 5, 0, "com.stranded"));

        // Shrink to 4 columns → x=5 becomes out of bounds.
        Settings.setGridCols(ctx, 4);
        store.reloadGridSize(ctx);

        HomeItem stranded = null;
        for (HomeItem item : store.getItems()) {
            if ("com.stranded".equals(item.getPackageName())) { stranded = item; break; }
        }
        assertNotNull("stranded item must still exist", stranded);
        // Must now be within the 4-column bounds.
        assertTrue("cellX in bounds", stranded.getCellX() >= 0);
        assertTrue("cellX+spanX <= cols", stranded.getCellX() + stranded.getSpanX() <= 4);
        assertTrue("cellY in bounds", stranded.getCellY() >= 0);
        assertTrue("cellY+spanY <= rows", stranded.getCellY() + stranded.getSpanY() <= store.rows());
    }

    @Test
    public void repackOutOfBounds_widget5x2_clampedTo4x2_andStaysInPlace() {
        // Widget with spanX=5 is valid when the grid has >=5 columns.
        Settings.setGridCols(ctx, 6);
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.widget(0, 0, 0, 5, 2, 42, "com.w/com.w.W"));

        // Shrink to 4 columns → spanX must be clamped to 4.
        Settings.setGridCols(ctx, 4);
        store.reloadGridSize(ctx);

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        HomeItem widget = items.get(0);
        assertEquals(HomeItem.Type.WIDGET, widget.getType());
        assertEquals("spanX clamped to grid width", 4, widget.getSpanX());
        assertEquals("spanY unchanged", 2, widget.getSpanY());
        // After clamping (0+4=4 <= 4) the widget fits in place.
        assertEquals("stays at cellX=0", 0, widget.getCellX());
        assertEquals("stays at cellY=0", 0, widget.getCellY());
    }

    @Test
    public void repackOutOfBounds_multipleStrandedItemsAllRelocated() {
        Settings.setGridCols(ctx, 6);
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 4, 0, "com.x"));
        store.addItem(HomeItem.app(0, 5, 0, "com.y"));

        Settings.setGridCols(ctx, 4);
        store.reloadGridSize(ctx);

        for (HomeItem item : store.getItems()) {
            assertTrue(item.getCellX() + item.getSpanX() <= 4);
            assertTrue(item.getCellY() + item.getSpanY() <= store.rows());
        }
        // Both items must be present.
        assertEquals(2, store.getItems().size());
    }

    @Test
    public void repackOutOfBounds_itemWithinBoundsIsUntouched() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 1, 2, "com.ok"));

        // No grid change — item should be untouched.
        store.reloadGridSize(ctx);

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(1, items.get(0).getCellX());
        assertEquals(2, items.get(0).getCellY());
    }

    // -------------------------------------------------------------------------
    // removePackage — composite-key (work-profile) behaviour (Phase F)
    // -------------------------------------------------------------------------
    //
    // The only caller is MainActivity's package receiver, and package broadcasts are delivered
    // per user: the only ACTION_PACKAGE_REMOVED this launcher can receive is the personal
    // profile's, so the key it removes is always the bare one. The four tests this block used to
    // hold asserted the opposite — "package broadcasts are not per-profile, so removing com.pkg
    // must also strip com.pkg#10" — and the store matched on UserKeys.packageOf to satisfy them,
    // which is how uninstalling personal Chrome deleted work Chrome's icon. Work entries that have
    // really gone are pruned by removeUnknownApps on the next full load, which knows which
    // profiles it enumerated; this path cannot know that and must not guess.

    @Test
    public void removePackage_personalUninstall_keepsTheWorkCopy() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.pkg"));              // personal (bare key)
        store.addItem(HomeItem.app(0, 1, 0, "com.pkg#10"));           // work (composite key)
        store.addItem(HomeItem.app(0, 2, 0, "com.keep"));

        store.removePackage("com.pkg");

        List<HomeItem> items = store.getItems();
        assertEquals("only the personal copy was uninstalled", 2, items.size());
        assertEquals("com.pkg#10", items.get(0).getPackageName());
        assertEquals("com.keep", items.get(1).getPackageName());
    }

    @Test
    public void removePackage_workCopyAlone_survivesAPersonalUninstall() {
        // Only the work entry is on the home screen; the personal broadcast is about a package
        // this user never had an icon for, and must leave the work icon alone.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.work#10"));

        store.removePackage("com.work");

        assertEquals(1, store.getItems().size());
        assertEquals("com.work#10", store.getItems().get(0).getPackageName());
    }

    @Test
    public void removePackage_personalUninstall_keepsTheWorkMemberOfAFolder() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        List<String> members = Arrays.asList("com.pkg", "com.pkg#10", "com.other");
        store.addItem(HomeItem.folder(0, 0, 0, "F", members));

        store.removePackage("com.pkg");

        // Only "com.pkg" leaves; 2 members remain, so the folder survives.
        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.FOLDER, items.get(0).getType());
        assertEquals(Arrays.asList("com.pkg#10", "com.other"), items.get(0).getPackageNames());
    }

    @Test
    public void removePackage_personalUninstall_leavesAFolderOfWorkEntriesAlone() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        List<String> members = Arrays.asList("com.gone#10", "com.gone#11", "com.keep");
        store.addItem(HomeItem.folder(0, 1, 2, "F", members));

        store.removePackage("com.gone");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.FOLDER, items.get(0).getType());
        assertEquals(members, items.get(0).getPackageNames());
    }

    @Test
    public void removePackage_personalUninstall_collapsesOntoTheWorkMember() {
        // The cascade still runs on what is actually removed: a two-member folder holding both
        // copies collapses to the surviving work copy, at the folder's cell.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.folder(0, 2, 3, "F", Arrays.asList("com.pkg", "com.pkg#10")));

        store.removePackage("com.pkg");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        HomeItem collapsed = items.get(0);
        assertEquals(HomeItem.Type.APP, collapsed.getType());
        assertEquals("com.pkg#10", collapsed.getPackageName());
        assertEquals(2, collapsed.getCellX());
        assertEquals(3, collapsed.getCellY());
    }

    @Test
    public void removePackage_matchesTheWholeKey_soAWorkKeyRemovesOnlyThatCopy() {
        // Nothing sends a composite key today; this pins the contract to "the key, exactly"
        // rather than "the package", so a future caller cannot rely on a package-wide sweep.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.pkg"));
        store.addItem(HomeItem.app(0, 1, 0, "com.pkg#10"));

        store.removePackage("com.pkg#10");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals("com.pkg", items.get(0).getPackageName());
    }

    @Test
    public void repackOutOfBounds_rowShrink_relocatesRowStranded() {
        Settings.setGridRows(ctx, 8);
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 7, "com.bottom")); // valid at row 7 in an 8-row grid

        Settings.setGridRows(ctx, 5);
        store.reloadGridSize(ctx);

        HomeItem item = store.getItems().get(0);
        assertTrue("cellY+spanY <= 5", item.getCellY() + item.getSpanY() <= 5);
    }

    // -------------------------------------------------------------------------
    // Phase B — Dock (screen == HomeItem.DOCK == -1)
    // -------------------------------------------------------------------------

    @Test
    public void dock_jsonRoundTrip_dockItem() throws Exception {
        // screen=-1 must survive JSON serialization.
        HomeItem original = HomeItem.app(HomeItem.DOCK, 2, 0, "com.dock.app");
        HomeItem restored = HomeItem.fromJson(original.toJson());

        assertEquals(HomeItem.Type.APP, restored.getType());
        assertEquals(HomeItem.DOCK, restored.getScreen());
        assertEquals(2, restored.getCellX());
        assertEquals(0, restored.getCellY());
        assertEquals("com.dock.app", restored.getPackageName());
    }

    @Test
    public void dock_storeRoundTrip_dockItemPersists() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(HomeItem.DOCK, 0, 0, "com.dock.a"));
        store.addItem(HomeItem.app(0, 0, 0, "com.grid.a")); // grid item alongside dock item
        store.flush();

        HomeScreenStore.resetForTesting();
        HomeScreenStore reloaded = HomeScreenStore.get(ctx);

        List<HomeItem> dockItems = reloaded.dockItems();
        assertEquals(1, dockItems.size());
        assertEquals(HomeItem.DOCK, dockItems.get(0).getScreen());
        assertEquals(0, dockItems.get(0).getCellX());
        assertEquals("com.dock.a", dockItems.get(0).getPackageName());
    }

    @Test
    public void dock_dockItems_returnsOnlyDockItems() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(HomeItem.DOCK, 0, 0, "com.dock.a"));
        store.addItem(HomeItem.app(HomeItem.DOCK, 1, 0, "com.dock.b"));
        store.addItem(HomeItem.app(0, 2, 0, "com.grid"));

        List<HomeItem> dock = store.dockItems();
        assertEquals(2, dock.size());
        for (HomeItem item : dock) assertEquals(HomeItem.DOCK, item.getScreen());
    }

    @Test
    public void dock_isDockSlotFree_emptyDock() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        for (int slot = 0; slot < store.columns(); slot++) {
            assertTrue("slot " + slot + " should be free", store.isDockSlotFree(slot));
        }
    }

    @Test
    public void dock_isDockSlotFree_occupiedSlot() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(HomeItem.DOCK, 1, 0, "com.dock.a"));

        assertFalse(store.isDockSlotFree(1));
        assertTrue(store.isDockSlotFree(0));
        assertTrue(store.isDockSlotFree(2));
    }

    @Test
    public void dock_firstFreeDockSlot_empty() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertEquals(0, store.firstFreeDockSlot());
    }

    @Test
    public void dock_firstFreeDockSlot_slotZeroOccupied() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(HomeItem.DOCK, 0, 0, "com.dock.a"));
        assertEquals(1, store.firstFreeDockSlot());
    }

    @Test
    public void dock_firstFreeDockSlot_allFull() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        for (int slot = 0; slot < store.columns(); slot++) {
            store.addItem(HomeItem.app(HomeItem.DOCK, slot, 0, "com.dock." + slot));
        }
        assertEquals(-1, store.firstFreeDockSlot());
    }

    @Test
    public void dock_screenCount_ignoresDockItems() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Dock items have screen=-1; they must not affect screenCount().
        store.addItem(HomeItem.app(HomeItem.DOCK, 0, 0, "com.dock.a"));
        store.addItem(HomeItem.app(HomeItem.DOCK, 1, 0, "com.dock.b"));
        assertEquals(1, store.screenCount()); // still at least 1 grid screen
    }

    @Test
    public void dock_screenCount_dockAndGridTogether() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(HomeItem.DOCK, 0, 0, "com.dock.a"));
        store.addItem(HomeItem.app(2, 0, 0, "com.grid.b")); // highest grid screen = 2
        assertEquals(3, store.screenCount()); // screens 0,1,2 — dock ignored
    }

    @Test
    public void dock_isAreaFree_dockAndGridDoNotConflict() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Same x/y but different "screen" — should not conflict.
        store.addItem(HomeItem.app(HomeItem.DOCK, 0, 0, "com.dock.a"));
        store.addItem(HomeItem.app(0, 0, 0, "com.grid.a"));

        // Grid slot (0,0) occupied by com.grid.a.
        assertFalse(store.isAreaFree(0, 0, 0, 1, 1));
        // Dock slot 0 occupied by com.dock.a.
        assertFalse(store.isAreaFree(HomeItem.DOCK, 0, 0, 1, 1));
        // Grid slot (1,0) is free.
        assertTrue(store.isAreaFree(0, 1, 0, 1, 1));
        // Dock slot 1 is free.
        assertTrue(store.isAreaFree(HomeItem.DOCK, 1, 0, 1, 1));
    }

    @Test
    public void dock_repackOutOfBounds_doesNotRelocateDockItems() {
        // Dock items (screen=-1) must be excluded from the grid repack pass.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(HomeItem.DOCK, 0, 0, "com.dock.a"));
        store.addItem(HomeItem.app(HomeItem.DOCK, 1, 0, "com.dock.b"));

        // Trigger repack without changing grid size (items are already in bounds).
        store.reloadGridSize(ctx);

        List<HomeItem> dockItems = store.dockItems();
        assertEquals(2, dockItems.size());
        for (HomeItem item : dockItems) {
            assertEquals("dock item must stay in dock", HomeItem.DOCK, item.getScreen());
        }
    }

    @Test
    public void dock_repackRelocatesDockItemWhenSlotOutOfBounds() {
        // If the grid shrinks so that a dock slot index >= new_cols, move within dock.
        Settings.setGridCols(ctx, 6);
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Occupy slots 0 and 5 in a 6-col dock.
        store.addItem(HomeItem.app(HomeItem.DOCK, 0, 0, "com.dock.first"));
        store.addItem(HomeItem.app(HomeItem.DOCK, 5, 0, "com.dock.last"));

        // Shrink to 4 cols → slot 5 is out of range.
        Settings.setGridCols(ctx, 4);
        store.reloadGridSize(ctx);

        List<HomeItem> dockItems = store.dockItems();
        assertEquals(2, dockItems.size());
        for (HomeItem item : dockItems) {
            assertEquals("must stay in dock", HomeItem.DOCK, item.getScreen());
            assertTrue("slot must be < 4", item.getCellX() < 4);
            assertTrue("slot must be >= 0", item.getCellX() >= 0);
        }
    }

    @Test
    public void dock_removePackage_removesDockAppItem() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(HomeItem.DOCK, 0, 0, "com.gone"));
        store.addItem(HomeItem.app(HomeItem.DOCK, 1, 0, "com.keep"));

        store.removePackage("com.gone");

        List<HomeItem> dockItems = store.dockItems();
        assertEquals(1, dockItems.size());
        assertEquals("com.keep", dockItems.get(0).getPackageName());
    }

    @Test
    public void dock_removePackage_collapsesDockFolderToApp() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.folder(HomeItem.DOCK, 2, 0, "F",
                Arrays.asList("com.a", "com.b")));

        store.removePackage("com.a");

        // Folder collapses to APP at the same dock slot.
        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        HomeItem collapsed = items.get(0);
        assertEquals(HomeItem.Type.APP, collapsed.getType());
        assertEquals(HomeItem.DOCK, collapsed.getScreen()); // still in dock
        assertEquals(2, collapsed.getCellX());              // same slot
        assertEquals("com.b", collapsed.getPackageName());
    }

    @Test
    public void dock_removeFromFolder_collapsesDockFolderKeepsDockSlot() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem folder = HomeItem.folder(HomeItem.DOCK, 3, 0, "F",
                Arrays.asList("com.a", "com.b"));
        store.addItem(folder);

        store.removeFromFolder(folder, "com.a");

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        HomeItem collapsed = items.get(0);
        assertEquals(HomeItem.Type.APP, collapsed.getType());
        assertEquals(HomeItem.DOCK, collapsed.getScreen()); // remains in dock
        assertEquals(3, collapsed.getCellX());              // same dock slot
        assertEquals("com.b", collapsed.getPackageName());
    }

    @Test
    public void dock_moveItem_movesFromGridToDock() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem app = HomeItem.app(0, 1, 2, "com.x");
        store.addItem(app);

        store.moveItem(app, HomeItem.DOCK, 0, 0);

        assertEquals(HomeItem.DOCK, app.getScreen());
        assertEquals(0, app.getCellX());
        assertEquals(0, app.getCellY());
        // Grid position freed.
        assertTrue(store.isAreaFree(0, 1, 2, 1, 1));
        // Dock slot occupied.
        assertFalse(store.isDockSlotFree(0));
    }

    // -------------------------------------------------------------------------
    // Phase E — replaceAll
    // -------------------------------------------------------------------------

    @Test
    public void replaceAll_atomicSwapAndPersists() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.old1"));
        store.addItem(HomeItem.app(0, 1, 0, "com.old2"));

        List<HomeItem> newItems = Arrays.asList(
                HomeItem.app(0, 0, 0, "com.new1"),
                HomeItem.folder(0, 1, 0, "F", Arrays.asList("com.a", "com.b")));
        store.replaceAll(newItems);

        List<HomeItem> live = store.getItems();
        assertEquals(2, live.size());
        assertEquals("com.new1", live.get(0).getPackageName());
        assertEquals(HomeItem.Type.FOLDER, live.get(1).getType());

        // Persists after reload
        store.flush();
        HomeScreenStore.resetForTesting();
        HomeScreenStore reloaded = HomeScreenStore.get(ctx);
        assertEquals(2, reloaded.getItems().size());
        assertEquals("com.new1", reloaded.getItems().get(0).getPackageName());
    }

    @Test
    public void replaceAll_emptyListClearsStore() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.x"));
        store.replaceAll(Collections.emptyList());
        assertTrue(store.getItems().isEmpty());
    }

    // -------------------------------------------------------------------------
    // Phase E — toBackupJson / fromBackupJson round-trip
    // -------------------------------------------------------------------------

    @Test
    public void toFromBackupJson_roundTrip_appAndFolder() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.app"));
        store.addItem(HomeItem.folder(0, 1, 0, "Work", Arrays.asList("com.a", "com.b")));

        JSONObject json = store.toBackupJson(ctx);
        HomeScreenStore.ParsedBackup backup = HomeScreenStore.fromBackupJson(json);

        assertEquals(0, backup.widgetSkipCount);
        assertEquals(2, backup.items.size());

        HomeItem app = null, folder = null;
        for (HomeItem it : backup.items) {
            if (it.getType() == HomeItem.Type.APP) app = it;
            else if (it.getType() == HomeItem.Type.FOLDER) folder = it;
        }
        assertNotNull(app);
        assertEquals("com.app", app.getPackageName());
        assertNotNull(folder);
        assertEquals("Work", folder.getFolderName());
        assertEquals(Arrays.asList("com.a", "com.b"), folder.getPackageNames());
    }

    @Test
    public void toFromBackupJson_dockItemsRoundTrip() throws Exception {
        // Dock items live at screen = HomeItem.DOCK (screen = -1 per Phase B design)
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem dockApp = HomeItem.app(-1, 0, 0, "com.dock");  // screen=-1 = dock
        store.addItem(dockApp);
        store.addItem(HomeItem.app(0, 0, 0, "com.normal"));

        JSONObject json = store.toBackupJson(ctx);
        HomeScreenStore.ParsedBackup backup = HomeScreenStore.fromBackupJson(json);

        assertEquals(2, backup.items.size());
        HomeItem dock = null;
        for (HomeItem it : backup.items) {
            if (it.getPackageName() != null && it.getPackageName().equals("com.dock")) dock = it;
        }
        assertNotNull("dock item must survive backup round-trip", dock);
        assertEquals("dock item screen value must be preserved", -1, dock.getScreen());
    }

    @Test
    public void toFromBackupJson_gridDimsRoundTrip() throws Exception {
        Settings.setGridCols(ctx, 5);
        Settings.setGridRows(ctx, 6);
        HomeScreenStore store = HomeScreenStore.get(ctx);

        JSONObject json = store.toBackupJson(ctx);
        assertEquals(5, json.getInt("gridCols"));
        assertEquals(6, json.getInt("gridRows"));

        HomeScreenStore.ParsedBackup backup = HomeScreenStore.fromBackupJson(json);
        assertEquals(5, backup.gridCols);
        assertEquals(6, backup.gridRows);
    }

    @Test
    public void toBackupJson_carriesNoPersonalityData() throws Exception {
        // The backup format is the launcher's, not a personality's. A fork's per-app table
        // (colours, categories, scores) is derived from the installed apps and regenerates on the
        // restoring device, so putting it in here would only make restores device-specific.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        JSONObject json = store.toBackupJson(ctx);

        assertEquals(4, json.length()); // version, gridCols, gridRows, items — and nothing else
        assertTrue(json.has("version"));
        assertTrue(json.has("gridCols"));
        assertTrue(json.has("gridRows"));
        assertTrue(json.has("items"));
    }

    @Test
    public void fromBackupJson_widgetItemsSkippedAndCounted() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.app"));
        store.addItem(HomeItem.widget(0, 1, 0, 2, 1, 42, "com.w/com.w.W"));
        store.addItem(HomeItem.widget(0, 0, 1, 1, 1, 43, "com.w/com.w.W2"));

        JSONObject json = store.toBackupJson(ctx);
        HomeScreenStore.ParsedBackup backup = HomeScreenStore.fromBackupJson(json);

        assertEquals(2, backup.widgetSkipCount);
        assertEquals(1, backup.items.size());
        assertEquals("com.app", backup.items.get(0).getPackageName());
    }

    @Test
    public void fromBackupJson_unknownVersion_throwsBackupVersionException() throws Exception {
        JSONObject bad = new JSONObject();
        bad.put("version", 99);
        bad.put("items", new JSONArray());

        try {
            HomeScreenStore.fromBackupJson(bad);
            fail("expected BackupVersionException");
        } catch (HomeScreenStore.BackupVersionException e) {
            assertEquals(99, e.version);
        }
    }

    @Test
    public void fromBackupJson_missingVersionField_throwsJSONException() {
        JSONObject bad = new JSONObject();
        try {
            bad.put("items", new JSONArray());
            HomeScreenStore.fromBackupJson(bad);
            fail("expected JSONException");
        } catch (org.json.JSONException expected) {
            // good
        }
    }

    @Test
    public void fromBackupJson_missingItemsArray_throwsJSONException() throws Exception {
        JSONObject bad = new JSONObject();
        bad.put("version", 1);
        // no "items" key

        try {
            HomeScreenStore.fromBackupJson(bad);
            fail("expected JSONException");
        } catch (org.json.JSONException expected) {
            // good
        }
    }

    @Test
    public void fromBackupJson_corruptItemEntry_skipsIt() throws Exception {
        JSONArray arr = new JSONArray();
        arr.put(HomeItem.app(0, 0, 0, "com.good").toJson());
        JSONObject bad = new JSONObject();
        bad.put("type", "APP"); // missing required fields
        arr.put(bad);
        arr.put(HomeItem.app(0, 1, 0, "com.also_good").toJson());

        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("items", arr);

        HomeScreenStore.ParsedBackup backup = HomeScreenStore.fromBackupJson(root);
        assertEquals(2, backup.items.size());
    }

    @Test
    public void fromBackupJson_absentGridDims_defaultsUsed() throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("items", new JSONArray());
        // no gridCols, gridRows

        HomeScreenStore.ParsedBackup backup = HomeScreenStore.fromBackupJson(root);
        assertEquals(HomeScreenStore.DEFAULT_COLS, backup.gridCols);
        assertEquals(HomeScreenStore.DEFAULT_ROWS, backup.gridRows);
    }

    @Test
    public void fromBackupJson_unknownFields_areIgnored() throws Exception {
        // Backups written by a fork that added its own top-level block must still restore here:
        // unknown keys are skipped rather than rejected.
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("items", new JSONArray());
        root.put("colorCache", new JSONObject().put("com.example", "whatever"));

        HomeScreenStore.ParsedBackup backup = HomeScreenStore.fromBackupJson(root);
        assertTrue(backup.items.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Phase C — setSpan
    // -------------------------------------------------------------------------

    @Test
    public void setSpan_updatesSpanAndPersists() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem widget = HomeItem.widget(0, 0, 0, 2, 2, 42, "com.w/com.w.W");
        store.addItem(widget);
        store.setSpan(widget, 3, 1);

        assertEquals("spanX updated", 3, widget.getSpanX());
        assertEquals("spanY updated", 1, widget.getSpanY());

        store.flush();
        HomeScreenStore.resetForTesting();
        HomeScreenStore reloaded = HomeScreenStore.get(ctx);
        HomeItem reloadedWidget = reloaded.getItems().get(0);
        assertEquals("spanX persisted", 3, reloadedWidget.getSpanX());
        assertEquals("spanY persisted", 1, reloadedWidget.getSpanY());
    }

    @Test
    public void setSpan_doesNotChangePosition() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem widget = HomeItem.widget(0, 1, 2, 2, 2, 7, "com.w/com.w.W");
        store.addItem(widget);
        store.setSpan(widget, 4, 3);

        assertEquals("screen unchanged", 0, widget.getScreen());
        assertEquals("cellX unchanged", 1, widget.getCellX());
        assertEquals("cellY unchanged", 2, widget.getCellY());
    }

    @Test
    public void setSpan_isAreaFreeExcluding_reflectsNewSpan() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        HomeItem widget = HomeItem.widget(0, 0, 0, 2, 2, 42, "com.w/com.w.W");
        store.addItem(widget);

        // After shrinking to 1x1, cells (1,0),(0,1),(1,1) should be free.
        store.setSpan(widget, 1, 1);
        assertTrue("cell (1,0) free after shrink", store.isAreaFree(0, 1, 0, 1, 1));
        assertTrue("cell (0,1) free after shrink", store.isAreaFree(0, 0, 1, 1, 1));
    }

    // -------------------------------------------------------------------------
    // removeUnknownUserSerials — work-profile ghost-item pruning (Finding 2)
    // -------------------------------------------------------------------------

    @Test
    public void removeUnknownUserSerials_removesWorkAppWithUnknownSerial() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.personal"));   // bare key, serial 0
        store.addItem(HomeItem.app(0, 1, 0, "com.work#10"));    // work, serial 10 (valid)
        store.addItem(HomeItem.app(0, 2, 0, "com.gone#20"));    // work, serial 20 (invalid)

        java.util.Set<Long> valid = new java.util.HashSet<>(Arrays.asList(0L, 10L));
        store.removeUnknownUserSerials(valid);

        List<HomeItem> items = store.getItems();
        assertEquals(2, items.size());
        java.util.List<String> pkgs = new java.util.ArrayList<>();
        for (HomeItem item : items) pkgs.add(item.getPackageName());
        assertTrue(pkgs.contains("com.personal"));
        assertTrue(pkgs.contains("com.work#10"));
        assertFalse(pkgs.contains("com.gone#20"));
    }

    @Test
    public void removeUnknownUserSerials_personalItemsUntouched() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.a"));
        store.addItem(HomeItem.app(0, 1, 0, "com.b"));

        // Only serial 0 is valid — bare-key personal apps must survive.
        java.util.Set<Long> valid = new java.util.HashSet<>(Collections.singletonList(0L));
        store.removeUnknownUserSerials(valid);

        assertEquals(2, store.getItems().size());
    }

    @Test
    public void removeUnknownUserSerials_folderCascade_collapseToApp() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Folder: valid work member (serial 10) and invalid work member (serial 20).
        store.addItem(HomeItem.folder(0, 1, 2, "F",
                Arrays.asList("com.a#10", "com.b#20")));

        java.util.Set<Long> valid = new java.util.HashSet<>(Arrays.asList(0L, 10L));
        store.removeUnknownUserSerials(valid);

        // 1 member remaining → collapses to APP at folder's cell.
        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        HomeItem collapsed = items.get(0);
        assertEquals(HomeItem.Type.APP, collapsed.getType());
        assertEquals("com.a#10", collapsed.getPackageName());
        assertEquals(0, collapsed.getScreen());
        assertEquals(1, collapsed.getCellX());
        assertEquals(2, collapsed.getCellY());
    }

    @Test
    public void removeUnknownUserSerials_folderCascade_removeFolderWhenEmpty() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Both members belong to the deleted profile (serial 10).
        store.addItem(HomeItem.folder(0, 0, 0, "F",
                Arrays.asList("com.a#10", "com.b#10")));

        java.util.Set<Long> valid = new java.util.HashSet<>(Collections.singletonList(0L));
        store.removeUnknownUserSerials(valid);

        assertTrue(store.getItems().isEmpty());
    }

    @Test
    public void removeUnknownUserSerials_folderSurvivesIfPersonalMembersRemain() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        // Folder has one personal member (bare key) and two invalid work members.
        store.addItem(HomeItem.folder(0, 0, 0, "F",
                Arrays.asList("com.personal", "com.a#10", "com.b#10")));

        java.util.Set<Long> valid = new java.util.HashSet<>(Collections.singletonList(0L));
        store.removeUnknownUserSerials(valid);

        // 1 member remains → collapses to APP.
        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.APP, items.get(0).getType());
        assertEquals("com.personal", items.get(0).getPackageName());
    }

    // -------------------------------------------------------------------------
    // repackOutOfBounds — stranded spanning widget lands at optimal cell (Finding 5)
    // -------------------------------------------------------------------------

    @Test
    public void repackOutOfBounds_strandedSpanningWidget_landsAtFirstFreeCellExcludingOwn() {
        // Regression: 4-wide widget at (1,0) in a 6-col grid; shrink to 4 cols.
        // Without the fix: the widget's own footprint blocks (0,0), so it lands at (0,1).
        // With the fix: the widget is excluded from the free-cell scan and correctly lands at (0,0).
        Settings.setGridCols(ctx, 6);
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.widget(0, 1, 0, 4, 1, 42, "com.w/com.w.W"));

        Settings.setGridCols(ctx, 4);
        store.reloadGridSize(ctx);

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        HomeItem widget = items.get(0);
        assertEquals(HomeItem.Type.WIDGET, widget.getType());
        assertEquals("spanX fits 4-col grid", 4, widget.getSpanX());
        assertEquals("widget relocates to cellX=0", 0, widget.getCellX());
        assertEquals("widget relocates to cellY=0", 0, widget.getCellY());
    }

    // -------------------------------------------------------------------------
    // Out-of-range geometry from a file (C2)
    // -------------------------------------------------------------------------

    @Test
    public void fromBackupJson_hugeScreenIndex_itemDropped() throws Exception {
        JSONObject poisoned = HomeItem.app(0, 0, 0, "com.poison").toJson();
        poisoned.put("screen", 1000000);
        JSONObject root = backupRoot(new JSONArray()
                .put(poisoned)
                .put(HomeItem.app(0, 1, 0, "com.ok").toJson()));

        HomeScreenStore.ParsedBackup parsed = HomeScreenStore.fromBackupJson(root);
        assertEquals(1, parsed.items.size());
        assertEquals("com.ok", parsed.items.get(0).getPackageName());
    }

    @Test
    public void fromBackupJson_hugeScreenIndex_neverReachesScreenCount() throws Exception {
        JSONObject poisoned = HomeItem.app(0, 0, 0, "com.poison").toJson();
        poisoned.put("screen", 1000000);

        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.replaceAll(HomeScreenStore.fromBackupJson(
                backupRoot(new JSONArray().put(poisoned))).items);

        // One CellLayout is allocated per screen on the main thread — a million is an OOM.
        assertEquals(1, store.screenCount());
    }

    @Test
    public void fromBackupJson_negativeAndOversizedCells_dropped() throws Exception {
        JSONObject negCell = HomeItem.app(0, 0, 0, "com.negCell").toJson();
        negCell.put("cellX", -5);
        JSONObject negScreen = HomeItem.app(0, 0, 0, "com.negScreen").toJson();
        negScreen.put("screen", -2); // -1 is DOCK; anything below is nonsense
        JSONObject hugeCell = HomeItem.app(0, 0, 0, "com.hugeCell").toJson();
        hugeCell.put("cellY", 99);

        HomeScreenStore.ParsedBackup parsed = HomeScreenStore.fromBackupJson(backupRoot(
                new JSONArray().put(negCell).put(negScreen).put(hugeCell)));
        assertTrue("every out-of-range item must be dropped", parsed.items.isEmpty());
    }

    @Test
    public void fromBackupJson_appSpanCannotBePoisoned() throws Exception {
        // Documents why the span check only bites on the load path: HomeItem.fromJson builds
        // APP/FOLDER through the 1x1 factories and ignores the file's spans entirely, and every
        // WIDGET is dropped on import — so a backup can never carry a bad span through.
        JSONObject zeroSpan = HomeItem.app(0, 0, 0, "com.zeroSpan").toJson();
        zeroSpan.put("spanX", 0);
        zeroSpan.put("spanY", 4000);

        HomeScreenStore.ParsedBackup parsed = HomeScreenStore.fromBackupJson(
                backupRoot(new JSONArray().put(zeroSpan)));
        assertEquals(1, parsed.items.size());
        assertEquals(1, parsed.items.get(0).getSpanX());
        assertEquals(1, parsed.items.get(0).getSpanY());
    }

    @Test
    public void load_widgetWithBadSpan_isNotLoaded() throws Exception {
        // WIDGET is the only type that carries spans through fromJson, and only the load path
        // ever builds one from a file. A zero or grid-sized span makes isAreaFree nonsense.
        JSONObject zero = HomeItem.widget(0, 0, 0, 2, 2, 7, "com.w/com.w.W").toJson();
        zero.put("spanX", 0);
        JSONObject huge = HomeItem.widget(0, 0, 1, 2, 2, 8, "com.w/com.w.W").toJson();
        huge.put("spanY", 500);
        JSONObject root = new JSONObject().put("items", new JSONArray()
                .put(zero).put(huge).put(HomeItem.app(0, 3, 0, "com.ok").toJson()));
        writeFile(new File(ctx.getFilesDir(), "homescreen.json"), root.toString());

        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertEquals(1, store.getItems().size());
        assertEquals("com.ok", store.getItems().get(0).getPackageName());
    }

    @Test
    public void fromBackupJson_dockItemsSurviveTheBoundsCheck() throws Exception {
        // screen == -1 is the dock sentinel, not an out-of-range screen.
        HomeScreenStore.ParsedBackup parsed = HomeScreenStore.fromBackupJson(backupRoot(
                new JSONArray().put(HomeItem.app(HomeItem.DOCK, 2, 0, "com.dock").toJson())));
        assertEquals(1, parsed.items.size());
        assertEquals(HomeItem.DOCK, parsed.items.get(0).getScreen());
    }

    @Test
    public void fromBackupJson_itemCountIsCapped() throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < 5000; i++) arr.put(HomeItem.app(0, 0, 0, "com.a" + i).toJson());

        HomeScreenStore.ParsedBackup parsed = HomeScreenStore.fromBackupJson(backupRoot(arr));
        assertTrue("item count must be bounded, was " + parsed.items.size(),
                parsed.items.size() <= 512);
    }

    @Test
    public void load_hugeScreenIndexAlreadyOnDisk_isNotLoaded() throws Exception {
        // An install poisoned before the import-side fix existed must still recover on launch.
        JSONObject poisoned = HomeItem.app(0, 0, 0, "com.poison").toJson();
        poisoned.put("screen", 1000000);
        JSONObject root = new JSONObject().put("items", new JSONArray()
                .put(poisoned)
                .put(HomeItem.app(0, 1, 0, "com.ok").toJson()));
        writeFile(new File(ctx.getFilesDir(), "homescreen.json"), root.toString());

        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertEquals(1, store.getItems().size());
        assertEquals("com.ok", store.getItems().get(0).getPackageName());
        assertEquals(1, store.screenCount());
    }

    // -------------------------------------------------------------------------
    // Save failure must never damage the live file (M17)
    // -------------------------------------------------------------------------

    @Test
    public void failedRename_leavesLiveFileIntactAndKeepsTmpForRecovery() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.good"));
        store.flush();

        File live = new File(ctx.getFilesDir(), "homescreen.json");
        String lastGood = readFile(live);
        assertTrue(lastGood.contains("com.good"));

        HomeScreenStore.sFailRenameForTesting = true;
        store.addItem(HomeItem.app(0, 1, 0, "com.later"));
        store.flush();
        HomeScreenStore.sFailRenameForTesting = false;

        // The old fallback truncated the live file before rewriting it; if that write also
        // failed the user's whole layout became a zero-byte file.
        assertEquals("a failed save must not touch the live layout", lastGood, readFile(live));
        File tmp = new File(ctx.getFilesDir(), "homescreen.json.tmp");
        assertTrue("the fully-written temp file is the recovery source", tmp.exists());
        assertTrue(readFile(tmp).contains("com.later"));
    }

    @Test
    public void load_zeroByteLiveFile_recoversFromTmp() throws Exception {
        // Exactly the state the old truncating fallback left behind.
        JSONObject root = new JSONObject().put("items",
                new JSONArray().put(HomeItem.app(0, 2, 3, "com.saved").toJson()));
        writeFile(new File(ctx.getFilesDir(), "homescreen.json.tmp"), root.toString());
        assertTrue(new File(ctx.getFilesDir(), "homescreen.json").createNewFile());

        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertEquals(1, store.getItems().size());
        assertEquals("com.saved", store.getItems().get(0).getPackageName());

        // The recovered layout is written back to the live file so the next launch is normal.
        store.flush();
        assertTrue(readFile(new File(ctx.getFilesDir(), "homescreen.json")).contains("com.saved"));
    }

    @Test
    public void writeToFile_keepsPreviousLayoutAsBak() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.first"));
        store.flush();
        store.addItem(HomeItem.app(0, 1, 0, "com.second"));
        store.flush();

        String bak = readFile(new File(ctx.getFilesDir(), "homescreen.json.bak"));
        assertTrue("the .bak holds the layout the last save replaced", bak.contains("com.first"));
        assertFalse(bak.contains("com.second"));
    }

    // -------------------------------------------------------------------------
    // Ghost pruning for uninstalled apps (M1)
    // -------------------------------------------------------------------------

    @Test
    public void removeUnknownApps_prunesAppWhoseInstallIsGone() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.installed"));
        store.addItem(HomeItem.app(0, 1, 0, "com.ghost"));

        assertTrue(store.removeUnknownApps(
                setOf("com.installed"), serialsOf(0L)));

        assertEquals(1, store.getItems().size());
        assertEquals("com.installed", store.getItems().get(0).getPackageName());
        // The freed cell is reusable again — the whole point of the finding.
        assertTrue(store.isAreaFree(0, 1, 0, 1, 1));
    }

    @Test
    public void removeUnknownApps_keepsWorkAppWhenItsProfileIsAbsent() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.work#10"));

        // Serial 10 is not in the valid set: the profile is paused/unavailable and enumerates
        // no apps, which is not evidence the app was uninstalled.
        assertFalse(store.removeUnknownApps(setOf("com.other"), serialsOf(0L)));
        assertEquals(1, store.getItems().size());
    }

    @Test
    public void removeUnknownApps_prunesWorkAppWhenItsProfileIsPresent() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.work#10"));

        assertTrue(store.removeUnknownApps(setOf("com.other#10"), serialsOf(0L, 10L)));
        assertTrue(store.getItems().isEmpty());
    }

    @Test
    public void removeUnknownApps_folderCascade_collapsesToApp() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.folder(0, 1, 2, "F", Arrays.asList("com.here", "com.ghost")));

        assertTrue(store.removeUnknownApps(setOf("com.here"), serialsOf(0L)));

        List<HomeItem> items = store.getItems();
        assertEquals(1, items.size());
        assertEquals(HomeItem.Type.APP, items.get(0).getType());
        assertEquals("com.here", items.get(0).getPackageName());
        assertEquals(1, items.get(0).getCellX());
        assertEquals(2, items.get(0).getCellY());
    }

    @Test
    public void removeUnknownApps_folderCascade_removesEmptyFolder() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.folder(0, 0, 0, "F", Arrays.asList("com.g1", "com.g2")));

        assertTrue(store.removeUnknownApps(setOf("com.unrelated"), serialsOf(0L)));
        assertTrue(store.getItems().isEmpty());
    }

    @Test
    public void removeUnknownApps_emptyKnownSet_isNoOp() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.a"));

        // A failed enumeration must never be read as "everything was uninstalled".
        assertFalse(store.removeUnknownApps(setOf(), serialsOf(0L)));
        assertEquals(1, store.getItems().size());
    }

    @Test
    public void removeUnknownApps_widgetsAreNeverTouched() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.widget(0, 0, 0, 2, 1, 7, "com.w/com.w.W"));

        assertFalse(store.removeUnknownApps(setOf("com.something"), serialsOf(0L)));
        assertEquals(1, store.getItems().size());
    }

    @Test
    public void removeUnknownApps_persistsThePrune() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.ghost"));
        store.addItem(HomeItem.app(0, 1, 0, "com.keep"));
        store.removeUnknownApps(setOf("com.keep"), serialsOf(0L));
        store.flush();

        HomeScreenStore.resetForTesting();
        assertEquals(1, HomeScreenStore.get(ctx).getItems().size());
    }

    // -------------------------------------------------------------------------
    // B1 — the prune at the layer the production caller actually uses.
    //
    // The Set overload lets a test hand in whatever serials it likes, so it proved nothing about
    // the real caller: MainActivity.applyLoadedApps has only the app list, and the Context
    // overload used to re-derive the "valid" serials from UserManager.getUserProfiles(). A
    // paused / quiet / locked / policy-denied work profile is still listed there while
    // enumerating zero apps, so every work icon and folder member on the home screen was deleted
    // and the deletion persisted.
    // -------------------------------------------------------------------------

    @Test
    public void removeUnknownApps_context_keepsWorkItemsWhenTheWorkProfileEnumeratedNothing() {
        // The profile is paused: getUserProfiles() still lists it, getActivityList() returned
        // nothing for it. Being listed is not evidence its apps were uninstalled.
        registerProfile(10, 10L);

        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.work#10"));
        store.addItem(HomeItem.app(0, 1, 0, "com.personal"));

        assertFalse(store.removeUnknownApps(ctx, appsWithKeys("com.personal")));
        assertEquals(2, store.getItems().size());
    }

    @Test
    public void removeUnknownApps_context_keepsWorkFolderMembersWhenThatProfileEnumeratedNothing() {
        registerProfile(10, 10L);

        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.folder(0, 0, 0, "F", Arrays.asList("com.a", "com.work#10")));

        assertFalse(store.removeUnknownApps(ctx, appsWithKeys("com.a")));
        assertEquals(1, store.getItems().size());
        assertEquals(Arrays.asList("com.a", "com.work#10"),
                store.getItems().get(0).getPackageNames());
    }

    @Test
    public void removeUnknownApps_context_keepsPersonalItemsWhenOnlyTheWorkProfileEnumerated() {
        // The mirror image: our own user is the one that failed. A bare key's serial is 0, which
        // used to be treated as unconditionally valid, so the whole personal layout went.
        registerProfile(10, 10L);

        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.personal"));

        assertFalse(store.removeUnknownApps(ctx, appsWithKeys("com.work#10")));
        assertEquals(1, store.getItems().size());
    }

    @Test
    public void removeUnknownApps_context_stillPrunesWorkItemsWhenThatProfileDidEnumerate() {
        // The prune must not be disabled outright: a profile that contributed apps is proof.
        registerProfile(10, 10L);

        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.gone#10"));
        store.addItem(HomeItem.app(0, 1, 0, "com.here#10"));

        assertTrue(store.removeUnknownApps(ctx, appsWithKeys("com.personal", "com.here#10")));
        assertEquals(1, store.getItems().size());
        assertEquals("com.here#10", store.getItems().get(0).getPackageName());
    }

    @Test
    public void removeUnknownApps_context_stillPrunesPersonalGhosts() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.ghost"));
        store.addItem(HomeItem.app(0, 1, 0, "com.installed"));

        assertTrue(store.removeUnknownApps(ctx, appsWithKeys("com.installed")));
        assertEquals(1, store.getItems().size());
        assertEquals("com.installed", store.getItems().get(0).getPackageName());
    }

    // -------------------------------------------------------------------------
    // S2 — M12 changed the key format with no migration; the prune made the loss permanent
    // -------------------------------------------------------------------------

    @Test
    public void removeUnknownApps_context_keepsLegacyOwnUserKeysAfterTheKeyFormatChange() {
        // Before M12, running as a secondary or guest user (serial 10) wrote "pkg#10" for that
        // user's *own* apps. They now load under bare keys, so nothing in the result carries
        // serial 10 — and reading that as "uninstalled" deleted the entire layout on the first
        // reload after upgrade, which is the install M12 exists to fix.
        registerProfile(10, 10L);

        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.a#10"));
        store.addItem(HomeItem.app(0, 1, 0, "com.b#10"));

        assertFalse(store.removeUnknownApps(ctx, appsWithKeys("com.a", "com.b")));
        assertEquals(2, store.getItems().size());
    }

    @Test
    public void migrateOwnUserKeys_rewritesLegacyKeysForOurOwnUser() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.a#10"));
        store.addItem(HomeItem.folder(0, 1, 0, "F", Arrays.asList("com.b#10", "com.w#11")));

        assertTrue(store.migrateOwnUserKeys(10L));

        assertEquals("com.a", store.getItems().get(0).getPackageName());
        assertEquals("a genuine work-profile key must survive untouched",
                Arrays.asList("com.b", "com.w#11"), store.getItems().get(1).getPackageNames());
    }

    @Test
    public void migrateOwnUserKeys_isIdempotent() {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.a#10"));

        assertTrue(store.migrateOwnUserKeys(10L));
        assertFalse("a second pass has nothing left to rewrite", store.migrateOwnUserKeys(10L));
        assertEquals("com.a", store.getItems().get(0).getPackageName());
    }

    @Test
    public void migrateOwnUserKeys_onThePrimaryUserIsANoOp() {
        // Serial 0 already means the bare key, so "#0" is not a form any build ever wrote — and a
        // real work-profile key sitting there must not be mistaken for one.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.work#10"));

        assertFalse(store.migrateOwnUserKeys(0L));
        assertEquals("com.work#10", store.getItems().get(0).getPackageName());
    }

    @Test
    public void migrateOwnUserKeys_keepsGeometryAndPersists() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(HomeItem.DOCK, 2, 0, "com.a#10"));
        assertTrue(store.migrateOwnUserKeys(10L));
        store.flush();

        HomeScreenStore.resetForTesting();
        List<HomeItem> items = HomeScreenStore.get(ctx).getItems();
        assertEquals(1, items.size());
        assertEquals("com.a", items.get(0).getPackageName());
        assertEquals(HomeItem.DOCK, items.get(0).getScreen());
        assertEquals(2, items.get(0).getCellX());
    }

    @Test
    public void migrateOwnUserKeys_collapsesFolderMembersThatCollide() {
        // The dock was seeded with bare names while drags wrote "pkg#10", so the two forms of one
        // app can meet inside a folder; leaving both would render the same app twice.
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.folder(0, 0, 0, "F",
                Arrays.asList("com.a", "com.a#10", "com.b#10")));

        assertTrue(store.migrateOwnUserKeys(10L));
        assertEquals(Arrays.asList("com.a", "com.b"),
                store.getItems().get(0).getPackageNames());
    }

    // -------------------------------------------------------------------------
    // Schema version and forward compatibility (L1)
    // -------------------------------------------------------------------------

    @Test
    public void save_writesFormatVersion() throws Exception {
        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 0, 0, "com.a"));
        store.flush();

        JSONObject root = new JSONObject(
                readFile(new File(ctx.getFilesDir(), "homescreen.json")));
        assertEquals(1, root.getInt("version"));
        assertEquals(1, root.getJSONArray("items").length());
    }

    @Test
    public void load_unknownTypeEntry_isReEmittedOnTheNextSave() throws Exception {
        // A newer build wrote a type this one has never heard of. Dropping it at load is fine —
        // it cannot be rendered — but the next full-file rewrite must not erase it from disk.
        JSONObject unknown = new JSONObject();
        unknown.put("type", "UNKNOWN_FUTURE_TYPE");
        unknown.put("screen", 0); unknown.put("cellX", 3); unknown.put("cellY", 0);
        unknown.put("spanX", 1);  unknown.put("spanY", 1);
        JSONObject root = new JSONObject()
                .put("version", 2)
                .put("items", new JSONArray()
                        .put(HomeItem.app(0, 0, 0, "com.first").toJson())
                        .put(unknown));
        File f = new File(ctx.getFilesDir(), "homescreen.json");
        writeFile(f, root.toString());

        HomeScreenStore store = HomeScreenStore.get(ctx);
        assertEquals(1, store.getItems().size());

        store.addItem(HomeItem.app(0, 1, 0, "com.second"));
        store.flush();

        JSONArray arr = new JSONObject(readFile(f)).getJSONArray("items");
        boolean preserved = false;
        for (int i = 0; i < arr.length(); i++) {
            if ("UNKNOWN_FUTURE_TYPE".equals(arr.getJSONObject(i).optString("type"))) {
                preserved = true;
            }
        }
        assertTrue("unrecognised entries must be re-emitted verbatim, not erased", preserved);
        assertEquals(3, arr.length());
    }

    @Test
    public void malformedEntry_isNotMistakenForAFutureType() throws Exception {
        // Missing required fields on a known type is corruption, not forward compatibility —
        // it must not be resurrected on the next save.
        JSONObject bad = new JSONObject();
        bad.put("type", "APP");
        JSONObject root = new JSONObject().put("items", new JSONArray()
                .put(HomeItem.app(0, 0, 0, "com.good").toJson()).put(bad));
        File f = new File(ctx.getFilesDir(), "homescreen.json");
        writeFile(f, root.toString());

        HomeScreenStore store = HomeScreenStore.get(ctx);
        store.addItem(HomeItem.app(0, 1, 0, "com.new"));
        store.flush();

        assertEquals(2, new JSONObject(readFile(f)).getJSONArray("items").length());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static HomeItem itemOfType(List<HomeItem> items, HomeItem.Type type) {
        for (HomeItem item : items) if (item.getType() == type) return item;
        fail("No item of type " + type + " found");
        return null;
    }

    /** Minimal valid backup envelope wrapping the given item array. */
    private static JSONObject backupRoot(JSONArray items) throws Exception {
        return new JSONObject().put("version", 1).put("items", items);
    }

    private static void writeFile(File f, String content) throws Exception {
        try (FileWriter fw = new FileWriter(f)) { fw.write(content); }
    }

    private static String readFile(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static java.util.Set<String> setOf(String... keys) {
        return new java.util.HashSet<>(Arrays.asList(keys));
    }

    private static java.util.Set<Long> serialsOf(Long... serials) {
        return new java.util.HashSet<>(Arrays.asList(serials));
    }

    /** Makes {@code UserManager.getUserProfiles()} report a second profile with this serial. */
    private void registerProfile(int userId, long serial) {
        UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        ShadowUserManager shadow = Shadows.shadowOf(um);
        UserHandle handle = UserHandle.getUserHandleForUid(userId * 100000);
        shadow.addUserProfile(handle);
        shadow.setSerialNumberForUser(handle, serial);
    }

    /** An app list as {@code AppLoader} would deliver it — only the key matters here. */
    private static List<AppInfo> appsWithKeys(String... keys) {
        List<AppInfo> apps = new ArrayList<>();
        for (String key : keys) apps.add(new AppInfo(key, key, null));
        return apps;
    }
}
