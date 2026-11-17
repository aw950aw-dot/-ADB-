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
            Color.rgb(6, 13, 26),
            Color.rgb(15, 28, 46),
            Color.rgb(23, 40, 62),
            Color.rgb(239, 246, 255),
            Color.rgb(151, 171, 196),
            Color.rgb(65, 164, 245),
            Color.rgb(47, 68, 94),
            Color.rgb(11, 23, 39),
            Color.rgb(6, 13, 26),
            Color.rgb(6, 13, 26)
        )
    } else {
        UiPalette(
            false,
            Color.rgb(244, 248, 253),
            Color.WHITE,
            Color.rgb(233, 241, 251),
            Color.rgb(24, 41, 63),
            Color.rgb(91, 111, 134),
            Color.rgb(23, 105, 210),
            Color.rgb(211, 225, 240),
            Color.WHITE,
            Color.rgb(244, 248, 253),
            Color.WHITE
        )
    }
}
