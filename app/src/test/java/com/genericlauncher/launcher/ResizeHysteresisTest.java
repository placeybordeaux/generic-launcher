package com.genericlauncher.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The snap hysteresis on {@link HomescreenView#spanDelta}.
 *
 * This exists because the threshold was changed from an implicit 0.5 (plain Math.round) to stock
 * Launcher3's 0.66 and the whole 795-test suite stayed green — nothing could express the
 * behaviour being changed. A dead band that no test can see is a dead band that silently comes
 * back, which is the failure mode CLAUDE.md records for this exact area twice over.
 */
@RunWith(RobolectricTestRunner.class)
public class ResizeHysteresisTest {

    private static final int CELL = 318;   // the real cell width measured on the test device

    @Test
    public void belowTheThreshold_doesNotChangeTheSpan() {
        assertEquals(0, HomescreenView.spanDelta(0f, CELL, 0));
        assertEquals(0, HomescreenView.spanDelta(CELL * 0.49f, CELL, 0));
        // Half a cell used to be enough. It no longer is: that is the whole point of the change.
        assertEquals(0, HomescreenView.spanDelta(CELL * 0.50f, CELL, 0));
        assertEquals(0, HomescreenView.spanDelta(CELL * 0.65f, CELL, 0));
    }

    @Test
    public void pastTheThreshold_commitsOneCell() {
        assertEquals(1, HomescreenView.spanDelta(CELL * 0.67f, CELL, 0));
        assertEquals(1, HomescreenView.spanDelta(CELL, CELL, 0));
    }

    @Test
    public void isSymmetricForShrinking() {
        assertEquals(0,  HomescreenView.spanDelta(-CELL * 0.65f, CELL, 0));
        assertEquals(-1, HomescreenView.spanDelta(-CELL * 0.67f, CELL, 0));
        assertEquals(-1, HomescreenView.spanDelta(-CELL, CELL, 0));
    }

    @Test
    public void theMeasuredJitterNoLongerFlipsTheSpan() {
        // The device trace that motivated this: a 7px tremble across the old half-cell boundary
        // took the widget 3x1 -> 4x1 -> 3x1 -> 4x1 within a second.
        // Sitting at 1 cell committed, neither reading may move it: both are inside the band.
        assertEquals(1, HomescreenView.spanDelta(471f, CELL, 1));
        assertEquals(1, HomescreenView.spanDelta(478f, CELL, 1));
        // And the same two readings from a 2-cell commit must equally stay put.
        assertEquals(2, HomescreenView.spanDelta(471f, CELL, 2));
        assertEquals(2, HomescreenView.spanDelta(478f, CELL, 2));
    }

    @Test
    public void aDeliberateDragStillCrossesTheBand() {
        // Hysteresis must not make the control sticky: 0.66 past the committed span still moves.
        assertEquals(2, HomescreenView.spanDelta(CELL * 1.67f, CELL, 1));
        assertEquals(0, HomescreenView.spanDelta(CELL * 0.33f, CELL, 1));
    }

    @Test
    public void aFastDragCrossesSeveralCellsAtOnce() {
        // MOVEs can be sparse; a single event may span multiple cells.
        assertEquals(3, HomescreenView.spanDelta(CELL * 3.1f, CELL, 0));
        assertEquals(-3, HomescreenView.spanDelta(CELL * -3.1f, CELL, 0));
    }

    // ------------------------------------------------------------------
    // Live frame tracking — reported on device as the frame moving at twice
    // the speed of the finger while expanding.
    // ------------------------------------------------------------------

    /**
     * Concrete residuals, hand-computed rather than re-derived from the implementation.
     *
     * An earlier version of this test asserted
     * {@code committed*CELL + liveResidual(delta, committed, CELL) == delta}, which expands to
     * {@code delta == delta} and therefore held for ANY implementation. Re-deriving the formula
     * under test proves nothing; these are fixed numbers.
     */
    @Test
    public void residualIsWhatIsLeftOverPastTheCommittedCells() {
        assertEquals(0.5f * CELL,  HomescreenView.liveResidual(1.5f * CELL,  1, CELL), 0.001f);
        assertEquals(0.4f * CELL,  HomescreenView.liveResidual(2.4f * CELL,  2, CELL), 0.001f);
        assertEquals(-0.2f * CELL, HomescreenView.liveResidual(-1.2f * CELL, -1, CELL), 0.001f);
    }

    @Test
    public void withNothingCommittedTheResidualIsTheWholeDelta() {
        assertEquals(100f, HomescreenView.liveResidual(100f, 0, CELL), 0.001f);
    }

    @Test
    public void atAnExactCellBoundaryTheResidualIsZero() {
        // One whole cell dragged and one whole cell committed: the frame sits exactly on the new
        // committed edge, with nothing left over to offset it by.
        assertEquals(0f, HomescreenView.liveResidual(CELL, 1, CELL), 0.001f);
        assertEquals(0f, HomescreenView.liveResidual(-2f * CELL, -2, CELL), 0.001f);
    }

    @Test
    public void guardsAgainstAZeroCellSize() {
        assertEquals(0, HomescreenView.spanDelta(500f, 0, 0));
    }
}
