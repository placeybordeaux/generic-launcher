package com.genericlauncher.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.OverScroller;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.genericlauncher.launcher.personality.Personality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen home-screen view: a horizontally paged strip of {@link CellLayout}s rendered from
 * {@link HomeScreenStore}, with custom touch paging (OverScroller + VelocityTracker) and page
 * indicator dots at the bottom.
 *
 * <p>Phase 3 features: long-press-to-drag items, edge paging during drag, a "✕ Remove" drop zone,
 * folder popup panel, and empty-space long-press context menu.
 */
public class HomescreenView extends FrameLayout {

    private static final String TAG = "HomescreenView";

    /**
     * Debug-build-only trace of the widget-resize touch and geometry path. Read it with
     * {@code ./dev-debug.sh resize} (or {@code watch}, whose filter already carries the tag).
     *
     * <p>It exists because the last resize bug took exactly this — logcat instrumentation on a
     * real device — after three plausible fixes made without it failed in a row. Both of the bugs
     * it eventually found were invisible to the Robolectric suite because the fixture could not
     * express them (provider minimums left at 0; a single widget on an empty page), and neither
     * was guessable from the code.
     *
     * <p>{@code BuildConfig.DEBUG} is a compile-time constant, so javac removes every guarded
     * block from the release build outright — no strings are built and nothing is logged in the
     * binary the store ships.
     */
    private static final boolean RDBG = BuildConfig.DEBUG;
    /**
     * Drop-path trace. Separate tag from {@link #RTAG} so {@code ./dev-debug.sh drop} can show
     * the drag without the resize frame's much noisier per-MOVE geometry.
     *
     * <p>It exists because this path cannot be reproduced off-device end to end: the drag
     * carries a null ClipData on purpose (see {@code startItemDrag}) and Robolectric's
     * window-session shadow refuses to start one. {@code DragToRemoveTest} drives everything
     * after that call, but which events the framework actually delivers, and where, is only
     * observable here.
     */
    private static final boolean DDBG = BuildConfig.DEBUG;
    /** Tag for {@link #DDBG}. Streamed by {@code ./dev-debug.sh drop}. */
    private static final String  DTAG = "DropDbg";
    /** Tag for {@link #RDBG}. Already listed in {@code dev-debug.sh watch}'s logcat filter. */
    private static final String  RTAG = "WidgetDbg";

    // -------------------------------------------------------------------------
    // Listener interfaces
    // -------------------------------------------------------------------------

    /** Called when the user taps an app shortcut. */
    public interface OnAppClickListener {
        void onAppClick(String packageName);
    }

    /** Called when the user taps a folder chip. */
    public interface OnFolderClickListener {
        void onFolderClick(HomeItem folder);
    }

    /** Called when the user requests the settings dialog from the background long-press menu. */
    public interface OnSettingsRequestListener {
        void onSettingsRequested();
    }

    /** Called when the user picks "Change wallpaper" from the background long-press menu. */
    public interface OnWallpaperRequestListener {
        void onWallpaperChangeRequested();
    }

    /** Called when the user selects a widget from the picker. */
    public interface OnAddWidgetListener {
        void onPickWidget(AppWidgetProviderInfo provider);

        /**
         * The "Tap to add Google search" pill's pick, as opposed to the widget picker's. The
         * pill is a one-shot invitation: once its widget is added it must not come back, and
         * the widget should take the row the pill occupied. Only the caller knows the pick came
         * from the pill, so it says so here; the default keeps lambda callers working.
         */
        default void onPickSearchWidget(AppWidgetProviderInfo provider) { onPickWidget(provider); }
    }

    /**
     * Called when the user performs a deliberate upward fling on the homescreen.
     * The listener is invoked from {@link #onInterceptTouchEvent} before the event
     * stream is intercepted, so the caller can safely rebuild the view hierarchy.
     */
    public interface OnSwipeUpListener {
        void onSwipeUp();
    }

    /**
     * Called during a cross-view drag (drawer → homescreen) on {@code ACTION_DRAG_STARTED} so
     * MainActivity can dismiss the drawer overlay and reveal the home grid for dropping.
     * Invoked on the main thread, inside the drag-event callback.
     */
    public interface OnDrawerDismissForDragListener {
        void onDismissDrawerForDrag();
    }

    /**
     * Called when an incoming drawer-app drag ends without any movement (the user long-pressed
     * but did not drag). MainActivity should show the deep-shortcut context menu for the app.
     *
     * @param packageKey The composite package key of the app that was long-pressed.
     * @param anchor     The source view in the drawer (may be null if already detached; callers
     *                   should supply a fallback anchor).
     */
    public interface OnDrawerDragCancelledListener {
        void onDrawerDragCancelled(String packageKey, View anchor);
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    // Package-private so MainActivity can use them without duplicating the values.
    static final int DOT_AREA_DP    = 32;  // height reserved at bottom for dots
    static final int DOCK_HEIGHT_DP = 64;  // dock row height (icon 52dp + 6dp top/bottom padding)

    private static final int DOCK_MARGIN_DP  = 12;  // horizontal inset for the dock pill
    private static final int DOT_RADIUS_DP   =  3;
    private static final int DOT_GAP_DP      =  7;
    // Drag action bar. Deliberately short and close to the status bar: it sits on top of grid
    // row 0, so every dp it occupies is a dp of row 0 the user cannot drop into mid-drag.
    private static final int ACTION_TOP_DP   =  8;  // gap between the status bar and the action bar
    private static final int ACTION_H_DP     = 36;  // action-pill height
    private static final int ACTION_PILL_DP  = 96;  // action-pill width
    private static final int ACTION_GAP_DP   =  8;  // gap between adjacent action pills
    private static final int ACTION_GROW_DP  =  5;  // how far a pill swells while hovered

    // Folder popup. Width/height are capped but otherwise derived from the display, so the
    // member grid always has room for all FOLDER_COLS columns (see openFolderPopup).
    private static final int FOLDER_CARD_MAX_W_DP  = 360;
    private static final int FOLDER_CARD_MAX_H_DP  = 480;
    private static final int FOLDER_CARD_MARGIN_DP =  24;
    private static final int FOLDER_CARD_PAD_DP    =  16;
    private static final int FOLDER_COLS           =   4;
    private static final int FOLDER_CELL_H_DP      =  80;
    private static final int SNAP_MS         = 280;  // page-snap animation duration ms
    /**
     * Extra ms per page beyond the first when {@link #animateToPage} crosses several at once, and
     * the ceiling on the total. One SNAP_MS for an eight-page trip would be a blur that tells the
     * user nothing; eight SNAP_MS would be a two-second wait they cannot cancel.
     */
    private static final int PAGE_FLIP_MS    = 200;
    private static final int MAX_FLIP_MS     = 900;
    private static final int EDGE_DP         = 40;   // drag edge-zone width in dp
    private static final long EDGE_DELAY_MS  = 600;  // ms of hovering before edge flip
    private static final int MAX_PAGES       = 9;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private HomeScreenStore store;
    private Map<String, AppInfo> appMap = new HashMap<>();
    private OnAppClickListener                appClickListener;
    private OnFolderClickListener             folderClickListener;
    private OnSettingsRequestListener         settingsRequestListener;
    private OnWallpaperRequestListener        wallpaperRequestListener;
    private OnAddWidgetListener               addWidgetListener;
    private OnSwipeUpListener                 swipeUpListener;
    private OnDrawerDismissForDragListener    drawerDismissForDragListener;
    private OnDrawerDragCancelledListener     drawerDragCancelledListener;
    private AppWidgetHost                     host;

    // Cross-view drag of an app that is not (yet) a home-screen item. Two sources feed it: the
    // drawer (localState is a DrawerDragState) and a folder member dragged out of the open folder
    // popup (localState is a FolderDragState — see folderPullOutListener). In both cases
    // draggedItem is a synthetic span-1 placeholder that is NOT in the store, and dragSourceView
    // is null because the source view is torn down mid-drag.
    private String  incomingAppPkg = null;   // composite package key (UserKeys scheme)
    private boolean isIncomingApp  = false;  // true during an incoming-app drag

    // Folder the incoming app is being pulled out of, or null when it came from the drawer. Its
    // one behavioural difference: a committed drop is a *move*, so the member is removed from
    // this folder afterwards — see commitIncomingDrop.
    private HomeItem pullOutFolder = null;
    // The member drag the popup started, held from ACTION_DRAG_STARTED until ACTION_DRAG_ENDED so
    // this view can tell "the popup handled a reorder" from "nothing happened" and leave the
    // popup alone in the first case.
    private FolderDragState activeFolderDrag = null;

    /**
     * Context used to inflate {@link AppWidgetHostView} RemoteViews. Deliberately the
     * <em>application</em> context, NOT the AppCompat Activity context: AppCompat installs an
     * {@code AppCompatViewInflater} factory on the Activity's LayoutInflater that rewrites
     * {@code <ImageView>} to {@code androidx.appcompat.widget.AppCompatImageView}. RemoteViews'
     * {@code setImageBitmap} is a {@code @RemotableViewMethod} only on base
     * {@code android.widget.ImageView}, so applying an image-bearing widget's RemoteViews against
     * an AppCompat-inflated view throws {@code RemoteViews$ActionException} and the host shows its
     * "Couldn't add widget." error layout. The application context has no such factory.
     */
    private final Context widgetContext;

    private final List<CellLayout> pageViews = new ArrayList<>();
    private int currentPage = 0;

    // System-bar insets. The app targets SDK 35, where the framework forces edge-to-edge and the
    // window spans the full display — without these the top grid row sits under the status bar and
    // the page dots sit under the gesture pill. Updated from onApplyWindowInsets; every piece of
    // vertical geometry in this view (pages, dock, dots, action bar, drop resolution, resize frame)
    // is measured from insetTop and back from insetBottom.
    private int insetTop    = 0;
    private int insetBottom = 0;

    // Dock (Phase B) — fixed row pinned below the pager, above the page dots.
    private LinearLayout dockView = null;

    // Paging
    private final OverScroller  scroller;
    private VelocityTracker     velocityTracker;
    private float               interceptDownX, interceptDownY;
    private float               touchDownX;
    // Gesture anchor captured once on ACTION_DOWN in onTouchEvent. Distinct from touchDownX, which
    // is overwritten every MOVE for incremental paging — these keep the total displacement from the
    // gesture start so the vertical up-swipe threshold can be tested when the swipe begins over
    // empty wallpaper (no child consumed DOWN, so onInterceptTouchEvent's MOVE branch never runs).
    private float               gestureStartX, gestureStartY;
    private boolean             swipeUpFired;  // guards onSwipeUp to once per gesture
    // True while a finger owns the horizontal scroll offset. onLayout must not snap the strip
    // back to currentPage while it is set, or a descendant's requestLayout (a self-updating
    // widget) would yank the pages out from under the finger — see shouldSnapScrollOnLayout.
    private boolean             touchPaging;
    // Guards cancelPendingWidgetLongPress() to once per gesture — see that method for why the
    // cancellation has to be driven from here rather than from WidgetWrapper itself.
    private boolean             widgetLongPressCancelled;
    // True when this gesture's ACTION_DOWN landed inside a WidgetWrapper. The widget owns the
    // long-press over its own footprint (there it means "pick me up"), so the background menu has
    // to stay out of it — see widgetWrapperAt() and the gesture detector's onLongPress.
    private boolean             downOnWidget;
    // Set when the detector's long-press has opened the background menu: the remaining MOVE/UP
    // of that gesture must neither open the drawer nor page. Per gesture; cleared on DOWN. The
    // third owner of a home-screen long-press, next to downOnWidget and
    // cancelPendingWidgetLongPress().
    private boolean             backgroundMenuOwnsGesture;
    private final int           touchSlop;
    private final int           minFlingVelocity;
    private final int           maxFlingVelocity;

    // Dot indicator
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Drag state
    private HomeItem draggedItem    = null;
    private View     dragSourceView = null;
    private boolean  isDragging     = false;
    private int      pendingEdgeDir = 0;  // -1=left, +1=right, 0=none
    private final Runnable edgePageTask = this::flipEdgePage;
    private final Paint removePillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint removeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Action-bar zones for the drag in flight, resolved once when the drag starts. Null when no
    // drag is active. Sharing one array between the painter and the drop resolver is what keeps a
    // pill from ever being drawn somewhere the resolver would reject.
    private DropTarget.Zone[] dragActions = null;

    // Drop-target preview — pre-allocated to avoid per-frame allocations.
    private DropTarget previewTarget  = null;  // current resolved target (null = no preview)
    private boolean    previewIsValid = false; // true when previewTarget should be drawn
    private final Paint previewPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF previewRect   = new RectF();

    // Folder popup
    private FrameLayout folderPopupOverlay = null;
    private HomeItem    openFolder         = null;
    private EditText    folderNameEdit     = null;
    // Set when a member reorder changes the stored order. The folder's page chip previews its
    // first four members, so the pages need rebuilding — deferred to popup close so the rebuild
    // never runs while the popup (a child of this view) is still on screen.
    private boolean     folderOrderChanged = false;
    // A rename already written by Enter (commitFolderName) while the popup stayed open.
    private boolean     folderNameCommitted = false;

    // Resize frame (Phase C)
    private HomeItem   resizingItem       = null;
    private View       resizeFrameOverlay = null;
    // Gesture ownership for the open resize frame, decided once on ACTION_DOWN. A handle drag is
    // predominantly horizontal and one grid cell (270 px at 1080/4) is an order of magnitude past
    // touchSlop, so onInterceptTouchEvent's page-swipe test matches it every time — which dismissed
    // the frame and stole the stream before a single cell of delta could accumulate, and is why
    // resize "did nothing". When the DOWN lands on the frame the gesture belongs to the frame; the
    // pager must keep its hands off it until the next DOWN. Same question H9 answered for the drag
    // path, answered the same way: decide the owner at DOWN, not from the movement.
    //
    // "On the frame" is the handle *or* anywhere inside the outline: a drag started inside the
    // box repositions the widget (see ResizeFrameView's GESTURE_MOVE). Both are drags of the
    // widget, differing only in which of span and origin they change, so both have to survive the
    // pager for the same reason.
    private boolean    resizeFrameOwnsGesture = false;
    private HomeItem   pendingResizeItem  = null;  // set before endDrag() to show frame after refresh
    private boolean    dragMoved          = false;  // true if drag moved past touchSlop
    // Which cell of the dragged item the finger picked it up in (see captureGrabCell). The
    // item's origin is committed this many cells before the pointer's cell, so a multi-cell
    // widget lands where it was let go rather than with its top-left under the finger.
    private int        grabCellX, grabCellY;
    // True once at least one ACTION_DRAG_LOCATION has been delivered. Without this flag a
    // system-cancelled drag (zero DRAG_LOCATION events) is indistinguishable from a deliberate
    // long-press-release, which would wrongly trigger the shortcut popup or resize frame.
    private boolean    sawDragLocation    = false;
    // Position at ACTION_DRAG_STARTED, in SCREEN space (see toScreenX/Y). DragEvent coordinates
    // are view-local, and a drawer drag moves this view between DRAG_STARTED and the first
    // DRAG_LOCATION: dismissing the drawer hides the toolbar row above it, so the same stationary
    // finger reads ~220px higher in local space and a plain long-press counted as a drag.
    private float      dragStartX, dragStartY;
    private final int[] screenOrigin = new int[2];  // scratch for toScreenX/Y

    // Phase D — deep shortcuts: set when a long-press ends without movement on an APP item.
    // The shortcut popup is shown after the subsequent refresh() call in endDrag().
    //
    // Scheme: long-press always starts a system drag (keeps drag responsive). If the user
    // releases without moving past touchSlop, dragMoved stays false and DRAG_ENDED fires with
    // result=false. We detect that case here and show the shortcut popup post-refresh.
    private HomeItem   pendingShortcutItem = null;

    // Phase D — notification dots listener; registered on attach, unregistered on detach.
    private final NotificationDotsService.ChangeListener dotChangeListener =
            this::onNotificationDotsChanged;

    // Background long-press
    private final GestureDetector gestureDetector;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public HomescreenView(Context context) { this(context, null); }

    public HomescreenView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Use the application context for widget RemoteViews inflation so AppCompat's inflater
        // factory (which swaps ImageView -> AppCompatImageView and breaks RemoteViews.setImageBitmap)
        // is never applied to hostviews. See the widgetContext field doc.
        widgetContext = context.getApplicationContext();
        setClipChildren(true);
        setWillNotDraw(false);

        scroller = new OverScroller(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop        = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();

        removeTextPaint.setTextAlign(Paint.Align.CENTER);
        removeTextPaint.setFakeBoldText(true);

        previewPaint.setStyle(Paint.Style.STROKE);
        previewPaint.setColor(0x80BB86FC); // accent #BB86FC at ~50% alpha

        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(MotionEvent e) {
                        // downOnWidget is the same "who owns this gesture" question the H9 fix
                        // answered for the drag path. This detector is fed from onTouchEvent, which
                        // a non-interactive widget's whole touch stream falls through to (nothing
                        // under the wrapper consumes ACTION_DOWN), so without the check a press
                        // anywhere on a clock or a search bar opened the wallpaper menu on top of
                        // the widget's own long-press-to-drag.
                        if (downOnWidget) return;
                        if (!isDragging && folderPopupOverlay == null) {
                            showBackgroundMenu(e.getX(), e.getY());
                            // The rest of this touch stream belongs to the menu. The finger is
                            // still down, and onTouchEvent keeps receiving its MOVE/UP: without
                            // this an upward slide after the menu opened still read as the
                            // open-drawer swipe, and the user closed the menu to find the drawer
                            // open underneath it.
                            backgroundMenuOwnsGesture = true;
                        }
                    }
                });

