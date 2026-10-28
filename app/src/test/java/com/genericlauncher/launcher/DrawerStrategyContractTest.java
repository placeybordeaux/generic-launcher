package com.genericlauncher.launcher;

import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.DrawerQuery;
import com.genericlauncher.launcher.personality.DrawerSection;
import com.genericlauncher.launcher.personality.DrawerStrategy;
import com.genericlauncher.launcher.personality.Personality;
import com.genericlauncher.launcher.personality.strategy.AlphabeticalStrategy;
import com.genericlauncher.launcher.personality.strategy.UsageStrategy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * The {@link DrawerStrategy} contract, as an executable spec — the Robolectric twin of the
 * repo-root {@code SeamCheck}.
 *
 * <p>Two checks, one contract. {@code SeamCheck} runs on a bare JVM with no Android SDK, which is
 * the loop someone writing a strategy wants ({@code javac} + {@code java}, one second, no device).
 * This one runs inside the app module, which is the only place that can answer the question
 * {@code SeamCheck} structurally cannot: <b>is the strategy this build actually ships</b> —
 * {@link Personality#DRAWER}, contributed by whichever product flavour is being compiled — a legal
 * one? A personality that violates the contract fails here, in the flavour that introduced it.
 *
 * <p><b>Writing a strategy?</b> Add it to {@link #strategies()} and run this. If it is red it names
 * the clause you broke.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DrawerStrategyContractTest {

    /**
     * Every strategy the contract is enforced against. {@code Personality.DRAWER} is the load-bearing
     * entry: it is whatever the active flavour wired up.
     */
    private static List<DrawerStrategy> strategies() {
        return Arrays.asList(
                Personality.DRAWER,
                new AlphabeticalStrategy(true),
                new AlphabeticalStrategy(false),
                new UsageStrategy());
    }

    // -------------------------------------------------------------------------
    // The contract: every input app in exactly one section, no invention, no nulls
    // -------------------------------------------------------------------------

    @Test
    public void everyStrategy_partitionsItsInputExactly() {
        for (DrawerStrategy s : strategies()) assertPartitions(s, sample());
    }

    @Test
    public void everyStrategy_acceptsAnEmptyDrawerWithoutThrowing() {
        // A device mid-boot, or a search matching nothing, hands over an empty list. Returning no
        // sections is legal; throwing is not, and this is the HOME app.
        for (DrawerStrategy s : strategies()) assertPartitions(s, new ArrayList<>());
    }

    @Test
    public void everyStrategy_acceptsASingleApp() {
        for (DrawerStrategy s : strategies()) {
            assertPartitions(s, new ArrayList<>(Arrays.asList(app("Solo", "com.solo", 5))));
        }
    }

    @Test
    public void everyStrategy_leavesItsInputListUnmodified() {
        // Strategies are pure functions of their input. The caller reuses `apps` across renders,
        // so a strategy that sorted in place would quietly reorder everyone else's view.
        for (DrawerStrategy s : strategies()) {
            List<DrawerApp> apps = sample();
            List<String> before = keysOf(apps);
            s.organize(apps);
            assertEquals(s.id() + ": mutated the list it was given", before, keysOf(apps));
        }
    }

    @Test
    public void everyStrategy_isDeterministic() {
        // Two renders of an unchanged app list must produce an unchanged drawer; anything else
        // makes icons move under the user's finger between repaints.
        for (DrawerStrategy s : strategies()) {
            assertEquals(s.id() + ": not deterministic",
                    flatKeys(s.organize(sample())), flatKeys(s.organize(sample())));
        }
    }

    @Test
    public void everyStrategy_hasANonBlankId() {
        Set<String> seen = new HashSet<>();
        for (DrawerStrategy s : strategies()) {
            assertNotNull("null id()", s.id());
            assertFalse("blank id()", s.id().trim().isEmpty());
            seen.add(s.id());
        }
        // Not an assertion about count — Personality.DRAWER legitimately shares an id with the
        // default it points at. Only that ids exist and are usable as settings keys.
        assertFalse(seen.isEmpty());
    }

    // -------------------------------------------------------------------------
    // The active personality specifically
    // -------------------------------------------------------------------------

    @Test
    public void thisBuildsPersonality_isWired() {
        assertNotNull("Personality.DRAWER must not be null — the drawer cannot render without it",
                Personality.DRAWER);
    }

    @Test
    public void thisBuildsPersonality_survivesTheFilterItIsAlwaysHandedFirst() {
        // Real render path: DrawerQuery.filter(...) then organize(...). A strategy never sees the
        // unfiltered list, so the contract has to hold on the filtered one too.
        List<DrawerApp> filtered = DrawerQuery.filter(sample(), "a");
        assertFalse("fixture should leave something to organize", filtered.isEmpty());
        assertPartitions(Personality.DRAWER, filtered);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Asserts the partition/no-invention/no-nulls contract for one strategy and one input. */
    private static void assertPartitions(DrawerStrategy s, List<DrawerApp> apps) {
        List<DrawerSection> sections = s.organize(apps);
        assertNotNull(s.id() + ": returned a null section list", sections);

        List<String> emitted = new ArrayList<>();
        for (DrawerSection sec : sections) {
            assertNotNull(s.id() + ": null section", sec);
            assertNotNull(s.id() + ": null section title", sec.title());
            assertNotNull(s.id() + ": null app list", sec.apps());
            for (DrawerApp a : sec.apps()) {
                assertNotNull(s.id() + ": null app in section", a);
                emitted.add(a.key());
            }
        }

        assertEquals(s.id() + ": section apps do not add up to the input", apps.size(), emitted.size());
        assertEquals(s.id() + ": the same app appeared in more than one section",
                emitted.size(), new HashSet<>(emitted).size());
        assertEquals(s.id() + ": emitted a different set of apps than it was given",
                new HashSet<>(keysOf(apps)), new HashSet<>(emitted));
    }

    private static List<String> flatKeys(List<DrawerSection> sections) {
        List<String> out = new ArrayList<>();
        for (DrawerSection s : sections) {
            out.add("#" + s.title());
            for (DrawerApp a : s.apps()) out.add(a.key());
        }
        return out;
    }

    private static List<String> keysOf(List<DrawerApp> apps) {
        List<String> out = new ArrayList<>();
        for (DrawerApp a : apps) out.add(a.key());
        return out;
    }

    /**
     * A fixture with the shapes that break naive strategies: mixed case, a duplicate label under
     * distinct keys, a non-letter initial, an empty label, a work-profile app, and zero-usage apps.
     */
    private static List<DrawerApp> sample() {
        return new ArrayList<>(Arrays.asList(
                app("Calendar", "com.cal", 90),
                app("browser", "com.browse", 40),
                app("Alarm", "com.alarm", 0),
                app("alarm", "com.alarm.other", 7),
                app("1Weather", "com.weather", 3),
                app("", "com.unnamed", 0),
                work("Mail", "com.mail#10"),
                app("Zoom", "com.zoom", 0)));
    }

    private static DrawerApp app(String label, String key, long usage) {
        AppInfo a = new AppInfo(label, key, null);
        a.setUsageScore(usage);
        return a;
    }

    private static DrawerApp work(String label, String key) {
        AppInfo a = new AppInfo(label, key, null);
        a.setWork(true);
        return a;
    }
}
