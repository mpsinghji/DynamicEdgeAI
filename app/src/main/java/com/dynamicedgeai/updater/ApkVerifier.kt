package com.dynamicedgeai.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.dynamicedgeai.util.AppVersion
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Result of the pre-installation APK verification.
 */
data class VerificationResult(
    val isValid: Boolean,
    val failureReason: String? = null,
    val archiveVersionCode: Int = 0,
    val archiveVersionName: String = ""
)

/**
 * Validates APK compatibility, package integrity, and signing certificate continuity.
 */
object ApkVerifier {
    private const val TAG = "ApkVerifier"

    fun verifyDownloadedApk(
        context: Context,
        apkFile: File,
        expectedSha256: String? = null
    ): VerificationResult {
        // 1. File existence and basic size check
        if (!apkFile.exists()) {
            return VerificationResult(false, "APK file does not exist")
        }
        if (apkFile.length() < 1024 * 1024) {
            return VerificationResult(false, "APK file is incomplete or empty (${apkFile.length()} bytes)")
        }

        // 2. SHA-256 verification (if expected hash provided)
        if (!expectedSha256.isNullOrBlank()) {
            val calculatedHash = calculateSha256(apkFile)
            if (!calculatedHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                Log.e(TAG, "SHA-256 mismatch. Expected: $expectedSha256, Actual: $calculatedHash")
                return VerificationResult(false, "SHA-256 checksum mismatch")
            }
            Log.i(TAG, "SHA-256 verified successfully: $calculatedHash")
        }

        // 3. Package archive info inspection
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return VerificationResult(false, "Failed to parse APK package metadata (corrupted archive)")

        // 4. Validate Package Name
        if (archiveInfo.packageName != context.packageName) {
            Log.e(TAG, "Package name mismatch: ${archiveInfo.packageName} vs ${context.packageName}")
            return VerificationResult(
                false,
                "Package name mismatch: '${archiveInfo.packageName}' (expected '${context.packageName}')"
            )
        }

        // 5. Validate Version Code
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode
        }
        val currentVersionCode = AppVersion.versionCode

        if (archiveVersionCode <= currentVersionCode) {
            Log.e(TAG, "Version code not newer: archive=$archiveVersionCode, current=$currentVersionCode")
            return VerificationResult(
                false,
                "APK version (Build $archiveVersionCode) is not newer than current (Build $currentVersionCode)",
                archiveVersionCode,
                archiveInfo.versionName ?: ""
            )
        }

        // 6. Validate Signing Certificate Continuity
        val certMatch = verifySigningCertificateContinuity(context, archiveInfo)
        if (!certMatch) {
            Log.e(TAG, "Signing certificate mismatch! APK cannot update installed application.")
            return VerificationResult(
                false,
                "Signing certificate mismatch: the update was signed with a different key than the installed app.",
                archiveVersionCode,
                archiveInfo.versionName ?: ""
            )
        }

        Log.i(TAG, "APK verified successfully: v${archiveInfo.versionName} (Build $archiveVersionCode)")
        return VerificationResult(
            isValid = true,
            archiveVersionCode = archiveVersionCode,
            archiveVersionName = archiveInfo.versionName ?: ""
        )
    }

    private fun verifySigningCertificateContinuity(
        context: Context,
        archiveInfo: PackageInfo
    ): Boolean {
        return try {
            val pm = context.packageManager

            val installedSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                pkgInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                pkgInfo.signatures
            } ?: return false

            val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                archiveInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                archiveInfo.signatures
            } ?: return false

            if (installedSignatures.isEmpty() || archiveSignatures.isEmpty()) {
                return false
            }

            val installedShaSet = installedSignatures.map { getCertSha256(it.toByteArray()) }.toSet()
            val archiveShaSet = archiveSignatures.map { getCertSha256(it.toByteArray()) }.toSet()

            Log.d(TAG, "Installed cert SHA-256: $installedShaSet")
            Log.d(TAG, "Archive cert SHA-256: $archiveShaSet")

            // Any matching certificate ensures continuity
            installedShaSet.intersect(archiveShaSet).isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking certificate continuity", e)
            false
        }
    }

    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun getCertSha256(certBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(certBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
