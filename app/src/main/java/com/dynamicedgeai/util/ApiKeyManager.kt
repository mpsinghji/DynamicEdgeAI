package com.dynamicedgeai.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages user-configured API keys for Cloud AI services (e.g. Google Gemini API).
 * Replaces hardcoded keys with secure on-device persistence in SharedPreferences.
 */
object ApiKeyManager {

    private const val PREFS_NAME = "dynamicedgeai_settings"
    private const val KEY_GEMINI_API = "user_gemini_api_key"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves the stored Gemini API key, or empty string if not set.
     */
    fun getGeminiApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_GEMINI_API, "")?.trim() ?: ""
    }

    /**
     * Stores the user's Gemini API key.
     */
    fun setGeminiApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString(KEY_GEMINI_API, key.trim()).apply()
    }

    /**
     * Checks if a valid Gemini API key is configured.
     */
    fun hasGeminiApiKey(context: Context): Boolean {
        return getGeminiApiKey(context).isNotEmpty()
    }

    /**
     * Clears the stored Gemini API key.
     */
    fun clearGeminiApiKey(context: Context) {
        getPrefs(context).edit().remove(KEY_GEMINI_API).apply()
    }

    /**
     * Returns a masked preview of the API key for display in settings (e.g. "AIzaSy...vvsM").
     */
    fun getMaskedApiKey(context: Context): String {
        val key = getGeminiApiKey(context)
        return when {
            key.isEmpty() -> "Not configured"
            key.length <= 10 -> "••••••••"
            else -> "${key.take(6)}••••••••${key.takeLast(4)}"
        }
    }
}
