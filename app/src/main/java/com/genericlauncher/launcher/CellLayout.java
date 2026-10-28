package com.genericlauncher.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * ViewGroup that positions children at fixed grid cells using {@link LayoutParams}.
 * Grid dimensions default to {@link HomeScreenStore#DEFAULT_COLS} × {@link HomeScreenStore#DEFAULT_ROWS}
 * and should be set via {@link #setGridDimensions(int, int)} by the parent after construction.
 * Cell size is computed from this view's measured size.
 */
public class CellLayout extends ViewGroup {

    /** LayoutParams that describe where a child sits on the grid. */
    public static final class LayoutParams extends ViewGroup.LayoutParams {
        public int cellX;
        public int cellY;
        public int spanX;
        public int spanY;

        public LayoutParams(int cellX, int cellY, int spanX, int spanY) {
            super(MATCH_PARENT, MATCH_PARENT);
            this.cellX = cellX;
            this.cellY = cellY;
            this.spanX = spanX;
            this.spanY = spanY;
        }
    }

    /**
     * Horizontal breathing room at the left and right edge of every page, in dp.
     *
     * Stock Launcher3 has workspace page padding and therefore room to draw a widget's resize
     * frame *outside* the widget. This grid had none — cells ran edge to edge — so the frame had
     * to be clamped inward off the system gesture insets and ended up drawn INSIDE the widget it
     * was meant to outline (measured on device: 154px in on each side, see docs/TODO.md).
     * Padding the page is what buys that room back.
     *
     * The constraint is PAGE_PAD_DP >= OUTSET_DP + KNOB_R_DP, so an edge widget's knob still lands
     * fully on screen. Icons are a fixed dp(52) and do not scale with the cell, so this costs
     * inter-icon spacing and label width, not icon size.
     */
    public static final int PAGE_PAD_DP = 16;

    /** Page padding in px. The single place the dp value is converted. */
    public static int pagePadPx(android.content.Context ctx) {
        return Math.round(PAGE_PAD_DP * ctx.getResources().getDisplayMetrics().density);
    }

    /**
     * Width of one grid cell. Every caller must go through here: the drawn grid, the drop-target
     * hit test and the resize frame each used to divide by {@code cols} themselves, and a padding
     * applied to only some of them would put drops in different cells from the ones on screen.
     */
    public static int cellWidth(int totalWidthPx, int cols, int padPx) {
        if (cols <= 0) return 0;
        return Math.max(0, totalWidthPx - 2 * padPx) / cols;
    }

    /** Left edge of a cell column, in the same coordinates as {@link #cellWidth}'s input. */
    public static int cellLeft(int cellX, int cellWidthPx, int padPx) {
        return padPx + cellX * cellWidthPx;
    }

    private int numCols = HomeScreenStore.DEFAULT_COLS;
    private int numRows = HomeScreenStore.DEFAULT_ROWS;

    public CellLayout(Context context) { super(context); }
    public CellLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    /** Must be called by the parent (HomescreenView) after construction and on grid-size changes. */
    public void setGridDimensions(int cols, int rows) {
        numCols = cols;
        numRows = rows;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(0, 0, 1, 1);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int pad   = pagePadPx(getContext());
        int cellW = cellWidth(w, numCols, pad);
        int cellH = h / numRows;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            child.measure(
                    MeasureSpec.makeMeasureSpec(cellW * lp.spanX, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellH * lp.spanY, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int pad   = pagePadPx(getContext());
        int cellW = cellWidth(r - l, numCols, pad);
        int cellH = (b - t) / numRows;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int left = cellLeft(lp.cellX, cellW, pad);
            int top  = lp.cellY * cellH;
            child.layout(left, top, left + cellW * lp.spanX, top + cellH * lp.spanY);
        }
    }
}
