package com.genericlauncher.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Process;
import android.os.UserHandle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for {@link WidgetPicker#buildEntries} — the provider enumeration that used to run
 * inline on the UI thread.
 *
 * <p>The two collaborators it needs ({@link WidgetPicker.ProviderSource} and
 * {@link WidgetPicker.Labeler}) are interfaces precisely so the failure modes that matter here
 * are reachable without a live {@code AppWidgetManager}: a provider whose label resource is
 * missing, a profile that disappears mid-enumeration, and a work profile whose widgets have to
 * show up alongside the personal ones.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetPickerEntriesTest {

    private static final UserHandle PERSONAL = Process.myUserHandle();
    // A second handle standing in for a work profile. Its identity is all buildEntries uses.
    private static final UserHandle WORK = UserHandle.getUserHandleForUid(10 * 100000);

    private static AppWidgetProviderInfo provider(String pkg, String cls) {
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(pkg, cls);
        return info;
    }

    /** Serial per profile plus a canned provider list; either lookup can be told to throw. */
    private static final class FakeSource implements WidgetPicker.ProviderSource {
        final Map<UserHandle, List<AppWidgetProviderInfo>> byProfile = new HashMap<>();
        final Map<UserHandle, Long> serials = new HashMap<>();
        final List<UserHandle> throwFor = new ArrayList<>();
        final List<UserHandle> queried = new ArrayList<>();

        @Override public List<AppWidgetProviderInfo> providersFor(UserHandle profile) {
            queried.add(profile);
            if (throwFor.contains(profile)) throw new IllegalStateException("profile gone");
            return byProfile.get(profile);
        }

        @Override public long serialFor(UserHandle profile) {
            Long s = serials.get(profile);
            return s == null ? 0L : s;
        }
    }

    /** Labels straight off the component name, with an opt-in throwing package. */
    private static final class FakeLabeler implements WidgetPicker.Labeler {
        final List<String> throwForPackage = new ArrayList<>();

        @Override public String appLabel(AppWidgetProviderInfo info, UserHandle profile) {
            if (throwForPackage.contains(info.provider.getPackageName())) {
                throw new Resources.NotFoundException("no label resource");
            }
            return info.provider.getPackageName();
        }

        @Override public String widgetLabel(AppWidgetProviderInfo info) {
            if (throwForPackage.contains(info.provider.getPackageName())) {
                throw new Resources.NotFoundException("no label resource");
            }
            return info.provider.getClassName();
        }
    }

    private static List<String> labels(List<WidgetPicker.Entry> entries) {
        List<String> out = new ArrayList<>();
        for (WidgetPicker.Entry e : entries) out.add(e.appLabel + "/" + e.widgetLabel);
        return out;
    }

    // -------------------------------------------------------------------------
    // H4 — the list the background thread hands back
    // -------------------------------------------------------------------------

    @Test
    public void buildEntries_returnsEverySortedProvider() {
        FakeSource source = new FakeSource();
        source.byProfile.put(PERSONAL, Arrays.asList(
                provider("weather", "Forecast"),
                provider("calendar", "Month"),
                provider("calendar", "Agenda")));

        List<WidgetPicker.Entry> entries = WidgetPicker.buildEntries(
                Collections.singletonList(PERSONAL), source, new FakeLabeler());

        assertEquals(Arrays.asList("calendar/Agenda", "calendar/Month", "weather/Forecast"),
                labels(entries));
    }

    @Test
    public void buildEntries_nullArgumentsProduceEmptyList() {
        assertTrue(WidgetPicker.buildEntries(null, new FakeSource(), new FakeLabeler()).isEmpty());
        assertTrue(WidgetPicker.buildEntries(
                Collections.singletonList(PERSONAL), null, new FakeLabeler()).isEmpty());
        assertTrue(WidgetPicker.buildEntries(
                Collections.singletonList(PERSONAL), new FakeSource(), null).isEmpty());
    }

    @Test
    public void buildEntries_nullProviderListForAProfileIsSkipped() {
        FakeSource source = new FakeSource();
        source.byProfile.put(PERSONAL, null);

        assertTrue(WidgetPicker.buildEntries(
                Collections.singletonList(PERSONAL), source, new FakeLabeler()).isEmpty());
    }

    // -------------------------------------------------------------------------
    // M10 — one bad provider must not take the picker (or the process) down
    // -------------------------------------------------------------------------

    @Test
    public void buildEntries_skipsProviderWhoseLabelThrows() {
        FakeSource source = new FakeSource();
        source.byProfile.put(PERSONAL, Arrays.asList(
                provider("calendar", "Month"),
                provider("broken", "Widget"),
                provider("weather", "Forecast")));
        FakeLabeler labeler = new FakeLabeler();
        labeler.throwForPackage.add("broken");

        List<WidgetPicker.Entry> entries = WidgetPicker.buildEntries(
                Collections.singletonList(PERSONAL), source, labeler);

        assertEquals(Arrays.asList("calendar/Month", "weather/Forecast"), labels(entries));
    }

    @Test
    public void buildEntries_skipsProfileWhoseProviderQueryThrows() {
        FakeSource source = new FakeSource();
        source.byProfile.put(PERSONAL, Collections.singletonList(provider("calendar", "Month")));
        source.serials.put(WORK, 10L);
        source.throwFor.add(WORK);

        List<WidgetPicker.Entry> entries = WidgetPicker.buildEntries(
                Arrays.asList(PERSONAL, WORK), source, new FakeLabeler());

        assertEquals(Collections.singletonList("calendar/Month"), labels(entries));
    }

    @Test
    public void appLabelFor_fallsBackToThePackageNameWhenItCannotBeResolved() {
        // The Resources.NotFoundException branch is not reachable through Robolectric's
        // PackageManager, but it lands here too: an unusable label degrades to the package name
        // rather than throwing out of a background thread.
        PackageManager pm = RuntimeEnvironment.getApplication().getPackageManager();

        assertEquals("not.installed",
                WidgetPicker.appLabelFor(provider("not.installed", "Widget"), pm));
    }

    @Test
    public void buildEntries_skipsProviderWithNoComponent() {
        FakeSource source = new FakeSource();
        AppWidgetProviderInfo headless = new AppWidgetProviderInfo(); // provider left null
        source.byProfile.put(PERSONAL, Arrays.asList(
                headless, null, provider("calendar", "Month")));

        assertEquals(Collections.singletonList("calendar/Month"),
                labels(WidgetPicker.buildEntries(
                        Collections.singletonList(PERSONAL), source, new FakeLabeler())));
    }

    // -------------------------------------------------------------------------
    // L10 — work-profile widgets
    // -------------------------------------------------------------------------

    @Test
    public void buildEntries_walksEveryProfile() {
        FakeSource source = new FakeSource();
        source.byProfile.put(PERSONAL, Collections.singletonList(provider("calendar", "Month")));
        source.byProfile.put(WORK, Collections.singletonList(provider("workapp", "Inbox")));
        source.serials.put(WORK, 10L);

        List<WidgetPicker.Entry> entries = WidgetPicker.buildEntries(
                Arrays.asList(PERSONAL, WORK), source, new FakeLabeler());

        assertEquals(Arrays.asList(PERSONAL, WORK), source.queried);
        assertEquals(Arrays.asList("calendar/Month", "workapp/Inbox"), labels(entries));
    }

    @Test
    public void buildEntries_keepsBothCopiesOfAProviderInstalledInTwoProfiles() {
        FakeSource source = new FakeSource();
        source.byProfile.put(PERSONAL, Collections.singletonList(provider("calendar", "Month")));
        source.byProfile.put(WORK, Collections.singletonList(provider("calendar", "Month")));
        source.serials.put(WORK, 10L);

        List<WidgetPicker.Entry> entries = WidgetPicker.buildEntries(
                Arrays.asList(PERSONAL, WORK), source, new FakeLabeler());

        assertEquals(2, entries.size());
        // The serial is what tells the two apart — including in the preview cache key.
        assertEquals(0L, entries.get(0).userSerial);
        assertEquals(10L, entries.get(1).userSerial);
        assertTrue(!entries.get(0).previewKey().equals(entries.get(1).previewKey()));
    }

    @Test
    public void buildEntries_dedupesTheSameProviderSurfacingTwiceForOneUser() {
        // Some OEM builds return the personal catalogue from every profile query.
        FakeSource source = new FakeSource();
        source.byProfile.put(PERSONAL, Collections.singletonList(provider("calendar", "Month")));
        source.byProfile.put(WORK, Collections.singletonList(provider("calendar", "Month")));

        List<WidgetPicker.Entry> entries = WidgetPicker.buildEntries(
                Arrays.asList(PERSONAL, WORK), source, new FakeLabeler());

        assertEquals(Collections.singletonList("calendar/Month"), labels(entries));
    }
}
