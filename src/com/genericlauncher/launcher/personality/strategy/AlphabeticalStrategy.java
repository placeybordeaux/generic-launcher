package com.genericlauncher.launcher.personality.strategy;

import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.DrawerSection;
import com.genericlauncher.launcher.personality.DrawerStrategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The taste-free default: apps A–Z, optionally under single-letter headers.
 *
 * <p>Sort is case-insensitive by label. Apps whose label does not start with a letter
 * (numbers, symbols, emoji) collect under a trailing {@code "#"} header. Ties on label are
 * broken by {@link DrawerApp#key()} so the order is deterministic — the contract check relies
 * on that, and so does a stable-looking drawer.
 */
public final class AlphabeticalStrategy implements DrawerStrategy {

    private final boolean headers;

    /** @param headers true to emit A, B, C… (and {@code #}) section headers; false for one flat list. */
    public AlphabeticalStrategy(boolean headers) {
        this.headers = headers;
    }

    @Override
    public List<DrawerSection> organize(List<DrawerApp> apps) {
        List<DrawerApp> sorted = new ArrayList<>(apps);
        sorted.sort(Comparator
                // Letter-initial apps first (A–Z); numbers/symbols collect after Z, not before A,
                // because their code points are lower than letters and a raw sort would float them
                // to the top. This is the "#-goes-last" convention every A–Z index uses.
                .comparingInt((DrawerApp a) -> startsWithLetter(a.label()) ? 0 : 1)
                .thenComparing(a -> a.label(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(DrawerApp::key));

        List<DrawerSection> sections = new ArrayList<>();
        if (!headers) {
            if (!sorted.isEmpty()) sections.add(new DrawerSection("", sorted));
            return sections;
        }

        String current = null;
        List<DrawerApp> bucket = null;
        for (DrawerApp app : sorted) {
            String letter = bucketFor(app.label());
            if (!letter.equals(current)) {
                if (bucket != null) sections.add(new DrawerSection(current, bucket));
                current = letter;
                bucket = new ArrayList<>();
            }
            bucket.add(app);
        }
        if (bucket != null) sections.add(new DrawerSection(current, bucket));
        return sections;
    }

    /** Uppercase first letter, or {@code "#"} for empty / non-letter starts. */
    private static String bucketFor(String label) {
        if (label == null || label.isEmpty()) return "#";
        int cp = label.codePointAt(0);
        if (!Character.isLetter(cp)) return "#";
        return new String(Character.toChars(Character.toUpperCase(cp)));
    }

    private static boolean startsWithLetter(String label) {
        return label != null && !label.isEmpty() && Character.isLetter(label.codePointAt(0));
    }

    @Override
    public String id() { return "alpha"; }
}
