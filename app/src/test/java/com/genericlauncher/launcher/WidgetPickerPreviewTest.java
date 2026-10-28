package com.genericlauncher.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the widget picker's preview plumbing: the bounded cache that stops a preview
 * being re-decoded on every bind, the sample-size maths that keeps a multi-megabyte PNG from
 * being decoded at full resolution, and the no-op check that makes a debounced keystroke cheap.
 *
 * <p>Robolectric-free on purpose — {@link WidgetPicker.PreviewCache} takes an explicit cost per
 * entry rather than measuring a {@code Drawable}, so eviction is testable with null values.
 */
public class WidgetPickerPreviewTest {

    // -------------------------------------------------------------------------
    // PreviewCache
    // -------------------------------------------------------------------------

    @Test
    public void cache_missesForAnUnknownKey() {
        WidgetPicker.PreviewCache cache = new WidgetPicker.PreviewCache(1000);
        assertFalse(cache.contains("a"));
        assertNull(cache.get("a"));
    }

    @Test
    public void cache_recordsANullPreviewAsAHit() {
        // A provider with no preview image must be remembered, or every bind retries the decode.
        WidgetPicker.PreviewCache cache = new WidgetPicker.PreviewCache(1000);
        cache.put("a", null, 1);

        assertTrue(cache.contains("a"));
        assertNull(cache.get("a"));
    }

    @Test
    public void cache_evictsLeastRecentlyUsedOnceOverBudget() {
        WidgetPicker.PreviewCache cache = new WidgetPicker.PreviewCache(300);
        cache.put("a", null, 100);
        cache.put("b", null, 100);
        cache.put("c", null, 100);
        cache.put("d", null, 100);

        assertFalse(cache.contains("a"));
        assertTrue(cache.contains("b"));
        assertTrue(cache.contains("c"));
        assertTrue(cache.contains("d"));
        assertEquals(300, cache.sizeBytes());
    }

    @Test
    public void cache_readingAnEntryMakesItRecent() {
        WidgetPicker.PreviewCache cache = new WidgetPicker.PreviewCache(300);
        cache.put("a", null, 100);
        cache.put("b", null, 100);
        cache.put("c", null, 100);
        cache.get("a");                 // "b" is now the least recently used
        cache.put("d", null, 100);

        assertTrue(cache.contains("a"));
        assertFalse(cache.contains("b"));
    }

    @Test
    public void cache_replacingAKeyDoesNotDoubleCountItsCost() {
        WidgetPicker.PreviewCache cache = new WidgetPicker.PreviewCache(1000);
        cache.put("a", null, 100);
        cache.put("a", null, 250);

        assertEquals(1, cache.count());
        assertEquals(250, cache.sizeBytes());
    }

    @Test
    public void cache_alwaysKeepsTheEntryJustAdded() {
        // A single preview larger than the whole budget must still be usable for its own bind.
        WidgetPicker.PreviewCache cache = new WidgetPicker.PreviewCache(100);
        cache.put("a", null, 50);
        cache.put("big", null, 9999);

        assertTrue(cache.contains("big"));
        assertEquals(1, cache.count());
    }

    // -------------------------------------------------------------------------
    // sampleSize
    // -------------------------------------------------------------------------

    @Test
    public void sampleSize_isOneWhenTheImageAlreadyFits() {
        assertEquals(1, WidgetPicker.sampleSize(400, 300, 800, 600));
        assertEquals(1, WidgetPicker.sampleSize(800, 600, 800, 600));
    }

    @Test
    public void sampleSize_halvesUntilTheImageNoLongerCoversTheBox() {
        // 2000x2000 into 500x500: /2 -> 1000, /4 -> 500, /8 would undershoot.
        assertEquals(4, WidgetPicker.sampleSize(2000, 2000, 500, 500));
        assertEquals(2, WidgetPicker.sampleSize(1600, 1200, 800, 600));
    }

    @Test
    public void sampleSize_neverSubsamplesTheShortAxisAway() {
        // Wide-but-short preview: halving would take the height below the box, so leave it.
        assertEquals(1, WidgetPicker.sampleSize(4000, 100, 500, 500));
    }

    @Test
    public void sampleSize_degradesToOneOnNonsenseInput() {
        assertEquals(1, WidgetPicker.sampleSize(0, 0, 500, 500));
        assertEquals(1, WidgetPicker.sampleSize(500, 500, 0, 0));
        assertEquals(1, WidgetPicker.sampleSize(-1, -1, -1, -1));
    }

    // -------------------------------------------------------------------------
    // sameEntries — the guard that makes a debounced keystroke free
    // -------------------------------------------------------------------------

    private static WidgetPicker.Entry e(String app, String widget) {
        return new WidgetPicker.Entry(app, widget, null);
    }

    @Test
    public void sameEntries_trueForTheIdenticalSequence() {
        WidgetPicker.Entry a = e("Calendar", "Month");
        WidgetPicker.Entry b = e("Clock", "Analog");
        assertTrue(WidgetPicker.sameEntries(Arrays.asList(a, b), Arrays.asList(a, b)));
    }

    @Test
    public void sameEntries_falseWhenOrderOrContentDiffers() {
        WidgetPicker.Entry a = e("Calendar", "Month");
        WidgetPicker.Entry b = e("Clock", "Analog");
        assertFalse(WidgetPicker.sameEntries(Arrays.asList(a, b), Arrays.asList(b, a)));
        assertFalse(WidgetPicker.sameEntries(Arrays.asList(a, b), Arrays.asList(a)));
        assertFalse(WidgetPicker.sameEntries(
                Arrays.asList(a), Arrays.asList(e("Calendar", "Month"))));
    }

    @Test
    public void sameEntries_falseWhenEitherSideIsNull() {
        // null means "nothing has been bound yet", which must never be treated as a match.
        List<WidgetPicker.Entry> empty = new ArrayList<>();
        assertFalse(WidgetPicker.sameEntries(null, empty));
        assertFalse(WidgetPicker.sameEntries(empty, null));
    }

    // -------------------------------------------------------------------------
    // Entry.previewKey
    // -------------------------------------------------------------------------

    @Test
    public void previewKey_distinguishesProfilesOfTheSameProvider() {
        WidgetPicker.Entry personal = new WidgetPicker.Entry("Calendar", "Month", null, 0L);
        WidgetPicker.Entry work     = new WidgetPicker.Entry("Calendar", "Month", null, 10L);

        assertFalse(personal.previewKey().equals(work.previewKey()));
        // Personal keys stay bare, matching UserKeys' scheme.
        assertFalse(personal.previewKey().contains("#"));
        assertTrue(work.previewKey().endsWith("#10"));
    }
}
