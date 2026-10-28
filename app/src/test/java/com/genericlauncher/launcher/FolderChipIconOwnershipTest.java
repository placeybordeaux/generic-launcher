package com.genericlauncher.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A folder chip's mini icons must own their {@link Drawable} instances.
 *
 * <p>A {@code Drawable} has one set of bounds and every surface shares one instance per app.
 * {@link ImageView} configures its drawable's bounds once, when the drawable is set; the drawer's
 * canvas views (Mosaic, Most used, the six prototype drawers) call {@code setBounds} at absolute
 * view coordinates on every frame. So a canvas draw moved the shared icon out of any ImageView
 * still holding it, and the ImageView never put it back.
 *
 * <p>The folder chip was the only home-screen surface holding a raw instance — every other one
 * wraps the icon in a {@link BadgeDrawable}, which re-establishes bounds inside its own
 * {@code draw} and is therefore self-correcting. Opening the drawer renders the peek strip's
 * Mosaic and Honeycomb previews over the real app list, so <b>every</b> drawer open mutated the
 * bounds: the user opened the drawer, pressed Back, and the folder on the home screen was an
 * empty square until something rebuilt it (opening and closing the folder did, which is what made
 * it look intermittent).
 *
 * <p>The bounds assertions are the real oracle here. Asserting only that the instances differ
 * would keep passing if someone handed the chip a fresh instance that still shared bounds with
 * the icon somehow, and asserting only "the chip is not blank" would pass on an unmeasured view.
 */
@RunWith(RobolectricTestRunner.class)
public class FolderChipIconOwnershipTest {

    private static final int W = 1080;
    private static final int H = 2424;
    /** What a canvas drawer view does to the shared icon: bounds at absolute view coordinates. */
    private static final Rect DRAWER_BOUNDS = new Rect(700, 1200, 760, 1260);

    private ActivityController<Activity> controller;
    private Activity activity;
    private HomeScreenStore store;
    private HomescreenView view;
    private Map<String, AppInfo> apps;

    @Before
    public void setUp() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);

        HomeScreenStore.resetForTesting();
        NotificationDotsService.resetForTesting();
        new File(activity.getFilesDir(), "homescreen.json").delete();
        store = HomeScreenStore.get(activity);

        apps = new HashMap<>();
        apps.put("com.example.first", app("com.example.first", 0xFFFF0000));
        apps.put("com.example.second", app("com.example.second", 0xFF00FF00));

        store.replaceAll(new ArrayList<>(Arrays.asList(
                HomeItem.folder(0, 0, 0, "Tools",
                        Arrays.asList("com.example.first", "com.example.second")),
                HomeItem.folder(-1, 1, 0, "Dock folder",
                        Arrays.asList("com.example.first", "com.example.second")))));

        view = new HomescreenView(activity);
        view.setStore(store);
        view.setAppMap(apps);
        root.addView(view, W, H);
        view.refresh();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, W, H);
    }

    @After
    public void tearDown() {
        NotificationDotsService.resetForTesting();
        controller.pause().stop().destroy();
    }

    private static AppInfo app(String pkg, int color) {
        // A ColorDrawable has a constant state and an intrinsic size of -1, which is exactly the
        // shape ImageView's configureBounds treats as "fill the view" — the same path a real
        // adaptive icon takes for bounds ownership.
        return new AppInfo(pkg, pkg, new ColorDrawable(color));
    }

    /** Every ImageView under the view whose drawable came from one of the fixture's icons. */
    private List<ImageView> chipIcons() {
        List<ImageView> out = new ArrayList<>();
        collect(view, out);
        return out;
    }

    private void collect(View v, List<ImageView> out) {
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            // The folder chip's mini icons are the ColorDrawables; the badge is its own drawable.
            if (d instanceof ColorDrawable) {
                int c = ((ColorDrawable) d).getColor();
                if (c == 0xFFFF0000 || c == 0xFF00FF00) out.add((ImageView) v);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static String dump(View v, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        if (v instanceof ImageView) {
            Drawable d = ((ImageView) v).getDrawable();
            sb.append("[drawable=").append(d == null ? "null" : d.getClass().getSimpleName());
            if (d instanceof ColorDrawable) sb.append(" #").append(Integer.toHexString(((ColorDrawable) d).getColor()));
            sb.append("]");
        }
        sb.append("\n");
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(dump(g.getChildAt(i), depth + 1));
        }
        return sb.toString();
    }

    @Test
    public void theChipsMiniIconsAreNotTheSharedInstance() {
        List<ImageView> icons = chipIcons();
        assertFalse("no folder-chip icons were built; the fixture is not exercising the chip. Tree: "
                + dump(view, 0), icons.isEmpty());
        for (ImageView iv : icons) {
            for (AppInfo info : apps.values()) {
                assertNotSame("a folder chip is holding the shared icon instance, so the drawer "
                                + "moving its bounds empties the chip",
                        info.getIcon(), iv.getDrawable());
            }
        }
    }

    @Test
    public void aDrawerDrawDoesNotMoveTheIconsOutOfTheChip() {
        List<ImageView> icons = chipIcons();
        assertFalse(icons.isEmpty());

        Map<ImageView, Rect> before = new HashMap<>();
        for (ImageView iv : icons) before.put(iv, new Rect(iv.getDrawable().getBounds()));

        // The drawer opens: the peek strip renders the Mosaic and Honeycomb previews over the
        // real app list, and those canvas views set each icon's bounds where they draw it.
        for (AppInfo info : apps.values()) info.getIcon().setBounds(DRAWER_BOUNDS);

        for (ImageView iv : icons) {
            assertEquals("the drawer's draw moved a folder chip's icon: it now draws outside "
                            + "the chip and the folder looks empty",
                    before.get(iv), iv.getDrawable().getBounds());
        }
    }

    /**
     * Positive control for the test above: without it, "the bounds did not change" would also
     * hold for a chip whose icons were never given bounds at all.
     */
    @Test
    public void theChipsIconsHaveRealBoundsToBeginWith() {
        List<ImageView> icons = chipIcons();
        assertFalse(icons.isEmpty());
        for (ImageView iv : icons) {
            Rect b = iv.getDrawable().getBounds();
            assertNotNull(b);
            assertTrue("a chip icon was laid out with empty bounds, so this class could pass "
                    + "while measuring nothing: " + b, !b.isEmpty());
        }
    }
}
