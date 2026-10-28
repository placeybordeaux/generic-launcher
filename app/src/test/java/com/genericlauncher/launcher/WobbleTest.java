package com.genericlauncher.launcher;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.SensorEventBuilder;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The Android half: a pinned icon gets a pin and a pivot under it, the accelerometer drives the
 * swing through the frame loop, and nothing runs while the launcher is paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WobbleTest {

    private ActivityController<Activity> controller;
    private Activity activity;
    private ImageView icon;
    private ShadowSensorManager sensors;
    private Sensor accel;

    @Before
    public void setUp() {
        SensorManager sm = (SensorManager) androidx.test.core.app.ApplicationProvider
                .getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
        sensors = shadowOf(sm);
        accel = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER);
        sensors.addSensor(accel);

        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        FrameLayout root = new FrameLayout(activity);
        icon = new ImageView(activity);
        Wobble.pin(icon, "com.example.mail".hashCode());
        root.addView(icon, new FrameLayout.LayoutParams(150, 150));
        activity.setContentView(root);
        ShadowLooper.idleMainLooper();
        Wobble.setForceForTests(0f, SensorManager.GRAVITY_EARTH);
    }

    @After
    public void tearDown() {
        Wobble.pause();
        controller.close();
    }

    /** Device coordinates, as the accelerometer reports specific force: f = a - g. */
    private void accelerometerReads(float x, float y) {
        sensors.sendSensorEventToListeners(SensorEventBuilder.newBuilder()
                .setSensor(accel)
                .setValues(new float[]{x, y, 0f})
                .setTimestamp(System.nanoTime())
                .build());
    }

    private void frames(int ms) {
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
    }

    @Test
    public void aPinnedIconHasAPinAndPivotsUnderIt() {
        assertTrue(icon.getForeground() instanceof Wobble.PinDrawable);
        assertEquals("pivot is the icon's horizontal centre", 75f, icon.getPivotX(), 0.5f);
        assertTrue("and just inside its top edge, where the pin is",
                icon.getPivotY() > 0 && icon.getPivotY() < 40);
        assertNotNull(Wobble.pendulumOf(icon));
    }

    @Test
    public void repinningARecycledViewKeepsItsSwingAndAddsNothing() {
        Pendulum p = Wobble.pendulumOf(icon);
        p.theta = 0.3f;
        Wobble.pin(icon, "com.example.maps".hashCode());
        assertEquals(0.3f, Wobble.pendulumOf(icon).theta, 0f);
        assertEquals("a recycled cell must not be registered twice", 1, Wobble.attachedCountForTests());
    }

    /**
     * The whole effect, end to end: jerk the phone left (the accelerometer reads a strong
     * negative x on top of gravity's +y) and the icon swings out to the right — a negative
     * rotation — then hangs straight again once the phone is still.
     */
    @Test
    public void jigglingThePhoneSwingsTheIconAndItSettlesBack() {
        Wobble.resume(activity);
        for (int i = 0; i < 4; i++) {
            accelerometerReads(-25f, SensorManager.GRAVITY_EARTH);
            frames(20);
        }
        assertTrue("the icon should have swung right: " + icon.getRotation(),
                icon.getRotation() < -2f);

        // Enough samples for the low-pass filter to forget the jerk; a real sensor streams them.
        for (int i = 0; i < 60; i++) accelerometerReads(0f, SensorManager.GRAVITY_EARTH);
        frames(8000);
        assertEquals("and come back to hanging straight", 0f, icon.getRotation(), 1f);
        assertFalse("with nothing moving, the frame loop stops", Wobble.isLoopingForTests());
    }

    @Test
    public void turningThePhoneOnItsSideMakesTheIconHangSideways() {
        Wobble.resume(activity);
        // Rotated a quarter-turn clockwise: gravity is along the device's -x, so the reading is +x.
        for (int i = 0; i < 60; i++) accelerometerReads(SensorManager.GRAVITY_EARTH, 0f);
        frames(8000);
        // The force on the icon is towards the screen's left; hanging left is a clockwise turn.
        assertEquals(90f, icon.getRotation(), 8f);
    }

    @Test
    public void aPausedLauncherHoldsNoSensorAndRunsNoFrames() {
        Wobble.resume(activity);
        assertEquals(1, sensors.getListeners().size());

        Wobble.pause();

        assertEquals(0, sensors.getListeners().size());
        assertFalse(Wobble.isLoopingForTests());
    }
}
