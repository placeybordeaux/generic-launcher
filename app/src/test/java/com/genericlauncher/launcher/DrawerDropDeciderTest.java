package com.genericlauncher.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * Unit tests for {@link DrawerDropDecider#decide} — the pure, Android-free helper that maps a
 * (zone, occupant, incomingPackage) triple to a drop action.
 *
 * <p>Covers all four meaningful outcomes and key boundary conditions:
 * <ul>
 *   <li>ADD — empty cell in GRID or DOCK</li>
 *   <li>FOLDER_MERGE — APP dropped onto a different APP</li>
 *   <li>FOLDER_APPEND — APP dropped onto a FOLDER</li>
 *   <li>REJECT — invalid targets: same-package APP, WIDGET, REMOVE zone, OUTSIDE</li>
 * </ul>
 */
public class DrawerDropDeciderTest {

    // -------------------------------------------------------------------------
    // ADD — empty cell
    // -------------------------------------------------------------------------

    @Test
    public void addOnEmptyGridCell() {
        // Dropping onto an empty grid cell should always ADD.
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.GRID,
                /*occupant=*/ null,
                "com.example.app");
        assertEquals(DrawerDropDecider.Action.ADD, action);
    }

    @Test
    public void addOnEmptyDockSlot() {
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.DOCK,
                /*occupant=*/ null,
                "com.example.app");
        assertEquals(DrawerDropDecider.Action.ADD, action);
    }

    // -------------------------------------------------------------------------
    // FOLDER_MERGE — APP onto a different APP
    // -------------------------------------------------------------------------

    @Test
    public void folderMergeWhenDroppingOntoAnotherApp() {
        HomeItem existing = HomeItem.app(0, 0, 0, "com.example.other");
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.GRID,
                existing,
                "com.example.app");
        assertEquals(DrawerDropDecider.Action.FOLDER_MERGE, action);
    }

    @Test
    public void folderMergeInDockWhenDroppingOntoAnotherApp() {
        HomeItem existing = HomeItem.app(HomeItem.DOCK, 0, 0, "com.example.other");
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.DOCK,
                existing,
                "com.example.app");
        assertEquals(DrawerDropDecider.Action.FOLDER_MERGE, action);
    }

    // -------------------------------------------------------------------------
    // FOLDER_APPEND — APP onto a FOLDER
    // -------------------------------------------------------------------------

    @Test
    public void folderAppendWhenDroppingOntoFolder() {
        HomeItem folder = HomeItem.folder(0, 1, 2, "Stuff",
                Arrays.asList("com.example.a", "com.example.b"));
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.GRID,
                folder,
                "com.example.new");
        assertEquals(DrawerDropDecider.Action.FOLDER_APPEND, action);
    }

    @Test
    public void folderAppendInDockWhenDroppingOntoFolder() {
        HomeItem folder = HomeItem.folder(HomeItem.DOCK, 0, 0, "Dock Folder",
                Arrays.asList("com.example.a", "com.example.b"));
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.DOCK,
                folder,
                "com.example.new");
        assertEquals(DrawerDropDecider.Action.FOLDER_APPEND, action);
    }

    // -------------------------------------------------------------------------
    // REJECT — invalid targets
    // -------------------------------------------------------------------------

    @Test
    public void rejectWhenDroppingOntoSamePackage() {
        // Dropping an app onto itself should snap back.
        HomeItem existing = HomeItem.app(0, 0, 0, "com.example.app");
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.GRID,
                existing,
                "com.example.app");
        assertEquals(DrawerDropDecider.Action.REJECT, action);
    }

    @Test
    public void rejectWhenDroppingOntoWidget() {
        // Cannot merge an incoming drawer app onto a widget.
        HomeItem widget = HomeItem.widget(0, 0, 0, 2, 2, 42, "com.example/.Widget");
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.GRID,
                widget,
                "com.example.app");
        assertEquals(DrawerDropDecider.Action.REJECT, action);
    }

    @Test
    public void rejectOnRemoveZone() {
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.REMOVE,
                /*occupant=*/ null,
                "com.example.app");
        assertEquals(DrawerDropDecider.Action.REJECT, action);
    }

    @Test
    public void rejectOnOutsideZone() {
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.OUTSIDE,
                /*occupant=*/ null,
                "com.example.app");
        assertEquals(DrawerDropDecider.Action.REJECT, action);
    }

    @Test
    public void rejectOnOutsideZoneEvenWithOccupant() {
        // Zone takes priority — OUTSIDE is always REJECT regardless of what's "there".
        HomeItem existing = HomeItem.app(0, 0, 0, "com.example.other");
        DrawerDropDecider.Action action = DrawerDropDecider.decide(
                HomescreenView.DropTarget.Zone.OUTSIDE,
                existing,
                "com.example.app");
        assertEquals(DrawerDropDecider.Action.REJECT, action);
    }
}
