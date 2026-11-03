package com.dynamicedgeai.updater

import com.google.gson.annotations.SerializedName
import java.io.File

/**
 * Data model for a GitHub release asset.
 */
data class GitHubAsset(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("size") val size: Long,
    @SerializedName("browser_download_url") val browserDownloadUrl: String,
    @SerializedName("content_type") val contentType: String?
)

/**
 * Data model for a GitHub Release from the GitHub REST API.
 */
data class GitHubRelease(
    @SerializedName("id") val id: Long,
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("draft") val draft: Boolean = false,
    @SerializedName("prerelease") val prerelease: Boolean = false,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("assets") val assets: List<GitHubAsset> = emptyList()
)

/**
 * Validated information about an available update.
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val releaseTitle: String,
    val releaseNotes: String,
    val assetName: String,
    val assetSize: Long,
    val downloadUrl: String,
    val expectedSha256: String? = null
) {
    val formattedSize: String
        get() {
            val mb = assetSize / (1024.0 * 1024.0)
            return String.format("%.1f MB", mb)
        }
}

/**
 * State of the auto-updater for UI presentation.
 */
sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val info: UpdateInfo) : UpdateState()
    data class UpToDate(val currentVersion: String) : UpdateState()
    data class Downloading(
        val info: UpdateInfo,
        val progress: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speed: String
    ) : UpdateState()
    data class DownloadCompleted(val info: UpdateInfo, val apkFile: File) : UpdateState()
    data class Installing(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
