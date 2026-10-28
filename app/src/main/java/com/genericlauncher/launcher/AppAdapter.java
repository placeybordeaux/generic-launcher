package com.genericlauncher.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.DrawerSection;
import com.genericlauncher.launcher.personality.Personality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RecyclerView adapter that displays the drawer as a grid of app cells under section headers.
 * Items are either HEADER (a {@link DrawerSection} title) or APP (icon + name).
 *
 * <p>The adapter has no opinion about order or grouping: it renders whatever sections the active
 * {@link com.genericlauncher.launcher.personality.DrawerStrategy} produced. Per-cell looks are
 * likewise deferred to {@link Personality#DECORATOR}. See {@code docs/SEAM.md}.
 */
public class AppAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** Callback for the "Add to Home screen" long-press menu entry. */
    public interface AddToHomeListener {
        void onAddToHome(AppInfo app);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_APP = 1;

    private final List<Object> items = new ArrayList<>();
    private final Context context;

    private AddToHomeListener addToHomeListener;

    // Notification-dot change listener registered while the adapter is attached to a RecyclerView.
    private final NotificationDotsService.ChangeListener dotListener =
            this::onNotificationDotsChanged;

    public AppAdapter(Context context) {
        this.context = context;
    }

    // -------------------------------------------------------------------------
    // RecyclerView lifecycle — register / unregister notification-dot listener
    // -------------------------------------------------------------------------

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView rv) {
        super.onAttachedToRecyclerView(rv);
        NotificationDotsService.addListener(dotListener);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView rv) {
        super.onDetachedFromRecyclerView(rv);
        NotificationDotsService.removeListener(dotListener);
    }

    private void onNotificationDotsChanged() {
        // Rebind all visible items so icon drawables are recreated with the current dot state.
        notifyDataSetChanged();
    }

    public void setAddToHomeListener(AddToHomeListener l) { addToHomeListener = l; }

    private List<DrawerSection> currentSections = new ArrayList<>();
    private boolean showHeaders = true;

    /**
     * Displays the sections the active {@link com.genericlauncher.launcher.personality.DrawerStrategy}
     * produced, top to bottom. Sections with an empty title render headerless.
     */
    public void setSections(List<DrawerSection> sections) {
        currentSections = new ArrayList<>(sections);
        rebuildItems();
    }

    /**
     * Whether section headers are drawn. When false the sections are flattened into one
     * continuous run of cells — the strategy still decides the <em>order</em>, this only
     * suppresses the titles.
     */
    public void setShowHeaders(boolean show) {
        if (showHeaders != show) {
            showHeaders = show;
            rebuildItems();
        }
    }

    public boolean getShowHeaders() {
        return showHeaders;
    }

    private void rebuildItems() {
        items.clear();

        for (DrawerSection section : currentSections) {
            // An empty title means "no header" — that is how the flat views (usage, search
            // results) say "one section, unlabelled" without needing a second code path.
            if (showHeaders && !section.title().isEmpty()) {
                items.add(new HeaderItem(section.title()));
            }
            for (DrawerApp app : section.apps()) {
                // Safe by contract, not by luck: a DrawerStrategy may only order and group the
                // apps it was handed, never invent one (DrawerStrategy's javadoc; enforced by
                // SeamCheck and DrawerStrategyContractTest). Every DrawerApp here is therefore
                // one of the AppInfo instances this adapter's caller passed in.
                items.add((AppInfo) app);
            }
        }

        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof HeaderItem ? TYPE_HEADER : TYPE_APP;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_app, parent, false);
            return new AppViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderViewHolder) {
            HeaderItem header = (HeaderItem) items.get(position);
            HeaderViewHolder hvh = (HeaderViewHolder) holder;
            hvh.title.setText(header.name);
        } else {
            AppInfo app = (AppInfo) items.get(position);
            AppViewHolder avh = (AppViewHolder) holder;

            // Notification count badge overlay.
            float density = context.getResources().getDisplayMetrics().density;
            int count = com.genericlauncher.launcher.Settings.getNotifDots(context)
                    ? NotificationDotsService.notificationCount(
                            UserKeys.packageOf(app.getPackageName()))
                    : 0;
            // 0f: badgeOnlyIconPx is only consulted for the badge-only (base == null) overlay;
            // app.getIcon() is never null here, so it's ignored.
            avh.icon.setImageDrawable(new BadgeDrawable(
                    Personality.ICON.treat(context, app.getIcon(), app.getPackageName()),
                    density, count, BadgeConfig.from(context),
                    Personality.BADGE.fillColor(app.key(), count), 0f));
            avh.label.setText(app.getLabel());

            // The cell's own look is the fork's business, not the launcher's. The default
            // decorator changes nothing, which is what "no taste" looks like on screen.
            // Contract reminder: cells are recycled, so a decorator must reset whatever it sets.
            Personality.DECORATOR.decorate(avh.icon, avh.label, app);

            avh.itemView.setOnClickListener(v -> launchApp(context, app));

            avh.itemView.setOnLongClickListener(v -> {
                // Issue 2: start a cross-view drag so the user can drag the app onto the home
                // screen. The DrawerDragState localState marker tells HomescreenView to accept
                // the drag as an incoming-drawer drop. If the user releases without moving,
                // HomescreenView fires onDrawerDragCancelled and MainActivity shows the menu.
                // Null ClipData, deliberately — same reason as HomescreenView.startItemDrag.
                // The drag never leaves the process (flags 0) and nothing reads the clip: the
                // package key travels in the DrawerDragState localState, which is all
                // handleIncomingDrawerDrop consumes. Advertising text/plain instead makes OEM
                // "smart drag" panels (OPPO/OnePlus File Dock) register a system drop target over
                // the top-right of the screen, which swallows DRAG_LOCATION and the drop — the
                // drag then ends having done nothing.
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(v);
                DrawerDragState state = new DrawerDragState(app.getPackageName());
                v.startDragAndDrop(null, shadow, state, 0);
                return true;
            });
        }
    }

    /**
     * Whether the item at this position should span the full grid width (headers do).
     */
    public boolean isHeader(int position) {
        return position >= 0 && position < items.size()
                && items.get(position) instanceof HeaderItem;
    }

    /**
     * Launches {@code app}, using {@link LauncherApps#startMainActivity} for work-profile apps
     * and {@link android.content.pm.PackageManager#getLaunchIntentForPackage} for personal apps.
     * Package-private static so every surface that launches an app — the drawer grid, the
     * wheel's colour list, the Usage field and the home screen — goes through the one guard.
     *
     * <p>Never throws. The app list is a snapshot, and the world moves under it: a work app
     * updated so that its main activity is no longer a launcher activity makes
     * {@code startMainActivity} throw {@code SecurityException} ("Attempt to launch activity
     * without category Intent.CATEGORY_LAUNCHER"), and a personal app disabled between load and
     * tap makes {@code startActivity} throw {@code ActivityNotFoundException}. Both are
     * unchecked, both land on the main thread, and for the HOME app an exception there is no
     * home screen. The tap is reported and the reload the package broadcast schedules sorts out
     * the icon.
     */
    static void launchApp(Context context, AppInfo app) {
        Personality.ICON.onLaunched(context, app.getPackageName());
        try {
            if (app.isWork() && app.getComponentName() != null && app.getUser() != null) {
                LauncherApps la =
                        (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                la.startMainActivity(app.getComponentName(), app.getUser(), null, null);
            } else {
                Intent launch = context.getPackageManager()
                        .getLaunchIntentForPackage(UserKeys.packageOf(app.getPackageName()));
                if (launch != null) context.startActivity(launch);
            }
        } catch (RuntimeException e) {
            reportLaunchFailure(context, app.getLabel(), e);
        }
    }

    /**
     * The personal-profile half of {@link #launchApp} for a home item whose {@link AppInfo} is
     * not loaded — the key alone is all the home screen has for it. Never throws, for the same
     * reasons.
     */
    static void launchPackage(Context context, String packageKey) {
        String pkg = UserKeys.packageOf(packageKey);
        Personality.ICON.onLaunched(context, packageKey);
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) context.startActivity(launch);
        } catch (RuntimeException e) {
            reportLaunchFailure(context, pkg, e);
        }
    }

    private static void reportLaunchFailure(Context context, String label, RuntimeException e) {
        Log.w("AppAdapter", "launch failed for " + label, e);
        Toast.makeText(context, "Couldn't open " + label, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // Phase D — Deep shortcuts
    // -------------------------------------------------------------------------

    /**
     * Fetches up to 4 static + dynamic shortcuts for the given app, rank-sorted ascending.
     * Returns an empty list if the launcher doesn't hold shortcut-host permission or on any error.
     * Package-private so HomescreenView can reuse the same fetch logic.
     */
    static List<ShortcutInfo> fetchShortcuts(LauncherApps la, AppInfo app) {
        if (la == null || !la.hasShortcutHostPermission()) return Collections.emptyList();
        try {
            LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
            q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    | LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC);
            q.setPackage(UserKeys.packageOf(app.getPackageName()));
            UserHandle user = app.getUser() != null ? app.getUser()
                                                     : android.os.Process.myUserHandle();
            List<ShortcutInfo> all = la.getShortcuts(q, user);
            if (all == null || all.isEmpty()) return Collections.emptyList();
            // Sort by rank; cap at 4.
            List<ShortcutInfo> sorted = new ArrayList<>(all);
            Collections.sort(sorted, (a, b) -> Integer.compare(a.getRank(), b.getRank()));
            return sorted.subList(0, Math.min(4, sorted.size()));
        } catch (Exception e) {
            // SecurityException or similar — degrade gracefully.
            return Collections.emptyList();
        }
    }

    /**
     * Shows an anchored popup with up to 4 deep-shortcut rows (icon + label) above the
     * standard long-press actions (App info, Uninstall or Disable, Add to Home screen). Degrades to
     * the plain {@link #showAppMenu} when no shortcuts are available or permission is absent.
     * Package-private static so HomescreenView can call it for homescreen/dock icons.
     */
    static void showDeepShortcutMenu(Context context, android.view.View anchor, AppInfo app,
                                     AddToHomeListener addToHomeListener) {
        LauncherApps la = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        List<ShortcutInfo> shortcuts = fetchShortcuts(la, app);

        if (shortcuts.isEmpty()) {
            showAppMenu(context, anchor, app, addToHomeListener);
            return;
        }

        // Build combined item list: ShortcutInfo rows first, then action-string rows.
        final List<Object> dialogItems = new ArrayList<>(shortcuts);
        dialogItems.add("App info");
        // Uninstall, Disable (system apps) or nothing (work apps) — see AppRemoval.
        String removal = AppRemoval.labelFor(app);
        if (removal != null) dialogItems.add(removal);
        if (addToHomeListener != null) dialogItems.add("Add to Home screen");

        float density = context.getResources().getDisplayMetrics().density;
        final Uri pkgUri = Uri.parse("package:" + UserKeys.packageOf(app.getPackageName()));

        AlertDialog[] dialogRef = { null };

        ListView list = new ListView(context);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setAdapter(new BaseAdapter() {
            private static final int TYPE_SHORTCUT = 0;
            private static final int TYPE_ACTION   = 1;

            @Override public int getCount()        { return dialogItems.size(); }
            @Override public Object getItem(int i) { return dialogItems.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int i) {
                return dialogItems.get(i) instanceof ShortcutInfo ? TYPE_SHORTCUT : TYPE_ACTION;
            }

            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                Object item = dialogItems.get(pos);
                int pad = (int) (12 * density);

                if (item instanceof ShortcutInfo) {
                    ShortcutInfo si = (ShortcutInfo) item;
                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(pad, (int) (8 * density), pad, (int) (8 * density));

                    ImageView icon = new ImageView(context);
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    try {
                        Drawable d = la.getShortcutIconDrawable(si, 0);
                        if (d != null) icon.setImageDrawable(d);
                    } catch (Exception ignored) {}
                    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                            (int) (40 * density), (int) (40 * density));
                    row.addView(icon, iconLp);

                    TextView label = new TextView(context);
                    CharSequence lbl = si.getShortLabel() != null ? si.getShortLabel()
                                                                   : si.getLongLabel();
                    label.setText(lbl != null ? lbl : "");
                    label.setTextColor(0xFFFFFFFF);
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                    LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    labelLp.setMarginStart((int) (12 * density));
                    label.setLayoutParams(labelLp);
                    row.addView(label);
                    return row;
                } else {
                    // Plain text action row.
                    TextView tv = new TextView(context);
                    tv.setText((String) item);
                    tv.setTextColor(0xFFFFFFFF);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                    tv.setPadding(pad, (int) (14 * density), pad, (int) (14 * density));
                    return tv;
                }
            }
        });

        list.setOnItemClickListener((parent, v, pos, id) -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            Object item = dialogItems.get(pos);
            if (item instanceof ShortcutInfo) {
                ShortcutInfo si = (ShortcutInfo) item;
                try { la.startShortcut(si, null, null); } catch (Exception ignored) {}
            } else {
                String action = (String) item;
                if ("App info".equals(action)) {
                    // Work apps: open managed-profile app details via LauncherApps.
                    if (app.isWork()
                            && app.getComponentName() != null
                            && app.getUser() != null) {
                        try {
                            la.startAppDetailsActivity(
                                    app.getComponentName(), app.getUser(), null, null);
                        } catch (Exception ignored) {}
                    } else {
                        Intent i = new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(i);
                    }
                } else if (AppRemoval.isRemovalLabel(action)) {
                    AppRemoval.start(context, app);
                } else if ("Add to Home screen".equals(action)) {
                    if (addToHomeListener != null) addToHomeListener.onAddToHome(app);
                }
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(app.getLabel())
                .setView(list)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    /**
     * Standard launcher long-press actions: open App info, uninstall or disable, or add to home
     * screen.
     * Package-private static so RainbowFieldView's long-press callback can reuse it.
     */
    static void showAppMenu(Context context, android.view.View anchor, AppInfo app,
                            AddToHomeListener addToHomeListener) {
        LauncherApps la = (LauncherApps) context.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.getMenu().add(0, 1, 0, "App info");
        // Uninstall, Disable (system apps) or nothing (work apps) — see AppRemoval.
        String removal = AppRemoval.labelFor(app);
        if (removal != null) menu.getMenu().add(0, 2, 1, removal);
        menu.getMenu().add(0, 3, 2, "Add to Home screen");
        // Always use the bare package name for system intents — composite keys are internal only.
        Uri pkg = Uri.parse("package:" + UserKeys.packageOf(app.getPackageName()));
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                // Work apps: open managed-profile app details via LauncherApps.
                if (app.isWork()
                        && app.getComponentName() != null
                        && app.getUser() != null) {
                    try {
                        la.startAppDetailsActivity(
                                app.getComponentName(), app.getUser(), null, null);
                    } catch (Exception ignored) {}
                } else {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
                return true;
            }
            if (id == 3) {
                if (addToHomeListener != null) addToHomeListener.onAddToHome(app);
                return true;
            }
            // id == 2: Uninstall or Disable (work apps don't see this item)
            AppRemoval.start(context, app);
            return true;
        });
        menu.show();
    }

    // --- View Holders ---

    static class AppViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;

        AppViewHolder(View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.app_icon);
            label = itemView.findViewById(R.id.app_label);
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.header_title);
        }
    }

    // --- Header model ---

    static class HeaderItem {
        final String name;

        HeaderItem(String name) {
            this.name = name;
        }
    }
}
