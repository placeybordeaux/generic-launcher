package com.genericlauncher.launcher.personality;

import java.util.Collections;
import java.util.List;

/**
 * A titled run of apps in the drawer. An empty {@link #title()} renders headerless — the
 * caller draws no section header for it, which is how the flat views (usage, search results)
 * express "one section, no label".
 */
public final class DrawerSection {

    private final String title;
    private final List<DrawerApp> apps;

    public DrawerSection(String title, List<DrawerApp> apps) {
        this.title = title == null ? "" : title;
        this.apps = Collections.unmodifiableList(apps);
    }

    /** Section header text; empty string means "no header". */
    public String title() { return title; }

    /** Apps in this section, in the order the strategy chose. Unmodifiable. */
    public List<DrawerApp> apps() { return apps; }
}
