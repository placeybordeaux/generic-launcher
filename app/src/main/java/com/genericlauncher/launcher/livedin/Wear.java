package com.genericlauncher.launcher.livedin;

/**
 * The age maths behind Lived-in: how worn, how dusty and how cobwebbed one icon is, from how it
 * has been used. Pure Java — no {@code android.*} — so the bucketing is testable without a device
 * and the renderer only ever sees a small packed integer.
 *
 * <p>A <em>look</em> is three quantised levels packed into an int (see {@link #pack}). Quantising
 * is what makes the effect cheap: thousands of usage histories collapse onto a few dozen looks,
 * each rendered once and cached, and an icon only redraws when it crosses a bucket boundary.
 */
public final class Wear {

    public static final long MINUTE = 60_000L;
    public static final long DAY = 24 * 60 * MINUTE;

    /** {@link #look} result for an icon that shows no age at all — the renderer draws it raw. */
    public static final int PRISTINE = 0;

    /**
     * Which effects are on. Persisted by ordinal: <b>append only</b>.
     */
    public enum Variant {
        OFF("Off  ·  icons as the platform draws them"),
        PATINA("Patina only  ·  well-used icons wear smooth"),
        DUST("Dust & cobwebs only  ·  neglected icons gather dust"),
        FULL("Full lived-in  ·  patina, dust and cobwebs"),
        TIMELAPSE("Time-lapse demo  ·  invented history, a day passes every minute");

        public final String label;

        Variant(String label) { this.label = label; }

        boolean patina() { return this == PATINA || this == FULL || this == TIMELAPSE; }

        boolean dust() { return this == DUST || this == FULL || this == TIMELAPSE; }

        public static String[] labels() {
            Variant[] v = values();
            String[] out = new String[v.length];
            for (int i = 0; i < v.length; i++) out[i] = v[i].label;
            return out;
        }
    }

    /** What the patina is made of. Persisted by ordinal: <b>append only</b>. */
    public enum Style {
        BRASS("Brass  ·  warm, burnished, thumb-polished"),
        LEATHER("Leather  ·  darker, rubbed soft at the edges"),
        KEEPSAKE("Keepsake  ·  faded like an old photograph");

        public final String label;

        Style(String label) { this.label = label; }

        public static String[] labels() {
            Style[] v = values();
            String[] out = new String[v.length];
            for (int i = 0; i < v.length; i++) out[i] = v[i].label;
            return out;
        }
    }

    // -------------------------------------------------------------------------
    // Bucket boundaries. Chosen so a normal week of use changes almost nothing: dust needs
    // days of neglect, cobwebs a month, and the top patina bucket a few months of daily use.
    // -------------------------------------------------------------------------

    /** Effective-use thresholds for patina levels 1, 2, 3. */
    static final double[] PATINA_USES = {8, 25, 80};
    /** Idle thresholds for dust levels 1, 2, 3. */
    static final long[] DUST_IDLE = {4 * DAY, 10 * DAY, 21 * DAY};
    /** Idle thresholds for cobweb levels 1 (a web) and 2 (a web with a resident). */
    static final long[] COBWEB_IDLE = {30 * DAY, 75 * DAY};

    /**
     * Minutes in the foreground that count as one launch, when Usage Access supplies foreground
     * time. Roughly one session; it only has to put the two signals on the same scale.
     */
    static final long FOREGROUND_PER_USE = 3 * MINUTE;

    /** Time-lapse: one real minute is this many real minutes of ageing (one day). */
    public static final long TIMELAPSE_SPEED = DAY / MINUTE;
    /**
     * Time-lapse: a launch counts this many times toward patina, so a few demo taps visibly
     * polish an icon.
     */
    static final int TIMELAPSE_USE_WEIGHT = 10;

    private Wear() {}

    public static int pack(int patina, int dust, int cobweb) {
        return (patina & 0xF) | (dust & 0xF) << 4 | (cobweb & 0xF) << 8;
    }

