package com.genericlauncher.launcher.personality.strategy;

import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.DrawerSection;
import com.genericlauncher.launcher.personality.DrawerStrategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Most-used first, one flat headerless section.
 *
 * <p>Sorted by {@link DrawerApp#usageScore()} descending. Work-profile apps report a score of
 * 0 (usage stats cover the personal profile only), so they fall to the bottom naturally rather
 * than needing a special case. Ties — including the whole all-zero case when usage access has
 * not been granted — break by label so the view is still deterministic and readable instead of
 * arbitrary.
 */
public final class UsageStrategy implements DrawerStrategy {

    @Override
    public List<DrawerSection> organize(List<DrawerApp> apps) {
        List<DrawerApp> sorted = new ArrayList<>(apps);
        sorted.sort(Comparator
                .comparingLong(DrawerApp::usageScore).reversed()
                .thenComparing(a -> a.label(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DrawerApp::key));

        List<DrawerSection> sections = new ArrayList<>();
        if (!sorted.isEmpty()) sections.add(new DrawerSection("", sorted));
        return sections;
    }

    @Override
    public String id() { return "usage"; }
}
