package com.genericlauncher.launcher.livedin;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One app icon on one surface, drawn with its age.
 *
 * <p>The shared platform drawable is never mutated (beyond the bounds every surface sets before it
 * draws). A pristine icon is drawn straight through; any other look is a cached bitmap from
 * {@link LivedIn#RENDERED}, so a frame costs one {@code drawBitmap}. The look is re-derived when
 * {@link LivedIn#generation()} moves or {@link #refresh()} is called; the clock alone never forces
 * a redraw, which is why {@link LivedIn} refreshes on resume.
 */
final class LivedInDrawable extends Drawable {

    private final Drawable base;
    private final String key;
    private final int seed;

    private int look = -1;
    private int lookGeneration = Integer.MIN_VALUE;

    private final Slot current = new Slot();
    private final Slot puffFrom = new Slot();
    private int puffFromLook = -1;
    /** A puff is due or running. */
    private boolean puffing;
    /** Uptime the running puff started; meaningless unless {@link #puffStarted}. */
    private long puffStart;
    private boolean puffStarted;
    /** The resume the last puff belonged to, so one return plays it once. */
    private long puffToken = -1;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fadePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint puffPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;
    /**
     * The next puff frame. Scheduled rather than invalidated from inside {@link #draw}: an
     * invalidate issued while the view is recording its display list is cleared when recording
     * ends, and the puff froze on its first frame on the emulator.
     */
    private final Runnable nextFrame = this::invalidateSelf;

    LivedInDrawable(Drawable base, String key) {
        this.base = base;
        this.key = key;
        this.seed = Wear.mix(key.hashCode());
    }

    /** Re-derives the look and redraws if it (or a pending puff) changed. */
    void refresh() {
        int before = look;
        updateLook();
        boolean puff = maybeArmPuff();
        if (look != before || puff) invalidateSelf();
    }

    private void updateLook() {
        look = LivedIn.lookFor(key);
        lookGeneration = LivedIn.generation();
    }

    private boolean maybeArmPuff() {
        if (puffing || !LivedIn.puffPending(key)) return false;
        long token = LivedIn.resumeToken();
        if (token == puffToken) return false;
        int from = LivedIn.pendingPuffFrom(key);
        if (from < 0 || !Wear.dustCleared(from, look)) return false;
        puffFromLook = from;
        puffing = true;
        puffStarted = false;
        puffToken = token;
        return true;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;

        if (lookGeneration != LivedIn.generation()) updateLook();
        maybeArmPuff();

        Bitmap bmp = look == Wear.PRISTINE ? null : current.get(base, key, look, w, h, seed);
        if (bmp == null) {
            base.setBounds(b);
            base.draw(canvas);
        } else {
            canvas.drawBitmap(bmp, b.left, b.top, bitmapPaint);
        }

        if (!puffing) return;
        long now = SystemClock.uptimeMillis();
        if (!puffStarted) {
            puffStarted = true;
            // A beat of still dust first: the first frames after a return are often still under
            // the closing app's window animation.
            puffStart = now + WearPainter.PUFF_DELAY_MS;
        }
        float t = Math.max(0f, Math.min(1f, (now - puffStart) / (float) WearPainter.PUFF_MS));

        // The old, dusty icon fades over the first part of the puff while the cloud lifts off it.
        Bitmap from = puffFrom.get(base, key, puffFromLook, w, h, seed);
        float fade = Math.max(0f, 1f - t / 0.6f);
        if (from != null && fade > 0f) {
            fadePaint.setAlpha((int) (alpha * fade));
            canvas.drawBitmap(from, b.left, b.top, fadePaint);
        }
        WearPainter.drawPuff(canvas, b, seed, t, puffPaint);

        if (t < 1f) {
            scheduleSelf(nextFrame, now + 16);
        } else {
            puffing = false;
            puffFromLook = -1;
        }
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        bitmapPaint.setAlpha(alpha);
        // Forwarded as the badge did before this wrapper existed, so a pristine icon fades too.
        base.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter cf) {
        bitmapPaint.setColorFilter(cf);
        base.setColorFilter(cf);
    }

    @Override
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }

    @Override
    public int getIntrinsicWidth() { return base.getIntrinsicWidth(); }

    @Override
    public int getIntrinsicHeight() { return base.getIntrinsicHeight(); }

    int lookForTests() { return look; }

    boolean puffRunningForTests() { return puffing; }

    /**
     * One rendered look, remembered so an unchanged frame neither builds a cache key nor looks
     * one up.
     */
    private static final class Slot {
        private Bitmap bitmap;
        private int look = -1;
        private int w;
        private int h;
        private Wear.Style style;

        @Nullable
        Bitmap get(Drawable base, String key, int wantLook, int wantW, int wantH, int seed) {
            if (wantLook < 0 || wantLook == Wear.PRISTINE) return null;
            Wear.Style wantStyle = LivedIn.getStyleCached();
            if (bitmap != null && !bitmap.isRecycled() && look == wantLook && w == wantW
                    && h == wantH && style == wantStyle) {
                return bitmap;
            }
            String cacheKey = key + '|' + wantLook + '|' + wantStyle.ordinal() + '|'
                    + wantW + 'x' + wantH;
            Bitmap b = LivedIn.RENDERED.get(cacheKey);
            if (b == null) {
                b = Bitmap.createBitmap(wantW, wantH, Bitmap.Config.ARGB_8888);
                WearPainter.render(b, base, wantLook, wantStyle, seed);
                LivedIn.RENDERED.put(cacheKey, b);
            }
            bitmap = b;
            look = wantLook;
            w = wantW;
            h = wantH;
            style = wantStyle;
            return b;
        }
    }
}
