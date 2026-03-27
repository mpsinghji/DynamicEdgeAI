package com.dynamicedgeai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dynamicedgeai.R
import com.dynamicedgeai.util.DownloadManagerSingleton

/**
 * Foreground service that keeps downloads alive when the app is minimized.
 * 
 * Starts when any model download begins, stops when all downloads finish.
 * Shows a persistent notification with download status.
 */
class DownloadForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.dynamicedgeai.START_DOWNLOAD_SERVICE"
        private const val ACTION_STOP = "com.dynamicedgeai.STOP_DOWNLOAD_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun stopIfNoActiveDownloads(context: Context) {
            if (DownloadManagerSingleton.getAllActive().isEmpty()) {
                stop(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val notification = buildNotification("Downloading model...")
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Dynamic Edge AI")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while downloading AI models"
                setShowBadge(false)
            }
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
