package com.genericlauncher.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Choreographer;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Every app icon is a button pinned to the wallpaper by its top edge, and it swings when the
 * phone moves.
 *
 * <p>Nobody needs this. That is the point: it is the kind of change a launcher you own can take in
 * an afternoon and a launcher you rent never will. See {@code WOBBLE.md}.
 *
 * <p>How it works: {@link #pin} gives an icon a pin head (a foreground drawable, so it sits on top
 * of the badge too), puts the view's pivot exactly under the pin, and gives it a {@link Pendulum}.
 * While the activity is resumed, the accelerometer supplies the force and a Choreographer loop
 * integrates every attached icon and writes {@code setRotation}. The pin is a circle centred on
 * the pivot, so it rotates in place and looks fixed while the icon swings beneath it.
 *
 * <p>The accelerometer reports specific force, {@code f = a - g}. An icon in a moving phone feels
 * {@code g - a = -f}: gravity pulls it down, and a jerk of the phone to the left swings it right,
 * which is the whole effect. In screen coordinates (x right, y down; the launcher is portrait-only)
 * that is {@code (-f.x, +f.y)}.
 *
 * <p>The frame loop only runs while something is moving. Once every icon has settled it stops, and
 * the sensor listener restarts it when the reading changes — so a phone lying still costs a
 * sensor subscription and no frames.
 */
final class Wobble {

    private static final float PIN_RADIUS_DP = 4.5f;
    /** Pin centre below the top of the icon view: inside the bounds, so it is never clipped. */
    private static final float PIN_INSET_DP = 6f;

    /** Below this many rad/s on every icon, for {@link #SETTLE_FRAMES} frames, the loop sleeps. */
    private static final float SETTLED_OMEGA = 0.02f;
    private static final int SETTLE_FRAMES = 30;
    /** A change in the reading bigger than this (m/s^2) wakes a sleeping loop. */
    private static final float WAKE_DELTA = 0.15f;
    /** Low-pass weight on each new sample; the raw accelerometer is jittery enough to buzz. */
    private static final float SMOOTHING = 0.35f;

    private static final List<ImageView> attached = new ArrayList<>();

    private static SensorManager sensors;
    private static boolean resumed;
    private static boolean looping;
    private static int quietFrames;
    private static long lastFrameNanos;

    /** Smoothed in-plane force on the icons, screen coordinates. Upright until told otherwise. */
    private static float forceX = 0f, forceY = SensorManager.GRAVITY_EARTH;
    /** The force when the loop last went to sleep; see {@link #WAKE_DELTA}. */
    private static float sleptX, sleptY;

    private Wobble() {}

