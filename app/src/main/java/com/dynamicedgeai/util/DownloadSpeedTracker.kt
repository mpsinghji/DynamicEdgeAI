package com.dynamicedgeai.util

import java.util.LinkedList
import java.util.Locale

class DownloadSpeedTracker {

    private data class Sample(val timestamp: Long, val bytes: Long)

    private val samples = LinkedList<Sample>()
    private val WINDOW_MS = 3000L // 3-second rolling window

    @Synchronized
    fun addSample(downloadedBytes: Long) {
        val now = System.currentTimeMillis()
        samples.addLast(Sample(now, downloadedBytes))

        // Evict samples older than WINDOW_MS
        while (samples.size > 1 && (now - samples.first().timestamp) > WINDOW_MS) {
            samples.removeFirst()
        }
    }

    @Synchronized
    fun reset() {
        samples.clear()
    }

    @Synchronized
    fun getCurrentSpeedBytesPerSec(): Double {
        if (samples.size < 2) return 0.0
        val oldest = samples.first()
        val newest = samples.last()
        val timeDiffSec = (newest.timestamp - oldest.timestamp) / 1000.0
        if (timeDiffSec <= 0.05) return 0.0
        val byteDiff = newest.bytes - oldest.bytes
        return if (byteDiff > 0) byteDiff / timeDiffSec else 0.0
    }

    fun getFormattedSpeed(): String {
        val bytesPerSec = getCurrentSpeedBytesPerSec()
        if (bytesPerSec <= 0.0) return "0 KB/s"
        val kb = bytesPerSec / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB/s", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB/s", kb)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    fun getFormattedEta(remainingBytes: Long): String {
        val speed = getCurrentSpeedBytesPerSec()
        if (speed <= 1024.0 || remainingBytes <= 0) return "--"
        val seconds = (remainingBytes / speed).toLong()
        return when {
            seconds < 60 -> "$seconds sec"
            seconds < 3600 -> {
                val mins = seconds / 60
                val secs = seconds % 60
                "$mins min $secs sec"
            }
            else -> {
                val hours = seconds / 3600
                val mins = (seconds % 3600) / 60
                "$hours hr $mins min"
            }
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(Locale.US, "%.2f GB", mb / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }
    }
}
