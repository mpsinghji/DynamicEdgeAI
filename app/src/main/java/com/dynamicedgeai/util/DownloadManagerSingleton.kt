package com.dynamicedgeai.util

import android.content.Context
import com.dynamicedgeai.local.LocalModel
import com.dynamicedgeai.service.DownloadForegroundService
import java.io.File

/**
 * Application-level singleton that coordinates resumable model downloads,
 * live speed tracking, network switching resilience, and ForegroundService lifecycle.
 */
object DownloadManagerSingleton {

    data class ActiveDownload(
        val model: LocalModel,
        val resumeManager: DownloadResumeManager,
        var status: ModelStatus,
        var progress: Int,
        var speedLabel: String = "0 KB/s",
        var etaLabel: String = "--",
        var downloadSizeText: String = "",
        var statusText: String = "Downloading...",
        var isPaused: Boolean = false,
        var errorMessage: String? = null
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
        onProgress: (DownloadProgressInfo) -> Unit,
        onPaused: () -> Unit,
        onResumed: () -> Unit,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        // Cancel any existing download for this model
        activeDownloads[model]?.resumeManager?.cancelDownload()

        val resumeManager = DownloadResumeManager(context)
        val activeDl = ActiveDownload(
            model = model,
            resumeManager = resumeManager,
            status = ModelStatus.DOWNLOADING,
            progress = 0
        )
        activeDownloads[model] = activeDl

        DownloadForegroundService.start(context)

        resumeManager.startDownload(model, destination, object : ResumableDownloadListener {
            override fun onDownloadStarted(totalBytes: Long) {
                activeDl.status = ModelStatus.DOWNLOADING
                activeDl.statusText = "Starting download..."
            }

            override fun onDownloadProgress(info: DownloadProgressInfo) {
                activeDl.progress = info.percent
                activeDl.speedLabel = info.speedLabel
                activeDl.etaLabel = info.etaLabel
                activeDl.downloadSizeText = info.statusText
                activeDl.statusText = "Downloading..."
                activeDl.status = ModelStatus.DOWNLOADING
                onProgress(info)
            }

            override fun onDownloadPaused(downloadedBytes: Long, totalBytes: Long) {
                activeDl.isPaused = true
                activeDl.status = ModelStatus.PAUSED
                activeDl.statusText = "Paused"
                activeDl.speedLabel = "0 KB/s"
                onPaused()
            }

            override fun onWaitingForNetwork() {
                activeDl.statusText = "Waiting for network..."
                activeDl.speedLabel = "0 KB/s"
                activeDl.etaLabel = "--"
            }

            override fun onDownloadCompleted(destinationFile: File) {
                activeDownloads.remove(model)
                DownloadForegroundService.stopIfNoActiveDownloads(context)
                onComplete(destinationFile)
            }

            override fun onDownloadError(errorMessage: String) {
                activeDl.status = ModelStatus.NOT_DOWNLOADED
                activeDl.errorMessage = errorMessage
                activeDownloads.remove(model)
                DownloadForegroundService.stopIfNoActiveDownloads(context)
                onError(errorMessage)
            }
        }, isResuming = resume)
    }

    fun pauseDownload(model: LocalModel) {
        activeDownloads[model]?.resumeManager?.pauseDownload()
    }

    fun resumeDownload(model: LocalModel) {
        activeDownloads[model]?.resumeManager?.resumeDownload()
    }

    fun cancelDownload(context: Context, model: LocalModel) {
        activeDownloads[model]?.let {
            it.resumeManager.cancelDownload()
            activeDownloads.remove(model)
            DownloadForegroundService.stopIfNoActiveDownloads(context)
        }
    }
}
