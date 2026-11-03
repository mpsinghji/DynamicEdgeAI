package com.dynamicedgeai.util

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dynamicedgeai.local.LocalModel
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgressInfo(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int,
    val speedLabel: String,
    val etaLabel: String,
    val statusText: String
)

interface ResumableDownloadListener {
    fun onDownloadStarted(totalBytes: Long)
    fun onDownloadProgress(info: DownloadProgressInfo)
    fun onDownloadPaused(downloadedBytes: Long, totalBytes: Long)
    fun onWaitingForNetwork()
    fun onDownloadCompleted(destinationFile: File)
    fun onDownloadError(errorMessage: String)
}

class DownloadResumeManager(private val context: Context) {

    private val tag = "DownloadResumeManager"
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ModelDownloadResumePrefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var executor = Executors.newSingleThreadExecutor()
    private var downloadFuture: Future<*>? = null

    private val speedTracker = DownloadSpeedTracker()
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private val isWaitingForNetwork = AtomicBoolean(false)
    private val currentDownloaded = AtomicLong(0)

    private var currentModel: LocalModel? = null
    private var destinationFile: File? = null
    private var tempFile: File? = null
    private var listener: ResumableDownloadListener? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastUiUpdate = 0L
    private val UI_THROTTLE_MS = 800L

    // Active HTTP Call reference to safely abort dead sockets on network switch
    @Volatile
    private var activeCall: Call? = null

    // Connectivity monitoring for network transitions
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        try {
            connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(tag, "Default network available: $network")
                    handleNetworkRestored()
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (hasInternet && isValidated) {
                        handleNetworkRestored()
                    }
                }

