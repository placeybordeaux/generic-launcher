package com.genericlauncher.launcher;

import org.junit.Test;

import static org.junit.Assert.*;

/** The swing itself, without a sensor or a frame loop. Forces are screen coordinates, m/s^2. */
public class PendulumTest {

    private static final float G = 9.81f;

    private static void run(Pendulum p, float seconds, float fx, float fy) {
        for (float t = 0; t < seconds; t += 1 / 120f) p.step(1 / 120f, fx, fy);
    }

    @Test
    public void anUprightPhoneLeavesAnIconAtRestHangingStraight() {
        Pendulum p = Pendulum.forSeed(42);
        run(p, 2f, 0f, G);
        assertEquals(0f, p.theta, 1e-6f);
        assertEquals(0f, p.omega, 1e-6f);
    }

    @Test
    public void aNudgedIconSwingsBackAndSettles() {
        Pendulum p = Pendulum.forSeed(7);
        p.theta = 0.6f;

        boolean crossed = false;
        for (float t = 0; t < 1f; t += 1 / 120f) {
            p.step(1 / 120f, 0f, G);
            if (p.theta < 0) crossed = true;
        }
        assertTrue("it should swing through the bottom, not creep back", crossed);

        run(p, 6f, 0f, G);
        assertEquals("and come to rest hanging straight", 0f, p.theta, 0.01f);
    }

    /**
     * A phone turned on its side: gravity now points to the screen's right, so the icon hangs to
     * the right of its pin. With the pin at the top, "hanging right" is a counter-clockwise turn,
     * i.e. a negative {@code View.setRotation}. The pin's stiffness holds it a touch short.
     */
    @Test
    public void tiltingThePhoneMakesTheIconHangTowardsTheNewDown() {
        Pendulum p = Pendulum.forSeed(3);
        run(p, 8f, G, 0f);
        assertEquals(-Math.PI / 2, p.theta, 0.1);
    }

    /**
     * The giggle. Jerking the phone left is a force to the right on everything in it, so the icon
     * lags behind and swings out to the right before gravity brings it back.
     */
    @Test
    public void aJerkSwingsTheIconTheOtherWayAndItSwingsBack() {
        Pendulum p = Pendulum.forSeed(11);
        run(p, 0.08f, 20f, G);
        assertTrue("the icon should swing right (negative rotation): " + p.theta, p.theta < -0.05f);

        run(p, 6f, 0f, G);
        assertEquals(0f, p.theta, 0.01f);
    }

    @Test
    public void lyingFlatOnATableTheIconStillSettlesUpright() {
        Pendulum p = Pendulum.forSeed(5);
        p.theta = 1f;
        run(p, 10f, 0f, 0f);   // no gravity in the plane of the screen
        assertEquals(0f, p.theta, 0.02f);
    }

    @Test
    public void aLongStallIsNotIntegratedAsOneGiantStep() {
        Pendulum p = Pendulum.forSeed(9);
        p.step(30f, 30f, G);   // half a minute off screen, then a violent first frame
        assertTrue("one frame must not fling the icon round: " + p.theta, Math.abs(p.theta) < Math.PI);
        assertFalse(Float.isNaN(p.theta));
    }

    @Test
    public void theSameAppAlwaysGetsTheSameSwingAndNeighboursDiffer() {
        Pendulum a = Pendulum.forSeed("com.example.mail".hashCode());
        Pendulum b = Pendulum.forSeed("com.example.mail".hashCode());
        Pendulum c = Pendulum.forSeed("com.example.maps".hashCode());
        assertEquals(a.length, b.length, 0f);
        assertEquals(a.damping, b.damping, 0f);
        assertNotEquals("neighbours should not swing in lockstep", a.length, c.length, 1e-6f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void aPendulumWithNoLengthIsRefused() {
        new Pendulum(0f, 1f, 1f);
    }
}