        setOnDragListener(homeDragListener);
        setFitsSystemWindows(false); // insets are applied to our own geometry, not as padding
    }

    // -------------------------------------------------------------------------
    // Window insets
    // -------------------------------------------------------------------------

    /**
     * Captures the system-bar insets so the grid starts below the status bar and the page dots
     * clear the gesture pill. Returns the insets unconsumed so sibling views still see them.
     */
    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int top, bottom;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            top    = bars.top;
            bottom = bars.bottom;
        } else {
            top    = insets.getSystemWindowInsetTop();
            bottom = insets.getSystemWindowInsetBottom();
        }
        if (top != insetTop || bottom != insetBottom) {
            insetTop    = top;
            insetBottom = bottom;
            requestLayout();
            invalidate();
        }
        return insets;
    }

    /** Height of the paged grid area: the view minus the system bars, dock and dot strip. */
    private int pageHeight(int viewHeight) {
        return Math.max(0,
                viewHeight - insetTop - insetBottom - dp(DOCK_HEIGHT_DP) - dp(DOT_AREA_DP));
    }

    /**
     * Measured size of one grid cell in px, or {@code null} before this view has been laid out.
     * Exposed so callers needing cell geometry (widget span estimation) don't have to re-derive
     * the inset/dock/dots arithmetic and drift out of sync with it.
     *
     * @return {@code int[]{cellWidthPx, cellHeightPx}} or null
     */
    int[] measuredCellSize() {
        if (store == null || getWidth() <= 0 || getHeight() <= 0) return null;
        int cols = store.columns();
        int rows = store.rows();
        if (cols <= 0 || rows <= 0) return null;
        return new int[]{ CellLayout.cellWidth(getWidth(), cols, CellLayout.pagePadPx(getContext())),
                          pageHeight(getHeight()) / rows };
    }

    // -------------------------------------------------------------------------
    // Phase D — Notification-dot listener lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationDotsService.addListener(dotChangeListener);
        // This view is created programmatically well after the window's first inset dispatch, so
        // without an explicit request onApplyWindowInsets would not fire until something else
        // (a rotation, the IME) triggered one — leaving the grid under the status bar until then.
        requestApplyInsets();
    }

    /**
     * Drops every piece of state that would otherwise outlive this view. The listener
     * unregistration is the obvious one; the rest are all things that keep a destroyed Activity
     * reachable or fire against a dead view:
     *
     * <ul>
     *   <li>{@code edgePageTask} is posted with {@link #postDelayed}, which queues on the
     *       AttachInfo handler — detaching does not remove it, so without this it still runs
     *       {@code addView}/{@code requestLayout} on a detached view.</li>
     *   <li>{@code velocityTracker} is only recycled on ACTION_UP/CANCEL, and the terminal event
     *       may never arrive (onSwipeUp rebuilds the hierarchy mid-gesture), leaking it into the
     *       native pool.</li>
     *   <li>{@code scroller} otherwise keeps animating with {@code computeScroll} postInvalidating.</li>
     *   <li>The drag fields are what {@code flipEdgePage} and {@code handleDrop} guard on; leaving
     *       {@code isDragging} true means a stray callback sails past its only guard.</li>
     * </ul>
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationDotsService.removeListener(dotChangeListener);
        removeCallbacks(edgePageTask);
        pendingEdgeDir = 0;
        scroller.abortAnimation();
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        isDragging               = false;
        isIncomingApp            = false;
        incomingAppPkg           = null;
        pullOutFolder            = null;
        activeFolderDrag         = null;
        draggedItem              = null;
        dragSourceView           = null;
        dragActions              = null;
        touchPaging              = false;
        widgetLongPressCancelled = false;
        downOnWidget             = false;
        clearDropPreview();
    }

    /**
     * Called (on the main thread) when the set of notified packages changes.
     *
     * <p>Deliberately NOT a {@link #refresh()}: that tears the whole view tree down and re-runs
     * {@code host.createView} for every widget, re-inflating each provider's RemoteViews. A
     * messaging app re-posts the same notification key on every message, so ordinary traffic made
     * the widgets visibly flicker. The badge lives in a {@link BadgeDrawable} whose count is
     * mutable, so updating the counts in place is all that is actually needed.
     */
    private void onNotificationDotsChanged() {
        if (!Settings.getNotifDots(getContext())) return; // dots disabled — nothing to update
        post(this::updateBadgeCounts);
    }

    /**
     * Pushes the current notification counts into the badge drawables built for the live view
     * tree. No layout pass, no rebuild — so this is safe to run during a drag, with the folder
     * popup open, or mid-resize, all of which the old full-refresh path had to skip.
     *
     * <p>"The live view tree" includes an open folder popup's member cells: a notification that
     * arrives while a folder is open updates the member icon in place, since nothing else will —
     * {@link #refresh()} closes the popup rather than rebuilding it.
     *
     * <p>Package-private so tests can drive it without a live notification listener.
     */
    void updateBadgeCounts() {
        if (!Settings.getNotifDots(getContext())) return;
        pushBadgeCounts(badgeBindings);
        pushBadgeCounts(popupBadgeBindings);
    }

    private void pushBadgeCounts(List<BadgeBinding> bindings) {
        for (BadgeBinding binding : bindings) {
            binding.drawable.setCount(binding.memberPkg != null
                    ? badgeCountFor(binding.memberPkg)
                    : badgeCountForItem(binding.item));
        }
    }

    /**
     * One badge drawable and what its notification count is read from. Recorded at build time
     * so {@link #updateBadgeCounts} does not have to re-walk (and re-interpret) the view tree.
     *
     * <p>{@code memberPkg} is null for everything on the pages and the dock, where the item alone
     * says what to count. It is non-null only for a member cell inside an open folder popup:
     * there {@code item} is the <em>folder</em>, so {@link #badgeCountForItem} would hand every
     * member icon the folder's summed count — a folder holding a 3-notification app and a
     * 5-notification one would show 8 on both. The member's own package is the count.
     */
    private static final class BadgeBinding {
        final HomeItem      item;
        /** Folder popup member cell only; null everywhere else. See the class doc. */
        final String        memberPkg;
        final BadgeDrawable drawable;

        BadgeBinding(HomeItem item, BadgeDrawable drawable) {
            this(item, null, drawable);
        }

        BadgeBinding(HomeItem item, String memberPkg, BadgeDrawable drawable) {
            this.item      = item;
            this.memberPkg = memberPkg;
            this.drawable  = drawable;
        }
    }

    /** Live bindings for the current view tree; rebuilt by {@link #refresh()}. */
    private final List<BadgeBinding> badgeBindings = new ArrayList<>();

    /**
     * Live bindings for the open folder popup's member cells, kept apart from
     * {@link #badgeBindings} because they have a different lifetime: the grid is rebuilt in place
     * by {@link #refreshFolderPopupGrid} (a reorder, a member removal) without any
     * {@link #refresh()}, so folding them into the main list would leave one dead drawable per
     * member per rebuild in it. Cleared by {@link #buildMemberGrid}, which every build path goes
     * through, and again when the popup closes.
     */
    private final List<BadgeBinding> popupBadgeBindings = new ArrayList<>();

    /** How many popup member badges are currently bound. Test-visibility only. */
    int popupBadgeBindingCountForTest() { return popupBadgeBindings.size(); }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public void setStore(HomeScreenStore s)  { store = s; }

    public void setAppMap(Map<String, AppInfo> map) {
        this.appMap = map != null ? map : new HashMap<>();
    }

    public void setOnAppClickListener(OnAppClickListener l)         { appClickListener         = l; }
    public void setOnFolderClickListener(OnFolderClickListener l)   { folderClickListener      = l; }
    public void setOnSettingsRequestListener(OnSettingsRequestListener l)   { settingsRequestListener  = l; }
    public void setOnWallpaperRequestListener(OnWallpaperRequestListener l) { wallpaperRequestListener = l; }
    public void setOnAddWidgetListener(OnAddWidgetListener l)               { addWidgetListener        = l; }
    public void setOnSwipeUpListener(OnSwipeUpListener l)                            { swipeUpListener              = l; }
    public void setOnDrawerDismissForDragListener(OnDrawerDismissForDragListener l) { drawerDismissForDragListener  = l; }
    public void setOnDrawerDragCancelledListener(OnDrawerDragCancelledListener l)  { drawerDragCancelledListener   = l; }
    public void setWidgetHost(AppWidgetHost h)                                      { host = h; }

    /** Current page index. */
    public int getCurrentPage() { return currentPage; }

    /**
     * Animates the strip to {@code page}, flipping through every page in between so the user can
     * see where they were taken. Used when something lands on a page other than the one on screen
     * — a widget added to a full page spills onto the next one, and a widget added before an
     * activity recreation is placed while the rebuilt view is back on page 0. Silently landing it
     * off-screen is indistinguishable from not adding it at all.
     *
     * <p>No-ops when the page is already showing, so callers may call it unconditionally.
     */
    public void animateToPage(int page) {
        if (page < 0 || page >= MAX_PAGES) return;
        if (page >= pageViews.size()) {
            // The strip has not been rebuilt this far yet. refresh() will pick this up.
            pendingPageReveal = page;
            return;
        }
        int w = getWidth();
        if (page == currentPage && w > 0 && getScrollX() == page * w) return;

        int distance = Math.abs(page - currentPage);
        currentPage = page;
        if (w == 0) {
            // Never laid out — there is no motion to show anyway, and onLayout snaps to
            // currentPage on its own once it runs.
            requestLayout();
            return;
        }
        int duration = Math.min(MAX_FLIP_MS, SNAP_MS + Math.max(0, distance - 1) * PAGE_FLIP_MS);
        scroller.startScroll(getScrollX(), 0, page * w - getScrollX(), 0, duration);
        postInvalidate();
    }

    /** Returns the CellLayout for the given screen index. */
    public CellLayout getPage(int index) {
        return (index >= 0 && index < pageViews.size()) ? pageViews.get(index) : null;
    }

    /** True if a folder popup is open. */
    public boolean isFolderPopupOpen() { return folderPopupOverlay != null; }

    /**
     * Closes the folder popup, committing any name edit first.
     *
     * <p>A committed rename rebuilds the pages. {@link #buildFolderView} reads
     * {@code getFolderName()} once, when the chip is built, so mutating the model alone leaves
     * the label under the popup showing the old name until some unrelated event happens to
     * rebuild — which is exactly what "the name doesn't change until you restart" looked like.
     * The rebuild is skipped when the text came back unchanged, so merely opening and dismissing
     * a folder still costs nothing.
     */
    public void closeFolderPopup() {
        if (folderPopupOverlay == null) return;
        boolean renamed = saveFolderName();
        removeView(folderPopupOverlay);
        folderPopupOverlay = null;
        openFolder         = null;
        folderNameEdit     = null;
        popupBadgeBindings.clear(); // drawables belong to the member cells just removed
        if (folderOrderChanged || renamed || folderNameCommitted) {
            folderOrderChanged   = false;
            folderNameCommitted  = false;
            // Posted, not called inline: refresh() itself calls closeFolderPopup(), so a direct
            // call here would recurse.
            post(this::refresh);
        }
    }

    /**
     * True when a {@link #refresh()} must be held back rather than run now.
     *
     * <p>A refresh does {@code removeAllViews()} and rebuilds, but the drag state
     * ({@code isDragging}, {@code draggedItem}, {@code dragSourceView}) and the resize frame's
     * captured wrapper all survive it — so a reload landing mid-gesture leaves the gesture
     * pointing at views that are no longer in the tree. The reload is not lost: it is replayed
     * from {@link #endDrag()} / {@link #dismissResizeFrame()}, mirroring the existing
     * {@code pendingResizeItem} / {@code pendingShortcutItem} hand-off.
     *
     * <p>The folder popup is deliberately not on this list: {@code refresh()} closing it is the
     * intended behaviour of several flows (removing the last folder member, for one).
     */
    static boolean shouldDeferRefresh(boolean dragging, boolean resizeFrameOpen) {
        return dragging || resizeFrameOpen;
    }

    /** Set when a {@link #refresh()} arrived mid-gesture; replayed once the gesture finishes. */
    private boolean pendingRefresh = false;

    /**
     * Page {@link #animateToPage} was asked for before the strip had been rebuilt that far, or -1.
     * Applied at the end of {@link #refresh()}. A caller that adds an item and then asks to be
     * shown it cannot know whether the rebuild has already happened — {@code goHome()} rebuilds
     * synchronously when the drawer is closed but 180 ms later when it has to animate one shut.
     */
    private int pendingPageReveal = -1;

    /**
     * How many times {@link #refresh()} has been asked for, deferred or not. Test-visibility
     * only: a refresh re-hosts every widget, so "how often was it called" is the number a
     * performance regression shows up in.
     */
    private int refreshCount = 0;

    int refreshCountForTest() { return refreshCount; }

    /** Rebuilds all pages and the dock from the store using the current app map. */
    public void refresh() {
        refreshCount++;
        if (shouldDeferRefresh(isDragging, resizeFrameOverlay != null)) {
            pendingRefresh = true;
            return;
        }
        pendingRefresh = false;
        // Close popup before rebuild so no stale references remain.
        if (folderPopupOverlay != null) closeFolderPopup();
        // Dismiss resize frame before removing views so updateAppWidgetSize can still find the wrapper.
        if (resizeFrameOverlay != null) dismissResizeFrame();
        removeAllViews();
        pageViews.clear();
        badgeBindings.clear(); // drawables belong to the views just removed
        dockView = null; // cleared by removeAllViews
        if (store == null) return;

        int numScreens = store.screenCount();
        for (int s = 0; s < numScreens; s++) {
            CellLayout page = new CellLayout(getContext());
            page.setGridDimensions(store.columns(), store.rows());
            populatePage(page, s);
            addView(page);
            pageViews.add(page);
        }
        buildDock(); // dock is added after pages so it draws on top
        currentPage = Math.min(currentPage, Math.max(0, numScreens - 1));
        requestLayout();
        if (pendingPageReveal >= 0) {
            int target = pendingPageReveal;
            pendingPageReveal = -1;
            animateToPage(target);
        }
        // Apply widget size options after the layout pass so cell dimensions are known.
        // This populates OPTION_APPWIDGET_SIZES (required by Jetpack Glance) on every
        // AppWidgetHostView, both at first creation and after any drop/move refresh.
        post(this::applyAllWidgetSizes);
    }

    /**
     * Iterates all home-screen pages, finds every {@link AppWidgetHostView}, and
     * notifies it of its current dp size via {@link WidgetSizeHelper#applySize}.
     *
     * <p>Invoked via {@code post()} from {@link #refresh()} so the layout pass has
     * already completed and cell dimensions are valid.  Skips any page whose cell
     * size is still 0 (e.g. HomescreenView not yet measured).
     */
    private void applyAllWidgetSizes() {
        if (store == null) return;
        int cols    = store.columns();
        int rows    = store.rows();
        float density = getResources().getDisplayMetrics().density;
        for (CellLayout page : pageViews) {
            if (page.getWidth() == 0 || page.getHeight() == 0) continue;
            int cellW = CellLayout.cellWidth(page.getWidth(), cols, CellLayout.pagePadPx(getContext()));
            int cellH = page.getHeight() / rows;
            for (int i = 0; i < page.getChildCount(); i++) {
                View child = page.getChildAt(i);
                // Widget wrappers are FrameLayouts; app/folder views are LinearLayouts.
                if (!(child instanceof FrameLayout)) continue;
                FrameLayout wrapper = (FrameLayout) child;
                if (wrapper.getChildCount() == 0) continue;
                View inner = wrapper.getChildAt(0);
                if (!(inner instanceof AppWidgetHostView)) continue;
                CellLayout.LayoutParams lp = (CellLayout.LayoutParams) wrapper.getLayoutParams();
                AppWidgetHostView hostView = (AppWidgetHostView) inner;
                WidgetSizeHelper.applySize(hostView, lp.spanX, lp.spanY, cellW, cellH, density);
                // Refine the full options bundle with the REAL measured cell size now that layout
                // has completed, replacing the display-metrics estimate used at populate time.
                WidgetSizeHelper.applyFullOptions(getContext(), hostView.getAppWidgetId(),
                        lp.spanX, lp.spanY, cellW, cellH, density);
            }
        }
    }

    /** Updates the app map then calls {@link #refresh()}. */
    public void refresh(Map<String, AppInfo> newAppMap) {
        setAppMap(newAppMap);
        refresh();
    }

    // -------------------------------------------------------------------------
    // Page population
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // First-run search-widget placeholder (spec: 2026-07-20 default home screen)
    // -------------------------------------------------------------------------

    /** Package of the Google app, whose search widget the placeholder offers to add. */
    static final String GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox";

    /**
     * Pure decision: the placeholder shows only on a seeded layout the user hasn't
     * dismissed it from, while page 0 still has a whole row free for it and the Google search
     * widget is actually installable.
     *
     * <p>{@code rowAvailable} used to be "the <em>top</em> row is free", which made the offer
     * come and go with what happened to be at (0,0) — and "Add to Home screen" from the drawer's
     * menu puts an app at exactly (0,0) on a fresh layout, so the first thing a new user is
     * likely to do silently retracted the offer, which then reappeared if they later moved that
     * app away (QA R5). The pill is a one-shot offer: it stays until the user takes it or
     * dismisses it, and moves down the page to make room.
     */
    static boolean shouldShowSearchPlaceholder(
            boolean seeded, boolean dismissed, boolean rowAvailable, boolean providerPresent) {
        return seeded && !dismissed && rowAvailable && providerPresent;
    }

    /**
     * Search-bar widget classes inside {@link #GOOGLE_SEARCH_PACKAGE}, best first. The Google
     * app publishes several widgets from that one package and the platform hands them back in
     * an order we don't control — on a Pixel 9a "At a Glance" (Smartspace) comes first — so the
     * provider has to be chosen by class name, never by position.
     */
    private static final String[] SEARCH_WIDGET_CLASSES = {
        // The long-standing search pill every launcher can bind.
        "com.google.android.googlequicksearchbox.SearchWidgetProvider",
        // Pixel Launcher's search bar, and its themed variant.
        "com.google.android.apps.gsa.staticplugins.searchwidget.GoogleSearchWidgetProvider",
        "com.google.android.apps.gsa.staticplugins.searchwidget.PremiumSearchWidgetProvider",
    };

    /** Package sub-path shared by the Google app's search-bar widget providers. */
    private static final String SEARCH_WIDGET_PACKAGE_FRAGMENT = ".searchwidget.";

    /**
     * The Google app's search-bar widget provider, or null when none is installed.
     *
     * <p>Known classes win in {@link #SEARCH_WIDGET_CLASSES} order; a search widget we don't
     * know by name is still accepted on its {@code .searchwidget.} package. Anything else the
     * Google app ships — At a Glance, Stocks — is rejected outright, so the placeholder simply
     * doesn't appear rather than offering to add the wrong widget.
     */
    static AppWidgetProviderInfo findGoogleSearchProvider(List<AppWidgetProviderInfo> providers) {
        if (providers == null) return null;
        AppWidgetProviderInfo best = null;
        int bestRank = Integer.MAX_VALUE;
        for (AppWidgetProviderInfo info : providers) {
            if (info == null || info.provider == null) continue;
            if (!GOOGLE_SEARCH_PACKAGE.equals(info.provider.getPackageName())) continue;
            int rank = searchWidgetRank(info.provider.getClassName());
            if (rank < bestRank) {
                best = info;
                bestRank = rank;
                if (rank == 0) break; // can't do better than the top preference
            }
        }
        return best;
    }

    /**
     * Rank of a Google-app widget class as "the search bar": lower is better,
     * {@link Integer#MAX_VALUE} for widgets that aren't a search bar at all.
     */
    private static int searchWidgetRank(String className) {
        if (className == null) return Integer.MAX_VALUE;
        for (int i = 0; i < SEARCH_WIDGET_CLASSES.length; i++) {
            if (SEARCH_WIDGET_CLASSES[i].equals(className)) return i;
        }
        // Unknown class: accept it only if it lives in the search-widget package.
        if (className.contains(SEARCH_WIDGET_PACKAGE_FRAGMENT)) {
            return SEARCH_WIDGET_CLASSES.length;
        }
        return Integer.MAX_VALUE;
    }

    private void populatePage(CellLayout page, int screen) {
        List<HomeItem> staleWidgets = null;
        for (HomeItem item : store.getItems()) {
            if (item.getScreen() != screen) continue;
            switch (item.getType()) {
                case APP: {
                    AppInfo info = appMap.get(item.getPackageName());
                    if (info == null) continue; // package not installed — skip
                    page.addView(buildAppView(item, info), new CellLayout.LayoutParams(
                            item.getCellX(), item.getCellY(), item.getSpanX(), item.getSpanY()));
                    break;
                }
                case FOLDER: {
                    // Every member hidden (a paused work profile): skip it as a lone APP is.
                    if (visibleMembers(item).isEmpty()) continue;
                    page.addView(buildFolderView(item), new CellLayout.LayoutParams(
                            item.getCellX(), item.getCellY(), item.getSpanX(), item.getSpanY()));
                    break;
                }
                case WIDGET: {
                    if (host == null) break;
                    int widgetId = item.getAppWidgetId();
                    AppWidgetProviderInfo info = AppWidgetManager.getInstance(getContext())
                            .getAppWidgetInfo(widgetId);
                    if (info == null) {
                        // A null provider is NOT proof the widget is gone: getAppWidgetInfo also
                        // returns null for a suspended package (Digital Wellbeing, device policy),
                        // an app on unmounted storage, and the mid-PACKAGE_REPLACED window. Drop
                        // the item only when the provider's package is genuinely not installed —
                        // otherwise show a placeholder, because deleting here persists
                        // immediately and unsuspending would never bring the widget back.
                        if (isPackageAbsent(getContext().getPackageManager(),
                                            providerPackageOf(item.getProvider()))) {
                            if (staleWidgets == null) staleWidgets = new ArrayList<>();
                            staleWidgets.add(item);
                        } else {
                            page.addView(buildWidgetPlaceholder(item),
                                    new CellLayout.LayoutParams(item.getCellX(), item.getCellY(),
                                            item.getSpanX(), item.getSpanY()));
                        }
                        break;
                    }
                    // Set the full options bundle BEFORE createView/setAppWidget so the host's
                    // very first applyRemoteViews already has a valid size to select against.
                    // Use the display-metrics cell estimate — the page hasn't been laid out yet at
                    // this point, so a measured cell size isn't available. applyAllWidgetSizes()
                    // later refines with the real measured cell size once layout completes.
                    float wDensity = getResources().getDisplayMetrics().density;
                    int[] estCell = WidgetSizeHelper.estimateCellPx(
                            getContext(), store.columns(), store.rows());
                    WidgetSizeHelper.applyFullOptions(getContext(), widgetId,
                            item.getSpanX(), item.getSpanY(),
                            estCell[0], estCell[1], wDensity);

                    // Inflate against the application context (widgetContext), NOT getContext():
                    // the Activity's AppCompat inflater factory would otherwise break image-bearing
                    // RemoteViews. See the widgetContext field doc.
                    AppWidgetHostView hostView = host.createView(widgetContext, widgetId, info);
                    hostView.setAppWidget(widgetId, info);
                    // Also push the size onto the freshly-created hostview synchronously.
                    WidgetSizeHelper.applySize(hostView, item.getSpanX(), item.getSpanY(),
                            estCell[0], estCell[1], wDensity);

                    // Wrap in WidgetWrapper so long-press-drag is intercepted via
                    // onInterceptTouchEvent without stealing the widget's short taps.
                    // WidgetWrapper extends FrameLayout so all instanceof checks in
                    // showResizeFrame / applyWidgetSizeUpdate / ResizeFrameView still pass.
                    // Use setDragCallback() after construction to allow the lambda to capture
                    // `wrapper` (forward-reference in the constructor argument would not compile).
                    WidgetWrapper wrapper = new WidgetWrapper(getContext());
                    wrapper.setDragCallback(() -> startItemDrag(wrapper, item));
                    wrapper.addView(hostView, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
                    page.addView(wrapper, new CellLayout.LayoutParams(
                            item.getCellX(), item.getCellY(), item.getSpanX(), item.getSpanY()));
                    break;
                }
            }
        }
        if (staleWidgets != null) {
            for (HomeItem stale : staleWidgets) {
                // Pair the model removal with the host deallocation. The drag-to-remove path and
                // SettingsActivity's reset both do this; leaving it out here leaked one
                // host-allocated id per dropped widget, permanently. Guarded because this runs
                // inside a render pass — a throwing OEM host must not cost the user their whole
                // home screen.
                if (host != null) {
                    try {
                        host.deleteAppWidgetId(stale.getAppWidgetId());
                    } catch (RuntimeException e) {
                        Log.w(TAG, "deleteAppWidgetId failed for " + stale.getAppWidgetId(), e);
                    }
                }
                store.removeItem(stale);
            }
        }
        if (screen == 0) maybeAddSearchPlaceholder(page);
    }

    /**
     * Package name of a widget item's provider ({@code ComponentName#flattenToString()}), or null
     * when the item carries no usable provider string — an older layout file, for instance, in
     * which case we cannot prove the package is gone and must keep the widget.
     */
    static String providerPackageOf(String flattenedProvider) {
        if (flattenedProvider == null || flattenedProvider.isEmpty()) return null;
        ComponentName cn = ComponentName.unflattenFromString(flattenedProvider);
        if (cn != null) return cn.getPackageName();
        // Not a flattened component (shouldn't happen) — treat the whole string as a package
        // only if it has no separator to misread.
        return flattenedProvider.indexOf('/') < 0 ? flattenedProvider : null;
    }

    /**
     * True only when {@code packageName} is provably not installed. Anything else — a null or
     * unparseable provider, a binder failure — answers false, because the cost of a wrong "true"
     * is a permanently deleted widget while the cost of a wrong "false" is a placeholder tile.
     */
    static boolean isPackageAbsent(PackageManager pm, String packageName) {
        if (pm == null || packageName == null || packageName.isEmpty()) return false;
        try {
            pm.getPackageInfo(packageName, 0);
            return false;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "package lookup failed for " + packageName + " — keeping the widget", e);
            return false;
        }
    }

    /**
     * Stand-in tile for a widget whose provider is momentarily unavailable (suspended package,
     * unmounted storage, an update in flight). It occupies the widget's cells so nothing else
     * claims them, and long-press still starts a drag so the user can remove it by hand.
     */
    private View buildWidgetPlaceholder(HomeItem item) {
        TextView pill = new TextView(getContext());
        pill.setText("Widget unavailable");
        pill.setTextColor(0xB3FFFFFF);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        pill.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0x55000000); // same translucent tone as the dock pill
        pill.setBackground(bg);
        pill.setOnLongClickListener(v -> {
            startItemDrag(v, item);
            return true;
        });
        return pill;
    }

    /**
     * Adds the "Tap to add Google search" pill to the first free full row of page 0 when
     * {@link #shouldShowSearchPlaceholder} says so. Tap → existing widget bind flow
     * via {@link OnAddWidgetListener}; the ✕ dismisses permanently.
     *
     * <p>The row comes from {@link HomeScreenStore#firstFreeFullRow}, which is also what widens
     * the widget the pill adds — so the widget fills the row the pill was drawn on.
     */
    private void maybeAddSearchPlaceholder(CellLayout page) {
        if (store == null) return;
        // Cheap checks first so the AppWidgetManager binder call below only runs when the
        // pill could plausibly show.
        if (!Settings.isHomeSeeded(getContext())) return;
        if (Settings.isSearchPlaceholderDismissed(getContext())) return;
        int row = store.firstFreeFullRow(0, 1);
        if (row < 0) return;

        AppWidgetProviderInfo google = findGoogleSearchProvider(queryInstalledProviders());
        if (!shouldShowSearchPlaceholder(
                Settings.isHomeSeeded(getContext()),
                Settings.isSearchPlaceholderDismissed(getContext()),
                row >= 0,
                google != null)) {
            return;
        }

        LinearLayout pill = new LinearLayout(getContext());
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(0x55000000); // translucent scrim, matches buildDock's pill tone
        pill.setBackground(bg);

        TextView label = new TextView(getContext());
        label.setText("Tap to add Google search");
        label.setTextColor(0xFFFFFFFF);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pill.addView(label, labelLp);

        TextView dismiss = new TextView(getContext());
        dismiss.setText("✕"); // ✕
        dismiss.setContentDescription("Dismiss"); // a screen reader would otherwise read the glyph
        dismiss.setTextColor(0xB3FFFFFF);
        dismiss.setPadding(dp(12), dp(12), dp(16), dp(12));
        pill.addView(dismiss, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pill.setOnClickListener(v -> {
            // Re-query at tap time: the provider captured at render time may no longer be
            // installed (Google app uninstalled/updated between render and tap).
            AppWidgetProviderInfo freshProvider = findGoogleSearchProvider(queryInstalledProviders());
            if (freshProvider == null) {
                android.widget.Toast.makeText(getContext(), "Google search widget unavailable",
                        android.widget.Toast.LENGTH_SHORT).show();
                Settings.setSearchPlaceholderDismissed(getContext(), true);
                page.removeView(pill);
                return;
            }
            if (addWidgetListener != null) {
                addWidgetListener.onPickSearchWidget(freshProvider);
            }
        });
        dismiss.setOnClickListener(v -> {
            Settings.setSearchPlaceholderDismissed(getContext(), true);
            page.removeView(pill);
        });

        page.addView(pill, new CellLayout.LayoutParams(0, row, store.columns(), 1));
    }

    /** Best-effort fetch of installed widget providers; null on any binder failure. */
    private List<AppWidgetProviderInfo> queryInstalledProviders() {
        try {
            return AppWidgetManager.getInstance(getContext()).getInstalledProviders();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Notification badges — shared by the page and dock builders
    // -------------------------------------------------------------------------

    /** Badge count for a single home item, or 0 when badges are switched off. */
    private int badgeCountFor(String key) {
        if (!Settings.getNotifDots(getContext())) return 0;
        return NotificationDotsService.notificationCount(UserKeys.packageOf(key));
    }

    /**
     * Badge count an item's drawable should currently show: one package for an APP, the sum
     * across members for a FOLDER (a folder has no single package of its own).
     */
    private int badgeCountForItem(HomeItem item) {
        if (item.getType() == HomeItem.Type.FOLDER) return folderBadgeCount(item.getPackageNames());
        return badgeCountFor(item.getPackageName());
    }

    /** Summed notification count across a folder's members, or 0 when badges are switched off. */
    private int folderBadgeCount(List<String> pkgs) {
        if (!Settings.getNotifDots(getContext())) return 0;
        int total = 0;
        for (String pkg : pkgs) {
            total += NotificationDotsService.notificationCount(UserKeys.packageOf(pkg));
        }
        return total;
    }

    /**
     * Builds a badge-wrapped icon drawable using the current badge settings, with the fill the
     * active {@link Personality#BADGE} chose for {@code appKey}.
     */
    private BadgeDrawable newBadgeDrawable(android.graphics.drawable.Drawable base, float density,
                                           int count, String appKey) {
        // 52dp is the size of every icon and folder chip host on this screen; it is only
        // consulted for the badge-only (base == null) folder-chip overlay.
        return new BadgeDrawable(base, density, count, BadgeConfig.from(getContext()),
                Personality.BADGE.fillColor(appKey, count), dp(52));
    }

    /**
     * Overlays a count badge on a folder chip. The count is the <em>sum</em> across members; the
     * badge colour is the one the personality picks for the first member, since a folder has no
     * app key of its own.
     *
     * <p>The overlay is added even when the count is currently zero, so that
     * {@link #updateBadgeCounts} can raise the count in place when the folder's first notification
     * arrives instead of rebuilding the page. That works only because a badge-only
     * {@link BadgeDrawable} reports the same intrinsic size at every count: raising the count
     * invalidates but never re-measures, so a view sized for count 0 alone would stay that size
     * and clip the badge away. At count 0 the overlay is a fully transparent box.
     */
    private void addFolderBadge(FrameLayout chip, HomeItem item) {
        if (!Settings.getNotifDots(getContext())) return;

        List<String> pkgs = visibleMembers(item);
        // A folder has no app key of its own, so its badge takes the colour of its first member —
        // the same rule the count summary follows, and stable as long as the folder's order is.
        String badgeKey = pkgs.isEmpty() ? "" : pkgs.get(0);

        float density = getResources().getDisplayMetrics().density;
        BadgeDrawable badge = newBadgeDrawable(null, density, folderBadgeCount(pkgs), badgeKey);
        ImageView badgeView = new ImageView(getContext());
        // CENTER (not FIT_CENTER): the drawable's intrinsic size is already the badge size, so
        // there is nothing to scale and scaling would soften the outline.
        badgeView.setScaleType(ImageView.ScaleType.CENTER);
        badgeView.setImageDrawable(badge);
        badgeBindings.add(new BadgeBinding(item, badge));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        chip.addView(badgeView, lp);
    }

    private LinearLayout buildAppView(HomeItem item, AppInfo info) {
        LinearLayout outer = new LinearLayout(getContext());
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);

        // Wrap the icon with a notification count badge overlay.
        int count = badgeCountFor(item.getPackageName());
        float density = getResources().getDisplayMetrics().density;
        BadgeDrawable badge = newBadgeDrawable(
                Personality.ICON.treat(getContext(), info.getIcon(), info.getPackageName()),
                density, count, info.getPackageName());
        badgeBindings.add(new BadgeBinding(item, badge));
        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(badge);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        outer.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView label = new TextView(getContext());
        label.setText(info.getLabel());
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(3f, 0f, 1f, 0xFF000000); // shadow for readability on any wallpaper
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(4);
        outer.addView(label, labelLp);

        outer.setOnClickListener(v -> {
            if (appClickListener != null) appClickListener.onAppClick(item.getPackageName());
        });
        outer.setOnLongClickListener(v -> {
            startItemDrag(v, item);
            return true;
        });
        return outer;
    }

    /**
     * The folder's members that are in the loaded app list, in folder order. A member missing
     * from it — on a paused work profile, or disabled until the full load prunes it — stays in
     * the folder, but the chip shows only what a tap on the folder would.
     */
    private List<String> visibleMembers(HomeItem folder) {
        List<String> out = new ArrayList<>();
        for (String pkg : folder.getPackageNames()) {
            if (appMap.containsKey(pkg)) out.add(pkg);
        }
        return out;
    }

    private LinearLayout buildFolderView(HomeItem item) {
        LinearLayout outer = new LinearLayout(getContext());
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);

        // Dark translucent rounded chip containing a 2×2 mini-grid of member icons
        FrameLayout chip = new FrameLayout(getContext());
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(dp(14));
        chipBg.setColor(0xBB000000);
        chip.setBackground(chipBg);

        int miniSlot   = dp(20);
        int miniMargin = dp(1);
        List<String> pkgs = visibleMembers(item);

        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);
        for (int row = 0; row < 2; row++) {
            LinearLayout rowView = new LinearLayout(getContext());
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setGravity(Gravity.CENTER);
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                ImageView iv = new ImageView(getContext());
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (idx < pkgs.size()) {
                    AppInfo info = appMap.get(pkgs.get(idx));
                    // Its own instance: an ImageView sets a drawable's bounds once, and the
                    // drawer's canvas views move the shared one. See AppInfo.getIcon().
                    if (info != null) iv.setImageDrawable(info.newIconForView());
                }
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(miniSlot, miniSlot);
                ivLp.setMargins(miniMargin, miniMargin, miniMargin, miniMargin);
                rowView.addView(iv, ivLp);
            }
            grid.addView(rowView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        chip.addView(grid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        addFolderBadge(chip, item);

        outer.addView(chip, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView label = new TextView(getContext());
        String name = item.getFolderName();
        label.setText(name != null && !name.isEmpty() ? name : "Folder");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(3f, 0f, 1f, 0xFF000000);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = dp(4);
        outer.addView(label, labelLp);

        outer.setOnClickListener(v -> {
            if (folderClickListener != null) folderClickListener.onFolderClick(item);
        });
        outer.setOnLongClickListener(v -> {
            startItemDrag(v, item);
            return true;
        });
        return outer;
    }

    // -------------------------------------------------------------------------
    // Dock — fixed row below the pager, above the page dots
    // -------------------------------------------------------------------------

    /** Builds (or rebuilds) the dock view and adds it to the view hierarchy. */
    private void buildDock() {
        if (store == null) return;

        dockView = new LinearLayout(getContext());
        dockView.setOrientation(LinearLayout.HORIZONTAL);
        dockView.setGravity(Gravity.CENTER_VERTICAL);

        // Subtle translucent pill background.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x55000000);
        bg.setCornerRadius(dp(DOCK_HEIGHT_DP / 2));
        dockView.setBackground(bg);

        int cols = store.columns();
        java.util.List<HomeItem> dock = store.dockItems();

        for (int slot = 0; slot < cols; slot++) {
            HomeItem item = null;
            for (HomeItem di : dock) {
                if (di.getCellX() == slot) { item = di; break; }
            }

            FrameLayout slotFrame = new FrameLayout(getContext());
            LinearLayout.LayoutParams slotLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            dockView.addView(slotFrame, slotLp);

            if (item == null) continue;

            View iconView = null;
            switch (item.getType()) {
                case APP: {
                    AppInfo info = appMap.get(item.getPackageName());
                    if (info != null) iconView = buildDockAppView(item, info);
                    break;
                }
                case FOLDER:
                    if (!visibleMembers(item).isEmpty()) iconView = buildDockFolderView(item);
                    break;
                default:
                    break; // WIDGET not allowed in dock
            }
            if (iconView != null) {
                FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(52), dp(52));
                iconLp.gravity = Gravity.CENTER;
                slotFrame.addView(iconView, iconLp);
            }
        }

        addView(dockView);
    }

    /** Builds a no-label icon view for a dock APP item. */
    private ImageView buildDockAppView(HomeItem item, AppInfo info) {
        // Notification count badge.
        int count = badgeCountFor(item.getPackageName());
        float density = getResources().getDisplayMetrics().density;
        BadgeDrawable badge = newBadgeDrawable(
                Personality.ICON.treat(getContext(), info.getIcon(), info.getPackageName()),
                density, count, info.getPackageName());
        badgeBindings.add(new BadgeBinding(item, badge));
        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(badge);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // The dock has no label under the icon, so this is the only name a screen reader gets.
        icon.setContentDescription(info.getLabel());
        icon.setOnClickListener(v -> {
            if (appClickListener != null) appClickListener.onAppClick(item.getPackageName());
        });
        icon.setOnLongClickListener(v -> {
            startItemDrag(v, item);
            return true;
        });
        return icon;
    }

    /** Builds a no-label folder chip view for a dock FOLDER item. */
    private FrameLayout buildDockFolderView(HomeItem item) {
        FrameLayout chip = new FrameLayout(getContext());
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setCornerRadius(dp(14));
        chipBg.setColor(0xBB000000);
        chip.setBackground(chipBg);

        int miniSlot   = dp(20);
        int miniMargin = dp(1);
        java.util.List<String> pkgs = visibleMembers(item);

        LinearLayout grid = new LinearLayout(getContext());
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);
        for (int row = 0; row < 2; row++) {
            LinearLayout rowView = new LinearLayout(getContext());
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            rowView.setGravity(Gravity.CENTER);
            for (int col = 0; col < 2; col++) {
                int idx = row * 2 + col;
                ImageView iv = new ImageView(getContext());
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                if (idx < pkgs.size()) {
                    AppInfo info = appMap.get(pkgs.get(idx));
                    // Its own instance: an ImageView sets a drawable's bounds once, and the
                    // drawer's canvas views move the shared one. See AppInfo.getIcon().
                    if (info != null) iv.setImageDrawable(info.newIconForView());
                }
                LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(miniSlot, miniSlot);
                ivLp.setMargins(miniMargin, miniMargin, miniMargin, miniMargin);
                rowView.addView(iv, ivLp);
            }
            grid.addView(rowView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        chip.addView(grid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        addFolderBadge(chip, item);

        // No label in the dock (see buildDockAppView), so the chip names itself.
        String folderName = item.getFolderName();
        chip.setContentDescription(folderName != null && !folderName.isEmpty()
                ? folderName : "Folder");
        chip.setOnClickListener(v -> {
            if (folderClickListener != null) folderClickListener.onFolderClick(item);
        });
        chip.setOnLongClickListener(v -> {
            startItemDrag(v, item);
            return true;
        });
        return chip;
    }

    // -------------------------------------------------------------------------
    // Layout — horizontal strip: page[i] at (i * width, 0)
    // -------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        // Pages sit below the status bar and above the dock (DOCK_HEIGHT_DP), which itself sits
        // above the dots (DOT_AREA_DP), which clear the navigation bar.
        int pageH = pageHeight(h);

        for (CellLayout page : pageViews) {
            page.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(pageH, MeasureSpec.EXACTLY));
        }
        // Dock is measured at its own width (minus horizontal margins) and fixed height.
        int dockMrgPx = dp(DOCK_MARGIN_DP);
        int dockMeasW = MeasureSpec.makeMeasureSpec(Math.max(0, w - 2 * dockMrgPx), MeasureSpec.EXACTLY);
        int dockMeasH = MeasureSpec.makeMeasureSpec(dp(DOCK_HEIGHT_DP), MeasureSpec.EXACTLY);
        // Overlay children (folder popup, etc.) fill the whole view.
        int fullW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int fullH = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof CellLayout) continue;
            if (child == dockView) {
                child.measure(dockMeasW, dockMeasH);
            } else {
                child.measure(fullW, fullH);
            }
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w     = r - l;
        int pageH = pageHeight(b - t);
        for (int i = 0; i < pageViews.size(); i++) {
            pageViews.get(i).layout(i * w, insetTop, (i + 1) * w, insetTop + pageH);
        }
        int dockMrgPx = dp(DOCK_MARGIN_DP);
        int dockTop   = insetTop + pageH;
        int dockBot   = dockTop + dp(DOCK_HEIGHT_DP);
        // Overlay children laid out at (0,0,w,h). Every non-page child — the dock included — is
        // translated by the scroll offset so it stays pinned to the visible screen while the page
        // strip scrolls underneath it. (translationX=0 would make the dock scroll away with page 0.)
        int targetScrollX = currentPage * w;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof CellLayout) continue;
            if (child == dockView) {
                // Dock pill: positioned with horizontal margins, fixed vertically.
                child.layout(dockMrgPx, dockTop, w - dockMrgPx, dockBot);
            } else {
                // Folder overlay (and any other overlay): fill the whole view.
                child.layout(0, 0, w, b - t);
            }
            child.setTranslationX(targetScrollX);
        }
        // Snap to current page without animation (e.g. after rebuild or rotation) — but only when
        // nothing else currently owns the scroll offset. See shouldSnapScrollOnLayout.
        if (shouldSnapScrollOnLayout(changed, scroller.isFinished(), isDragging, touchPaging)) {
            scroller.abortAnimation();
            scrollTo(targetScrollX, 0);
        }
    }

    /**
     * Whether {@code onLayout} should abort any running scroll and jump to {@code currentPage}.
     *
     * <p>{@code onLayout} runs far more often than "the view changed size": {@code View.layout}
     * invokes it whenever {@code PFLAG_LAYOUT_REQUIRED} is set, which it is for every ancestor in
     * a layout pass started by a descendant. An {@link AppWidgetHostView} applying new RemoteViews
     * does exactly that — a clock widget, once a minute. Snapping unconditionally therefore killed
     * fling animations mid-flight, undid the finger's own {@code scrollTo} while drag-paging, and
     * made {@code flipEdgePage} teleport instead of animate (it calls {@code requestLayout} to
     * place the new page, then starts the scroll the layout pass had just aborted).
     *
     * @param boundsChanged    the {@code changed} flag {@code onLayout} was handed
     * @param scrollerFinished whether the page-snap/fling animation has completed
     * @param itemDragActive   whether an item drag owns the screen (edge paging drives the scroll)
     * @param touchPaging      whether a finger is currently dragging the page strip
     */
    static boolean shouldSnapScrollOnLayout(boolean boundsChanged, boolean scrollerFinished,
                                            boolean itemDragActive, boolean touchPaging) {
        // A real geometry change invalidates every scroll offset in flight, so it always wins.
        if (boundsChanged) return true;
        return scrollerFinished && !itemDragActive && !touchPaging;
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        // Keep overlay children fixed in screen space as the strip scrolls.
        updateOverlayTranslation();
    }

    private void updateOverlayTranslation() {
        int scrollX = getScrollX();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            // Every non-page child — dock and overlays alike — cancels the scroll so it stays
            // fixed on screen. Skipping the dock here would let it slide off with page 0.
            if (!(child instanceof CellLayout)) child.setTranslationX(scrollX);
        }
    }

    // -------------------------------------------------------------------------
    // Dot indicator — drawn on top of pages in dispatchDraw so it stays fixed
    // regardless of scroll offset.
    // -------------------------------------------------------------------------

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (pageViews.size() > 1) drawDots(canvas);
        if (isDragging) drawActionBar(canvas);
        if (isDragging && previewIsValid) drawDropPreview(canvas);
    }

    private void drawDots(Canvas canvas) {
        int numPages = pageViews.size();
        int scrollX  = getScrollX();
        int w        = getWidth();
        int h        = getHeight();
        int dotR     = dp(DOT_RADIUS_DP);
        int gap      = dp(DOT_GAP_DP);
        int totalW   = numPages * (dotR * 2) + (numPages - 1) * gap;
        int startX   = (w - totalW) / 2;
        int centerY  = h - insetBottom - dp(DOT_AREA_DP) / 2;

        for (int i = 0; i < numPages; i++) {
            dotPaint.setColor(i == currentPage ? 0xCCFFFFFF : 0x55FFFFFF);
            // Add scrollX so the dot draws at its screen position despite the translated canvas.
            int cx = scrollX + startX + i * (dotR * 2 + gap) + dotR;
            canvas.drawCircle(cx, centerY, dotR, dotPaint);
        }
    }

    /**
     * Zones offered on the action bar for the item currently being dragged, left to right.
     * Remove is always available; Uninstall for a real, non-work personal app, or Disable in its
     * place for a system app (see {@link AppRemoval}).
     *
     * <p>Computed once per drag (see {@link #dragActions}); both the painter and the drop
     * resolver consult the result every frame.
     */
    private DropTarget.Zone[] computeAvailableActions() {
        // A drawer app is not on the home screen; a folder member being pulled out is (Remove
        // there means "out of the folder and off the screen", see commitIncomingDrop).
        boolean onHomeScreen = !(isIncomingApp && pullOutFolder == null);
        if (draggedItem == null) return availableActions(null, null, onHomeScreen);
        boolean isApp = draggedItem.getType() == HomeItem.Type.APP;
        return availableActions(draggedItem,
                isApp ? appMap.get(draggedItem.getPackageName()) : null, onHomeScreen);
    }

    /**
     * Pure decision half of {@link #computeAvailableActions}, split out so the rule is testable
     * without a live drag.
     *
     * @param info         the dragged app's {@link AppInfo}, or null if it isn't an app / isn't
     *                     known
     * @param onHomeScreen whether the dragged item exists on the home screen at all. An app
     *                     dragged out of the drawer does not, and Remove has nothing to mean for
     *                     it — {@code commitIncomingDrop} refuses that drop, so offering the pill
     *                     lit it up as a target and then silently did nothing.
     */
    static DropTarget.Zone[] availableActions(HomeItem item, AppInfo info, boolean onHomeScreen) {
        List<DropTarget.Zone> zones = new ArrayList<>(2);
        if (onHomeScreen) zones.add(DropTarget.Zone.REMOVE);
        if (item != null && item.getType() == HomeItem.Type.APP) {
            // Same rule as every app menu, from the same place.
            switch (AppRemoval.kindFor(info)) {
                case UNINSTALL: zones.add(DropTarget.Zone.UNINSTALL); break;
                case DISABLE:   zones.add(DropTarget.Zone.DISABLE); break;
                default:        break;
            }
        }
        return zones.toArray(new DropTarget.Zone[0]);
    }

    /**
     * Geometry of the action bar for the current drag, in <b>screen-local</b> space — the space
     * {@link DropTarget#resolve} hit-tests in, and the space the bar conceptually lives in: the
     * pills sit at the visible centre no matter which page is scrolled under them.
     *
     * <p>This used to add {@code getScrollX()} to the centre. That is the compensation the
     * <em>painter</em> needs (see {@link #drawDots}) and it belongs there, not in shared geometry:
     * baked in here it made the pills draw correctly while {@code resolve} — handed a raw
     * {@code DragEvent.getX()} — missed every pill by exactly the scroll offset. Remove worked on
     * page 0 and silently did nothing on every other page.
     */
    private DropTarget.ActionBar actionBarLayout() {
        if (!isDragging || dragActions == null) return DropTarget.ActionBar.NONE;
        float top = insetTop + dp(ACTION_TOP_DP);
        return new DropTarget.ActionBar(
                dragActions,
                getWidth() / 2f,
                top, top + dp(ACTION_H_DP),
                dp(ACTION_PILL_DP), dp(ACTION_GAP_DP));
    }

    /**
     * Label for an action pill. Plain words rather than icon glyphs for Uninstall and Resize:
     * "✕" is already proven to render here, but arrow and bin dingbats are not guaranteed to be
     * in the device's font fallback chain and would show as tofu. Colour carries the emphasis.
     */
    private static String actionLabel(DropTarget.Zone zone) {
        switch (zone) {
            case UNINSTALL: return AppRemoval.UNINSTALL_LABEL;
            case DISABLE:   return AppRemoval.DISABLE_LABEL;
            default:        return "✕  Remove";
        }
    }

    private static int actionColor(DropTarget.Zone zone) {
        switch (zone) {
            case UNINSTALL: // orange — destructive but reversible
            case DISABLE:   return 0xFFFF8A65;
            default:        return 0xFFFF5555; // red
        }
    }

    /**
     * Draws the drag action bar at the top of the visible screen. Pill rectangles come from the
     * same {@link DropTarget.ActionBar} the drop resolver hit-tests against, so what is drawn and
     * what is droppable cannot drift apart.
     */
    private void drawActionBar(Canvas canvas) {
        DropTarget.ActionBar bar = actionBarLayout();
        float radius = dp(ACTION_H_DP / 2);

        // The bar is screen-local but dispatchDraw's canvas is content-space, so shift by the
        // scroll to paint it at the visible centre — the same move drawDots makes, and the reason
        // this offset must not live in the geometry the hit test shares.
        canvas.save();
        canvas.translate(getScrollX(), 0);
        for (int i = 0; i < bar.count(); i++) {
            DropTarget.Zone zone = bar.zones[i];
            float left = bar.pillLeft(i);
            // The pointer is over this pill when the resolver already picked its zone.
            boolean active = previewTarget != null && previewTarget.zone == zone;

            // Hover has to be unmistakable. Remove destroys something the moment the finger
            // lifts, so "armed" must read at a glance and from behind a fingertip: the pill
            // fills with its own colour and swells, rather than swapping one dark grey for a
            // slightly redder dark grey. The swell is visual only — the hit rect stays put, and
            // it can only appear once the pointer is already inside the pill.
            float grow = active ? dp(ACTION_GROW_DP) : 0f;
            removePillPaint.setColor(active ? actionColor(zone) : 0xCC222222);
            canvas.drawRoundRect(
                    new RectF(left - grow, bar.topPx - grow,
                            left + bar.pillW + grow, bar.botPx + grow),
                    radius + grow, radius + grow, removePillPaint);

            // On the filled pill the label inverts to near-black: white on this red sits around
            // 2.5:1, black nearer 7:1, and this is the one label that must stay readable.
            removeTextPaint.setColor(active ? 0xFF1A1013 : actionColor(zone));
            removeTextPaint.setTextSize(dp(active ? 14 : 13));
            removeTextPaint.setFakeBoldText(active);
            // Baseline at 62% of the pill height centres the cap-height line well enough.
            canvas.drawText(actionLabel(zone), left + bar.pillW / 2f,
                    bar.topPx + (bar.botPx - bar.topPx) * 0.62f, removeTextPaint);
        }
        canvas.restore();
    }

    // -------------------------------------------------------------------------
    // Touch paging
    // -------------------------------------------------------------------------

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (folderPopupOverlay != null) return false; // popup handles its own touches
        if (isDragging)                return false; // drag system owns the touch stream

        // Resize frame: allow horizontal swipes through (so page navigation still works and
        // dismisses the frame), but leave non-swipe touches — and every handle drag — for the
        // frame to handle.
        if (resizeFrameOverlay != null) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    interceptDownX = ev.getX();
                    interceptDownY = ev.getY();
                    // Ownership is settled here, while the DOWN position still says what the user
                    // grabbed. By the first MOVE a handle drag is indistinguishable from a swipe.
                    resizeFrameOwnsGesture = isOnResizeFrame(ev.getX(), ev.getY());
                    if (RDBG) Log.d(RTAG, "intercept DOWN (" + Math.round(ev.getX()) + ","
                            + Math.round(ev.getY()) + ") frameOwnsGesture="
                            + resizeFrameOwnsGesture
                            + " (handle=" + isOnResizeHandle(ev.getX(), ev.getY()) + ")");
                    break;
                case MotionEvent.ACTION_MOVE: {
                    if (resizeFrameOwnsGesture) break; // the frame owns this stream
                    float dx = Math.abs(ev.getX() - interceptDownX);
                    float dy = Math.abs(ev.getY() - interceptDownY);
                    if (dx > touchSlop && dx > dy) {
                        // The near-miss case worth watching for: the user meant to grab the
                        // corner grip or the widget itself, the DOWN landed outside the frame, and
                        // the very first horizontal movement closes the frame and pages instead.
                        if (RDBG) Log.d(RTAG, "SWIPE-DISMISS: the DOWN was outside the frame; dx="
                                + Math.round(dx) + " dy=" + Math.round(dy)
                                + " slop=" + touchSlop + " -> frame closed, page now swiping");
                        dismissResizeFrame();
                        touchDownX = ev.getX();
                        return true; // let onTouchEvent handle the page swipe
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    resizeFrameOwnsGesture = false;
                    break;
            }
            return false; // let resize frame handle non-swipe touches
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                interceptDownX = ev.getX();
                interceptDownY = ev.getY();
                widgetLongPressCancelled = false;
                // Intercept immediately if a fling is in progress (so it can be stopped).
                if (!scroller.isFinished()) {
                    scroller.abortAnimation();
                    touchPaging = true;
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                float absDx = Math.abs(ev.getX() - interceptDownX);
                float rawDy = ev.getY() - interceptDownY; // negative = upward
                float absDy = Math.abs(rawDy);
                if (movementCancelsLongPress(absDx, absDy, touchSlop)) {
                    cancelPendingWidgetLongPress();
                }
                if (absDx > touchSlop && absDx > absDy) {
                    // Horizontal intent — intercept for page paging.
                    touchDownX = ev.getX();
                    touchPaging = true;
                    return true;
                }
                // Upward swipe: clear vertical-up intent (displacement > 2× slop, more vertical
                // than horizontal). Fire the listener and let the touch stream continue normally —
                // the activity will replace this view so remaining events are harmless.
                if (isUpSwipe(rawDy, absDx, absDy)) {
                    // The listener rebuilds the hierarchy, so this gesture's UP may never reach
                    // us — release the scroll here rather than waiting for a terminal event.
                    touchPaging = false;
                    if (swipeUpListener != null) swipeUpListener.onSwipeUp();
                    return false;
                }
                break;
            }
        }
        return false;
    }

    /**
     * Whether a pointer displacement is large enough to mean "this is a swipe, not a long-press".
     * Shared by the intercept and touch paths so the two cannot disagree about when a widget's
     * pending long-press dies.
     */
    static boolean movementCancelsLongPress(float absDx, float absDy, int touchSlop) {
        return absDx > touchSlop || absDy > touchSlop;
    }

    /**
     * The {@link WidgetWrapper} whose laid-out footprint contains a point, or null when the point
     * is over empty wallpaper (or over any non-widget child).
     *
     * <p>This is a geometric test on purpose, not "which view consumed the DOWN". The whole reason
     * the background menu could fire over a widget is that nothing under the wrapper consumed the
     * DOWN — a clock, a weather tile, or the padding around the Google search pill — so asking the
     * dispatch machinery would answer "nobody" for exactly the cases that need suppressing.
     *
     * <p>Coordinates arriving at {@link #onTouchEvent} are pre-scroll: {@code ViewGroup} adds
     * {@link #getScrollX()} back when it hit-tests children, so the same offset has to be applied
     * here or every test would be a page out as soon as the user paged sideways. The vertical axis
     * needs no such correction — this view only ever scrolls horizontally.
     *
     * <p>Package-private so tests can assert the hit test itself.
     */
    WidgetWrapper widgetWrapperAt(float x, float y) {
        float contentX = x + getScrollX();
        for (CellLayout page : pageViews) {
            if (contentX < page.getLeft() || contentX >= page.getRight()) continue;
            if (y < page.getTop() || y >= page.getBottom())               continue;
            float pageX = contentX - page.getLeft();
            float pageY = y - page.getTop();
            // Back to front: the topmost child wins, matching dispatch order.
            for (int i = page.getChildCount() - 1; i >= 0; i--) {
                View child = page.getChildAt(i);
                if (!(child instanceof WidgetWrapper))  continue;
                if (child.getVisibility() != VISIBLE)   continue;
                if (pageX >= child.getLeft() && pageX < child.getRight()
                        && pageY >= child.getTop() && pageY < child.getBottom()) {
                    return (WidgetWrapper) child;
                }
            }
        }
        return null;
    }

    /**
     * Cancels the pending long-press on every widget wrapper currently on a page.
     *
     * <p>This has to be driven from here, not from {@code WidgetWrapper}. When a widget's
     * RemoteViews root isn't clickable — a clock, a weather tile — nothing beneath the wrapper
     * consumes ACTION_DOWN, so {@code wrapper.dispatchTouchEvent} returns false, the parent
     * {@link CellLayout} never installs it as a touch target, and the wrapper receives no MOVE,
     * no UP and no CANCEL: its own {@code removeCallbacks} branches are unreachable. Left alone,
     * the timeout fires mid-swipe (or 500 ms after a plain tap) and starts an accidental drag.
     * This view always sees the whole stream, so the cancellation belongs here.
     */
    private void cancelPendingWidgetLongPress() {
        if (widgetLongPressCancelled) return; // once per gesture is enough
        widgetLongPressCancelled = true;
        for (CellLayout page : pageViews) {
            for (int i = 0; i < page.getChildCount(); i++) {
                View child = page.getChildAt(i);
                if (child instanceof WidgetWrapper) {
                    ((WidgetWrapper) child).cancelPendingLongPress();
                }
            }
        }
    }

    /**
     * Whether a gesture's displacement from its start point qualifies as a drawer-opening
     * up-swipe: predominantly vertical, upward, and past twice the touch slop. Shared by
     * {@link #onInterceptTouchEvent} (start-on-icon case) and {@link #onTouchEvent}
     * (start-on-empty-wallpaper case) so the two stay in sync.
     *
     * @param rawDy total vertical displacement from the gesture start (negative = upward)
     * @param absDx absolute total horizontal displacement from the gesture start
     * @param absDy absolute total vertical displacement from the gesture start
     */
    private boolean isUpSwipe(float rawDy, float absDx, float absDy) {
        return rawDy < 0 && absDy > 2 * touchSlop && absDy > absDx;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (pageViews.isEmpty()) return false;

        // Decide who owns this gesture BEFORE the detector can arm its long-press timeout: a DOWN
        // that landed on a widget belongs to that widget, not to the background menu.
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downOnWidget = widgetWrapperAt(ev.getX(), ev.getY()) != null;
            backgroundMenuOwnsGesture = false;
        }

        // Feed the gesture detector for background long-press detection.
        if (!isDragging && folderPopupOverlay == null) gestureDetector.onTouchEvent(ev);

        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(ev);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX    = ev.getX();
                gestureStartX = ev.getX();
                gestureStartY = ev.getY();
                swipeUpFired  = false;
                widgetLongPressCancelled = false;
                if (!scroller.isFinished()) scroller.abortAnimation();
                return true;

            case MotionEvent.ACTION_MOVE: {
                // The background menu took this gesture on its long-press; swallow the rest so a
                // slide after it opened is neither an up-swipe nor a page drag.
                if (backgroundMenuOwnsGesture) return true;
                float moveDx = Math.abs(ev.getX() - gestureStartX);
                float moveDy = Math.abs(ev.getY() - gestureStartY);
                // Kill any widget's pending long-press before anything else: on this path the
                // wrapper itself never sees the MOVE (see cancelPendingWidgetLongPress).
                if (movementCancelsLongPress(moveDx, moveDy, touchSlop)) {
                    cancelPendingWidgetLongPress();
                }
                // Up-swipe check first, using total displacement from the gesture start (not the
                // per-move delta). Fires once per gesture and consumes without paging so a vertical
                // up-swipe over empty wallpaper opens the drawer instead of scrolling pages.
                if (!swipeUpFired) {
                    float rawDy = ev.getY() - gestureStartY; // negative = upward
                    if (isUpSwipe(rawDy, moveDx, moveDy)) {
                        swipeUpFired = true;
                        // The listener rebuilds the hierarchy, so this gesture's UP may never
                        // reach us — release the scroll now, not on a terminal event.
                        touchPaging = false;
                        if (swipeUpListener != null) swipeUpListener.onSwipeUp();
                        return true;
                    }
                }
                // After an up-swipe fired, swallow the rest of the gesture so it doesn't also page.
                if (swipeUpFired) return true;
                float dx = ev.getX() - touchDownX;
                touchDownX = ev.getX();
                int maxScroll = Math.max(0, (pageViews.size() - 1) * getWidth());
                int newScroll = Math.max(0, Math.min(maxScroll, getScrollX() - Math.round(dx)));
                touchPaging = true;
                scrollTo(newScroll, 0);
                return true;
            }

            case MotionEvent.ACTION_UP: {
                // A quick tap on a non-interactive widget also lands here (the wrapper never
                // became the touch target), and its long-press timeout is still queued.
                cancelPendingWidgetLongPress();
                if (backgroundMenuOwnsGesture) {
                    // Nothing was scrolled, so there is nothing to snap; just let the tracker go.
                    velocityTracker.recycle();
                    velocityTracker = null;
                    touchPaging = false;
                    return true;
                }
                velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                float velX = velocityTracker.getXVelocity();
                velocityTracker.recycle();
                velocityTracker = null;
                touchPaging = false;
                snapToPage(velX);
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                cancelPendingWidgetLongPress();
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                touchPaging = false;
                snapToPage(0f);
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    private void snapToPage(float velX) {
        if (resizeFrameOverlay != null) {
            if (RDBG) Log.d(RTAG, "snapToPage: dismissing the frame (a page swipe finished)");
            dismissResizeFrame(); // Phase C: swipe dismisses frame
        }
        int pageWidth = getWidth();
        if (pageWidth == 0) return;
        int numPages = pageViews.size();
        int scrollX  = getScrollX();
        // Candidate page from midpoint
        int page = (scrollX + pageWidth / 2) / pageWidth;
        // Honour a sufficiently fast fling to flip one page
        if (velX < -minFlingVelocity && page < numPages - 1) page++;
        else if (velX >  minFlingVelocity && page > 0)       page--;
        currentPage = Math.max(0, Math.min(numPages - 1, page));
        scroller.startScroll(scrollX, 0, currentPage * pageWidth - scrollX, 0, SNAP_MS);
        postInvalidate();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            postInvalidate();
        }
    }

    // -------------------------------------------------------------------------
    // Drag — startDragAndDrop approach (simpler than a hand-rolled overlay: the
    // system renders the shadow above all windows and DragEvents route naturally
    // to OnDragListeners set on specific views).
    // -------------------------------------------------------------------------

    private void startItemDrag(View itemView, HomeItem item) {
        dismissResizeFrame(); // Phase C: starting a drag cancels the resize frame
        View.DragShadowBuilder shadow = new View.DragShadowBuilder(itemView);
        // Null ClipData, deliberately. This drag is internal — nothing reads the clip — and a
        // drag advertising text/plain makes OEM "smart drag" panels (OPPO/OnePlus File Dock)
        // register a system drop target over the top-right of the screen. That window swallows
        // the drag: the launcher stops receiving DRAG_LOCATION there and never sees the drop,
        // which made the Uninstall pill (right of centre) impossible to hit on those devices.
        boolean started = itemView.startDragAndDrop(null, shadow, null, 0);
        if (!started) return; // system refused; leave all state unchanged
        beginItemDrag(itemView, item);
    }

    /**
     * The state half of {@link #startItemDrag}, split out from the {@code startDragAndDrop} call
     * so a test can drive a drag the harness cannot start.
     *
     * <p>Robolectric's window-session shadow rejects a drag with a null {@link ClipData}, which is
     * exactly the ClipData this drag deliberately uses (see {@link #startItemDrag}). That is why
     * the whole drop path — the Remove pill included — had no unit test until one was needed to
     * catch a regression in it. Everything after the system call lives here so the fixture drives
     * production code rather than a copy of it.
     */
    void beginItemDrag(View itemView, HomeItem item) {
        draggedItem    = item;
        dragSourceView = itemView;
        isDragging     = true;
        dragActions    = computeAvailableActions();
        itemView.setVisibility(View.INVISIBLE);
        invalidate(); // show the action bar
    }

    /** Concludes a drag: resets state and rebuilds pages from the store. */
    private void endDrag() {
        isDragging          = false;
        isIncomingApp       = false;
        incomingAppPkg      = null;
        pullOutFolder       = null;
        activeFolderDrag    = null;
        dragActions         = null;
        grabCellX           = 0;
        grabCellY           = 0;
        clearDropPreview();
        HomeItem toResize   = pendingResizeItem;   // save before clearing
        HomeItem toShortcut = pendingShortcutItem; // Phase D: shortcut popup post-refresh
        draggedItem         = null;
        dragSourceView      = null;
        pendingResizeItem   = null;
        pendingShortcutItem = null; // Phase D
        pendingEdgeDir      = 0;
        removeCallbacks(edgePageTask);
        invalidate(); // hide remove zone
        touchPaging   = false;
        // Rebuild from store (restores original or reflects new placement). This is also where a
        // reload deferred by shouldDeferRefresh during the drag gets replayed.
        refresh();
        if (toResize != null)   showResizeFrame(toResize);         // Phase C
        if (toShortcut != null) showDeepShortcutPopup(toShortcut); // Phase D
    }

    private final OnDragListener homeDragListener = (v, event) -> {
        switch (event.getAction()) {

            case DragEvent.ACTION_DRAG_STARTED:
                // Accept drags we initiated (draggedItem != null) OR incoming drawer-app drags.
                dragMoved       = false;
                sawDragLocation = false;
                dragStartX = toScreenX(event.getX());
                dragStartY = toScreenY(event.getY());

                // Check for an incoming cross-view drag from the drawer.
                Object localState = event.getLocalState();
                if (localState instanceof DrawerDragState) {
                    DrawerDragState dds = (DrawerDragState) localState;
                    incomingAppPkg = dds.packageKey;
                    isIncomingApp  = true;
                    // Synthesise a 1×1 APP placeholder so the existing preview and drop math work.
                    draggedItem    = HomeItem.app(0, 0, 0, dds.packageKey);
                    dragSourceView = null; // source is in the drawer; no view to restore
                    isDragging     = true;
                    dragActions    = computeAvailableActions();
                    invalidate(); // show the action bar
                    // Notify MainActivity to dismiss the drawer overlay so the grid is revealed.
                    // MUST be deferred: removing views synchronously here mutates the view tree
                    // while ViewGroup.notifyChildOfDragStart is still iterating it to deliver
                    // ACTION_DRAG_STARTED, which crashes with an NPE in dispatchDragEvent.
                    post(() -> {
                        if (drawerDismissForDragListener != null) {
                            drawerDismissForDragListener.onDismissDrawerForDrag();
                        }
                    });
                    return true;
                }

                // A folder-member drag started by the open popup. Accepted, but no drag state
                // is set up here: while the popup is on screen its own listeners own the gesture
                // (a reorder). Accepting matters for the other outcome — dragging the member out
                // of the card tears the popup down mid-drag (see folderPullOutListener), and only
                // a view that returned true here keeps receiving events once it is gone.
                if (localState instanceof FolderDragState) {
                    activeFolderDrag = (FolderDragState) localState;
                    if (DDBG) Log.d(DTAG, "STARTED: folder member (popup owns it for now)");
                    return true;
                }

                // Internal homescreen drag.
                if (DDBG) Log.d(DTAG, "STARTED: internal drag; accepted=" + (draggedItem != null)
                        + " item=" + describe(draggedItem)
                        + " actions=" + Arrays.toString(dragActions)
                        + " staleIncoming=" + isIncomingApp + "/" + incomingAppPkg);
                // Here and not in beginItemDrag: the finger's position arrives with this event.
                // For an internal drag nothing is re-laid out between now and the first
                // DRAG_LOCATION, so the local coordinates are the ones the drop will use.
                captureGrabCell(event.getX(), event.getY());
                return draggedItem != null;

            case DragEvent.ACTION_DRAG_LOCATION:
                // Track whether the drag moved more than slop (used by resize-frame logic).
                sawDragLocation = true;
                updateDragMoved(event.getX(), event.getY());
                handleDragLocation(event.getX(), event.getY());
                return true;

            case DragEvent.ACTION_DROP: {
                boolean accepted = handleDrop(event.getX(), event.getY());
                if (DDBG) Log.d(DTAG, "DROP at (" + Math.round(event.getX()) + ","
                        + Math.round(event.getY()) + ") -> accepted=" + accepted
                        + "; a false here is the whole \"Remove did nothing\" symptom");
                return accepted;
            }

            case DragEvent.ACTION_DRAG_EXITED:
                // Pointer left this view — hide preview until it re-enters.
                clearDropPreview();
                return true;

            case DragEvent.ACTION_DRAG_ENDED: {
                // A member drag that stayed inside the popup: the popup committed (or refused)
                // its own reorder and is still on screen. endDrag() would refresh() and close it.
                boolean handledInPopup = activeFolderDrag != null && !isDragging;
                if (DDBG) Log.d(DTAG, "ENDED: result=" + event.getResult()
                        + " handledInPopup=" + handledInPopup
                        + " (endDrag " + (handledInPopup ? "SKIPPED" : "runs")
                        + ", it is what clears isIncomingApp)");
                activeFolderDrag = null;
                if (handledInPopup) return true;

                if (!event.getResult()) {
                    // Drop was not accepted — restore the hidden source view before rebuilding.
                    // Guard: incoming drawer drags have no source view (dragSourceView is null).
                    if (dragSourceView != null) dragSourceView.setVisibility(View.VISIBLE);
                    if (isIncomingApp) {
                        // Incoming drawer drag with no accepted drop.
                        // If the user never moved past touchSlop, treat this as a
                        // long-press-without-move: notify MainActivity to show the context menu.
                        // dragMoved, not sawDragLocation: once the drawer is dismissed this view
                        // is under the finger and receives DRAG_LOCATION for a finger that has
                        // not gone anywhere, so "no location event" only ever described a drag
                        // the system cancelled — a real release in place always had one.
                        if (!dragMoved && incomingAppPkg != null && pullOutFolder == null
                                && drawerDragCancelledListener != null) {
                            final String cancelledPkg = incomingAppPkg;
                            drawerDragCancelledListener.onDrawerDragCancelled(cancelledPkg, null);
                        }
                    } else {
                        // sawDragLocation guards against system-cancelled drags (no DRAG_LOCATION
                        // delivered at all) being mistaken for deliberate release-without-move.
                        if (!dragMoved && sawDragLocation && draggedItem != null) {
                            // Long-press-without-movement on a widget → show resize frame after refresh.
                            if (draggedItem.getType() == HomeItem.Type.WIDGET) {
                                pendingResizeItem = draggedItem;
                            }
                            // Phase D: long-press-without-movement on an APP (homescreen or dock)
                            // → show deep-shortcut popup after refresh instead of just restoring.
                            // This keeps drag responsive (starts immediately on long-press) while
                            // the popup only appears when the user lifts without moving past slop.
                            else if (draggedItem.getType() == HomeItem.Type.APP) {
                                pendingShortcutItem = draggedItem;
                            }
                        }
                    }
                }
                endDrag();
                return true;
            }

            default:
                return true;
        }
    };

    /** One-line item summary for the {@link #DDBG} trace. */
    private static String describe(HomeItem item) {
        if (item == null) return "null";
        return item.getType() + (item.getType() == HomeItem.Type.WIDGET
                ? "(id=" + item.getAppWidgetId() + ")" : "(" + item.getPackageName() + ")");
    }

    /**
     * Updates {@link #dragMoved} if the pointer has moved past {@link #touchSlop}.
     *
     * <p>Compared in screen space, not view-local space, because this view can move under a
     * finger that has not: a drawer drag hides the toolbar above it the moment it starts. Only
     * the finger's own travel may count as movement — it is what decides between "drop it here"
     * and "show the menu".
     */
    private void updateDragMoved(float x, float y) {
        if (!dragMoved) {
            float mx = Math.abs(toScreenX(x) - dragStartX);
            float my = Math.abs(toScreenY(y) - dragStartY);
            if (mx > touchSlop || my > touchSlop) dragMoved = true;
        }
    }

    /** View-local x to screen x, against wherever this view is laid out right now. */
    private float toScreenX(float localX) {
        getLocationOnScreen(screenOrigin);
        return localX + screenOrigin[0];
    }

    private float toScreenY(float localY) {
        getLocationOnScreen(screenOrigin);
        return localY + screenOrigin[1];
    }

    /**
     * Handles edge-paging timer while drag is near the left or right edge.
     * Suppressed when the drag is hovering over the dock to avoid accidental page flips.
     * Also updates the drop-target preview via the shared {@link DropTarget#resolve} helper.
     */
    private void handleDragLocation(float x, float y) {
        // Update drop-target preview using the same resolver used by handleDrop.
        updateDropPreview(x, y);

        // Drag over the dock must not trigger page-edge flipping (spec Phase B).
        if (isInDockZone(y)) {
            if (pendingEdgeDir != 0) {
                removeCallbacks(edgePageTask);
                pendingEdgeDir = 0;
            }
            return;
        }

        int w       = getWidth();
        int edgePx  = dp(EDGE_DP);
        int newDir  = 0;

        if (x < edgePx && currentPage > 0) {
            newDir = -1;
        } else if (x > w - edgePx) {
            // Right edge: may flip to next page or create a new one (up to MAX_PAGES).
            if (currentPage < pageViews.size() - 1 || pageViews.size() < MAX_PAGES) {
                newDir = 1;
            }
        }

        if (newDir != pendingEdgeDir) {
            removeCallbacks(edgePageTask);
            pendingEdgeDir = newDir;
            if (newDir != 0) postDelayed(edgePageTask, EDGE_DELAY_MS);
        }
    }

    /** True when the drag y-coordinate (in HomescreenView space) is within the dock zone. */
    private boolean isInDockZone(float y) {
        int h       = getHeight();
        int dockTop = h - insetBottom - dp(DOT_AREA_DP) - dp(DOCK_HEIGHT_DP);
        int dockBot = h - insetBottom - dp(DOT_AREA_DP);
        return y >= dockTop && y <= dockBot;
    }

    private void flipEdgePage() {
        if (!isDragging) return;
        if (pendingEdgeDir == 1) {
            if (currentPage < pageViews.size() - 1) {
                currentPage++;
            } else if (pageViews.size() < MAX_PAGES) {
                // Create an empty visual page for the drag target.
                CellLayout newPage = new CellLayout(getContext());
                if (store != null) newPage.setGridDimensions(store.columns(), store.rows());
                addView(newPage, 0); // index 0 so non-CellLayout overlays stay at end
                pageViews.add(newPage);
                currentPage = pageViews.size() - 1;
                requestLayout();
            }
        } else if (pendingEdgeDir == -1 && currentPage > 0) {
            currentPage--;
        }
        scroller.startScroll(getScrollX(), 0, currentPage * getWidth() - getScrollX(), 0, SNAP_MS);
        pendingEdgeDir = 0;
        postInvalidate();
    }

    /**
     * Resolves (x, y) — DragEvent coordinates in HomescreenView screen-local space — to a
     * {@link DropTarget} using the shared {@link DropTarget#resolve} helper. Both
     * {@link #handleDragLocation} (preview) and {@link #handleDrop} (commit) call this so they
     * cannot disagree on which zone or cell the pointer maps to.
     */
    private DropTarget resolveTarget(float x, float y) {
        int spanX = draggedItem != null ? draggedItem.getSpanX() : 1;
        int spanY = draggedItem != null ? draggedItem.getSpanY() : 1;
        return resolveTarget(x, y, spanX, spanY, grabCellX, grabCellY, actionBarLayout());
    }

    private DropTarget resolveTarget(float x, float y, int spanX, int spanY, int grabX, int grabY,
                                     DropTarget.ActionBar bar) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return DropTarget.outside();
        int pageH    = pageHeight(h);
        int dockTop  = h - insetBottom - dp(DOT_AREA_DP) - dp(DOCK_HEIGHT_DP);
        int dockBot  = h - insetBottom - dp(DOT_AREA_DP);
        int cols     = store != null ? store.columns() : HomeScreenStore.DEFAULT_COLS;
        int rows     = store != null ? store.rows()    : HomeScreenStore.DEFAULT_ROWS;
        return DropTarget.resolve(
                x, y,
                getScrollX(), w, CellLayout.pagePadPx(getContext()), insetTop, Math.max(1, pageH),
                cols, rows, spanX, spanY,
                dockTop, dockBot, dp(DOCK_MARGIN_DP),
                bar, grabX, grabY);
    }

    /**
     * Records which cell of {@code draggedItem} the finger started in, so the preview and the
     * drop commit the item's origin relative to it rather than putting the origin under the
     * finger (see {@code DropTarget.resolve}'s grab parameters). Zero for anything 1x1, and
     * for a start point that does not resolve onto the item's own page.
     */
    private void captureGrabCell(float startX, float startY) {
        grabCellX = grabCellY = 0;
        if (draggedItem == null || draggedItem.getScreen() == HomeItem.DOCK) return;
        if (draggedItem.getSpanX() <= 1 && draggedItem.getSpanY() <= 1) return;
        // The pointer's own cell: span 1x1, no grab, no action bar (the finger is on the item).
        DropTarget pointer = resolveTarget(startX, startY, 1, 1, 0, 0, DropTarget.ActionBar.NONE);
        if (pointer.zone != DropTarget.Zone.GRID || pointer.page != draggedItem.getScreen()) return;
        grabCellX = Math.max(0, Math.min(draggedItem.getSpanX() - 1,
                pointer.cellX - draggedItem.getCellX()));
        grabCellY = Math.max(0, Math.min(draggedItem.getSpanY() - 1,
                pointer.cellY - draggedItem.getCellY()));
        if (DDBG) Log.d(DTAG, "  grab cell [" + grabCellX + "," + grabCellY + "] of "
                + describe(draggedItem) + " (pointer cell [" + pointer.cellX + ","
                + pointer.cellY + "], origin [" + draggedItem.getCellX() + ","
                + draggedItem.getCellY() + "])");
    }

    /**
     * Dispatches a confirmed drop using the resolved {@link DropTarget}. Both the remove-zone,
     * dock, and grid paths delegate coordinate math to {@link #resolveTarget}, which uses
     * {@link DropTarget#resolve} — the same helper consulted during preview updates.
     *
     * <p>Incoming drawer-app drops are routed through {@link DrawerDropDecider} and committed
     * without modifying any existing item position (they only add or merge).
     */
    private boolean handleDrop(float x, float y) {
        if (draggedItem == null || store == null) {
            if (DDBG) Log.d(DTAG, "  handleDrop: no drag in flight (item=" + describe(draggedItem)
                    + " store=" + (store != null) + ")");
            return false;
        }

        // Incoming drawer-app drop: use DrawerDropDecider and commit without a "source" item.
        if (isIncomingApp && incomingAppPkg != null) {
            if (DDBG) Log.d(DTAG, "  handleDrop: INCOMING-APP branch, pkg=" + incomingAppPkg
                    + " pullOutFolder=" + (pullOutFolder != null)
                    + " — wrong branch for a home-screen item, and Remove refuses it");
            // The same gate the home-screen items have. A drawer long-press starts the drag at
            // once and dismisses the drawer, so by the time the finger lifts this view is under
            // it and the drop lands in whatever cell that is. Without the gate a long-press
            // released in place — the gesture that is meant to open the app's menu — added the
            // app to the home screen instead, and the menu was unreachable from the drawer.
            // Refusing here makes ACTION_DRAG_ENDED arrive with result=false, which is where the
            // menu hand-off lives. (A folder pull-out sets dragMoved itself, see
            // beginFolderPullOut, so it is unaffected.)
            if (!dragMoved) {
                if (DDBG) Log.d(DTAG, "  handleDrop: released without moving past slop — refused"
                        + " so DRAG_ENDED routes it to the app menu, not onto the grid");
                return false;
            }
            return handleIncomingAppDrop(x, y);
        }

        if (!canCommitDrop(store.getItems(), draggedItem)) {
            if (DDBG) Log.d(DTAG, "  handleDrop: item is no longer in the store — snapping back");
            return false; // snap back
        }

        DropTarget target = resolveTarget(x, y);
        if (DDBG) Log.d(DTAG, "  handleDrop: zone=" + target.zone + " page=" + target.page
                + " cell=[" + target.cellX + "," + target.cellY + "]");

        switch (target.zone) {
            case REMOVE:
                // Guarded, like the identical pairing in populatePage. There it was "a throwing
                // OEM host must not cost the user their whole home screen"; here it is worse than
                // that. An escaping RuntimeException unwinds out of ACTION_DROP *before*
                // removeItem, so the drop is never committed and the widget silently survives —
                // the Remove pill simply appears to do nothing, with no crash to explain it. A
                // leaked host id is by far the cheaper failure.
                if (draggedItem.getType() == HomeItem.Type.WIDGET && host != null) {
                    try {
                        host.deleteAppWidgetId(draggedItem.getAppWidgetId());
                    } catch (RuntimeException e) {
                        Log.w(TAG, "deleteAppWidgetId failed for "
                                + draggedItem.getAppWidgetId(), e);
                    }
                }
                store.removeItem(draggedItem);
                if (DDBG) Log.d(DTAG, "  REMOVE: committed; store now holds "
                        + store.getItems().size() + " item(s)");
                return true; // endDrag() called from ACTION_DRAG_ENDED with result=true

            case UNINSTALL:
                // Hand off to the system uninstaller. The item deliberately stays on the home
                // screen: the user has not confirmed yet, and MainActivity's package receiver
                // calls removePackage() if and when the uninstall actually completes.
                startUninstall(draggedItem.getPackageName());
                return true;

            case DISABLE:
                // Same reasoning as UNINSTALL: App info is where the user confirms, and the
                // package receiver hears the disable.
                AppRemoval.openDisable(getContext(), draggedItem.getPackageName());
                return true;

            case DOCK:
                return handleDockDropAt(target.cellX);

            case GRID: {
                CellLayout page = getPage(target.page);
                if (page == null) return false;
                return handleGridDropAt(target.page, target.cellX, target.cellY);
            }

            default:
                return false; // OUTSIDE — snap back
        }
    }

    /**
     * Whether a drop for {@code draggedItem} may still be committed against {@code storeItems}.
     *
     * <p>A background install or uninstall completing mid-drag runs
     * {@link HomeScreenStore#removePackage} from MainActivity's package receiver, which can delete
     * the very item under the finger. Committing anyway either moves an orphan — {@code moveItem}
     * silently does nothing but {@code handleDrop} returns true, so the icon just disappears — or
     * takes the merge branch and writes a folder whose member is a package that no longer exists.
     * Snapping back is the only honest answer; {@code endDrag()}'s refresh then shows the truth.
     *
     * <p>Incoming drawer drags are exempt at the call site: their {@code draggedItem} is a
     * synthetic placeholder that was never in the store to begin with.
     */
    static boolean canCommitDrop(List<HomeItem> storeItems, HomeItem draggedItem) {
        return draggedItem != null && storeItems != null && storeItems.contains(draggedItem);
    }

    /**
     * Commits a drop of the incoming app — one that is not (yet) a home-screen item, so there is
     * no source item to move. Resolves the zone/cell and hands the store mutation to
     * {@link #commitIncomingDrop}.
     *
     * <p>Uninstall stays here rather than in the shared helper: it is the one zone that touches
     * the system instead of the store, and it deliberately changes nothing on the home screen —
     * the user has not confirmed yet. For a folder pull-out that means the member stays in its
     * folder until MainActivity's package receiver sees the uninstall actually complete.
     */
    private boolean handleIncomingAppDrop(float x, float y) {
        if (store == null || incomingAppPkg == null) return false;
        // Resolve using span 1×1 (an incoming app is always a 1×1 shortcut).
        DropTarget target = resolveTarget(x, y);

        if (target.zone == DropTarget.Zone.UNINSTALL) {
            startUninstall(incomingAppPkg);
            return true;
        }
        if (target.zone == DropTarget.Zone.DISABLE) {
            AppRemoval.openDisable(getContext(), incomingAppPkg);
            return true;
        }
        return commitIncomingDrop(store, target, incomingAppPkg, pullOutFolder);
    }

    /**
     * Applies an incoming-app drop to the store.
     *
     * <p>Package-private and store-only — no view or drag state — so every placement rule can be
     * exercised without a live drag, which is the only way these branches get tested at all.
     *
     * @param sourceFolder the folder the app is being dragged out of, or null when it came from
     *                     the drawer. Non-null makes the drop a <em>move</em>: the member is
     *                     removed from the folder once, and only once, it has landed somewhere.
     * @return true if the drop is committed; false to snap back
     */
    static boolean commitIncomingDrop(HomeScreenStore store, DropTarget target,
                                      String pkg, HomeItem sourceFolder) {
        if (store == null || target == null || pkg == null) return false;

        // Remove means "out of the folder, and off the home screen" for a pull-out. A drawer app
        // was never on the home screen, so the pill has nothing to mean there.
        if (target.zone == DropTarget.Zone.REMOVE) {
            if (sourceFolder == null) return false;
            store.removeFromFolder(sourceFolder, pkg);
            return true;
        }
        if (target.zone == DropTarget.Zone.GRID && target.page < 0) return false;

        HomeItem occupant            = occupantAt(store, target);
        DrawerDropDecider.Action act = incomingAction(target.zone, occupant, pkg, sourceFolder);
        int destScreen = (target.zone == DropTarget.Zone.DOCK) ? HomeItem.DOCK : target.page;
        int destY      = (target.zone == DropTarget.Zone.DOCK) ? 0 : target.cellY;

        switch (act) {
            case ADD:
                store.addItem(HomeItem.app(destScreen, target.cellX, destY, pkg));
                break;
            case FOLDER_MERGE: {
                // occupant is a non-null APP.
                List<String> members = new ArrayList<>(Arrays.asList(
                        occupant.getPackageName(), pkg));
                store.removeItem(occupant);
                store.addItem(HomeItem.folder(destScreen, target.cellX, destY, "Folder", members));
                break;
            }
            case FOLDER_APPEND:
                // occupant is a non-null FOLDER.
                store.addToFolder(occupant, pkg);
                break;
            default:
                return false; // REJECT — snap back
        }

        // Landed: only now does the member leave its folder. The other order would collapse a
        // two-member folder before the drop is known to be legal, and a refused drop would then
        // have destroyed the folder it snapped back into.
        if (sourceFolder != null) store.removeFromFolder(sourceFolder, pkg);
        return true;
    }

    /** Item occupying a resolved GRID or DOCK target, or null (also null for other zones). */
    static HomeItem occupantAt(HomeScreenStore store, DropTarget target) {
        if (store == null || target == null) return null;
        if (target.zone == DropTarget.Zone.GRID) {
            return target.page < 0 ? null : store.itemAt(target.page, target.cellX, target.cellY);
        }
        if (target.zone == DropTarget.Zone.DOCK) return store.itemAt(HomeItem.DOCK, target.cellX, 0);
        return null;
    }

    /**
     * {@link DrawerDropDecider#decide} with the one rule that only a folder pull-out needs:
     * dropping the member back onto the folder it came from changes nothing.
     *
     * <p>Without it the decider says FOLDER_APPEND, {@code addToFolder}'s duplicate guard makes
     * that a no-op, and the removal that follows a committed drop then deletes the member from
     * the only folder it was ever in. {@code sourceFolder} is null for a drawer drag, which makes
     * this the decider verbatim.
     */
    static DrawerDropDecider.Action incomingAction(DropTarget.Zone zone, HomeItem occupant,
                                                   String pkg, HomeItem sourceFolder) {
        if (sourceFolder != null && occupant == sourceFolder) return DrawerDropDecider.Action.REJECT;
        return DrawerDropDecider.decide(zone, occupant, pkg);
    }

    /**
     * Launches the system uninstall confirmation for {@code packageKey} (UserKeys composite form).
     * Nothing is removed from the store here — MainActivity's package receiver calls
     * {@code removePackage} if and when the user actually confirms.
     */
    void startUninstall(String packageKey) {
        AppRemoval.startUninstall(getContext(), packageKey);
    }

    /**
     * Commits a drop onto a resolved grid cell. WIDGET items trigger a resize frame after rebuild.
     * APP items may merge with an existing APP (→ folder) or append to an existing FOLDER.
     */
    private boolean handleGridDropAt(int dropPage, int cellX, int cellY) {
        int spanX = draggedItem.getSpanX();
        int spanY = draggedItem.getSpanY();

        // Same position as origin — snap back.
        if (dropPage == draggedItem.getScreen()
                && cellX == draggedItem.getCellX()
                && cellY == draggedItem.getCellY()) {
            return false;
        }

        // Check if the entire target area is free (excluding the dragged item itself,
        // so partial overlaps with its own origin are allowed).
        if (store.isAreaFreeExcluding(dropPage, cellX, cellY, spanX, spanY, draggedItem)) {
            store.moveItem(draggedItem, dropPage, cellX, cellY);
            // Deliberately no pendingResizeItem here. This used to open the resize frame after
            // every widget move, so a widget dragged onto another page landed with the frame
            // already up — the next drag on it was then a frame-body move rather than a drag,
            // and dismissing the frame looked like the widget vanishing. The frame is the
            // long-press-without-movement gesture's (see ACTION_DRAG_ENDED); a drop that moved
            // is not that gesture.
            return true;
        }

        // Area not fully free — only APP+APP→folder and APP+FOLDER→append are valid merges.
        // Widgets dropped onto occupied cells, and anything dropped onto a WIDGET, snap back.
        if (spanX == 1 && spanY == 1 && draggedItem.getType() == HomeItem.Type.APP) {
            HomeItem existing = store.itemAt(dropPage, cellX, cellY);
            if (existing != null && existing.getType() == HomeItem.Type.APP) {
                // Same package dropped onto itself → snap back.
                if (existing.getPackageName().equals(draggedItem.getPackageName())) return false;
                // APP onto APP → create folder at the target cell.
                List<String> members = new ArrayList<>(Arrays.asList(
                        existing.getPackageName(), draggedItem.getPackageName()));
                HomeItem folder = HomeItem.folder(dropPage, cellX, cellY, "Folder", members);
                store.removeItem(draggedItem);
                store.removeItem(existing);
                store.addItem(folder);
                return true;
            }
            if (existing != null && existing.getType() == HomeItem.Type.FOLDER) {
                // APP onto FOLDER → add to folder.
                store.removeItem(draggedItem);
                store.addToFolder(existing, draggedItem.getPackageName());
                return true;
            }
        }
        return false; // snap back
    }

    /**
     * Commits a drop onto a resolved dock slot. WIDGET items always snap back. APP items may merge
     * with an existing dock APP (→ folder) or append to an existing dock FOLDER. FOLDERs may be
     * placed in a free dock slot but do not merge with other items.
     */
    private boolean handleDockDropAt(int slot) {
        if (draggedItem.getType() == HomeItem.Type.WIDGET) return false; // snap back

        // Same slot as origin — snap back.
        if (draggedItem.getScreen() == HomeItem.DOCK && slot == draggedItem.getCellX()) {
            return false;
        }

        if (store.isAreaFreeExcluding(HomeItem.DOCK, slot, 0, 1, 1, draggedItem)) {
            store.moveItem(draggedItem, HomeItem.DOCK, slot, 0);
            return true;
        }

        // Slot occupied — only APP merges are supported.
        if (draggedItem.getType() == HomeItem.Type.APP) {
            HomeItem existing = store.itemAt(HomeItem.DOCK, slot, 0);
            if (existing != null && existing.getType() == HomeItem.Type.APP) {
                if (existing.getPackageName().equals(draggedItem.getPackageName())) return false;
                // APP onto APP → create folder at the dock slot.
                List<String> members = new ArrayList<>(Arrays.asList(
                        existing.getPackageName(), draggedItem.getPackageName()));
                store.removeItem(draggedItem);
                store.removeItem(existing);
                store.addItem(HomeItem.folder(HomeItem.DOCK, slot, 0, "Folder", members));
                return true;
            }
            if (existing != null && existing.getType() == HomeItem.Type.FOLDER) {
                // APP onto FOLDER → append to folder.
                store.removeItem(draggedItem);
                store.addToFolder(existing, draggedItem.getPackageName());
                return true;
            }
        }
        return false; // snap back
    }

    // -------------------------------------------------------------------------
    // Drop-target preview — visual feedback during drag
    // -------------------------------------------------------------------------

    /**
     * Recomputes the preview target and, if it changed, schedules a redraw.
     * Called from {@link #handleDragLocation} so previews update every pointer event.
     */
    private void updateDropPreview(float x, float y) {
        if (draggedItem == null || store == null) { clearDropPreview(); return; }
        DropTarget newTarget = resolveTarget(x, y);
        boolean newIsValid   = computePreviewValid(newTarget);
        if (!targetsEqual(previewTarget, newTarget) || newIsValid != previewIsValid) {
            previewTarget  = newTarget;
            previewIsValid = newIsValid;
            invalidate();
        }
    }

    /**
     * Test-only: {page, cellX, cellY} of the grid cell the drop preview is outlining, or null
     * when no valid grid preview is showing. The outline is the only thing that tells the user
     * where a multi-cell item will land before they let go, so it has to agree with the drop.
     */
    int[] dropPreviewCellForTest() {
        if (previewTarget == null || !previewIsValid
                || previewTarget.zone != DropTarget.Zone.GRID) return null;
        return new int[]{previewTarget.page, previewTarget.cellX, previewTarget.cellY};
    }

    /** Hides the drop-target preview and triggers a redraw if one was visible. */
    private void clearDropPreview() {
        if (previewTarget != null || previewIsValid) {
            previewTarget  = null;
            previewIsValid = false;
            invalidate();
        }
    }

    /**
     * Returns true when {@code target} should be drawn as a valid drop location.
     * Valid means either the target area is free (regular move) or the combination
     * is a supported merge (APP onto APP or APP onto FOLDER). All other occupancy
     * states (e.g. widget onto widget) are invalid and produce no preview.
     *
     * <p>For incoming-app drags the placeholder item is not in the store, so we ask
     * {@link #incomingAction} — the same call {@link #commitIncomingDrop} makes — instead of the
     * isAreaFreeExcluding path. Sharing it is what stops a cell being outlined as a valid drop
     * that the commit then refuses, the source folder's own cell being the case that differs.
     */
    private boolean computePreviewValid(DropTarget target) {
        if (target == null || store == null || draggedItem == null) return false;

        // Incoming app drag: consult the decider (never wrongly includes the placeholder).
        if (isIncomingApp && incomingAppPkg != null) {
            if (target.zone != DropTarget.Zone.GRID && target.zone != DropTarget.Zone.DOCK) {
                return false;
            }
            if (target.zone == DropTarget.Zone.GRID && target.page < 0) return false;
            return incomingAction(target.zone, occupantAt(store, target),
                                  incomingAppPkg, pullOutFolder)
                    != DrawerDropDecider.Action.REJECT;
        }

        if (target.zone == DropTarget.Zone.GRID) {
            if (target.page < 0) return false;
            return store.isAreaFreeExcluding(
                    target.page, target.cellX, target.cellY,
                    draggedItem.getSpanX(), draggedItem.getSpanY(), draggedItem)
                    || isMergeTarget(target.page, target.cellX, target.cellY);
        }
        if (target.zone == DropTarget.Zone.DOCK) {
            return draggedItem.getType() != HomeItem.Type.WIDGET
                    && (store.isAreaFreeExcluding(HomeItem.DOCK, target.cellX, 0, 1, 1, draggedItem)
                            || isDockMergeTarget(target.cellX));
        }
        return false; // REMOVE and OUTSIDE do not show a cell preview
    }

    /** True if dragging an APP over (page, cellX, cellY) would trigger a folder merge. */
    private boolean isMergeTarget(int page, int cellX, int cellY) {
        if (draggedItem == null || store == null) return false;
        if (draggedItem.getSpanX() != 1 || draggedItem.getSpanY() != 1) return false;
        if (draggedItem.getType() != HomeItem.Type.APP) return false;
        HomeItem existing = store.itemAt(page, cellX, cellY);
        if (existing == null) return false;
        if (existing.getType() == HomeItem.Type.APP) {
            return !existing.getPackageName().equals(draggedItem.getPackageName());
        }
        return existing.getType() == HomeItem.Type.FOLDER;
    }

    /** True if dragging an APP onto dock {@code slot} would trigger a folder merge. */
    private boolean isDockMergeTarget(int slot) {
        if (draggedItem == null || store == null) return false;
        if (draggedItem.getType() != HomeItem.Type.APP) return false;
        HomeItem existing = store.itemAt(HomeItem.DOCK, slot, 0);
        if (existing == null) return false;
        if (existing.getType() == HomeItem.Type.APP) {
            return !existing.getPackageName().equals(draggedItem.getPackageName());
        }
        return existing.getType() == HomeItem.Type.FOLDER;
    }

    /** Returns true only when both targets resolve to the same zone, page, and cell. */
    private static boolean targetsEqual(DropTarget a, DropTarget b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.zone == b.zone && a.page == b.page && a.cellX == b.cellX && a.cellY == b.cellY;
    }

    /**
     * Draws an accent-coloured rounded-rect outline at the current preview target cell.
     * Called from {@link #dispatchDraw} only when {@link #previewIsValid} is true.
     *
     * <p>Canvas convention (confirmed by {@link #drawDots} and {@link #drawRemoveZone}):
     * canvas x = content x = screen x + scrollX. Fixed on-screen elements add scrollX;
     * grid cells are already in content coordinates so no adjustment is needed.
     */
    private void drawDropPreview(Canvas canvas) {
        if (!previewIsValid || previewTarget == null || draggedItem == null || store == null) return;

        previewPaint.setStrokeWidth(dp(2));
        int scrollX = getScrollX();
        int w       = getWidth();
        int h       = getHeight();
        int cols    = store.columns();
        int rows    = store.rows();
        int pageH   = pageHeight(h);
        float inset  = dp(2);
        float corner = dp(8);

        if (previewTarget.zone == DropTarget.Zone.GRID) {
            if (previewTarget.page < 0 || rows <= 0 || cols <= 0) return;
            int gridPad = CellLayout.pagePadPx(getContext());
            int cellW = CellLayout.cellWidth(w, cols, gridPad);
            int cellH = Math.max(1, pageH) / rows;
            if (cellW <= 0 || cellH <= 0) return;

            int spanX = draggedItem.getSpanX();
            int spanY = draggedItem.getSpanY();
            // Content coordinates: page starts at page*w, rows start below the status bar.
            float left   = previewTarget.page * (float) w
                    + CellLayout.cellLeft(previewTarget.cellX, cellW, gridPad);
            float top    = insetTop + previewTarget.cellY * (float) cellH;
            float right  = left + spanX * cellW;
            float bottom = top  + spanY * cellH;
            previewRect.set(left + inset, top + inset, right - inset, bottom - inset);
            canvas.drawRoundRect(previewRect, corner, corner, previewPaint);

        } else if (previewTarget.zone == DropTarget.Zone.DOCK) {
            int mrgPx   = dp(DOCK_MARGIN_DP);
            int dockW   = w - 2 * mrgPx;
            if (dockW <= 0 || cols <= 0) return;
            int slotW   = dockW / cols;
            int dockTop = h - insetBottom - dp(DOT_AREA_DP) - dp(DOCK_HEIGHT_DP);
            // Dock is screen-fixed, so add scrollX to stay in canvas (content) coordinates.
            float left   = scrollX + mrgPx + previewTarget.cellX * (float) slotW;
            float top    = dockTop;
            float right  = left + slotW;
            float bottom = top + dp(DOCK_HEIGHT_DP);
            previewRect.set(left + inset, top + inset, right - inset, bottom - inset);
            canvas.drawRoundRect(previewRect, corner, corner, previewPaint);
        }
    }

    // -------------------------------------------------------------------------
    // Folder popup
    // -------------------------------------------------------------------------

    /** Opens a centered folder popup over a dimmed scrim. */
    public void openFolderPopup(HomeItem folder) {
        if (folderPopupOverlay != null) closeFolderPopup();
        openFolder = folder;

        FrameLayout overlay = new FrameLayout(getContext());
        folderPopupOverlay = overlay;

        // Scrim
        View scrim = new View(getContext());
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> closeFolderPopup());
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Card. Sized from the display rather than a fixed 300×400 dp: at 4 columns a fixed
        // 300 dp card left the grid 268 dp of content for 4 × 76 dp of cells, so the last column
        // was clipped. Cap the width so it doesn't sprawl on large screens.
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int cardW = folderCardWidth();
        int cardH = Math.min(dp(FOLDER_CARD_MAX_H_DP),
                Math.round((dm.heightPixels - insetTop - insetBottom) * 0.65f));
        LinearLayout card = buildFolderCard(folder, overlay, cardW);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(cardW, cardH);
        cardLp.gravity = Gravity.CENTER;
        overlay.addView(card, cardLp);
        overlay.setOnDragListener(folderPullOutListener(card));

        addView(overlay);
        requestLayout();
    }

    /**
     * Folder card width in px: the display width less a margin on each side, capped. Derived
     * rather than remembered so {@link #refreshFolderPopupGrid} recomputes the identical value
     * without depending on the card having been laid out yet.
     */
    private int folderCardWidth() {
        return Math.min(dp(FOLDER_CARD_MAX_W_DP),
                getResources().getDisplayMetrics().widthPixels - 2 * dp(FOLDER_CARD_MARGIN_DP));
    }

    private LinearLayout buildFolderCard(HomeItem folder, FrameLayout overlay,
                                          int cardWidthPx) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF1E1E1E);
        cardBg.setCornerRadius(dp(20));
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        int pad = dp(FOLDER_CARD_PAD_DP);
        card.setPadding(pad, pad, pad, pad);

        // Folder name EditText
        EditText nameEdit = new EditText(getContext());
        nameEdit.setText(folder.getFolderName() != null ? folder.getFolderName() : "Folder");
        nameEdit.setTextColor(0xFFFFFFFF);
        nameEdit.setHintTextColor(0x88FFFFFF);
        nameEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        nameEdit.setGravity(Gravity.CENTER);
        nameEdit.setBackground(null);
        nameEdit.setSingleLine(true);
        // Enter commits the name and puts the keyboard away. A single-line field's default
        // action is NEXT, which only moved focus to the first member cell with the keyboard
        // still up and the name still uncommitted — nothing told the user the edit had taken.
        nameEdit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameEdit.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            commitFolderName();
            return true;
        });
        folderNameEdit = nameEdit;
        card.addView(nameEdit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Divider
        View divider = new View(getContext());
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(8);
        divLp.bottomMargin = dp(8);
        card.addView(divider, divLp);

        // Scrollable grid of member apps
        ScrollView scroll = new ScrollView(getContext());
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        GridLayout grid = buildMemberGrid(folder, overlay, cardWidthPx);
        scroll.addView(grid);
        card.addView(scroll);

        return card;
    }

    /**
     * Width of one member cell so that {@code cols} of them, each with {@code marginPx} on both
     * sides, fit exactly inside a card of {@code cardWidthPx} with {@code cardPadPx} padding.
     *
     * <p>The old code hardcoded a 72 dp cell against a fixed 300 dp card: 4 × (72 + 2 + 2) = 304 dp
     * of cells in 268 dp of content, so the fourth icon in every row was clipped. Deriving the cell
     * from the card makes that impossible whatever the card or column count becomes.
     *
     * <p>Package-private for unit tests.
     */
    static int folderCellWidth(int cardWidthPx, int cardPadPx, int cols, int marginPx) {
        if (cols <= 0) return 0;
        int content = cardWidthPx - 2 * cardPadPx;
        return Math.max(1, content / cols - 2 * marginPx);
    }

    /**
     * Builds the member grid, sizing cells from the card so every column fits (see
     * {@link #folderCellWidth}).
     *
     * <p>Long-pressing a member starts a reorder drag; the grid's drag listener resolves the
     * pointer to a member index and commits via {@link HomeScreenStore#reorderFolder}.
     */
    private GridLayout buildMemberGrid(HomeItem folder, FrameLayout overlay, int cardWidthPx) {
        // Both build paths — opening the popup and rebuilding its grid in place — come through
        // here, so this is the one place that can drop the bindings for the cells being replaced.
        popupBadgeBindings.clear();

        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(FOLDER_COLS);
        grid.setPadding(0, dp(4), 0, dp(4));
        int cellMargin = dp(2);
        int cellW = folderCellWidth(cardWidthPx, dp(FOLDER_CARD_PAD_DP), FOLDER_COLS, cellMargin);

        // Only members whose package is installed get a cell, so the view index and the model
        // index can diverge; keep the mapping explicit for the reorder hit test.
        final List<Integer> modelIndexOfCell = new ArrayList<>();

        List<String> pkgs = folder.getPackageNames();
        for (int i = 0; i < pkgs.size(); i++) {
            String pkg = pkgs.get(i);
            AppInfo info = appMap.get(pkg);
            if (info == null) continue;

            LinearLayout cell = buildPopupAppCell(info, pkg, folder, overlay, /*modelIndex=*/ i);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width  = cellW;
            lp.height = dp(FOLDER_CELL_H_DP);
            lp.setMargins(cellMargin, dp(4), cellMargin, dp(4));
            grid.addView(cell, lp);
            modelIndexOfCell.add(i);
        }
        grid.setOnDragListener(folderReorderListener(grid, folder, overlay, modelIndexOfCell));
        return grid;
    }

    /**
     * Drag listener for the folder member grid. Accepts only drags this popup started (local
     * state is a {@link FolderDragState} for the open folder) and, on drop, moves the dragged
     * member to the index of the cell under the pointer.
     */
    private OnDragListener folderReorderListener(GridLayout grid, HomeItem folder,
                                                 FrameLayout overlay,
                                                 List<Integer> modelIndexOfCell) {
        return (v, event) -> {
            Object local = event.getLocalState();
            if (!(local instanceof FolderDragState)) return false;
            FolderDragState state = (FolderDragState) local;
            if (state.folder != folder) return false;

            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;

                case DragEvent.ACTION_DRAG_LOCATION:
                    state.noteMoved(event.getX(), event.getY(), touchSlop);
                    return true;

                case DragEvent.ACTION_DROP: {
                    int cell = cellIndexAt(grid, event.getX(), event.getY());
                    if (cell < 0 || cell >= modelIndexOfCell.size()) return false;
                    if (store != null
                            && store.reorderFolder(folder, state.modelIndex,
                                                   modelIndexOfCell.get(cell))) {
                        folderOrderChanged = true; // page chips refresh when the popup closes
                        // Deferred: rebuilding the grid here would swap out the very view that is
                        // still dispatching this drag (and that must still receive DRAG_ENDED).
                        post(() -> refreshFolderPopupGrid(overlay, folder));
                    }
                    return true;
                }

                case DragEvent.ACTION_DRAG_ENDED:
                    // The popup is gone — the member was dragged out of it (see
                    // folderPullOutListener). There is no cell left to restore and no anchor for
                    // a menu; the home-screen drag owns what happens next.
                    if (folderPopupOverlay == null) return true;
                    // Always un-hide the source cell, not just on a refused drop: dropping a
                    // member back onto itself is an accepted drop that reorders nothing and
                    // schedules no rebuild, and the cell would stay invisible forever.
                    if (state.sourceView != null) state.sourceView.setVisibility(View.VISIBLE);
                    // Released without moving — treat as a plain long-press on the member.
                    // (Runs after the restore above so the menu has a visible anchor.)
                    if (!event.getResult() && !state.moved && state.onPressWithoutMove != null) {
                        state.onPressWithoutMove.run();
                    }
                    return true;

                default:
                    return true;
            }
        };
    }

    /**
     * Drag listener on the folder popup's full-screen overlay — the "drag a member out of the
     * folder" half of a member drag, where {@link #folderReorderListener} is the "reorder inside
     * it" half.
     *
     * <p>The split falls out of how {@code ViewGroup} routes drag events: the member grid is the
     * frontmost droppable child over the card, so it takes every event the pointer delivers
     * there, and the overlay hears from the framework only once the pointer is somewhere else.
     * That is exactly the gesture that means "take this app out".
     *
     * <p>Handing the drag over is a two-step, and both steps are load-bearing. The popup is torn
     * down — posted, never inline: mutating the view tree while it is still dispatching this very
     * event is what crashes {@code dispatchDragEvent}, the same trap the drawer dismissal is
     * posted around. And {@link #homeDragListener} accepted this drag back at
     * ACTION_DRAG_STARTED, which is the only reason this view is still receiving events once the
     * overlay it would otherwise have routed them to is gone.
     */
    private OnDragListener folderPullOutListener(View card) {
        return (v, event) -> {
            if (!(event.getLocalState() instanceof FolderDragState)) return false;
            FolderDragState state = (FolderDragState) event.getLocalState();
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return true;

                case DragEvent.ACTION_DRAG_LOCATION:
                    state.noteMoved(event.getX(), event.getY(), touchSlop);
                    if (isPullOut(card.getLeft(), card.getTop(), card.getRight(), card.getBottom(),
                                  event.getX(), event.getY(), touchSlop)) {
                        beginFolderPullOut(state);
                    }
                    return true;

                case DragEvent.ACTION_DROP:
                    // Only reachable if a release beat the posted teardown. Overlays are laid out
                    // at (0,0,w,h) and translated by the scroll offset (see onLayout), so the
                    // overlay's local coordinates are this view's and handleDrop takes them as is.
                    return pullOutFolder != null && handleDrop(event.getX(), event.getY());

                default:
                    return true;
            }
        };
    }

    /**
     * True when a pointer at (x, y) has left the folder card by more than {@code slop}.
     *
     * <p>The slop is the whole point: without it a member dragged along the card's own edge
     * flickers in and out of the folder, and pulling out is one-way — the popup is gone by the
     * time the finger could come back.
     *
     * <p>Package-private for unit tests; coordinates are in the overlay's space, which is this
     * view's (see {@link #folderPullOutListener}).
     */
    static boolean isPullOut(int cardLeft, int cardTop, int cardRight, int cardBottom,
                             float x, float y, int slop) {
        return x < cardLeft - slop || x > cardRight + slop
                || y < cardTop - slop || y > cardBottom + slop;
    }

    /**
     * Converts an in-folder member drag into an incoming-app drag on the home screen, and closes
     * the popup behind it.
     *
     * <p>The member is deliberately <em>not</em> removed from the folder here. A drag that is
     * refused or released over nothing has to leave the folder exactly as it was, so the removal
     * belongs with the commit — see {@link #commitIncomingDrop}.
     */
    private void beginFolderPullOut(FolderDragState state) {
        if (pullOutFolder != null) return;     // already handed over
        if (store == null || state.packageKey == null) return;
        pullOutFolder   = state.folder;
        incomingAppPkg  = state.packageKey;
        isIncomingApp   = true;
        // Synthesise a 1×1 APP placeholder so the existing preview and drop math work.
        draggedItem     = HomeItem.app(0, 0, 0, state.packageKey);
        dragSourceView  = null;                // the source cell dies with the popup
        isDragging      = true;
        dragActions     = computeAvailableActions();
        // Leaving the card is movement by definition: without this a release straight after the
        // hand-off reads as a long-press-without-move and reopens a popup over the drop — both
        // this view's (dragMoved) and the popup's own (state.moved), whose noteMoved has only
        // ever seen one point if the finger left the card in a single event.
        state.moved     = true;
        dragMoved       = true;
        sawDragLocation = true;
        invalidate();  // show the action bar
        // Posted for the reason spelled out on folderPullOutListener: removing the overlay while
        // ViewGroup is still dispatching this event crashes in dispatchDragEvent.
        post(this::closeFolderPopup);
    }

    /** Index of the grid child whose bounds contain (x, y), or -1. */
    static int cellIndexAt(GridLayout grid, float x, float y) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            if (x >= c.getLeft() && x < c.getRight() && y >= c.getTop() && y < c.getBottom()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Local drag state for an in-folder reorder. Its presence as {@code DragEvent.getLocalState()}
     * is what distinguishes a folder drag from a home-screen or drawer drag, so
     * {@link #homeDragListener} correctly ignores it.
     */
    private static final class FolderDragState {
        final HomeItem folder;
        final int      modelIndex;
        /** Composite package key (UserKeys scheme) of the member being dragged. */
        final String   packageKey;
        final View     sourceView;
        /** Runs on DRAG_ENDED when the pointer never moved — shows the member menu. */
        final Runnable onPressWithoutMove;

        private boolean hasOrigin = false;
        private float   originX, originY;
        boolean moved = false;

        FolderDragState(HomeItem folder, int modelIndex, String packageKey, View sourceView,
                        Runnable onPressWithoutMove) {
            this.folder             = folder;
            this.modelIndex         = modelIndex;
            this.packageKey         = packageKey;
            this.sourceView         = sourceView;
            this.onPressWithoutMove = onPressWithoutMove;
        }

        /** Latches {@link #moved} once the pointer travels further than {@code slop}. */
        void noteMoved(float x, float y, int slop) {
            if (!hasOrigin) {
                originX   = x;
                originY   = y;
                hasOrigin = true;
                return;
            }
            if (Math.abs(x - originX) > slop || Math.abs(y - originY) > slop) moved = true;
        }
    }

    /**
     * The {@code localState} a member's long-press attaches, for tests that have to drive a folder
     * drag the harness cannot start — same reason as {@link #beginItemDrag}: Robolectric's
     * window-session shadow rejects this drag's deliberately null ClipData.
     */
    Object folderDragStateForTest(HomeItem folder, int modelIndex) {
        return new FolderDragState(folder, modelIndex,
                folder.getPackageNames().get(modelIndex), null, () -> {});
    }

    private LinearLayout buildPopupAppCell(AppInfo info, String pkg,
                                            HomeItem folder, FrameLayout overlay,
                                            int modelIndex) {
        LinearLayout cell = new LinearLayout(getContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);

        // Notification count badge, exactly as an app on a page or in the dock gets one. Without
        // it the folder chip showed a count while the popup you open to act on it showed none, so
        // there was no way to tell *which* member the folder's badge was about.
        //
        // The count is this member's own, not the folder's sum — the sum is what the chip
        // underneath already shows, and repeating it on every member would be wrong the moment
        // two members are notified. That is what the binding's memberPkg carries.
        int count = badgeCountFor(pkg);
        float density = getResources().getDisplayMetrics().density;
        BadgeDrawable badge = newBadgeDrawable(
                Personality.ICON.treat(getContext(), info.getIcon(), info.getPackageName()),
                density, count, info.getPackageName());
        popupBadgeBindings.add(new BadgeBinding(folder, pkg, badge));

        ImageView icon = new ImageView(getContext());
        icon.setImageDrawable(badge);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cell.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView lbl = new TextView(getContext());
        lbl.setText(info.getLabel());
        lbl.setTextColor(0xFFFFFFFF);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        lbl.setGravity(Gravity.CENTER);
        lbl.setMaxLines(1);
        lbl.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lblLp.topMargin = dp(3);
        cell.addView(lbl, lblLp);

        // Tap → launch app and close popup
        cell.setOnClickListener(v -> {
            closeFolderPopup();
            if (appClickListener != null) appClickListener.onAppClick(pkg);
        });

        final Runnable showMemberMenu = () -> {
            PopupMenu menu = new PopupMenu(getContext(), cell);
            menu.getMenu().add(0, 1, 0, "Remove from folder");
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1) {
                    store.removeFromFolder(folder, pkg);
                    // If folder collapsed (now an APP or gone), close the popup.
                    HomeItem stillFolder = store.itemAt(
                            folder.getScreen(), folder.getCellX(), folder.getCellY());
                    if (stillFolder == null || stillFolder.getType() != HomeItem.Type.FOLDER) {
                        closeFolderPopup();
                        refresh();
                    } else {
                        // Rebuild just the grid inside the popup.
                        refreshFolderPopupGrid(overlay, folder);
                    }
                }
                return true;
            });
            menu.show();
        };

        // Long-press → start a reorder drag. Releasing without moving falls back to the member
        // menu, the same "drag is immediate, menu on release-in-place" convention the home-screen
        // items use (see the pendingShortcutItem field doc).
        cell.setOnLongClickListener(v -> {
            FolderDragState state =
                    new FolderDragState(folder, modelIndex, pkg, v, showMemberMenu);
            // Null ClipData: this drag never leaves the app, and advertising text content makes
            // OEM drag panels (OPPO/OnePlus "File Dock") claim part of the screen as a drop
            // target — see startItemDrag.
            boolean started = v.startDragAndDrop(
                    null, new View.DragShadowBuilder(v), state, 0);
            if (!started) {
                showMemberMenu.run(); // system refused the drag — still give the user the menu
                return true;
            }
            v.setVisibility(View.INVISIBLE);
            return true;
        });

        return cell;
    }

    /** Rebuilds the member grid inside an already-open folder popup after a member is removed. */
    private void refreshFolderPopupGrid(FrameLayout overlay, HomeItem folder) {
        // The card is overlay's child index 1 (index 0 is the scrim).
        if (overlay.getChildCount() < 2) return;
        View cardView = overlay.getChildAt(1);
        if (!(cardView instanceof LinearLayout)) return;
        LinearLayout card = (LinearLayout) cardView;

        // The ScrollView is card's last child.
        View last = card.getChildAt(card.getChildCount() - 1);
        if (!(last instanceof ScrollView)) return;
        ScrollView scroll = (ScrollView) last;
        scroll.removeAllViews();
        scroll.addView(buildMemberGrid(folder, overlay, folderCardWidth()));
    }

    /**
     * Commits the folder-name EditText to the model.
     *
     * @return true if the stored name actually changed, so the caller knows the home-screen
     *         chip's label is now stale and has to be rebuilt
     */
    private boolean saveFolderName() {
        if (openFolder == null || folderNameEdit == null || store == null) return false;
        String typed   = folderNameEdit.getText().toString().trim();
        // An emptied field means "reset to the default", not "name it the empty string" — so
        // compare the name that would actually be stored, or clearing an already-default folder
        // would report a change every time.
        String newName = typed.isEmpty() ? "Folder" : typed;
        if (newName.equals(openFolder.getFolderName())) return false;
        store.renameFolder(openFolder, newName);
        return true;
    }

    /**
     * Enter in the name field: store the name now and dismiss the keyboard, leaving the popup
     * open. The store then already holds the typed name, so {@link #saveFolderName} on close
     * would report no change and skip the chip rebuild — {@link #folderNameCommitted} carries
     * the fact that it did change across to {@link #closeFolderPopup}.
     */
    private void commitFolderName() {
        if (folderNameEdit == null) return;
        if (saveFolderName()) folderNameCommitted = true;
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(folderNameEdit.getWindowToken(), 0);
        folderNameEdit.clearFocus();
    }

    // -------------------------------------------------------------------------
    // Empty-space long-press context menu
    // -------------------------------------------------------------------------

    /**
     * Shows the empty-space context menu anchored near the long-press touch point.
     *
     * <p>A 1×1 transparent anchor view is added to the window's decor view at the absolute
     * screen position of the touch so the PopupMenu opens next to the finger rather than at
     * the window origin. The anchor is removed when the menu is dismissed.
     *
     * @param touchX X coordinate of the long-press in this view's local coordinate space
     * @param touchY Y coordinate of the long-press in this view's local coordinate space
     */
    private void showBackgroundMenu(float touchX, float touchY) {
        // Convert view-local touch coordinates to absolute screen coordinates.
        int[] viewOrigin = new int[2];
        getLocationOnScreen(viewOrigin);
        final int absX = viewOrigin[0] + (int) touchX;
        final int absY = viewOrigin[1] + (int) touchY;

        // Add a tiny invisible anchor to the window decor at the touch location.
        // Using the decor view avoids any coordinate-space interference from
        // HomescreenView's horizontal scroll (scrollTo) and overlay translations.
        final android.view.ViewGroup decor =
                ((Activity) getContext()).getWindow().getDecorView().getRootView() instanceof android.view.ViewGroup
                        ? (android.view.ViewGroup) ((Activity) getContext()).getWindow().getDecorView()
                        : null;
        final View anchor = new View(getContext());
        if (decor != null) {
            anchor.setX(absX);
            anchor.setY(absY);
            decor.addView(anchor, new android.view.ViewGroup.LayoutParams(1, 1));
        }

        PopupMenu menu = decor != null
                ? new PopupMenu(getContext(), anchor)
                : new PopupMenu(getContext(), this, Gravity.CENTER);
        menu.getMenu().add(0, 1, 0, "Add widget");
        menu.getMenu().add(0, 2, 1, "Change wallpaper");
        menu.getMenu().add(0, 3, 2, "Settings");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == 1) {
                showWidgetPicker();
            } else if (id == 2) {
                if (wallpaperRequestListener != null) wallpaperRequestListener.onWallpaperChangeRequested();
            } else if (id == 3) {
                if (settingsRequestListener != null) settingsRequestListener.onSettingsRequested();
            }
            return true;
        });
        if (decor != null) {
            menu.setOnDismissListener(m -> decor.removeView(anchor));
        }
        menu.show();
    }

    // -------------------------------------------------------------------------
    // Phase D — Deep-shortcut popup (homescreen / dock icons)
    // -------------------------------------------------------------------------

    /**
     * Shows a popup for an APP item that was long-pressed without movement: shortcut rows
     * (if the launcher holds shortcut-host permission) above standard homescreen actions
     * (App info, Uninstall or Disable, Remove from Home screen). An AlertDialog is used so no anchor-view
     * positioning is needed (item views are rebuilt by refresh() before this runs).
     */
    private void showDeepShortcutPopup(HomeItem item) {
        AppInfo info = appMap.get(item.getPackageName());
        if (info == null) {
            // Package no longer installed; nothing to show.
            return;
        }

        LauncherApps la = (LauncherApps) getContext().getSystemService(Context.LAUNCHER_APPS_SERVICE);
        List<ShortcutInfo> shortcuts = AppAdapter.fetchShortcuts(la, info);

        float density = getResources().getDisplayMetrics().density;
        Uri pkgUri = Uri.parse("package:" + UserKeys.packageOf(item.getPackageName()));

        // Combined items: shortcut rows first, then homescreen-specific action rows.
        final List<Object> dialogItems = new ArrayList<>(shortcuts);
        dialogItems.add("App info");
        // Uninstall, Disable (system apps) or nothing (work apps) — see AppRemoval.
        String removal = AppRemoval.labelFor(info);
        if (removal != null) dialogItems.add(removal);
        dialogItems.add("Remove from Home screen");

        AlertDialog[] dialogRef = { null };

        ListView list = new ListView(getContext());
        list.setDivider(null);
        list.setDividerHeight(0);
        final HomeItem finalItem = item;
        final AppInfo  finalInfo = info;

        list.setAdapter(new BaseAdapter() {
            private static final int TYPE_SHORTCUT = 0;
            private static final int TYPE_ACTION   = 1;

            @Override public int     getCount()        { return dialogItems.size(); }
            @Override public Object  getItem(int i)    { return dialogItems.get(i); }
            @Override public long    getItemId(int i)  { return i; }
            @Override public int     getViewTypeCount() { return 2; }
            @Override public int     getItemViewType(int i) {
                return dialogItems.get(i) instanceof ShortcutInfo ? TYPE_SHORTCUT : TYPE_ACTION;
            }

            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                Object di = dialogItems.get(pos);
                int pad = (int) (12 * density);

                if (di instanceof ShortcutInfo) {
                    ShortcutInfo si = (ShortcutInfo) di;
                    LinearLayout row = new LinearLayout(getContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(pad, (int) (8 * density), pad, (int) (8 * density));

                    ImageView iconView = new ImageView(getContext());
                    iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    try {
                        Drawable d = la.getShortcutIconDrawable(si, 0);
                        if (d != null) iconView.setImageDrawable(d);
                    } catch (Exception ignored) {}
                    LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                            (int) (40 * density), (int) (40 * density));
                    row.addView(iconView, iconLp);

                    TextView label = new TextView(getContext());
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
                    TextView tv = new TextView(getContext());
                    tv.setText((String) di);
                    tv.setTextColor(0xFFFFFFFF);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
                    tv.setPadding(pad, (int) (14 * density), pad, (int) (14 * density));
                    return tv;
                }
            }
        });

        list.setOnItemClickListener((parent, v, pos, id) -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            Object di = dialogItems.get(pos);
            if (di instanceof ShortcutInfo) {
                try { la.startShortcut((ShortcutInfo) di, null, null); } catch (Exception ignored) {}
            } else {
                String action = (String) di;
                if ("App info".equals(action)) {
                    // Work apps: open the managed-profile app details page via LauncherApps.
                    if (finalInfo.isWork()
                            && finalInfo.getComponentName() != null
                            && finalInfo.getUser() != null) {
                        try {
                            la.startAppDetailsActivity(
                                    finalInfo.getComponentName(), finalInfo.getUser(),
                                    null, null);
                        } catch (Exception ignored) {}
                    } else {
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                pkgUri);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        getContext().startActivity(intent);
                    }
                } else if (AppRemoval.isRemovalLabel(action)) {
                    AppRemoval.start(getContext(), finalInfo);
                } else if ("Remove from Home screen".equals(action)) {
                    if (store != null) {
                        store.removeItem(finalItem);
                        refresh();
                    }
                }
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle(finalInfo.getLabel())
                .setView(list)
                .create();
        dialogRef[0] = dialog;
        dialog.show();
    }

    // -------------------------------------------------------------------------
    // Widget picker
    // -------------------------------------------------------------------------

    /**
     * Opens the widget picker. Layout and container are the picker's own concern (read from
     * {@link Settings}); this view supplies only the grid geometry needed for the span estimate.
     *
     * <p>The cell-size fallbacks matter: the picker can be opened before this view has been laid
     * out, and zero cell dimensions would collapse every preview to a 1px box.
     */
    private void showWidgetPicker() {
        if (addWidgetListener == null) return;
        int cols = store != null ? store.columns() : HomeScreenStore.DEFAULT_COLS;
        int rows = store != null ? store.rows()    : HomeScreenStore.DEFAULT_ROWS;
        int pickerPad = CellLayout.pagePadPx(getContext());
        int cellW = CellLayout.cellWidth(
                getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels,
                cols, pickerPad);
        int cellH = getHeight() > 0
                ? pageHeight(getHeight()) / rows
                : getResources().getDisplayMetrics().heightPixels / rows;

        WidgetPicker.show(getContext(), cols, rows, cellW, cellH,
                provider -> addWidgetListener.onPickWidget(provider));
    }

    /**
     * Initial span in cells for a provider — the single place that reads a provider's minimum
     * size, because the units are a trap.
     *
     * <p>{@link AppWidgetProviderInfo#minWidth} and {@code minHeight} are already in
     * <b>pixels</b>: the framework converts the dp declared in the provider XML by display
     * density when it parses the provider. Both call sites used to scale by density a second
     * time, which on a 560dpi phone placed every widget several cells too large — the Google
     * search bar landed 2 rows tall when its own minimum only asks for 1. Having the conversion
     * written out twice is why both were wrong and neither was covered.
     */
    /**
     * Width of the dead band around each snap boundary, in cells. Stock Launcher3 calls this
     * RESIZE_THRESHOLD and uses the same 0.66.
     */
    static final float RESIZE_THRESHOLD = 0.66f;

    /**
     * Whole-cell span delta for a drag of {@code deltaPx}, with hysteresis around the span that is
     * already committed.
     *
     * Plain {@code Math.round} commits at exactly half a cell, so a few pixels of finger tremble
     * either side of the midpoint toggles the span and re-lays out the widget on every wobble.
     * Measured on device: 478px gave 2 cells and 471px gave 1, so a 7px jitter flipped a widget
     * between 3x1 and 4x1 four times in a second.
     *
     * Note stock's own threshold does NOT fix that on its own: it is compared against the raw
     * fraction, so it only widens the dead band around zero and still uses Math.round past it —
     * 1.481 and 1.503 cells still land on different spans. Anchoring the band to
     * {@code committedDelta} instead puts a 0.66-cell dead zone at *every* boundary, so nothing
     * smaller than that can ever flip the span, wherever the drag currently sits.
     *
     * @param committedDelta the whole-cell delta currently applied, so the band moves with it.
     */
    static int spanDelta(float deltaPx, int cellPx, int committedDelta) {
        if (cellPx <= 0) return 0;
        float f = deltaPx / cellPx;
        int d = committedDelta;
        // Loops rather than a single step: a fast drag can cross several cells between MOVEs.
        while (f >= d + RESIZE_THRESHOLD) d++;
        while (f <= d - RESIZE_THRESHOLD) d--;
        return d;
    }

    /**
     * The part of a drag that is not yet reflected in the committed span.
     *
     * The frame's committed edge already moved by {@code committedDelta} whole cells, because
     * computeBounds derives it from the item's current span. Adding the whole finger delta on top
     * of that counts the committed cells twice and the frame runs at double the speed of the
     * finger — reported on device as "when I expand the size it moves twice as fast as I drag it".
     *
     * <p>The invariant: {@code committedDelta * cellPx + liveResidual(...) == deltaPx}, i.e. the
     * frame's total travel from where it started equals the finger's.
     */
    static float liveResidual(float deltaPx, int committedDelta, int cellPx) {
        return deltaPx - committedDelta * (float) cellPx;
    }

    static int[] spanForProvider(AppWidgetProviderInfo info, int cellWidthPx, int cellHeightPx,
                                 int maxCols, int maxRows) {
        if (info == null) return new int[]{1, 1};
        return computeSpan(info.minWidth, info.minHeight,
                cellWidthPx, cellHeightPx, maxCols, maxRows);
    }

    /**
     * Computes the home-screen cell span for a widget given its minimum size in pixels and the
     * current cell dimensions in pixels. Span is clamped to the supplied grid limits.
     * Package-private for unit tests. Prefer {@link #spanForProvider} when you have the provider.
     */
    static int[] computeSpan(int minWidthPx, int minHeightPx, int cellWidthPx, int cellHeightPx,
                              int maxCols, int maxRows) {
        if (cellWidthPx <= 0 || cellHeightPx <= 0) return new int[]{1, 1};
        int spanX = (int) Math.ceil((double) minWidthPx  / cellWidthPx);
        int spanY = (int) Math.ceil((double) minHeightPx / cellHeightPx);
        spanX = Math.max(1, Math.min(maxCols, spanX));
        spanY = Math.max(1, Math.min(maxRows, spanY));
        return new int[]{spanX, spanY};
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Human-readable {@link AppWidgetProviderInfo#resizeMode}, for the {@link #RDBG} trace. */
    private static String resizeModeName(int mode) {
        boolean h = (mode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0;
        boolean v = (mode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0;
        if (h && v) return "BOTH(" + mode + ")";
        if (h)      return "HORIZONTAL_ONLY(" + mode + ") - no top/bottom handle, by design";
        if (v)      return "VERTICAL_ONLY(" + mode + ") - no left/right handle, by design";
        return "NONE(" + mode + ")";
    }

    // -------------------------------------------------------------------------
    // DropTarget — shared point→zone/cell resolver used by preview and drop commit
    // -------------------------------------------------------------------------

    /**
     * Package-private: resolves a raw DragEvent coordinate pair to a home-screen zone and cell.
     *
     * <p>By funnelling BOTH the live preview (from {@link HomescreenView#handleDragLocation}) and
     * the actual drop commit (from {@link HomescreenView#handleDrop}) through
     * {@link #resolve}, the two paths are guaranteed to agree on which zone and cell the
     * pointer maps to, eliminating the class of bugs where the preview shows cell (A) but the
     * drop lands on cell (B).
     */
    static final class DropTarget {

        /**
         * Drag-time drop zones. There is deliberately no RESIZE here: the pill was removed
         * once long-press-without-movement on a widget opened the frame on its own (see
         * {@code endDrag}), which is the same gesture with one less thing on screen.
         */
        enum Zone { REMOVE, UNINSTALL, DISABLE, DOCK, GRID, OUTSIDE }

        /**
         * Geometry of the action bar shown at the top of the screen during a drag: {@code zones}
         * equal-width pills laid out left-to-right, centred on {@code centerX}.
         *
         * <p>Both the painter ({@link HomescreenView#drawActionBar}) and the hit test
         * ({@link #hit}) derive pill rectangles from this one object, so a pill can never be
         * drawn somewhere the drop resolver doesn't accept it.
         */
        static final class ActionBar {
            static final ActionBar NONE = new ActionBar(new Zone[0], 0f, 0f, 0f, 0f, 0f);

            final Zone[] zones;
            final float  centerX, topPx, botPx, pillW, gapPx;

            ActionBar(Zone[] zones, float centerX, float topPx, float botPx,
                      float pillW, float gapPx) {
                this.zones   = zones;
                this.centerX = centerX;
                this.topPx   = topPx;
                this.botPx   = botPx;
                this.pillW   = pillW;
                this.gapPx   = gapPx;
            }

            int count() { return zones.length; }

            /** Total width of the laid-out bar in px. */
            float totalWidth() {
                int n = zones.length;
                return n == 0 ? 0f : n * pillW + (n - 1) * gapPx;
            }

            /** Left edge of pill {@code i} in the same coordinate space as {@link #centerX}. */
            float pillLeft(int i) {
                return centerX - totalWidth() / 2f + i * (pillW + gapPx);
            }

            /** Zone of the pill under (x, y), or null when the point is outside every pill. */
            Zone hit(float x, float y) {
                if (zones.length == 0 || y < topPx || y > botPx) return null;
                for (int i = 0; i < zones.length; i++) {
                    float left = pillLeft(i);
                    if (x >= left && x <= left + pillW) return zones[i];
                }
                return null;
            }
        }

        final Zone zone;
        /** Page index (≥ 0) — valid for {@link Zone#GRID}; -1 otherwise. */
        final int page;
        /** Column index for {@link Zone#GRID}, slot index for {@link Zone#DOCK}; -1 otherwise. */
        final int cellX;
        /** Row index — valid for {@link Zone#GRID}; 0 for {@link Zone#DOCK}; -1 otherwise. */
        final int cellY;

        DropTarget(Zone zone, int page, int cellX, int cellY) {
            this.zone  = zone;
            this.page  = page;
            this.cellX = cellX;
            this.cellY = cellY;
        }

        static DropTarget outside() {
            return new DropTarget(Zone.OUTSIDE, -1, -1, -1);
        }

        /**
         * Resolves DragEvent coordinates to a zone + cell.
         *
         * <p>All coordinate arguments are in HomescreenView <em>screen-local</em> space (i.e.
         * the raw {@code DragEvent.getX()} / {@code getY()} values — NOT content-scroll-adjusted).
         *
         * @param x             DragEvent x (view-local, screen space).
         * @param y             DragEvent y (view-local, screen space).
         * @param scrollX       Current horizontal scroll offset in px.
         * @param pageWidth     Width of one page in px (must be ≥ 1).
         * @param pagePadPx     Horizontal page padding (left and right) in px — see
         *                      {@link CellLayout#PAGE_PAD_DP}. Pass the same value the grid is
         *                      drawn with: a mismatch puts drops in different cells from the
         *                      preview.
         * @param gridTopPx     Top y of the grid area (the status-bar inset), in view-local coords.
         * @param pageHeight    Height of the grid area in px (total − insets − dock − dots; ≥ 1).
         * @param cols          Grid columns.
         * @param rows          Grid rows.
         * @param spanX         Horizontal span of the dragged item (≥ 1).
         * @param spanY         Vertical span of the dragged item (≥ 1).
         * @param dockTopPx     Top y of the dock zone in view-local coords.
         * @param dockBotPx     Bottom y of the dock zone in view-local coords.
         * @param dockMarginPx  Horizontal inset (left and right) of the dock pill in px.
         * @param actionBar     Geometry of the top action bar (screen-local x). Never null; use
         *                      {@link ActionBar#NONE} when no action pills are shown.
         * @return a {@link DropTarget} describing which zone and cell/slot was hit.
         */
        static DropTarget resolve(
                float x, float y,
                int scrollX, int pageWidth, int pagePadPx, int gridTopPx, int pageHeight,
                int cols, int rows, int spanX, int spanY,
                int dockTopPx, int dockBotPx, int dockMarginPx,
                ActionBar actionBar) {
            return resolve(x, y, scrollX, pageWidth, pagePadPx, gridTopPx, pageHeight,
                    cols, rows, spanX, spanY, dockTopPx, dockBotPx, dockMarginPx, actionBar,
                    0, 0);
        }

        /**
         * As {@link #resolve(float, float, int, int, int, int, int, int, int, int, int, int, int,
         * int, ActionBar)}, for an item grabbed somewhere other than its top-left cell.
         *
         * <p>The pointer sits in one cell of the item; the item's origin is {@code grabX} columns
         * and {@code grabY} rows before it. Without this a 2x1 widget picked up by its right half
         * had its top-left committed to the cell under the finger, so a long-press in the middle
         * of a widget walked it one column right per press, and a 3x1 dragged by its centre landed
         * a column right of where the user let go. Subtracted from the pointer's own cell, not
         * from a span-clamped one: with the pointer in the last column of a 4-wide grid and a
         * 3-wide item grabbed in its third cell, the origin is column 1, and clamping the pointer
         * to {@code cols - spanX} first would have put it at 0.
         *
         * @param grabX columns from the item's origin to the cell the pointer started in, in
         *              {@code [0, spanX)}
         * @param grabY rows likewise, in {@code [0, spanY)}
         */
        static DropTarget resolve(
                float x, float y,
                int scrollX, int pageWidth, int pagePadPx, int gridTopPx, int pageHeight,
                int cols, int rows, int spanX, int spanY,
                int dockTopPx, int dockBotPx, int dockMarginPx,
                ActionBar actionBar, int grabX, int grabY) {

            // --- Action bar (screen-local: pills track the visible centre regardless of scroll) ---
            Zone action = actionBar.hit(x, y);
            if (action != null) return new DropTarget(action, -1, -1, -1);

            // --- Dock zone ---
            if (y >= dockTopPx && y <= dockBotPx) {
                int dockW   = pageWidth - 2 * dockMarginPx;
                float localX = x - dockMarginPx;
                if (dockW > 0 && localX >= 0 && localX <= dockW) {
                    int slot = Math.max(0, Math.min(cols - 1, (int)(localX * cols / dockW)));
                    return new DropTarget(Zone.DOCK, -1, slot, 0);
                }
                return outside();
            }

            // --- Grid zone ---
            if (pageWidth <= 0 || pageHeight <= 0 || cols <= 0 || rows <= 0) return outside();
            int cellW = CellLayout.cellWidth(pageWidth, cols, pagePadPx);
            int cellH = pageHeight / rows;
            if (cellW == 0 || cellH == 0) return outside();

            // Convert screen-local x to content x, then derive page + cell-local x.
            float contentX = x + scrollX;
            int   page     = Math.max(0, (int)(contentX / pageWidth));
            float localX   = contentX - page * (float) pageWidth;
            // Rows are measured from gridTopPx (the status-bar inset), not from y=0: the pages are
            // laid out below the inset, so a raw y would map every row one notch too low.
            float localY = y - gridTopPx;
            // Subtract the page padding before dividing: without this the pad strip on the left
            // shifts every column one cell across, so a drop lands in a different cell from the
            // one the preview drew. Touches inside the pad strips clamp to the edge column.
            int pointerCol = Math.max(0, Math.min(cols - 1, (int)((localX - pagePadPx) / cellW)));
            int pointerRow = Math.max(0, Math.min(rows - 1, (int)(Math.max(0f, localY) / cellH)));
            int cellX = Math.max(0, Math.min(cols - spanX, pointerCol - grabX));
            int cellY = Math.max(0, Math.min(rows - spanY, pointerRow - grabY));
            return new DropTarget(Zone.GRID, page, cellX, cellY);
        }
    }

    // -------------------------------------------------------------------------
    // Resize frame (Phase C) — shown after a widget drag or long-press-without-move
    // -------------------------------------------------------------------------

    /** True if a widget resize frame is currently displayed. */
    /**
     * The resize frame's currently drawn rectangle {left, top, right, bottom}, or null when no
     * frame is open. Test-only: it is the one way to assert what the frame actually DRAWS, which
     * is where the live-tracking double-count lived — the frame ran at twice the finger's speed
     * while every unit test of the arithmetic passed, because the bug was in how two already-
     * correct values were combined.
     */
    float[] resizeFrameRectForTest() {
        if (!(resizeFrameOverlay instanceof ResizeFrameView)) return null;
        return ((ResizeFrameView) resizeFrameOverlay).drawnRectForTest();
    }

    /**
     * Test-only: paints the open resize frame onto {@code canvas}, exactly as
     * {@link ResizeFrameView#onDraw} would.
     *
     * <p>The grip's pressed state lives only in pixels — no geometry, no span, nothing
     * {@link #resizeFrameRectForTest()} can see — so the only assertion that can tell a drawn
     * held state from an undrawn one is on the drawing itself. Requires
     * {@code @GraphicsMode(NATIVE)}; under legacy shadows Canvas paints nothing and every
     * comparison reads "identical".
     */
    void drawResizeFrameForTest(Canvas canvas) {
        if (resizeFrameOverlay != null) resizeFrameOverlay.draw(canvas);
    }

    /**
     * Test-only: whether the resize frame is easing back onto the committed cells after a
     * release. False when no frame is open.
     */
    /**
     * Test-only: the frame overlay itself, so a test can assert it was <em>asked</em> to repaint.
     *
     * <p>{@link #drawResizeFrameForTest(Canvas)} renders on demand and therefore cannot see a
     * missing {@code invalidate()} — it draws whatever the current state says, invalidated or
     * not. That is exactly how a pressed grip that never repainted until the first MOVE passed
     * the suite and failed on the device.
     */
    View resizeFrameViewForTest() {
        return resizeFrameOverlay;
    }

    boolean isResizeFrameSettlingForTest() {
        return resizeFrameOverlay instanceof ResizeFrameView
                && ((ResizeFrameView) resizeFrameOverlay).isSettlingForTest();
    }

    /** Test-only: see {@link ResizeFrameView#advanceSettleForTest(long)}. */
    void advanceResizeSettleForTest(long ms) {
        if (resizeFrameOverlay instanceof ResizeFrameView) {
            ((ResizeFrameView) resizeFrameOverlay).advanceSettleForTest(ms);
        }
    }

    /** Test-only: run any settle in flight straight to its end. */
    void finishResizeSettleForTest() {
        if (resizeFrameOverlay instanceof ResizeFrameView) {
            ((ResizeFrameView) resizeFrameOverlay).finishSettleForTest();
        }
    }

    public boolean isResizeFrameOpen() { return resizeFrameOverlay != null; }

    /**
     * Whether {@code (x, y)} — in this view's coordinates — is on a handle of the open resize
     * frame. False when no frame is open.
     *
     * <p>The overlay is laid out at {@code (0, 0, w, h)} with {@code translationX = scrollX}, so
     * its coordinate space and this view's coincide; no offset is needed. See
     * {@link ResizeFrameView#computeBounds()}.
     */
    private boolean isOnResizeHandle(float x, float y) {
        return resizeFrameOverlay instanceof ResizeFrameView
                && ((ResizeFrameView) resizeFrameOverlay).isOnHandle(x, y);
    }

    /**
     * Whether {@code (x, y)} lands anywhere on the open resize frame — the grip or the outlined
     * box itself. False when no frame is open.
     *
     * <p>Wider than {@link #isOnResizeHandle} because the frame now owns two gestures, not one:
     * the grip resizes, and a drag starting inside the outline repositions the widget. The pager
     * has to stand off both, so ownership is decided from the whole frame.
     */
    private boolean isOnResizeFrame(float x, float y) {
        return resizeFrameOverlay instanceof ResizeFrameView
                && ((ResizeFrameView) resizeFrameOverlay).isOnFrame(x, y);
    }

    /**
     * Shows an accent-colored resize frame around {@code item}. Respects
     * {@link AppWidgetProviderInfo#resizeMode}: NONE → no frame; HORIZONTAL/VERTICAL/BOTH →
     * matching edge handles only.
     *
     * <p>Package-private rather than private so {@code WidgetResizeGestureTest} can open the frame
     * directly. The sole production entry point is {@link #endDrag()}'s
     * {@code pendingResizeItem} hand-off, set when a widget's long-press ends without movement.
     */
    void showResizeFrame(HomeItem item) {
        if (item == null || item.getType() != HomeItem.Type.WIDGET) return;
        if (host == null) return;
        AppWidgetProviderInfo info =
                AppWidgetManager.getInstance(getContext()).getAppWidgetInfo(item.getAppWidgetId());
        if (info == null) {
            if (RDBG) Log.d(RTAG, "showResizeFrame: no provider info for widgetId="
                    + item.getAppWidgetId() + " -> no frame");
            return;
        }
        if (info.resizeMode == AppWidgetProviderInfo.RESIZE_NONE) {
            if (RDBG) Log.d(RTAG, "showResizeFrame: " + info.provider
                    + " declares resizeMode=NONE -> no frame (provider constraint, not a bug)");
            return;
        }

        View wrapperView = findWidgetView(item);
        if (!(wrapperView instanceof FrameLayout)) {
            if (RDBG) Log.d(RTAG, "showResizeFrame: no wrapper view at cell ("
                    + item.getCellX() + "," + item.getCellY() + ") -> no frame");
            return;
        }
        dismissResizeFrame(); // remove any previous frame
        // Logged after the dismiss above, so a re-open never prints OPEN before the previous
        // frame's CLOSED and the trace reads in the order things happened.
        if (RDBG) Log.d(RTAG, "==== frame OPEN for " + info.provider
                + " resizeMode=" + resizeModeName(info.resizeMode)
                + " item=[" + item.getCellX() + "," + item.getCellY() + " "
                + item.getSpanX() + "x" + item.getSpanY() + "]"
                + " minPx=" + info.minWidth + "x" + info.minHeight
                + " minResizePx=" + info.minResizeWidth + "x" + info.minResizeHeight
                + "  (those four are PIXELS, already density-scaled by the framework)");
        resizingItem = item;

        ResizeFrameView frame = new ResizeFrameView(getContext(), item, info, (FrameLayout) wrapperView);
        resizeFrameOverlay = frame;
        addView(frame);
        requestLayout();
    }

    /**
     * Dismisses the resize frame (if open), calling
     * {@link AppWidgetHostView#updateAppWidgetSize} with the final dp size.
     */
    public void dismissResizeFrame() {
        if (resizeFrameOverlay == null) return;
        if (RDBG) {
            // Which caller closed it is the whole question for the "dismissed too eagerly"
            // candidate: onInterceptTouchEvent (a horizontal swipe past touchSlop),
            // ResizeFrameView.onTouchEvent (a tap outside the frame + slack), snapToPage,
            // refresh (a package/notification reload arriving mid-resize), MainActivity's back
            // handler, or showResizeFrame re-opening. A one-frame stack walk names it without
            // changing this method's signature or any call site.
            StackTraceElement[] st = new Throwable().getStackTrace();
            String who = st.length > 1
                    ? st[1].getClassName().substring(st[1].getClassName().lastIndexOf('.') + 1)
                      + "." + st[1].getMethodName() + ":" + st[1].getLineNumber()
                    : "?";
            Log.d(RTAG, "==== frame CLOSED (dismissed by " + who + ")");
        }
        if (resizingItem != null) {
            applyWidgetSizeUpdate(resizingItem);
        }
        removeView(resizeFrameOverlay);
        resizeFrameOverlay = null;
        resizingItem = null;
        // No frame, no handle owner. Also covers showResizeFrame, which dismisses first: a frame
        // that opens mid-gesture has not seen a DOWN and must not inherit the previous one's.
        resizeFrameOwnsGesture = false;
        // Replay a reload that arrived while the frame was open (see shouldDeferRefresh). Safe
        // from recursion: refresh()'s own dismissResizeFrame() call is a no-op by now.
        if (pendingRefresh) refresh();
    }

    /**
     * Calls {@link WidgetSizeHelper#applySize} on the host view inside the widget wrapper.
     * Used by {@link #dismissResizeFrame()} to finalize the size after the user finishes
     * an interactive resize.  Delegates to the shared helper so all call sites use the
     * same API-31 SizeF overload.
     */
    private void applyWidgetSizeUpdate(HomeItem item) {
        CellLayout page = getPage(item.getScreen());
        if (page == null || page.getWidth() == 0 || page.getHeight() == 0) return;
        int cols = store != null ? store.columns() : HomeScreenStore.DEFAULT_COLS;
        int rows = store != null ? store.rows()    : HomeScreenStore.DEFAULT_ROWS;
        int cellW = page.getWidth()  / cols;
        int cellH = page.getHeight() / rows;
        float density = getResources().getDisplayMetrics().density;

        View wrapperView = findWidgetView(item);
        if (!(wrapperView instanceof FrameLayout)) return;
        FrameLayout wrapper = (FrameLayout) wrapperView;
        if (wrapper.getChildCount() == 0) return;
        View child = wrapper.getChildAt(0);
        if (!(child instanceof AppWidgetHostView)) return;
        WidgetSizeHelper.applySize((AppWidgetHostView) child,
                item.getSpanX(), item.getSpanY(), cellW, cellH, density);
    }

    /** Finds the child view in the given page's CellLayout at the item's current cell position. */
    private View findWidgetView(HomeItem item) {
        CellLayout page = getPage(item.getScreen());
        if (page == null) return null;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) child.getLayoutParams();
            if (lp.cellX == item.getCellX() && lp.cellY == item.getCellY()) return child;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // WidgetWrapper — FrameLayout subclass that intercepts long-press for drag
    // -------------------------------------------------------------------------

    /**
     * A {@link FrameLayout} wrapper around an {@link AppWidgetHostView} that intercepts
     * long-press gestures to start a drag, while letting short taps pass through to the
     * widget's interactive content (e.g. a play button inside an Audible Glance widget).
     *
     * <p>Plain {@code setOnLongClickListener} does not work for interactive widgets because
     * the child {@code AppWidgetHostView} consumes the entire touch stream; the parent
     * {@code FrameLayout} never receives a long-press callback. This class solves the problem
     * via {@link #onInterceptTouchEvent}:
     * <ul>
     *   <li>On {@code ACTION_DOWN}: record the position and post a delayed Runnable at
     *       {@link ViewConfiguration#getLongPressTimeout()} ms.</li>
     *   <li>If {@code ACTION_MOVE} exceeds {@code touchSlop}, or {@code ACTION_UP}/
     *       {@code ACTION_CANCEL} arrives before the timeout, the pending Runnable is
     *       cancelled and the touch stream passes through to the child normally.</li>
     *   <li>If the timeout fires without movement: {@code dragCallback.run()} starts the
     *       system drag; {@code intercepting} is set so subsequent {@code MOVE} events are
     *       consumed by this view rather than forwarded to the child (the drag owns the
     *       stream from here on).</li>
     * </ul>
     *
     * <p><b>Important:</b> those MOVE/UP/CANCEL branches only run for widgets whose content
     * actually consumes ACTION_DOWN. For a non-interactive widget — a clock, a weather tile —
     * nothing beneath this view consumes DOWN, so {@code dispatchTouchEvent} returns false, the
     * parent {@link CellLayout} never installs this view as a touch target, and no further event
     * is ever delivered here. {@link HomescreenView#cancelPendingWidgetLongPress()} exists to
     * close that gap; do not assume this class can cancel itself.
     *
     * <p>Because this class extends {@code FrameLayout}, all existing
     * {@code instanceof FrameLayout} checks in {@link #showResizeFrame},
     * {@link #applyWidgetSizeUpdate}, and {@link ResizeFrameView} continue to work correctly.
     */
    static final class WidgetWrapper extends FrameLayout {

        /** Minimum pixels of movement that cancels the pending long-press. */
        private final int touchSlop;

        /**
         * Callback to invoke when a long-press is confirmed (no earlier than timeout).
         * Set via {@link #setDragCallback} after construction so that the caller can
         * capture {@code wrapper} in a lambda without a forward-reference compile error.
         */
        private Runnable dragCallback;

        /** True once the long-press timeout has fired and a drag has been started. */
        private boolean intercepting = false;

        /** Coordinates of the most recent ACTION_DOWN, for slop calculation. */
        private float downX, downY;

        /** Pending long-press Runnable (posted on DOWN, cancelled on move/up/cancel). */
        private final Runnable longPressRunnable = this::onLongPressTimeout;

        /** True between posting {@link #longPressRunnable} and it firing or being cancelled. */
        private boolean longPressPending = false;

        WidgetWrapper(android.content.Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        /** Must be called once, immediately after construction, before this view is used. */
        void setDragCallback(Runnable callback) {
            this.dragCallback = callback;
        }

        /**
         * Drops any queued long-press. Called by this view's own MOVE/UP/CANCEL handling and — for
         * widgets that never become a touch target at all — by
         * {@link HomescreenView#cancelPendingWidgetLongPress()}.
         */
        void cancelPendingLongPress() {
            if (!longPressPending) return;
            longPressPending = false;
            removeCallbacks(longPressRunnable);
        }

        /** True while a long-press timeout is queued. Package-private for tests. */
        boolean hasPendingLongPress() { return longPressPending; }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    intercepting = false;
                    downX = ev.getX();
                    downY = ev.getY();
                    // Post the long-press timeout. Cancel it in UP/CANCEL/slop-exceeded MOVE.
                    removeCallbacks(longPressRunnable);
                    longPressPending = true;
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                    // Return false: let DOWN reach the child so widget taps work normally.
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (intercepting) return true; // already dragging — own all moves
                    float dx = Math.abs(ev.getX() - downX);
                    float dy = Math.abs(ev.getY() - downY);
                    if (movementCancelsLongPress(dx, dy, touchSlop)) {
                        // Moved too far — this is a scroll/swipe, not a long-press. Cancel.
                        cancelPendingLongPress();
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cancelPendingLongPress();
                    intercepting = false;
                    return false;

                default:
                    return intercepting;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            // Absorb events after the drag has started so the child is not spuriously clicked.
            return intercepting;
        }

        /**
         * A refresh() between DOWN and the timeout detaches this view, but the runnable stays
         * queued on the old AttachInfo handler and would still fire against the dead view.
         */
        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            cancelPendingLongPress();
            intercepting = false;
        }

        /** Called when the long-press timeout fires without enough pointer movement. */
        private void onLongPressTimeout() {
            longPressPending = false;
            // Belt and braces for the detach case above: a queued runnable can outlive the view.
            if (dragCallback == null || !isAttachedToWindow()) return;
            intercepting = true;
            dragCallback.run();
        }
    }

    /**
     * Accent-bordered resize frame overlay drawn above the current page. Draws handles on the
     * edges permitted by {@link AppWidgetProviderInfo#resizeMode} and handles touch-drag to
     * grow/shrink the widget span one cell at a time.
     *
     * <p>While the frame is open the widget can also be <em>repositioned</em>: a drag that starts
     * inside the outline rather than on the grip slides it a cell at a time
     * ({@link #applyMove}). The two gestures are told apart once, on ACTION_DOWN, from where the
     * finger landed — the same "decide the owner at DOWN, not from the movement" rule the pager
     * and the widget long-press already follow, and for the same reason: by the first MOVE a
     * grip drag, a body drag and a page swipe all look alike.
     */
    private final class ResizeFrameView extends View {

        private static final int ACCENT_COLOR    = 0xFFBB86FC;
        private static final int BORDER_DP   = 2;   // frame stroke, as the stock launcher draws it
        private static final int CORNER_DP    = 16;  // frame corner radius
        private static final int KNOB_R_DP    = 9;   // sizes the frame outset (see OUTSET_DP)
        private static final int GRIP_ARM_DP  = 18;  // length of each arm of the corner bracket
        private static final int GRIP_W_DP    = 4;   // stroke width of the bracket
        private static final int GRIP_TOUCH_DP = 28; // half-size of the square grab box
        /**
         * Pressed state for the corner grip: a thicker bracket over a translucent accent disc.
         *
         * The grab box is dp(GRIP_TOUCH_DP) = 28dp half-width while the mark is an 18dp bracket
         * drawn in a 4dp stroke, so a fingertip covers the whole affordance and there was
         * previously nothing at all to say the press had landed on it. A grip that looks
         * identical held and idle is the same class of failure as a blocked grow that does
         * nothing: the user cannot tell "not grabbed" from "grabbed and refused".
         *
         * <p>Nothing here may grow the grip OUTWARD past dp(KNOB_R_DP) from the frame corner.
         * That radius is exactly the budget {@code CellLayout.PAGE_PAD_DP >= OUTSET_DP +
         * KNOB_R_DP} reserves (16 >= 7 + 9), and the same radius computeHandleAnchors clamps the
         * anchors by, so anything wider is off the display on a column-0 or last-column widget —
         * which is how the frame came to be drawn INSIDE the widget the first time round. The
         * halo is therefore centred on the bracket's elbow (inside the outline) rather than on
         * the corner point, and the extra stroke width is taken inward.
         */
        private static final int HELD_GRIP_W_DP  = 6;   // vs GRIP_W_DP at rest
        private static final int HELD_HALO_R_DP  = KNOB_R_DP;
        private static final int HELD_HALO_COLOR = 0x59BB86FC; // ACCENT_COLOR at ~35% alpha
        /**
         * Width of the grab strip along each edge, and how far outside the widget the frame sits.
         * Both are the stock launcher's {@code dimen/resize_frame_margin}.
         */
        private static final int TOUCH_STRIP_DP = 22;
        // How far outside the widget the frame is drawn. Must satisfy
        // CellLayout.PAGE_PAD_DP >= OUTSET_DP + KNOB_R_DP so an edge widget's knob stays fully on
        // screen; with PAGE_PAD_DP=16 and KNOB_R_DP=9 that caps this at 7.
        private static final int OUTSET_DP      = 7;
        /** Keeps a knob clear of the system gesture strip, and fully on screen. */

        // Edge bitmask constants
        private static final int EDGE_LEFT   = 1;
        private static final int EDGE_RIGHT  = 2;
        private static final int EDGE_TOP    = 4;
        private static final int EDGE_BOTTOM = 8;

        private final HomeItem item;
        private final AppWidgetProviderInfo info;
        private final FrameLayout wrapper; // widget wrapper inside the CellLayout

        // Widget bounds in this view's coordinate space (screen-relative, updated each draw/touch)
        private float wLeft, wTop, wRight, wBottom;
        private int   pagePad;
        private int   cellWidth, cellHeight;

        // Touch drag state.
        //
        // The frame owns two gestures, told apart once on ACTION_DOWN by where the finger lands:
        // on the grip it resizes, anywhere inside the outline it repositions the widget. They are
        // mutually exclusive within a stream, so they share touchStartX/Y, liveDx/Dy and
        // committedDeltaX/Y rather than duplicating them.
        private static final int GESTURE_NONE   = 0;
        private static final int GESTURE_RESIZE = 1;
        private static final int GESTURE_MOVE   = 2;
        private int   gesture = GESTURE_NONE;
        /**
         * True between a DOWN inside the outline and the slop crossing that turns it into a move.
         * Without the wait, the tremble in a tap on the widget would walk it off its cell.
         */
        private boolean movePending;
        private int   activeEdge;
        /**
         * True while a finger is actually down on the corner grip, i.e. what {@link #drawHandles}
         * paints the pressed state from.
         *
         * <p>Derived from {@code activeEdge} on ACTION_DOWN with an {@code &} against
         * {@link #grippedAxes()} — never an {@code ==}, because the corner's mask is
         * {@code EDGE_RIGHT | EDGE_BOTTOM} and an equality test matches neither bit of it. Kept
         * as its own flag rather than read back off activeEdge at draw time because activeEdge
         * outlives the finger: the settle animation needs it until the frame has come to rest,
         * and a grip that stays lit for 150ms after the finger has gone reads as a stuck press.
         */
        private boolean gripHeld;
        private float touchStartX, touchStartY;
        /**
         * Live finger offset for the gesture in progress, in px, or 0 when not dragging: on the
         * dragged edge for a resize, on the whole frame for a move.
         *
         * Stock Launcher3 moves the frame with the finger every MOVE (visualizeResizeForDelta)
         * and only snaps it back to the committed cells on release. Without that the frame is
         * derived purely from committed cells, so a drag showed nothing at all until it crossed a
         * whole cell — measured on device at ~170px of dead travel before anything moved.
         */
        private float liveDx, liveDy;
        /** Whole-cell delta already applied this drag; anchors the hysteresis band. */
        private int committedDeltaX, committedDeltaY;
        private int   startCellX, startCellY, startSpanX, startSpanY;

        // Per-gesture counters for the {@link HomescreenView#RDBG} trace. Reset on every DOWN and
        // summarised on UP/CANCEL, so a whole drag costs one block of log rather than one line
        // per MOVE. All reads and writes are inside {@code if (RDBG)}, so javac drops them
        // (but not the fields) from the release build.
        private int   dbgMoves, dbgApplied;
        private float dbgMaxDx, dbgMaxDy;
        private float dbgDeadTravel = -1f;
        private int   dbgLastDeltaX = Integer.MIN_VALUE, dbgLastDeltaY = Integer.MIN_VALUE;

        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrimPaint  = new Paint();
        /** Fills the pressed-state disc under the bracket. See {@link #HELD_HALO_COLOR}. */
        private final Paint haloPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

        ResizeFrameView(android.content.Context ctx,
                        HomeItem item, AppWidgetProviderInfo info, FrameLayout wrapper) {
            super(ctx);
            this.item    = item;
            this.info    = info;
            this.wrapper = wrapper;
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setColor(ACCENT_COLOR);
            borderPaint.setStrokeWidth(dp(BORDER_DP));
            handlePaint.setStyle(Paint.Style.STROKE);
            handlePaint.setColor(ACCENT_COLOR);
            handlePaint.setStrokeWidth(dp(GRIP_W_DP));
            handlePaint.setStrokeCap(Paint.Cap.ROUND);
            handlePaint.setStrokeJoin(Paint.Join.ROUND);
            scrimPaint.setColor(0x33000000);
            haloPaint.setStyle(Paint.Style.FILL);
            haloPaint.setColor(HELD_HALO_COLOR);
        }

        /** How far outside the widget the frame is drawn and hit-tested, in px. */
        private float outsetPx() {
            return dp(OUTSET_DP);
        }

        /**
         * Where each handle actually sits, in this view's coordinates: the frame edge pulled
         * inward far enough to clear the system gesture insets.
         *
         * <p>A widget spanning the full grid has its left and right edges at x=0 and x=width,
         * which is exactly where the back-gesture strip lives — the system consumes the touch
         * before the app sees it, so those handles cannot be grabbed at all. Stock Launcher3 never
         * hits this because its workspace has horizontal page padding and widgets never reach the
         * display edge; this grid has none, so the handles are pulled in instead.
         */
        private float aLeft, aTop, aRight, aBottom;

        private void computeHandleAnchors() {
            float out = outsetPx();
            // The frame outlines the widget, full stop. It used to be clamped inward off the
            // system gesture insets, which on this device (left/right 30dp, top 52dp) put the
            // frame 44dp INSIDE a column-0 widget and 26dp below its top — so the outline did not
            // describe the widget at all, and pressing the widget's own visible edge missed the
            // touch strip because the strip had moved inward with it. Measured on device:
            //   widget=[0,141,1272,588] frame=[154,232,1118,665]  => L=-154 T=-91 R=-154
            // The room to draw outside now comes from CellLayout.PAGE_PAD_DP instead, and the
            // handles stay grabbable inside the gesture strips via setSystemGestureExclusionRects
            // (declared in layoutHandles) rather than by moving them.
            aLeft   = wLeft   - out;
            aTop    = wTop    - out;
            aRight  = wRight  + out;
            aBottom = wBottom + out;
            // Never let a knob fall off the display; the page padding normally makes this moot.
            float k = dp(KNOB_R_DP);
            aLeft   = Math.max(aLeft, k);
            aTop    = Math.max(aTop,  k);
            aRight  = Math.min(aRight,  getWidth()  - k);
            aBottom = Math.min(aBottom, getHeight() - k);
            // A widget narrower than the insets would otherwise cross its handles over each other.
            if (aLeft > aRight)  { aLeft = aRight  = (aLeft + aRight)  / 2f; }
            if (aTop  > aBottom) { aTop  = aBottom = (aTop  + aBottom) / 2f; }
        }

        /** System gesture insets. */
        private android.graphics.Rect gestureInsets() {
            android.graphics.Rect r = new android.graphics.Rect();
            android.view.WindowInsets wi = getRootWindowInsets();
            if (wi == null) return r;
            android.graphics.Insets in = android.os.Build.VERSION.SDK_INT >= 30
                    ? wi.getInsets(android.view.WindowInsets.Type.systemGestures())
                    : wi.getSystemGestureInsets();
            r.set(in.left, in.top, in.right, in.bottom);
            return r;
        }

        /**
         * Asks the system to leave the handle strips to us. Complements the inward clamp rather
         * than replacing it — the platform caps how much of an edge an app may exclude, so a
         * handle still has to sit somewhere reachable.
         */
        private void updateGestureExclusion() {
            float t = dp(TOUCH_STRIP_DP);
            java.util.List<android.graphics.Rect> rects = new java.util.ArrayList<>(4);
            int mode = info.resizeMode;
            if ((mode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0) {
                rects.add(new android.graphics.Rect(
                        (int) (aLeft - t), (int) aTop, (int) (aLeft + t), (int) aBottom));
                rects.add(new android.graphics.Rect(
                        (int) (aRight - t), (int) aTop, (int) (aRight + t), (int) aBottom));
            }
            if ((mode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0) {
                rects.add(new android.graphics.Rect(
                        (int) aLeft, (int) (aTop - t), (int) aRight, (int) (aTop + t)));
                rects.add(new android.graphics.Rect(
                        (int) aLeft, (int) (aBottom - t), (int) aRight, (int) (aBottom + t)));
            }
            setSystemGestureExclusionRects(rects);
        }

        /**
         * Computes widget pixel bounds in this view's coordinate space. The overlay's
         * translationX=scrollX makes (0,0) correspond to the visible top-left of HomescreenView,
         * so: widgetLeft = pageIndex*pageWidth - scrollX + cellX*cellW.
         */
        /** {left, top, right, bottom} as onDraw would draw it right now. */
        float[] drawnRectForTest() {
            computeBounds();
            computeHandleAnchors();
            applyLiveOffset();
            return new float[] { aLeft, aTop, aRight, aBottom };
        }

        private void computeBounds() {
            int cols = store != null ? store.columns() : HomeScreenStore.DEFAULT_COLS;
            int rows = store != null ? store.rows()    : HomeScreenStore.DEFAULT_ROWS;
            CellLayout page = getPage(item.getScreen());
            if (page == null || page.getWidth() == 0 || page.getHeight() == 0) return;
            pagePad    = CellLayout.pagePadPx(getContext());
            cellWidth  = CellLayout.cellWidth(page.getWidth(), cols, pagePad);
            cellHeight = page.getHeight() / rows;
            int scrollX   = HomescreenView.this.getScrollX();
            int pageWidth = HomescreenView.this.getWidth();
            float pageLeft = item.getScreen() * (float) pageWidth - scrollX;
            wLeft   = pageLeft + CellLayout.cellLeft(item.getCellX(), cellWidth, pagePad);
            // Rows start below the status bar (see the insetTop field doc).
            wTop    = insetTop + item.getCellY() * (float) cellHeight;
            wRight  = wLeft + item.getSpanX() * (float) cellWidth;
            wBottom = wTop  + item.getSpanY() * (float) cellHeight;
            shrinkToRenderedContent(page);
        }

        /**
         * Pull the frame in from the cell footprint onto what the widget actually renders.
         *
         * The AppWidgetHostView fills its cell exactly, but a widget's RemoteViews need not fill
         * the host: measured on device, the Google search widget centres a 168px bar inside a
         * 447px row, so framing the cell drew a tall empty box around a short search bar and
         * looked like the frame belonged to the grid rather than the widget.
         *
         * The launcher cannot know where RemoteViews paint, but it can see the root view they
         * were inflated into, which tracks the content closely. Only ever shrinks, never grows,
         * and falls back to the cell whenever the root is missing, empty, or already fills the
         * host — so a widget that does use its whole cell is unaffected.
         *
         * <p>Note this changes only what is DRAWN and where the grip sits. Spans are still
         * computed in whole cells by {@link #applyResize}; the frame is an affordance, not the
         * unit of resize.
         */
        private void shrinkToRenderedContent(CellLayout page) {
            AppWidgetHostView host = findHostView(page);
            if (host == null || host.getChildCount() == 0) return;
            View root = host.getChildAt(0);
            int w = root.getWidth(), h = root.getHeight();
            if (w <= 0 || h <= 0) return;
            // Offsets are relative to the host, and the host fills the cell, so they apply
            // directly to the cell rect.
            float l = wLeft + root.getLeft();
            float t = wTop  + root.getTop();
            float r = l + w;
            float b = t + h;
            if (l >= wLeft && t >= wTop && r <= wRight && b <= wBottom) {
                wLeft = l; wTop = t; wRight = r; wBottom = b;
            }
        }

        /** The host view for this item's widget on {@code page}, or null. */
        private AppWidgetHostView findHostView(CellLayout page) {
            for (int i = 0; i < page.getChildCount(); i++) {
                View child = page.getChildAt(i);
                if (!(child instanceof FrameLayout)) continue;
                FrameLayout wrapper = (FrameLayout) child;
                if (wrapper.getChildCount() == 0) continue;
                View inner = wrapper.getChildAt(0);
                if (!(inner instanceof AppWidgetHostView)) continue;
                if (((AppWidgetHostView) inner).getAppWidgetId() != item.getAppWidgetId()) continue;
                return (AppWidgetHostView) inner;
            }
            return null;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            computeBounds();
            // Dim the whole screen slightly so the widget stands out.
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);

            computeHandleAnchors();
            applyLiveOffset();
            updateGestureExclusion();

            // The frame is drawn on the anchors, not the raw widget edges, so it never runs off
            // the display — an outset frame on a full-width widget otherwise draws at x=-77.
            float half = dp(BORDER_DP) / 2f;
            float r = dp(CORNER_DP);
            canvas.drawRoundRect(aLeft + half, aTop + half,
                    aRight - half, aBottom - half, r, r, borderPaint);
            // Nothing is drawn between the live edge and the committed edge. There used to be a
            // "cut preview" shading that strip, from when the span committed on release; the span
            // now commits live with a dead band around the cell boundary, so the strip is the
            // dead band and a release never cuts it — the shade previewed a cut that could not
            // happen, and read on device as a stray dark bar beside the frame while the grip was
            // held.
            drawHandles(canvas, aLeft, aTop, aRight, aBottom);
        }

        private void drawHandles(Canvas canvas,
                                 float fLeft, float fTop, float fRight, float fBottom) {
            if (grippedAxes() == 0) return;
            // One bracket, on the bottom-right corner, the way the OnePlus launcher draws it.
            // Being on a corner it drives both axes, so a single grip is all a resize needs and
            // the frame stays clean.
            float arm = dp(GRIP_ARM_DP);
            // Held: a fatter stroke. The inset tracks the stroke width so the bracket still sits
            // inside the frame line rather than spilling outward past it — the extra 2dp is taken
            // inward, which is what keeps the OUTSET_DP + KNOB_R_DP budget untouched.
            float w   = dp(gripHeld ? HELD_GRIP_W_DP : GRIP_W_DP);
            handlePaint.setStrokeWidth(w);
            float in  = w / 2f;                  // keep the stroke inside the frame line
            float r   = dp(CORNER_DP);           // the frame's own corner radius
            float x   = fRight  - in;
            float y   = fBottom - in;
            if (gripHeld) {
                // Centred on the elbow of the bracket — the 45-degree point of the frame's corner
                // arc — and not on (fRight, fBottom), which lies outside the rounded outline
                // altogether and would push the disc dp(HELD_HALO_R_DP) past the frame on both
                // axes. From the elbow it reaches only ~0.3 * dp(CORNER_DP) beyond the corner,
                // well inside the dp(KNOB_R_DP) computeHandleAnchors already guarantees is on
                // screen.
                float d  = (float) (r - r * Math.cos(Math.PI / 4)); // corner point -> arc, 45 deg
                canvas.drawCircle(x - d, y - d, dp(HELD_HALO_R_DP), haloPaint);
            }
            // Trace the frame's rounded corner rather than cutting a square one across it. Drawn
            // as a right angle the bracket collides with the 16dp radius and reads as a thickened
            // blob instead of a grip; following the same arc makes it look like a bolder segment
            // of the frame, which is what it is.
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(x - r - arm, y);
            p.lineTo(x - r, y);
            android.graphics.RectF oval =
                    new android.graphics.RectF(x - 2 * r, y - 2 * r, x, y);
            p.arcTo(oval, 90f, -90f);            // bottom edge round to the right edge
            p.lineTo(x, y - r - arm);
            canvas.drawPath(p, handlePaint);
        }

        /**
         * Which axes the corner grip may move, as an EDGE_* mask, honouring the provider's
         * resizeMode. A corner offers both, but resizeMode is a constraint and not a suggestion:
         * the Google search widget is RESIZE_HORIZONTAL, so its grip moves sideways only.
         */
        private int grippedAxes() {
            int mode = info.resizeMode;
            int axes = 0;
            if ((mode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0) axes |= EDGE_RIGHT;
            if ((mode & AppWidgetProviderInfo.RESIZE_VERTICAL)   != 0) axes |= EDGE_BOTTOM;
            return axes;
        }

        /**
         * Whether (x, y) lands on a handle. Called by
         * {@link HomescreenView#isOnResizeHandle(float, float)} from {@code onInterceptTouchEvent}
         * on ACTION_DOWN — before this view has been offered the event, so the bounds have to be
         * recomputed here rather than relying on {@link #onTouchEvent}'s call.
         */
        boolean isOnHandle(float x, float y) {
            computeBounds();
            return hitTestHandle(x, y) != 0;
        }

        /**
         * Whether (x, y) lands on the frame at all — the grip, or inside the drawn outline. Called
         * by {@link HomescreenView#isOnResizeFrame(float, float)} on ACTION_DOWN to decide whether
         * the pager or the frame owns the stream, so like {@link #isOnHandle} it recomputes the
         * bounds itself.
         *
         * <p>Measured against the outline rather than the widget: the frame sits {@link #OUTSET_DP}
         * outside the widget, and the strip between the two reads as part of the target.
         */
        boolean isOnFrame(float x, float y) {
            computeBounds();
            if (hitTestHandle(x, y) != 0) return true;
            computeHandleAnchors();
            return x >= aLeft && x <= aRight && y >= aTop && y <= aBottom;
        }

        /** Returns the edge bitmask for the handle under (x, y), or 0 if none. */
        private int hitTestHandle(float x, float y) {
            return hitTestEdgeStrip(x, y);
        }

        /**
         * Corner-grip hit test: a square box on the bottom-right corner of the frame.
         *
         * <p>Replaces the old per-edge strips. Those made the whole of every side live while
         * drawing a 9dp dot at its midpoint, so where you could press and where it looked like you
         * should press were different shapes. A corner grip drives both axes at once, which is why
         * one is enough, and it returns a COMBINED mask — {@link #applyResize} and
         * {@link #applyLiveOffset} test the bits rather than switching on a single edge.
         */
        private int hitTestEdgeStrip(float x, float y) {
            int axes = grippedAxes();
            if (axes == 0) return 0;
            computeHandleAnchors();
            // Deliberately larger than the drawn bracket: a fingertip is much bigger than the mark.
            // Unlike a full-length edge strip this is still a shape the user can locate.
            float t = dp(GRIP_TOUCH_DP);
            boolean onCorner = x >= aRight  - t && x <= aRight  + t
                            && y >= aBottom - t && y <= aBottom + t;
            return onCorner ? axes : 0;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    // A new touch wins over a settle still in flight; without this the animator
                    // would keep writing liveDx/liveDy underneath the new gesture.
                    cancelSettle();
                    computeBounds();
                    // Explicit rather than relying on hitTestEdgeStrip's call: that one returns
                    // early when the provider grips no axis, leaving aLeft..aBottom stale for
                    // everything below that reads them.
                    computeHandleAnchors();
                    activeEdge  = hitTestHandle(ev.getX(), ev.getY());
                    gesture     = activeEdge != 0 ? GESTURE_RESIZE : GESTURE_NONE;
                    // Bitmask, never equality — see the gripHeld field doc.
                    gripHeld    = (activeEdge & grippedAxes()) != 0;
                    // The pressed state is pixels, so setting the flag is only half of it: DOWN
                    // schedules no draw of its own, and the next one would be the first MOVE.
                    // A press-and-hold that never moves would then look exactly like no press at
                    // all — the very ambiguity this state exists to remove — and it lights up
                    // only once the drag is already under way, by which point the user knows.
                    // Invisible to a test that renders the frame on demand; caught on device.
                    if (gripHeld) invalidate();
                    movePending = false;
                    if (RDBG) dbgDumpDown(ev.getX(), ev.getY());
                    if (activeEdge == 0) {
                        boolean insideFrame = ev.getX() >= aLeft && ev.getX() <= aRight
                                && ev.getY() >= aTop && ev.getY() <= aBottom;
                        if (insideFrame) {
                            // Inside the outline but off the grip → a reposition, once the finger
                            // has moved far enough to mean it.
                            movePending = true;
                            if (RDBG) Log.d(RTAG, "  => inside the frame, off the grip:"
                                    + " a MOVE gesture arms here and starts at "
                                    + touchSlop + "px of travel");
                        } else {
                            // Tap outside the frame → dismiss. Measured against the drawn frame,
                            // not the widget: the frame sits outside the widget so the grabbable
                            // margin is outside it too, and dismissing there would make the target
                            // unusable. A slack of one touch strip keeps a near-miss on a handle
                            // from closing it.
                            float slack = dp(TOUCH_STRIP_DP);
                            if (ev.getX() < aLeft - slack || ev.getX() > aRight + slack
                                    || ev.getY() < aTop - slack || ev.getY() > aBottom + slack) {
                                if (RDBG) Log.d(RTAG,
                                        "  => TAP-OUTSIDE DISMISS: outside frame+slack("
                                        + Math.round(slack) + "px). Overshoot: left="
                                        + Math.round((aLeft - slack) - ev.getX()) + " right="
                                        + Math.round(ev.getX() - (aRight + slack)) + " top="
                                        + Math.round((aTop - slack) - ev.getY()) + " bottom="
                                        + Math.round(ev.getY() - (aBottom + slack))
                                        + "  (only the positive one fired)");
                                dismissResizeFrame();
                                return true;
                            }
                            // In the slack margin — consume without action.
                        }
                    }
                    touchStartX = ev.getX();
                    touchStartY = ev.getY();
                    committedDeltaX = committedDeltaY = 0;
                    // A cancelled settle leaves the offset part-way home rather than at zero
                    // (cancelSettle deliberately skips endSettle, so the incoming gesture owns
                    // the state). Without this the frame would open the new drag displaced by
                    // whatever was left of the last one.
                    liveDx = liveDy = 0f;
                    startCellX  = item.getCellX();
                    startCellY  = item.getCellY();
                    startSpanX  = item.getSpanX();
                    startSpanY  = item.getSpanY();
                    if (RDBG) {
                        dbgMoves = dbgApplied = 0;
                        dbgMaxDx = dbgMaxDy = 0f;
                        dbgDeadTravel = -1f;
                        dbgLastDeltaX = dbgLastDeltaY = Integer.MIN_VALUE;
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (RDBG) {
                        dbgMoves++;
                        dbgMaxDx = Math.max(dbgMaxDx, Math.abs(ev.getX() - touchStartX));
                        dbgMaxDy = Math.max(dbgMaxDy, Math.abs(ev.getY() - touchStartY));
                    }
                    if (movePending) {
                        // Re-anchor at the slop crossing rather than at the DOWN, so the frame
                        // starts tracking the finger from where the move began instead of
                        // jumping a slop's worth the instant it does.
                        if (Math.hypot(ev.getX() - touchStartX, ev.getY() - touchStartY)
                                > touchSlop) {
                            movePending = false;
                            gesture     = GESTURE_MOVE;
                            touchStartX = ev.getX();
                            touchStartY = ev.getY();
                            committedDeltaX = committedDeltaY = 0;
                            startCellX = item.getCellX();
                            startCellY = item.getCellY();
                            if (RDBG) Log.d(RTAG, "  => MOVE gesture started at ("
                                    + Math.round(ev.getX()) + "," + Math.round(ev.getY())
                                    + ") from cell [" + startCellX + "," + startCellY + "]");
                        }
                    }
                    if (gesture == GESTURE_RESIZE) {
                        liveDx = ev.getX() - touchStartX;
                        liveDy = ev.getY() - touchStartY;
                        applyResize(ev.getX(), ev.getY());
                        invalidate();   // the frame follows the finger, not just the committed span
                    } else if (gesture == GESTURE_MOVE) {
                        liveDx = ev.getX() - touchStartX;
                        liveDy = ev.getY() - touchStartY;
                        applyMove(ev.getX(), ev.getY());
                        invalidate();   // the frame follows the finger, not just the committed cell
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (RDBG) dbgDumpUp(ev.getActionMasked() == MotionEvent.ACTION_CANCEL);
                    movePending = false;
                    // The finger is gone, so the grip is no longer held even though the frame is
                    // still moving: activeEdge stays set for the settle below, and painting the
                    // pressed state off it would leave the grip lit for SETTLE_MS after release.
                    gripHeld = false;
                    // Slide the live offset onto the committed cells rather than dropping it.
                    startSettle();
                    return true;
            }
            return false;
        }

        /**
         * Slide the dragged edge to follow the finger. Applied after computeHandleAnchors so the
         * frame tracks continuously while the committed span still changes only in whole cells.
         * The offset is bounded by the same span limits applyResize enforces, so the frame cannot
         * be dragged somewhere the widget could never actually go.
         */
        private void applyLiveOffset() {
            if (gesture == GESTURE_NONE || (liveDx == 0 && liveDy == 0)) return;
            if (gesture == GESTURE_MOVE) {
                // The whole outline slides.
                float resX = liveResidual(liveDx, committedCellsX(), cellWidth);
                float resY = liveResidual(liveDy, committedCellsY(), cellHeight);
                aLeft += resX; aRight  += resX;
                aTop  += resY; aBottom += resY;
                return;
            }
            float minW = Math.max(1, minSpanCellsX()) * (float) cellWidth;
            float minH = Math.max(1, minSpanCellsY()) * (float) cellHeight;
            // Bitmask rather than a switch: the corner grip is EDGE_RIGHT | EDGE_BOTTOM and has
            // to move both edges within one drag.
            // Only the residual: aRight/aBottom already carry the committed cells (computeBounds
            // reads the item's current span), so adding the raw liveDx counted them twice.
            float resX = liveResidual(liveDx, committedCellsX(), cellWidth);
            float resY = liveResidual(liveDy, committedCellsY(), cellHeight);
            if ((activeEdge & EDGE_RIGHT)  != 0) aRight  = Math.max(aRight  + resX, aLeft + minW);
            if ((activeEdge & EDGE_BOTTOM) != 0) aBottom = Math.max(aBottom + resY, aTop  + minH);
        }

        /**
         * Whole cells of this gesture that the frame's committed edge already carries, which is
         * what {@link HomescreenView#liveResidual} has to be told to subtract.
         *
         * <p>The two gestures answer this differently and must keep doing so. A resize is clamped
         * back to what fits, so its committed cells are {@code committedDeltaX}; a move is refused
         * outright whenever the target overlaps a neighbour, so the cells it actually took are
         * {@code item.getCellX() - startCellX} and subtracting the requested delta would drag the
         * frame off the finger by exactly the cells the widget never moved.
         *
         * <p>Extracted only so {@link #startSettle()} cannot answer it differently: the settle
         * animates the residual to zero, so if its idea of "committed" drifted from this one the
         * frame would come to rest somewhere the widget is not.
         */
        private int committedCellsX() {
            return gesture == GESTURE_MOVE ? item.getCellX() - startCellX : committedDeltaX;
        }

        private int committedCellsY() {
            return gesture == GESTURE_MOVE ? item.getCellY() - startCellY : committedDeltaY;
        }

        // ---------------------------------------------------------------------
        // Settle on release
        // ---------------------------------------------------------------------

        /**
         * Ease the frame from where the finger left it onto the committed cells, instead of
         * teleporting there.
         *
         * <p>The frame tracks the finger continuously, and the span commits in whole cells with a
         * 0.66-cell dead band, so at release the outline is up to 0.66 of a cell away from the
         * footprint the widget actually has — measured on device that is ~210px across at
         * cellWidth=318. Dropping the offset in one frame made that gap read as the frame
         * *jumping* at the exact moment the user stopped, which is indistinguishable from a
         * refused resize snapping back. Stock Launcher3 animates the same transition
         * ({@code AppWidgetResizeFrame.snapToWidget}) over {@code SNAP_DURATION = 150ms}.
         *
         * <p>What animates is the {@link HomescreenView#liveResidual} — the part of the drag the
         * committed cells do not already carry — driven to zero. Animating {@code liveDx} to zero
         * instead would run the frame back to where the drag STARTED, because the committed cells
         * live in the frame's own edge and the residual is what is left over after them; that is
         * the mirror image of the double-counting bug that once made the frame run at twice the
         * finger's speed.
         */
        private static final int SETTLE_MS = 150; // stock's AppWidgetResizeFrame.SNAP_DURATION

        private android.animation.ValueAnimator settleAnim;

        private void startSettle() {
            cancelSettle();
            // liveDx at which the residual is zero: the frame drawn exactly on the committed
            // cells, which is also what applyLiveOffset's early return produces at rest.
            final float toX = committedCellsX() * (float) cellWidth;
            final float toY = committedCellsY() * (float) cellHeight;
            final float fromX = liveDx, fromY = liveDy;
            if (gesture == GESTURE_NONE || (fromX == toX && fromY == toY)) {
                endSettle();
                return;
            }
            android.animation.ValueAnimator a =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(SETTLE_MS);
            a.setInterpolator(new android.view.animation.DecelerateInterpolator());
            a.addUpdateListener(anim -> {
                float t = (float) anim.getAnimatedValue();
                liveDx = fromX + (toX - fromX) * t;
                liveDy = fromY + (toY - fromY) * t;
                invalidate();
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator which) {
                    // Only the animator still installed may clear the state: cancelSettle() also
                    // routes through here, and by then a new gesture has usually replaced it.
                    if (settleAnim == which) endSettle();
                }
            });
            settleAnim = a;
            a.start();
        }

        /** Drops the drag state, with the frame already at rest on the committed cells. */
        private void endSettle() {
            settleAnim  = null;
            activeEdge  = 0;
            gesture     = GESTURE_NONE;
            gripHeld    = false;
            liveDx = liveDy = 0f;
            invalidate();
        }

        private void cancelSettle() {
            android.animation.ValueAnimator a = settleAnim;
            if (a == null) return;
            settleAnim = null;   // so the end listener leaves the incoming gesture alone
            a.cancel();
        }

        @Override
        protected void onDetachedFromWindow() {
            // dismissResizeFrame() can remove the overlay mid-settle — from a refresh, a back
            // press, or showResizeFrame re-opening. An animator left running would keep
            // invalidating a detached view.
            cancelSettle();
            super.onDetachedFromWindow();
        }

        /** Test-only: whether a settle animation is in flight. */
        boolean isSettlingForTest() {
            return settleAnim != null;
        }

        /**
         * Test-only: drive the settle to {@code ms} into its {@link #SETTLE_MS} run, without a
         * clock.
         *
         * <p>Idling the looper cannot sample mid-flight reliably — measured on nixos-2, a 75ms
         * idle ran the whole 150ms animation to completion — and it also re-lays the view out at
         * Robolectric's default window size, which quietly invalidates every geometry assertion
         * made after it. Setting the play time directly is exact, and what needs pinning here is
         * the interpolation *target*: a settle aimed at zero rather than at the committed cells
         * lands correctly and is only wrong in between.
         */
        void advanceSettleForTest(long ms) {
            if (settleAnim != null) settleAnim.setCurrentPlayTime(ms);
        }

        /** Test-only: run the settle straight to its end, listeners and all. */
        void finishSettleForTest() {
            if (settleAnim != null) settleAnim.end();
        }

        /** Minimum horizontal span in cells, from the provider's PIXEL minimums. */
        private int minSpanCellsX() {
            int cols = store != null ? store.columns() : HomeScreenStore.DEFAULT_COLS;
            int minWPx = info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth;
            if (cellWidth <= 0) return 1;
            return Math.max(1, Math.min(cols, (int) Math.ceil(minWPx / (float) cellWidth)));
        }

        /** Minimum vertical span in cells, from the provider's PIXEL minimums. */
        private int minSpanCellsY() {
            int rows = store != null ? store.rows() : HomeScreenStore.DEFAULT_ROWS;
            int minHPx = info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight;
            if (cellHeight <= 0) return 1;
            return Math.max(1, Math.min(rows, (int) Math.ceil(minHPx / (float) cellHeight)));
        }

        /**
         * Slide the widget to the cell the finger has dragged it to. Same shape as
         * {@link #applyResize}, and deliberately so: whole-cell steps with the same hysteresis
         * band, committed as the drag crosses them rather than on release, and clamped back
         * toward where the gesture started when a neighbour is in the way instead of refusing
         * outright — a move that silently does nothing is indistinguishable from a frame that
         * never took the touch, which is exactly the failure mode resize spent months in.
         *
         * <p>The span never changes here, and the item never leaves its page: paging is off for
         * the life of the frame, so there is no other screen to land on.
         */
        private void applyMove(float x, float y) {
            if (store == null || cellWidth == 0 || cellHeight == 0) return;
            int cols = store.columns();
            int rows = store.rows();
            int spanX = item.getSpanX();
            int spanY = item.getSpanY();

            float dx = x - touchStartX;
            float dy = y - touchStartY;
            // The same 0.66-cell dead band the span uses, and for the same reason: Math.round
            // commits at the midpoint, so finger tremble at a boundary would shuttle the widget
            // between two cells and re-lay out the page on every wobble.
            int deltaCellX = spanDelta(dx, cellWidth,  committedDeltaX);
            int deltaCellY = spanDelta(dy, cellHeight, committedDeltaY);
            committedDeltaX = deltaCellX;
            committedDeltaY = deltaCellY;

            boolean trace = RDBG && (deltaCellX != dbgLastDeltaX || deltaCellY != dbgLastDeltaY);
            if (RDBG) { dbgLastDeltaX = deltaCellX; dbgLastDeltaY = deltaCellY; }

            int newCellX = clampCell(startCellX + deltaCellX, cols - spanX);
            int newCellY = clampCell(startCellY + deltaCellY, rows - spanY);
            int reqCellX = newCellX, reqCellY = newCellY;

            // Walk back toward the cell the gesture started on, which is free by construction
            // (the item itself is excluded), so this terminates. X retracts first, the way
            // applyResize's clamp does, so a blocked diagonal degrades into the vertical move
            // rather than into nothing.
            while (!store.isAreaFreeExcluding(
                    item.getScreen(), newCellX, newCellY, spanX, spanY, item)) {
                if (newCellX != startCellX) {
                    newCellX += Integer.signum(startCellX - newCellX);
                } else if (newCellY != startCellY) {
                    newCellY += Integer.signum(startCellY - newCellY);
                } else {
                    if (trace) Log.d(RTAG, "  MOVE CLAMP: blocked all the way back to the"
                            + " starting cell — nothing applied");
                    return;
                }
            }

            if (trace) {
                Log.d(RTAG, "applyMove finger d=(" + Math.round(dx) + "," + Math.round(dy) + ")px"
                        + " cell=" + cellWidth + "x" + cellHeight + "px"
                        + " -> deltaCells=(" + deltaCellX + "," + deltaCellY + ")"
                        + " start=[" + startCellX + "," + startCellY + "]"
                        + " requested=[" + reqCellX + "," + reqCellY + "]"
                        + (newCellX != reqCellX || newCellY != reqCellY
                                ? " CLAMPED to [" + newCellX + "," + newCellY + "]" : ""));
            }

            if (newCellX == item.getCellX() && newCellY == item.getCellY()) return;

            store.moveItem(item, item.getScreen(), newCellX, newCellY);

            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) wrapper.getLayoutParams();
            lp.cellX = item.getCellX();
            lp.cellY = item.getCellY();
            wrapper.setLayoutParams(lp); // triggers requestLayout on the CellLayout

            if (RDBG) {
                dbgApplied++;
                double travel = Math.hypot(dx, dy);
                if (dbgDeadTravel < 0f) dbgDeadTravel = (float) travel;
                Log.d(RTAG, "  MOVED -> [" + item.getCellX() + "," + item.getCellY() + "] after "
                        + Math.round(travel) + "px of finger travel");
            }

            computeBounds();
            invalidate();
        }

        /** Clamps a cell index into {@code [0, max]}; {@code max} may be negative on a full grid. */
        private int clampCell(int v, int max) {
            return Math.max(0, Math.min(Math.max(0, max), v));
        }

        private void applyResize(float x, float y) {
            if (store == null || cellWidth == 0 || cellHeight == 0) return;
            int cols = store.columns();
            int rows = store.rows();

            // Minimum span from provider info (px → cells).
            //
            // These fields are already in PIXELS: the framework converts the dp values declared in
            // the provider XML by display density when it parses them. Multiplying by density
            // again made every minimum 3.5x too large on a 560dpi device — enough to clamp
            // essentially any widget to its current span, so no drag could ever change anything.
            // That, not the handles, is why resize appeared not to work at all.
            int minWPx = info.minResizeWidth  > 0 ? info.minResizeWidth  : info.minWidth;
            int minHPx = info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight;
            int minSpanX = Math.max(1, Math.min(cols, (int) Math.ceil(minWPx / (float) cellWidth)));
            int minSpanY = Math.max(1, Math.min(rows, (int) Math.ceil(minHPx / (float) cellHeight)));

            float dx = x - touchStartX;
            float dy = y - touchStartY;
            // Stock's RESIZE_THRESHOLD. Plain Math.round() commits at half a cell, so a few px of
            // finger jitter either side of the midpoint toggles the span and the widget re-lays
            // out on every wobble. Measured on device: 478px -> 2 cells, 471px -> 1 cell, i.e. a
            // 7px tremble flipping the widget between 3x1 and 4x1 repeatedly. Requiring 0.66 of a
            // cell puts a dead band around the boundary. This is only tolerable together with the
            // live frame above: on its own it would widen the dead travel from 159px to 210px.
            int deltaCellX = spanDelta(dx, cellWidth,  committedDeltaX);
            int deltaCellY = spanDelta(dy, cellHeight, committedDeltaY);
            committedDeltaX = deltaCellX;
            committedDeltaY = deltaCellY;

            // Log only when the whole-cell delta changes, not on every MOVE: the interesting
            // moments are the snaps and the refusals, and 60 lines a second buries both.
            boolean trace = RDBG && (deltaCellX != dbgLastDeltaX || deltaCellY != dbgLastDeltaY);
            if (RDBG) { dbgLastDeltaX = deltaCellX; dbgLastDeltaY = deltaCellY; }

            int newCellX = startCellX;
            int newCellY = startCellY;
            int newSpanX = startSpanX;
            int newSpanY = startSpanY;

            // Bitmask so one corner drag applies both axes. Only the bottom and right edges move:
            // the grip is anchored to the widget's top-left, so cellX/cellY never change and a
            // resize cannot walk the widget off its own origin.
            if ((activeEdge & EDGE_RIGHT) != 0) {
                newSpanX = Math.max(minSpanX,
                        Math.min(cols - startCellX, startSpanX + deltaCellX));
            }
            if ((activeEdge & EDGE_BOTTOM) != 0) {
                newSpanY = Math.max(minSpanY,
                        Math.min(rows - startCellY, startSpanY + deltaCellY));
            }

            int reqCellX = newCellX, reqCellY = newCellY, reqSpanX = newSpanX, reqSpanY = newSpanY;
            if (trace) {
                Log.d(RTAG, "applyResize edge=" + edgeName(activeEdge)
                        + " finger d=(" + Math.round(dx) + "," + Math.round(dy) + ")px"
                        + " cell=" + cellWidth + "x" + cellHeight + "px"
                        + " -> deltaCells=(" + deltaCellX + "," + deltaCellY + ")");
                Log.d(RTAG, "  minSpan=" + minSpanX + "x" + minSpanY
                        + " from minPx=" + minWPx + "x" + minHPx
                        + "; start=[" + startCellX + "," + startCellY + " "
                        + startSpanX + "x" + startSpanY
                        + "] requested=[" + reqCellX + "," + reqCellY + " "
                        + reqSpanX + "x" + reqSpanY + "]");
            }

            // Clamp to the largest footprint that actually fits, rather than refusing outright.
            //
            // The old code returned as soon as the requested footprint overlapped anything, which
            // on an empty page never happens and on a real home screen almost always does — so
            // every handle looked dead while the frame drew perfectly. Growing now stops against
            // the neighbour instead of doing nothing.
            //
            // Terminates: each step moves one cell back toward the widget's own starting
            // footprint, which is free by definition (the item itself is excluded).
            while (!store.isAreaFreeExcluding(
                    item.getScreen(), newCellX, newCellY, newSpanX, newSpanY, item)) {
                // Bitmask, not equality: the corner grip is EDGE_RIGHT | EDGE_BOTTOM, and an
                // `activeEdge == EDGE_RIGHT` test matches neither bit of it — the loop fell
                // straight through to the bail-out below and every blocked grow silently did
                // nothing, which is the exact symptom this clamp was written to remove.
                // Shrink the axes back one cell at a time; the origin never moves, so only the
                // spans are in play.
                if ((activeEdge & EDGE_RIGHT) != 0 && newSpanX > startSpanX) {
                    newSpanX--;
                } else if ((activeEdge & EDGE_BOTTOM) != 0 && newSpanY > startSpanY) {
                    newSpanY--;
                } else {
                    if (trace) Log.d(RTAG, "  CLAMP: blocked all the way back to the original"
                            + " footprint by a neighbor — nothing applied");
                    return; // back at the original footprint and still blocked — nothing to do
                }
            }

            if (trace && (newCellX != reqCellX || newCellY != reqCellY
                    || newSpanX != reqSpanX || newSpanY != reqSpanY)) {
                Log.d(RTAG, "  CLAMP: a neighbor cut it back to [" + newCellX + "," + newCellY
                        + " " + newSpanX + "x" + newSpanY + "]");
            }

            // No change — skip.
            if (newSpanX == item.getSpanX() && newSpanY == item.getSpanY()
                    && newCellX == item.getCellX() && newCellY == item.getCellY()) {
                if (trace) Log.d(RTAG, "  no-op: already at [" + item.getCellX() + ","
                        + item.getCellY() + " " + item.getSpanX() + "x" + item.getSpanY() + "]");
                return;
            }

            // Apply position change (if any).
            if (newCellX != item.getCellX() || newCellY != item.getCellY()) {
                store.moveItem(item, item.getScreen(), newCellX, newCellY);
            }
            // Apply span change (if any).
            if (newSpanX != item.getSpanX() || newSpanY != item.getSpanY()) {
                store.setSpan(item, newSpanX, newSpanY);
            }

            // Update the wrapper's CellLayout.LayoutParams so the page re-lays out correctly.
            CellLayout.LayoutParams lp = (CellLayout.LayoutParams) wrapper.getLayoutParams();
            lp.cellX = item.getCellX();
            lp.cellY = item.getCellY();
            lp.spanX = item.getSpanX();
            lp.spanY = item.getSpanY();
            wrapper.setLayoutParams(lp); // triggers requestLayout on the CellLayout

            // Notify the widget of its new size via the shared helper (API-31 SizeF overload
            // where available) so interactive resize and creation-time sizing agree.
            if (wrapper.getChildCount() > 0 && wrapper.getChildAt(0) instanceof AppWidgetHostView) {
                WidgetSizeHelper.applySize((AppWidgetHostView) wrapper.getChildAt(0),
                        item.getSpanX(), item.getSpanY(), cellWidth, cellHeight,
                        getResources().getDisplayMetrics().density);
            }

            if (RDBG) {
                dbgApplied++;
                double travel = Math.hypot(dx, dy);
                if (dbgDeadTravel < 0f) dbgDeadTravel = (float) travel;
                Log.d(RTAG, "  APPLIED -> [" + item.getCellX() + "," + item.getCellY() + " "
                        + item.getSpanX() + "x" + item.getSpanY() + "] after "
                        + Math.round(travel) + "px of finger travel");
            }

            // Recompute and redraw.
            computeBounds();
            invalidate();
        }

        // ---------------------------------------------------------------------
        // Debug trace (HomescreenView.RDBG). Nothing below runs in a release build.
        // ---------------------------------------------------------------------

        /**
         * One block per ACTION_DOWN, answering the questions a device session actually has:
         * which handle (if any) was hit, where the touch landed relative to both the live strip
         * and the drawn knob, how the frame sits against the widget it is framing, and what
         * {@code activeEdge} became.
         */
        private void dbgDumpDown(float x, float y) {
            // hitTestHandle() has just refreshed aLeft..aBottom via computeHandleAnchors().
            android.graphics.Rect gi = gestureInsets();
            int t  = dp(TOUCH_STRIP_DP);
            int kr = dp(KNOB_R_DP);
            float cx = (aLeft + aRight) / 2f, cy = (aTop + aBottom) / 2f;
            Log.d(RTAG, "---- resize DOWN at (" + Math.round(x) + "," + Math.round(y) + ") ----");
            Log.d(RTAG, "  mode=" + resizeModeName(info.resizeMode)
                    + " item=[" + item.getCellX() + "," + item.getCellY() + " "
                    + item.getSpanX() + "x" + item.getSpanY() + "]"
                    + " cell=" + cellWidth + "x" + cellHeight + "px"
                    + " density=" + getResources().getDisplayMetrics().density
                    + " scrollX=" + HomescreenView.this.getScrollX()
                    + " frameTranslationX=" + Math.round(getTranslationX()));
            Log.d(RTAG, "  widget=[" + rnd(wLeft) + "," + rnd(wTop) + "," + rnd(wRight) + ","
                    + rnd(wBottom) + "] frame=[" + rnd(aLeft) + "," + rnd(aTop) + ","
                    + rnd(aRight) + "," + rnd(aBottom) + "]"
                    + " gestureInsets=" + gi.toShortString()
                    + " outset=" + Math.round(outsetPx()) + " strip=" + t + " knobR=" + kr);
            // Positive = the frame is drawn outside the widget (the intended outset). Negative =
            // the gesture-inset clamp pulled it inside, so the accent border cuts across the
            // widget rather than surrounding it. That asymmetry is a "focus" symptom all by
            // itself and it is invisible to every Robolectric test (insets are all zero there).
            Log.d(RTAG, "  frame outside widget by: L=" + rnd(wLeft - aLeft)
                    + " T=" + rnd(wTop - aTop) + " R=" + rnd(aRight - wRight)
                    + " B=" + rnd(aBottom - wBottom) + "px  (negative = drawn INSIDE the widget)");
            Log.d(RTAG, "  dist to edge LINE: L=" + rnd(Math.abs(x - aLeft))
                    + " R=" + rnd(Math.abs(x - aRight)) + " T=" + rnd(Math.abs(y - aTop))
                    + " B=" + rnd(Math.abs(y - aBottom)) + "  (live when <= " + t
                    + "px on an enabled edge)");
            Log.d(RTAG, "  dist to drawn KNOB: L=" + rnd((float) Math.hypot(x - aLeft, y - cy))
                    + " R=" + rnd((float) Math.hypot(x - aRight, y - cy))
                    + " T=" + rnd((float) Math.hypot(x - cx, y - aTop))
                    + " B=" + rnd((float) Math.hypot(x - cx, y - aBottom))
                    + "  (the knob is only " + kr + "px in radius — press-target and"
                    + " visual affordance are not the same shape)");
            Log.d(RTAG, "  => activeEdge=" + edgeName(activeEdge)
                    + (activeEdge == 0 ? "  *** NO HANDLE GRABBED ***" : ""));
            if (activeEdge != 0) {
                // applyResize measures dx/dy from the FINGER-DOWN point, not from the edge it
                // grabbed, so wherever inside the 22dp strip the finger landed becomes the new
                // origin. Two fingers grabbing the same edge 40px apart therefore snap at two
                // different places, and neither is the cell boundary. This line prints the offset
                // and the absolute coordinates the first snap will actually occur at.
                boolean horiz = activeEdge == EDGE_LEFT || activeEdge == EDGE_RIGHT;
                float edgeLine = activeEdge == EDGE_LEFT  ? aLeft
                               : activeEdge == EDGE_RIGHT ? aRight
                               : activeEdge == EDGE_TOP   ? aTop : aBottom;
                float here = horiz ? x : y;
                int   band = Math.round((horiz ? cellWidth : cellHeight) * RESIZE_THRESHOLD);
                Log.d(RTAG, "  grab offset from the edge LINE: " + rnd(here - edgeLine)
                        + "px (edge at " + rnd(edgeLine) + ", finger at " + rnd(here) + ")."
                        + " First span change at " + rnd(here - band) + " or " + rnd(here + band)
                        + " (" + RESIZE_THRESHOLD + " of a cell = " + band + "px away), measured"
                        + " from the grab point, not the cell boundary. The frame itself follows"
                        + " the finger from the first pixel.");
            }
        }

        /** One block per UP/CANCEL: how much of the drag produced no visible change at all. */
        private void dbgDumpUp(boolean cancelled) {
            Log.d(RTAG, "---- resize " + (cancelled ? "CANCEL" : "UP") + ": gesture="
                    + gestureName(gesture) + " edge=" + edgeName(activeEdge)
                    + " moves=" + dbgMoves + " applied=" + dbgApplied
                    + " maxTravel=(" + Math.round(dbgMaxDx) + "," + Math.round(dbgMaxDy) + ")px"
                    + " span " + startSpanX + "x" + startSpanY + " -> "
                    + item.getSpanX() + "x" + item.getSpanY()
                    + " cell [" + startCellX + "," + startCellY + "] -> ["
                    + item.getCellX() + "," + item.getCellY() + "]");
            Log.d(RTAG, "  dead travel before the first change: "
                    + (dbgDeadTravel < 0f ? "n/a — nothing ever changed"
                                          : Math.round(dbgDeadTravel) + "px")
                    + "; the span commits at " + RESIZE_THRESHOLD + " of a cell = "
                    + Math.round(cellWidth * RESIZE_THRESHOLD) + "px across, "
                    + Math.round(cellHeight * RESIZE_THRESHOLD) + "px down, and that dead band is"
                    + " re-anchored to the committed span after every change (so jitter cannot"
                    + " flip it back). The frame tracks the finger continuously in between, so"
                    + " 'dead travel' here means no SPAN change, not a frozen frame.");
        }

        private static String gestureName(int g) {
            switch (g) {
                case GESTURE_RESIZE: return "RESIZE (grip)";
                case GESTURE_MOVE:   return "MOVE (inside the frame)";
                default:             return "NONE";
            }
        }

        private static String edgeName(int edge) {
            switch (edge) {
                case EDGE_RIGHT:               return "RIGHT-only (horizontal-only widget)";
                case EDGE_BOTTOM:              return "BOTTOM-only (vertical-only widget)";
                case EDGE_RIGHT | EDGE_BOTTOM: return "CORNER (both axes)";
                case EDGE_LEFT:   return "LEFT";
                case EDGE_TOP:    return "TOP";
                case 0:           return "NONE";
                default:          return "MASK:" + edge;
            }
        }

        private static int rnd(float v) { return Math.round(v); }

        private int dp(int v) {
            return Math.round(v * getResources().getDisplayMetrics().density);
        }
    }
}
