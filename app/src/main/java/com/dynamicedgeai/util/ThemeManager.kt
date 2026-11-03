package com.dynamicedgeai.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class AppThemeMode(val id: Int, val nightMode: Int, val title: String) {
    SYSTEM(0, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, "System Default"),
    DARK(1, AppCompatDelegate.MODE_NIGHT_YES, "Dark Mode"),
    LIGHT(2, AppCompatDelegate.MODE_NIGHT_NO, "Light Mode");

    companion object {
        fun fromId(id: Int): AppThemeMode {
            return values().firstOrNull { it.id == id } ?: SYSTEM
        }
    }
}

object ThemeManager {

    private const val PREFS_NAME = "ThemePrefs"
    private const val KEY_THEME_MODE = "app_theme_mode"

    fun getThemeMode(context: Context): AppThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getInt(KEY_THEME_MODE, AppThemeMode.SYSTEM.id)
        return AppThemeMode.fromId(id)
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_THEME_MODE, mode.id).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: AppThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    fun initTheme(context: Context) {
        val mode = getThemeMode(context)
        applyTheme(mode)
    }

    fun isNightMode(context: Context): Boolean {
        val mode = getThemeMode(context)
        return when (mode) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.SYSTEM -> {
                val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }
}

