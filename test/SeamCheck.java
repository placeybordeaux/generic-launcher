import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.DrawerQuery;
import com.genericlauncher.launcher.personality.DrawerSection;
import com.genericlauncher.launcher.personality.DrawerStrategy;
import com.genericlauncher.launcher.personality.example.ColorGroupStrategy;
import com.genericlauncher.launcher.personality.example.ColorGroups;
import com.genericlauncher.launcher.personality.strategy.AlphabeticalStrategy;
import com.genericlauncher.launcher.personality.strategy.UsageStrategy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Dependency-free contract check for the drawer seam — no JUnit, no Android SDK, so it runs on
 * a plain JVM: {@code javac -d out $(find src test -name '*.java') && java -cp out SeamCheck}.
 *
 * <p>This is the executable form of the {@link DrawerStrategy} contract. In the real Android
 * module the same assertions live in {@code DrawerStrategyContractTest} (Robolectric); here they
 * are a standalone main() so the seam is verifiable in environments without the SDK — which is
 * the point of keeping the strategy framework-free.
 */
public final class SeamCheck {

    private static int checks = 0;

    public static void main(String[] args) {
        List<DrawerApp> apps = sample();

        // A group provider for the colour example: hash the key into a 0..12 group, deterministic.
        ToIntFunction<DrawerApp> groupOf = a -> Math.floorMod(a.key().hashCode(), 13);

        // Every registered strategy must satisfy the universal contract.
        //
        // The *active* strategy — Personality.DRAWER — is deliberately not in this list. Personality
        // is the one class a product flavour replaces, so it lives in the app module's flavour source
        // set and does not exist on this bare-JVM classpath; which strategy is active is a question
        // only a resolved variant can answer. DrawerStrategyContractTest runs these same assertions
        // inside the app module, where it can, and covers Personality.DRAWER there.
        for (DrawerStrategy s : Arrays.asList(
                new AlphabeticalStrategy(true),
                new AlphabeticalStrategy(false),
                new UsageStrategy(),
                new ColorGroupStrategy(groupOf))) {
            checkContract(s, apps);
            checkContract(s, new ArrayList<>()); // empty input is legal and must not throw
        }

        // Behavioural spot-checks beyond the universal contract.
        checkAlphaOrder();
        checkNonLetterBucket();
        checkUsageDescending();
        checkQueryFilter();
        checkColorClassifierBoundaries();
        checkBrownUnreachable();
        checkColorSectionsInGroupOrder();

        System.out.println("SeamCheck: PASS (" + checks + " assertions)");
    }

    /** The universal DrawerStrategy contract: partition, no invention, no nulls. */
    private static void checkContract(DrawerStrategy s, List<DrawerApp> apps) {
        List<DrawerSection> sections = s.organize(apps);
        require(sections != null, s.id() + ": returned null section list");

        List<String> emitted = new ArrayList<>();
        for (DrawerSection sec : sections) {
            require(sec != null, s.id() + ": null section");
            require(sec.title() != null, s.id() + ": null section title");
            for (DrawerApp a : sec.apps()) {
                require(a != null, s.id() + ": null app in section");
                emitted.add(a.key());
            }
        }

        Set<String> input = new HashSet<>();
        for (DrawerApp a : apps) input.add(a.key());

        require(emitted.size() == apps.size(),
                s.id() + ": app count " + emitted.size() + " != input " + apps.size());
        require(new HashSet<>(emitted).size() == emitted.size(),
                s.id() + ": duplicate app across sections");
        require(new HashSet<>(emitted).equals(input),
                s.id() + ": emitted key set differs from input");
    }

    private static void checkAlphaOrder() {
        List<DrawerSection> secs = new AlphabeticalStrategy(true).organize(sample());
        // Letter headers ascend A→Z; the "#" bucket, if present, is strictly last.
        String prevLetter = "";
        boolean seenHash = false;
        for (DrawerSection sec : secs) {
            if (sec.title().equals("#")) { seenHash = true; continue; }
            require(!seenHash, "alpha: a letter header followed the '#' bucket");
            require(sec.title().compareTo(prevLetter) > 0, "alpha: letter headers not ascending");
            prevLetter = sec.title();
        }
    }

    private static void checkNonLetterBucket() {
        List<DrawerApp> apps = new ArrayList<>();
        apps.add(app("1Weather", "com.a", 0));
        apps.add(app("Zoom", "com.z", 0));
        List<DrawerSection> secs = new AlphabeticalStrategy(true).organize(apps);
        boolean hashLast = secs.get(secs.size() - 1).title().equals("#");
        require(hashLast, "alpha: '#' bucket must sort after letters");
    }