    /**
     * Pins {@code icon} by its top edge. {@code seed} picks the icon's swing (use the package
     * name's hash so an app keeps the same wobble across rebuilds). Safe to call again on a
     * recycled view: it re-seeds the pendulum and leaves the rest alone.
     */
    static void pin(ImageView icon, int seed) {
        Pendulum previous = (Pendulum) icon.getTag(R.id.wobble_pendulum);
        Pendulum p = Pendulum.forSeed(seed);
        if (previous != null) {
            // A recycled drawer cell keeps its current swing so a scroll doesn't snap it upright.
            p.theta = previous.theta;
            p.omega = previous.omega;
        }
        icon.setTag(R.id.wobble_pendulum, p);
        if (previous != null) return;

        float density = icon.getResources().getDisplayMetrics().density;
        float inset = PIN_INSET_DP * density;
        icon.setForeground(new PinDrawable(PIN_RADIUS_DP * density, inset));
        icon.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX((r - l) / 2f);
            v.setPivotY(inset);
        });
        icon.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(@NonNull View v) {
                if (!attached.contains(icon)) attached.add(icon);
                unclipContainers(icon);
                wake();
            }
            @Override public void onViewDetachedFromWindow(@NonNull View v) {
                attached.remove(icon);
            }
        });
        if (icon.isAttachedToWindow() && !attached.contains(icon)) {
            attached.add(icon);
            unclipContainers(icon);
        }
    }

    /**
     * A hard shake swings an icon up over its pin, out of the dock or cell that holds it, and a
     * clipped icon there looks like a rendering bug rather than a joke. So the two containers
     * nearest the icon draw children past their edges. Never a scrolling list: a RecyclerView
     * that stops clipping draws rows over the toolbar while scrolling.
     */
    private static void unclipContainers(View icon) {
        android.view.ViewParent p = icon.getParent();
        for (int i = 0; i < 2 && p instanceof android.view.ViewGroup; i++) {
            if (p instanceof androidx.recyclerview.widget.RecyclerView) return;
            ((android.view.ViewGroup) p).setClipChildren(false);
            ((android.view.ViewGroup) p).setClipToPadding(false);
            p = p.getParent();
        }
    }

    /** From the activity's {@code onResume}: start listening and let everything swing. */
    static void resume(Context context) {
        resumed = true;
        if (sensors == null) {
            sensors = (SensorManager) context.getApplicationContext()
                    .getSystemService(Context.SENSOR_SERVICE);
        }
        Sensor accel = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        // No accelerometer (some emulators, Robolectric): the icons simply hang straight.
        if (accel != null) {
            sensors.registerListener(LISTENER, accel, SensorManager.SENSOR_DELAY_GAME);
        }
        wake();
    }

    /** From {@code onPause}: a backgrounded launcher holds no sensor and runs no frames. */
    static void pause() {
        resumed = false;
        if (sensors != null) sensors.unregisterListener(LISTENER);
        if (looping) {
            Choreographer.getInstance().removeFrameCallback(FRAME);
            looping = false;
        }
    }

    private static void wake() {
        if (!resumed || looping || attached.isEmpty()) return;
        looping = true;
        quietFrames = 0;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(FRAME);
    }

    private static final SensorEventListener LISTENER = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            forceX += SMOOTHING * (-e.values[0] - forceX);
            forceY += SMOOTHING * (e.values[1] - forceY);
            if (!looping && (Math.abs(forceX - sleptX) > WAKE_DELTA
                    || Math.abs(forceY - sleptY) > WAKE_DELTA)) {
                wake();
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private static final Choreographer.FrameCallback FRAME = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!resumed) { looping = false; return; }
            float dt = lastFrameNanos == 0 ? 0f : (frameTimeNanos - lastFrameNanos) / 1e9f;
            lastFrameNanos = frameTimeNanos;

            float fastest = 0f;
            for (int i = attached.size() - 1; i >= 0; i--) {
                ImageView icon = attached.get(i);
                Pendulum p = (Pendulum) icon.getTag(R.id.wobble_pendulum);
                if (p == null) continue;
                p.step(dt, forceX, forceY);
                icon.setRotation(p.degrees());
                fastest = Math.max(fastest, Math.abs(p.omega));
            }

            quietFrames = fastest < SETTLED_OMEGA ? quietFrames + 1 : 0;
            if (attached.isEmpty() || quietFrames >= SETTLE_FRAMES) {
                looping = false;
                sleptX = forceX;
                sleptY = forceY;
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** Test seam: the force the icons currently feel, as the sensor listener would leave it. */
    static void setForceForTests(float x, float y) {
        forceX = x;
        forceY = y;
    }

    static boolean isLoopingForTests() { return looping; }

    static int attachedCountForTests() { return attached.size(); }

    @Nullable static Pendulum pendulumOf(View icon) {
        return (Pendulum) icon.getTag(R.id.wobble_pendulum);
    }

    /**
     * A red push-pin head with a highlight, centred on the icon's pivot. Drawn as a foreground so
     * it lands on top of the icon and its badge, and because the pivot is its centre, rotating the
     * view spins it in place — it reads as the one thing on screen that isn't moving.
     */
    static final class PinDrawable extends Drawable {
        private final float radius;
        private final float inset;
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);

        PinDrawable(float radius, float inset) {
            this.radius = radius;
            this.inset = inset;
            shadow.setColor(0x66000000);
            head.setColor(0xFFE53935);
            shine.setColor(0xCCFFFFFF);
        }

        @Override public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX();
            float cy = b.top + inset;
            canvas.drawCircle(cx, cy + radius * 0.25f, radius * 1.05f, shadow);
            canvas.drawCircle(cx, cy, radius, head);
            canvas.drawCircle(cx - radius * 0.35f, cy - radius * 0.35f, radius * 0.32f, shine);
        }

        @Override public void setAlpha(int alpha) {
            head.setAlpha(alpha);
            shine.setAlpha(alpha * 0xCC / 0xFF);
            shadow.setAlpha(alpha * 0x66 / 0xFF);
        }

        @Override public void setColorFilter(@Nullable ColorFilter cf) {
            head.setColorFilter(cf);
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
