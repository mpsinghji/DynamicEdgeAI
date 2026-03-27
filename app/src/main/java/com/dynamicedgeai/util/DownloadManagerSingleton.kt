package com.dynamicedgeai.util

import android.content.Context
import com.dynamicedgeai.local.LocalModel
import com.dynamicedgeai.service.DownloadForegroundService
import java.io.File

/**
 * Application-level singleton that keeps download state alive across activity re-creation.
 * Downloads survive when user navigates back from ModelManagerActivity or minimizes the app.
 * Automatically starts/stops a ForegroundService to keep downloads alive in background.
 */
object DownloadManagerSingleton {

    data class ActiveDownload(
        val model: LocalModel,
        val downloader: ParallelDownloader,
        var status: ModelStatus,
        var progress: Int,
        var isPaused: Boolean,
        var errorMessage: String?
    )

    private val activeDownloads = mutableMapOf<LocalModel, ActiveDownload>()

    fun getActiveDownload(model: LocalModel): ActiveDownload? = activeDownloads[model]

    fun getAllActive(): Map<LocalModel, ActiveDownload> = activeDownloads.toMap()

    fun isDownloading(model: LocalModel): Boolean {
        val dl = activeDownloads[model] ?: return false
        return dl.status == ModelStatus.DOWNLOADING || dl.status == ModelStatus.PAUSED
    }

    fun hasAnyActiveDownload(): Boolean = activeDownloads.isNotEmpty()

    fun startDownload(
        context: Context,
        model: LocalModel,
        destination: File,
        resume: Boolean,
        onProgress: (Int) -> Unit,
        onPaused: () -> Unit,
        onResumed: () -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        // Cancel any existing download for this model
        activeDownloads[model]?.downloader?.cancel()

        val tempDestination = File(destination.absolutePath + ".tmp")

        if (!resume) {
            tempDestination.parentFile?.mkdirs()
            if (tempDestination.exists()) tempDestination.delete()
            if (destination.exists()) destination.delete()
        }

        val downloader = ParallelDownloader()
        val activeDl = ActiveDownload(model, downloader, ModelStatus.DOWNLOADING, 0, false, null)
        activeDownloads[model] = activeDl

        // Start foreground service to keep download alive in background
        DownloadForegroundService.start(context)

        downloader.download(model.downloadUrl, tempDestination, object : ParallelDownloader.DownloadListener {
            override fun onStart(totalSize: Long) {
                activeDl.status = ModelStatus.DOWNLOADING
            }

            override fun onProgress(progress: Int, speed: String) {
                activeDl.progress = progress
                onProgress(progress)
            }

            override fun onPaused() {
                activeDl.isPaused = true
                activeDl.status = ModelStatus.PAUSED
                onPaused()
            }

            override fun onResumed() {
                activeDl.isPaused = false
                activeDl.status = ModelStatus.DOWNLOADING
                onResumed()
            }

            override fun onComplete(file: File) {
                // Rename .tmp file to final destination
                if (file.exists()) {
                    if (destination.exists()) destination.delete()
                    file.renameTo(destination)
                }
                activeDownloads.remove(model)
                DownloadForegroundService.stopIfNoActiveDownloads(context)
                onComplete(destination)
            }

            override fun onError(error: String) {
                activeDl.status = ModelStatus.NOT_DOWNLOADED
                activeDl.errorMessage = error
                activeDownloads.remove(model)
                DownloadForegroundService.stopIfNoActiveDownloads(context)
                onError(error)
            }
        }, resume)
    }

    fun pauseDownload(model: LocalModel) {
        activeDownloads[model]?.downloader?.pause()
    }

    fun resumeDownload(model: LocalModel) {
        activeDownloads[model]?.downloader?.resume()
    }

    fun cancelDownload(context: Context, model: LocalModel) {
        activeDownloads[model]?.let {
            it.downloader.cancel()
            activeDownloads.remove(model)
            DownloadForegroundService.stopIfNoActiveDownloads(context)
        }
    }
}
