package com.genericlauncher.launcher;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HomescreenView.DropTarget#resolve} — the shared point→zone/cell helper
 * used by both the drag-target preview (handleDragLocation) and the drop commit (handleDrop).
 *
 * <p>Test geometry:
 * <ul>
 *   <li>pageWidth = 400, pageHeight = 800, cols = 4, rows = 5 → cellW = 100, cellH = 160</li>
 *   <li>dockTopPx = 900, dockBotPx = 964 (64 dp dock zone)</li>
 *   <li>dockMarginPx = 12 → dockW = 376, slotW = 94 (4 slots)</li>
 *   <li>removeCenterX = 200, removeTop = 16, removeBot = 66, removeHalfW = 80</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DropTargetTest {

    // -------------------------------------------------------------------------
    // Shared test geometry
    // -------------------------------------------------------------------------

    private static final int PAGE_W    = 400;
    /**
     * Non-zero on purpose. The grid ships with CellLayout.PAGE_PAD_DP of horizontal padding, so a
     * fixture that passed 0 here would be testing a configuration that never runs — the same shape
     * of blind spot that let two widget-resize bugs sit green for months (see CLAUDE.md).
     * With PAGE_W=400, COLS=4 and this pad, a cell is (400 - 2*16)/4 = 92px and column 0 starts
     * at x=16.
     */
    private static final int PAGE_PAD  = 16;
    private static final int PAGE_H    = 800;
    private static final int COLS      = 4;
    private static final int ROWS      = 5;
    // cellW = 100, cellH = 160
    private static final int DOCK_TOP  = 900;
    private static final int DOCK_BOT  = 964;
    private static final int DOCK_MRG  = 12;
    // dockW = 376, each slot ~94 px wide

    // Single "Remove" pill, 160 px wide, centred at x=200 → hit range [120, 280] on y 16..66.
    private static final float BAR_CX    = 200f;
    private static final float BAR_TOP   = 16f;
    private static final float BAR_BOT   = 66f;
    private static final float BAR_PILL  = 160f;
    private static final float BAR_GAP   = 8f;
    private static final int   GRID_TOP  = 0;

    private static HomescreenView.DropTarget.ActionBar bar(
            HomescreenView.DropTarget.Zone... zones) {
        return new HomescreenView.DropTarget.ActionBar(
                zones, BAR_CX, BAR_TOP, BAR_BOT, BAR_PILL, BAR_GAP);
    }

    private static HomescreenView.DropTarget.ActionBar removeOnly() {
        return bar(HomescreenView.DropTarget.Zone.REMOVE);
    }

    // Convenience overload: spanX=1, spanY=1, scrollX=0.
    private HomescreenView.DropTarget resolve(float x, float y) {
        return HomescreenView.DropTarget.resolve(
                x, y,
                /*scrollX=*/ 0, PAGE_W, PAGE_PAD, GRID_TOP, PAGE_H,
                COLS, ROWS, /*spanX=*/ 1, /*spanY=*/ 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                removeOnly());
    }

    // -------------------------------------------------------------------------
    // Remove zone
    // -------------------------------------------------------------------------

    @Test
    public void removeZone_centreHit() {
        HomescreenView.DropTarget t = resolve(200f, 40f);
        assertEquals(HomescreenView.DropTarget.Zone.REMOVE, t.zone);
    }

    @Test
    public void removeZone_leftEdgeHit() {
        // x = removeCenterX - removeHalfW = 120
        HomescreenView.DropTarget t = resolve(120f, 40f);
        assertEquals(HomescreenView.DropTarget.Zone.REMOVE, t.zone);
    }

    @Test
    public void removeZone_rightEdgeHit() {
        // x = removeCenterX + removeHalfW = 280
        HomescreenView.DropTarget t = resolve(280f, 40f);
        assertEquals(HomescreenView.DropTarget.Zone.REMOVE, t.zone);
    }

    @Test
    public void removeZone_outsideX_fallsToGrid() {
        // x = 281 > removeCenterX + removeHalfW → not remove zone; y=40 < dockTop → grid
        HomescreenView.DropTarget t = resolve(281f, 40f);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
    }

    @Test
    public void removeZone_outsideY_fallsToGrid() {
        // y = 67 > removeBot → not remove zone
        HomescreenView.DropTarget t = resolve(200f, 67f);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
    }

    // -------------------------------------------------------------------------
    // Dock zone
    // -------------------------------------------------------------------------

    @Test
    public void dockZone_slot0() {
        // x = dockMrg + 0 = 12 → localX=0, slot = max(0, min(3, 0*4/376)) = 0
        HomescreenView.DropTarget t = resolve(12f, 930f);
        assertEquals(HomescreenView.DropTarget.Zone.DOCK, t.zone);
        assertEquals(0, t.cellX);
    }

    @Test
    public void dockZone_slot1_midSlot() {
        // dockW=376, slotW=94. Slot 1 centre: dockMrg + 1*94 + 47 = 12+94+47 = 153
        // slot = (153-12)*4/376 = 141*4/376 = 564/376 = 1 (truncated) → slot 1
        HomescreenView.DropTarget t = resolve(153f, 950f);
        assertEquals(HomescreenView.DropTarget.Zone.DOCK, t.zone);
        assertEquals(1, t.cellX);
    }

    @Test
    public void dockZone_slot3_rightEdge() {
        // Rightmost slot: x = dockMrg + dockW - 1 = 387
        HomescreenView.DropTarget t = resolve(387f, 950f);
        assertEquals(HomescreenView.DropTarget.Zone.DOCK, t.zone);
        assertEquals(3, t.cellX);
    }

    @Test
    public void dockZone_leftMargin_isOutside() {
        // x = 11 < dockMarginPx=12 → localX < 0 → OUTSIDE
        HomescreenView.DropTarget t = resolve(11f, 930f);
        assertEquals(HomescreenView.DropTarget.Zone.OUTSIDE, t.zone);
    }

    @Test
    public void dockZone_aboveDock_isGrid() {
        // y = 899 < dockTopPx=900 → falls through to grid
        HomescreenView.DropTarget t = resolve(200f, 899f);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
    }

    @Test
    public void dockZone_belowDock_isGrid() {
        // y = 965 > dockBotPx=964 → falls through to grid, y/cellH=965/160=6 but clamped to rows-1=4
        HomescreenView.DropTarget t = resolve(200f, 965f);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
    }

    // -------------------------------------------------------------------------
    // Grid zone — basic cell resolution
    // -------------------------------------------------------------------------

    @Test
    public void grid_topLeftCell() {
        // x=0, y=0 → page=0, cellX=0, cellY=0
        HomescreenView.DropTarget t = resolve(0f, 0f);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(0, t.page);
        assertEquals(0, t.cellX);
        assertEquals(0, t.cellY);
    }

    @Test
    public void grid_midCell() {
        // x=150 → col=150/100=1; y=240 → row=240/160=1
        HomescreenView.DropTarget t = resolve(150f, 240f);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(0, t.page);
        assertEquals(1, t.cellX);
        assertEquals(1, t.cellY);
    }

    @Test
    public void grid_rightmostCell_lastRow() {
        // x=380 → col=380/100=3; y=720 → row=720/160=4 (last row of 5)
        HomescreenView.DropTarget t = resolve(380f, 720f);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(0, t.page);
        assertEquals(3, t.cellX);
        assertEquals(4, t.cellY);
    }

    @Test
    public void grid_exactCellBoundary() {
        // Column 1 starts at PAGE_PAD + one cell, not at PAGE_W/COLS: the page padding shifts
        // every boundary right by PAGE_PAD. Expressed in terms of the constants so that changing
        // the padding moves the test with the code instead of leaving a stale magic number.
        final int cellW = (PAGE_W - 2 * PAGE_PAD) / COLS;   // 92
        HomescreenView.DropTarget t = resolve(PAGE_PAD + cellW, 0f);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(1, t.cellX);
        // One pixel earlier is still column 0 — the boundary belongs to the column it starts.
        assertEquals(0, resolve(PAGE_PAD + cellW - 1, 0f).cellX);
    }

    @Test
    public void grid_touchInsideTheLeftPadStrip_clampsToColumnZero() {
        // The pad strips are not part of any column. A drop there must land in the edge column
        // rather than producing a negative index or refusing the drop.
        assertEquals(0, resolve(0f, 0f).cellX);
        assertEquals(0, resolve(PAGE_PAD - 1f, 0f).cellX);
    }

    @Test
    public void grid_touchInsideTheRightPadStrip_clampsToLastColumn() {
        assertEquals(COLS - 1, resolve(PAGE_W - 1f, 0f).cellX);
    }

    // -------------------------------------------------------------------------
    // Grid zone — span > 1 clamping
    // -------------------------------------------------------------------------

    @Test
    public void grid_spanX2_clampsToRightEdge() {
        // spanX=2, x=350 → col=350/100=3, clamp to min(cols-spanX, 3) = min(2,3) = 2
        HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                350f, 0f,
                0, PAGE_W, PAGE_PAD, GRID_TOP, PAGE_H,
                COLS, ROWS, /*spanX=*/ 2, /*spanY=*/ 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                removeOnly());
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(2, t.cellX);
    }

    @Test
    public void grid_spanX4_alwaysClampsToCol0() {
        // spanX=4 (full width), any x → cellX = min(0, anything) = 0
        HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                350f, 0f,
                0, PAGE_W, PAGE_PAD, GRID_TOP, PAGE_H,
                COLS, ROWS, /*spanX=*/ 4, /*spanY=*/ 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                removeOnly());
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(0, t.cellX);
    }

    @Test
    public void grid_spanY3_clampsToBottomEdge() {
        // spanY=3, y=700 → row=700/160=4, clamp to min(rows-spanY, 4) = min(2,4) = 2
        HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                0f, 700f,
                0, PAGE_W, PAGE_PAD, GRID_TOP, PAGE_H,
                COLS, ROWS, /*spanX=*/ 1, /*spanY=*/ 3,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                removeOnly());
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(2, t.cellY);
    }

    // -------------------------------------------------------------------------
    // Grid zone — multi-page resolution via scrollX
    // -------------------------------------------------------------------------

    @Test
    public void grid_scrollX_pageWidth_selectsPage1() {
        // scrollX = PAGE_W → viewing page 1; x=50 screen → contentX=450 → page=1, localX=50 → col=0
        HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                50f, 0f,
                /*scrollX=*/ PAGE_W, PAGE_W, PAGE_PAD, GRID_TOP, PAGE_H,
                COLS, ROWS, 1, 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                removeOnly());
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(1, t.page);
        assertEquals(0, t.cellX);
    }

    @Test
    public void grid_scrollX_midPage_resolvesBothPages() {
        // scrollX = PAGE_W/2 → mid-scroll between page 0 and 1
        // x=250 (screen) → contentX=250+200=450 → page=1, localX=50, col=0
        HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                250f, 0f,
                /*scrollX=*/ PAGE_W / 2, PAGE_W, PAGE_PAD, GRID_TOP, PAGE_H,
                COLS, ROWS, 1, 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                removeOnly());
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(1, t.page);
        assertEquals(0, t.cellX);
    }

    @Test
    public void grid_scrollX_page0_stillReachable() {
        // scrollX = PAGE_W, but x=0 → contentX=400 → page=1. x=-PAGE_W is not realistic;
        // simulate left edge of page 1: x=0,scrollX=PAGE_W → page=1,col=0 ✓
        // Verify page 0 is reachable when scrollX=0:
        HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                50f, 0f,
                /*scrollX=*/ 0, PAGE_W, PAGE_PAD, GRID_TOP, PAGE_H,
                COLS, ROWS, 1, 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                removeOnly());
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(0, t.page);
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    public void zeroCellSize_returnsOutside() {
        // pageWidth=0 → OUTSIDE (guard against division by zero)
        HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                200f, 400f,
                0, /*pageWidth=*/ 0, PAGE_PAD, GRID_TOP, PAGE_H,
                COLS, ROWS, 1, 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                removeOnly());
        assertEquals(HomescreenView.DropTarget.Zone.OUTSIDE, t.zone);
    }

    @Test
    public void zeroCols_returnsOutside() {
        HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                200f, 400f,
                0, PAGE_W, PAGE_PAD, GRID_TOP, PAGE_H,
                /*cols=*/ 0, ROWS, 1, 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                removeOnly());
        assertEquals(HomescreenView.DropTarget.Zone.OUTSIDE, t.zone);
    }

    @Test
    public void grid_midCellVsCellEdge_differ() {
        // Mid-cell and the column's own left edge must both resolve to that column.
        final int cellW = (PAGE_W - 2 * PAGE_PAD) / COLS;   // 92
        HomescreenView.DropTarget mid  = resolve(PAGE_PAD + cellW + cellW / 2f, 0f);
        HomescreenView.DropTarget edge = resolve(PAGE_PAD + cellW, 0f);
        assertEquals(1, mid.cellX);
        assertEquals(1, edge.cellX); // boundary belongs to the column it opens
    }

    @Test
    public void grid_justBeforeBoundary_isCol0() {
        // One pixel before column 1 opens (PAGE_PAD + cellW) is still column 0.
        HomescreenView.DropTarget t = resolve(PAGE_PAD + (PAGE_W - 2 * PAGE_PAD) / COLS - 1f, 0f);
        assertEquals(0, t.cellX);
    }

    // -------------------------------------------------------------------------
    // Action bar — multiple pills (Remove / Uninstall / Resize)
    // -------------------------------------------------------------------------

    private HomescreenView.DropTarget resolveWithBar(
            float x, float y, HomescreenView.DropTarget.ActionBar bar) {
        return HomescreenView.DropTarget.resolve(
                x, y,
                0, PAGE_W, PAGE_PAD, GRID_TOP, PAGE_H,
                COLS, ROWS, 1, 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG,
                bar);
    }

    @Test
    public void actionBar_twoPills_leftIsRemoveRightIsUninstall() {
        // Two 160 px pills + 8 px gap = 328 total, centred at 200 → starts at x=36.
        // Pill 0 (Remove) spans [36, 196]; pill 1 (Uninstall) spans [204, 364].
        HomescreenView.DropTarget.ActionBar bar = bar(
                HomescreenView.DropTarget.Zone.REMOVE,
                HomescreenView.DropTarget.Zone.UNINSTALL);

        assertEquals(HomescreenView.DropTarget.Zone.REMOVE,
                resolveWithBar(100f, 40f, bar).zone);
        assertEquals(HomescreenView.DropTarget.Zone.UNINSTALL,
                resolveWithBar(300f, 40f, bar).zone);
    }

    @Test
    public void actionBar_gapBetweenPills_isNotAnAction() {
        HomescreenView.DropTarget.ActionBar bar = bar(
                HomescreenView.DropTarget.Zone.REMOVE,
                HomescreenView.DropTarget.Zone.UNINSTALL);
        // x=200 sits in the 8 px gap between the two pills → falls through to the grid.
        assertEquals(HomescreenView.DropTarget.Zone.GRID, resolveWithBar(200f, 40f, bar).zone);
    }

    @Test
    public void actionBar_none_leavesTopOfScreenDroppable() {
        // With no pills at all (e.g. mid-drag state), the top strip is an ordinary grid cell.
        HomescreenView.DropTarget t =
                resolveWithBar(200f, 40f, HomescreenView.DropTarget.ActionBar.NONE);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(0, t.cellY);
    }

    // -------------------------------------------------------------------------
    // gridTopPx — status-bar inset shifts every row down
    // -------------------------------------------------------------------------

    @Test
    public void gridTop_rowsAreMeasuredFromTheInset() {
        // cellH = 160. With a 100 px status-bar inset, row N spans y in [100+160N, 100+160(N+1)).
        // Both y values below land in a DIFFERENT row if the inset is ignored (200/160 = 1 and
        // 360/160 = 2), so this fails against the pre-fix "measure from y=0" behaviour.
        int gridTop = 100;
        HomescreenView.DropTarget row0 = HomescreenView.DropTarget.resolve(
                50f, 200f, 0, PAGE_W, PAGE_PAD, gridTop, PAGE_H, COLS, ROWS, 1, 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG, HomescreenView.DropTarget.ActionBar.NONE);
        HomescreenView.DropTarget row1 = HomescreenView.DropTarget.resolve(
                50f, 360f, 0, PAGE_W, PAGE_PAD, gridTop, PAGE_H, COLS, ROWS, 1, 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG, HomescreenView.DropTarget.ActionBar.NONE);

        assertEquals("y=200 is 100 px into row 0", 0, row0.cellY);
        assertEquals("y=360 is 100 px into row 1", 1, row1.cellY);
    }

    @Test
    public void gridTop_zeroInset_matchesLegacyRowMath() {
        // Guard the other direction: with no inset the resolver must behave exactly as before.
        for (float y = 0f; y < 800f; y += 13f) {
            HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                    50f, y, 0, PAGE_W, PAGE_PAD, /*gridTopPx=*/ 0, PAGE_H, COLS, ROWS, 1, 1,
                    DOCK_TOP, DOCK_BOT, DOCK_MRG, HomescreenView.DropTarget.ActionBar.NONE);
            assertEquals("y=" + y, Math.min(ROWS - 1, (int) (y / 160)), t.cellY);
        }
    }

    @Test
    public void gridTop_aboveTheInset_clampsToRowZero() {
        // A drop in the status-bar strip must not produce a negative row.
        HomescreenView.DropTarget t = HomescreenView.DropTarget.resolve(
                50f, 10f, 0, PAGE_W, PAGE_PAD, /*gridTopPx=*/ 100, PAGE_H, COLS, ROWS, 1, 1,
                DOCK_TOP, DOCK_BOT, DOCK_MRG, HomescreenView.DropTarget.ActionBar.NONE);
        assertEquals(HomescreenView.DropTarget.Zone.GRID, t.zone);
        assertEquals(0, t.cellY);
    }
}
