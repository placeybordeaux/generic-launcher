package com.genericlauncher.launcher.color;

import android.graphics.Color;

import com.genericlauncher.launcher.personality.example.ColorGroups;

/**
 * The swatch that represents each {@link ColorGroups} group on screen.
 *
 * <p>Separate from {@code ColorGroups} because that class is pure Java and unit-testable with no
 * device, while these are {@code android.graphics} colour ints. Classification is maths; what
 * shade to paint it is taste — and taste lives in the flavour.
 */
final class GroupAccents {

    private GroupAccents() {}

    /** Accent colour for a group index, or grey for anything unrecognised. */
    static int of(int group) {
        switch (group) {
            case ColorGroups.BLACK:   return 0xFF212121;
            case ColorGroups.RED:     return 0xFFF44336;
            case ColorGroups.ORANGE:  return 0xFFFF9800;
            case ColorGroups.YELLOW:  return 0xFFFFEB3B;
            case ColorGroups.GREEN:   return 0xFF4CAF50;
            case ColorGroups.TEAL:    return 0xFF009688;
            case ColorGroups.BLUE:    return 0xFF2196F3;
            case ColorGroups.PURPLE:  return 0xFF9C27B0;
            case ColorGroups.PINK:    return 0xFFE91E63;
            case ColorGroups.BROWN:   return 0xFF795548;
            case ColorGroups.GRAY:    return 0xFF9E9E9E;
            case ColorGroups.WHITE:   return 0xFFE0E0E0;
            case ColorGroups.RAINBOW: return 0xFF7E57C2;
            default:                  return Color.GRAY;
        }
    }
}
