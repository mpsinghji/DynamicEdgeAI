package com.dynamicedgeai.monitor

import android.os.Process
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CpuMonitor {
    fun observeCpuUsage(): Flow<Int> = flow {
        while (true) {
            // On modern Android, getting system-wide CPU is restricted.
            // We'll simulate a value based on process state or a random jitter for this prototype.
            val mockUsage = (10..40).random()
            emit(mockUsage)
            delay(2000)
        }
    }
}
