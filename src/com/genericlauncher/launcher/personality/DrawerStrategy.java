package com.genericlauncher.launcher.personality;

import java.util.List;

/**
 * THE primary personality seam: how the app drawer is ordered and grouped.
 *
 * <p>This is the one interface most forks will implement. Sort A–Z, sort by usage, group by
 * category, group by colour, cluster your five most-used and bury the rest — all of it is a
 * {@code DrawerStrategy}. The launcher's colour drawer (the app this base was extracted from)
 * is just a {@code ColorGroupStrategy}; nothing about colour lives in the core.
 *
 * <h3>Contract</h3>
 * The input list is <em>already filtered</em> by the caller (search box, hidden-apps, etc.);
 * a strategy only orders and groups. Given that, {@link #organize(List)} must:
 * <ul>
 *   <li>place <b>every</b> input app into <b>exactly one</b> section — the multiset of apps
 *       across all returned sections equals the input (compared by {@link DrawerApp#key()});</li>
 *   <li>never invent an app that was not in the input;</li>
 *   <li>return no null section and no null app.</li>
 * </ul>
 * {@code SeamCheck} / {@code DrawerStrategyContractTest} enforces this against every strategy,
 * so a fork can write one and immediately know whether it holds. Implementations must be pure
 * functions of their input: no I/O, no {@code android.*}, no mutation of the argument list.
 */
public interface DrawerStrategy {

    /**
     * Order and group the already-filtered apps into the sections the drawer will render,
     * top to bottom.
     */
    List<DrawerSection> organize(List<DrawerApp> apps);

    /** Short stable id, for settings persistence and the recipe table (e.g. {@code "alpha"}). */
    String id();
}
