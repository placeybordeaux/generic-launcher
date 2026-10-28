package com.genericlauncher.launcher.personality;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Seam: what renders behind the home screen and drawer.
 *
 * <p>It imports {@code android.*}, so unlike {@link DrawerStrategy} it is not part of the pure-JVM
 * {@code SeamCheck} — it is declared here in the app module beside the view code it draws into.
 * See {@code docs/SEAM.md}.
 *
 * <p>Return a {@link View} to be inserted at the back of the drawer's content stack (below the app
 * grid), or {@code null} for "nothing" — the theme's transparent window background and the system
 * wallpaper show through. {@link #NONE} is the taste-free default. A colour build's animated
 * rainbow field is just a {@code Background} that returns its animated view.
 *
 * <p>Contract: the returned view must not steal touches meant for the grid — make it
 * non-clickable / {@code android:clickable="false"} and let events fall through.
 */
public interface Background {

    /** Background view for the given host context, or {@code null} for wallpaper/solid only. */
    @Nullable
    View createView(Context context);

    /** Short stable id, for settings and the recipe table (e.g. {@code "solid"}). */
    String id();

    /**
     * Draws nothing of its own — the taste-free default.
     *
     * <p>A no-op instance rather than a {@code null} seam so callers never have to null-check
     * {@code Personality.BACKGROUND} itself; only its (documented, {@code @Nullable}) view.
     */
    Background NONE = new Background() {
        @Override @Nullable public View createView(Context context) { return null; }
        @Override public String id() { return "none"; }
    };
}