    private static void checkUsageDescending() {
        List<DrawerSection> secs = new UsageStrategy().organize(sample());
        require(secs.size() == 1 && secs.get(0).title().isEmpty(),
                "usage: expected one headerless section");
        long prev = Long.MAX_VALUE;
        for (DrawerApp a : secs.get(0).apps()) {
            require(a.usageScore() <= prev, "usage: not descending by score");
            prev = a.usageScore();
        }
    }

    private static void checkQueryFilter() {
        List<DrawerApp> apps = sample();
        require(DrawerQuery.filter(apps, "").size() == apps.size(), "query: blank must match all");
        require(DrawerQuery.filter(apps, "   ").size() == apps.size(), "query: whitespace = blank");
        require(DrawerQuery.filter(apps, null).size() == apps.size(), "query: null must match all");
        // "browser" is stored lowercase; a mixed-case query must still hit it (case-insensitive).
        require(DrawerQuery.filter(apps, "BROW").size() == 1, "query: case-insensitive substring");
        require(DrawerQuery.filter(apps, "zzz").isEmpty(), "query: no match = empty");
    }

    private static void checkColorClassifierBoundaries() {
        // Chromatic hue boundaries (sat/brightness high enough to be chromatic).
        require(ColorGroups.of(0, 1, 1) == ColorGroups.RED, "color: hue 0 -> Red");
        require(ColorGroups.of(20, 1, 1) == ColorGroups.ORANGE, "color: hue 20 -> Orange");
        require(ColorGroups.of(50, 1, 1) == ColorGroups.YELLOW, "color: hue 50 -> Yellow");
        require(ColorGroups.of(100, 1, 1) == ColorGroups.GREEN, "color: hue 100 -> Green");
        require(ColorGroups.of(170, 1, 1) == ColorGroups.TEAL, "color: hue 170 -> Teal");
        require(ColorGroups.of(210, 1, 1) == ColorGroups.BLUE, "color: hue 210 -> Blue");
        require(ColorGroups.of(270, 1, 1) == ColorGroups.PURPLE, "color: hue 270 -> Purple");
        require(ColorGroups.of(300, 1, 1) == ColorGroups.PINK, "color: hue 300 -> Pink");
        require(ColorGroups.of(340, 1, 1) == ColorGroups.RED, "color: hue 340 wraps -> Red");
        // Achromatic by brightness.
        require(ColorGroups.of(0, 0.05f, 0.1f) == ColorGroups.BLACK, "color: dark achromatic -> Black");
        require(ColorGroups.of(0, 0.05f, 0.5f) == ColorGroups.GRAY, "color: mid achromatic -> Gray");
        require(ColorGroups.of(0, 0.05f, 0.95f) == ColorGroups.WHITE, "color: light achromatic -> White");
    }

    /** Characterization test: the ported classifier never returns Brown (a known bug, kept faithful). */
    private static void checkBrownUnreachable() {
        for (int h = 0; h < 360; h++) {
            for (float s = 0.2f; s <= 1f; s += 0.2f) {
                for (float b = 0.4f; b <= 1f; b += 0.2f) {
                    require(ColorGroups.of(h, s, b) != ColorGroups.BROWN,
                            "color: Brown was reached at hue " + h + " (port should match the known bug)");
                }
            }
        }
    }

    private static void checkColorSectionsInGroupOrder() {
        // Two apps forced into known groups: Blue(6) and Red(1). Sections must come out Red then Blue.
        List<DrawerApp> apps = new ArrayList<>();
        apps.add(app("Bluey", "com.blue", 0));
        apps.add(app("Ruby", "com.red", 0));
        ToIntFunction<DrawerApp> groupOf = a -> a.key().equals("com.red") ? ColorGroups.RED : ColorGroups.BLUE;
        List<DrawerSection> secs = new ColorGroupStrategy(groupOf).organize(apps);
        require(secs.size() == 2, "color: expected two sections");
        require(secs.get(0).title().equals("Red"), "color: Red section must precede Blue");
        require(secs.get(1).title().equals("Blue"), "color: Blue section second");
    }

    private static List<DrawerApp> sample() {
        List<DrawerApp> apps = new ArrayList<>();
        apps.add(app("Calendar", "com.cal", 500));
        apps.add(app("browser", "com.web", 900));   // lowercase: exercises case-insensitive sort
        apps.add(app("Files", "com.files", 100));
        apps.add(app("Files", "com.files#10", 0));  // same label, work profile: key tiebreak
        apps.add(app("2048", "com.game", 300));     // non-letter start
        return apps;
    }

    private static DrawerApp app(String label, String key, long usage) {
        return new DrawerApp() {
            public String label() { return label; }
            public String key() { return key; }
            public long usageScore() { return usage; }
            public boolean isWork() { return key.contains("#"); }
        };
    }

    private static void require(boolean cond, String msg) {
        checks++;
        if (!cond) {
            System.err.println("SeamCheck: FAIL — " + msg);
            System.exit(1);
        }
    }
}
