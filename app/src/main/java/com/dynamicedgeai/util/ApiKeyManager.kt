package com.dynamicedgeai.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Supported Cloud AI Providers.
 */
enum class PlatformType(
    val id: String,
    val displayName: String,
    val defaultModel: String,
    val keyHint: String,
    val consoleUrl: String
) {
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        defaultModel = "gemini-1.5-flash",
        keyHint = "AIzaSy...",
        consoleUrl = "https://aistudio.google.com/apikey"
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        defaultModel = "gpt-4o-mini",
        keyHint = "sk-...",
        consoleUrl = "https://platform.openai.com/api-keys"
    ),
    GROQ(
        id = "groq",
        displayName = "Groq (Fast Llama)",
        defaultModel = "llama-3.3-70b-versatile",
        keyHint = "gsk_...",
        consoleUrl = "https://console.groq.com/keys"
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter (Universal)",
        defaultModel = "meta-llama/llama-3.3-70b-instruct",
        keyHint = "sk-or-...",
        consoleUrl = "https://openrouter.ai/keys"
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic Claude",
        defaultModel = "claude-3-5-haiku-latest",
        keyHint = "sk-ant-...",
        consoleUrl = "https://console.anthropic.com/settings/keys"
    );

    companion object {
        fun fromId(id: String): PlatformType {
            return values().firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GEMINI
        }
    }
}

/**
 * Manages user-configured API keys for Cloud AI services across multiple platforms.
 * All keys and platform selections are stored 100% client-side in SharedPreferences.
 */
object ApiKeyManager {

    private const val PREFS_NAME = "dynamicedgeai_settings"
    private const val KEY_ACTIVE_PLATFORM = "active_cloud_platform"
    private const val PREFIX_PLATFORM_KEY = "api_key_platform_"
    private const val PREFIX_CUSTOM_MODEL = "custom_model_platform_"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets the currently active Cloud AI platform (defaults to Gemini).
     */
    fun getActivePlatform(context: Context): PlatformType {
        val id = getPrefs(context).getString(KEY_ACTIVE_PLATFORM, PlatformType.GEMINI.id) ?: PlatformType.GEMINI.id
        return PlatformType.fromId(id)
    }

    /**
     * Sets the active Cloud AI platform.
     */
    fun setActivePlatform(context: Context, platform: PlatformType) {
        getPrefs(context).edit().putString(KEY_ACTIVE_PLATFORM, platform.id).apply()
    }

    /**
     * Retrieves the stored API key for a specific platform.
     */
    fun getApiKey(context: Context, platform: PlatformType): String {
        return getPrefs(context).getString("$PREFIX_PLATFORM_KEY${platform.id}", "")?.trim() ?: ""
    }

    /**
     * Saves an API key for a specific platform.
     */
    fun setApiKey(context: Context, platform: PlatformType, key: String) {
        getPrefs(context).edit().putString("$PREFIX_PLATFORM_KEY${platform.id}", key.trim()).apply()
    }

    /**
     * Checks if an API key is configured for a specific platform.
     */
    fun hasApiKey(context: Context, platform: PlatformType): Boolean {
        return getApiKey(context, platform).isNotEmpty()
    }

    /**
     * Clears the API key for a specific platform.
     */
    fun clearApiKey(context: Context, platform: PlatformType) {
        getPrefs(context).edit().remove("$PREFIX_PLATFORM_KEY${platform.id}").apply()
    }

    /**
     * Returns a masked preview of the API key for UI display (e.g. "AIzaSy...vvsM").
     */
    fun getMaskedApiKey(context: Context, platform: PlatformType): String {
        val key = getApiKey(context, platform)
        return when {
            key.isEmpty() -> "Not configured"
            key.length <= 10 -> "••••••••"
            else -> "${key.take(6)}••••••••${key.takeLast(4)}"
        }
    }

    // ── Active Platform Helpers ──────────────────────────────────────────────

    fun getMaskedApiKey(context: Context): String = getMaskedApiKey(context, getActivePlatform(context))

    fun hasApiKey(context: Context): Boolean = hasApiKey(context, getActivePlatform(context))

    // ── Legacy Gemini Helpers for backward compatibility ─────────────────────

    fun getGeminiApiKey(context: Context): String = getApiKey(context, PlatformType.GEMINI)

    fun setGeminiApiKey(context: Context, key: String) = setApiKey(context, PlatformType.GEMINI, key)

    fun hasGeminiApiKey(context: Context): Boolean = hasApiKey(context, PlatformType.GEMINI)

    fun clearGeminiApiKey(context: Context) = clearApiKey(context, PlatformType.GEMINI)
}
