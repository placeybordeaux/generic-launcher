package com.genericlauncher.launcher.personality;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The search filter the caller applies <em>before</em> handing apps to a {@link DrawerStrategy}.
 *
 * <p>Filtering lives here, not in each strategy, so every personality searches identically and a
 * strategy stays a pure ordering/grouping function. Match is a case-insensitive substring on the
 * label — the same "type any part of the name" behaviour the launcher's search box has always had.
 * An empty or blank query matches everything (the drawer's resting state).
 */
public final class DrawerQuery {

    private DrawerQuery() {}

    /** True if {@code app}'s label contains {@code query}, case-insensitively. Blank query = match. */
    public static boolean matches(DrawerApp app, String query) {
        if (query == null) return true;
        String q = query.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) return true;
        return app.label().toLowerCase(Locale.getDefault()).contains(q);
    }

    /** The subset of {@code apps} matching {@code query}, order preserved. */
    public static List<DrawerApp> filter(List<DrawerApp> apps, String query) {
        List<DrawerApp> out = new ArrayList<>();
        for (DrawerApp a : apps) if (matches(a, query)) out.add(a);
        return out;
    }
}
