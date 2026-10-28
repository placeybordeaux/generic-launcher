package com.genericlauncher.launcher;

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
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
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * H9 — a widget's pending long-press must die when the gesture turns out to be a swipe or a tap.
 *
 * <p>The bug: {@code WidgetWrapper} cancels its timeout from ACTION_MOVE/UP/CANCEL, but for a
 * widget whose RemoteViews root isn't clickable nothing under the wrapper consumes ACTION_DOWN.
 * {@code dispatchTouchEvent} therefore returns false, the parent {@link CellLayout} never installs
 * the wrapper as a touch target, and the wrapper is delivered no further event at all — so those
 * branches never run and the timeout fires mid-swipe, starting an accidental drag.
 *
 * <p>These tests drive the real dispatch path (a widget hostview that consumes nothing) rather
 * than calling the wrapper's handlers directly, because "who receives the MOVE" is the whole bug.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetLongPressCancelTest {

    private static final int W = 1080;
    private static final int H = 2424;
    private static final int WIDGET_ID = 7;
    /** Comfortably past ViewConfiguration.getLongPressTimeout() (500 ms). */
    private static final long PAST_TIMEOUT_MS = 2000;

    private ActivityController<Activity> controller;
    private Activity   activity;
    private FrameLayout root;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity   = controller.get();
        root       = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        new File(activity.getFilesDir(), "homescreen.json.tmp").delete();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    private static MotionEvent event(int action, float x, float y) {
        long t = SystemClock.uptimeMillis();
        return MotionEvent.obtain(t, t, action, x, y, 0);
    }

    private static void idlePastTimeout() {
        ShadowLooper.idleMainLooper(PAST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------------
    // The wrapper in isolation
    // ---------------------------------------------------------------------

    private HomescreenView.WidgetWrapper attachedWrapper(boolean[] dragStarted) {
        HomescreenView.WidgetWrapper wrapper = new HomescreenView.WidgetWrapper(activity);
        wrapper.setDragCallback(() -> dragStarted[0] = true);
        root.addView(wrapper, 200, 200);
        assertTrue("test setup: wrapper must be attached for postDelayed to run",
                wrapper.isAttachedToWindow());
        return wrapper;
    }

    @Test
    public void anUninterruptedPressStillStartsTheDrag() {
        boolean[] started = { false };
        HomescreenView.WidgetWrapper wrapper = attachedWrapper(started);

        wrapper.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 10, 10));
        assertTrue("DOWN must queue the long-press", wrapper.hasPendingLongPress());

        idlePastTimeout();
        assertTrue("holding still past the timeout is still a long-press", started[0]);
    }

    @Test
    public void cancelPendingLongPressStopsTheDrag() {
        boolean[] started = { false };
        HomescreenView.WidgetWrapper wrapper = attachedWrapper(started);

        wrapper.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 10, 10));
        wrapper.cancelPendingLongPress();

        assertFalse(wrapper.hasPendingLongPress());
        idlePastTimeout();
        assertFalse("a cancelled long-press must not start a drag", started[0]);
    }

    @Test
    public void detachingBeforeTheTimeoutDropsTheCallback() {
        // A refresh() between DOWN and the timeout detaches the wrapper; View.postDelayed queues
        // on the AttachInfo handler, which detaching does not clear.
        boolean[] started = { false };
        HomescreenView.WidgetWrapper wrapper = attachedWrapper(started);

        wrapper.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 10, 10));
        root.removeView(wrapper);

        assertFalse("detach must drop the pending long-press", wrapper.hasPendingLongPress());
        idlePastTimeout();
        assertFalse("a detached wrapper must never start a drag", started[0]);
    }

    @Test
    public void aNullDragCallbackIsNotDereferenced() {
        // setDragCallback is documented as mandatory, but a queued runnable outliving its setup
        // must degrade rather than NPE — this view is the user's only home screen.
        HomescreenView.WidgetWrapper wrapper = new HomescreenView.WidgetWrapper(activity);
        root.addView(wrapper, 200, 200);

        wrapper.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 10, 10));
        idlePastTimeout(); // must not throw
    }

    // ---------------------------------------------------------------------
    // End to end: a non-interactive widget inside a real HomescreenView
    // ---------------------------------------------------------------------

    /** A HomescreenView with one 2x2 widget on page 0, laid out at Pixel-9a size. */
    private HomescreenView viewWithWidget() {
        AppWidgetProviderInfo provider = new AppWidgetProviderInfo();
        provider.provider   = new ComponentName("com.example.clock", ".ClockProvider");
        provider.resizeMode = AppWidgetProviderInfo.RESIZE_NONE;
        ShadowAppWidgetManager shadow =
                Shadows.shadowOf(AppWidgetManager.getInstance(activity));
        shadow.addBoundWidget(WIDGET_ID, provider);

        HomeScreenStore store = HomeScreenStore.get(activity);
        store.addItem(HomeItem.widget(0, 0, 0, 2, 2, WIDGET_ID,
                "com.example.clock/.ClockProvider"));

        HomescreenView view = new HomescreenView(activity);
        view.setStore(store);
        view.setWidgetHost(new AppWidgetHost(activity, 1024));
        root.addView(view, W, H);
        view.refresh();
        view.measure(
                View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
        return view;
    }

    private static HomescreenView.WidgetWrapper wrapperIn(HomescreenView view) {
        CellLayout page = view.getPage(0);
        assertNotNull("test setup: page 0 must exist", page);
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            if (child instanceof HomescreenView.WidgetWrapper) {
                return (HomescreenView.WidgetWrapper) child;
            }
        }
        fail("test setup: no WidgetWrapper was built for the stored widget");
        return null;
    }

    /** Centre of the widget's 2x2 footprint, in HomescreenView coordinates. */
    private static float[] overWidget(HomescreenView view) {
        HomescreenView.WidgetWrapper wrapper = wrapperIn(view);
        CellLayout page = view.getPage(0);
        return new float[]{
                page.getLeft() + (wrapper.getLeft() + wrapper.getRight()) / 2f,
                page.getTop()  + (wrapper.getTop()  + wrapper.getBottom()) / 2f };
    }

    @Test
    public void pressingANonInteractiveWidgetQueuesTheLongPress() {
        // Establishes the pre-condition the rest of the tests depend on: the wrapper does get the
        // DOWN (so the timeout is queued) even though it never becomes the touch target.
        HomescreenView view = viewWithWidget();
        float[] p = overWidget(view);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));

        assertTrue(wrapperIn(view).hasPendingLongPress());
    }

    @Test
    public void aSlowSwipeAcrossAWidgetCancelsItsLongPress() {
        HomescreenView view = viewWithWidget();
        float[] p = overWidget(view);
        int slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));
        // Sideways past slop: the user is paging, not long-pressing. The wrapper itself receives
        // nothing here — HomescreenView has to do the cancelling.
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0] - slop * 4, p[1]));

        assertFalse("a swipe must not leave a drag armed",
                wrapperIn(view).hasPendingLongPress());
    }

    @Test
    public void movementBelowSlopLeavesTheLongPressArmed() {
        HomescreenView view = viewWithWidget();
        float[] p = overWidget(view);
        int slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, p[0] + slop - 1, p[1]));

        assertTrue("a finger wobble must not cancel long-press-to-drag",
                wrapperIn(view).hasPendingLongPress());
    }

    @Test
    public void aQuickTapOnAWidgetCancelsItsLongPress() {
        // Same root cause: the wrapper never sees the UP either, so before the fix a plain tap
        // started a drag half a second later.
        HomescreenView view = viewWithWidget();
        float[] p = overWidget(view);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_UP, p[0], p[1]));

        assertFalse("a tap must not leave a drag armed",
                wrapperIn(view).hasPendingLongPress());
    }

    @Test
    public void aCancelledStreamCancelsTheLongPress() {
        HomescreenView view = viewWithWidget();
        float[] p = overWidget(view);

        view.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, p[0], p[1]));
        view.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, p[0], p[1]));

        assertFalse(wrapperIn(view).hasPendingLongPress());
    }

    // ---------------------------------------------------------------------
    // The shared slop rule
    // ---------------------------------------------------------------------

    @Test
    public void slopRuleIsExclusiveOnBothAxes() {
        assertFalse(HomescreenView.movementCancelsLongPress(10f, 10f, 10));
        assertTrue(HomescreenView.movementCancelsLongPress(11f, 0f, 10));
        assertTrue(HomescreenView.movementCancelsLongPress(0f, 11f, 10));
    }
}
