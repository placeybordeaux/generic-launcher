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
 * Unit tests for {@link HomeScreenStore#reorderFolder} — the model half of drag-to-reorder
 * inside the folder popup.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FolderReorderTest {

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

    private HomeItem folderOf(String... pkgs) {
        HomeItem folder = HomeItem.folder(0, 0, 0, "Folder", Arrays.asList(pkgs));
        store.addItem(folder);
        return folder;
    }

    @Test
    public void moveForward_shiftsInterveningMembersBack() {
        HomeItem f = folderOf("a", "b", "c", "d");
        assertTrue(store.reorderFolder(f, 0, 2));
        assertEquals(Arrays.asList("b", "c", "a", "d"), f.getPackageNames());
    }

    @Test
    public void moveBackward_shiftsInterveningMembersForward() {
        HomeItem f = folderOf("a", "b", "c", "d");
        assertTrue(store.reorderFolder(f, 3, 1));
        assertEquals(Arrays.asList("a", "d", "b", "c"), f.getPackageNames());
    }

    @Test
    public void moveToLastIndex_isAllowed() {
        HomeItem f = folderOf("a", "b", "c");
        assertTrue(store.reorderFolder(f, 0, 2));
        assertEquals(Arrays.asList("b", "c", "a"), f.getPackageNames());
    }

    @Test
    public void sameIndex_isNoOp() {
        HomeItem f = folderOf("a", "b", "c");
        assertFalse(store.reorderFolder(f, 1, 1));
        assertEquals(Arrays.asList("a", "b", "c"), f.getPackageNames());
    }

    @Test
    public void outOfRangeIndices_areIgnored() {
        HomeItem f = folderOf("a", "b", "c");
        assertFalse(store.reorderFolder(f, -1, 1));
        assertFalse(store.reorderFolder(f, 1, 3));
        assertFalse(store.reorderFolder(f, 5, 0));
        assertEquals(Arrays.asList("a", "b", "c"), f.getPackageNames());
    }

    @Test
    public void nonFolderItem_isIgnored() {
        HomeItem app = HomeItem.app(0, 1, 1, "a");
        store.addItem(app);
        assertFalse(store.reorderFolder(app, 0, 0));
    }

    @Test
    public void reorderSurvivesReload() {
        HomeItem f = folderOf("a", "b", "c", "d");
        store.reorderFolder(f, 0, 3);

        store.flush();
        HomeScreenStore.resetForTesting();
        HomeScreenStore reloaded = HomeScreenStore.get(ctx);

        List<HomeItem> items = reloaded.getItems();
        assertEquals(1, items.size());
        assertEquals(Arrays.asList("b", "c", "d", "a"), items.get(0).getPackageNames());
    }
}
