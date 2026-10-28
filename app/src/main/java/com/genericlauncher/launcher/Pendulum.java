package com.genericlauncher.launcher;

/**
 * One icon's swing: a damped pendulum hanging from a pin at the top of the icon.
 *
 * <p>Pure Java and framework-free so the physics can be unit-tested without a sensor or a
 * Choreographer; {@link Wobble} owns the Android half.
 *
 * <p>{@link #theta} is the icon's rotation in radians, clockwise-positive to match
 * {@code View.setRotation}. With the pin at the top-centre, the icon's centre of mass sits at
 * {@code (-L sin θ, L cos θ)} from the pin in screen coordinates (y down), so an in-plane force
 * {@code (fx, fy)} per unit mass gives
 *
 * <pre>
 *   θ'' = -(fx cos θ + fy sin θ) / L  -  damping·θ'  -  stiffness·θ
 * </pre>
 *
 * The stiffness term is the pin's own friction: without it an icon on a phone lying flat on a
 * table — where almost no gravity is in the plane of the screen — would drift around forever
 * instead of settling upright.
 */
final class Pendulum {

    /** Integration step cap. The stiffest icon rings at ~2.5 Hz; 4 ms keeps Euler well-behaved. */
    private static final float MAX_STEP_S = 0.004f;

    final float length;     // metres
    final float damping;    // 1/s
    final float stiffness;  // 1/s^2

    float theta;            // radians, clockwise-positive
    float omega;            // radians/s

    Pendulum(float length, float damping, float stiffness) {
        if (length <= 0f) throw new IllegalArgumentException("length must be positive: " + length);
        this.length = length;
        this.damping = damping;
        this.stiffness = stiffness;
    }

    /**
     * A pendulum whose proportions are picked from {@code seed}, so every icon swings at a
     * slightly different rate and a shaken home screen ripples rather than marching in lockstep.
     * Same seed, same pendulum: an icon keeps its personality across rebuilds.
     */
    static Pendulum forSeed(int seed) {
        // Three independent-ish fractions in [0, 1) from one hash.
        int h = seed * 0x9E3779B1;
        float a = ((h >>> 8) & 0xFF) / 256f;
        float b = ((h >>> 16) & 0xFF) / 256f;
        float c = ((h >>> 24) & 0xFF) / 256f;
        return new Pendulum(
                0.035f + 0.03f * a,   // 3.5–6.5 cm: ~2–2.7 Hz swings
                1.6f + 1.2f * b,      // a few lively swings, calm within two or three seconds
                3f + 3f * c);
    }

    /** Advances by {@code dt} seconds under the in-plane force {@code (fx, fy)}, screen coords. */
    void step(float dt, float fx, float fy) {
        if (dt <= 0f) return;
        // A frame that arrives after a long stall (the launcher was off screen) must not be
        // integrated as one giant step, which would fling every icon.
        dt = Math.min(dt, 0.1f);
        while (dt > 0f) {
            float h = Math.min(dt, MAX_STEP_S);
            float alpha = -(fx * (float) Math.cos(theta) + fy * (float) Math.sin(theta)) / length
                    - damping * omega - stiffness * theta;
            omega += alpha * h;   // semi-implicit Euler: velocity first, then position
            theta += omega * h;
            dt -= h;
        }
        // Keep θ in (-π, π] so the stiffness term pulls the short way round after a full spin.
        if (theta > Math.PI) theta -= (float) (2 * Math.PI);
        else if (theta < -Math.PI) theta += (float) (2 * Math.PI);
    }

    float degrees() {
        return (float) Math.toDegrees(theta);
    }
}
