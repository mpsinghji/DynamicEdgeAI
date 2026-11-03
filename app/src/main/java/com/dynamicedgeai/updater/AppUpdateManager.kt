package com.dynamicedgeai.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.dynamicedgeai.util.AppVersion
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton manager coordinating GitHub release queries, APK downloads,
 * verification, and Android PackageInstaller handoff.
 */
class AppUpdateManager private constructor() {

    private val tag = "AppUpdateManager"
    private val gson = Gson()
    private val isCancelled = AtomicBoolean(false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    companion object {
        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager().also { instance = it }
            }
        }
    }

    /**
     * Checks the GitHub Releases API for new updates.
     */
    suspend fun checkForUpdate(context: Context) = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.Checking
        isCancelled.set(false)

        try {
            val request = Request.Builder()
                .url(GitHubConfig.RELEASES_API_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "DynamicEdgeAI-Updater")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    Log.i(tag, "No GitHub releases found for ${GitHubConfig.OWNER}/${GitHubConfig.REPO}")
                    _updateState.value = UpdateState.UpToDate(AppVersion.shortLabel)
                    return@withContext
                }
                val errorMsg = "GitHub API returned HTTP ${response.code}: ${response.message}"
                Log.e(tag, errorMsg)
                _updateState.value = UpdateState.Error(errorMsg)
                return@withContext
            }

            val bodyString = response.body?.string() ?: ""
            response.close()

            val listType = object : TypeToken<List<GitHubRelease>>() {}.type
            val releases: List<GitHubRelease> = try {
                gson.fromJson(bodyString, listType) ?: emptyList()
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse releases JSON", e)
                emptyList()
            }

            if (releases.isEmpty()) {
                Log.i(tag, "No releases published yet on GitHub.")
                _updateState.value = UpdateState.UpToDate(AppVersion.shortLabel)
                return@withContext
            }

            // Filter for stable releases (ignore drafts and prereleases)
            val stableReleases = releases.filter { !it.draft && !it.prerelease }
            if (stableReleases.isEmpty()) {
                Log.i(tag, "Only drafts/prereleases found on GitHub.")
                _updateState.value = UpdateState.UpToDate(AppVersion.shortLabel)
                return@withContext
            }

            val latestRelease = stableReleases.first()
            val candidateAsset = findCompatibleApkAsset(latestRelease)

            if (candidateAsset == null) {
                val msg = "Release ${latestRelease.tagName} has no compatible APK asset."
                Log.w(tag, msg)
                _updateState.value = UpdateState.Error(msg)
                return@withContext
            }

            val remoteVersionName = latestRelease.tagName.removePrefix("v").trim()
            val remoteVersionCode = parseVersionCode(latestRelease, remoteVersionName)
            val currentVersionCode = AppVersion.versionCode

            Log.i(tag, "Installed: Build $currentVersionCode (${AppVersion.versionName}) | Remote: Build $remoteVersionCode ($remoteVersionName)")

            if (remoteVersionCode > currentVersionCode) {
                val sha256 = extractExpectedSha256(latestRelease, candidateAsset.name)
                val info = UpdateInfo(
                    versionName = remoteVersionName,
                    versionCode = remoteVersionCode,
                    releaseTitle = latestRelease.name ?: "Version $remoteVersionName",
                    releaseNotes = latestRelease.body ?: "Bug fixes and performance improvements.",
                    assetName = candidateAsset.name,
                    assetSize = candidateAsset.size,
                    downloadUrl = candidateAsset.browserDownloadUrl,
                    expectedSha256 = sha256
                )
                _updateState.value = UpdateState.UpdateAvailable(info)
            } else {
                _updateState.value = UpdateState.UpToDate(AppVersion.shortLabel)
            }
        } catch (e: Exception) {
            Log.e(tag, "Check for update failed", e)
            _updateState.value = UpdateState.Error(e.localizedMessage ?: "Network connection error")
        }
    }

    /**
     * Downloads the APK file from GitHub Releases.
     */
    suspend fun downloadUpdate(context: Context, info: UpdateInfo) = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        _updateState.value = UpdateState.Downloading(
            info = info,
            progress = 0,
            bytesDownloaded = 0,
            totalBytes = info.assetSize,
            speed = "Connecting..."
        )

        val updatesDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
        if (!updatesDir.exists()) updatesDir.mkdirs()

        val destinationFile = File(updatesDir, info.assetName)
        val tempFile = File(updatesDir, "${info.assetName}.tmp")

        try {
            val request = Request.Builder()
                .url(info.downloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "DynamicEdgeAI-Updater")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("Server returned HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw Exception("Empty response body from asset URL")
            val totalBytes = if (body.contentLength() > 0) body.contentLength() else info.assetSize

            val inputStream: InputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(16384)
            var bytesRead: Int
            var totalRead = 0L
            var lastUpdate = System.currentTimeMillis()
            var lastRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled.get()) {
                    outputStream.close()
                    inputStream.close()
                    tempFile.delete()
                    _updateState.value = UpdateState.Idle
                    return@withContext
                }

                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastUpdate >= 200) {
                    val timeDiff = (now - lastUpdate) / 1000.0
                    val speedBytes = if (timeDiff > 0) ((totalRead - lastRead) / timeDiff).toLong() else 0L
                    val speedStr = formatSpeed(speedBytes)
                    val progress = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else 0

                    _updateState.value = UpdateState.Downloading(
                        info = info,
                        progress = progress,
                        bytesDownloaded = totalRead,
                        totalBytes = totalBytes,
                        speed = speedStr
                    )
                    lastUpdate = now
                    lastRead = totalRead
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            if (tempFile.exists()) {
                if (destinationFile.exists()) destinationFile.delete()
                tempFile.renameTo(destinationFile)
            }

            // Verify the downloaded APK before declaring completion
            val verification = ApkVerifier.verifyDownloadedApk(context, destinationFile, info.expectedSha256)
            if (!verification.isValid) {
                destinationFile.delete()
                val failMsg = "Downloaded update failed verification: ${verification.failureReason}"
                Log.e(tag, failMsg)
                _updateState.value = UpdateState.Error(failMsg)
                return@withContext
            }

            Log.i(tag, "Download verified: ${destinationFile.absolutePath} (${destinationFile.length()} bytes)")
            _updateState.value = UpdateState.DownloadCompleted(info, destinationFile)

        } catch (e: Exception) {
            Log.e(tag, "Download error", e)
            if (tempFile.exists()) tempFile.delete()
            _updateState.value = UpdateState.Error(e.localizedMessage ?: "Download failed")
        }
    }

    /**
     * Initiates the official Android PackageInstaller workflow for the verified APK.
     */
    fun installUpdate(context: Context, apkFile: File): Boolean {
        _updateState.value = UpdateState.Installing(apkFile)

        // 1. Verify before handoff
        val verification = ApkVerifier.verifyDownloadedApk(context, apkFile)
        if (!verification.isValid) {
            _updateState.value = UpdateState.Error("Security verification failed: ${verification.failureReason}")
            return false
        }

        // 2. Check install unknown apps permission (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                return false
            }
        }

        // 3. Launch PackageInstaller via FileProvider
        return try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to launch PackageInstaller", e)
            _updateState.value = UpdateState.Error("Failed to launch PackageInstaller: ${e.localizedMessage}")
            false
        }
    }

    fun cancelDownload() {
        isCancelled.set(true)
        _updateState.value = UpdateState.Idle
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    // ── Helper parsing methods ────────────────────────────────────────────────

    private fun findCompatibleApkAsset(release: GitHubRelease): GitHubAsset? {
        val assets = release.assets
        if (assets.isEmpty()) return null

        // Priority 1: Exact naming pattern DynamicEdgeAI-vX.Y.Z-release.apk or DynamicEdgeAI-*.apk
        val exactMatch = assets.firstOrNull {
            it.name.startsWith(GitHubConfig.ASSET_PREFIX, ignoreCase = true) &&
                    it.name.endsWith(GitHubConfig.ASSET_EXTENSION, ignoreCase = true)
        }
        if (exactMatch != null) return exactMatch

        // Priority 2: Any .apk asset with DynamicEdgeAI in the name
        val namedMatch = assets.firstOrNull {
            it.name.contains("DynamicEdgeAI", ignoreCase = true) &&
                    it.name.endsWith(".apk", ignoreCase = true)
        }
        if (namedMatch != null) return namedMatch

        // Priority 3: Any .apk asset
        return assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }

    private fun parseVersionCode(release: GitHubRelease, remoteVersionName: String): Int {
        // 1. Look for explicit versionCode in the release body or title
        val textToSearch = "${release.name ?: ""} \n ${release.body ?: ""}"
        val regex = Regex("(?i)(?:versionCode|build|buildNumber)[\\s:=]+(\\d+)")
        val match = regex.find(textToSearch)
        if (match != null) {
            val parsed = match.groupValues[1].toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }

        // 2. Synthesize versionCode from semantic version comparison
        return try {
            val parts = remoteVersionName.split(".").map { it.filter { char -> char.isDigit() }.toIntOrNull() ?: 0 }
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            val semverCode = major * 10000 + minor * 100 + patch

            val currentCode = AppVersion.versionCode
            if (semverCode > currentCode) semverCode else currentCode
        } catch (_: Exception) {
            AppVersion.versionCode
        }
    }

    private fun extractExpectedSha256(release: GitHubRelease, assetName: String): String? {
        val body = release.body ?: return null
        val regex = Regex("(?i)(?:SHA-?256|hash)[\\s:=]+([a-fA-F0-9]{64})")
        return regex.find(body)?.groupValues?.get(1)
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format("%.1f MB/s", mb)
        else String.format("%.0f KB/s", kb)
    }
}
