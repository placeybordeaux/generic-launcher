package com.genericlauncher.launcher.personality;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

/**
 * Seam: what an app icon looks like <em>everywhere</em> it appears — drawer, home pages, dock and
 * folder popups — and what the personality hears about the icon being used.
 *
 * <p><b>Why a sixth seam.</b> {@link DrawerItemDecorator} only reaches the drawer; the home screen
 * draws the raw platform drawable (see the recipe table in {@code docs/SEAM.md}). A personality
 * whose icons change with use (the Lived-in branch: patina, dust, cobwebs) has to look the same on
 * every surface, and has to be told when an app is launched. Without this it could only be built
 * by editing four icon sites and the launch path, which is the seam being wrong.
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>{@link #treat} runs on the main thread on every icon (re)build, including drawer scroll
 *       binds — return quickly, and do any expensive rendering lazily and cached.</li>
 *   <li>Never mutate the {@code icon} you are handed: it is the app's shared drawable, drawn by
 *       every surface at once. Wrap it.</li>
 *   <li>The launcher wraps the result in its notification badge, so the badge is never treated.
 *       The badge forwards invalidations from the treated drawable, so it may animate.</li>
 *   <li>The lifecycle hooks must not throw — this is the HOME app.</li>
 * </ul>
 */
public interface IconTreatment {

    /** The drawable to show for {@code appKey}'s icon. Return {@code icon} for no change. */
    Drawable treat(Context context, @Nullable Drawable icon, String appKey);

    /** The launcher just launched {@code appKey} (main thread, before the app is in front). */
    default void onLaunched(Context context, String appKey) { }

    /** The launcher came back to the front. */
    default void onResume(Context context) { }

    /** The launcher left the front. */
    default void onPause() { }

    /** Icons exactly as the platform draws them — the taste-free default. */
    IconTreatment NONE = (context, icon, appKey) -> icon;
}
