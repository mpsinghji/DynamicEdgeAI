package com.dynamicedgeai.monitor

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RamMonitor(private val context: Context) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    // We use a virtual offset for simulation so we don't actually crash the app with OOM
    private var simulatedConsumedMB = 0L

    fun observeRamUsage(): Flow<Pair<Long, Long>> = flow {
        while (true) {
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val totalMB = memoryInfo.totalMem / (1024 * 1024)
            val actualAvailableMB = memoryInfo.availMem / (1024 * 1024)
            
            // Apply simulation: Subtract the 'eaten' amount from reported free RAM
            val availableMB = (actualAvailableMB - simulatedConsumedMB).coerceAtLeast(0)
            
            emit(Pair(availableMB, totalMB))
            delay(2000) // Slightly faster updates for better feedback
        }
    }

    /**
     * Virtually consumes RAM to test threshold triggers
     */
    fun eatRam(amountMB: Int) {
        simulatedConsumedMB += amountMB
    }

    /**
     * Resets simulated consumption
     */
    fun freeRam() {
        simulatedConsumedMB = 0
    }
}
