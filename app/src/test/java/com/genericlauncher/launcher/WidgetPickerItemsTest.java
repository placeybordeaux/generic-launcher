package com.genericlauncher.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the widget picker's item model — the pure functions every layout consumes.
 *
 * <p>Deliberately free of Robolectric: {@link WidgetPicker.Entry} carries a nullable
 * {@code AppWidgetProviderInfo}, so sorting, flattening, grouping and filtering are testable
 * without a live {@code AppWidgetManager} or {@code PackageManager}.
 */
public class WidgetPickerItemsTest {

    private static WidgetPicker.Entry e(String app, String widget) {
        return new WidgetPicker.Entry(app, widget, null);
    }

    private static List<WidgetPicker.Entry> list(WidgetPicker.Entry... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    /** "app/widget" labels, so ordering assertions read as a single line. */
    private static List<String> labels(List<WidgetPicker.Entry> entries) {
        List<String> out = new ArrayList<>();
        for (WidgetPicker.Entry e : entries) out.add(e.appLabel + "/" + e.widgetLabel);
        return out;
    }

    // -------------------------------------------------------------------------
    // sort
    // -------------------------------------------------------------------------

    @Test
    public void sort_ordersByAppLabelThenWidgetLabel() {
        List<WidgetPicker.Entry> sorted = WidgetPicker.sort(list(
                e("Weather", "Forecast"),
                e("Calendar", "Month"),
                e("Calendar", "Agenda"),
                e("Clock", "Analog")));

        assertEquals(Arrays.asList(
                "Calendar/Agenda", "Calendar/Month", "Clock/Analog", "Weather/Forecast"),
                labels(sorted));
    }

    @Test
    public void sort_isCaseInsensitive() {
        List<WidgetPicker.Entry> sorted = WidgetPicker.sort(list(
                e("beta", "zulu"),
                e("Alpha", "bravo"),
                e("alpha", "Alpha"),
                e("BETA", "alpha")));

        assertEquals(Arrays.asList(
                "alpha/Alpha", "Alpha/bravo", "BETA/alpha", "beta/zulu"),
                labels(sorted));
    }

    @Test
    public void sort_leavesInputUntouched() {
        List<WidgetPicker.Entry> input = list(e("Zed", "One"), e("Ada", "Two"));
        WidgetPicker.sort(input);
        assertEquals(Arrays.asList("Zed/One", "Ada/Two"), labels(input));
    }

    // -------------------------------------------------------------------------
    // flatten
    // -------------------------------------------------------------------------

    @Test
    public void flatten_emitsOneHeaderPerAppFollowedByItsEntries() {
        List<WidgetPicker.Entry> sorted = WidgetPicker.sort(list(
                e("Calendar", "Agenda"),
                e("Calendar", "Month"),
                e("Clock", "Analog")));

        List<Object> flat = WidgetPicker.flatten(sorted);

        assertEquals(5, flat.size());
        assertEquals("Calendar", flat.get(0));
        assertSame(sorted.get(0), flat.get(1));
        assertSame(sorted.get(1), flat.get(2));
        assertEquals("Clock", flat.get(3));
        assertSame(sorted.get(2), flat.get(4));
    }

    @Test
    public void flatten_doesNotRepeatHeaderForSameApp() {
        List<Object> flat = WidgetPicker.flatten(WidgetPicker.sort(list(
                e("Calendar", "A"),
                e("Calendar", "B"),
                e("Calendar", "C"))));

        int headers = 0;
        for (Object o : flat) if (o instanceof String) headers++;
        assertEquals(1, headers);
        assertEquals(4, flat.size());
        assertEquals("Calendar", flat.get(0));
    }

    @Test
    public void flatten_headersFollowSortOrder() {
        List<Object> flat = WidgetPicker.flatten(WidgetPicker.sort(list(
                e("Weather", "Forecast"),
                e("Calendar", "Month"),
                e("Clock", "Analog"))));

        List<String> headers = new ArrayList<>();
        for (Object o : flat) if (o instanceof String) headers.add((String) o);
        assertEquals(Arrays.asList("Calendar", "Clock", "Weather"), headers);
    }

    @Test
    public void flatten_emptyInputProducesEmptyList() {
        assertTrue(WidgetPicker.flatten(new ArrayList<>()).isEmpty());
    }

    // -------------------------------------------------------------------------
    // groupByApp
    // -------------------------------------------------------------------------

    @Test
    public void groupByApp_oneBucketPerAppPreservingOrder() {
        List<WidgetPicker.Entry> sorted = WidgetPicker.sort(list(
                e("Weather", "Forecast"),
                e("Calendar", "Month"),
                e("Calendar", "Agenda"),
                e("Clock", "Analog")));

        List<List<WidgetPicker.Entry>> groups = WidgetPicker.groupByApp(sorted);

        assertEquals(3, groups.size());
        assertEquals(Arrays.asList("Calendar/Agenda", "Calendar/Month"), labels(groups.get(0)));
        assertEquals(Arrays.asList("Clock/Analog"), labels(groups.get(1)));
        assertEquals(Arrays.asList("Weather/Forecast"), labels(groups.get(2)));
    }

    @Test
    public void groupByApp_accountsForEveryEntry() {
        List<WidgetPicker.Entry> sorted = WidgetPicker.sort(list(
                e("A", "one"), e("A", "two"), e("B", "three"), e("C", "four"), e("C", "five")));

        int total = 0;
        for (List<WidgetPicker.Entry> g : WidgetPicker.groupByApp(sorted)) total += g.size();
        assertEquals(sorted.size(), total);
    }

    @Test
    public void groupByApp_emptyInputProducesNoBuckets() {
        assertTrue(WidgetPicker.groupByApp(new ArrayList<>()).isEmpty());
    }

    // -------------------------------------------------------------------------
    // filter
    // -------------------------------------------------------------------------

    @Test
    public void filter_matchesWidgetLabel() {
        List<WidgetPicker.Entry> all = list(
                e("Calendar", "Agenda"), e("Clock", "Analog"), e("Weather", "Forecast"));

        assertEquals(Arrays.asList("Weather/Forecast"),
                labels(WidgetPicker.filter(all, "Forecast")));
    }

    @Test
    public void filter_matchesAppLabel() {
        List<WidgetPicker.Entry> all = list(
                e("Calendar", "Agenda"), e("Clock", "Analog"), e("Weather", "Forecast"));

        assertEquals(Arrays.asList("Calendar/Agenda"),
                labels(WidgetPicker.filter(all, "Calend")));
    }

    @Test
    public void filter_isCaseInsensitive() {
        List<WidgetPicker.Entry> all = list(e("Calendar", "Agenda"), e("Clock", "ANALOG"));

        assertEquals(Arrays.asList("Clock/ANALOG"), labels(WidgetPicker.filter(all, "analog")));
        assertEquals(Arrays.asList("Clock/ANALOG"), labels(WidgetPicker.filter(all, "CLOCK")));
    }

    @Test
    public void filter_blankOrNullQueryReturnsEverything() {
        List<WidgetPicker.Entry> all = list(e("Calendar", "Agenda"), e("Clock", "Analog"));

        assertEquals(labels(all), labels(WidgetPicker.filter(all, null)));
        assertEquals(labels(all), labels(WidgetPicker.filter(all, "")));
        assertEquals(labels(all), labels(WidgetPicker.filter(all, "   ")));
    }

    @Test
    public void filter_noMatchReturnsEmpty() {
        List<WidgetPicker.Entry> all = list(e("Calendar", "Agenda"), e("Clock", "Analog"));
        assertTrue(WidgetPicker.filter(all, "zzz").isEmpty());
    }
}
