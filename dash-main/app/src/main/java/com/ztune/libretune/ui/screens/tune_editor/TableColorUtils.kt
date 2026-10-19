package com.ztune.libretune.ui.screens.tune_editor

import androidx.compose.ui.graphics.Color

/**
 * Utility for computing cell background colors based on a value's position
 * in the min–max range of the table.
 *
 * The gradient goes: **cyan** (low) → **yellow** (mid) → **red** (high).
 */
object TableColorUtils {

    // Gradient anchor colors (RGB 0..255).
    private const val CYAN_R = 0f;     private const val CYAN_G = 200f;   private const val CYAN_B = 215f
    private const val YELLOW_R = 255f; private const val YELLOW_G = 235f; private const val YELLOW_B = 0f
    private const val RED_R = 255f;    private const val RED_G = 0f;      private const val RED_B = 0f

    /**
 * Maps a [value] within [min]..[max] to a gradient color.
 *
 * - Values at or below [min] return pure cyan.
 * - Values at or above [max] return pure red.
 * - Values in the lower half of the range interpolate cyan → yellow.
 * - Values in the upper half of the range interpolate yellow → red.
 *
 * @param value The cell value to colorise.
 * @param min   The minimum expected value (maps to cyan).
 * @param max   The maximum expected value (maps to red).
 * @return A [Color] representing the cell's background.
 */
    fun valueToColor(value: Double, min: Double, max: Double): Color {
        // Guard: avoid division by zero when range is flat (e.g. all-zero table).
        // Flat tables render in the low-end color (cyan) so an untouched VE table
        // reads as "empty/low" instead of a neutral middle tone.
        val range = max - min
        if (range <= 0.0) return Color(CYAN_R / 255f, CYAN_G / 255f, CYAN_B / 255f, 0.85f)

        // Clamp the value into [min, max] so we never overshoot.
        val fraction = (value - min) / range
        val t = fraction.coerceIn(0.0, 1.0)

        // Cyan  (0.0) → Yellow (0.5) → Red    (1.0)
        // R:      0       → 255       → 255
        // G:    200       → 235       → 0
        // B:    215       → 0         → 0
        return when {
            t <= 0.5 -> {
                // Cyan → Yellow
                val localT = (t / 0.5).toFloat() // 0..1 within the first half
                Color(
                    red = (CYAN_R + (YELLOW_R - CYAN_R) * localT) / 255f,
                    green = (CYAN_G + (YELLOW_G - CYAN_G) * localT) / 255f,
                    blue = (CYAN_B + (YELLOW_B - CYAN_B) * localT) / 255f,
                    alpha = 0.85f
                )
            }
            else -> {
                // Yellow → Red
                val localT = ((t - 0.5) / 0.5).toFloat() // 0..1 within the second half
                Color(
                    red = (YELLOW_R + (RED_R - YELLOW_R) * localT) / 255f,
                    green = (YELLOW_G + (RED_G - YELLOW_G) * localT) / 255f,
                    blue = (YELLOW_B + (RED_B - YELLOW_B) * localT) / 255f,
                    alpha = 0.85f
                )
            }
        }
    }

    /**
 * Determines a readable text color for a given background color.
 * Uses luminance calculation to pick black or white text.
 *
 * @param backgroundColor The cell's background color.
 * @return [Color.White] on dark backgrounds, [Color.Black] on light ones.
 */
    fun contrastTextColor(backgroundColor: Color): Color {
        val luminance = 0.299f * backgroundColor.red +
                0.587f * backgroundColor.green +
                0.114f * backgroundColor.blue
        return if (luminance > 0.5f) Color.Black else Color.White
    }
}