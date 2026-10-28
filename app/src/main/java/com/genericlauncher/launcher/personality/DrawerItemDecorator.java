package com.genericlauncher.launcher.personality;

import android.widget.ImageView;
import android.widget.TextView;

/**
 * Seam: how one drawer cell looks, after the icon and label are bound.
 *
 * <p>Imports {@code android.widget}, so unlike {@link DrawerStrategy} it is not part of the
 * pure-JVM {@code SeamCheck}; see {@code docs/SEAM.md}.
 *
 * <p>Called once per drawer cell on bind. Use it to tint the icon, restyle or hide the label, swap in
 * a monogram for a missing icon, add a corner glyph — anything cell-local. The default is a no-op
 * (platform icon + label, untouched). A colour build's decorator is where per-app icon tinting lives.
 *
 * <p>Contract: decoration is cosmetic. Do not change what the cell launches, and do not attach or
 * remove click handling — the launcher owns the tap. Cells are recycled, so a decorator that sets a
 * property on one app's cell must reset it for the default case, or recycling will leak the look.
 */
public interface DrawerItemDecorator {

    /** Restyle {@code icon}/{@code label} for {@code app}. Called on every (re)bind; reset your changes. */
    void decorate(ImageView icon, TextView label, DrawerApp app);

    /** A decorator that changes nothing — the taste-free default. */
    DrawerItemDecorator NONE = new DrawerItemDecorator() {
        @Override public void decorate(ImageView icon, TextView label, DrawerApp app) { }
    };
}
