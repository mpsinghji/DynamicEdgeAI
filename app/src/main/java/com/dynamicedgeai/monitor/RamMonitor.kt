package com.dynamicedgeai.monitor

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RamMonitor(private val context: Context) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun observeRamUsage(): Flow<Long> = flow {
        while (true) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            val availableMB = memoryInfo.availMem / (1024 * 1024)
            emit(availableMB)
            delay(3000) // Update every 3 seconds
        }
    }
}
