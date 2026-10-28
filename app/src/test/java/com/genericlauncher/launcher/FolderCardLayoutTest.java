package com.genericlauncher.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HomescreenView#folderCellWidth} — the sizing rule that keeps the last
 * column of the folder popup's member grid inside the card.
 *
 * <p>Regression origin: a fixed 72 dp cell with 2 dp side margins in a fixed 300 dp card with
 * 16 dp padding needed 4 × 76 = 304 dp of a 268 dp content box, so the fourth icon in every row
 * was clipped on a Pixel 9a.
 */
public class FolderCardLayoutTest {

    /** Total width the grid needs for {@code cols} cells of {@code cellW} plus their margins. */
    private static int gridWidth(int cellW, int cols, int marginPx) {
        return cols * (cellW + 2 * marginPx);
    }

    @Test
    public void cellsPlusMargins_fitInsideTheCardContentBox() {
        int cardW = 945, pad = 42, cols = 4, margin = 5; // ~360/16/2 dp at density 2.625
        int cellW = HomescreenView.folderCellWidth(cardW, pad, cols, margin);

        assertTrue("grid must fit the content box",
                gridWidth(cellW, cols, margin) <= cardW - 2 * pad);
    }

    @Test
    public void fitsAcrossAPlausibleRangeOfCardWidths() {
        int pad = 42, cols = 4, margin = 5;
        for (int cardW = 300; cardW <= 1200; cardW += 7) {
            int cellW = HomescreenView.folderCellWidth(cardW, pad, cols, margin);
            assertTrue("overflow at cardW=" + cardW,
                    gridWidth(cellW, cols, margin) <= cardW - 2 * pad);
        }
    }

    @Test
    public void usesMostOfTheAvailableWidth() {
        // Not just "fits" — a degenerate 1 px cell would also fit. The grid should fill the box
        // to within one column's worth of rounding.
        int cardW = 945, pad = 42, cols = 4, margin = 5;
        int content = cardW - 2 * pad;
        int used    = gridWidth(HomescreenView.folderCellWidth(cardW, pad, cols, margin),
                                cols, margin);
        assertTrue("used " + used + " of " + content, used >= content - cols);
    }

    @Test
    public void theOldHardcodedSizing_reallyDidOverflow() {
        // Pins the bug this rule exists to prevent, so the "fits" assertions above are known to
        // have teeth. Pixel 9a density 2.625: card 300dp=787px, pad 16dp=42px, cell 72dp=189px,
        // margin 2dp=5px. 4 × (189 + 10) = 796px of cells in 787 − 84 = 703px of content box.
        int oldCardW = 787, pad = 42, cols = 4, margin = 5, oldCellW = 189;
        assertTrue("the reported bug: the 4th column overflowed the card",
                gridWidth(oldCellW, cols, margin) > oldCardW - 2 * pad);

        // The derived width does not.
        int cellW = HomescreenView.folderCellWidth(oldCardW, pad, cols, margin);
        assertTrue(gridWidth(cellW, cols, margin) <= oldCardW - 2 * pad);
    }

    @Test
    public void narrowCard_stillReturnsAPositiveWidth() {
        // Padding and margins alone exceed the card: clamp rather than return 0 or negative.
        assertTrue(HomescreenView.folderCellWidth(60, 42, 4, 5) >= 1);
    }

    @Test
    public void zeroColumns_returnsZero() {
        assertEquals(0, HomescreenView.folderCellWidth(945, 42, 0, 5));
    }
}
