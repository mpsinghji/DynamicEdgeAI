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
import java.util.concurrent.atomic.AtomicLong

class ParallelDownloader {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
        
    private val executor = Executors.newFixedThreadPool(8)
    private var isCancelled = false
    private val downloadedBytes = AtomicLong(0)
    private var totalBytes = 0L

    interface DownloadListener {
        fun onStart(totalSize: Long)
        fun onProgress(progress: Int, speed: String)
        fun onComplete(file: File)
        fun onError(error: String)
    }

    fun download(url: String, destination: File, listener: DownloadListener) {
        isCancelled = false
        downloadedBytes.set(0)
        
        executor.execute {
            try {
                Log.d("ParallelDownloader", "Starting download from $url to ${destination.absolutePath}")
                
                // 1. Get file size
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    throw Exception("Server returned code ${response.code}")
                }
                
                totalBytes = response.body?.contentLength() ?: -1L
                response.close()

                if (totalBytes <= 0) {
                    Handler(Looper.getMainLooper()).post { listener.onError("Cannot determine file size. Server might not support parallel download.") }
                    return@execute
                }

                Log.d("ParallelDownloader", "File size: $totalBytes bytes")
                Handler(Looper.getMainLooper()).post { listener.onStart(totalBytes) }

                // 2. Prepare file placeholder
                // Ensure parent directory exists and is writable
                val parent = destination.parentFile
                if (parent != null && !parent.exists()) {
                    if (!parent.mkdirs()) {
                        throw Exception("Failed to create directory ${parent.absolutePath}")
                    }
                }

                val raf = RandomAccessFile(destination, "rw")
                raf.setLength(totalBytes)
                raf.close()

                // 3. Start 4 segments
                val numSegments = 4
                val segmentSize = totalBytes / numSegments
                val futures = mutableListOf<java.util.concurrent.Future<*>>()

                for (i in 0 until numSegments) {
                    val start = i * segmentSize
                    val end = if (i == numSegments - 1) totalBytes - 1 else (i + 1) * segmentSize - 1
                    futures.add(executor.submit { downloadSegment(url, destination, start, end, listener) })
                }

                // Wait for all segments
                for (future in futures) {
                    future.get()
                }

                if (!isCancelled) {
                    Log.d("ParallelDownloader", "Download complete: ${destination.absolutePath}")
                    Handler(Looper.getMainLooper()).post { listener.onComplete(destination) }
                }
            } catch (e: Exception) {
                Log.e("ParallelDownloader", "Download error", e)
                if (!isCancelled) {
                    Handler(Looper.getMainLooper()).post { listener.onError(e.localizedMessage ?: "Download error") }
                }
            }
        }
    }

    private fun downloadSegment(url: String, file: File, start: Long, end: Long, listener: DownloadListener) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Range", "bytes=$start-$end")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code != 206 && response.code != 200) {
                    throw Exception("Segment failed with code ${response.code}")
                }
                
                val body = response.body ?: throw Exception("No response body")
                val inputStream: InputStream = body.byteStream()
                val raf = RandomAccessFile(file, "rw")
                raf.seek(start)

                val buffer = ByteArray(65536)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled) break
                    raf.write(buffer, 0, bytesRead)
                    val current = downloadedBytes.addAndGet(bytesRead.toLong())
                    val progress = (current * 100 / totalBytes).toInt()
                    Handler(Looper.getMainLooper()).post { listener.onProgress(progress, "") }
                }
                raf.close()
            }
        } catch (e: Exception) {
            Log.e("ParallelDownloader", "Segment error at range $start-$end", e)
            isCancelled = true
            Handler(Looper.getMainLooper()).post { listener.onError("Segment failed: ${e.message}") }
        }
    }

    fun cancel() {
        isCancelled = true
    }
}
