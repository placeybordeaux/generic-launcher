package com.genericlauncher.launcher;

/**
 * Pure, testable helper that decides what action to perform when a drawer-app drag is dropped
 * onto a home-screen zone/cell.
 *
 * <p>This is a static utility class with no Android dependencies so it can be exercised by plain
 * JUnit tests (no Robolectric needed). All state comes in as parameters; no mutation occurs here.
 *
 * <h3>Decision matrix</h3>
 * <table>
 *   <tr><th>Target zone</th><th>Cell occupant</th><th>Decision</th></tr>
 *   <tr><td>GRID / DOCK (empty)</td><td>—</td><td>{@link Action#ADD}</td></tr>
 *   <tr><td>GRID / DOCK</td><td>APP (different package)</td><td>{@link Action#FOLDER_MERGE}</td></tr>
 *   <tr><td>GRID / DOCK</td><td>FOLDER</td><td>{@link Action#FOLDER_APPEND}</td></tr>
 *   <tr><td>GRID</td><td>WIDGET or same package</td><td>{@link Action#REJECT}</td></tr>
 *   <tr><td>REMOVE / OUTSIDE</td><td>—</td><td>{@link Action#REJECT}</td></tr>
 * </table>
 */
public final class DrawerDropDecider {

    private DrawerDropDecider() {}

    /** The action HomescreenView should take after calling {@link #decide}. */
    public enum Action {
        /** Add a new APP HomeItem at the target cell (cell is empty or zone is free). */
        ADD,
        /** Merge the incoming app with the existing APP at the target → create a FOLDER. */
        FOLDER_MERGE,
        /** Append the incoming app to the existing FOLDER at the target cell. */
        FOLDER_APPEND,
        /** Drop is invalid; do nothing (snap back). */
        REJECT
    }

    /**
     * Decide the action for a drawer-app drop.
     *
     * @param zone        The resolved {@link HomescreenView.DropTarget.Zone}.
     * @param occupant    The {@link HomeItem} currently occupying the target cell, or {@code null}
     *                    if the cell is free.
     * @param incomingPkg The composite package key of the dragged app (UserKeys scheme).
     * @return The action to perform.
     */
    public static Action decide(HomescreenView.DropTarget.Zone zone,
                                HomeItem occupant,
                                String incomingPkg) {
        // Only GRID and DOCK zones are valid drop targets for an incoming drawer app.
        if (zone != HomescreenView.DropTarget.Zone.GRID
                && zone != HomescreenView.DropTarget.Zone.DOCK) {
            return Action.REJECT;
        }

        // Empty cell → add.
        if (occupant == null) {
            return Action.ADD;
        }

        switch (occupant.getType()) {
            case APP:
                // Same package → reject (no point creating a single-item folder duplicate).
                if (occupant.getPackageName().equals(incomingPkg)) return Action.REJECT;
                return Action.FOLDER_MERGE;

            case FOLDER:
                return Action.FOLDER_APPEND;

            case WIDGET:
                // Cannot merge an app onto a widget.
                return Action.REJECT;

            default:
                return Action.REJECT;
        }
    }
}
