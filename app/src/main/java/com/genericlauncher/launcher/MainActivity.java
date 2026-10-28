package com.genericlauncher.launcher;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.DrawerQuery;
import com.genericlauncher.launcher.personality.DrawerStrategy;
import com.genericlauncher.launcher.personality.Personality;
import com.genericlauncher.launcher.personality.strategy.AlphabeticalStrategy;
import com.genericlauncher.launcher.personality.strategy.UsageStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int GRID_COLUMNS = 4;

    /**
     * Ordering for {@code Mode.USAGE}. A field rather than a fresh instance per render because a
     * strategy is a pure function with no state to keep.
     */
    private static final DrawerStrategy USAGE_STRATEGY = new UsageStrategy();

    /**
     * Ordering for search results: plain A–Z, headerless, regardless of the active personality.
     * Grouping is what the user is searching <em>past</em>, so imposing the personality's sections
     * on three matches only buries them under headers.
     */
    private static final DrawerStrategy SEARCH_RESULTS = new AlphabeticalStrategy(false);
    private static final String PREFS    = "color_picker_prefs";
    private static final String KEY_MODE = "layout_mode";

    // Package-private so a test can deliver the bind/configure results onActivityResult expects.
    static final int RC_WIDGET_BIND      = 1001;
    static final int RC_WIDGET_CONFIGURE = 1002;
    private static final int RC_SETTINGS         = 1005;

    /**
     * Layout modes the mode-switch button cycles through.
     *
     * <p>{@code LIST} is the strategy-driven drawer: whatever {@link Personality#DRAWER} says.
     * {@code USAGE} is the one built-in alternative, because it needs a permission and a banner
     * rather than only an ordering. {@code HOME} is the home screen itself.
     *
     * <p><b>Ordinals are persisted</b> ({@code drawer_mode}, and bit positions in the enabled-views
     * mask). This base ships with no installed users, so it defines them cleanly rather than
     * inheriting anyone's migration history — but that licence is spent. From the first release of
     * a fork onwards, <b>append only; never reorder</b>, or an update silently moves every user to
     * a different view.
     */
    private enum Mode { LIST, USAGE, HOME }

    /** Labels for the "Enabled views" settings picker; index matches Mode ordinal. */
    static final String[] MODE_LABELS = {
            "List", "Usage", "Home"
    };

    private LinearLayout rootLayout;
    private LinearLayout toolbarRow;
    private FrameLayout contentContainer;
    private ProgressBar progress;
    private ImageView toggleView;
    private ImageView modeSwitch;
    private ImageView settingsButton;
    private TextView usageBanner;
    private EditText searchBox;

    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> apps    = new ArrayList<>(); // filtered for display
    private String query = "";
    private boolean hasUsageAccess = false;

    /**
     * The currently-displayed non-HOME drawer mode. Never {@code HOME}: HOME is entered by
     * {@link #goHome()} and exited via {@link #openDrawer()}.  Persisted as {@code KEY_MODE}
     * (for backward compat) and {@code KEY_DRAWER_MODE} (drawer-specific slot).
     */
    private Mode mode = Mode.LIST;

    /**
     * True while the homescreen (HOME state) is displayed — toolbar hidden, wallpaper visible.
     * False while the app-drawer is open — toolbar visible, drawer content visible.
     */
    private boolean isHome = true;
    /** Navigation-bar inset height in px, from the root inset listener. */
    private int navBottomInset = 0;

    private boolean showHeaders = true;
    private boolean firstResume = true;
    /** True while a drawer open/close animation is running — guards against overlapping animates. */
    private boolean isAnimatingDrawer = false;
    @Nullable private HomescreenView homescreenView;
    /**
     * True while {@link #homescreenView} may be showing something other than what {@link #allApps}
     * and the store now say. Set wherever this Activity changes either behind the view's back —
     * a load landing, a widget or pinned app added — and consumed by {@link #ensureHomescreenView}.
     *
     * <p>{@code renderMode()} runs on every search keystroke, and it used to hand the view a fresh
     * app map every time. {@code refresh} rebuilds every page, and for each widget that is a
     * binder round trip and a RemoteViews inflation — all on the main thread, behind a drawer that
     * covers the result, for a keystroke that changed nothing the home screen shows. The view
     * itself owns every change it makes (drop, move, resize, folder edits), so only the changes
     * made here need to raise the flag.
     */
    private boolean homescreenStale = true;
    /**
     * The drawer overlay FrameLayout placed on top of {@link #homescreenView} when a drawer mode
     * is active. The HomescreenView persists beneath it so it is already registered for drag
     * events before any cross-view drag starts (Android gotcha: views created after
     * startDragAndDrop do NOT receive ACTION_DRAG_STARTED).
     */
    @Nullable private FrameLayout drawerOverlay;

    // Widget host — started in onStart, stopped in onStop.
    private LauncherWidgetHost widgetHost;
    private boolean widgetHostListening = false;

    // Pending widget placement: tracks state across the bind and configure activity results.
    // Persisted in onSaveInstanceState so rotation during the flow doesn't leak the allocated id.
    private int pendingWidgetId = -1;

    /**
     * The home page the user was on when they picked the widget — where {@link #finalizeWidget()}
     * tries to place it first.
     *
     * <p>Captured at pick time rather than read back in {@code finalizeWidget}, because the bind
     * and configure activities are a full trip out of this process: a recreation in that window
     * rebuilds {@link #homescreenView} at page 0, so by the time the result lands the view no
     * longer knows where the user was. Saved alongside {@link #pendingWidgetId} for the same
     * reason.
     */
    private int pendingWidgetPage = 0;
    /**
     * Whether the pending widget was picked through the "Tap to add Google search" pill. Saved
     * alongside {@link #pendingWidgetId} for the same reason as {@link #pendingWidgetPage}: the
     * bind dialog is another process's activity, and a recreation during it must not turn the
     * pill's pick into an ordinary one.
     */
    private boolean pendingWidgetFromSearchPill = false;

    /**
     * Debounces package-broadcast reloads. A single install emits ADDED then REPLACED, and a store
     * batch repeats that per app; reloading on each would queue full re-enumerations on
     * AsyncTask's serial executor faster than they complete.
     */
    private ReloadCoalescer reloadCoalescer;

    /**
     * True once an app load has actually delivered results. Until then the spinner covers an
     * empty {@link #contentContainer}; afterwards reloads repaint in place, because hiding
     * contentContainer would take the {@link HomescreenView} down with it.
     */
    private boolean firstLoadDone = false;

    /** The in-flight load's callback, detached in {@code onDestroy} so it releases this Activity. */
    @Nullable private LoadCallback loadCallback;
    /**
     * The in-flight load's priority-phase callback. Tracked separately from
     * {@link #loadCallback} because it is delivered by its own main-thread post and so must be
     * detachable on its own — otherwise a destroyed Activity is still reachable from a queued
     * priority result even after the full load has been released.
     */
    @Nullable private LoadCallback priorityCallback;
    /** True once {@link #reportFullyDrawn()} has been called for this activity instance. */
    private boolean reportedFullyDrawn = false;

    /**
     * Reloads when apps are installed/updated/removed, or disabled/re-enabled.
     * Also prunes the home screen when an app is truly uninstalled (not part of an update).
     * Package broadcasts are per user, so the removal only ever names the personal (bare-key)
     * entry; a work copy of the same package stays until the reload this schedules finds it
     * gone (see {@link HomeScreenStore#removePackage}).
     *
     * <p>The cache/prune work is done inline because it is cheap and must not be lost if several
     * broadcasts arrive; only the expensive reload is coalesced.
     */
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (Intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())
                    && !isWholePackageChange(intent)) {
                return;
            }
            if (intent.getData() != null) {
                String pkg = intent.getData().getSchemeSpecificPart();
                // Only remove from home screen on actual uninstall, not during app updates.
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                        && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    HomeScreenStore.get(ctx).removePackage(pkg);
                }
            }
            reloadCoalescer.request();
        }
    };

    /**
     * Whether an {@code ACTION_PACKAGE_CHANGED} is the app itself being disabled or re-enabled,
     * rather than one of its components. The platform signals the former by putting the package
     * name in the changed-component list. Package-private for tests.
     */
    static boolean isWholePackageChange(Intent intent) {
        if (intent.getData() == null) return false;
        String pkg = intent.getData().getSchemeSpecificPart();
        String[] changed = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
        if (pkg == null || changed == null) return false;
        for (String name : changed) {
            if (pkg.equals(name)) return true;
        }
        return false;
    }

    /**
     * Reloads apps when a managed (work) profile is added, removed, or becomes unavailable.
     * On REMOVED/UNAVAILABLE, prunes home-screen items whose user serial is no longer valid so
     * deleted work profiles don't leave ghost items permanently blocking home-screen cells.
     */
    private final BroadcastReceiver profileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            // Collect the set of currently valid user serials and prune stale work items.
            UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
            Set<Long> validSerials = new HashSet<>();
            for (UserHandle user : um.getUserProfiles()) {
                validSerials.add(um.getSerialNumberForUser(user));
            }
            HomeScreenStore.get(ctx).removeUnknownUserSerials(validSerials);
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Opt in to edge-to-edge explicitly rather than inheriting it from targetSdk 35. On
        // Android 15+ the framework forces it anyway; declaring it here means older devices lay
        // out identically, so the inset handling in HomescreenView and applyToolbarInsets() has
        // one behaviour to be correct against instead of two.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // Traced end to end (see Perf). Deliberately not in a try/finally: nothing here is
        // recoverable, so an escaping exception kills the process and the unclosed slice is
        // moot — Perfetto truncates it at trace end.
        Perf.begin(Perf.ACTIVITY_ON_CREATE);
        Perf.begin(Perf.INFLATE);
        setContentView(R.layout.activity_main);
        Perf.end();
        widgetHost = new LauncherWidgetHost(this);

        // Restore pending widget id across rotation so the bind/configure flow can complete.
        if (savedInstanceState != null) {
            pendingWidgetId = savedInstanceState.getInt("pendingWidgetId", -1);
            pendingWidgetPage = clampToScreens(savedInstanceState.getInt("pendingWidgetPage", 0));
            pendingWidgetFromSearchPill =
                    savedInstanceState.getBoolean("pendingWidgetFromSearchPill", false);
        }

        rootLayout       = findViewById(R.id.root_layout);
        toolbarRow       = findViewById(R.id.toolbar_row);
        contentContainer = findViewById(R.id.content_container);
        progress         = findViewById(R.id.progress);
        toggleView       = findViewById(R.id.toggle_view);
        modeSwitch       = findViewById(R.id.mode_switch);
        settingsButton   = findViewById(R.id.settings_button);
        usageBanner      = findViewById(R.id.usage_banner);
        searchBox        = findViewById(R.id.search_box);

        settingsButton.setOnClickListener(v -> openSettings());
        // Somewhere for focus to go when the search box gives it up; see hideSearchKeyboard().
        contentContainer.setFocusableInTouchMode(true);
        registerBackHandler();
        applyWindowInsets();

        // Resolve the initial drawer mode from KEY_DRAWER_MODE, defaulting to LIST.
        // The app always starts in HOME state regardless of what was saved.
        int drawerOrdinal = Settings.getDrawerMode(this);
        if (drawerOrdinal < 0 || drawerOrdinal >= Mode.HOME.ordinal()) {
            drawerOrdinal = Mode.LIST.ordinal();
        }
        mode = Mode.values()[drawerOrdinal];
        if (!isEnabled(mode) || mode == Mode.HOME) mode = firstEnabledNonHomeMode();
        isHome = true; // always start on the homescreen

        // The mode-switch button cycles non-HOME drawer modes; HOME is reached via back/home.
        modeSwitch.setOnClickListener(v -> {
            mode = nextEnabledNonHomeMode(mode);
            getPrefs().edit().putInt(KEY_MODE, mode.ordinal()).apply();
            Settings.setDrawerMode(this, mode.ordinal());
            renderMode();
        });

        toggleView.setOnClickListener(v -> {
            showHeaders = !showHeaders;
            updateToggleIcon();
            renderMode();
        });

        usageBanner.setOnClickListener(v ->
                startActivity(UsageStatsHelper.usageAccessSettingsIntent()));

        reloadCoalescer = new ReloadCoalescer(this::loadApps);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        // ACTION_PACKAGE_CHANGED also fires on component enable/disable, which apps do routinely
        // in the background, so the receiver drops all of those and keeps only the whole app
        // being disabled or re-enabled (isWholePackageChange). That one does change the
        // launchable list: a disabled app is gone from it, and without this it stayed in the
        // drawer and on the home screen until some unrelated reload. The reload's full load
        // prunes it from the home screen like an uninstall (see AppRemoval).
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        // RECEIVER_NOT_EXPORTED: every action above is a protected broadcast today, so nothing can
        // spoof them, but declaring it means adding a non-protected action later cannot silently
        // make this receiver implicitly exported.
        ContextCompat.registerReceiver(this, packageReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // Work-profile lifecycle: reload when a managed profile appears, disappears, or locks.
        IntentFilter profileFilter = new IntentFilter();
        profileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        profileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        profileFilter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        ContextCompat.registerReceiver(this, profileReceiver, profileFilter,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                syncSearchClearControl();
                applyFilter();
                renderMode();
            }
        });
        // Enter on a query with exactly one match opens it: the keyboard is already up and the
        // result is unambiguous, so making the user reach for the tile is a wasted tap.
        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (!enter || query.isEmpty() || apps.size() != 1) return false;
            launch(apps.get(0));
            return true;
        });
        // ...and because it is a drawable and not a view, it has no node of its own: TalkBack and
        // switch access could not reach it at all (QA findings R3 / B5). A custom action on the
        // field itself, rather than a second view, keeps the touch behaviour and the toolbar's
        // widths exactly as they are — see syncSearchClearControl for why the widths matter.
        ViewCompat.setAccessibilityDelegate(searchBox, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                // Offered only while there is something to clear, so the action list matches the
                // ✕ the sighted user sees.
                if (query.isEmpty()) return;
                info.addAction(new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        R.id.action_clear_search, "Clear search"));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, @Nullable Bundle args) {
                if (action != R.id.action_clear_search) {
                    return super.performAccessibilityAction(host, action, args);
                }
                // setText alone, as the ✕'s own tap does: the keyboard stays up and the field
                // keeps focus, so the next query can be typed straight away.
                searchBox.setText("");
                return true;
            }
        });
        // The clear control is a compound drawable, so its tap is a touch on the field's end.
        searchBox.setOnTouchListener((v, e) -> {
            if (e.getAction() != android.view.MotionEvent.ACTION_UP || query.isEmpty()) return false;
            android.graphics.drawable.Drawable clear = searchBox.getCompoundDrawablesRelative()[2];
            if (clear == null) return false;
            if (e.getX() < searchBox.getWidth() - searchBox.getPaddingEnd() - clear.getIntrinsicWidth()) return false;
            searchBox.setText("");
            searchBox.performClick();   // keeps the click-side accessibility contract
            return true;
        });

        HomeScreenStore store = HomeScreenStore.get(this);
        reportCorruptLoad(store);
        // Keys written before "personal" meant the user we actually run as: on a secondary or
        // guest user that stored even that user's own apps as "pkg#serial", which resolves to
        // nothing now. Idempotent, so running it on every launch costs one pass over the items.
        store.migrateOwnUserKeys(this);

        // First run on a fresh install: seed the default dock (spec:
        // docs/superpowers/specs/2026-07-20-default-home-screen-design.md).
        Perf.begin(Perf.SEED);
        DefaultHomeSeeder.seedIfNeeded(this, store);
        Perf.end();

        loadApps();
        Perf.end(); // ACTIVITY_ON_CREATE
    }

    @Override
    protected void onStart() {
        super.onStart();
        try {
            widgetHost.startListening();
            widgetHostListening = true;
        } catch (Exception e) {
            // Degrade gracefully — widget updates may not arrive but the host stays usable.
            Log.w("MainActivity", "widgetHost.startListening failed; widgets will not update", e);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (widgetHostListening) {
            try {
                widgetHost.stopListening();
            } catch (RuntimeException e) {
                // Some OEM builds throw (SecurityException or similar) when stopListening
                // is called in unexpected states.
                Log.w("MainActivity", "widgetHost.stopListening failed", e);
            } finally {
                widgetHostListening = false;
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("pendingWidgetId", pendingWidgetId);
        outState.putInt("pendingWidgetPage", pendingWidgetPage);
        outState.putBoolean("pendingWidgetFromSearchPill", pendingWidgetFromSearchPill);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RC_WIDGET_BIND) {
            if (resultCode == RESULT_OK) {
                proceedToConfigureOrFinalize();
            } else {
                cancelPendingWidget();
            }
        } else if (requestCode == RC_WIDGET_CONFIGURE) {
            if (resultCode == RESULT_OK) {
                finalizeWidget();
            } else {
                cancelPendingWidget();
            }
        } else if (requestCode == RC_SETTINGS) {
            // SettingsActivity always finishes with RESULT_OK — there is no RESULT_CANCELED
            // path, back press included — so every step below runs unconditionally rather than
            // being gated on a "something changed" flag. Tracking which of several unrelated
            // settings screens changed something would be far more fragile than just re-running
            // all four cheap, idempotent re-checks on every return.
            if (homescreenView != null) homescreenView.refresh();
            // A successful backup restore replaces the whole home-screen layout; the user should
            // see it immediately rather than still be looking at the drawer. Settings is only
            // reachable from the drawer toolbar (see activity_main.xml's toolbar_row, which is
            // hidden on the homescreen), so the drawer is always open when this extra is set.
            // Inlined from goHome()'s non-animated branch rather than calling goHome() itself:
            // goHome() would see isHome == false here and take its *animated* slide-down path,
            // leaving the restored layout hidden behind a drawer overlay for another 180 ms.
            // (goHome() now flips isHome synchronously, so this is a presentation choice rather
            // than the overlay-orphaning hazard it originally guarded against.)
            if (data != null && data.getBooleanExtra(SettingsActivity.EXTRA_RESTORED_LAYOUT, false)) {
                isHome = true;
                if (drawerOverlay != null) {
                    contentContainer.removeView(drawerOverlay);
                    drawerOverlay = null;
                    syncHomeAccessibility();
                }
            }
            // The enabled-views mask may have just disabled the mode currently on screen.
            // Settings.setEnabledModesMask already reconciled the persisted drawer_mode pref,
            // but `mode` is a live field assigned only in onCreate (see above) — a
            // paused-not-recreated MainActivity would otherwise keep rendering a mode the user
            // just turned off until process death. Mirrors the fallback this activity already
            // applies to its own mode field wherever it edits the enabled-modes mask.
            if (!isEnabled(mode) || mode == Mode.HOME) {
                mode = firstEnabledNonHomeMode();
                getPrefs().edit().putInt(KEY_MODE, mode.ordinal()).apply();
                Settings.setDrawerMode(this, mode.ordinal());
            }
            // Color source may have changed; re-extract app colors so the change is visible
            // immediately instead of silently waiting for an unrelated reload trigger (a
            // usage-access grant, package add/remove). loadApps() is async and renders again on
            // completion; the renderMode() below is the immediate, synchronous repaint using the
            // (possibly just-corrected) mode from the step above.
            loadApps();
            renderMode();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // try/finally, and note the early return below is inside it — this is the hot-resume
        // path's only measurable cost (see StartupBenchmark.startupHot), so the slice has to
        // close on every exit.
        Perf.begin(Perf.ON_RESUME);
        try {
            // Before the firstResume early-return below — badge animation must restart on every
            // resume, not just the ones that reload apps.
            BadgeAnimator.resume();
            // Same reason: the icons hang from their pins on every resume, first one included.
            Wobble.resume(this);
            // The initial load happens in onCreate; the package receiver keeps data fresh. Only
            // reload here if Usage Access changed (e.g. the user just granted it from Settings).
            boolean ua = UsageStatsHelper.hasAccess(this);
            if (firstResume) { firstResume = false; return; }
            if (ua != hasUsageAccess) loadApps();
        } finally {
            Perf.end();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Hard-stop the shared badge frame loop so a backgrounded launcher burns no frames.
        // Registrations survive, so onResume picks straight back up.
        BadgeAnimator.pause();
        Wobble.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) {}
        try { unregisterReceiver(profileReceiver);  } catch (IllegalArgumentException ignored) {}
        // A delayed reload outlives the Activity that posted it, and an in-flight AppLoader task
        // keeps this Activity — its whole view tree and every app-icon Drawable — reachable until
        // it finishes. No configChanges is declared, so a uiMode/font/locale/multi-window change
        // recreates this Activity mid-load routinely.
        if (reloadCoalescer != null) reloadCoalescer.cancel();
        if (loadCallback != null) {
            loadCallback.detach();
            loadCallback = null;
        }
        if (priorityCallback != null) {
            priorityCallback.detach();
            priorityCallback = null;
        }
    }

    /** Pressing Home while already here: always return to the homescreen. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Perf.begin(Perf.NEW_INTENT);
        try {
            if (isDrawerShowing()) {
                // Clear drawer state before going home so no stale overlays remain. The frame
                // can be open underneath the drawer, and Home must not hand it back.
                if (homescreenView != null) homescreenView.dismissResizeFrame();
                if (!query.isEmpty()) searchBox.setText("");
                goHome();
                return;
            }
            if (homescreenView != null) {
                // The frame is modal in practice: while it is up it owns the touch stream, so the
                // press after Home went into dismissing it instead of doing anything (QA R4).
                // Home is the reset key — it already closes the drawer, the chooser and a folder.
                homescreenView.dismissResizeFrame();
                homescreenView.closeFolderPopup();
            }
            if (!query.isEmpty()) searchBox.setText("");
        } finally {
            Perf.end();
        }
    }

    /**
     * Registers Back with the activity's {@link androidx.activity.OnBackPressedDispatcher}, which
     * is the only route that still reaches this code on Android 16.
     *
     * <p>This used to be an {@code onBackPressed()} override, and it stopped running the day the
     * app targeted 36: with {@code targetSdk >= 33} on Android 13+, and by default from 16,
     * predictive back is on, the framework dispatches Back to an {@code OnBackInvokedCallback}
     * instead of calling {@code onBackPressed()}, and with no callback registered its default one
     * <em>finishes</em> the activity. A finished HOME activity is relaunched by the system a few
     * milliseconds later, so every "Back closes X" was really "Back kills and recreates the
     * launcher": it looked right for the drawer, cost a cold-ish start each time, threw the user
     * back to page 0, and dropped anything the dying instance had not persisted (a folder renamed
     * in its popup and closed with Back kept its old name). The AndroidX dispatcher registers the
     * framework callback itself on API 33+ and falls back to the old key path below that.
     *
     * <p>Always enabled: the launcher's only activity must never finish on Back. Toggling the
     * callback off to "let the system handle it" would hand the press straight back to the
     * finishing default.
     *
     * <p>Robolectric cannot see predictive back, so {@code BackHandlingTest} drives
     * {@code getOnBackPressedDispatcher().onBackPressed()} — the same entry the framework
     * callback uses — and asserts on what closed and that nothing finished.
     */
    private void registerBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
    }

    /**
     * What Back does, in one place: one layer per press, outermost first — a query back to the
     * full list, the drawer back to the home screen, an overlay off the home screen. With
     * nothing open, Back returns to the first page, which is what the relaunch used to do by
     * accident and what stock launchers do on purpose.
     */
    void handleBack() {
        // isDrawerShowing() rather than !isHome so a second back-press inside the close
        // animation finishes the close instead of falling through and doing nothing.
        if (isDrawerShowing()) {
            // The query first, leaving the drawer open. When the keyboard is up it has already
            // consumed a press of its own before this one.
            if (!query.isEmpty()) { searchBox.setText(""); return; }
            goHome();
            return;
        }
        // isHome is already true inside the 180 ms close animation while the overlay is still
        // attached and eating touches: a second press there finishes the close rather than
        // falling through to the home-screen branch and doing nothing.
        if (drawerOverlay != null) { goHome(); return; }
        // On the homescreen: close overlays in order.
        // Phase C: close resize frame before folder popup.
        if (homescreenView != null && homescreenView.isResizeFrameOpen()) {
            homescreenView.dismissResizeFrame();
            return;
        }
        if (homescreenView != null && homescreenView.isFolderPopupOpen()) {
            homescreenView.closeFolderPopup();
            return;
        }
        if (!query.isEmpty()) { searchBox.setText(""); return; }
        if (homescreenView != null) homescreenView.animateToPage(0);
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Opens the sectioned settings screen; result is handled in {@link #onActivityResult}. */
    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), RC_SETTINGS);
    }

    /**
     * Applies system-bar insets to the drawer chrome: the toolbar drops below the status bar and
     * the drawer overlay keeps its content clear of the navigation bar.
     *
     * <p>Needed because the window is edge-to-edge (forced by targetSdk 35 on Android 15+, and
     * opted into explicitly in {@code onCreate} so older devices match). This replaces the
     * layout's old hardcoded 40dp toolbar padding — a guess that came up short on tall devices
     * with a punch-hole camera.
     *
     * <p>The listener sits on the root and returns the insets unconsumed, so {@link
     * HomescreenView} — which applies them to its own grid geometry — still receives them.
     */
    private void applyWindowInsets() {
        final int basePad = dp(8);
        rootLayout.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top    = bars.top;
                bottom = bars.bottom;
            } else {
                top    = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            toolbarRow.setPadding(toolbarRow.getPaddingLeft(), top + basePad,
                                  toolbarRow.getPaddingRight(), toolbarRow.getPaddingBottom());
            navBottomInset = bottom;
            applyDrawerBottomInset();
            return insets;
        });
    }

    /**
     * Keeps the drawer overlay's content above the navigation bar. Padding (not margin) so the
     * overlay's opaque background still paints the strip behind the gesture pill.
     */
    private void applyDrawerBottomInset() {
        if (drawerOverlay != null) drawerOverlay.setPadding(0, 0, 0, navBottomInset);
    }

    private boolean isEnabled(Mode m) {
        return Settings.isModeEnabled(this, m.ordinal());
    }

    private Mode firstEnabledMode() {
        for (Mode m : Mode.values()) if (isEnabled(m)) return m;
        return Mode.HOME;
    }

    private Mode nextEnabledMode(Mode from) {
        Mode[] vals = Mode.values();
        for (int i = 1; i <= vals.length; i++) {
            Mode candidate = vals[(from.ordinal() + i) % vals.length];
            if (isEnabled(candidate)) return candidate;
        }
        return from; // only one enabled
    }

    // -------------------------------------------------------------------------
    // Home ↔ drawer navigation
    // -------------------------------------------------------------------------

    /** Returns the first enabled non-HOME mode; falls back to RAINBOW if none are enabled. */
    private Mode firstEnabledNonHomeMode() {
        for (Mode m : Mode.values()) {
            if (m != Mode.HOME && isEnabled(m)) return m;
        }
        return Mode.LIST;
    }

    /** Cycles to the next enabled non-HOME mode (skips HOME). */
    private Mode nextEnabledNonHomeMode(Mode from) {
        Mode[] vals = Mode.values();
        for (int i = 1; i <= vals.length; i++) {
            Mode candidate = vals[(from.ordinal() + i) % vals.length];
            if (candidate != Mode.HOME && isEnabled(candidate)) return candidate;
        }
        return from; // only one non-HOME mode enabled
    }

    /** Number of enabled non-HOME modes (used to decide whether the mode-switch button shows). */
    private int enabledNonHomeModeCount() {
        int n = 0;
        for (Mode m : Mode.values()) if (m != Mode.HOME && isEnabled(m)) n++;
        return n;
    }

    /**
     * Returns true if the user has disabled animations system-wide (accessibility / reduced-motion).
     * When true, transitions should be instant.
     */
    private boolean isAnimationDisabled() {
        float scale = android.provider.Settings.Global.getFloat(
                getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return scale == 0f;
    }

    /**
     * Opens the app drawer with the last-used non-HOME mode. Called by the swipe-up gesture.
     * Clears any stale search query so the drawer opens cleanly.
     * The drawer overlay slides/fades up into place (~220 ms, decelerate) on top of the
     * persistent HomescreenView so the homescreen is always registered for drag events.
     * Package-private for tests.
     */
    void openDrawer() {
        if (!query.isEmpty()) searchBox.setText("");
        isHome = false;

        // Cancel any in-flight close animation.
        if (isAnimatingDrawer && drawerOverlay != null) {
            drawerOverlay.animate().cancel();
            isAnimatingDrawer = false;
        }

        renderMode();

        if (drawerOverlay == null || isAnimationDisabled()) return;

        // Animate the newly-created drawer overlay in: slide up from below.
        final int SLIDE_OPEN_MS = 220;
        final float SLIDE_OFFSET_PX = 80f * getResources().getDisplayMetrics().density;

        drawerOverlay.setTranslationY(SLIDE_OFFSET_PX);
        drawerOverlay.setAlpha(0f);
        isAnimatingDrawer = true;
        drawerOverlay.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(SLIDE_OPEN_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> isAnimatingDrawer = false)
                .start();
    }

    /**
     * Returns to the homescreen. Called by back-press and onNewIntent when the drawer is open.
     * Animates the drawer overlay sliding/fading down (~180 ms), then removes it.
     * The HomescreenView beneath is never destroyed — it stays registered for drag events.
     */
    void goHome() {
        // Every route out of the drawer passes here — Back, Home, an app launch, a widget add —
        // and only Back closes the keyboard on its own. Home with the search box focused left
        // the IME standing over the wallpaper with no text field under it, eating the swipe
        // that opens the drawer; the chooser did the same, modal over a home screen it was
        // claiming to describe.
        hideSearchKeyboard();
        if (!isHome && drawerOverlay != null && !isAnimationDisabled()) {
            // Cancel any in-flight open animation first.
            if (isAnimatingDrawer) {
                drawerOverlay.animate().cancel();
                isAnimatingDrawer = false;
            }

            final int SLIDE_CLOSE_MS = 180;
            final float SLIDE_OFFSET_PX = 80f * getResources().getDisplayMetrics().density;
            final FrameLayout overlayToRemove = drawerOverlay;

            // Flip to HOME *now*, not in the end action. Anything that calls renderMode() inside
            // the 180 ms window — a loadApps() callback, most easily — would otherwise still see
            // isHome == false, take the drawer branch, and install a second overlay on top; the
            // end action would then null the field while that new overlay was still attached,
            // leaving an overlay no dismissal path could ever reach.
            isHome = true;

            isAnimatingDrawer = true;
            overlayToRemove.animate()
                    .translationY(SLIDE_OFFSET_PX)
                    .alpha(0f)
                    .setDuration(SLIDE_CLOSE_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> finishDrawerClose(overlayToRemove))
                    .start();
        } else {
            isHome = true;
            if (drawerOverlay != null) {
                // Reached with an animation still running when the user presses back a second
                // time inside the close window: finish the close immediately rather than
                // leaving the overlay to an end action that will now decline to touch it.
                if (isAnimatingDrawer) {
                    drawerOverlay.animate().cancel();
                    isAnimatingDrawer = false;
                }
                contentContainer.removeView(drawerOverlay);
                drawerOverlay = null;
            }
            renderMode();
        }
    }

    /**
     * Tears down the overlay whose close animation just finished. Package-private for tests.
     *
     * <p>Only acts if that overlay is still the live one: {@link #openDrawer()},
     * {@link #dismissDrawerForDrag()} and the restore path in {@link #onActivityResult} can all
     * replace or clear {@link #drawerOverlay} inside the animation window, and each already
     * detached the overlay we were closing. Clearing the field on their behalf would strand a
     * visible, attached overlay that swallows every touch — nothing removes it, because
     * back-press, {@code onNewIntent} and {@code renderMode()} all short-circuit once HOME.
     */
    void finishDrawerClose(FrameLayout overlayToRemove) {
        if (drawerOverlay != overlayToRemove) return;
        isAnimatingDrawer = false;
        isHome = true;
        contentContainer.removeView(overlayToRemove);
        drawerOverlay = null;
        renderMode(); // update toolbar visibility etc.
    }

    /**
     * Hides the home screen from accessibility services for as long as the drawer overlay covers
     * it, and hands it back when the overlay goes.
     *
     * <p>The overlay is a sibling of the home content, not a dialog, so nothing in the framework
     * knows it is modal: the dock icons and the "Tap to add Google search" placeholder all stayed
     * focusable and clickable underneath it. A screen-reader user swiping past the last drawer item
     * landed on controls they could not see and could activate them.
     *
     * <p>Derived from {@link #drawerOverlay} rather than from {@link #isHome} on purpose: isHome
     * is already true for the 180 ms of the close animation, while the overlay is still attached
     * and still covering everything. Every route out of the drawer — Back, Home, launching an app,
     * a drag dismissal, a rebuild — passes through {@link #renderMode()} or names this itself, so
     * there is one answer to "is the home screen behind the drawer right now".
     */
    private void syncHomeAccessibility() {
        if (contentContainer == null) return;
        int flag = drawerOverlay != null
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        for (int i = 0; i < contentContainer.getChildCount(); i++) {
            View child = contentContainer.getChildAt(i);
            if (child == drawerOverlay) continue;
            child.setImportantForAccessibility(flag);
        }
    }

    /**
     * True while a drawer overlay is on screen — including the ~180 ms of its close animation,
     * during which {@link #isHome} is already true but the overlay is still attached and still
     * eating touches. Back-press and Home must keep treating that as "the drawer is open".
     */
    private boolean isDrawerShowing() {
        return !isHome || drawerOverlay != null;
    }

    /**
     * Immediately dismisses the drawer overlay without animation. Called when a cross-view drag
     * starts from the drawer so the home grid is revealed for the drop.
     * Safe to call from a drag-event callback (runs on the main thread).
     * Package-private for tests.
     */
    void dismissDrawerForDrag() {
        if (isAnimatingDrawer && drawerOverlay != null) {
            drawerOverlay.animate().cancel();
            isAnimatingDrawer = false;
        }
        if (drawerOverlay != null) {
            contentContainer.removeView(drawerOverlay);
            drawerOverlay = null;
        }
        // This path never calls renderMode(), so it gives the home screen back itself.
        syncHomeAccessibility();
        // Dismiss the search keyboard — hiding the toolbar does not close an open IME,
        // so without this the keyboard would sit over the home grid during the drag.
        hideSearchKeyboard();

        // Mark as home so toolbar is hidden while the drag continues.
        isHome = true;
        toolbarRow.setVisibility(View.GONE);
        usageBanner.setVisibility(View.GONE);
        contentContainer.setBackgroundColor(0x00000000);
    }

    /**
     * Closes the search keyboard and drops the search box's focus. Hiding the toolbar does not
     * close an open IME, and neither does removing the overlay: the keyboard belongs to the
     * window, so anything that takes the drawer away has to take the keyboard with it.
     */
    private void hideSearchKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
        // Not searchBox.clearFocus(): while the toolbar is still showing (an app launch, the
        // 180 ms of the close animation) the search box is the window's only focusable view and
        // clearFocus() hands focus straight back to it, where the IME would find it again on
        // the next window-focus gain. Park focus on the content container instead.
        contentContainer.requestFocus();
        searchBox.clearFocus();
    }

    // -------------------------------------------------------------------------
    // Widget bind / configure / finalize flow
    // -------------------------------------------------------------------------

    /**
     * Called when the user selects a widget in HomescreenView — from the picker, or from the
     * "Tap to add Google search" pill.
     *
     * @param fromSearchPill true for the pill; see {@link #finalizeWidget} for what that changes
     */
    private void onPickWidget(AppWidgetProviderInfo provider, boolean fromSearchPill) {
        int widgetId = widgetHost.allocateAppWidgetId();
        beginPendingWidget(widgetId, fromSearchPill);

        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        if (awm.bindAppWidgetIdIfAllowed(widgetId, provider.provider)) {
            // Bind succeeded — proceed to configure if needed.
            proceedToConfigureOrFinalize();
        } else {
            // Ask the user to grant binding permission.
            Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider);
            try {
                startActivityForResult(intent, RC_WIDGET_BIND);
            } catch (ActivityNotFoundException e) {
                // Some AOSP variants and OEM TV/tablet builds ship no handler for
                // ACTION_APPWIDGET_BIND. Crashing here is the worst outcome for a HOME app, and
                // the id allocated above would leak into the host on every attempt.
                Log.w("MainActivity", "no handler for ACTION_APPWIDGET_BIND", e);
                cancelPendingWidget();
                Toast.makeText(this, "Can't add widgets on this device", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * The state half of {@link #onPickWidget}, split out from the {@code AppWidgetManager} bind
     * so a test can drive the rest of the flow without one. Package-private for tests.
     */
    void beginPendingWidget(int widgetId) {
        beginPendingWidget(widgetId, false);
    }

    /** As {@link #beginPendingWidget(int)}, for a pick that came from the search pill. */
    void beginPendingWidget(int widgetId, boolean fromSearchPill) {
        pendingWidgetId = widgetId;
        pendingWidgetPage = homescreenView != null
                ? clampToScreens(homescreenView.getCurrentPage())
                : 0;
        pendingWidgetFromSearchPill = fromSearchPill;
    }

    /**
     * Swaps the widget host, so a test can install one whose {@code deleteAppWidgetId} throws
     * the way some OEM builds' does. Package-private for tests.
     */
    void setWidgetHostForTest(LauncherWidgetHost host) {
        widgetHost = host;
        if (homescreenView != null) homescreenView.setWidgetHost(host);
    }

    private static int clampToScreens(int screen) {
        return Math.max(0, Math.min(HomeScreenStore.MAX_SCREENS - 1, screen));
    }

    /**
     * After a successful bind (immediate or via activity result): if the provider has a
     * configure activity launch it, otherwise finalise placement directly.
     */
    private void proceedToConfigureOrFinalize() {
        if (pendingWidgetId < 0) return;
        AppWidgetProviderInfo info =
                AppWidgetManager.getInstance(this).getAppWidgetInfo(pendingWidgetId);
        if (info == null) {
            cancelPendingWidget();
            return;
        }
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(
                        this, pendingWidgetId, 0, RC_WIDGET_CONFIGURE, null);
            } catch (ActivityNotFoundException e) {
                // Configure activity missing — place without configuration.
                finalizeWidget();
            }
        } else {
            finalizeWidget();
        }
    }

    /**
     * Places the pending widget at the first free cell on the page the user added it from.
     * Package-private for tests.
     */
    void finalizeWidget() {
        if (pendingWidgetId < 0) return;
        AppWidgetManager awm = AppWidgetManager.getInstance(this);
        AppWidgetProviderInfo info = awm.getAppWidgetInfo(pendingWidgetId);
        if (info == null) {
            cancelPendingWidget();
            return;
        }

        int[] span = computeWidgetSpan(info);
        int spanX = span[0];
        int spanY = span[1];

        HomeScreenStore store = HomeScreenStore.get(this);

        // The pill draws across a whole row of page 0, so the widget it promises should take that
        // row: the provider's minimum made the search bar 3 of 4 columns, sitting visibly
        // off-centre under a 4-column pill. Only when such a row is still free (the scan below
        // then lands there — firstFreeFullRow is what placed the pill too, so the two cannot
        // disagree about which row) and only for a provider that permits a horizontal resize —
        // resizeMode is a constraint, not a suggestion, and the user can shrink it again from
        // the frame.
        if (pendingWidgetFromSearchPill
                && (info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0
                && store.firstFreeFullRow(0, spanY) >= 0) {
            spanX = store.columns();
        }

        // Set the FULL options bundle (min/max dp + OPTION_APPWIDGET_SIZES) BEFORE the widget is
        // added to the store & rendered, so the provider's very first composition — and the host's
        // first applyRemoteViews — sees a real, non-zero size instead of the empty default that
        // makes multi-size Glance widgets fall back to the "Couldn't add widget." error layout.
        // Use the display-metrics cell estimate so options are non-zero even before any layout pass.
        float density = getResources().getDisplayMetrics().density;
        int[] cellPx = WidgetSizeHelper.estimateCellPx(this, store.columns(), store.rows());
        WidgetSizeHelper.applyFullOptions(this, pendingWidgetId, spanX, spanY,
                cellPx[0], cellPx[1], density);

        // Start at the page the widget was added from, then walk forward, wrapping. This scan
        // used to start at 0 unconditionally, so adding a widget from page 2 dropped it on page 0
        // whenever page 0 still had a free cell — which it usually does, leaving the user staring
        // at the page they asked for with nothing new on it. Walking forward rather than falling
        // back to 0 also means a full page spills onto the next one along (creating it if need
        // be), where the user can actually see it, instead of onto some earlier page.
        int screen = -1;
        int[] cell = null;
        for (int i = 0; i < HomeScreenStore.MAX_SCREENS; i++) {
            int candidate = (pendingWidgetPage + i) % HomeScreenStore.MAX_SCREENS;
            cell = store.firstFreeCell(candidate, spanX, spanY);
            if (cell != null) {
                screen = candidate;
                break;
            }
        }
        if (cell == null) {
            Toast.makeText(this, "No space for widget", Toast.LENGTH_SHORT).show();
            cancelPendingWidget();
            return;
        }

        store.addItem(HomeItem.widget(screen, cell[0], cell[1], spanX, spanY,
                pendingWidgetId, info.provider.flattenToString()));

        // The pill has done its job. Its own check is only "top row free and not dismissed",
        // so without this it came back the moment the top row emptied again — with the search
        // bar sitting on another page — inviting a second one. Same latch as the pill's ✕, and
        // set only here, on success: a bind dialog cancelled keeps the pill (see cancelPendingWidget).
        if (pendingWidgetFromSearchPill) Settings.setSearchPlaceholderDismissed(this, true);

        cleanupPendingWidget();
        // Return to the homescreen so the new widget is immediately visible. goHome() rebuilds
        // the page strip, so the landing page exists by the time we ask to be shown it — but
        // only because the flag is raised first: the store changed behind the view's back.
        homescreenStale = true;
        goHome();
        // Usually a no-op — the widget went on the page already showing. It is not a no-op when
        // the page was full and the widget spilled onto the next one, or when the bind/configure
        // round trip recreated the activity and left the rebuilt view on page 0. In both cases
        // the alternative is a widget the user never sees, which looks exactly like a failure.
        if (homescreenView != null) homescreenView.animateToPage(screen);
    }

    /**
     * Deletes the allocated widget id and clears pending state on any cancellation.
     *
     * <p>{@code deleteAppWidgetId} throws on some OEM builds, and this runs on the main thread
     * from {@link #onActivityResult} — the bind and configure activities' cancel path, i.e. the
     * user pressing Back — where an escaping exception takes the home screen down with it. The
     * other four call sites (HomescreenView's stale-widget prune and drag-to-remove,
     * SettingsActivity's reset) already wrap it; these two were the last bare ones. A leaked id
     * is always the cheaper failure, and the pending state must be cleared regardless, or the
     * next widget added inherits a stale id.
     */
    private void cancelPendingWidget() {
        if (pendingWidgetId >= 0) {
            try {
                widgetHost.deleteAppWidgetId(pendingWidgetId);
            } catch (RuntimeException e) {
                Log.w("MainActivity", "deleteAppWidgetId failed for " + pendingWidgetId, e);
            }
        }
        cleanupPendingWidget();
    }

    private void cleanupPendingWidget() {
        pendingWidgetId = -1;
        pendingWidgetPage = 0;
        pendingWidgetFromSearchPill = false;
    }

    /**
     * One-time repair of widgets saved at the wrong size, from when placement scaled the
     * provider's already-in-pixels minimum by density a second time.
     *
     * <p>Guarded by a pref rather than made idempotent: shrinking is safe to repeat in the sense
     * that it never collides, but now that resize works it would also keep undoing any widget the
     * user deliberately enlarged.
     *
     * <p>Failures are swallowed. This is cosmetic repair on the HOME app's startup path, and an
     * unreadable {@code AppWidgetManager} must not cost the user their home screen.
     */
    private void correctOversizedWidgetSpansOnce(HomeScreenStore store) {
        if (Settings.getWidgetSpansCorrected(this)) return;
        try {
            AppWidgetManager awm = AppWidgetManager.getInstance(this);
            if (awm == null) return;
            int[] cell = homescreenView != null ? homescreenView.measuredCellSize() : null;
            if (cell == null || cell[0] <= 0 || cell[1] <= 0) return; // retry next launch
            int cols = store.columns(), rows = store.rows();

            Map<String, int[]> cache = new HashMap<>();
            boolean changed = store.shrinkOversizedWidgets(provider -> {
                if (cache.containsKey(provider)) return cache.get(provider);
                int[] span = null;
                ComponentName cn = ComponentName.unflattenFromString(provider);
                if (cn != null) {
                    for (AppWidgetProviderInfo info : awm.getInstalledProviders()) {
                        if (info != null && cn.equals(info.provider)) {
                            span = HomescreenView.spanForProvider(info, cell[0], cell[1], cols, rows);
                            break;
                        }
                    }
                }
                cache.put(provider, span);
                return span;
            });

            Settings.setWidgetSpansCorrected(this, true);
            if (changed && homescreenView != null) homescreenView.refresh();
        } catch (RuntimeException e) {
            Log.w("MainActivity",
                    "could not correct oversized widget spans; leaving the layout alone", e);
        }
    }

    /**
     * Computes widget span using the current HomescreenView dimensions when available, falling
     * back to DisplayMetrics when the view has not been laid out yet.
     */
    private int[] computeWidgetSpan(AppWidgetProviderInfo info) {
        HomeScreenStore store = HomeScreenStore.get(this);
        int storeCols = store.columns();
        int storeRows = store.rows();
        int cellW, cellH;
        // Ask the view for its own cell size rather than re-deriving it here: it is the only
        // place that knows the system-bar insets it laid the grid out inside.
        int[] cell = homescreenView != null ? homescreenView.measuredCellSize() : null;
        if (cell != null) {
            cellW = cell[0];
            cellH = cell[1];
        } else {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            cellW = dm.widthPixels  / storeCols;
            cellH = dm.heightPixels / storeRows;
        }
        return HomescreenView.spanForProvider(info, cellW, cellH, storeCols, storeRows);
    }

    /**
     * Tells the user once that their saved layout could not be read, then acknowledges it so
     * saving resumes.
     *
     * <p>Until this runs the store suppresses every save for the whole process life: the user got
     * a blank home screen with no explanation, rebuilt it, and lost the rebuild on the next
     * launch. The quarantined file is named because its bytes are the only copy of the old layout.
     * Self-limiting — acknowledging clears the flag, so an Activity recreation does not repeat it.
     */
    private void reportCorruptLoad(HomeScreenStore store) {
        if (!store.isLoadCorrupt()) return;
        String kept = store.corruptFileName();
        String message = "Your home screen layout could not be read, so it has been reset.";
        if (kept != null) message += " The old file was kept as " + kept + ".";
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        store.acknowledgeCorruptLoad();
    }

    /**
     * Starts an app load. Package-private for tests.
     *
     * <p>Only the very first load blanks the content for the spinner. {@link #contentContainer}
     * is where the {@link HomescreenView} lives, so hiding it on every reload made the whole home
     * screen disappear each time a package broadcast arrived — repeatedly, for minutes, during a
     * store batch update. Every later load repaints in place when it completes.
     */
    void loadApps() {
        hasUsageAccess = UsageStatsHelper.hasAccess(this);

        // Detach any earlier in-flight load: its result is stale and applying it would repaint
        // with an older app set after the newer one has already landed. Both phases of the
        // previous load have to go — the priority half can still be queued on the main thread.
        if (loadCallback != null) loadCallback.detach();
        if (priorityCallback != null) priorityCallback.detach();
        priorityCallback = new LoadCallback(this, true);
        loadCallback = new LoadCallback(this, false);
        // Application context, not this Activity: AsyncTask keeps its Context alive for the whole
        // load, and this Activity can be recreated (uiMode, font size, locale, multi-window)
        // while one is in flight.
        AppLoader.loadAsync(getApplicationContext(),
                HomeScreenStore.get(this).referencedAppKeys(),
                priorityCallback, loadCallback);
    }

    /**
     * Applies the priority phase: the home screen's own apps, ahead of the full list.
     *
     * <p>Deliberately does <em>not</em> do two things {@link #applyLoadedApps} does.
     *
     * <p>It does not call {@code removeUnknownApps}. That prunes home-screen items missing from
     * the list it is given, and this list is a subset by construction — handing it a partial
     * view of the world risks deleting the user's layout. Pruning stays with the full load,
     * where "absent" genuinely means "not installed".
     *
     * <p>It does not set {@link #firstLoadDone}. The drawer still needs the complete list, so
     * the spinner logic in {@link #renderMode()} must keep treating the load as unfinished.
     */
    void applyPriorityApps(List<AppInfo> loaded) {
        allApps.clear();
        allApps.addAll(loaded);
        homescreenStale = true;
        applyFilter();
        contentContainer.setVisibility(View.VISIBLE);
        renderMode();
        reportFullyDrawnOnce();
    }

    /**
     * Applies a completed app load. Package-private for tests; called only from
     * {@link LoadCallback}, which has already established that this Activity is still alive.
     */
    void applyLoadedApps(List<AppInfo> loaded) {
        allApps.clear();
        allApps.addAll(loaded);
        // Prune home-screen items whose package went away while no receiver was listening —
        // an uninstall with this Activity destroyed leaves a ghost cell that renders nothing
        // but still counts as occupied, so it can never be reused.
        HomeScreenStore.get(this).removeUnknownApps(this, loaded);
        homescreenStale = true;
        applyFilter();
        firstLoadDone = true;
        contentContainer.setVisibility(View.VISIBLE);
        // Spinner visibility is renderMode()'s job now: the home screen never wants one (the
        // priority phase already gave it every icon it references), while the drawer does until
        // the full list lands.
        renderMode();
        // Also reported here, not only from the priority phase: an empty home screen skips that
        // phase entirely, and startup is not "fully drawn" until one of the two has rendered.
        reportFullyDrawnOnce();
    }

    /**
     * Marks the end of startup for the framework's time-to-full-display measurement.
     *
     * <p>Without this, {@code timeToInitialDisplayMs} — what {@code am start -W} reports — is the
     * only startup number available, and it measures the first frame, which used to be a
     * spinner. TTFD is what the user actually waits for.
     *
     * <p>Guarded because {@link #loadApps()} also runs on package install, work-profile changes
     * and returning from settings. The framework honours only the first call; the flag makes
     * that intent explicit rather than incidental.
     */
    private void reportFullyDrawnOnce() {
        if (reportedFullyDrawn) return;
        reportedFullyDrawn = true;
        reportFullyDrawn();
    }

    /**
     * Delivers an {@link AppLoader} result to a still-living Activity.
     *
     * <p>Static and detachable on purpose: a lambda capturing {@code MainActivity.this} pins the
     * dead Activity, its whole view tree and every app-icon Drawable until the load finishes, and
     * then applies the result to destroyed views — rebuilding a HomescreenView and registering a
     * NotificationDotsService listener on it. {@code AsyncTask.cancel} would not help; nothing
     * interrupts {@code doInBackground}'s package enumeration.
     */
    static final class LoadCallback implements AppLoader.Callback {

        @Nullable private MainActivity activity;
        /** True for the home-screen-only phase, which applies through a different path. */
        private final boolean priority;

        LoadCallback(MainActivity activity, boolean priority) {
            this.activity = activity;
            this.priority = priority;
        }

        /**
         * A full-load callback. The detach/leak semantics these tests exercise are identical for
         * both phases, so they need not care which one they hold.
         */
        LoadCallback(MainActivity activity) {
            this(activity, false);
        }

        /** Releases the Activity so the result is dropped when it arrives. */
        void detach() {
            activity = null;
        }

        /** True while this callback still points at an Activity. Package-private for tests. */
        boolean holdsActivity() {
            return activity != null;
        }

        @Override
        public void onAppsLoaded(List<AppInfo> apps) {
            MainActivity a = activity;
            if (a == null || a.isDestroyed() || a.isFinishing()) return;
            if (priority) a.applyPriorityApps(apps); else a.applyLoadedApps(apps);
        }
    }

    /**
     * Recomputes the displayed app set from the search query.
     *
     * <p>Filtering lives in {@link DrawerQuery}, not in each strategy, so every personality
     * searches identically and a {@code DrawerStrategy} stays a pure ordering/grouping function
     * over an already-filtered list.
     */
    private void applyFilter() {
        apps.clear();
        for (DrawerApp a : DrawerQuery.filter(new ArrayList<DrawerApp>(allApps), query)) {
            apps.add((AppInfo) a);
        }
    }

    /**
     * Rebuilds the content view for the current HOME/drawer state using the persistent-overlay
     * architecture:
     *
     * <ul>
     *   <li>The {@link HomescreenView} always lives at the bottom of {@link #contentContainer}
     *       so it is registered for drag events before any cross-view drag begins.</li>
     *   <li>When a drawer mode is active, a {@link #drawerOverlay} FrameLayout is added on top;
     *       animations target the overlay, not contentContainer.</li>
     * </ul>
     */
    private void renderMode() {
        // --- Ensure the HomescreenView is always present as the background layer ---
        // try/finally because renderMode runs on every mode switch and search keystroke: a
        // leaked slice would corrupt every later trace, not just one.
        Perf.begin(Perf.RENDER_HOME);
        try {
            ensureHomescreenView();
        } finally {
            Perf.end();
        }

        if (isHome) {
            // HOME: remove any drawer overlay, hide toolbar, reveal wallpaper.
            toolbarRow.setVisibility(View.GONE);
            usageBanner.setVisibility(View.GONE);
            // The home screen never needs the spinner — the priority load phase has already
            // given it every icon it references, whatever the drawer is still waiting for.
            progress.setVisibility(View.GONE);
            contentContainer.setBackgroundColor(0x00000000); // transparent — wallpaper shows
            syncHomeAccessibility();
            // The overlay is goHome()'s to remove — either already gone, or still sliding down
            // with its close animation's end action holding the only claim on it. Touching
            // drawerOverlay here is what orphaned it before.
            return;
        }

        // Drawer: show toolbar and rebuild the drawer overlay with current mode content.
        toolbarRow.setVisibility(View.VISIBLE);
        // Unlike the home screen, the drawer genuinely needs every app, so it waits.
        progress.setVisibility(firstLoadDone ? View.GONE : View.VISIBLE);

        boolean searching = !query.isEmpty();
        // The headers toggle only makes sense where the active strategy emits titled sections.
        boolean listMode  = mode == Mode.LIST;
        toggleView.setVisibility(listMode && !searching ? View.VISIBLE : View.GONE);
        updateToggleIcon();
        // Only show mode-switch when there is more than one non-HOME mode enabled.
        modeSwitch.setVisibility(enabledNonHomeModeCount() > 1 ? View.VISIBLE : View.GONE);
        usageBanner.setVisibility(
                mode == Mode.USAGE && !hasUsageAccess && !searching ? View.VISIBLE : View.GONE);

        // Build or rebuild the drawer overlay. Traced: this is the per-keystroke cost while
        // searching, and the cost of any rebuild triggered while the drawer is already open.
        Perf.begin(Perf.RENDER_DRAWER);
        try {
            if (drawerOverlay != null) {
                // Preserve any in-flight color-list overlay reference.
                contentContainer.removeView(drawerOverlay);
            }
            drawerOverlay = new FrameLayout(this);
            drawerOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            drawerOverlay.setBackgroundColor(0xFF121212);
            contentContainer.setBackgroundColor(0x00000000); // underlying HSV shows wallpaper
            contentContainer.addView(drawerOverlay);
            // Announced as a pane so a screen reader says what just took the screen, rather than
            // silently moving focus into a drawer the user was not told about.
            ViewCompat.setAccessibilityPaneTitle(drawerOverlay, "App drawer");
            applyDrawerBottomInset();
            syncHomeAccessibility();

            // Search results are a flat, headerless list whatever the active personality groups
            // by: while typing, "closest match first" is the only ordering the user is looking
            // for, and section headers just push results off screen.
            renderDrawer(searching ? SEARCH_RESULTS : strategyFor(mode), !searching);
            syncNoMatchLabel(searching && apps.isEmpty());
        } finally {
            Perf.end();
        }
    }

    /**
     * Ensures a {@link HomescreenView} exists as the first (bottom) child of
     * {@link #contentContainer}. If one already exists it is reused; if not (first call, or
     * after a full teardown) a new one is created and wired with all listeners.
     *
     * <p>Keeping the same HomescreenView instance alive across drawer open/close cycles is the
     * key to the cross-view drag: Android's {@code startDragAndDrop} broadcasts
     * {@code ACTION_DRAG_STARTED} only to views that are attached and have an
     * {@link android.view.View.OnDragListener} registered at the moment the drag begins.
     * By keeping the HomescreenView alive we guarantee it is always registered.
     *
     * <p>An existing view is refreshed only while {@link #homescreenStale} says it needs to be.
     * Mode switches, the header toggle, drawer open/close and every search keystroke all come
     * through here and none of them change what the home screen shows; the refresh they used to
     * trigger re-hosted every widget on every character typed.
     */
    private void ensureHomescreenView() {
        if (homescreenView != null && homescreenView.getParent() == contentContainer) {
            if (!homescreenStale) return;
            // Apps reloaded or an item was added from here — hand over the map and rebuild pages.
            Map<String, AppInfo> appMap = new HashMap<>();
            for (AppInfo a : allApps) appMap.put(a.getPackageName(), a);
            homescreenView.refresh(appMap);
            homescreenStale = false;
            return;
        }

        // Close any in-flight folder popup on the old view before discarding it.
        if (homescreenView != null) homescreenView.closeFolderPopup();
        // Remove the old view from the container if it's still attached.
        if (homescreenView != null && homescreenView.getParent() != null) {
            contentContainer.removeView(homescreenView);
        }
        homescreenView = null;

        renderHome();
        homescreenStale = false; // built from allApps and the store just now
    }

    private int enabledModeCount() {
        int n = 0;
        for (Mode m : Mode.values()) if (isEnabled(m)) n++;
        return n;
    }

    /**
     * The ordering/grouping to render for {@code m}.
     *
     * <p>{@code LIST} is whatever the fork chose — this is the line that makes the drawer the
     * personality's. {@code USAGE} is pinned to {@link UsageStrategy} because it is not merely an
     * ordering: it owns a permission prompt and the banner above the grid, so it has to stay a
     * mode rather than becoming another thing {@code Personality.DRAWER} might be.
     */
    private DrawerStrategy strategyFor(Mode m) {
        return m == Mode.USAGE ? USAGE_STRATEGY : Personality.DRAWER;
    }

    /** Tag on the "No apps match" label, so the canvas path can find and remove its own. */
    private static final String NO_MATCH_TAG = "no_match";

    /**
     * Shows or removes the "No apps match" label over the drawer. A query with no matches used
     * to leave an empty black area, which reads as the drawer having broken rather than as an
     * answer. The grid path rebuilds the overlay every keystroke so the label is simply added;
     * the canvas path keeps its overlay, so the label is added and removed by tag.
     */
    private void syncNoMatchLabel(boolean show) {
        if (drawerOverlay == null) return;
        View existing = drawerOverlay.findViewWithTag(NO_MATCH_TAG);
        if (!show) {
            if (existing != null) drawerOverlay.removeView(existing);
            return;
        }
        if (existing != null) return;
        TextView label = new TextView(this);
        label.setTag(NO_MATCH_TAG);
        label.setText("No apps match");
        label.setTextColor(0xFF888888);
        label.setTextSize(16f);
        label.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = dp(48);
        drawerOverlay.addView(label, lp);
    }

    /**
     * Puts a clear (✕) at the end of the search box while there is text to clear. A compound
     * drawable rather than a second view: the toolbar row's widths are all weight-and-fixed and
     * an extra ImageView would move the buttons every time the query emptied.
     */
    private void syncSearchClearControl() {
        android.graphics.drawable.Drawable[] d = searchBox.getCompoundDrawablesRelative();
        boolean has = d[2] != null;
        if (has == !query.isEmpty()) return;
        android.graphics.drawable.Drawable clear = null;
        if (!query.isEmpty()) {
            clear = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_close_clear_cancel);
            if (clear != null) {
                clear = clear.mutate();
                clear.setTint(0xFF888888);
            }
        }
        searchBox.setCompoundDrawablesRelativeWithIntrinsicBounds(d[0], d[1], clear, d[3]);
    }


    /**
     * Fills the drawer overlay with {@code strategy}'s sections, under the personality's
     * background.
     *
     * @param withHeaders false to flatten every section into one continuous run of cells; the
     *                    strategy still decides the order, this only suppresses the titles
     */
    private void renderDrawer(DrawerStrategy strategy, boolean withHeaders) {
        if (drawerOverlay == null) return;

        // Background first, so it sits behind the grid. Contract: it must not intercept touches
        // meant for the cells (see Background's javadoc).
        View background = Personality.BACKGROUND.createView(this);
        if (background != null) {
            background.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            drawerOverlay.addView(background);
        }

        AppAdapter adapter = makeAdapter();
        adapter.setShowHeaders(withHeaders && showHeaders);

        RecyclerView grid = new RecyclerView(this);
        grid.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        grid.setClipToPadding(false);
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));

        GridLayoutManager lm = new GridLayoutManager(this, GRID_COLUMNS);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter.isHeader(position) ? GRID_COLUMNS : 1;
            }
        });

        grid.setLayoutManager(lm);
        grid.setAdapter(adapter);
        // `apps` is already filtered by the search box; a strategy only orders and groups.
        adapter.setSections(strategy.organize(new ArrayList<DrawerApp>(apps)));
        drawerOverlay.addView(grid);
    }

    /**
     * Creates (or re-creates) the {@link HomescreenView} and inserts it as the first child of
     * {@link #contentContainer} (beneath any drawer overlay). Wires all listeners including the
     * two cross-view drag listeners added in Issue 2.
     *
     * <p>Called only from {@link #ensureHomescreenView()} — never call directly.
     */
    private void renderHome() {
        // Let wallpaper show through the content area; drawer overlay sits on top.
        contentContainer.setBackgroundColor(0x00000000);

        // Build a map keyed by the composite key (bare for personal, "pkg#serial" for work)
        // so HomescreenView can resolve icons and we can dispatch launches correctly.
        Map<String, AppInfo> appMap = new HashMap<>();
        for (AppInfo a : allApps) appMap.put(a.getPackageName(), a);

        HomescreenView hsv = new HomescreenView(this);
        // Created in code, so it needs an id assigned explicitly — see res/values/ids.xml for
        // why the benchmark suite depends on it being addressable.
        hsv.setId(R.id.homescreen_view);
        hsv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        hsv.setStore(HomeScreenStore.get(this));
        hsv.setAppMap(appMap);
        // Swipe up on the homescreen opens the app drawer.
        hsv.setOnSwipeUpListener(this::openDrawer);
        hsv.setOnAppClickListener(this::launchHomeApp);
        hsv.setOnFolderClickListener(hsv::openFolderPopup);
        hsv.setOnSettingsRequestListener(this::openSettings);
        hsv.setOnWallpaperRequestListener(() ->
                startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SET_WALLPAPER), "Choose wallpaper")));
        hsv.setWidgetHost(widgetHost);
        hsv.setOnAddWidgetListener(new HomescreenView.OnAddWidgetListener() {
            @Override public void onPickWidget(AppWidgetProviderInfo provider) {
                MainActivity.this.onPickWidget(provider, false);
            }
            @Override public void onPickSearchWidget(AppWidgetProviderInfo provider) {
                MainActivity.this.onPickWidget(provider, true);
            }
        });

        // Issue 2 — cross-view drag listeners:
        // When a drawer-app drag starts, dismiss the overlay so the home grid is revealed.
        hsv.setOnDrawerDismissForDragListener(this::dismissDrawerForDrag);
        // When a drawer-app drag ends without movement (user long-pressed but didn't drag),
        // show the deep-shortcut context menu for that app.
        hsv.setOnDrawerDragCancelledListener((packageKey, anchor) -> {
            AppInfo app = null;
            for (AppInfo a : allApps) {
                if (a.getPackageName().equals(packageKey)) { app = a; break; }
            }
            if (app != null) {
                // Re-open the drawer (it was dismissed during the drag attempt), then show menu.
                // Use a view anchor within contentContainer since the drawer item is gone.
                AppAdapter.showDeepShortcutMenu(this, contentContainer, app, this::onAddToHome);
                // Ensure we're back in drawer mode so the user can keep browsing.
                if (isHome) openDrawer();
            }
        });

        hsv.refresh();
        homescreenView = hsv;
        // Insert at position 0 so any existing drawer overlay stays on top.
        contentContainer.addView(hsv, 0);
        // Posted, not called inline: the one-time span repair needs measuredCellSize(), which is
        // only meaningful once the grid has been laid out inside its insets.
        hsv.post(() -> correctOversizedWidgetSpansOnce(HomeScreenStore.get(this)));
    }

    /**
     * Adds {@code app} to the first free 1×1 cell on the home screen (scanning screens 0–8,
     * appending a new screen if the current one is full), then switches to HOME mode.
     * Package-private for tests.
     */
    void onAddToHome(AppInfo app) {
        HomeScreenStore store = HomeScreenStore.get(this);
        int screen = 0;
        int[] cell = null;
        while (screen < 9) {
            cell = store.firstFreeCell(screen, 1, 1);
            if (cell != null) break;
            screen++;
        }
        if (cell == null) return; // all 9 screens full — no action
        store.addItem(HomeItem.app(screen, cell[0], cell[1], app.getPackageName()));
        // Return to the homescreen so the newly-pinned app is immediately visible. The store
        // changed behind the view's back, so the flag is what makes goHome()'s render rebuild.
        homescreenStale = true;
        goHome();
    }

    private void launch(AppInfo app) {
        // The drawer stays behind the launched app, but the keyboard must not: Home from that
        // app arrives through onNewIntent with the IME still owned by the search box.
        hideSearchKeyboard();
        AppAdapter.launchApp(this, app);
    }

    /**
     * A tap on a home-screen icon. Looks the key up in the live app list rather than in a map
     * captured when the view was built: {@link #ensureHomescreenView()} hands a reloaded view a
     * fresh map but the listener is not rebuilt, so a map captured there went stale on the first
     * reload — a work app installed after startup missed the lookup and fell through to a
     * personal launch intent, which for a work-only package is null. Package-private for tests.
     */
    void launchHomeApp(String packageKey) {
        for (AppInfo a : allApps) {
            if (packageKey.equals(a.getPackageName())) {
                AppAdapter.launchApp(this, a);
                return;
            }
        }
        AppAdapter.launchPackage(this, packageKey);
    }

    /** Creates an AppAdapter wired up with the "Add to Home screen" callback. */
    private AppAdapter makeAdapter() {
        AppAdapter a = new AppAdapter(this);
        a.setAddToHomeListener(this::onAddToHome);
        return a;
    }

    private void updateToggleIcon() {
        toggleView.setImageResource(
                showHeaders ? R.drawable.ic_view_list : R.drawable.ic_view_grouped);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // -------------------------------------------------------------------------
    // Test seams. Package-private read-only views of state the drawer/reload tests assert on;
    // nothing in the app calls these.
    // -------------------------------------------------------------------------

    @Nullable FrameLayout drawerOverlayForTests() { return drawerOverlay; }

    boolean isHomeForTests() { return isHome; }

    /** Launches {@code app} the way a tap on it in the drawer does. */
    void launchForTests(AppInfo app) { launch(app); }

    /** Builds the drawer without the open path around it, to test what renderMode alone does. */
    void renderModeForTests() { isHome = false; renderMode(); }

    ReloadCoalescer reloadCoalescerForTests() { return reloadCoalescer; }

    @Nullable LoadCallback loadCallbackForTests() { return loadCallback; }

    @Nullable LoadCallback priorityCallbackForTests() { return priorityCallback; }

    @Nullable HomescreenView homescreenViewForTests() { return homescreenView; }
}
