package com.genericlauncher.launcher;

import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Add widget" picker, extracted from {@code HomescreenView}.
 *
 * <p>Self-contained: the caller supplies only the grid geometry needed for the span estimate and
 * a callback for the chosen provider. The picker reads its own presentation preferences —
 * {@link Settings#getWidgetPickerLayout} and {@link Settings#getWidgetPickerFullscreen} — so
 * adding a layout never touches the caller.
 *
 * <p>Three layouts ({@link WidgetPickerLayout}) compose with an independent full-screen/search
 * container; the container is not a fourth layout.
 *
 * <p>Nothing that talks to {@code PackageManager} runs on the UI thread: enumerating the provider
 * catalogue costs a binder round trip plus an arsc parse per installed widget, and decoding a
 * preview costs a multi-megabyte bitmap. Both happen on {@link #IO} and land back on the main
 * thread; the dialog opens immediately and fills in.
 *
 * <p>The item model ({@link Entry} plus {@link #sort}, {@link #flatten}, {@link #groupByApp},
 * {@link #filter} and {@link #buildEntries}) is deliberately pure so it is unit-testable without a
 * live {@link AppWidgetManager} or {@link PackageManager}.
 */
public final class WidgetPicker {

    /** Called with the chosen provider after the picker has dismissed itself. */
    public interface OnPick {
        void onPick(AppWidgetProviderInfo provider);
    }

    private static final String TAG = "WidgetPicker";

    // Palette — matches the launcher's dark surfaces.
    private static final int COLOR_SURFACE   = 0xFF121212;
    private static final int COLOR_CARD      = 0xFF1E1E1E;
    private static final int COLOR_PRIMARY   = 0xFFFFFFFF;
    private static final int COLOR_SECONDARY = 0x88FFFFFF;
    private static final int COLOR_FIELD     = 0x22FFFFFF;

    /**
     * Provider enumeration and preview decodes. Single-threaded on purpose: it serialises the
     * decodes so a preview queued twice before the first lands is only decoded once, and it keeps
     * the picker from competing with the launcher's own load for cores. Daemon so it never holds
     * the process open.
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "widget-picker");
        t.setDaemon(true);
        return t;
    });

    private WidgetPicker() {}

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /**
     * Shows the picker. The dialog appears empty and fills in once the catalogue has been walked
     * off-thread; it dismisses itself with a Toast when no widgets are installed at all.
     *
     * @param cellWidthPx  home-grid cell width in px, for the span estimate
     * @param cellHeightPx home-grid cell height in px
     */
    public static void show(Context context, int gridCols, int gridRows,
                            int cellWidthPx, int cellHeightPx, OnPick onPick) {
        if (context == null || onPick == null) return;

        AlertDialog[] dialogRef = {null};
        boolean[] dismissed = {false};
        Env env = new Env(context, gridCols, gridRows, cellWidthPx, cellHeightPx,
                onPick, () -> { if (dialogRef[0] != null) dialogRef[0].dismiss(); });

        Content content = buildContent(env, Settings.getWidgetPickerLayout(context));
        Model model = new Model();

        if (Settings.getWidgetPickerFullscreen(context)) {
            showFullscreen(env, content, model, dialogRef);
        } else {
            showDialog(env, content, dialogRef);
        }
        if (dialogRef[0] != null) {
            dialogRef[0].setOnDismissListener(d -> dismissed[0] = true);
        }

        IO.execute(() -> {
            List<Entry> built;
            try {
                built = buildEntries(profilesOf(context), new SystemProviderSource(context),
                        new SystemLabeler(context));
            } catch (RuntimeException e) {
                // Last line of defence: an uncaught throw on this thread kills the launcher,
                // and there is no home screen left to fall back to.
                Log.w(TAG, "widget enumeration failed", e);
                built = new ArrayList<>();
            }
            List<Entry> all = built;
            env.main.post(() -> {
                if (dismissed[0]) return;   // user closed the picker before the catalogue landed
                if (all.isEmpty()) {
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                    Toast.makeText(context, "No widgets available", Toast.LENGTH_SHORT).show();
                    return;
                }
                model.all = all;
                content.setEntries(all);
            });
        });
    }

    /** The loaded catalogue, so the search field can filter a list that arrives after it does. */
    private static final class Model {
        List<Entry> all = new ArrayList<>();
    }

    private static Content buildContent(Env env, WidgetPickerLayout layout) {
        switch (layout) {
            case COMPACT_LIST:  return new ListContent(env, new CompactAdapter(env));
            case APP_CAROUSELS: return new ListContent(env, new CarouselAdapter(env));
            case PREVIEW_CARDS:
            default:            return new ListContent(env, new CardAdapter(env));
        }
    }

    // -------------------------------------------------------------------------
    // Containers
    // -------------------------------------------------------------------------

    /** Today's container: a plain "Add widget" dialog with a Cancel button. */
    private static void showDialog(Env env, Content content, AlertDialog[] dialogRef) {
        AlertDialog dialog = new AlertDialog.Builder(env.context)
                .setTitle("Add widget")
                .setView(content.view())
                .setNegativeButton("Cancel", null)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    /** Full-height sheet with a search field pinned above whichever layout is selected. */
    private static void showFullscreen(Env env, Content content,
                                       Model model, AlertDialog[] dialogRef) {
        Context c = env.context;

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_SURFACE);
        root.setPadding(dp(c, 12), dp(c, 16), dp(c, 12), 0);

        TextView title = new TextView(c);
        title.setText("Add widget");
        title.setTextColor(COLOR_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        title.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 10));
        root.addView(title);

        EditText search = new EditText(c);
        search.setHint("Search widgets");
        search.setSingleLine(true);
        search.setTextColor(COLOR_PRIMARY);
        search.setHintTextColor(COLOR_SECONDARY);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        GradientDrawable field = new GradientDrawable();
        field.setColor(COLOR_FIELD);
        field.setCornerRadius(dp(c, 20));
        search.setBackground(field);
        search.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {
                content.setEntries(filter(model.all, s == null ? null : s.toString()));
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(search, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        contentLp.topMargin = dp(c, 8);
        root.addView(content.view(), contentLp);

        AlertDialog dialog = new AlertDialog.Builder(c).setView(root).create();
        dialogRef[0] = dialog;
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(COLOR_SURFACE));
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    // -------------------------------------------------------------------------
    // Item model — pure, unit-testable without AppWidgetManager / PackageManager
    // -------------------------------------------------------------------------

    /** One installed widget provider plus the two labels every layout displays. */
    static final class Entry {
        final String appLabel;
        final String widgetLabel;
        /** The provider itself; {@code null} in unit tests. */
        final AppWidgetProviderInfo info;
        /** Serial of the profile the provider came from; 0 is personal (see {@link UserKeys}). */
        final long userSerial;

        Entry(String appLabel, String widgetLabel, AppWidgetProviderInfo info) {
            this(appLabel, widgetLabel, info, 0L);
        }

        Entry(String appLabel, String widgetLabel, AppWidgetProviderInfo info, long userSerial) {
            this.appLabel    = appLabel    == null ? "" : appLabel;
            this.widgetLabel = widgetLabel == null ? "" : widgetLabel;
            this.info        = info;
            this.userSerial  = userSerial;
        }

        /**
         * Identity for the preview cache: the provider component, profile-qualified the way
         * {@link UserKeys} qualifies package names, so the same widget installed in both profiles
         * does not share one cached preview.
         */
        String previewKey() {
            String base = (info != null && info.provider != null)
                    ? info.provider.flattenToShortString()
                    : appLabel + "/" + widgetLabel;
            return UserKeys.compose(base, userSerial);
        }

        @Override public String toString() { return appLabel + "/" + widgetLabel; }
    }

    /** Sorts by app label, then by widget label within an app. Case-insensitive; input untouched. */
    static List<Entry> sort(List<Entry> entries) {
        List<Entry> copy = new ArrayList<>(entries == null ? new ArrayList<>() : entries);
        Collections.sort(copy, (a, b) -> {
            int cmp = a.appLabel.compareToIgnoreCase(b.appLabel);
            if (cmp != 0) return cmp;
            return a.widgetLabel.compareToIgnoreCase(b.widgetLabel);
        });
        return copy;
    }

    /**
     * Interleaves app-name section headers ({@link String}) with {@link Entry} items. One header
     * per app, emitted before that app's entries; consecutive entries of the same app share it.
     */
    static List<Object> flatten(List<Entry> sorted) {
        List<Object> out = new ArrayList<>();
        if (sorted == null) return out;
        String last = null;
        for (Entry e : sorted) {
            if (last == null || !last.equalsIgnoreCase(e.appLabel)) {
                out.add(e.appLabel);
                last = e.appLabel;
            }
            out.add(e);
        }
        return out;
    }

    /** Groups into per-app buckets for {@code APP_CAROUSELS}, preserving the incoming order. */
    static List<List<Entry>> groupByApp(List<Entry> sorted) {
        List<List<Entry>> out = new ArrayList<>();
        if (sorted == null) return out;
        String last = null;
        List<Entry> bucket = null;
        for (Entry e : sorted) {
            if (bucket == null || last == null || !last.equalsIgnoreCase(e.appLabel)) {
                bucket = new ArrayList<>();
                out.add(bucket);
                last = e.appLabel;
            }
            bucket.add(e);
        }
        return out;
    }

    /** Case-insensitive match on widget label OR app label. A blank/null query returns all. */
    static List<Entry> filter(List<Entry> all, String query) {
        List<Entry> out = new ArrayList<>();
        if (all == null) return out;
        if (query == null || query.trim().isEmpty()) {
            out.addAll(all);
            return out;
        }
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Entry e : all) {
            if (e.widgetLabel.toLowerCase(Locale.ROOT).contains(q)
                    || e.appLabel.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * True when the two sequences hold the same entries in the same order. Typing in the search
     * field re-filters on every keystroke, and most keystrokes narrow nothing (or nothing yet);
     * this lets the adapters skip the rebind rather than re-running every visible bind.
     */
    static boolean sameEntries(List<Entry> a, List<Entry> b) {
        if (a == null || b == null) return false;   // "nothing bound yet" is never a match
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    static String appLabelFor(AppWidgetProviderInfo info, PackageManager pm) {
        String pkg = info.provider.getPackageName();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            // getApplicationLabel throws Resources.NotFoundException for a package whose label
            // resource is missing or that is being updated underneath us. The name still names it.
            return pkg;
        }
    }

    // -------------------------------------------------------------------------
    // Catalogue enumeration — off the UI thread, one profile at a time
    // -------------------------------------------------------------------------

    /** Where the provider catalogue comes from. An interface so enumeration is testable. */
    interface ProviderSource {
        /** Providers installed for {@code profile}; may return null or throw. */
        List<AppWidgetProviderInfo> providersFor(UserHandle profile);
        /** The profile's serial number, 0 for personal. */
        long serialFor(UserHandle profile);
    }

    /** The two label lookups, each a binder call plus an arsc parse in production. */
    interface Labeler {
        String appLabel(AppWidgetProviderInfo info, UserHandle profile);
        String widgetLabel(AppWidgetProviderInfo info);
    }

    /**
     * Builds the sorted entry list across every profile the launcher can see, so work-profile
     * widgets appear alongside personal ones exactly as {@code AppLoader} does for apps.
     *
     * <p>Every per-provider and per-profile failure is swallowed: this runs on {@link #IO}, where
     * an uncaught {@code Resources.NotFoundException} from one misbehaving package would take the
     * whole process down instead of costing one row.
     */
    static List<Entry> buildEntries(List<UserHandle> profiles,
                                    ProviderSource source, Labeler labeler) {
        List<Entry> out = new ArrayList<>();
        if (profiles == null || source == null || labeler == null) return out;

        Set<String> seen = new HashSet<>();
        for (UserHandle profile : profiles) {
            long serial;
            List<AppWidgetProviderInfo> providers;
            try {
                serial = source.serialFor(profile);
                providers = source.providersFor(profile);
            } catch (RuntimeException e) {
                // Profile removed or locked mid-enumeration; the rest of the catalogue is fine.
                Log.w(TAG, "skipping widget profile", e);
                continue;
            }
            if (providers == null) continue;

            for (AppWidgetProviderInfo p : providers) {
                if (p == null || p.provider == null) continue;
                try {
                    Entry e = new Entry(labeler.appLabel(p, profile), labeler.widgetLabel(p),
                            p, serial);
                    // Some builds hand back the personal catalogue from every profile query.
                    if (seen.add(e.previewKey())) out.add(e);
                } catch (RuntimeException e) {
                    Log.w(TAG, "skipping widget provider " + p.provider, e);
                }
            }
        }
        return sort(out);
    }

    /** Every profile this user owns, falling back to the calling user when UserManager is absent. */
    private static List<UserHandle> profilesOf(Context c) {
        UserManager um = (UserManager) c.getSystemService(Context.USER_SERVICE);
        if (um != null) {
            try {
                List<UserHandle> profiles = um.getUserProfiles();
                if (profiles != null && !profiles.isEmpty()) return profiles;
            } catch (RuntimeException e) {
                Log.w(TAG, "getUserProfiles failed; personal profile only", e);
            }
        }
        return Collections.singletonList(Process.myUserHandle());
    }

    /** {@link ProviderSource} over the real {@link AppWidgetManager}. */
    private static final class SystemProviderSource implements ProviderSource {
        private final AppWidgetManager awm;
        private final UserManager um;

        SystemProviderSource(Context c) {
            awm = AppWidgetManager.getInstance(c);
            um  = (UserManager) c.getSystemService(Context.USER_SERVICE);
        }

        @Override public List<AppWidgetProviderInfo> providersFor(UserHandle profile) {
            return awm == null ? null : awm.getInstalledProvidersForProfile(profile);
        }

        @Override public long serialFor(UserHandle profile) {
            return um == null ? 0L : um.getSerialNumberForUser(profile);
        }
    }

    /**
     * {@link Labeler} over {@link PackageManager}, reaching the app label through
     * {@link LauncherApps} so a work-profile-only package resolves against its own profile rather
     * than falling back to its package name.
     */
    private static final class SystemLabeler implements Labeler {
        private final PackageManager pm;
        private final LauncherApps launcherApps;
        /** One lookup per package rather than per provider — an app may ship a dozen widgets. */
        private final Map<String, String> appLabels = new HashMap<>();

        SystemLabeler(Context c) {
            pm = c.getPackageManager();
            launcherApps = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        }

        @Override public String appLabel(AppWidgetProviderInfo info, UserHandle profile) {
            String key = info.provider.getPackageName() + "@" + profile;
            String cached = appLabels.get(key);
            if (cached != null) return cached;

            String label = null;
            if (launcherApps != null && profile != null) {
                try {
                    ApplicationInfo ai = launcherApps.getApplicationInfo(
                            info.provider.getPackageName(), 0, profile);
                    if (ai != null) label = pm.getApplicationLabel(ai).toString();
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {}
            }
            if (label == null) label = appLabelFor(info, pm);
            appLabels.put(key, label);
            return label;
        }

        @Override public String widgetLabel(AppWidgetProviderInfo info) {
            return String.valueOf(info.loadLabel(pm));
        }
    }

    // -------------------------------------------------------------------------
    // Preview cache
    // -------------------------------------------------------------------------

    /**
     * Bounded LRU of decoded previews, keyed by {@link Entry#previewKey()}. A {@code null} value
     * is a real entry: a provider with no preview image has to be remembered, or every bind pays
     * for the failed decode again.
     *
     * <p>Cost is passed in rather than measured off the {@link Drawable} so eviction is testable
     * without a live {@code Bitmap}. Scoped to one picker: the drawables it holds are attached to
     * that dialog's {@code ImageView}s, and outliving the dialog would pin its view tree.
     */
    static final class PreviewCache {
        /** Access-ordered, so iteration starts at the least recently used. */
        private final LinkedHashMap<String, Drawable> map = new LinkedHashMap<>(16, 0.75f, true);
        private final Map<String, Integer> costs = new HashMap<>();
        private final int maxBytes;
        private int sizeBytes;

        PreviewCache(int maxBytes) { this.maxBytes = Math.max(1, maxBytes); }

        synchronized boolean contains(String key) { return map.containsKey(key); }

        synchronized Drawable get(String key) { return map.get(key); }

        synchronized void put(String key, Drawable value, int cost) {
            int c = Math.max(0, cost);
            Integer previous = costs.remove(key);
            if (previous != null) {
                map.remove(key);
                sizeBytes -= previous;
            }
            map.put(key, value);
            costs.put(key, c);
            sizeBytes += c;

            Iterator<Map.Entry<String, Drawable>> it = map.entrySet().iterator();
            // Never evict the entry just added: one oversized preview still has to render once.
            while (sizeBytes > maxBytes && map.size() > 1 && it.hasNext()) {
                Map.Entry<String, Drawable> eldest = it.next();
                if (eldest.getKey().equals(key)) continue;
                it.remove();
                Integer gone = costs.remove(eldest.getKey());
                sizeBytes -= gone == null ? 0 : gone;
            }
        }

        synchronized int sizeBytes() { return sizeBytes; }

        synchronized int count() { return map.size(); }
    }

    /**
     * The {@code inSampleSize} for decoding a {@code srcW}×{@code srcH} image into a
     * {@code maxW}×{@code maxH} box: halve while both axes still cover the box, so the short axis
     * is never sampled below it. Degrades to 1 on nonsense input.
     */
    static int sampleSize(int srcW, int srcH, int maxW, int maxH) {
        if (srcW <= 0 || srcH <= 0 || maxW <= 0 || maxH <= 0) return 1;
        int sample = 1;
        while (srcW / (sample * 2) >= maxW && srcH / (sample * 2) >= maxH) {
            sample *= 2;
        }
        return sample;
    }

    /** Roughly what a decoded preview costs to hold, for the cache budget. */
    private static int costOf(Drawable d) {
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null) return Math.max(1, b.getByteCount());
        }
        if (d == null) return 1;
        int w = Math.max(1, d.getIntrinsicWidth());
        int h = Math.max(1, d.getIntrinsicHeight());
        return Math.max(1, w * h * 4);
    }

    /** An eighth of the heap, clamped — a large catalogue must not push the launcher into OOM. */
    private static int previewBudget() {
        long eighth = Runtime.getRuntime().maxMemory() / 8;
        return (int) Math.max(2L * 1024 * 1024, Math.min(16L * 1024 * 1024, eighth));
    }

    // -------------------------------------------------------------------------
    // Shared environment for the adapters
    // -------------------------------------------------------------------------

    /** Everything the layouts need that isn't the data itself. */
    private static final class Env {
        final Context context;
        final PackageManager pm;
        final float density;
        final int cols, rows, cellW, cellH;
        final OnPick onPick;
        final Runnable dismiss;
        final Handler main = new Handler(Looper.getMainLooper());
        final PreviewCache previews = new PreviewCache(previewBudget());
        /** Widest a preview may be inside a full-width card. */
        final int maxPreviewW;
        /** Tallest a preview may be, so a 5-row widget can't fill the whole sheet. */
        final int maxPreviewH;

        Env(Context context, int cols, int rows, int cellW, int cellH,
            OnPick onPick, Runnable dismiss) {
            this.context = context;
            this.pm      = context.getPackageManager();
            this.cols    = cols;
            this.rows    = rows;
            this.cellW   = cellW;
            this.cellH   = cellH;
            this.onPick  = onPick;
            this.dismiss = dismiss;
            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            this.density = dm.density <= 0f ? 1f : dm.density;
            this.maxPreviewW = Math.max(dp(context, 96), dm.widthPixels - dp(context, 72));
            this.maxPreviewH = Math.max(dp(context, 96), Math.round(dm.heightPixels * 0.45f));
        }

        int[] span(AppWidgetProviderInfo info) {
            return HomescreenView.spanForProvider(info, cellW, cellH, cols, rows);
        }

        /**
         * Fills {@code target} with the entry's preview — straight from the cache when it is
         * there, off-thread otherwise. The view is tagged with the entry's key so a decode that
         * lands after the holder has been rebound to a different widget is dropped rather than
         * painted over the new one.
         */
        void bindPreview(Entry e, ImageView target, int maxW, int maxH) {
            String key = e.previewKey();
            target.setTag(key);
            if (previews.contains(key)) {
                target.setImageDrawable(previews.get(key));
                return;
            }
            target.setImageDrawable(null);
            if (e.info == null) return;

            IO.execute(() -> {
                Drawable d;
                if (previews.contains(key)) {
                    // An earlier bind of the same widget already decoded it on this thread.
                    d = previews.get(key);
                } else {
                    Drawable decoded = null;
                    try {
                        decoded = decodePreview(e.info, maxW, maxH);
                    } catch (RuntimeException ex) {
                        Log.w(TAG, "preview decode failed for " + key, ex);
                    }
                    previews.put(key, decoded, costOf(decoded));
                    d = decoded;
                }
                Drawable shown = d;
                main.post(() -> {
                    if (key.equals(target.getTag())) target.setImageDrawable(shown);
                });
            });
        }

        /** The provider preview, falling back to the application icon when there is none. */
        private Drawable decodePreview(AppWidgetProviderInfo info, int maxW, int maxH) {
            Drawable d = decodeSampled(info, maxW, maxH);
            if (d == null) {
                try {
                    d = info.loadPreviewImage(context, 0);
                } catch (RuntimeException ignored) {}
            }
            if (d == null) {
                try {
                    d = pm.getApplicationIcon(info.provider.getPackageName());
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {}
            }
            return d;
        }

        /**
         * Decodes the preview resource no larger than the box it will be drawn in. Widget previews
         * are routinely full-width PNGs of several megabytes, and the cache would otherwise hold a
         * screenful of them at full resolution.
         *
         * <p>Returns null — leaving the caller to fall back to {@code loadPreviewImage} — when the
         * provider has no preview resource, its resource is not a bitmap, or its package is not
         * readable from this profile.
         */
        private Drawable decodeSampled(AppWidgetProviderInfo info, int maxW, int maxH) {
            if (info.previewImage == 0 || info.provider == null) return null;
            Resources res;
            try {
                res = pm.getResourcesForApplication(info.provider.getPackageName());
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(res, info.previewImage, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;   // vector, most likely

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH);
            Bitmap bm = BitmapFactory.decodeResource(res, info.previewImage, opts);
            return bm == null ? null : new BitmapDrawable(context.getResources(), bm);
        }

        /**
         * The widget's true cell footprint in px, shrunk proportionally to fit the available
         * preview box so a card shows the real size it will occupy.
         */
        int[] previewSize(AppWidgetProviderInfo info, int maxW, int maxH) {
            int[] span = span(info);
            float w = Math.max(1, span[0] * cellW);
            float h = Math.max(1, span[1] * cellH);
            float scale = Math.min(1f, Math.min(maxW / w, maxH / h));
            return new int[]{Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))};
        }

        void pick(AppWidgetProviderInfo info) {
            if (info == null) return;
            dismiss.run();
            onPick.onPick(info);
        }
    }

    // -------------------------------------------------------------------------
    // Content — a RecyclerView plus whichever adapter the selected layout uses
    // -------------------------------------------------------------------------

    /** A layout's view, re-fillable so the fullscreen search field can live-filter it. */
    private interface Content {
        View view();
        void setEntries(List<Entry> entries);
    }

    /** Adapter contract shared by the three layouts. */
    private abstract static class PickerAdapter<VH extends RecyclerView.ViewHolder>
            extends RecyclerView.Adapter<VH> {
        /** What is currently bound, so an unchanged keystroke costs nothing. */
        List<Entry> bound;

        abstract void setEntries(List<Entry> entries);
    }

    private static final class ListContent implements Content {
        private final RecyclerView rv;
        private final PickerAdapter<?> adapter;

        ListContent(Env env, PickerAdapter<?> adapter) {
            this.adapter = adapter;
            rv = new RecyclerView(env.context);
            rv.setLayoutManager(new LinearLayoutManager(env.context));
            rv.setAdapter(adapter);
            rv.setClipToPadding(false);
            rv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        @Override public View view() { return rv; }

        @Override public void setEntries(List<Entry> entries) { adapter.setEntries(entries); }
    }

    // -------------------------------------------------------------------------
    // COMPACT_LIST — dense 48dp rows with app-name section headers
    // -------------------------------------------------------------------------

    private static final class CompactAdapter extends PickerAdapter<RecyclerView.ViewHolder> {
        private static final int TYPE_HEADER = 0;
        private static final int TYPE_WIDGET = 1;

        private final Env env;
        private List<Object> items = new ArrayList<>();

        CompactAdapter(Env env) { this.env = env; }

        @Override void setEntries(List<Entry> entries) {
            if (sameEntries(bound, entries)) return;
            bound = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
            items = flatten(bound);
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return items.size(); }

        @Override public int getItemViewType(int position) {
            return items.get(position) instanceof String ? TYPE_HEADER : TYPE_WIDGET;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = env.context;
            if (viewType == TYPE_HEADER) {
                TextView hdr = new TextView(c);
                hdr.setTextColor(COLOR_SECONDARY);
                hdr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                hdr.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 2));
                hdr.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new HeaderVH(hdr);
            }

            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ImageView icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));

            LinearLayout textCol = new LinearLayout(c);
            textCol.setOrientation(LinearLayout.VERTICAL);
            textCol.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textLp.setMarginStart(dp(c, 12));
            textCol.setLayoutParams(textLp);

            TextView label = new TextView(c);
            label.setTextColor(COLOR_PRIMARY);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(label);

            TextView span = new TextView(c);
            span.setTextColor(COLOR_SECONDARY);
            span.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            textCol.addView(span);

            row.addView(textCol);
            return new CompactVH(row, icon, label, span);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = items.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).text.setText(String.valueOf(item));
                return;
            }
            Entry e = (Entry) item;
            CompactVH vh = (CompactVH) holder;
            int iconPx = dp(env.context, 48);
            env.bindPreview(e, vh.icon, iconPx, iconPx);
            vh.label.setText(e.widgetLabel);
            int[] span = env.span(e.info);
            vh.span.setText(span[0] + "×" + span[1]);
            vh.itemView.setOnClickListener(v -> env.pick(e.info));
        }
    }

    private static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView text;
        HeaderVH(TextView v) { super(v); text = v; }
    }

    private static final class CompactVH extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label, span;
        CompactVH(View root, ImageView icon, TextView label, TextView span) {
            super(root);
            this.icon = icon;
            this.label = label;
            this.span = span;
        }
    }

    // -------------------------------------------------------------------------
    // PREVIEW_CARDS — full-width cards, preview scaled to the real cell footprint
    // -------------------------------------------------------------------------

    private static final class CardAdapter extends PickerAdapter<CardVH> {
        private final Env env;
        private List<Entry> items = new ArrayList<>();

        CardAdapter(Env env) { this.env = env; }

        @Override void setEntries(List<Entry> entries) {
            if (sameEntries(bound, entries)) return;
            bound = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
            items = bound;
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return items.size(); }

        @NonNull @Override
        public CardVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = env.context;

            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COLOR_CARD);
            bg.setCornerRadius(dp(c, 16));
            card.setBackground(bg);
            RecyclerView.LayoutParams cardLp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
            card.setLayoutParams(cardLp);

            FrameLayout previewBox = new FrameLayout(c);
            previewBox.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            ImageView preview = new ImageView(c);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams previewLp = new FrameLayout.LayoutParams(0, 0);
            previewLp.gravity = Gravity.CENTER;
            previewBox.addView(preview, previewLp);
            card.addView(previewBox);

            TextView label = new TextView(c);
            label.setTextColor(COLOR_PRIMARY);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = dp(c, 10);
            card.addView(label, labelLp);

            LinearLayout meta = new LinearLayout(c);
            meta.setOrientation(LinearLayout.HORIZONTAL);
            meta.setGravity(Gravity.CENTER_VERTICAL);

            TextView app = new TextView(c);
            app.setTextColor(COLOR_SECONDARY);
            app.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            app.setMaxLines(1);
            app.setEllipsize(TextUtils.TruncateAt.END);
            meta.addView(app, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView span = new TextView(c);
            span.setTextColor(COLOR_SECONDARY);
            span.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            span.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
            GradientDrawable badge = new GradientDrawable();
            badge.setColor(COLOR_FIELD);
            badge.setCornerRadius(dp(c, 10));
            span.setBackground(badge);
            meta.addView(span);

            card.addView(meta);
            return new CardVH(card, previewBox, preview, label, app, span);
        }

        @Override
        public void onBindViewHolder(@NonNull CardVH vh, int position) {
            Entry e = items.get(position);
            int[] size = env.previewSize(e.info, env.maxPreviewW, env.maxPreviewH);
            ViewGroup.LayoutParams lp = vh.preview.getLayoutParams();
            lp.width  = size[0];
            lp.height = size[1];
            vh.preview.setLayoutParams(lp);
            env.bindPreview(e, vh.preview, size[0], size[1]);
            vh.label.setText(e.widgetLabel);
            vh.app.setText(e.appLabel);
            int[] span = env.span(e.info);
            vh.span.setText(span[0] + "×" + span[1]);
            vh.itemView.setOnClickListener(v -> env.pick(e.info));
        }
    }

    private static final class CardVH extends RecyclerView.ViewHolder {
        final FrameLayout previewBox;
        final ImageView preview;
        final TextView label, app, span;
        CardVH(View root, FrameLayout previewBox, ImageView preview,
               TextView label, TextView app, TextView span) {
            super(root);
            this.previewBox = previewBox;
            this.preview = preview;
            this.label = label;
            this.app = app;
            this.span = span;
        }
    }

    // -------------------------------------------------------------------------
    // APP_CAROUSELS — apps stack vertically, each app's widgets scroll horizontally
    // -------------------------------------------------------------------------

    private static final class CarouselAdapter extends PickerAdapter<CarouselVH> {
        private final Env env;
        private List<List<Entry>> groups = new ArrayList<>();
        /** Where each app's strip was left, keyed by app label so it survives filtering. */
        private final Map<String, Integer> scrollByApp = new HashMap<>();

        CarouselAdapter(Env env) { this.env = env; }

        @Override void setEntries(List<Entry> entries) {
            if (sameEntries(bound, entries)) return;
            bound = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
            groups = groupByApp(bound);
            notifyDataSetChanged();
        }

        @Override public int getItemCount() { return groups.size(); }

        @NonNull @Override
        public CarouselVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = env.context;

            LinearLayout root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, dp(c, 10), 0, dp(c, 10));
            root.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView app = new TextView(c);
            app.setTextColor(COLOR_PRIMARY);
            app.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            app.setPadding(dp(c, 16), 0, dp(c, 16), dp(c, 8));
            root.addView(app);

            HorizontalScrollView scroller = new HorizontalScrollView(c);
            scroller.setHorizontalScrollBarEnabled(false);
            scroller.setClipToPadding(false);
            scroller.setPadding(dp(c, 16), 0, dp(c, 16), 0);

            LinearLayout strip = new LinearLayout(c);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            scroller.addView(strip, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroller, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            return new CarouselVH(root, app, scroller, strip);
        }

        @Override
        public void onBindViewHolder(@NonNull CarouselVH vh, int position) {
            List<Entry> group = groups.get(position);
            String app = group.isEmpty() ? "" : group.get(0).appLabel;
            rememberScroll(vh);
            vh.boundApp = app;
            vh.app.setText(app);

            // Tiles are built once per holder and rebound. A HorizontalScrollView does not
            // recycle, so rebuilding the strip on every outer bind re-inflated (and re-decoded)
            // every one of an app's widgets, on-screen or not, on every keystroke.
            int childCount = vh.strip.getChildCount();
            for (int i = 0; i < group.size(); i++) {
                View tile;
                if (i < childCount) {
                    tile = vh.strip.getChildAt(i);
                    tile.setVisibility(View.VISIBLE);
                } else {
                    tile = buildTile();
                    vh.strip.addView(tile);
                }
                bindTile(tile, group.get(i));
            }
            for (int i = group.size(); i < childCount; i++) {
                vh.strip.getChildAt(i).setVisibility(View.GONE);
            }

            Integer saved = scrollByApp.get(app);
            int scrollX = saved == null ? 0 : saved;
            // After layout, or the strip has no content to scroll over yet.
            vh.scroller.post(() -> vh.scroller.setScrollX(scrollX));
        }

        @Override public void onViewRecycled(@NonNull CarouselVH vh) {
            rememberScroll(vh);
        }

        /** Keeps a strip's scroll offset across rebinds instead of silently snapping it home. */
        private void rememberScroll(CarouselVH vh) {
            if (vh.boundApp != null) scrollByApp.put(vh.boundApp, vh.scroller.getScrollX());
        }

        /** The structure of one tile in an app's strip; {@link #bindTile} fills in the contents. */
        private View buildTile() {
            Context c = env.context;
            int tileW = dp(c, 148);

            LinearLayout tile = new LinearLayout(c);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COLOR_CARD);
            bg.setCornerRadius(dp(c, 14));
            tile.setBackground(bg);
            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                    tileW, LinearLayout.LayoutParams.WRAP_CONTENT);
            tileLp.setMarginEnd(dp(c, 10));
            tile.setLayoutParams(tileLp);

            ImageView preview = new ImageView(c);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(1, 1);
            previewLp.gravity = Gravity.CENTER_HORIZONTAL;
            tile.addView(preview, previewLp);

            TextView label = new TextView(c);
            label.setTextColor(COLOR_PRIMARY);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = dp(c, 8);
            tile.addView(label, labelLp);

            TextView spanLabel = new TextView(c);
            spanLabel.setTextColor(COLOR_SECONDARY);
            spanLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            tile.addView(spanLabel);

            tile.setTag(new TileViews(preview, label, spanLabel));
            return tile;
        }

        private void bindTile(View tile, Entry e) {
            Context c = env.context;
            int tileW = dp(c, 148);
            TileViews v = (TileViews) tile.getTag();

            int[] size = env.previewSize(e.info, tileW - dp(c, 20), dp(c, 148));
            ViewGroup.LayoutParams lp = v.preview.getLayoutParams();
            lp.width  = size[0];
            lp.height = size[1];
            v.preview.setLayoutParams(lp);
            env.bindPreview(e, v.preview, size[0], size[1]);

            v.label.setText(e.widgetLabel);
            int[] span = env.span(e.info);
            v.span.setText(span[0] + "×" + span[1]);
            tile.setOnClickListener(x -> env.pick(e.info));
        }
    }

    /** The rebindable parts of a carousel tile, hung off the tile's tag. */
    private static final class TileViews {
        final ImageView preview;
        final TextView label, span;
        TileViews(ImageView preview, TextView label, TextView span) {
            this.preview = preview;
            this.label = label;
            this.span = span;
        }
    }

    private static final class CarouselVH extends RecyclerView.ViewHolder {
        final TextView app;
        final HorizontalScrollView scroller;
        final LinearLayout strip;
        /** App label currently displayed, so its scroll offset can be filed under the right key. */
        String boundApp;

        CarouselVH(View root, TextView app, HorizontalScrollView scroller, LinearLayout strip) {
            super(root);
            this.app = app;
            this.scroller = scroller;
            this.strip = strip;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
