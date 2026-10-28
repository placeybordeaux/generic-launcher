package com.genericlauncher.launcher.color;

import android.graphics.Color;
import android.widget.ImageView;
import android.widget.TextView;

import com.genericlauncher.launcher.personality.AppDataSink;
import com.genericlauncher.launcher.personality.Background;
import com.genericlauncher.launcher.personality.BadgeAppearance;
import com.genericlauncher.launcher.personality.DrawerApp;
import com.genericlauncher.launcher.personality.DrawerItemDecorator;

/**
 * Everything the colour personality is made of, in one public façade.
 *
 * <p>{@code Personality} names these; the classes behind them stay package-private so the flavour's
 * internals are not part of anyone's API. A fork copying this build as a template gets one obvious
 * place to look and one obvious place to change.
 */
public final class ColorPersonality {

    private ColorPersonality() {}

    /** Colour group for an app — a pure lookup into the table {@link #DATA} fills. */
    public static int groupOf(DrawerApp app) {
        return ColorTable.groupOf(app);
    }

    /** The drawer's own spectrum, painted behind the grid. */
    public static final Background BACKGROUND = new RainbowBackground();

    /**
     * Tints each icon's backing toward the app's colour group, so a section reads as a block of
     * colour rather than as a header with arbitrary icons under it.
     *
     * <p>Two bits of contract compliance worth copying into your own decorator:
     *
     * <ul>
     *   <li><b>Set the property on every bind, never conditionally.</b> Cells are recycled, so a
     *       decorator that styles only the interesting case leaves the previous app's look on the
     *       next one. Here every app gets a background, including a colourless one.</li>
     *   <li><b>Touch only what you were handed.</b> The seam passes the icon and the label; it
     *       promises nothing about a parent, and reaching for {@code icon.getParent()} throws when
     *       there isn't one. {@code PersonalityWiringTest} calls this with a bare {@code ImageView}
     *       precisely to catch that — and did.</li>
     * </ul>
     */
    public static final DrawerItemDecorator DECORATOR = new DrawerItemDecorator() {
        @Override
        public void decorate(ImageView icon, TextView label, DrawerApp app) {
            int accent = GroupAccents.of(ColorTable.groupOf(app));
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(24f);
            bg.setColor(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent)));
            icon.setBackground(bg);
        }
    };

    /**
     * A badge in the complement of the app's own dominant colour.
     *
     * <p>Saturation and value are floored: rotating the hue of a grey by 180° leaves it grey, and
     * the "complement" of a black icon is still black — invisible against the badge's dark outline
     * ring. The floors guarantee a badge that is a real colour whatever the icon is.
     */
    public static final BadgeAppearance BADGE = new BadgeAppearance() {
        private static final float MIN_SAT = 0.70f;
        private static final float MIN_VAL = 0.75f;

        @Override
        public int fillColor(String appKey, int count) {
            float[] hsv = new float[3];
            Color.colorToHSV(ColorTable.colorOf(appKey), hsv);
            hsv[0] = (hsv[0] + 180f) % 360f;
            hsv[1] = Math.max(hsv[1], MIN_SAT);
            hsv[2] = Math.max(hsv[2], MIN_VAL);
            return Color.HSVToColor(hsv);
        }
    };

    /** Fills the colour table from the icons, on the loader's worker thread. */
    public static final AppDataSink DATA = ColorTable.SINK;
}
