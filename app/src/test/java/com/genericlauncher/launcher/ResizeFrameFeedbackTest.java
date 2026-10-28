package com.genericlauncher.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.time.Duration;

import static org.junit.Assert.*;

/**
 * The two things the resize frame did not tell the user, both listed in docs/TODO.md as "still
 * open, and deliberately not fixed":
 *
 * <ul>
 *   <li><b>Nothing indicated the grip was held.</b> {@code activeEdge} was never drawn, so a
 *       finger on the corner bracket looked exactly like no finger at all. With a 28dp grab box
 *       under an 18dp mark, "did I grab it?" and "did it refuse?" produced the same picture —
 *       the same ambiguity the occupancy clamp spent months in.</li>
 *   <li><b>Nothing animated the settle.</b> The frame tracks the finger continuously and the span
 *       commits in whole cells with a 0.66-cell dead band, so release could drop the outline by
 *       most of a cell in a single frame.</li>
 * </ul>
 *
 * <p>Neither is visible to {@code resizeFrameRectForTest()} alone, so this class asserts on two
 * things the rest of the resize suite does not touch: the pixels the frame actually paints, and
 * the drawn rect read <em>at the instant of release</em> — before any clock has advanced, where
 * an animated settle has committed the span but not yet moved the outline and an instant snap
 * would already be at the destination. (The same technique {@code WidgetAddPagePlacementTest}
 * uses for animateToPage, and for the same reason: idling the looper re-lays the view out at
 * Robolectric's own window size.)
 *
 * <p>Native graphics is mandatory for the pixel half. Under Robolectric's legacy shadows a
 * {@link Canvas} paints nothing, every bitmap reads blank, and "held looks the same as idle"
 * would pass for the wrong reason — hence {@link #theIdleGripIsDrawnAtAll}, the positive control.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ResizeFrameFeedbackTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int WIDGET_ID = 11;
    private static final String PROVIDER = "com.example.resizable/.Provider";

    /**
     * Half-width of the bitmap the grip is rendered into, in dp. The bracket reaches
     * GRIP_ARM_DP + CORNER_DP = 34dp back from the frame corner, which itself sits OUTSET_DP
     * outside the widget, so 60dp either side of the cell boundary contains all of it with room
     * to spare for anything a pressed state might add.
     */
    private static final int BOX_DP = 60;

    private ActivityController<Activity> controller;
    private Activity   activity;
    private FrameLayout root;
    private HomeScreenStore store;
    /** The view under test, so {@link #tearDown} can stop a settle this test left in flight. */
    private HomescreenView view;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        root       = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        for (File f : activity.getFilesDir().listFiles()) {
            if (f.getName().startsWith("homescreen.json")) f.delete();
        }
        store = HomeScreenStore.get(activity);
    }

    /**
     * Close the frame before the activity goes away, which cancels any settle still in flight
     * (dismissResizeFrame removes the overlay, and its onDetachedFromWindow cancels).
     *
     * <p>Several tests here release the grip deliberately without letting the settle finish, which
     * is the whole point of them. Leaving a {@link android.animation.ValueAnimator} pending across
     * teardown leaves frame callbacks queued on a looper the test is done with, and whatever that
     * costs is spent after the last assertion — where a green run gives nobody a reason to look.
     */
    @After
    public void tearDown() {
        if (view != null) view.dismissResizeFrame();
        controller.pause().stop().destroy();
    }

    private static MotionEvent event(int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    /** One 2x2 freely-resizable widget at (0,0) of page 0, with the resize frame already open. */
    private HomescreenView viewWithOpenResizeFrame() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = ComponentName.unflattenFromString(PROVIDER);
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_BOTH;
        Shadows.shadowOf(AppWidgetManager.getInstance(activity))
                .addBoundWidget(WIDGET_ID, provider);

        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID, PROVIDER));

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 2048));
        root.addView(view, W, H);
        view.refresh();
        layout(view);

        view.showResizeFrame(item());
        assertTrue("test setup: the frame must be open", view.isResizeFrameOpen());
        layout(view); // showResizeFrame adds the overlay, so lay that out too
        return view;
    }

    private static void layout(HomescreenView view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    private HomeItem item() {
        for (HomeItem i : store.getItems()) {
            if (i.getType() == HomeItem.Type.WIDGET) return i;
        }
        fail("test setup: the widget vanished from the store");
        return null;
    }

    /** Must match production: the grid has horizontal page padding, so cells are not width/cols. */
    private static int cellW(HomescreenView view) {
        return CellLayout.cellWidth(view.getPage(0).getWidth(), 4,
                CellLayout.pagePadPx(view.getContext()));
    }
    private static int cellH(HomescreenView view) { return view.getPage(0).getHeight() / 5; }

    /** X of the corner grip. Pass cellX + spanX. */
    private static float gripX(HomescreenView view, int rightCol) {
        return CellLayout.pagePadPx(view.getContext()) + rightCol * cellW(view);
    }
    /** Y of the corner grip. Pass cellY + spanY. */
    private static float gripY(HomescreenView view, int bottomRow) {
        return bottomRow * cellH(view);
    }

    private static int dp(HomescreenView view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------------
    // 1. The grip has a pressed state
    // ---------------------------------------------------------------------

    /**
     * Renders the frame into a bitmap centred on {@code (cx, cy)} and counts the accent-coloured
     * pixels in it.
     *
     * <p>Everything the frame paints over the corner is drawn in the accent (0xFFBB86FC) or a
     * translucent wash of it; the only other ink in the box is the 0x33000000 screen scrim, whose
     * blue channel is 0. So "how much accent ink is on the grip" is a blue-channel count.
     */
    private static int accentPixelsAtGrip(HomescreenView view, float cx, float cy) {
        int box = dp(view, BOX_DP);
        int x0 = Math.round(cx) - box, y0 = Math.round(cy) - box;
        Bitmap bmp = Bitmap.createBitmap(2 * box, 2 * box, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.translate(-x0, -y0);
        view.drawResizeFrameForTest(canvas);

        int n = 0;
        for (int y = 0; y < bmp.getHeight(); y++) {
            for (int x = 0; x < bmp.getWidth(); x++) {
                int p = bmp.getPixel(x, y);
                // The scrim is pure black; the accent's blue is 0xFC even at 35% alpha over it.
                if (Color.alpha(p) > 0 && Color.blue(p) >= 60) n++;
            }
        }
        bmp.recycle();
        return n;
    }

    /**
     * Positive control. Every other assertion here is "held differs from idle", and a Canvas that
     * paints nothing satisfies neither side of that — it makes both counts zero and the
     * comparison vacuous. This is the test that fails first if native graphics stops working.
     */
    @Test
    public void theIdleGripIsDrawnAtAll() {
        view = viewWithOpenResizeFrame(); // the field, so tearDown can stop a live settle
        int idle = accentPixelsAtGrip(view, gripX(view, 2), gripY(view, 2));
        assertTrue("the corner bracket must actually paint accent pixels — if this is 0 the"
                + " fixture is measuring an empty canvas, not an unpressed grip", idle > 0);
    }

    /**
     * The reported gap: a finger on the grip has to look different from no finger on the grip.
     *
     * <p>Drives a real ACTION_DOWN through {@code dispatchTouchEvent} rather than poking the
     * state, so what is being asserted is "pressing the grip changes the picture", not "setting a
     * flag changes the picture".
     */
    @Test
    public void aFingerOnTheGripChangesWhatTheGripDraws() {
        view = viewWithOpenResizeFrame(); // the field, so tearDown can stop a live settle
        float gx = gripX(view, 2), gy = gripY(view, 2);

        int idle = accentPixelsAtGrip(view, gx, gy);
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, gx, gy));
        int held = accentPixelsAtGrip(view, gx, gy);

        assertTrue("a held grip must draw more accent ink than an idle one (idle=" + idle
                + " held=" + held + "); with no pressed state the two are identical",
                held > idle);
    }

    /**
     * And DOWN must actually ask for a repaint.
     *
     * <p>Setting {@code gripHeld} is only half of a pressed state that lives in pixels. ACTION_DOWN
     * schedules no draw of its own, so without an explicit {@code invalidate()} the next frame is
     * the one the first MOVE triggers — and a press-and-hold that never moves looks exactly like
     * no press at all. That is the ambiguity the pressed state exists to remove, so arriving only
     * once the drag is under way is arriving too late.
     *
     * <p>{@link #aFingerOnTheGripChangesWhatTheGripDraws} cannot catch this: it renders through
     * {@code drawResizeFrameForTest}, which paints the current state on demand whether or not
     * anything was invalidated. Measured on device (OnePlus CPH2749): accent ink at the grip was
     * 3193px idle, 3193px after DOWN, and 4955px only after a 2px MOVE.
     */
    @Test
    public void pressingTheGripAsksTheFrameToRepaint() {
        view = viewWithOpenResizeFrame(); // the field, so tearDown can stop a live settle
        float gx = gripX(view, 2), gy = gripY(view, 2);

        View frame = view.resizeFrameViewForTest();
        assertNotNull("no frame overlay — the fixture never opened one", frame);
        Shadows.shadowOf(frame).clearWasInvalidated();

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, gx, gy));

        assertTrue("ACTION_DOWN on the grip must invalidate the frame; without it the pressed"
                + " state is not drawn until some later event happens to repaint, so a"
                + " press-and-hold shows no feedback at all",
                Shadows.shadowOf(frame).wasInvalidated());
    }

    /** And it must let go again — a pressed state that sticks is worse than none. */
    @Test
    public void releasingTheGripReturnsItToTheIdleDrawing() {
        view = viewWithOpenResizeFrame(); // the field, so tearDown can stop a live settle
        float gx = gripX(view, 2), gy = gripY(view, 2);

        int idle = accentPixelsAtGrip(view, gx, gy);
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, gx, gy));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, gx, gy));

        assertEquals("the grip must be back to its idle drawing the moment the finger lifts,"
                + " even though the frame may still be settling",
                idle, accentPixelsAtGrip(view, gx, gy));
    }

    /**
     * A drag that starts inside the outline repositions the widget and never touches the grip, so
     * the grip must stay idle throughout. Without the {@code &} against {@code grippedAxes()} a
     * pressed state keyed on "some gesture is running" would light up here too.
     */
    @Test
    public void aBodyDragDoesNotLightTheGrip() {
        view = viewWithOpenResizeFrame(); // the field, so tearDown can stop a live settle
        int cw = cellW(view), ch = cellH(view);
        float gx = gripX(view, 2), gy = gripY(view, 2);
        int idle = accentPixelsAtGrip(view, gx, gy);

        // Well inside the outline and a long way from the corner grip's 28dp box. The first MOVE
        // only crosses touchSlop, which is what arms the move and re-anchors it; the second
        // carries the displacement. A real drag streams MOVEs at 60Hz and gets both for free.
        float nudge = android.view.ViewConfiguration.get(activity).getScaledTouchSlop() + 1f;
        float bx = gripX(view, 1), by = gripY(view, 1);
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, bx, by));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, bx, by + nudge));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, bx, by + nudge + ch));

        assertEquals("the body drag moved the widget, so read the grip at its new corner",
                1, item().getCellY());
        assertEquals("a reposition must leave the grip unlit",
                idle, accentPixelsAtGrip(view, gripX(view, 2), gripY(view, 3)));
    }

    /**
     * Nothing dark is painted between the live edge and the committed edge while the grip is
     * held inside the dead band.
     *
     * <p>A "cut preview" used to shade that strip as the part a shrink was about to remove — from
     * when the span committed on release. The span now commits live, so the strip between the
     * finger and the committed edge is the dead band and a release never cuts it; the shade
     * previewed a cut that could not happen and showed on device as a solid dark bar, about 15px
     * wide and the frame's full height, beside the frame for as long as the grip was held.
     *
     * <p>Only the screen scrim (alpha 0x33) may be in that strip: any pixel darker than the scrim
     * that is not accent-coloured is the bar.
     */
    @Test
    public void draggingTheGripInsideTheDeadBandPaintsNothingBesideTheFrame() {
        view = viewWithOpenResizeFrame(); // the field, so tearDown can stop a live settle
        float gx = gripX(view, 2), gy = gripY(view, 2);
        float[] idle = view.resizeFrameRectForTest();

        // 15px inward: well inside the 0.66-cell dead band, so nothing commits and the live edge
        // sits 15px inside the committed one — the device repro.
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, gx, gy));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, gx - 15, gy));
        float[] live = view.resizeFrameRectForTest();
        assertEquals("test setup: the span must not have committed", 2, item().getSpanX());
        assertTrue("test setup: the live edge must be inside the committed one",
                live[2] < idle[2] - 5);

        // The strip between the two right edges, kept clear of the 2dp frame stroke on its left
        // and of the corner grip's bracket at the bottom.
        int x0 = Math.round(live[2]) + dp(view, 3), x1 = Math.round(idle[2]) - 1;
        int y0 = Math.round(idle[1]) + dp(view, 20), y1 = Math.round(idle[3]) - dp(view, 60);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.drawResizeFrameForTest(new Canvas(bmp));
        int dark = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = bmp.getPixel(x, y);
                if (Color.alpha(p) > 0x33 + 4 && Color.blue(p) < 60) dark++;
            }
        }
        bmp.recycle();
        assertEquals("dark ink beside the frame in a " + (x1 - x0) + "x" + (y1 - y0)
                + "px strip: that is the bar", 0, dark);
    }

    // ---------------------------------------------------------------------
    // 2. The frame settles instead of snapping
    // ---------------------------------------------------------------------

    /**
     * Release must start an animation, not teleport.
     *
     * <p>Read without the clock, which is the only way to tell the two apart: at the instant of
     * ACTION_UP an animated settle has already committed the span but has not yet moved the drawn
     * outline, while an instant snap is at the destination by the time UP returns. Idling the
     * looper first would collapse the distinction — and would also re-lay the view out at
     * Robolectric's default window size, which is a separate trap this file's history knows well.
     */
    @Test
    public void releasingTheGripDoesNotSnapTheFrameInOneFrame() {
        view = viewWithOpenResizeFrame(); // the field, so tearDown can stop a live settle
        int cw = cellW(view);
        float gx = gripX(view, 2), gy = gripY(view, 2);

        float[] before = view.resizeFrameRectForTest();
        assertNotNull("test setup: the frame must be open", before);

        // 1.4 cells: past the 0.66 dead band, so exactly one cell commits and 0.4 of a cell of
        // residual is left over. That leftover is the whole subject of this test — with a
        // whole-number drag there would be nothing to settle.
        float travel = 1.4f * cw;
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, gx, gy));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, gx + travel, gy));

        float[] during = view.resizeFrameRectForTest();
        assertEquals("test setup: exactly one cell must have committed", 3, item().getSpanX());
        assertEquals("test setup: the frame must be tracking the finger",
                travel, during[2] - before[2], 2f);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, gx + travel, gy));

        float[] atRelease = view.resizeFrameRectForTest();
        assertTrue("ACTION_UP must leave a settle animation in flight",
                view.isResizeFrameSettlingForTest());
        assertEquals("at the instant of release the outline must still be where the finger left"
                        + " it; an instant snap is already " + Math.round(0.4f * cw)
                        + "px away by now",
                during[2], atRelease[2], 1f);
    }

    /**
     * ...and it must ease <em>toward</em> the committed cells, then rest on them.
     *
     * <p>The failure this guards is the mirror image of the double-count that once made the frame
     * run at twice the finger's speed: animating {@code liveDx} to zero rather than the
     * <em>residual</em> to zero sends the outline back past the committed edge toward where the
     * drag started, because the committed cell already lives in the frame's own edge. The resting
     * place cannot tell the two apart — both land correctly, because the last thing the animation
     * does is drop the offset entirely — so the mid-flight sample is the assertion that matters.
     * With the correct target the outline is somewhere in (1.0, 1.4] cells at half time; with
     * {@code liveDx} driven to zero it is at ~0.35 of a cell, i.e. on the wrong side of the
     * footprint the widget actually has.
     *
     * <p>The finish is still driven by a real idle, so that something proves the animation ends
     * on its own clock rather than only when a test pokes it. The layout is re-imposed after it:
     * idling re-lays a HomescreenView out at Robolectric's default window size and would silently
     * change every number below.
     */
    @Test
    public void theSettleEasesTowardTheCommittedCellsAndNeverBackTowardTheStart() {
        view = viewWithOpenResizeFrame(); // the field, so tearDown can stop a live settle
        int cw = cellW(view);
        float gx = gripX(view, 2), gy = gripY(view, 2);

        float[] before = view.resizeFrameRectForTest();
        float travel = 1.4f * cw;
        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, gx, gy));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, gx + travel, gy));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, gx + travel, gy));

        // Half of SETTLE_MS, driven directly rather than by idling: measured on nixos-2, a 75ms
        // idle runs the whole 150ms animation to completion, so the looper cannot sample
        // mid-flight at all — and idling would re-lay the view out at Robolectric's default
        // window size on top of that.
        view.advanceResizeSettleForTest(75);
        float mid = view.resizeFrameRectForTest()[2] - before[2];
        assertTrue("mid-settle the outline must still be past the committed cell, on its way in"
                        + " — it was at " + Math.round(mid) + "px of a " + cw + "px cell, which"
                        + " is behind the widget's own footprint",
                mid > cw);
        assertTrue("and it must never overshoot the point the finger left it at ("
                        + Math.round(mid) + "px vs " + Math.round(travel) + "px)",
                mid <= travel + 1f);

        ShadowLooper.idleMainLooper(Duration.ofMillis(500).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        layout(view); // re-impose the geometry the idle just discarded

        assertFalse("the settle must have finished", view.isResizeFrameSettlingForTest());
        float[] settled = view.resizeFrameRectForTest();
        assertEquals("the outline must rest exactly one committed cell out from where it started"
                        + " — not back at the start, and not still 0.4 of a cell past it",
                cw, settled[2] - before[2], 1f);
        assertEquals("and the committed span must be untouched by the settle",
                3, item().getSpanX());
    }
}
