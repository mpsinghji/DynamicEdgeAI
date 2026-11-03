package com.dynamicedgeai.util

import com.dynamicedgeai.BuildConfig

/**
 * Single source of truth for version information across all DynamicEdgeAI screens.
 */
object AppVersion {
    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE

    val fullLabel: String get() = "v$versionName (Build $versionCode)"
    val shortLabel: String get() = "v$versionName"
    val label: String get() = "v$versionName (Build $versionCode)"
}
