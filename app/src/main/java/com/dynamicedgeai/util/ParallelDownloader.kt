package com.dynamicedgeai.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ParallelDownloader {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val NUM_SEGMENTS = 8
    private var executor = Executors.newFixedThreadPool(NUM_SEGMENTS + 1) // +1 for coordinator
    private var isCancelled = false
    private var isPaused = false
    
    private val downloadedBytes = AtomicLong(0)
    private var totalBytes = 0L
    private var currentUrl: String? = null
    private var currentDestination: File? = null
    private var currentListener: DownloadListener? = null
    
    private val segmentFutures = mutableListOf<Future<*>>()
    private var segmentProgress: LongArray = LongArray(NUM_SEGMENTS)

    // Throttle UI progress updates to every 200ms
    private var lastProgressUpdate = 0L
    private val PROGRESS_THROTTLE_MS = 200L

    interface DownloadListener {
        fun onStart(totalSize: Long)
        fun onProgress(progress: Int, speed: String)
        fun onPaused()
        fun onResumed()
        fun onComplete(file: File)
        fun onError(error: String)
    }

    fun download(url: String, destination: File, listener: DownloadListener, resume: Boolean = false) {
        this.currentUrl = url
        this.currentDestination = destination
        this.currentListener = listener
        
        isCancelled = false
        isPaused = false
        
        executor.execute {
            try {
                // 1. Get file size with HEAD request (faster than GET)
                val headRequest = Request.Builder().url(url).head().build()
                val headResponse = client.newCall(headRequest).execute()
                totalBytes = headResponse.header("Content-Length")?.toLongOrNull() ?: -1L
                headResponse.close()

                // Fallback: try GET if HEAD didn't give content-length
                if (totalBytes <= 0) {
                    val getRequest = Request.Builder().url(url).build()
                    val getResponse = client.newCall(getRequest).execute()
                    if (!getResponse.isSuccessful) throw Exception("Server error: ${getResponse.code}")
                    totalBytes = getResponse.body?.contentLength() ?: -1L
                    getResponse.close()
                }

                if (totalBytes <= 0) {
                    Handler(Looper.getMainLooper()).post { listener.onError("Cannot determine file size. Resumable download not supported.") }
                    return@execute
                }

                Handler(Looper.getMainLooper()).post { listener.onStart(totalBytes) }

                // 2. Initialize or Resume segments
                val segmentSize = totalBytes / NUM_SEGMENTS
                if (!resume || downloadedBytes.get() == 0L) {
                    downloadedBytes.set(0)
                    segmentProgress = LongArray(NUM_SEGMENTS)
                    for (i in 0 until NUM_SEGMENTS) {
                        segmentProgress[i] = i * segmentSize
                    }
                    // Create fresh file
                    val raf = RandomAccessFile(destination, "rw")
                    raf.setLength(totalBytes)
                    raf.close()
                }

                startSegments()

            } catch (e: Exception) {
                if (!isCancelled) Handler(Looper.getMainLooper()).post { listener.onError(e.localizedMessage ?: "Download error") }
            }
        }
    }

    private fun startSegments() {
        val url = currentUrl ?: return
        val destination = currentDestination ?: return
        val segmentSize = totalBytes / NUM_SEGMENTS
        
        segmentFutures.clear()
        for (i in 0 until NUM_SEGMENTS) {
            val start = segmentProgress[i]
            val end = if (i == NUM_SEGMENTS - 1) totalBytes - 1 else (i + 1) * segmentSize - 1
            if (start <= end) {
                segmentFutures.add(executor.submit { 
                    downloadSegment(url, destination, i, start, end) 
                })
            }
        }
    }

    private fun downloadSegment(url: String, file: File, index: Int, start: Long, end: Long) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=$start-$end")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body ?: return
                val inputStream: InputStream = body.byteStream()
                val raf = RandomAccessFile(file, "rw")
                raf.seek(start)

                // 256KB buffer for much faster I/O
                val buffer = ByteArray(262144)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled || isPaused) break
                    raf.write(buffer, 0, bytesRead)
                    segmentProgress[index] += bytesRead.toLong()
                    val current = downloadedBytes.addAndGet(bytesRead.toLong())
                    
                    // Throttle progress updates to avoid flooding the UI thread
                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate >= PROGRESS_THROTTLE_MS) {
                        lastProgressUpdate = now
                        val progress = (current * 100 / totalBytes).toInt().coerceIn(0, 100)
                        Handler(Looper.getMainLooper()).post { currentListener?.onProgress(progress, "") }
                    }
                }
                raf.close()
                checkCompletion()
            }
        } catch (e: Exception) {
            if (!isCancelled && !isPaused) {
                isCancelled = true
                Handler(Looper.getMainLooper()).post { currentListener?.onError("Network lost. Tap Retry to Resume.") }
            }
        }
    }

    @Synchronized
    private fun checkCompletion() {
        if (!isCancelled && !isPaused && downloadedBytes.get() >= totalBytes) {
            Handler(Looper.getMainLooper()).post { currentListener?.onComplete(currentDestination!!) }
        }
    }

    fun pause() { isPaused = true; currentListener?.onPaused() }
    fun resume() { if (!isPaused) return; isPaused = false; segmentFutures.clear(); currentListener?.onResumed(); startSegments() }
    fun cancel() { isCancelled = true; isPaused = false; executor.shutdownNow(); executor = Executors.newFixedThreadPool(NUM_SEGMENTS + 1); downloadedBytes.set(0) }
}
