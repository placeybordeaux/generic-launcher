package com.genericlauncher.launcher.personality.example;

import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.DrawerSection;
import com.genericlauncher.launcher.personality.DrawerStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * The worked example: the source launcher's "apps sorted into rainbow sections" identity,
 * expressed as a plain {@link DrawerStrategy} with <b>no edits to the launcher core</b>. This is
 * the load-bearing proof of the whole plan — the exact personality that was removed, re-added
 * through the seam.
 *
 * <p>It does not classify pixels; that (the {@code android.graphics}-bound part) happens once at
 * app-load and is handed in as {@code groupOf}. Given each app's precomputed colour group, the
 * strategy just buckets and orders — which is all the drawer ordering ever was. Sections come out
 * in group-index order (Black, Red, Orange … White, Rainbow), matching the app's black-first list
 * drawer; within a group, apps are alphabetical.
 *
 * <p>To make this the active personality, a fork points {@code Personality.DRAWER} here (wiring in
 * the real per-app classifier as {@code groupOf}) and changes nothing else. That one-line swap is
 * the product.
 */
public final class ColorGroupStrategy implements DrawerStrategy {

    private final ToIntFunction<DrawerApp> groupOf;

    /** @param groupOf per-app colour group (0–12), precomputed at load; see {@link ColorGroups}. */
    public ColorGroupStrategy(ToIntFunction<DrawerApp> groupOf) {
        this.groupOf = groupOf;
    }

    @Override
    public List<DrawerSection> organize(List<DrawerApp> apps) {
        // TreeMap keeps groups in ascending index order and drops empties for free.
        TreeMap<Integer, List<DrawerApp>> byGroup = new TreeMap<>();
        for (DrawerApp app : apps) {
            byGroup.computeIfAbsent(groupOf.applyAsInt(app), k -> new ArrayList<>()).add(app);
        }
        List<DrawerSection> sections = new ArrayList<>();
        for (var entry : byGroup.entrySet()) {
            List<DrawerApp> bucket = entry.getValue();
            bucket.sort((a, b) -> {
                int c = a.label().compareToIgnoreCase(b.label());
                return c != 0 ? c : a.key().compareTo(b.key());
            });
            sections.add(new DrawerSection(ColorGroups.name(entry.getKey()), bucket));
        }
        return sections;
    }

    @Override
    public String id() { return "color"; }
}