                override fun onLost(network: Network) {
                    Log.w(tag, "Default network lost during download. Suspending transfer...")
                    if (isDownloading() || (!isPaused.get() && !isCancelled.get() && currentModel != null)) {
                        isWaitingForNetwork.set(true)
                        try { activeCall?.cancel() } catch (_: Exception) {}
                        mainHandler.post {
                            listener?.onWaitingForNetwork()
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager?.registerNetworkCallback(request, networkCallback!!)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to register network callback", e)
        }
    }

    private fun handleNetworkRestored() {
        if (isWaitingForNetwork.get() && !isPaused.get() && !isCancelled.get()) {
            isWaitingForNetwork.set(false)
            Log.i(tag, "Network validated. Resuming active download...")
            mainHandler.postDelayed({
                val model = currentModel
                val dest = destinationFile
                val lis = listener
                if (model != null && dest != null && lis != null && !isPaused.get() && !isCancelled.get()) {
                    startDownload(model, dest, lis, isResuming = true)
                }
            }, 600)
        }
    }

    fun isDownloading(): Boolean = downloadFuture != null && !downloadFuture!!.isDone && !isPaused.get() && !isCancelled.get()

    fun getPersistedDownloadedBytes(model: LocalModel): Long {
        return prefs.getLong("dl_bytes_${model.name}", 0L)
    }

    fun getPersistedTotalBytes(model: LocalModel): Long {
        return prefs.getLong("total_bytes_${model.name}", model.expectedSizeBytes)
    }

    private fun saveMetadata(model: LocalModel, downloaded: Long, total: Long, eTag: String?, lastModified: String?) {
        prefs.edit().apply {
            putLong("dl_bytes_${model.name}", downloaded)
            putLong("total_bytes_${model.name}", total)
            putString("etag_${model.name}", eTag)
            putString("last_mod_${model.name}", lastModified)
            apply()
        }
    }

    private fun clearMetadata(model: LocalModel) {
        prefs.edit().apply {
            remove("dl_bytes_${model.name}")
            remove("total_bytes_${model.name}")
            remove("etag_${model.name}")
            remove("last_mod_${model.name}")
            apply()
        }
    }

    fun startDownload(
        model: LocalModel,
        destination: File,
        downloadListener: ResumableDownloadListener,
        isResuming: Boolean = true
    ) {
        if (isDownloading() && currentModel == model) {
            Log.w(tag, "Download already active for ${model.displayName}")
            return
        }

        cancelDownload()

        this.currentModel = model
        this.destinationFile = destination
        this.tempFile = File(destination.absolutePath + ".part")
        this.listener = downloadListener

        isPaused.set(false)
        isCancelled.set(false)
        isWaitingForNetwork.set(false)
        speedTracker.reset()

        if (!isResuming) {
            clearMetadata(model)
            if (tempFile?.exists() == true) tempFile?.delete()
            if (destination.exists()) destination.delete()
        }

        executor = Executors.newSingleThreadExecutor()
        downloadFuture = executor.submit {
            executeDownload(model, destination, tempFile!!, downloadListener)
        }
    }

    private fun executeDownload(
        model: LocalModel,
        finalDest: File,
        partFile: File,
        downloadListener: ResumableDownloadListener
    ) {
        var inputStream: InputStream? = null
        var randomAccessFile: RandomAccessFile? = null

        try {
            partFile.parentFile?.mkdirs()
            var existingBytes = if (partFile.exists()) partFile.length() else 0L
            val persistedTotal = getPersistedTotalBytes(model)
            val persistedETag = prefs.getString("etag_${model.name}", null)

            // If existing file is somehow larger than expected or corrupt, start fresh
            if (persistedTotal > 0 && existingBytes > persistedTotal) {
                partFile.delete()
                existingBytes = 0L
            }

            // Build initial request with Range and If-Range headers
            val requestBuilder = Request.Builder().url(model.downloadUrl)
            if (existingBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                if (!persistedETag.isNullOrBlank()) {
                    requestBuilder.addHeader("If-Range", persistedETag)
                }
            }

            val call = client.newCall(requestBuilder.build())
            activeCall = call
            val response = call.execute()
            val code = response.code

            // Handle HTTP 416 (Range Not Satisfiable)
            if (code == 416) {
                response.close()
                if (existingBytes == persistedTotal && persistedTotal > 0) {
                    finalizeDownload(partFile, finalDest, model, downloadListener)
                    return
                } else {
                    // Reset and start over
                    partFile.delete()
                    existingBytes = 0L
                    startDownload(model, finalDest, downloadListener, isResuming = false)
                    return
                }
            }

            if (!response.isSuccessful) {
                val errorMsg = "HTTP Error $code: ${response.message}"
                response.close()
                mainHandler.post { downloadListener.onDownloadError(errorMsg) }
                return
            }

            val eTag = response.header("ETag") ?: persistedETag
            val lastModified = response.header("Last-Modified")

            val totalBytes: Long
            val appendMode: Boolean

            if (code == 206) {
                // Partial Content accepted by server
                appendMode = true
                val contentRange = response.header("Content-Range")
                totalBytes = if (contentRange != null && contentRange.contains("/")) {
                    contentRange.substringAfter("/").toLongOrNull() ?: model.expectedSizeBytes
                } else {
                    val bodyLen = response.body?.contentLength() ?: 0L
                    existingBytes + bodyLen
                }
            } else {
                // HTTP 200: Server did not return partial content (doesn't support range or resource updated)
                appendMode = false
                existingBytes = 0L
                partFile.delete()
                totalBytes = response.body?.contentLength() ?: model.expectedSizeBytes
            }

            currentDownloaded.set(existingBytes)
            saveMetadata(model, existingBytes, totalBytes, eTag, lastModified)
            mainHandler.post { downloadListener.onDownloadStarted(totalBytes) }

            randomAccessFile = RandomAccessFile(partFile, "rw")
            if (appendMode) {
                randomAccessFile.seek(existingBytes)
            } else {
                randomAccessFile.setLength(0)
            }

            val body = response.body ?: throw Exception("Empty response body from server")
            inputStream = body.byteStream()

            val buffer = ByteArray(262144) // 256KB buffer for smooth I/O
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled.get()) break
                if (isPaused.get()) {
                    mainHandler.post { downloadListener.onDownloadPaused(currentDownloaded.get(), totalBytes) }
                    break
                }
                if (isWaitingForNetwork.get()) {
                    break
                }

                randomAccessFile.write(buffer, 0, bytesRead)
                val totalCurrent = currentDownloaded.addAndGet(bytesRead.toLong())
                speedTracker.addSample(totalCurrent)

                // Periodic UI progress update ~800ms
                val now = System.currentTimeMillis()
                if (now - lastUiUpdate >= UI_THROTTLE_MS) {
                    lastUiUpdate = now
                    saveMetadata(model, totalCurrent, totalBytes, eTag, lastModified)
                    
                    val percent = if (totalBytes > 0) ((totalCurrent * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                    val speedStr = speedTracker.getFormattedSpeed()
                    val remainingBytes = (totalBytes - totalCurrent).coerceAtLeast(0)
                    val etaStr = speedTracker.getFormattedEta(remainingBytes)
                    val statusText = "Downloading: ${DownloadSpeedTracker.formatBytes(totalCurrent)} / ${DownloadSpeedTracker.formatBytes(totalBytes)}"

                    val info = DownloadProgressInfo(
                        downloadedBytes = totalCurrent,
                        totalBytes = totalBytes,
                        percent = percent,
                        speedLabel = speedStr,
                        etaLabel = etaStr,
                        statusText = statusText
                    )
                    mainHandler.post { downloadListener.onDownloadProgress(info) }
                }
            }

            response.close()

            if (!isCancelled.get() && !isPaused.get() && !isWaitingForNetwork.get()) {
                if (currentDownloaded.get() >= totalBytes && totalBytes > 0) {
                    finalizeDownload(partFile, finalDest, model, downloadListener)
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "Download failed or disconnected", e)
            if (!isCancelled.get() && !isPaused.get()) {
                isWaitingForNetwork.set(true)
                mainHandler.post { downloadListener.onWaitingForNetwork() }
            }
        } finally {
            activeCall = null
            try { inputStream?.close() } catch (e: Exception) {}
            try { randomAccessFile?.close() } catch (e: Exception) {}
        }
    }

    private fun finalizeDownload(
        partFile: File,
        finalDest: File,
        model: LocalModel,
        downloadListener: ResumableDownloadListener
    ) {
        try {
            if (finalDest.exists()) finalDest.delete()
            val renamed = partFile.renameTo(finalDest)
            if (renamed || finalDest.exists()) {
                clearMetadata(model)
                mainHandler.post { downloadListener.onDownloadCompleted(finalDest) }
            } else {
                mainHandler.post { downloadListener.onDownloadError("Failed to rename partial file to final model destination.") }
            }
        } catch (e: Exception) {
            mainHandler.post { downloadListener.onDownloadError("Finalization error: ${e.localizedMessage}") }
        }
    }

    fun pauseDownload() {
        isPaused.set(true)
        try { activeCall?.cancel() } catch (_: Exception) {}
        val model = currentModel
        if (model != null) {
            saveMetadata(model, currentDownloaded.get(), getPersistedTotalBytes(model), null, null)
        }
    }

    fun resumeDownload() {
        val model = currentModel ?: return
        val dest = destinationFile ?: return
        val lis = listener ?: return
        startDownload(model, dest, lis, isResuming = true)
    }

    fun cancelDownload() {
        isCancelled.set(true)
        isPaused.set(false)
        isWaitingForNetwork.set(false)
        try { activeCall?.cancel() } catch (_: Exception) {}
        try {
            downloadFuture?.cancel(true)
            executor.shutdownNow()
        } catch (e: Exception) {}
        currentDownloaded.set(0)
    }

    fun unregister() {
        try {
            if (networkCallback != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            }
        } catch (e: Exception) {}
    }
}

