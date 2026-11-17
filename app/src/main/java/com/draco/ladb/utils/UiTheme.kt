package com.draco.ladb.utils

import android.content.Context
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

data class UiPalette(
    val dark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceAlt: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val outline: Int,
    val inputBackground: Int,
    val statusBar: Int,
    val navigationBar: Int
)

object UiTheme {
    private const val PREFS = "ui_preferences"
    private const val KEY_DARK = "dark_mode"

    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, true)

    fun applySavedMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (isDark(context)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun setDarkMode(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun palette(context: Context): UiPalette = if (isDark(context)) {
        UiPalette(
            true,
            Color.rgb(8, 14, 27),
            Color.rgb(18, 30, 48),
            Color.rgb(24, 39, 60),
            Color.rgb(239, 245, 255),
            Color.rgb(151, 169, 193),
            Color.rgb(73, 153, 230),
            Color.rgb(48, 68, 94),
            Color.rgb(20, 33, 51),
            Color.rgb(8, 14, 27),
            Color.rgb(8, 14, 27)
        )
    } else {
        UiPalette(
            false,
            Color.rgb(245, 248, 253),
            Color.WHITE,
            Color.rgb(235, 242, 250),
            Color.rgb(26, 42, 62),
            Color.rgb(91, 110, 132),
            Color.rgb(23, 105, 210),
            Color.rgb(215, 226, 239),
            Color.WHITE,
            Color.rgb(245, 248, 253),
            Color.WHITE
        )
    }
}
