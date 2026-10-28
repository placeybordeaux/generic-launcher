package com.genericlauncher.launcher;

/**
 * Layout used by the widget picker. Independent of the {@code widget_picker_fullscreen}
 * toggle, which swaps the container (dialog vs. full-screen sheet with search) and
 * composes with any layout.
 *
 * <p>Ordinals are persisted in SharedPreferences ({@code widget_picker_layout}) —
 * never reorder.
 */
public enum WidgetPickerLayout {
    /** Dense 48dp rows with app-name section headers. */
    COMPACT_LIST,
    /** Full-width cards with the preview scaled to the widget's real cell footprint. */
    PREVIEW_CARDS,
    /** Pixel-style: apps stack vertically, their widgets scroll horizontally. */
    APP_CAROUSELS;

    /** Labels for the settings picker, index-aligned with {@link #values()}. */
    public static String[] labels() {
        return new String[]{
                "Compact list  ·  dense rows",
                "Preview cards  ·  large true-size previews",
                "App carousels  ·  swipe through each app's widgets"
        };
    }
}
