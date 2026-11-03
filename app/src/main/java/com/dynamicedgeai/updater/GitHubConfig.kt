package com.dynamicedgeai.updater

/**
 * Centralized GitHub repository configuration for auto-updates.
 */
object GitHubConfig {
    const val OWNER = "mpsinghji"
    const val REPO = "DynamicEdgeAI"

    const val RELEASES_API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Preferred naming convention for release APK assets */
    const val ASSET_PREFIX = "DynamicEdgeAI-v"
    const val ASSET_EXTENSION = ".apk"
}
