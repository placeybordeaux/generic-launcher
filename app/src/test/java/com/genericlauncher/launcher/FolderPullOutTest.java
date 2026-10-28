package com.genericlauncher.launcher;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Dragging an app out of an open folder and dropping it on the home screen.
 *
 * <p>Covers the two halves that decide whether the gesture works at all: the geometry that says
 * the finger has left the folder card ({@link HomescreenView#isPullOut}), and the store mutation
 * the drop commits ({@link HomescreenView#commitIncomingDrop}). Neither needs a live drag — the
 * framework's drag dispatch is what carries the pointer between them, and that is the one part
 * a Robolectric test cannot exercise.
 *
 * <p>The invariant every drop case here is really asserting: the member leaves its folder if and
 * only if it landed somewhere. A refused drop must leave the folder exactly as it was.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FolderPullOutTest {

    private Context ctx;
    private HomeScreenStore store;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        HomeScreenStore.resetForTesting();
        new File(ctx.getFilesDir(), "homescreen.json").delete();
        new File(ctx.getFilesDir(), "homescreen.json.tmp").delete();
        store = HomeScreenStore.get(ctx);
    }

    private HomeItem folderAt(int screen, int cellX, int cellY, String... pkgs) {
        HomeItem folder = HomeItem.folder(screen, cellX, cellY, "Folder", Arrays.asList(pkgs));
        store.addItem(folder);
        return folder;
    }

    private static HomescreenView.DropTarget grid(int page, int cellX, int cellY) {
        return new HomescreenView.DropTarget(
                HomescreenView.DropTarget.Zone.GRID, page, cellX, cellY);
    }

    private static HomescreenView.DropTarget dock(int slot) {
        return new HomescreenView.DropTarget(
                HomescreenView.DropTarget.Zone.DOCK, -1, slot, 0);
    }

    private static HomescreenView.DropTarget zone(HomescreenView.DropTarget.Zone z) {
        return new HomescreenView.DropTarget(z, -1, -1, -1);
    }

    // -------------------------------------------------------------------------
    // isPullOut — "the finger has left the card"
    // -------------------------------------------------------------------------

    @Test
    public void insideTheCard_isNotAPullOut() {
        assertFalse(HomescreenView.isPullOut(100, 200, 400, 600, 250f, 400f, 8));
    }

    @Test
    public void justOutsideTheCard_isStillNotAPullOut() {
        // Within slop of every edge: a member dragged along the card's own border must not
        // flicker out of the folder, because the pull-out is one-way.
        assertFalse(HomescreenView.isPullOut(100, 200, 400, 600,  93f, 400f, 8));
        assertFalse(HomescreenView.isPullOut(100, 200, 400, 600, 407f, 400f, 8));
        assertFalse(HomescreenView.isPullOut(100, 200, 400, 600, 250f, 193f, 8));
        assertFalse(HomescreenView.isPullOut(100, 200, 400, 600, 250f, 607f, 8));
    }

    @Test
    public void pastSlopOnAnyEdge_isAPullOut() {
        assertTrue(HomescreenView.isPullOut(100, 200, 400, 600,  80f, 400f, 8));
        assertTrue(HomescreenView.isPullOut(100, 200, 400, 600, 420f, 400f, 8));
        assertTrue(HomescreenView.isPullOut(100, 200, 400, 600, 250f, 180f, 8));
        assertTrue(HomescreenView.isPullOut(100, 200, 400, 600, 250f, 620f, 8));
    }

    // -------------------------------------------------------------------------
    // incomingAction — the source folder is not a drop target for its own member
    // -------------------------------------------------------------------------

    @Test
    public void droppingBackOntoTheSourceFolder_isRejected() {
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");
        assertEquals(DrawerDropDecider.Action.REJECT,
                HomescreenView.incomingAction(
                        HomescreenView.DropTarget.Zone.GRID, source, "a", source));
    }

    @Test
    public void droppingOntoAnotherFolder_stillAppends() {
        HomeItem source = folderAt(0, 0, 0, "a", "b");
        HomeItem other  = folderAt(0, 1, 0, "c", "d");
        assertEquals(DrawerDropDecider.Action.FOLDER_APPEND,
                HomescreenView.incomingAction(
                        HomescreenView.DropTarget.Zone.GRID, other, "a", source));
    }

    @Test
    public void withNoSourceFolder_isTheDeciderVerbatim() {
        // A drawer drag passes sourceFolder = null and must be unaffected by any of this.
        HomeItem folder = folderAt(0, 0, 0, "a", "b");
        assertEquals(DrawerDropDecider.Action.FOLDER_APPEND,
                HomescreenView.incomingAction(
                        HomescreenView.DropTarget.Zone.GRID, folder, "z", null));
    }

    // -------------------------------------------------------------------------
    // commitIncomingDrop — pull-out placements
    // -------------------------------------------------------------------------

    @Test
    public void dropOnEmptyCell_movesTheMemberOutOfTheFolder() {
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");

        assertTrue(HomescreenView.commitIncomingDrop(store, grid(0, 2, 1), "a", source));

        assertEquals(Arrays.asList("b", "c"), source.getPackageNames());
        HomeItem moved = store.itemAt(0, 2, 1);
        assertNotNull("the member is now a home-screen app", moved);
        assertEquals(HomeItem.Type.APP, moved.getType());
        assertEquals("a", moved.getPackageName());
    }

    @Test
    public void dropOnEmptyDockSlot_movesTheMemberToTheDock() {
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");

        assertTrue(HomescreenView.commitIncomingDrop(store, dock(3), "c", source));

        assertEquals(Arrays.asList("a", "b"), source.getPackageNames());
        HomeItem moved = store.itemAt(HomeItem.DOCK, 3, 0);
        assertNotNull(moved);
        assertEquals(HomeItem.Type.APP, moved.getType());
        assertEquals("c", moved.getPackageName());
    }

    /**
     * The cascade that makes emptying a folder work at all: pulling the second-to-last member out
     * leaves one behind, and a one-member folder collapses to a plain app at the folder's cell.
     */
    @Test
    public void pullingFromATwoMemberFolder_collapsesItToAnApp() {
        HomeItem source = folderAt(0, 1, 1, "a", "b");

        assertTrue(HomescreenView.commitIncomingDrop(store, grid(0, 3, 2), "a", source));

        HomeItem collapsed = store.itemAt(0, 1, 1);
        assertNotNull("the folder's cell still holds the remaining member", collapsed);
        assertEquals(HomeItem.Type.APP, collapsed.getType());
        assertEquals("b", collapsed.getPackageName());
        assertEquals("a", store.itemAt(0, 3, 2).getPackageName());
        assertEquals(2, store.getItems().size());
    }

    @Test
    public void dropOnAnotherApp_mergesIntoANewFolderAndLeavesTheSource() {
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");
        store.addItem(HomeItem.app(0, 2, 0, "z"));

        assertTrue(HomescreenView.commitIncomingDrop(store, grid(0, 2, 0), "a", source));

        assertEquals(Arrays.asList("b", "c"), source.getPackageNames());
        HomeItem merged = store.itemAt(0, 2, 0);
        assertEquals(HomeItem.Type.FOLDER, merged.getType());
        assertEquals(Arrays.asList("z", "a"), merged.getPackageNames());
    }

    @Test
    public void dropOnAnotherFolder_appendsAndLeavesTheSource() {
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");
        HomeItem other  = folderAt(0, 2, 0, "y", "z");

        assertTrue(HomescreenView.commitIncomingDrop(store, grid(0, 2, 0), "a", source));

        assertEquals(Arrays.asList("b", "c"), source.getPackageNames());
        assertEquals(Arrays.asList("y", "z", "a"), other.getPackageNames());
    }

    @Test
    public void dropBackOntoTheSourceFolder_changesNothing() {
        // The rule commitIncomingDrop's removal depends on: without the same-folder reject the
        // append would no-op on the duplicate guard and the removal would then delete the member
        // from the only folder it was ever in.
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");

        assertFalse(HomescreenView.commitIncomingDrop(store, grid(0, 0, 0), "a", source));

        assertEquals(Arrays.asList("a", "b", "c"), source.getPackageNames());
        assertEquals(1, store.getItems().size());
    }

    @Test
    public void dropOnAWidget_isRefusedAndLeavesTheFolderIntact() {
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");
        store.addItem(HomeItem.widget(0, 1, 0, 2, 2, 7, "com.x/.W"));

        assertFalse(HomescreenView.commitIncomingDrop(store, grid(0, 1, 0), "a", source));

        assertEquals(Arrays.asList("a", "b", "c"), source.getPackageNames());
    }

    @Test
    public void dropOutsideAnyZone_isRefusedAndLeavesTheFolderIntact() {
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");

        assertFalse(HomescreenView.commitIncomingDrop(
                store, zone(HomescreenView.DropTarget.Zone.OUTSIDE), "a", source));

        assertEquals(Arrays.asList("a", "b", "c"), source.getPackageNames());
        assertEquals(1, store.getItems().size());
    }

    // -------------------------------------------------------------------------
    // The Remove pill
    // -------------------------------------------------------------------------

    @Test
    public void dropOnRemove_takesTheMemberOutOfTheFolderEntirely() {
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");

        assertTrue(HomescreenView.commitIncomingDrop(
                store, zone(HomescreenView.DropTarget.Zone.REMOVE), "a", source));

        assertEquals(Arrays.asList("b", "c"), source.getPackageNames());
        assertEquals("nothing was added to the home screen", 1, store.getItems().size());
    }

    @Test
    public void dropOnRemove_withoutASourceFolder_isRefused() {
        // A drawer app was never on the home screen; Remove has nothing to mean for it.
        store.addItem(HomeItem.app(0, 0, 0, "a"));

        assertFalse(HomescreenView.commitIncomingDrop(
                store, zone(HomescreenView.DropTarget.Zone.REMOVE), "z", null));

        assertEquals(1, store.getItems().size());
    }

    // -------------------------------------------------------------------------
    // The drawer path must be untouched by all of the above
    // -------------------------------------------------------------------------

    @Test
    public void drawerDrop_onEmptyCell_stillAdds() {
        assertTrue(HomescreenView.commitIncomingDrop(store, grid(0, 1, 1), "z", null));

        HomeItem added = store.itemAt(0, 1, 1);
        assertNotNull(added);
        assertEquals("z", added.getPackageName());
    }

    @Test
    public void drawerDrop_ontoAnApp_stillCreatesAFolder() {
        store.addItem(HomeItem.app(0, 1, 1, "a"));

        assertTrue(HomescreenView.commitIncomingDrop(store, grid(0, 1, 1), "z", null));

        HomeItem folder = store.itemAt(0, 1, 1);
        assertEquals(HomeItem.Type.FOLDER, folder.getType());
        assertEquals(Arrays.asList("a", "z"), folder.getPackageNames());
    }

    @Test
    public void drawerDrop_ontoTheSamePackage_isStillRejected() {
        store.addItem(HomeItem.app(0, 1, 1, "a"));

        assertFalse(HomescreenView.commitIncomingDrop(store, grid(0, 1, 1), "a", null));

        assertEquals(HomeItem.Type.APP, store.itemAt(0, 1, 1).getType());
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    @Test
    public void pullOutSurvivesReload() {
        HomeItem source = folderAt(0, 0, 0, "a", "b", "c");
        HomescreenView.commitIncomingDrop(store, grid(0, 2, 2), "b", source);

        store.flush();
        HomeScreenStore.resetForTesting();
        HomeScreenStore reloaded = HomeScreenStore.get(ctx);

        List<HomeItem> items = reloaded.getItems();
        assertEquals(2, items.size());
        HomeItem folder = reloaded.itemAt(0, 0, 0);
        assertEquals(HomeItem.Type.FOLDER, folder.getType());
        assertEquals(Arrays.asList("a", "c"), folder.getPackageNames());
        assertEquals("b", reloaded.itemAt(0, 2, 2).getPackageName());
    }
}
