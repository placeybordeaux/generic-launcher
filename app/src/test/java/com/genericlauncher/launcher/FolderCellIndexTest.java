package com.genericlauncher.launcher;

import android.content.Context;
import android.view.View;
import android.widget.GridLayout;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * {@link HomescreenView#cellIndexAt} — the drop-point-to-cell mapping behind drag-to-reorder
 * inside a folder.
 *
 * <p>{@code reorderFolder} is already covered at the model layer; this is the view half that
 * decides <em>which</em> index a drop lands on. An off-by-one here reorders the wrong pair of
 * icons, which looks like a broken feature rather than a bug.
 *
 * <p>Grid is 4 columns x 2 rows of 100x100 cells, matching the folder popup's shape.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FolderCellIndexTest {

    private static final int COLS = 4, ROWS = 2, CELL = 100;

    private GridLayout grid;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        grid = new GridLayout(ctx);
        grid.setColumnCount(COLS);
        for (int i = 0; i < COLS * ROWS; i++) {
            View cell = new View(ctx);
            grid.addView(cell);
            // Robolectric does not run GridLayout's measure pass here, so place the children
            // explicitly at the positions a laid-out grid would give them.
            int row = i / COLS, col = i % COLS;
            cell.layout(col * CELL, row * CELL, (col + 1) * CELL, (row + 1) * CELL);
        }
    }

    @Test
    public void everyCellMapsToItsOwnIndexWhenHitAtItsCentre() {
        for (int i = 0; i < COLS * ROWS; i++) {
            float cx = (i % COLS) * CELL + CELL / 2f;
            float cy = (i / COLS) * CELL + CELL / 2f;
            assertEquals("center of cell " + i, i, HomescreenView.cellIndexAt(grid, cx, cy));
        }
    }

    @Test
    public void theFourthColumnIsReachable() {
        // The same column that was being clipped off the card — it must still take drops.
        assertEquals(3, HomescreenView.cellIndexAt(grid, 3 * CELL + 50f, 50f));
        assertEquals(7, HomescreenView.cellIndexAt(grid, 3 * CELL + 50f, CELL + 50f));
    }

    @Test
    public void cellBoundsAreHalfOpenSoNeighboursDoNotOverlap() {
        assertEquals(0, HomescreenView.cellIndexAt(grid, 0f, 0f));            // top-left corner
        assertEquals(0, HomescreenView.cellIndexAt(grid, CELL - 1f, CELL - 1f));
        assertEquals(1, HomescreenView.cellIndexAt(grid, CELL, 0f));          // first px of cell 1
        assertEquals(COLS, HomescreenView.cellIndexAt(grid, 0f, CELL));       // first px of row 1
    }

    @Test
    public void pointsOutsideTheGridReturnNoCell() {
        assertEquals(-1, HomescreenView.cellIndexAt(grid, -1f, 50f));
        assertEquals(-1, HomescreenView.cellIndexAt(grid, 50f, -1f));
        assertEquals(-1, HomescreenView.cellIndexAt(grid, COLS * CELL, 50f));
        assertEquals(-1, HomescreenView.cellIndexAt(grid, 50f, ROWS * CELL));
    }

    @Test
    public void aPartialLastRowDoesNotMapPastTheEnd() {
        // 5 members in a 4-wide grid: dropping in the empty part of row 1 must return -1, not an
        // index the caller would then use to reorder.
        Context ctx = ApplicationProvider.getApplicationContext();
        GridLayout partial = new GridLayout(ctx);
        partial.setColumnCount(COLS);
        for (int i = 0; i < 5; i++) {
            View cell = new View(ctx);
            partial.addView(cell);
            int row = i / COLS, col = i % COLS;
            cell.layout(col * CELL, row * CELL, (col + 1) * CELL, (row + 1) * CELL);
        }
        assertEquals(4, HomescreenView.cellIndexAt(partial, 50f, CELL + 50f));       // filled
        assertEquals(-1, HomescreenView.cellIndexAt(partial, CELL + 50f, CELL + 50f)); // empty
    }

    @Test
    public void anEmptyGridReturnsNoCell() {
        Context ctx = ApplicationProvider.getApplicationContext();
        assertEquals(-1, HomescreenView.cellIndexAt(new GridLayout(ctx), 10f, 10f));
    }
}
