package com.genericlauncher.launcher.color;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;

import com.genericlauncher.launcher.AppInfo;
import com.genericlauncher.launcher.personality.AppDataSink;
import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.example.ColorGroups;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The colour personality's per-app side table: app key → dominant colour and colour group.
 *
 * <p><b>This is the shape the seam prescribes for personality data.</b> The launcher's
 * {@code AppInfo} carries no colour — a personality does not get to add fields to the launcher's
 * model. Instead this table is keyed by {@link DrawerApp#key()} and filled by the
 * {@link AppDataSink} hook on the loader's worker thread, and the strategy reads it as a plain
 * lookup and so stays a pure function.
 *
 * <p>Extraction is the expensive part (a Palette pass per icon, ~780ms across ~166 icons on a cold
 * start), so results are cached in SharedPreferences and only cache misses are computed. The cache
 * is fully regenerable: a missing or malformed entry costs one Palette pass and nothing else.
 * Entries are keyed by <em>bare package</em> — a work-profile app's artwork is identical to its
 * personal twin, only the badge differs.
 */
public final class ColorTable {

    private static final String TAG   = "ColorTable";
    private static final String PREFS = "personality_colors";

    /** app key → colour group (0–12). Read from the main thread, written from the loader thread. */
    private static final Map<String, Integer> GROUPS = new ConcurrentHashMap<>();
    /** app key → dominant ARGB, for the complementary badge. */
    private static final Map<String, Integer> COLORS = new ConcurrentHashMap<>();

    private ColorTable() {}

    /**
     * Hand-tuned overrides for icons the classifier reads wrong — multi-colour logos the average
     * misses, or whose blended colour lands one group over. Keyed by bare package name.
     *
     * <p>A fork's taste, in its own flavour, exactly where taste belongs.
     */
    private static final Map<String, Integer> OVERRIDES = new HashMap<>();
    static {
        OVERRIDES.put("com.google.android.apps.maps", ColorGroups.RAINBOW); // multi-colour pin
        OVERRIDES.put("com.google.android.gm",        ColorGroups.RAINBOW); // Gmail "M"
        OVERRIDES.put("com.anthropic.claude",         ColorGroups.ORANGE);  // coral reads as red
    }

    /**
     * The group provider handed to {@code ColorGroupStrategy}. A pure lookup: never decodes, never
     * blocks. An app not yet in the table sorts as Gray rather than vanishing — the drawer must
     * render every app it was given, whatever this personality does or does not know yet.
     */
    public static int groupOf(DrawerApp app) {
        Integer g = GROUPS.get(app.key());
        return g != null ? g : ColorGroups.GRAY;
    }

    /** Dominant ARGB for an app key, or grey when unknown. */
    public static int colorOf(String appKey) {
        Integer c = COLORS.get(appKey);
        return c != null ? c : Color.GRAY;
    }

    /**
     * The seam implementation: fill the table from the icons, on the loader's worker thread.
     *
     * <p>Per-app try/catch, deliberately. This runs on the thread where an escaping exception is
     * process death, and this app is the HOME app — one unreadable icon must cost that app's
     * colour, not the home screen.
     */
    public static final AppDataSink SINK = new AppDataSink() {
        @Override
        public void onAppsLoaded(Context context, List<AppInfo> apps) {
            SharedPreferences cache = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = cache.edit();
            boolean dirty = false;

            for (AppInfo app : apps) {
                try {
                    String key = app.key();
                    // Bare package: identical artwork across profiles, only the badge differs.
                    String cacheKey = bare(key);

                    Integer override = OVERRIDES.get(cacheKey);
                    int color;

                    int cached = cache.getInt(cacheKey, 0);
                    if (cached != 0) {
                        color = cached;
                    } else {
                        color = IconColors.dominantColor(app.getIcon());
                        // 0 is the "absent" sentinel above and is fully transparent, so it can
                        // never be a real dominant colour of an opaque icon; nudge it if it ever is.
                        if (color == 0) color = Color.GRAY;
                        editor.putInt(cacheKey, color);
                        dirty = true;
                    }

                    float[] hsb = IconColors.toHsb(color);
                    COLORS.put(key, color);
                    GROUPS.put(key, override != null
                            ? override
                            : ColorGroups.of(hsb[0], hsb[1], hsb[2]));
                } catch (RuntimeException e) {
                    Log.w(TAG, "colour extraction failed for " + app.key()
                            + "; it will sort as Gray", e);
                }
            }

            if (dirty) editor.apply();
        }
    };

    /** The bare package of a key that may be a {@code "pkg#serial"} work-profile composite. */
    private static String bare(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }
}
