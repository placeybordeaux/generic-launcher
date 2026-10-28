package com.genericlauncher.launcher;

/**
 * Immutable marker object placed in {@code localState} when a drag originates from a drawer
 * app item (as opposed to an internal homescreen item being repositioned).
 *
 * <p>When {@link HomescreenView}'s {@code homeDragListener} sees
 * {@code event.getLocalState() instanceof DrawerDragState} on {@code ACTION_DRAG_STARTED}
 * it accepts the drag and sets up an incoming-app placeholder, then asks MainActivity to
 * dismiss the drawer overlay so the home grid is revealed for dropping.
 *
 * <p>The package key follows the same UserKeys composite scheme used everywhere else:
 * bare package name for personal apps, {@code "com.example#serialNumber"} for work-profile apps.
 */
public final class DrawerDragState {

    /** Composite package key (UserKeys scheme). */
    public final String packageKey;

    public DrawerDragState(String packageKey) {
        this.packageKey = packageKey;
    }
}
