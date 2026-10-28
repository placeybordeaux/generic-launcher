package com.genericlauncher.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Drawable that renders a notification count badge in the top-right corner of its bounds,
 * optionally over a wrapped icon. Replaces the old 8dp {@code DotDrawable}.
 *
 * <p>{@code base} is nullable and that distinction drives two usage modes:
 * <ul>
 *   <li><b>With a base</b> — wraps an app icon; the icon is drawn first, badge on top. Intrinsic
 *       size comes from the icon.</li>
 *   <li><b>Without a base</b> — renders the badge alone. This is how the folder chip overlays a
 *       badge in its own {@code ImageView}; intrinsic size is the widest badge's size regardless
 *       of the current count, so the host view can simply use {@code WRAP_CONTENT} and never
 *       needs re-measuring when the count changes. See {@link #getIntrinsicWidth}.</li>
 * </ul>
 *
 * <p>Allocation: all paint state lives in the shared {@link BadgePainter}, built once here in the
 * constructor. {@link #draw} allocates nothing.
 *
 * <p>Animation: {@link #setVisible(boolean, boolean)} registers/unregisters with the process-wide
 * {@link BadgeAnimator}. {@code ImageView} calls {@code setVisible()} on window attach and detach,
 * which makes badge animation start and stop automatically — and, because the animator holds
 * callbacks weakly, leak-free even if a host view never detaches cleanly.
 */
public class BadgeDrawable extends Drawable {

    @Nullable private final Drawable base;
    private final BadgePainter painter;
    private final BadgeConfig config;
    private final int seamFill;
    /** True when style/effects need per-frame redraws (see {@link BadgeConfig#isAnimated}). */
    private final boolean animated;
    /** Icon size to assume when there is no base icon to measure. */
    private final float badgeOnlyIconPx;

    private int count;
    private boolean registered;

    /** Held as a field so register/unregister see the same identity across calls. */
    private final Runnable invalidate = this::invalidateSelf;

    /**
     * @param base              icon to draw beneath the badge, or {@code null} for a badge-only
     *                          drawable
     * @param density           display density scalar (dp → px)
     * @param count             notification count; {@code <= 0} renders no badge
     * @param config            badge appearance settings
     * @param seamFill        flat fill for this app, from the active
     *                        {@code Personality.BADGE} — see {@code docs/SEAM.md}
     * @param badgeOnlyIconPx icon size to scale against when {@code base} is {@code null};
     *                        ignored otherwise
     */
    public BadgeDrawable(@Nullable Drawable base, float density, int count,
                         @NonNull BadgeConfig config, int seamFill,
                         float badgeOnlyIconPx) {
        this.base = base;
        this.config = config;
        this.painter = new BadgePainter(density, config.size);
        this.count = count;
        this.seamFill = seamFill;
        this.animated = config.isAnimated();
        this.badgeOnlyIconPx = badgeOnlyIconPx;
        if (base == null) painter.setIconSize(badgeOnlyIconPx);
    }

    /** Updates the count and redraws. Also starts/stops animation as the badge appears/vanishes. */
    public void setCount(int count) {
        if (this.count == count) return;
        this.count = count;
        syncAnimator();
        invalidateSelf();
    }

    /** Current notification count. */
    public int getCount() { return count; }

    // -------------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------------

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();

        if (base == null) {
            // Badge-only: bounds are the badge's own box, already sized by the intrinsic
            // methods below. Nothing to inset.
            if (count <= 0) return;
            float pad = painter.glowPad();
            painter.draw(canvas, b.right - pad, b.top + pad, count,
                    config.style, seamFill, config.shimmer, config.glow);
            return;
        }

        painter.setIconSize(b.width());

        // Reserve room for the overhanging badge by shrinking the icon, rather than drawing
        // outside our bounds — the host ImageView clips, and clipChildren=false across
        // RecyclerView and CellLayout is fragile.
        //
        // The reserve is applied whether or not there is a badge right now: if it were
        // conditional on count, every icon would visibly resize the moment a notification
        // arrived.
        float reserve = painter.overhangPad() + painter.glowPad();
        base.setBounds((int) (b.left + reserve), (int) (b.top + reserve), b.right, b.bottom);
        base.draw(canvas);

        if (count <= 0) return;

        // Straddle the inset icon's top-right corner: overhangPad() of the badge sits outside
        // it, the rest over it. The icon itself is NOT inset on the right (only top/left), so
        // the right edge is additionally pulled in by glowPad() here — without it, the glow's
        // rightmost breathing extent would paint past b.right and get clipped by the host view.
        // (The top edge needs no equivalent adjustment: iconTop already carries glowPad() via
        // reserve, so topY = iconTop - overhangPad() lands exactly on b.top + glowPad().)
        float iconRight = b.right;
        float iconTop   = b.top + reserve;
        float rightX = iconRight - painter.glowPad();
        float topY   = iconTop - painter.overhangPad();

        float w = painter.widthFor(count);
        // Clamp so an unusually wide pill on a tiny icon still starts inside the left edge.
        if (rightX - w < b.left) rightX = b.left + Math.min(w, b.width());
        if (topY < b.top) topY = b.top;

        painter.draw(canvas, rightX, topY, count,
                config.style, seamFill, config.shimmer, config.glow);
    }

    // -------------------------------------------------------------------------
    // Animator lifecycle — ImageView drives this via setVisible() on attach/detach
    // -------------------------------------------------------------------------

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        syncAnimator();
        return changed;
    }

    private void syncAnimator() {
        boolean want = animated && count > 0 && isVisible();
        if (want == registered) return;
        registered = want;
        if (want) BadgeAnimator.register(invalidate);
        else      BadgeAnimator.unregister(invalidate);
    }

    // -------------------------------------------------------------------------
    // Delegation to the wrapped drawable (null-safe)
    // -------------------------------------------------------------------------

    @Override
    public void setAlpha(int alpha) {
        if (base != null) base.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter cf) {
        if (base != null) base.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    // The badge-only intrinsic size deliberately ignores the count: it is the box the widest
    // possible badge would need, reported identically at every count including zero.
    //
    // Anything count-dependent here cannot work, because setCount only invalidates. ImageView's
    // invalidateDrawable re-reads the intrinsic size and redraws but never calls requestLayout,
    // so the host view keeps whatever size it was last measured at. A count-0 size of -1 (which
    // ImageView floors to 1px) therefore meant a folder's first notification drew its badge into
    // a 1x1 box that the chip clipped away — the badge only appeared after some unrelated
    // refresh() rebuilt the page. A fixed box also survives 9 -> 10 widening the pill.
    //
    // Nothing shifts as a result: draw() anchors the badge to the box's right edge, so the extra
    // width sits on the empty left of the box, and the badge stays where it was.

    @Override
    public int getIntrinsicWidth() {
        if (base != null) return base.getIntrinsicWidth();
        return (int) Math.ceil(painter.maxWidth() + 2f * painter.glowPad());
    }

    @Override
    public int getIntrinsicHeight() {
        if (base != null) return base.getIntrinsicHeight();
        return (int) Math.ceil(painter.height() + 2f * painter.glowPad());
    }
}