    public static int patinaOf(int look) { return look & 0xF; }

    public static int dustOf(int look) { return (look >> 4) & 0xF; }

    public static int cobwebOf(int look) { return (look >> 8) & 0xF; }

    /** True when {@code after} has less dust or fewer cobwebs than {@code before}. */
    public static boolean dustCleared(int before, int after) {
        return dustOf(after) < dustOf(before) || cobwebOf(after) < cobwebOf(before);
    }

    static int patinaLevel(double uses) {
        int level = 0;
        while (level < PATINA_USES.length && uses >= PATINA_USES[level]) level++;
        return level;
    }

    static int dustLevel(long idleMs) {
        int level = 0;
        while (level < DUST_IDLE.length && idleMs >= DUST_IDLE[level]) level++;
        return level;
    }

    static int cobwebLevel(long idleMs) {
        int level = 0;
        while (level < COBWEB_IDLE.length && idleMs >= COBWEB_IDLE[level]) level++;
        return level;
    }

    /**
     * The look of one icon.
     *
     * @param key           the app's key; only used to invent a history in time-lapse
     * @param launches      launches the launcher has recorded itself
     * @param lastLaunchMs  wall time of the last recorded launch, 0 if never
     * @param seenSinceMs   when the launcher could first have seen this app launched: the later of
     *                      the launcher's own install and the app's. Without this a fresh install
     *                      would show every icon cobwebbed on day one.
     * @param foregroundMs  foreground time from Usage Access, 0 when not granted
     * @param timelapseStartMs when time-lapse was switched on (ignored by other variants)
     */
    public static int look(Variant variant, String key, long launches, long lastLaunchMs,
                           long seenSinceMs, long foregroundMs, long nowMs,
                           long timelapseStartMs) {
        if (variant == Variant.OFF) return PRISTINE;

        double uses;
        long idleMs;
        if (variant == Variant.TIMELAPSE) {
            int h = mix(key.hashCode());
            long sinceStart = Math.max(0, nowMs - timelapseStartMs) * TIMELAPSE_SPEED;
            if (lastLaunchMs >= timelapseStartMs && lastLaunchMs > 0) {
                // Launched during the demo: its own launch is the truth, sped up.
                idleMs = Math.max(0, nowMs - lastLaunchMs) * TIMELAPSE_SPEED;
            } else {
                idleMs = fakeIdleMs(h) + sinceStart;
            }
            uses = fakeUses(h) + (double) launches * TIMELAPSE_USE_WEIGHT;
        } else {
            uses = launches + (double) foregroundMs / FOREGROUND_PER_USE;
            long lastSeen = Math.max(lastLaunchMs, seenSinceMs);
            idleMs = Math.max(0, nowMs - lastSeen);
        }

        int patina = variant.patina() ? patinaLevel(uses) : 0;
        int dust = variant.dust() ? dustLevel(idleMs) : 0;
        int cobweb = variant.dust() ? cobwebLevel(idleMs) : 0;
        return pack(patina, dust, cobweb);
    }

    // -------------------------------------------------------------------------
    // Time-lapse's invented history. Spread so a screenful of icons shows every bucket at once:
    // a third worn, a third dusty, some cobwebbed, a few untouched.
    // -------------------------------------------------------------------------

    private static final long[] FAKE_IDLE_DAYS = {0, 0, 1, 2, 5, 12, 25, 40, 90};
    private static final int[] FAKE_USES = {0, 2, 10, 30, 90, 200};

    static long fakeIdleMs(int h) {
        return FAKE_IDLE_DAYS[Math.floorMod(h, FAKE_IDLE_DAYS.length)] * DAY;
    }

    static double fakeUses(int h) {
        return FAKE_USES[Math.floorMod(h >>> 8, FAKE_USES.length)];
    }

    /**
     * Murmur3's finaliser. {@code String.hashCode} is specified, so stable across runs and devices,
     * but its low bits are poorly spread for similar package names; this scrambles them.
     */
    public static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
